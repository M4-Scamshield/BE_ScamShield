package scamshield.pipeline.access;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 통화별 토큰. callId 도 서버가 무작위로 만든다 — callId 만 알아서는 남의 통화를 못 본다.
 * 토큰은 Authorization: Bearer 헤더, 또는 ?token= (EventSource·WebSocket 은 헤더를 못 붙인다).
 */
@Component
public class CallAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final long ttlSeconds;

    public CallAuth(JdbcTemplate db, @Value("${scamshield.access.token-ttl-hours}") long ttlHours) {
        this.db = db;
        this.ttlSeconds = ttlHours * 3600;
    }

    public record Issued(String callId, String token, long expiresInSeconds) {}

    public Issued issue() {
        String callId = UUID.randomUUID().toString();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // 만료 시각은 DB 시계로 (앱·DB 시계 차이를 피한다)
        db.update("insert into call_session(call_id, token_hash, expires_at) values (?, ?, now(6) + interval ? second)",
                callId, sha256(token), ttlSeconds);
        return new Issued(callId, token, ttlSeconds);
    }

    public boolean check(String callId, String token) {
        if (callId == null || token == null || token.isBlank()) return false;
        List<byte[]> rows = db.queryForList(
                "select token_hash from call_session where call_id = ? and expires_at > now(6)", byte[].class, callId);
        return !rows.isEmpty() && MessageDigest.isEqual(rows.get(0), sha256(token));
    }

    /** 맞지 않으면 401. 통화가 없는 것과 토큰이 틀린 것을 구분해 알려 주지 않는다. */
    public void require(String callId, HttpServletRequest req) {
        if (!check(callId, tokenOf(req))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public static String tokenOf(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).strip();
        return req.getParameter("token");
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

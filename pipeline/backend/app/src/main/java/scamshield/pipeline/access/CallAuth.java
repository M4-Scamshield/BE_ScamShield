package scamshield.pipeline.access;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 통화별 토큰. callId 도 서버가 무작위로 만든다 — callId 만 알아서는 남의 통화를 못 본다.
 * 방(room) 을 주면 같은 방의 참가자는 같은 callId 에 붙고, 토큰은 참가자마다 따로 받는다.
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
        return issue(null);
    }

    /** room 이 없으면 새 통화, 있으면 그 방의 통화를 찾고 없으면 만든다 (동시에 들어와도 하나만 생긴다). */
    public Issued issue(String room) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] hash = sha256(token);
        String callId = UUID.randomUUID().toString();
        // 만료 시각은 DB 시계로 (앱·DB 시계 차이를 피한다)
        if (room == null) {
            db.update("insert into call_session(call_id, token_hash, expires_at) values (?, ?, now(6) + interval ? second)",
                    callId, hash, ttlSeconds);
        } else {
            db.update("insert into call_session(call_id, token_hash, expires_at, room_key) values (?, ?, now(6) + interval ? second, ?)"
                            + " on duplicate key update call_id = call_id",
                    callId, hash, ttlSeconds, room);
            callId = db.queryForObject("select call_id from call_session where room_key = ?", String.class, room);
        }
        db.update("insert into call_token(token_hash, call_id, expires_at) values (?, ?, now(6) + interval ? second)",
                hash, callId, ttlSeconds);
        return new Issued(callId, token, ttlSeconds);
    }

    public boolean check(String callId, String token) {
        if (callId == null || token == null || token.isBlank()) return false;
        Integer n = db.queryForObject(
                "select count(*) from call_token where call_id = ? and token_hash = ? and expires_at > now(6)",
                Integer.class, callId, sha256(token));
        return n != null && n > 0;
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

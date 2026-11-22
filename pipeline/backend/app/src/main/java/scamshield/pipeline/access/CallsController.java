package scamshield.pipeline.access;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import scamshield.pipeline.common.ApiRole;

/**
 * POST /api/analysis/calls  { "room": "<시그널링 callId>" }(선택) → { callId, token, expiresInSeconds }
 * 통화방 참가자마다 한 번 부른다. 같은 room 이면 같은 callId(분석 하나)에 붙고 토큰은 각자 받는다.
 * 각 브라우저는 자기 마이크만 올리고, 결과(SSE)는 모두가 같이 받는다. room 이 없으면 혼자 쓰는 새 통화.
 * 계정이 없어 누구나 부를 수 있으므로 IP 별 속도 제한을 건다 (429).
 */
@RestController
@ApiRole
public class CallsController {

    private static final Pattern ROOM = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public record CreateRequest(String room) {}

    private final CallAuth auth;
    private final CreateLimiter limiter;

    public CallsController(CallAuth auth, CreateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/api/analysis/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallAuth.Issued create(HttpServletRequest req, @RequestBody(required = false) CreateRequest body) {
        String room = body == null ? null : body.room();
        if (room != null && !ROOM.matcher(room).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "room 형식이 틀렸다");
        if (!limiter.allow(req.getRemoteAddr()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "통화 생성이 너무 잦다");
        return auth.issue(room);
    }
}

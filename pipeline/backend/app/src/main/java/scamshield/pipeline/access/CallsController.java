package scamshield.pipeline.access;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import scamshield.pipeline.common.ApiRole;

/**
 * POST /api/analysis/calls → { callId, token, expiresInSeconds }
 * 분석을 켜는 브라우저(보호받는 사람)가 통화마다 한 번 부른다. 이 브라우저가 화자별 음성을 올리고 SSE 를 받는다.
 * 계정이 없어 누구나 부를 수 있으므로 IP 별 속도 제한을 건다 (429).
 */
@RestController
@ApiRole
public class CallsController {

    private final CallAuth auth;
    private final CreateLimiter limiter;

    public CallsController(CallAuth auth, CreateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/api/analysis/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallAuth.Issued create(HttpServletRequest req) {
        if (!limiter.allow(req.getRemoteAddr()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "통화 생성이 너무 잦다");
        return auth.issue();
    }
}

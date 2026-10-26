package scamshield.pipeline.access;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import scamshield.pipeline.common.ApiRole;

/**
 * POST /api/analysis/calls → { callId, token, expiresInSeconds }
 * 분석을 켜는 브라우저(보호받는 사람)가 통화마다 한 번 부른다. 이 브라우저가 화자별 음성을 올리고 SSE 를 받는다.
 */
@RestController
@ApiRole
public class CallsController {

    private final CallAuth auth;

    public CallsController(CallAuth auth) {
        this.auth = auth;
    }

    @PostMapping("/api/analysis/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallAuth.Issued create() {
        return auth.issue();
    }
}

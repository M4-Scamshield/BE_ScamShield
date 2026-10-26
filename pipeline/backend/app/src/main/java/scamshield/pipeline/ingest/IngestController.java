package scamshield.pipeline.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import scamshield.pipeline.access.CallAuth;
import scamshield.pipeline.common.ApiRole;

/**
 * POST /api/analysis/calls/{callId}/inputs : 사용자 입력(ACQUIRE 에 대한 답). 프론트 계약(FE2). 통화 토큰 필요.
 * 개발용 입구는 DevIngestController.
 */
@RestController
@ApiRole
public class IngestController {

    private final UtterancePublisher publisher;
    private final CallAuth auth;

    public IngestController(UtterancePublisher publisher, CallAuth auth) {
        this.publisher = publisher;
        this.auth = auth;
    }

    public record InputRequest(String inputType, String value, Long replyToEvent) {}

    @PostMapping("/api/analysis/calls/{callId}/inputs")
    public ResponseEntity<Map<String, String>> input(@PathVariable String callId, @RequestBody InputRequest r,
                                                     HttpServletRequest req) {
        auth.require(callId, req);
        if (r.value() == null || r.value().isBlank()) return ResponseEntity.badRequest().build();
        String id = publisher.publishInput(callId, r.inputType() == null ? "TEXT" : r.inputType(),
                r.value(), r.replyToEvent());
        return ResponseEntity.accepted().body(Map.of("eventId", id));
    }
}

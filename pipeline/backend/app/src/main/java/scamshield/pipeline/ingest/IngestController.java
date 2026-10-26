package scamshield.pipeline.ingest;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import scamshield.pipeline.common.ApiRole;

/**
 * HTTP 입구 두 개.
 *  - POST /api/calls/{callId}/inputs        : 사용자 입력(ACQUIRE 에 대한 답). 프론트 계약(FE2)
 *  - POST /api/dev/calls/{callId}/utterances : 개발용. STT 확정 발화를 직접 넣는다(가짜 STT 대신 텍스트)
 */
@RestController
@ApiRole
public class IngestController {

    private final UtterancePublisher publisher;

    public IngestController(UtterancePublisher publisher) {
        this.publisher = publisher;
    }

    public record InputRequest(String inputType, String value, Long replyToEvent) {}

    @PostMapping("/api/calls/{callId}/inputs")
    public ResponseEntity<Map<String, String>> input(@PathVariable String callId, @RequestBody InputRequest r) {
        if (r.value() == null || r.value().isBlank()) return ResponseEntity.badRequest().build();
        String id = publisher.publishInput(callId, r.inputType() == null ? "TEXT" : r.inputType(),
                r.value(), r.replyToEvent());
        return ResponseEntity.accepted().body(Map.of("eventId", id));
    }

    public record DevUtterance(String trackId, String text) {}

    @PostMapping("/api/dev/calls/{callId}/utterances")
    public ResponseEntity<Map<String, String>> devUtterance(@PathVariable String callId, @RequestBody DevUtterance r) {
        Instant now = Instant.now();
        String id = publisher.publishSpeech(callId, r.trackId() == null ? "caller" : r.trackId(), 0,
                new SttClient.Segment(null, 0, 0, r.text(), null, now), now);
        return ResponseEntity.accepted().body(Map.of("eventId", id));
    }
}

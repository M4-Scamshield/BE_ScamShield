package scamshield.pipeline.ingest;

import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import scamshield.pipeline.common.ApiRole;

/**
 * POST /api/dev/calls/{callId}/utterances : 개발용. STT 확정 발화를 직접 넣는다(가짜 STT 대신 텍스트).
 * 토큰 없이 아무 callId 에나 넣을 수 있어서 scamshield.dev.enabled=true 일 때만 뜬다 (배포에서는 끈다).
 */
@RestController
@ApiRole
@ConditionalOnProperty(name = "scamshield.dev.enabled", havingValue = "true")
public class DevIngestController {

    private final UtterancePublisher publisher;

    public DevIngestController(UtterancePublisher publisher) {
        this.publisher = publisher;
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

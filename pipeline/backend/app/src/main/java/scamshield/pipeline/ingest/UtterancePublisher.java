package scamshield.pipeline.ingest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import scamshield.pipeline.common.UtteranceEvent;

/**
 * 발화·사용자 입력을 Kafka 토픽 utterance 에 넣는다. 키 = callId 고정.
 * 브로커 확인(acks=all)까지 기다린다 — 확인 전에 성공이라고 답하지 않는다.
 */
@Component
public class UtterancePublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public UtterancePublisher(KafkaTemplate<String, String> kafka, ObjectMapper json,
                              @Value("${scamshield.kafka.topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    public String publishSpeech(String callId, String trackId, int streamEpoch,
                                SttClient.Segment s, Instant capturedAt) {
        return send(new UtteranceEvent(UUID.randomUUID().toString(), UtteranceEvent.SPEECH, callId, trackId,
                streamEpoch, s.segmentId(), 1, s.startMs(), s.endMs(), s.text(), true, s.confidence(),
                null, null, capturedAt, s.finalAt(), Instant.now()));
    }

    public String publishInput(String callId, String inputType, String value, Long replyToEvent) {
        Instant now = Instant.now();
        return send(new UtteranceEvent(UUID.randomUUID().toString(), UtteranceEvent.INPUT, callId, "USER",
                0, null, 1, null, null, value, true, null, inputType, replyToEvent, now, now, now));
    }

    private String send(UtteranceEvent e) {
        try {
            kafka.send(topic, e.callId(), json.writeValueAsString(e)).get(10, TimeUnit.SECONDS);
            return e.eventId();
        } catch (JacksonException | ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Kafka 발행 실패 callId=" + e.callId(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}

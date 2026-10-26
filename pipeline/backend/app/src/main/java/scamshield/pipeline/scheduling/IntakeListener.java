package scamshield.pipeline.scheduling;

import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import scamshield.pipeline.common.ApiRole;
import scamshield.pipeline.common.UtteranceEvent;

/**
 * Kafka → 접수. 커밋(T1)이 끝난 뒤에만 오프셋을 넘긴다.
 * 실패하면 ack 하지 않는다 → 기본 오류 처리기가 같은 레코드를 다시 준다(event_id 로 멱등).
 */
@Component
@ApiRole
public class IntakeListener {

    private final IntakeService intake;
    private final ObjectMapper json;

    public IntakeListener(IntakeService intake, ObjectMapper json) {
        this.intake = intake;
        this.json = json;
    }

    @KafkaListener(topics = "${scamshield.kafka.topic}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        intake.accept(json.readValue(record.value(), UtteranceEvent.class));
        ack.acknowledge();
    }
}

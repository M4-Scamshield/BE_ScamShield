package scamshield.pipeline.scheduling;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TopicConfig {

    /** 파티션 수 = 동시 처리 상한. 키가 callId 라 늘리면 통화→파티션 배정이 바뀐다(진행 중 통화 순서 주의). */
    @Bean
    NewTopic utteranceTopic(@Value("${scamshield.kafka.topic}") String topic,
                            @Value("${scamshield.kafka.partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}

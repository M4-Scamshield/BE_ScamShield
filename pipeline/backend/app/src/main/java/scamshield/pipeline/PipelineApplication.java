package scamshield.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 모듈러 모놀리스. 패키지 = 모듈 (명세 3절).
 *   ingest → (Kafka) → scheduling → (PG 작업) → analysis → result → (PG outbox) → delivery
 * 모듈끼리는 Kafka 이벤트·PG 테이블·포트 인터페이스로만 만난다.
 */
@SpringBootApplication
@EnableScheduling
public class PipelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}

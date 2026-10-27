package scamshield.pipeline.common;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** api 역할(업로드 WS·SSE·입력 API·Kafka 소비)에서만 켜지는 빈. scamshield.roles 로 고른다. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnExpression("'${scamshield.roles}'.contains('api')")
public @interface ApiRole {}

package scamshield.pipeline.common;

import java.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** executor 역할(작업 임대·AI 호출·채택)에서만 켜지는 빈. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnExpression("'${scamshield.roles}'.contains('executor')")
public @interface ExecutorRole {}

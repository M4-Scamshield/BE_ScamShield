package scamshield.pipeline.analysis;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * AI 응답 (계약 ②). action = OBSERVE | ACQUIRE | VERIFY | WARN (이름은 AI 합의 전).
 * newState 는 다음 호출에 그대로 돌려줄 상태. null 이면 상태를 바꾸지 않는다.
 */
public record AnalysisResponse(
        int score,
        String action,
        String message,
        List<Long> evidenceSeqs,
        JsonNode newState) {

    public static final String OBSERVE = "OBSERVE";
    public static final String ACQUIRE = "ACQUIRE";
    public static final String VERIFY = "VERIFY";
    public static final String WARN = "WARN";
}

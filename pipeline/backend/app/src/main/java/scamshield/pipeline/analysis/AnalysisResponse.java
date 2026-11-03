package scamshield.pipeline.analysis;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * AI 응답 (계약 ②). action = OBSERVE | ACQUIRE | VERIFY | WARN (이름은 AI 합의 전).
 * message = 사용자 안내, reason = 탐지 근거(의심일 때만, 없으면 null).
 * newState 는 다음 호출에 그대로 돌려줄 상태. null 이면 상태를 바꾸지 않는다.
 * 응답 예시는 backend/agent-bridge/contract/*.json (AgentContractTest 가 이 record 로 읽는다).
 */
public record AnalysisResponse(
        int score,
        String action,
        String message,
        String reason,
        List<Long> evidenceSeqs,
        JsonNode newState) {

    public static final String OBSERVE = "OBSERVE";
    public static final String ACQUIRE = "ACQUIRE";
    public static final String VERIFY = "VERIFY";
    public static final String WARN = "WARN";
}

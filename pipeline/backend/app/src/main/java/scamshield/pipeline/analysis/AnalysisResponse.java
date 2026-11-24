package scamshield.pipeline.analysis;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * AI 응답 (계약 ②). action = OBSERVE | ACQUIRE | VERIFY | WARN (이름은 AI 합의 전).
 * message = 사용자 안내, reason = 탐지 근거(의심일 때만, 없으면 null).
 * findings = 위험으로 판정된 수법 하나하나(먼저 잡힌 순서). 없으면 빈 목록. 점수는 이 수법들의 점수 합에서 온다.
 * newState 는 다음 호출에 그대로 돌려줄 상태. null 이면 상태를 바꾸지 않는다.
 * 응답 예시는 backend/agent-bridge/contract/*.json (AgentContractTest 가 이 record 로 읽는다).
 */
public record AnalysisResponse(
        int score,
        String action,
        String message,
        String reason,
        List<Long> evidenceSeqs,
        List<Finding> findings,
        JsonNode newState) {

    public static final String OBSERVE = "OBSERVE";
    public static final String ACQUIRE = "ACQUIRE";
    public static final String VERIFY = "VERIFY";
    public static final String WARN = "WARN";

    public AnalysisResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** 수법 목록이 없는 응답(가짜 분석기·테스트). */
    public AnalysisResponse(int score, String action, String message, String reason, List<Long> evidenceSeqs,
                            JsonNode newState) {
        this(score, action, message, reason, evidenceSeqs, List.of(), newState);
    }

    /**
     * 위험으로 판정된 수법 하나. code = agent 상태 코드(F1~F6), name = 수법 이름, points = 점수 몫,
     * reason = 이 수법의 근거, message = 이 수법의 대응 안내, evidenceSeqs = 이 수법이 잡힌 줄.
     */
    public record Finding(String code, String name, int points, String reason, String message, List<Long> evidenceSeqs) {
        public Finding {
            evidenceSeqs = evidenceSeqs == null ? List.of() : List.copyOf(evidenceSeqs);
        }
    }
}

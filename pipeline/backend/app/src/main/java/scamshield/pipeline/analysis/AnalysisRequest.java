package scamshield.pipeline.analysis;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * AI 에 보내는 요청 (계약 ②, 명세 2.2). 필드 이름은 AI 담당과 합의 전 초안(AI5).
 *
 * @param turns     이번 구간 [coveredFrom, coveredTo] 의 발화·입력
 * @param context   그 앞 N개 (문맥, scamshield.agent.context-size)
 * @param state     Agent 상태 원본. 서버는 해석하지 않고 그대로 돌려준다
 */
public record AnalysisRequest(
        String callId,
        long jobId,
        int attempt,
        long coveredFrom,
        long coveredTo,
        List<Turn> context,
        List<Turn> turns,
        JsonNode state,
        long stateVersion) {

    /** kind = SPEECH(발화) | INPUT(사용자 입력). INPUT 이면 inputType 이 붙는다. */
    public record Turn(long seq, String kind, String trackId, String text, String inputType) {}
}

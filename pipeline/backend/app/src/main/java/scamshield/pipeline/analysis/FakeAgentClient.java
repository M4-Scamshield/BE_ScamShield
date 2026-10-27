package scamshield.pipeline.analysis;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 가짜 Agent. 키워드 규칙 + 지연·실패율 손잡이. 진짜 AI 가 오기 전 파이프라인 검증용.
 * 상태(state)에는 지금까지 본 turn 수와 마지막 seq 만 넣는다.
 */
@Component
@ConditionalOnProperty(name = "scamshield.agent.mode", havingValue = "fake", matchIfMissing = true)
public class FakeAgentClient implements AgentClient {

    private static final List<String> MONEY = List.of("송금", "계좌", "보안카드", "검찰");
    private static final List<String> LINK = List.of("링크", "앱 설치", "URL");

    private final ObjectMapper json;
    private final long delayMs;
    private final long jitterMs;
    private final double failureRate;

    public FakeAgentClient(ObjectMapper json,
                           @Value("${scamshield.agent.fake.delay-ms}") long delayMs,
                           @Value("${scamshield.agent.fake.jitter-ms}") long jitterMs,
                           @Value("${scamshield.agent.fake.failure-rate}") double failureRate) {
        this.json = json;
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.failureRate = failureRate;
    }

    @Override
    public AnalysisResponse analyze(AnalysisRequest r) throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Thread.sleep(delayMs + (jitterMs > 0 ? rnd.nextLong(jitterMs) : 0));
        if (rnd.nextDouble() < failureRate) throw new IllegalStateException("fake agent 실패(주입)");

        ObjectNode state = r.state() != null && r.state().isObject() ? (ObjectNode) r.state().deepCopy() : json.createObjectNode();
        ArrayNode seen = state.withArray("seenSeqs");
        r.turns().forEach(t -> seen.add(t.seq()));
        state.put("lastSeq", r.coveredTo());

        List<Long> all = r.turns().stream().map(AnalysisRequest.Turn::seq).toList();
        boolean input = r.turns().stream().anyMatch(t -> "INPUT".equals(t.kind()));
        String text = String.join(" ", r.turns().stream().map(AnalysisRequest.Turn::text).toList());

        if (input) return new AnalysisResponse(60, AnalysisResponse.VERIFY, "입력하신 내용을 확인하고 있어요", all, state);
        if (MONEY.stream().anyMatch(text::contains))
            return new AnalysisResponse(90, AnalysisResponse.WARN, "송금·계좌 요구가 나왔습니다. 통화를 끊으세요", all, state);
        if (LINK.stream().anyMatch(text::contains))
            return new AnalysisResponse(50, AnalysisResponse.ACQUIRE, "받은 링크를 붙여넣어 주세요", all, state);
        return new AnalysisResponse(10, AnalysisResponse.OBSERVE, null, List.of(), state);
    }
}

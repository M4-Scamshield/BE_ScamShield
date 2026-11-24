package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 다리(backend/agent-bridge) 응답 예시를 Java 가 그대로 읽는지. 예시 파일은 파이썬 계약 테스트
 * (agent-bridge/tests/test_server.py)가 다리 출력과 똑같은지 확인한다 → 한쪽만 바뀌면 둘 중 하나가 깨진다.
 * 모르는 필드가 오면 실패하게 읽는다(조용히 버려지는 필드가 없게).
 */
class AgentContractTest extends PipelineTestSupport {

    static final Path CONTRACT = Path.of("..", "agent-bridge", "contract");
    static final String CALL = "C5";

    AnalysisResponse read(String name) throws Exception {
        ObjectMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        return strict.readValue(Files.readString(CONTRACT.resolve(name)), AnalysisResponse.class);
    }

    @Test
    @DisplayName("의심(Yes) 응답: WARN·점수·안내·근거·증거·상태를 읽는다")
    void yes() throws Exception {
        AnalysisResponse r = read("response_yes.json");
        assertEquals(50, r.score());
        assertEquals(AnalysisResponse.WARN, r.action());
        assertNotNull(r.message());
        assertNotNull(r.reason());
        assertEquals(List.of(2L, 3L, 4L), r.evidenceSeqs());
        assertEquals(List.of("F6", "F1"), r.findings().stream().map(AnalysisResponse.Finding::code).toList(),
                "수법은 먼저 잡힌 순서");
        AnalysisResponse.Finding f1 = r.findings().get(1);
        assertEquals("자산 넘기기", f1.name());
        assertEquals(40, f1.points());
        assertEquals(r.reason(), f1.reason());
        assertEquals(List.of(3L, 4L), f1.evidenceSeqs());
        assertEquals(3, r.newState().get("transcript").size());
    }

    @Test
    @DisplayName("정상(No) 응답: OBSERVE, 안내·근거 없음")
    void no() throws Exception {
        AnalysisResponse r = read("response_no.json");
        assertEquals(AnalysisResponse.OBSERVE, r.action());
        assertNull(r.message());
        assertNull(r.reason());
        assertTrue(r.evidenceSeqs().isEmpty());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    @DisplayName("오탐(JEV Yes → Gemini 재판단 false) 응답: 근거는 저장하고 경고는 내지 않는다")
    void falsePositiveRaisesNoAlert() throws Exception {
        AnalysisResponse fp = read("response_false_positive.json");
        assertEquals(AnalysisResponse.OBSERVE, fp.action());
        assertNull(fp.message());
        assertTrue(fp.evidenceSeqs().isEmpty());

        accept(CALL, 1, "카드사 이상거래 확인 전화입니다");
        apply(lease(), new AnalysisResponse(fp.score(), fp.action(), fp.message(), fp.reason(), List.of(), null));

        assertEquals(fp.reason(), db.queryForObject("select reason from analysis_result", String.class));
        assertEquals(0, db.queryForObject("select count(*) from alert", Integer.class));
    }

    @Test
    @DisplayName("근거(reason)는 결과에 저장되고, 근거와 수법별 목록이 경고 이벤트(SSE)에 실린다")
    void reasonReachesAlert() throws Exception {
        accept(CALL, 1, "서울중앙지검입니다");
        Lease l = lease();
        AnalysisResponse yes = read("response_yes.json");
        apply(l, new AnalysisResponse(yes.score(), yes.action(), yes.message(), yes.reason(), List.of(1L),
                yes.findings(), null));

        assertEquals(yes.reason(), db.queryForObject("select reason from analysis_result", String.class));
        JsonNode alert = json.readTree(db.queryForObject(
                "select payload from outbox where event_type = 'alert'", String.class));
        assertEquals("WARN", alert.get("level").asString());
        assertEquals(yes.reason(), alert.get("reason").asString());
        assertEquals(2, alert.get("findings").size(), "수법별 목록도 경고에 실린다");
        assertEquals("F6", alert.get("findings").get(0).get("code").asString());
        assertEquals("서울중앙지검이라며 수사기관을 내세움", alert.get("findings").get(0).get("reason").asString());
    }
}

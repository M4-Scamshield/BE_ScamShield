package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;

/** 실험 뒤에 생긴 규칙 — Agent 상태 CAS, 행동(ACQUIRE/VERIFY) 발행, 같은 안내 반복 억제, eventSeq 순번. */
class AgentStateTest extends PipelineTestSupport {

    static final String CALL = "C4";

    @Test
    @DisplayName("임대 뒤 Agent 상태가 바뀌었으면 newState 를 쓰지 않고 행동도 내지 않는다")
    void staleStateIsNotApplied() throws Exception {
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");
        Lease l = lease();
        AnalysisResponse res = analyze(l);
        db.update("update agent_state set state_version = state_version + 1 where call_id = ?", CALL);

        apply(l, res);
        assertEquals(1, count("select count(*) from analysis_result where not state_applied"));
        assertEquals(0, count("select count(*) from agent_action"), "어긋난 상태로 만든 질문은 화면에 내지 않는다");
        assertEquals(1, count("select count(*) from alert where level = 1"), "경고는 상태와 무관하게 나간다");
    }

    @Test
    @DisplayName("상태가 맞으면 버전이 하나 오르고 다음 요청이 그 상태를 받는다")
    void stateRoundTrips() throws Exception {
        accept(CALL, 1, "네");
        runOne();
        assertEquals(1, count("select state_version from agent_state where call_id = ?", CALL));

        accept(CALL, 2, "네");
        Lease l = lease();
        assertEquals(1, l.baseStateVersion());
        assertEquals(1, jobs.snapshot(l, 6).state().get("lastSeq").asLong(), "앞 작업이 남긴 상태를 그대로 넘긴다");
    }

    @Test
    @DisplayName("같은 행동·문구가 되풀이되면 화면에는 한 번만 — 결과는 둘 다 남는다")
    void repeatedActionIsSuppressed() throws Exception {
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");
        runOne();
        accept(CALL, 2, "링크 눌러 보셨어요");
        runOne();

        assertEquals(2, count("select count(*) from analysis_result where action = ?", AnalysisResponse.ACQUIRE));
        assertEquals(1, count("select count(*) from agent_action"));
    }

    @Test
    @DisplayName("경고와 행동은 한 순번(eventSeq)을 같이 쓰고 outbox 에 빈틈없이 쌓인다")
    void alertAndActionShareEventSeq() throws Exception {
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");   // SUSPECT 경고(1) + ACQUIRE(2)
        runOne();
        accept(CALL, 2, "검찰입니다 계좌가 연루됐습니다");  // WARN 경고(3)
        runOne();

        assertEquals(java.util.List.of(1L, 2L, 3L),
                db.queryForList("select event_seq from outbox where call_id = ? order by event_seq", Long.class, CALL));
        assertEquals(3, count("select last_event_seq from call_state where call_id = ?", CALL));
        assertEquals(2, count("select alert_level from call_state where call_id = ?", CALL));

        accept(CALL, 3, "네");
        runOne();
        assertEquals(2, count("select alert_level from call_state where call_id = ?", CALL), "경고 단계는 내려가지 않는다");
        assertEquals(3, count("select count(*) from outbox"));
    }
}

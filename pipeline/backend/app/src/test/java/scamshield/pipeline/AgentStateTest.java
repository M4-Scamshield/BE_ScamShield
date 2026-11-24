package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;

/** 실험 뒤에 생긴 규칙 — Agent 상태 CAS, 행동(ACQUIRE/VERIFY) 발행, 같은 안내 반복 억제, eventSeq 순번, WARN 안 점수 상승·새 수법. */
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
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");   // 전사(1) → SUSPECT 경고(2) + ACQUIRE(3)
        runOne();
        accept(CALL, 2, "검찰입니다 계좌가 연루됐습니다");  // 전사(4) → WARN 경고(5)
        runOne();

        assertEquals(java.util.List.of(1L, 2L, 3L, 4L, 5L),
                db.queryForList("select event_seq from outbox where call_id = ? order by event_seq", Long.class, CALL));
        assertEquals(java.util.List.of("utterance", "alert", "action", "utterance", "alert"),
                db.queryForList("select event_type from outbox where call_id = ? order by event_seq", String.class, CALL));
        assertEquals(5, count("select last_event_seq from call_state where call_id = ?", CALL));
        assertEquals(2, count("select alert_level from call_state where call_id = ?", CALL));

        accept(CALL, 3, "네");
        runOne();
        assertEquals(2, count("select alert_level from call_state where call_id = ?", CALL), "경고 단계는 내려가지 않는다");
        assertEquals(3, count("select count(*) from outbox where event_type in ('alert','action')"), "새 경고·행동은 없다");
        assertEquals(6, count("select count(*) from outbox"), "전사(3) + 경고·행동(3)");
    }

    @Test
    @DisplayName("이미 WARN 인 통화에서 점수가 최고점보다 오르면 warning 으로 다시 알린다 — 같거나 낮으면 안 낸다")
    void warnScoreRiseIsAnnouncedAgain() {
        accept(CALL, 1, "검찰 수사관입니다");
        apply(lease(), warn(10, "기관 사칭 안내"));     // 첫 위험 → alert
        accept(CALL, 2, "사건에 연루되셨어요");
        apply(lease(), warn(10, "기관 사칭 안내"));     // 점수 그대로 → 없음
        accept(CALL, 3, "안전계좌로 옮기셔야 합니다");
        apply(lease(), warn(50, "송금 요구 안내"));     // 새 위험 상태 → warning
        accept(CALL, 4, "네");
        apply(lease(), new AnalysisResponse(39, AnalysisResponse.OBSERVE, null, null, java.util.List.of(), null));
        accept(CALL, 5, "지금 바로요");
        apply(lease(), warn(50, "송금 요구 안내"));     // 최고점과 같음 → 없음

        assertEquals(java.util.List.of("alert", "warning"),
                db.queryForList("select event_type from outbox where call_id = ? and event_type <> 'utterance' order by event_seq",
                        String.class, CALL));
        var payload = json.readTree(db.queryForObject(
                "select cast(payload as char) from outbox where event_type = 'warning'", String.class));
        assertEquals(50, payload.get("score").asInt());
        assertEquals("WARN", payload.get("level").asString());
        assertEquals("송금 요구 안내", payload.get("message").asString());
        assertEquals(1, count("select count(*) from alert"), "단계는 그대로라 alert 표에는 하나만");
        assertEquals(count("select max(event_seq) from outbox where call_id = ?", CALL),
                count("select last_event_seq from call_state where call_id = ?", CALL), "eventSeq 빈틈 없음");
    }

    @Test
    @DisplayName("WARN 이 아닌 결과는 점수가 높아도 warning 을 내지 않는다")
    void nonWarnResultDoesNotAnnounce() {
        accept(CALL, 1, "검찰 수사관입니다");
        apply(lease(), warn(10, "기관 사칭 안내"));
        accept(CALL, 2, "네");
        apply(lease(), new AnalysisResponse(60, AnalysisResponse.ACQUIRE, "어떤 기관이라고 했나요?", null, java.util.List.of(), null));

        assertEquals(0, count("select count(*) from outbox where event_type = 'warning'"));
    }

    @Test
    @DisplayName("점수가 안 올라도 직전 경고에 없던 수법이 잡히면 warning — 같은 수법만이면 안 낸다")
    void newTacticIsAnnouncedEvenWithoutScoreRise() {
        accept(CALL, 1, "메신저로 옮기시죠");
        apply(lease(), warn(100, "링크 안내", finding("F3")));            // 첫 위험 → alert (이미 100)
        accept(CALL, 2, "다시 말씀드리면");
        apply(lease(), warn(100, "링크 안내", finding("F3")));            // 같은 수법 → 없음
        accept(CALL, 3, "안전계좌로 보내세요");
        apply(lease(), warn(100, "링크 안내", finding("F3"), finding("F1")));  // 점수 그대로, 새 수법 → warning
        accept(CALL, 4, "지금요");
        apply(lease(), warn(100, "링크 안내", finding("F3"), finding("F1")));  // 이미 알린 수법 → 없음

        assertEquals(java.util.List.of("alert", "warning"),
                db.queryForList("select event_type from outbox where call_id = ? and event_type <> 'utterance' order by event_seq",
                        String.class, CALL));
        var findings = json.readTree(db.queryForObject(
                "select cast(payload as char) from outbox where event_type = 'warning'", String.class)).get("findings");
        assertEquals(2, findings.size());
        assertEquals("F1", findings.get(1).get("code").asString());
        assertEquals("F1 근거", findings.get(1).get("reason").asString());
        assertEquals(java.util.List.of(3L), json.convertValue(findings.get(1).get("evidenceSeqs"),
                new tools.jackson.core.type.TypeReference<java.util.List<Long>>() {}));
    }

    private static AnalysisResponse warn(int score, String message, AnalysisResponse.Finding... findings) {
        return new AnalysisResponse(score, AnalysisResponse.WARN, message, "근거", java.util.List.of(),
                java.util.List.of(findings), null);
    }

    private static AnalysisResponse.Finding finding(String code) {
        return new AnalysisResponse.Finding(code, code + " 이름", 40, code + " 근거", code + " 안내",
                java.util.List.of("F1".equals(code) ? 3L : 1L));
    }
}

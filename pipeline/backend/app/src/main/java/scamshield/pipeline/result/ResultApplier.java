package scamshield.pipeline.result;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import scamshield.pipeline.analysis.AnalysisRequest;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;

/**
 * T3 채택 트랜잭션. 결과 저장 + 세 갈래 반영 + Agent 상태 CAS + 경고·행동 + outbox 를 한 번에 커밋한다.
 *
 *  1) 토큰 확인: 이 시도가 아직 임대 주인인가 (회수된 늦은 시도는 버린다)
 *  2) 현재 점수: 이 결과가 덮은 끝(to)이 지금 점수의 끝보다 뒤일 때만 바꾼다 (더 넓게 본 쪽이 이긴다)
 *  3) 구간 기록 → analyzedTo = 빈틈없이 이어진 끝
 *  4) 경고: 이력 기준. 늦게 와도 올린다 (한 번 위험은 위험). 단계는 내려가지 않는다
 *  5) Agent 상태: state_version CAS. 성공했을 때만 newState 적용 + ACQUIRE/VERIFY 행동 발행
 *     (직전 행동과 action·message 가 같으면 발행하지 않는다)
 */
@Service
public class ResultApplier {

    private static final Logger log = LoggerFactory.getLogger(ResultApplier.class);
    static final int SUSPECT = 40;
    static final int WARN = 70;

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ResultApplier(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public void apply(Lease l, AnalysisRequest req, AnalysisResponse res, Instant agentStarted, Instant agentFinished) {
        List<Long> mine = db.queryForList("""
                select job_id from analysis_job where job_id = ? and owner_token = ? and state = 'LEASED'
                for update""", Long.class, l.jobId(), l.token());
        if (mine.isEmpty()) {
            log.info("늦은 결과 버림 job={} attempt={}", l.jobId(), l.attempt());
            return;
        }
        Map<String, Object> cs = db.queryForMap("select * from call_state where call_id = ? for update", l.callId());
        long currentCoveredTo = ((Number) cs.get("current_covered_to")).longValue();
        int peak = ((Number) cs.get("historical_peak")).intValue();
        int alertLevel = ((Number) cs.get("alert_level")).intValue();
        long eventSeq = ((Number) cs.get("last_event_seq")).longValue();

        boolean adoptCurrent = l.coveredTo() > currentCoveredTo;
        boolean stateApplied = res.newState() == null || db.update("""
                update agent_state set state = cast(? as json), state_version = state_version + 1, updated_at = now(6)
                where call_id = ? and state_version = ?""",
                write(res.newState()), l.callId(), l.baseStateVersion()) == 1;

        List<Long> evidence = res.evidenceSeqs() == null ? List.of() : res.evidenceSeqs();
        db.update("""
                insert into analysis_result(job_id, attempt, attempt_token, covered_from_seq, covered_to_seq, score,
                    action, message, evidence_seqs, adopted_current, state_applied, agent_started_at, agent_finished_at)
                values (?,?,?,?,?,?,?,?,cast(? as json),?,?,?,?)""",
                l.jobId(), l.attempt(), l.token(), l.coveredFrom(), l.coveredTo(), res.score(), res.action(),
                res.message(), write(evidence), adoptCurrent, stateApplied,
                Timestamp.from(agentStarted), Timestamp.from(agentFinished));
        // last_insert_id() 는 연결 단위라 같은 트랜잭션(같은 연결) 안에서 안전하다
        Long resultId = db.queryForObject("select last_insert_id()", Long.class);

        db.update("insert into analyzed_segment values (?,?,?,?) on duplicate key update result_id = result_id",
                l.callId(), l.coveredFrom(), l.coveredTo(), resultId);
        long analyzedTo = Coverage.analyzedTo(db, l.callId());

        int level = Math.max(levelOf(res.score()), AnalysisResponse.WARN.equals(res.action()) ? 2 : 0);
        db.update("""
                update call_state set analyzed_to = ?, historical_peak = ?,
                    current_score = case when ? then ? else current_score end,
                    current_covered_to = case when ? then ? else current_covered_to end,
                    accepted_analysis_revision = case when ? then ? else accepted_analysis_revision end
                where call_id = ?""",
                analyzedTo, Math.max(peak, res.score()),
                adoptCurrent, res.score(), adoptCurrent, l.coveredTo(), adoptCurrent, l.coveredTo(), l.callId());

        if (level > alertLevel) {
            eventSeq++;
            db.update("""
                    insert into alert(call_id, event_seq, result_id, level, score, evidence_seqs)
                    values (?,?,?,?,?,cast(? as json))""",
                    l.callId(), eventSeq, resultId, level, res.score(), write(evidence));
            outbox(l.callId(), eventSeq, "alert", payload(
                    "eventSeq", eventSeq, "level", level == 2 ? "WARN" : "SUSPECT", "score", res.score(),
                    "message", res.message(), "evidenceSeqs", evidence, "coveredTo", l.coveredTo()));
            db.update("update call_state set alert_level = ? where call_id = ?", level, l.callId());
        }

        boolean visible = AnalysisResponse.ACQUIRE.equals(res.action()) || AnalysisResponse.VERIFY.equals(res.action());
        if (visible && stateApplied && !sameAsLastAction(l.callId(), res)) {
            eventSeq++;
            db.update("insert into agent_action(call_id, event_seq, result_id, action, message) values (?,?,?,?,?)",
                    l.callId(), eventSeq, resultId, res.action(), res.message());
            outbox(l.callId(), eventSeq, "action", payload(
                    "eventSeq", eventSeq, "action", res.action(), "message", res.message(), "coveredTo", l.coveredTo()));
        }

        db.update("update call_state set last_event_seq = ? where call_id = ?", eventSeq, l.callId());
        db.update("""
                update analysis_job set state = 'DONE', done_at = now(6), lease_until = null
                where job_id = ?""", l.jobId());
    }

    /** Agent 가 같은 판단을 되풀이해도 화면에는 한 번만. 결과는 analysis_result 에 그대로 남는다. */
    private boolean sameAsLastAction(String callId, AnalysisResponse res) {
        List<Map<String, Object>> last = db.queryForList(
                "select action, message from agent_action where call_id = ? order by event_seq desc limit 1", callId);
        return !last.isEmpty() && res.action().equals(last.get(0).get("action"))
                && Objects.equals(res.message(), last.get(0).get("message"));
    }

    static int levelOf(int score) {
        return score >= WARN ? 2 : score >= SUSPECT ? 1 : 0;
    }

    private void outbox(String callId, long eventSeq, String type, Map<String, Object> payload) {
        db.update("insert into outbox(call_id, event_seq, event_type, payload) values (?,?,?,cast(? as json))",
                callId, eventSeq, type, write(payload));
    }

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (JacksonException e) { throw new IllegalStateException(e); }
    }
}

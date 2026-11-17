package scamshield.pipeline.scheduling;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import scamshield.pipeline.common.UtteranceEvent;
import tools.jackson.databind.ObjectMapper;

/**
 * T1 접수 트랜잭션. 한 통화의 call_state 행을 잠그고:
 *   1) event_id 로 중복 거르기 (Kafka 재전달 멱등)
 *   2) seq = latest_input_seq + 1 부여 — 여기서 매기므로 파티션 순서 = seq 순서
 *   3) 발화 또는 사용자 입력 저장
 *   4) 전사 이벤트: outbox 에 utterance 를 쓴다. 경고·행동과 같은 순번(last_event_seq)을 쓴다 —
 *      같은 call_state 행 잠금 아래에서 올리므로 ResultApplier 와 번호가 겹치지 않는다.
 *      접수와 같은 트랜잭션이라 화면 전사에 빠지거나 두 번 들어가는 발화가 없고, 재접속하면 재생된다
 *   5) 작업 병합: READY 가 있으면 끝(to)만 늘리고, 없으면 analyzedTo+1 부터 새로 만든다
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public IntakeService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public void accept(UtteranceEvent e) {
        db.update("insert into call_state(call_id) values (?) on duplicate key update call_id = call_id", e.callId());
        db.update("insert into agent_state(call_id) values (?) on duplicate key update call_id = call_id", e.callId());
        long[] st = db.queryForObject(
                "select latest_input_seq, analyzed_to, last_event_seq from call_state where call_id = ? for update",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, e.callId());

        Integer dup = db.queryForObject("""
                select count(*) from (select 1 from utterance where event_id = ?
                                      union all select 1 from user_input where event_id = ?) d""",
                Integer.class, e.eventId(), e.eventId());
        if (dup != null && dup > 0) {
            log.debug("중복 이벤트 무시 {}", e.eventId());
            return;
        }

        long seq = st[0] + 1;
        if (UtteranceEvent.INPUT.equals(e.kind())) {
            db.update("""
                    insert into user_input(call_id, seq, event_id, input_type, value, reply_to_event, received_at)
                    values (?,?,?,?,?,?,?)""",
                    e.callId(), seq, e.eventId(), e.inputType(), e.text(), e.replyToEvent(), ts(e.capturedAt()));
        } else {
            db.update("""
                    insert into utterance(call_id, seq, event_id, track_id, stream_epoch, segment_id, revision,
                                          start_ms, end_ms, text, confidence, captured_at, stt_final_at, published_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    e.callId(), seq, e.eventId(), e.trackId(), e.streamEpoch(), e.segmentId(), e.revision(),
                    e.startMs(), e.endMs(), e.text(), e.confidence(), ts(e.capturedAt()), ts(e.sttFinalAt()),
                    ts(e.publishedAt()));
        }
        long eventSeq = st[2] + 1;
        db.update("update call_state set latest_input_seq = ?, last_event_seq = ? where call_id = ?",
                seq, eventSeq, e.callId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventSeq", eventSeq);
        payload.put("seq", seq);
        payload.put("kind", e.kind());
        payload.put("trackId", e.trackId());
        payload.put("text", e.text());
        payload.put("inputType", e.inputType());
        db.update("insert into outbox(call_id, event_seq, event_type, payload) values (?,?,'utterance',cast(? as json))",
                e.callId(), eventSeq, json.writeValueAsString(payload));

        int merged = db.update("""
                update analysis_job set covered_to_seq = ?, snapshot_revision = ?
                where call_id = ? and state = 'READY'""", seq, seq, e.callId());
        if (merged == 0) {
            db.update("""
                    insert into analysis_job(call_id, covered_from_seq, covered_to_seq, snapshot_revision)
                    values (?,?,?,?)""", e.callId(), st[1] + 1, seq, seq);
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

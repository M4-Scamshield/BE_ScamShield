package scamshield.pipeline.scheduling;

import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import scamshield.pipeline.common.UtteranceEvent;

/**
 * T1 접수 트랜잭션. 한 통화의 call_state 행을 잠그고:
 *   1) event_id 로 중복 거르기 (Kafka 재전달 멱등)
 *   2) seq = latest_input_seq + 1 부여 — 여기서 매기므로 파티션 순서 = seq 순서
 *   3) 발화 또는 사용자 입력 저장
 *   4) 작업 병합: READY 가 있으면 끝(to)만 늘리고, 없으면 analyzedTo+1 부터 새로 만든다
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final JdbcTemplate db;

    public IntakeService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void accept(UtteranceEvent e) {
        db.update("insert into call_state(call_id) values (?) on duplicate key update call_id = call_id", e.callId());
        db.update("insert into agent_state(call_id) values (?) on duplicate key update call_id = call_id", e.callId());
        long[] st = db.queryForObject(
                "select latest_input_seq, analyzed_to from call_state where call_id = ? for update",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, e.callId());

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
        db.update("update call_state set latest_input_seq = ? where call_id = ?", seq, e.callId());

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

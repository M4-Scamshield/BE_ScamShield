package scamshield.pipeline.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2 임대 / 반납 / 만료 회수. 규칙(명세 4절):
 *  - 통화당 LEASED 하나. 같은 통화가 이미 실행 중이면 그 통화의 READY 는 고르지 않는다.
 *  - 임대 순간 from 을 analyzedTo+1 로 다시 맞춘다 — 직전 작업이 끝난 뒤라 상태와 구간이 겹치지 않는다.
 *  - 반납·만료 때 같은 통화에 READY 가 이미 있으면 SUPERSEDED (그 READY 가 이 구간을 덮는다).
 *    없으면 READY 로 되돌리고, 시도 횟수를 다 쓰면 FAILED.
 */
@Repository
public class JobRepository {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final int leaseSeconds;
    private final int maxAttempts;

    public JobRepository(JdbcTemplate db, ObjectMapper json,
                         @Value("${scamshield.executor.lease-seconds}") int leaseSeconds,
                         @Value("${scamshield.executor.max-attempts}") int maxAttempts) {
        this.db = db;
        this.json = json;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** 고를 게 없거나 같은 통화 경합(유일 인덱스 위반)이면 빈 값 — 호출자가 다시 시도한다. */
    @Transactional
    public Optional<Lease> lease() {
        List<Long> ids = db.queryForList("""
                select j.job_id from analysis_job j
                where j.state = 'READY'
                  and not exists (select 1 from analysis_job l where l.call_id = j.call_id and l.state = 'LEASED')
                order by j.job_id
                limit 1
                for update skip locked""", Long.class);
        if (ids.isEmpty()) return Optional.empty();
        String token = UUID.randomUUID().toString();
        long jobId = ids.getFirst();
        try {
            // MySQL 에는 update ... returning 이 없다 → 같은 트랜잭션에서 고치고 다시 읽는다 (행은 이미 잠겨 있다)
            db.update("""
                    update analysis_job j set
                        state = 'LEASED', attempt = attempt + 1, owner_token = ?,
                        lease_until = now(6) + interval ? second, leased_at = now(6),
                        covered_from_seq = least(j.covered_to_seq,
                            (select c.analyzed_to + 1 from call_state c where c.call_id = j.call_id)),
                        base_state_version = (select s.state_version from agent_state s where s.call_id = j.call_id)
                    where job_id = ?""", token, leaseSeconds, jobId);
            return Optional.ofNullable(db.queryForObject("""
                    select job_id, call_id, covered_from_seq, covered_to_seq, attempt, owner_token, base_state_version
                    from analysis_job where job_id = ?""",
                    (rs, i) -> new Lease(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getInt(5), rs.getString(6), rs.getLong(7)),
                    jobId));
        } catch (DuplicateKeyException raced) {
            // 같은 통화의 다른 READY 가 동시에 LEASED 가 됐다 → 이 트랜잭션은 롤백, 다음 폴링에 다시
            throw new LeaseRaceException(raced);
        }
    }

    public static class LeaseRaceException extends RuntimeException {
        LeaseRaceException(Throwable cause) { super(cause); }
    }

    /** 실행 실패 반납. 토큰이 다르면(이미 회수됨) 아무것도 안 한다. */
    @Transactional
    public void release(Lease l, String error) {
        List<Long> mine = db.queryForList("""
                select job_id from analysis_job where job_id = ? and owner_token = ? and state = 'LEASED'
                for update""", Long.class, l.jobId(), l.token());
        if (!mine.isEmpty()) giveBack(l.jobId(), l.callId(), error);
    }

    /** 만료된 임대 회수. 몇 건 회수했는지 돌려준다. */
    @Transactional
    public int recoverExpired() {
        List<Object[]> expired = db.query("""
                select job_id, call_id from analysis_job
                where state = 'LEASED' and lease_until < now(6)
                for update skip locked""", (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)});
        expired.forEach(r -> giveBack((Long) r[0], (String) r[1], "lease expired"));
        return expired.size();
    }

    private void giveBack(long jobId, String callId, String error) {
        Integer ready = db.queryForObject(
                "select count(*) from analysis_job where call_id = ? and state = 'READY'", Integer.class, callId);
        String next;
        if (ready != null && ready > 0) next = "SUPERSEDED";
        else {
            Integer attempt = db.queryForObject("select attempt from analysis_job where job_id = ?", Integer.class, jobId);
            next = attempt != null && attempt >= maxAttempts ? "FAILED" : "READY";
        }
        db.update("""
                update analysis_job set state = ?, owner_token = null, lease_until = null, last_error = ?,
                    done_at = case when ? in ('FAILED','SUPERSEDED') then now(6) end
                where job_id = ?""", next, truncate(error), next, jobId);
    }

    /** AI 요청 만들기: 이번 구간 + 앞 N개 문맥 + Agent 상태. */
    public AnalysisRequest snapshot(Lease l, int contextSize) {
        List<AnalysisRequest.Turn> rows = new ArrayList<>(db.query("""
                select seq, 'SPEECH', track_id, text, null from utterance
                where call_id = ? and seq <= ? order by seq desc limit ?""",
                (rs, i) -> turn(rs), l.callId(), l.coveredTo(), (l.coveredTo() - l.coveredFrom() + 1) + contextSize));
        rows.addAll(db.query("""
                select seq, 'INPUT', 'USER', value, input_type from user_input
                where call_id = ? and seq <= ? order by seq desc limit ?""",
                (rs, i) -> turn(rs), l.callId(), l.coveredTo(), (l.coveredTo() - l.coveredFrom() + 1) + contextSize));
        rows.sort(Comparator.comparingLong(AnalysisRequest.Turn::seq));

        List<AnalysisRequest.Turn> turns = rows.stream().filter(t -> t.seq() >= l.coveredFrom()).toList();
        List<AnalysisRequest.Turn> before = rows.stream().filter(t -> t.seq() < l.coveredFrom()).toList();
        List<AnalysisRequest.Turn> context = before.subList(Math.max(0, before.size() - contextSize), before.size());

        JsonNode state = db.queryForObject("select cast(state as char) from agent_state where call_id = ?",
                (rs, i) -> readTree(rs.getString(1)), l.callId());
        return new AnalysisRequest(l.callId(), l.jobId(), l.attempt(), l.coveredFrom(), l.coveredTo(),
                context, turns, state, l.baseStateVersion());
    }

    private static AnalysisRequest.Turn turn(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AnalysisRequest.Turn(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }

    private JsonNode readTree(String s) {
        try { return json.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}

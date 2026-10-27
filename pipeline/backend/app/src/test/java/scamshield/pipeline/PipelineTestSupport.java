package scamshield.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import scamshield.pipeline.analysis.AnalysisRequest;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.FakeAgentClient;
import scamshield.pipeline.analysis.JobRepository;
import scamshield.pipeline.analysis.Lease;
import scamshield.pipeline.common.UtteranceEvent;
import scamshield.pipeline.delivery.OutboxRelay;
import scamshield.pipeline.delivery.SseHub;
import scamshield.pipeline.result.ResultApplier;
import scamshield.pipeline.scheduling.IntakeService;
import tools.jackson.databind.ObjectMapper;

/**
 * 파이프라인 테스트 공용 받침. 실험(backend/experiments, PostgreSQL 프로토타입)의 규칙을
 * 앱 코드(IntakeService·JobRepository·ResultApplier) + MySQL 8.4 로 다시 확인한다.
 *
 * 역할(api·executor)을 둘 다 끈다 — 백그라운드 실행기·릴레이·회수가 테스트가 잡을 작업을 가로채지 않게.
 * Kafka 는 쓰지 않는다. 접수는 IntakeListener 를 건너뛰고 IntakeService 를 바로 부른다.
 * 시간이 걸리는 lease 만료는 기다리지 않고 lease_until 을 과거로 돌려 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "scamshield.roles=none",
        "spring.kafka.admin.auto-create=false",
})
abstract class PipelineTestSupport {

    /** 테스트 클래스 전체가 컨테이너 하나를 같이 쓴다(스프링 컨텍스트 캐시와 수명을 맞춤). */
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    static {
        MYSQL.start();
    }

    private static final List<String> TABLES = List.of("outbox", "agent_action", "alert", "analyzed_segment",
            "analysis_result", "analysis_job", "user_input", "utterance", "agent_state", "call_state", "call_session");

    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired IntakeService intake;
    @Autowired JobRepository jobs;
    @Autowired ResultApplier applier;

    FakeAgentClient agent;
    OutboxRelay relay;

    @BeforeEach
    void resetSchema() {
        db.execute("set foreign_key_checks = 0");
        TABLES.forEach(t -> db.execute("truncate table " + t));
        db.execute("set foreign_key_checks = 1");
        agent = new FakeAgentClient(json, 0, 0, 0.0);   // 지연·실패 없음. 송금·계좌 → WARN 90, 링크 → ACQUIRE 50
        relay = new OutboxRelay(db, new SseHub(3, 500));  // 붙은 SSE 가 없으니 sent_at 만 찍힌다
    }

    // ── 단계 하나 = 트랜잭션 하나 (실험의 Pipeline 과 같은 자리) ──────────────

    static UtteranceEvent utter(String callId, long n, String text) {
        Instant now = Instant.now();
        return new UtteranceEvent(callId + "-e" + n, UtteranceEvent.SPEECH, callId, "caller", 0, "s" + n, 1,
                null, null, text, true, null, null, null, now, now, now);
    }

    static UtteranceEvent input(String callId, long n, String type, String value, long replyTo) {
        Instant now = Instant.now();
        return new UtteranceEvent(callId + "-i" + n, UtteranceEvent.INPUT, callId, "USER", 0, null, 1,
                null, null, value, true, null, type, replyTo, now, now, now);
    }

    void accept(String callId, long n, String text) {
        intake.accept(utter(callId, n, text));
    }

    /** 고를 게 없으면 null. */
    Lease lease() {
        Optional<Lease> l = jobs.lease();
        return l.orElse(null);
    }

    AnalysisResponse analyze(Lease l) throws Exception {
        return agent.analyze(jobs.snapshot(l, 6));
    }

    void apply(Lease l, AnalysisResponse res) {
        AnalysisRequest req = jobs.snapshot(l, 6);
        applier.apply(l, req, res, Instant.now(), Instant.now());
    }

    /** 임대 → (트랜잭션 밖) AI → 채택. ExecutorRunner.run 과 같은 순서. */
    Lease runOne() throws Exception {
        Lease l = lease();
        if (l != null) apply(l, analyze(l));
        return l;
    }

    /** 실행기가 멈춘 채 lease 시간이 다 지났다고 친다. */
    void expireLeases() {
        db.update("update analysis_job set lease_until = now(6) - interval 1 second where state = 'LEASED'");
    }

    int relayOnce() {
        long before = count("select count(*) from outbox where sent_at is null");
        tx.executeWithoutResult(s -> relay.relay());
        return (int) (before - count("select count(*) from outbox where sent_at is null"));
    }

    long count(String sql, Object... args) {
        Long n = db.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    long analyzedTo(String callId) {
        return count("select analyzed_to from call_state where call_id = ?", callId);
    }

    /** 접수됐는데 어떤 분석 구간에도 들지 않은 seq 목록. 끝 번호가 아니라 목록으로 본다. */
    List<Long> unanalyzed(String callId) {
        return db.queryForList("""
                select s.seq from (select seq from utterance where call_id = ?
                                   union all select seq from user_input where call_id = ?) s
                where not exists (select 1 from analyzed_segment a
                                  where a.call_id = ? and s.seq between a.from_seq and a.to_seq)
                order by s.seq""", Long.class, callId, callId, callId);
    }

    /** 모든 작업이 끝 상태로 수렴했고, 위험 결과가 있는데 경고가 없는 통화가 없다. */
    void assertConverged() {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                count("select count(*) from analysis_job where state not in ('DONE','FAILED','SUPERSEDED')"),
                "접수된 작업은 완료·명시적 실패·대체 중 하나로 수렴해야 한다");
        org.junit.jupiter.api.Assertions.assertEquals(0, count("""
                select count(*) from analysis_result r join analysis_job j on j.job_id = r.job_id
                where r.score >= 70
                  and not exists (select 1 from alert a where a.call_id = j.call_id and a.level = 2)"""),
                "결과는 있는데 경고만 없는 상태가 남으면 안 된다");
    }
}

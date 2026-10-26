package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;

/**
 * 실험 2 이식 — 아무 지점에서나 죽어도 상태가 수렴하는가.
 *
 * 실험은 별도 JVM 에 진짜 kill -9 를 보내 "미커밋 트랜잭션은 DB 가 버린다"를 확인했다.
 * 여기서는 그 결과를 전제로 죽음을 흉내 낸다: 커밋 전 = 롤백, 커밋 후 = 다음 단계를 부르지 않음,
 * 선점 뒤 = 임대를 들고 사라짐(lease_until 을 과거로).
 */
class CrashRecoveryTest extends PipelineTestSupport {

    static final String CALL = "C2";

    @Test
    @DisplayName("접수 커밋 전에 죽음 — 흔적이 없고, 재전달하면 정확히 한 번만 들어간다")
    void crashBeforeIntakeCommit() {
        tx.executeWithoutResult(s -> {
            accept(CALL, 7, "계좌로 송금해 주세요");
            s.setRollbackOnly();
        });
        assertEquals(0, count("select count(*) from utterance"),
                "오프셋을 커밋하지 않았으니 Kafka 가 다시 준다");

        accept(CALL, 7, "계좌로 송금해 주세요");
        accept(CALL, 7, "계좌로 송금해 주세요");
        assertEquals(1, count("select count(*) from utterance"), "eventId 중복은 무시된다");
        assertEquals(1, count("select count(*) from analysis_job where state = 'READY'"));
    }

    @Test
    @DisplayName("접수 커밋 직후에 죽음 — 오프셋 미커밋으로 다시 와도 한 건이다")
    void crashAfterIntakeCommit() {
        accept(CALL, 7, "계좌로 송금해 주세요");
        accept(CALL, 7, "계좌로 송금해 주세요");   // Kafka 재전달
        assertEquals(1, count("select count(*) from utterance"));
        assertEquals(1, count("select latest_input_seq from call_state where call_id = ?", CALL),
                "재전달이 seq 를 하나 더 먹으면 안 된다");
        assertEquals(1, count("select count(*) from analysis_job"), "작업이 하나 더 생기면 같은 구간을 두 번 분석한다");
    }

    @Test
    @DisplayName("선점 뒤에 죽음 — lease 만료 회수로 되살아나 DONE 으로 수렴한다")
    void crashAfterLease() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        assertNotNull(lease());   // 이 실행기가 죽었다

        assertEquals(1, count("select count(*) from analysis_job where state = 'LEASED'"));
        assertEquals(0, jobs.recoverExpired(), "아직 lease 가 안 끝났으면 뺏지 않는다");

        expireLeases();
        assertEquals(1, jobs.recoverExpired(), "만료됐으니 회수한다");
        assertEquals(1, count("select count(*) from analysis_job where state = 'READY'"));

        runOne();
        assertConverged();
        assertEquals(1, count("select count(*) from alert where level = 2"));
    }

    @Test
    @DisplayName("AI 호출 후 커밋 전에 죽음 — 결과가 없고, 재시도가 AI 를 다시 부른다(비용 손실)")
    void crashAfterAgentBeforeCommit() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        analyze(lease());   // AI 는 불렀다. 채택 전에 죽는다

        assertEquals(0, count("select count(*) from analysis_result"), "AI 는 불렀는데 결과는 없다");

        expireLeases();
        jobs.recoverExpired();
        runOne();
        assertEquals(2, count("select attempt from analysis_result"), "두 번째 시도의 결과로 남는다");
        assertConverged();
    }

    @Test
    @DisplayName("결과 커밋 후 발송 전에 죽음 — outbox 에 남아 릴레이가 마저 보낸다")
    void crashAfterResultCommit() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        runOne();   // 채택까지 커밋. 릴레이 전에 죽는다

        assertEquals(1, count("select count(*) from analysis_result"));
        assertEquals(1, count("select count(*) from alert"));
        assertEquals(1, count("select count(*) from outbox where sent_at is null"),
                "경고와 outbox 가 같은 트랜잭션이라 '결과는 있는데 알릴 방법이 없는' 상태가 안 생긴다");

        assertEquals(1, relayOnce());
        assertEquals(0, count("select count(*) from outbox where sent_at is null"));
        assertConverged();
    }

    @Test
    @DisplayName("좀비 실행기의 늦은 쓰기 — 옛 토큰은 거절된다")
    void zombieWriteIsRejected() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        Lease zombie = lease();   // 멈춰 있다 깨어날 실행기

        expireLeases();
        assertEquals(1, jobs.recoverExpired());
        Lease live = lease();
        assertEquals(zombie.jobId(), live.jobId());
        assertNotEquals(zombie.token(), live.token(), "시도마다 새 토큰이다");

        apply(zombie, new AnalysisResponse(90, AnalysisResponse.WARN, "좀비", java.util.List.of(1L), null));
        assertEquals(0, count("select count(*) from analysis_result"), "옛 토큰의 쓰기는 막혀야 한다");

        jobs.release(zombie, "좀비 반납");
        assertEquals(1, count("select count(*) from analysis_job where state = 'LEASED'"), "옛 토큰의 반납도 무시된다");

        apply(live, analyze(live));
        assertEquals(1, count("select count(*) from analysis_result"));
        assertConverged();
    }

    @Test
    @DisplayName("릴레이가 두 번 보내도 경고는 한 건 — 재전송과 중복 생성은 다른 문제다")
    void redeliveryDoesNotDuplicateAlert() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        Lease l = runOne();
        relayOnce();

        // 릴레이가 보내고 커밋하기 전에 죽었다 → 같은 행이 다시 대상이 된다
        db.update("update outbox set sent_at = null");
        assertEquals(1, relayOnce(), "전송은 두 번 일어난다 — 이걸 0으로 만드는 게 목표가 아니다");
        assertEquals(1, count("select count(*) from alert"), "목표는 같은 경고가 두 건 생기지 않는 것");
        assertEquals(1, count("select count(*) from outbox"));

        apply(l, analyze(l));   // 같은 임대의 결과가 한 번 더 와도
        assertEquals(1, count("select count(*) from analysis_result"), "DONE 이라 토큰 확인에서 버린다");
    }
}

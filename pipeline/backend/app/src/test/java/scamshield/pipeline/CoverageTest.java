package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.AnalysisResponse;
import scamshield.pipeline.analysis.Lease;

/**
 * 실험 1 이식 — 한 통화의 입력 커버리지와 결과 채택.
 * 통과 기준은 analyzedTo 숫자가 아니라 "실제로 어떤 입력이 분석 구간에 들었는지 목록"이다.
 *
 * 실험과 달라진 점: 앱은 통화당 LEASED 하나라(Agent 상태 직렬) 한 통화의 두 작업이 동시에 돌지 않고,
 * agent 는 "어디까지 읽었는지"를 말하지 않는다(구간은 서버 임대가 정한다). 그래서
 * 실험의 "agent 가 덜 읽음" 두 건은 해당 없음이고, 대신 SUPERSEDED 경로를 본다.
 */
class CoverageTest extends PipelineTestSupport {

    static final String CALL = "C1";

    @Test
    @DisplayName("분석 중에 발화가 계속 들어와도 결과는 버려지지 않고 경고가 나간다")
    void resultsAreAdoptedWhileInputKeepsComing() throws Exception {
        for (int round = 0; round < 5; round++) {
            accept(CALL, round * 4L + 1, "여보세요");
            Lease job = lease();
            for (int k = 2; k <= 4; k++)
                accept(CALL, round * 4L + k, k == 3 ? "계좌로 송금해 주세요" : "네 알겠습니다");
            apply(job, analyze(job));
        }
        assertEquals(5, count("select count(*) from analysis_result"), "옛 규칙(버전 비교)은 5건 모두 버렸다");
        assertEquals(5, count("select count(*) from analysis_result where adopted_current"));
        assertEquals(1, count("select count(*) from alert where level = 2"));

        runOne();
        assertEquals(List.of(), unanalyzed(CALL));
    }

    @Test
    @DisplayName("분석 중에 들어온 발화(seq 4)는 다음 작업에 반드시 들어간다 — 목록으로 검사")
    void pushedOutUtteranceIsNeverSkipped() throws Exception {
        for (long s = 1; s <= 3; s++) accept(CALL, s, "안녕하세요");
        Lease slow = lease();
        assertEquals(1, slow.coveredFrom());
        assertEquals(3, slow.coveredTo());

        accept(CALL, 4, "계좌로 송금해 주세요");
        for (long s = 5; s <= 8; s++) accept(CALL, s, "네");
        assertNull(lease(), "같은 통화가 실행 중이면 그 통화의 READY 는 고르지 않는다");

        apply(slow, analyze(slow));
        Lease next = lease();
        assertEquals(4, next.coveredFrom(), "임대 순간 from 을 analyzedTo+1 로 다시 맞춘다");
        assertTrue(jobs.snapshot(next, 6).turns().stream().anyMatch(t -> t.seq() == 4),
                "요청 turns 에 seq 4 가 실제로 들어 있어야 한다");
        apply(next, analyze(next));

        assertEquals(List.of(), unanalyzed(CALL));
        assertEquals(1, count("select count(*) from alert where level = 2"));
    }

    @Test
    @DisplayName("구멍이 있으면 analyzedTo 가 그 앞에서 멈춘다 — 8로 뛰지 않는다")
    void analyzedToStopsAtTheHole() throws Exception {
        for (long s = 1; s <= 3; s++) accept(CALL, s, "네");
        runOne();
        for (long s = 4; s <= 8; s++) accept(CALL, s, "네");

        // 6~8 만 분석된 상황을 직접 만든다 (부분·긴급 분석이 생기면 나올 모양)
        db.update("""
                insert into analysis_job(call_id, covered_from_seq, covered_to_seq, snapshot_revision, state,
                                         attempt, owner_token, lease_until)
                values (?, 6, 8, 8, 'LEASED', 1, 'manual', now(6) + interval 30 second)""", CALL);
        long jobId = count("select job_id from analysis_job where owner_token = 'manual'");
        long version = count("select state_version from agent_state where call_id = ?", CALL);
        Lease partial = new Lease(jobId, CALL, 6, 8, 1, "manual", version);
        apply(partial, analyze(partial));

        assertEquals(3, analyzedTo(CALL), "1~3, 6~8 만 봤는데 8 이면 4~5 도 본 것처럼 보인다");
        assertEquals(List.of(4L, 5L), unanalyzed(CALL), "빠진 구간이 목록으로 드러나야 한다");

        Lease fill = lease();
        assertEquals(4, fill.coveredFrom(), "다음 작업은 빈 구간부터");
        apply(fill, analyze(fill));
        assertEquals(8, analyzedTo(CALL));
        assertEquals(List.of(), unanalyzed(CALL));
    }

    @Test
    @DisplayName("현재 점수를 못 덮는 늦은 결과도 이력·커버리지·경고에는 남는다")
    void lateNarrowerResultStillRaisesAlert() throws Exception {
        for (long s = 1; s <= 10; s++) accept(CALL, s, s == 4 ? "계좌로 송금해 주세요" : "네");
        Lease jobA = lease();

        // 더 뒤까지 본 결과가 먼저 채택돼 있는 상황 (앱은 통화당 LEASED 하나라 평소엔 안 생기지만 규칙은 막아 둔다)
        db.update("update call_state set current_covered_to = 12, current_score = 10 where call_id = ?", CALL);

        apply(jobA, analyze(jobA));

        assertEquals(0, count("select count(*) from analysis_result where adopted_current"),
                "더 좁은 구간이므로 현재 점수는 덮지 않는다");
        assertEquals(10, count("select current_score from call_state where call_id = ?", CALL));
        assertEquals(1, count("select count(*) from alert where level = 2"),
                "그래도 경고는 나간다 — 경고는 현재 점수가 아니라 이력 기준");
        assertEquals(90, count("select historical_peak from call_state where call_id = ?", CALL));
        assertEquals(List.of(), unanalyzed(CALL));
    }

    @Test
    @DisplayName("작업이 FAILED 로 수렴해도 그 구간은 다음 작업이 다시 덮는다")
    void failedJobRangeIsRetakenByTheNextJob() throws Exception {
        accept(CALL, 1, "계좌로 송금해 주세요");
        for (int attempt = 1; attempt <= 3; attempt++) {   // max-attempts 3: 실행기가 세 번 다 죽었다
            assertNotNull(lease());
            expireLeases();
            assertEquals(1, jobs.recoverExpired());
        }
        assertEquals(1, count("select count(*) from analysis_job where state = 'FAILED'"),
                "조용히 사라지지 않고 명시적 실패로 수렴한다");
        assertNull(lease(), "시도를 다 쓴 작업은 다시 잡지 않는다");

        accept(CALL, 2, "네");
        Lease next = lease();
        assertEquals(1, next.coveredFrom(), "실패한 구간(1)을 건너뛰면 안 된다");
        apply(next, analyze(next));
        assertEquals(List.of(), unanalyzed(CALL));
        assertEquals(1, count("select count(*) from alert where level = 2"));
    }

    @Test
    @DisplayName("실행 중 새 READY 가 생긴 뒤 실패하면 SUPERSEDED — 그 구간은 새 작업이 덮는다")
    void failedJobIsSupersededByTheWiderReadyJob() throws Exception {
        for (long s = 1; s <= 3; s++) accept(CALL, s, s == 2 ? "계좌로 송금해 주세요" : "네");
        Lease first = lease();
        accept(CALL, 4, "네");
        assertEquals(1, count("select covered_from_seq from analysis_job where state = 'READY'"),
                "실행 중에 생긴 READY 는 analyzedTo+1(=1)부터 잡는다");

        jobs.release(first, "agent timeout");
        assertEquals("SUPERSEDED", db.queryForObject("select state from analysis_job where job_id = ?",
                String.class, first.jobId()));

        Lease next = lease();
        assertEquals(1, next.coveredFrom());
        assertEquals(4, next.coveredTo());
        apply(next, analyze(next));
        assertEquals(List.of(), unanalyzed(CALL));
        assertEquals(1, count("select count(*) from alert where level = 2"));
        assertConverged();
    }

    @Test
    @DisplayName("사용자 입력도 발화와 같은 seq 줄에 들어가 다음 작업이 함께 본다")
    void userInputSharesTheSeqLine() throws Exception {
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");
        runOne();
        assertEquals(1, count("select count(*) from agent_action where action = ?", AnalysisResponse.ACQUIRE));

        intake.accept(input(CALL, 1, "LINK", "http://x.invalid/a.apk", 2));
        assertEquals(2, count("select seq from user_input where call_id = ?", CALL));
        Lease l = runOne();
        assertEquals(2, l.coveredFrom());
        assertEquals(1, count("select count(*) from agent_action where action = ?", AnalysisResponse.VERIFY));
        assertEquals(List.of(), unanalyzed(CALL));
    }
}

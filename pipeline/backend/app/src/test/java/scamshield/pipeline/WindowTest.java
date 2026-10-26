package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scamshield.pipeline.analysis.Lease;

/**
 * 실험 3 이식 — "연속 구간만 분석" 정책에서 한 번에 보내는 구간이 커지는가.
 * 결론(실험과 같음): 처리 주기가 일정하면 수렴, 분석 시간이 구간 크기에 비례하고 처리량 < 입력율이면 발산.
 */
class WindowTest extends PipelineTestSupport {

    static final String CALL = "C3";

    @Test
    @DisplayName("분석이 입력을 따라가면 구간은 일정하게 유지된다")
    void windowStaysFlatWhenKeepingUp() throws Exception {
        List<Long> sizes = new ArrayList<>();
        long seq = 0;
        for (int round = 1; round <= 12; round++) {
            for (int k = 0; k < 3; k++) accept(CALL, ++seq, "네");
            sizes.add(size(runOne()));
        }
        assertEquals(sizes.get(1), sizes.getLast(), "구간 크기 추이 = " + sizes);
    }

    @Test
    @DisplayName("처리가 뜸해도 주기가 일정하면 구간은 수렴한다 — 커지지 않는다")
    void windowConvergesWhenServiceRateIsConstant() throws Exception {
        List<Long> sizes = new ArrayList<>();
        long seq = 0;
        for (int round = 1; round <= 12; round++) {
            for (int k = 0; k < 3; k++) accept(CALL, ++seq, "네");
            if (round % 2 == 0) sizes.add(size(runOne()));   // 두 라운드에 한 번만 처리
        }
        assertEquals(sizes.getFirst(), sizes.getLast(), "구간 크기 추이 = " + sizes);
        assertEquals(List.of(), unanalyzed(CALL));
    }

    @Test
    @DisplayName("분석 시간이 구간 크기에 비례하고 처리량 < 입력율이면 구간이 발산한다")
    void windowDivergesWhenServiceScalesWithSize() throws Exception {
        List<Long> slow = simulate(3, 2);   // 라운드당 발화 3, 처리 가능 2
        List<Long> fast = simulate(3, 6);   // 처리 가능 6

        assertTrue(slow.getLast() > slow.getFirst() * 2,
                "처리량이 입력율보다 작으면 한 번에 보낼 양이 계속 커진다 — 구간 상한이 필요하다. 추이 = " + slow);
        assertEquals(fast.getFirst(), fast.getLast(), "처리량이 충분하면 커지지 않는다. 추이 = " + fast);
    }

    /** 분석 소요 라운드 = ceil(구간 크기 / capacity). */
    private List<Long> simulate(int utterPerRound, int capacity) throws Exception {
        resetSchema();
        List<Long> sizes = new ArrayList<>();
        long seq = 0;
        Lease running = null;
        int roundsLeft = 0;
        for (int round = 1; round <= 20; round++) {
            for (int k = 0; k < utterPerRound; k++) accept(CALL, ++seq, "네");
            if (running == null) {
                running = lease();
                sizes.add(size(running));
                roundsLeft = (int) Math.ceil((double) size(running) / capacity);
            }
            if (--roundsLeft <= 0) {
                apply(running, analyze(running));
                running = null;
            }
        }
        return sizes;
    }

    private static long size(Lease l) {
        return l.coveredTo() - l.coveredFrom() + 1;
    }
}

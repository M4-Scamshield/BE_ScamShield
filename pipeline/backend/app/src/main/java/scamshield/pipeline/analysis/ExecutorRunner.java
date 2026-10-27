package scamshield.pipeline.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import scamshield.pipeline.common.ExecutorRole;
import scamshield.pipeline.result.ResultApplier;

/**
 * 작업 실행기. 가상 스레드 N개가 각자: 임대(T2) → 요청 만들기 → AI 호출(트랜잭션 밖) → 채택(T3).
 * AI 호출 중엔 DB 연결을 잡지 않는다 — 느린 AI 가 커넥션 풀을 막지 않게.
 */
@Component
@ExecutorRole
public class ExecutorRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRunner.class);

    private final JobRepository jobs;
    private final AgentClient agent;
    private final ResultApplier applier;
    private final int workers;
    private final long pollMs;
    private final int contextSize;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public ExecutorRunner(JobRepository jobs, AgentClient agent, ResultApplier applier,
                          @Value("${scamshield.executor.workers}") int workers,
                          @Value("${scamshield.executor.poll-interval-ms}") long pollMs,
                          @Value("${scamshield.agent.context-size}") int contextSize) {
        this.jobs = jobs;
        this.agent = agent;
        this.applier = applier;
        this.workers = workers;
        this.pollMs = pollMs;
        this.contextSize = contextSize;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < workers; i++) {
            threads.add(Thread.ofVirtual().name("executor-" + i).start(this::loop));
        }
        log.info("executor 시작 workers={}", workers);
    }

    private void loop() {
        while (running) {
            Optional<Lease> lease;
            try {
                lease = jobs.lease();
            } catch (JobRepository.LeaseRaceException raced) {
                continue;
            } catch (Exception e) {
                log.warn("임대 실패", e);
                sleep();
                continue;
            }
            if (lease.isEmpty()) {
                sleep();
                continue;
            }
            run(lease.get());
        }
    }

    private void run(Lease l) {
        try {
            AnalysisRequest req = jobs.snapshot(l, contextSize);
            Instant started = Instant.now();
            AnalysisResponse res = agent.analyze(req);
            applier.apply(l, req, res, started, Instant.now());
        } catch (Exception e) {
            log.warn("작업 실패 job={} attempt={}", l.jobId(), l.attempt(), e);
            try { jobs.release(l, e.toString()); } catch (Exception ex) { log.warn("반납 실패 job={}", l.jobId(), ex); }
        }
    }

    private void sleep() {
        try { Thread.sleep(pollMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

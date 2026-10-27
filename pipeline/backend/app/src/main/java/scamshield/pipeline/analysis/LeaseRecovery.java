package scamshield.pipeline.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import scamshield.pipeline.common.ExecutorRole;

/** 실행기가 죽어서 반납되지 않은 임대를 회수한다. 여러 인스턴스가 돌아도 skip locked 라 안전. */
@Component
@ExecutorRole
public class LeaseRecovery {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecovery.class);

    private final JobRepository jobs;

    public LeaseRecovery(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${scamshield.executor.recover-interval-ms}")
    public void recover() {
        int n = jobs.recoverExpired();
        if (n > 0) log.info("만료 임대 회수 {}건", n);
    }
}

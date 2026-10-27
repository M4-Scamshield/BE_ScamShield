package scamshield.pipeline.access;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 통화 생성 속도 제한. IP 별 1분 고정 창. 계정이 없어서 지금은 IP 가 유일한 주체다.
 * 인스턴스 메모리라 api 를 여러 대 띄우면 대수만큼 느슨해진다 (그때 Redis 로 옮긴다).
 * 프록시 뒤에서는 server.forward-headers-strategy 를 켜야 getRemoteAddr 가 진짜 IP 가 된다.
 */
@Component
public class CreateLimiter {

    private record Window(long minute, int count) { }

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public CreateLimiter(@Value("${scamshield.access.create-per-minute}") int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean allow(String ip) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(ip, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return w.count() <= perMinute;
    }

    @Scheduled(fixedDelay = 60_000)
    void sweep() {
        long minute = System.currentTimeMillis() / 60_000;
        windows.values().removeIf(w -> w.minute() < minute);
    }
}

package scamshield.pipeline.delivery;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import scamshield.pipeline.common.ApiRole;

/**
 * 이 인스턴스에 붙은 SSE 연결 목록. api 인스턴스가 여럿이면 relay 는 자기 연결에만 보낸다 —
 * 다른 인스턴스에 붙은 사용자는 재접속 재생(Last-Event-ID)으로 받는다. 다중 인스턴스 대응은 배포 단계 과제.
 * 연결 수 상한: 전체는 넘으면 거절(429). 통화당은 가장 오래된 연결을 닫는다 — 끊긴 연결은 하트비트가
 * 실패해야(최대 2번) 빠지는데, 그 사이 새로고침한 본인이 429 로 막히면 EventSource 가 다시 붙지 않기 때문이다.
 */
@Component
@ApiRole
public class SseHub {

    private final Map<String, Set<SseEmitter>> byCall = new ConcurrentHashMap<>();
    private final int maxPerCall;
    private final int maxTotal;
    private int total;

    public SseHub(@Value("${scamshield.sse.max-per-call}") int maxPerCall,
                  @Value("${scamshield.sse.max-total}") int maxTotal) {
        this.maxPerCall = maxPerCall;
        this.maxTotal = maxTotal;
    }

    /** 전체 상한을 넘으면 false. 콜백이 여러 번 불려도 한 번만 빠진다. */
    public synchronized boolean register(String callId, SseEmitter e) {
        Set<SseEmitter> set = byCall.get(callId);
        if (set != null && set.size() >= maxPerCall) {
            SseEmitter oldest = set.iterator().next(); // CopyOnWriteArraySet 은 넣은 순서
            unregister(callId, oldest);
            oldest.complete();
        }
        if (total >= maxTotal) return false;
        set = byCall.computeIfAbsent(callId, k -> new CopyOnWriteArraySet<>());
        set.add(e);
        total++;
        Runnable remove = () -> unregister(callId, e);
        e.onCompletion(remove);
        e.onTimeout(remove);
        e.onError(t -> remove.run());
        return true;
    }

    private synchronized void unregister(String callId, SseEmitter e) {
        Set<SseEmitter> set = byCall.get(callId);
        if (set == null || !set.remove(e)) return;
        total--;
        if (set.isEmpty()) byCall.remove(callId);
    }

    public void send(String callId, long eventSeq, String type, String payloadJson) {
        for (SseEmitter e : byCall.getOrDefault(callId, Set.of())) {
            send(e, eventSeq, type, payloadJson);
        }
    }

    static boolean send(SseEmitter e, long eventSeq, String type, String payloadJson) {
        try {
            synchronized (e) {
                e.send(SseEmitter.event().id(Long.toString(eventSeq)).name(type).data(payloadJson, MediaType.APPLICATION_JSON));
            }
            return true;
        } catch (IOException | IllegalStateException ex) {
            e.completeWithError(ex);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "#{${scamshield.sse.heartbeat-seconds} * 1000}")
    public void heartbeat() {
        byCall.values().forEach(set -> set.forEach(e -> {
            try {
                synchronized (e) { e.send(SseEmitter.event().comment("hb")); }
            } catch (IOException | IllegalStateException ex) {
                e.completeWithError(ex);
            }
        }));
    }
}

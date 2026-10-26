package scamshield.pipeline.delivery;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import scamshield.pipeline.common.ApiRole;

/**
 * 이 인스턴스에 붙은 SSE 연결 목록. api 인스턴스가 여럿이면 relay 는 자기 연결에만 보낸다 —
 * 다른 인스턴스에 붙은 사용자는 재접속 재생(Last-Event-ID)으로 받는다. 다중 인스턴스 대응은 배포 단계 과제.
 */
@Component
@ApiRole
public class SseHub {

    private final Map<String, Set<SseEmitter>> byCall = new ConcurrentHashMap<>();

    public void register(String callId, SseEmitter e) {
        byCall.computeIfAbsent(callId, k -> ConcurrentHashMap.newKeySet()).add(e);
        Runnable remove = () -> byCall.getOrDefault(callId, Set.of()).remove(e);
        e.onCompletion(remove);
        e.onTimeout(remove);
        e.onError(t -> remove.run());
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

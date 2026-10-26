package scamshield.pipeline.ingest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import scamshield.pipeline.common.ApiRole;

/**
 * 분석용 음성 업로드 (계약 ①, 1안). 브라우저가 자기 마이크만 PCM16 mono 16kHz 로 보낸다.
 *   ws://host/ws/audio?callId=..&token=..&trackId=..&epoch=..   (토큰 확인은 WebSocketConfig)
 * 연결 = 화자. 재연결하면 epoch 를 올려서 온다.
 * 동시 STT 세션 수에 상한을 둔다 (리턴제로 동시 채널 한도·요금). 넘으면 1013 으로 닫는다.
 * 토큰 하나로 상한을 다 먹지 못하게 통화당 화자 수도 묶는다. 같은 trackId 가 다시 오면(재연결) 예전 연결을 닫는다.
 * 음성 프레임이 idle-seconds 동안 안 오는 연결은 죽은 것으로 보고 닫는다 (업로더는 무음도 계속 보낸다).
 */
@Component
@ApiRole
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioWebSocketHandler.class);

    private final SttClient stt;
    private final UtterancePublisher publisher;
    private final Map<String, SttClient.SttSession> sessions = new ConcurrentHashMap<>();
    private static final String SLOT = "sttSlot";
    private static final String LAST_AUDIO = "lastAudio";
    private final Semaphore slots;
    private final int maxTracksPerCall;
    private final long idleMs;
    /** callId → (trackId → 연결). 통화당 화자 수 세기와 같은 화자 재연결 처리용 */
    private final Map<String, Map<String, WebSocketSession>> tracks = new ConcurrentHashMap<>();

    public AudioWebSocketHandler(SttClient stt, UtterancePublisher publisher,
                                 @Value("${scamshield.stt.max-sessions}") int maxSessions,
                                 @Value("${scamshield.stt.max-tracks-per-call}") int maxTracksPerCall,
                                 @Value("${scamshield.stt.idle-seconds}") long idleSeconds) {
        this.stt = stt;
        this.publisher = publisher;
        this.slots = new Semaphore(maxSessions);
        this.maxTracksPerCall = maxTracksPerCall;
        this.idleMs = idleSeconds * 1000;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        Map<String, String> q = query(ws.getUri());
        String callId = q.get("callId");
        String trackId = q.get("trackId");
        int epoch = Integer.parseInt(q.getOrDefault("epoch", "0"));
        if (callId == null || trackId == null) {
            close(ws, CloseStatus.BAD_DATA.withReason("callId, trackId 필요"));
            return;
        }
        WebSocketSession replaced = null;
        synchronized (tracks) {
            Map<String, WebSocketSession> byTrack = tracks.computeIfAbsent(callId, k -> new ConcurrentHashMap<>());
            if (!byTrack.containsKey(trackId) && byTrack.size() >= maxTracksPerCall) {
                log.warn("통화당 화자 상한 도달 callId={} trackId={}", callId, trackId);
                close(ws, CloseStatus.POLICY_VIOLATION.withReason("too many tracks"));
                return;
            }
            replaced = byTrack.put(trackId, ws);
        }
        // 예전 연결이 닫히며 슬롯을 돌려준 뒤에 새 슬롯을 잡는다
        if (replaced != null) close(replaced, CloseStatus.NORMAL.withReason("replaced"));
        ws.getAttributes().put(LAST_AUDIO, new AtomicLong(System.currentTimeMillis()));
        if (!slots.tryAcquire()) {
            log.warn("STT 세션 상한 도달 callId={} trackId={}", callId, trackId);
            close(ws, CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        ws.getAttributes().put(SLOT, Boolean.TRUE);
        SttClient.SttSession session = stt.open(callId, trackId, epoch, new SttClient.Listener() {
            @Override public void onFinal(SttClient.Segment s) {
                // captureTime 은 1안 업로더가 보내기 전까지 STT 확정 시각으로 대신한다
                publisher.publishSpeech(callId, trackId, epoch, s, s.finalAt());
            }
            @Override public void onError(Throwable error) {
                log.warn("STT 오류 callId={} trackId={}", callId, trackId, error);
                close(ws, CloseStatus.SERVER_ERROR);
            }
        });
        sessions.put(ws.getId(), session);
        // open 안에서 실패해 이미 닫혔으면 afterConnectionClosed 가 먼저 돌았다 → 여기서 치운다
        if (!ws.isOpen() && sessions.remove(ws.getId()) != null) session.close();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        SttClient.SttSession s = sessions.get(ws.getId());
        if (s == null) return;
        ((AtomicLong) ws.getAttributes().get(LAST_AUDIO)).set(System.currentTimeMillis());
        byte[] buf = new byte[message.getPayloadLength()];
        message.getPayload().get(buf);
        s.sendAudio(buf);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        SttClient.SttSession s = sessions.remove(ws.getId());
        try {
            if (s != null) s.close();
        } finally {
            if (ws.getAttributes().remove(SLOT) != null) slots.release();
            untrack(ws);
        }
    }

    /** 이 연결이 아직 그 화자의 현재 연결일 때만 지운다 (재연결로 바뀌었으면 새 연결을 지우면 안 된다) */
    private void untrack(WebSocketSession ws) {
        Map<String, String> q = query(ws.getUri());
        String callId = q.get("callId");
        String trackId = q.get("trackId");
        if (callId == null || trackId == null) return;
        synchronized (tracks) {
            Map<String, WebSocketSession> byTrack = tracks.get(callId);
            if (byTrack == null) return;
            byTrack.remove(trackId, ws);
            if (byTrack.isEmpty()) tracks.remove(callId);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    void closeIdle() {
        long now = System.currentTimeMillis();
        tracks.values().forEach(byTrack -> byTrack.values().forEach(ws -> {
            Object last = ws.getAttributes().get(LAST_AUDIO);
            if (last instanceof AtomicLong t && now - t.get() > idleMs) {
                log.info("음성이 {}초 안 와서 닫음 trackId={}", idleMs / 1000, query(ws.getUri()).get("trackId"));
                close(ws, CloseStatus.SESSION_NOT_RELIABLE.withReason("idle"));
            }
        }));
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    }

    private static void close(WebSocketSession ws, CloseStatus status) {
        try { ws.close(status); } catch (Exception ignored) { }
    }
}

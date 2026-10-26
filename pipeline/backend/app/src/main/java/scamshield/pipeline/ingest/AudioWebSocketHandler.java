package scamshield.pipeline.ingest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import scamshield.pipeline.common.ApiRole;

/**
 * 분석용 음성 업로드 (계약 ①, 1안). 브라우저가 자기 마이크만 PCM16 mono 16kHz 로 보낸다.
 *   ws://host/ws/audio?callId=..&trackId=..&epoch=..
 * 연결 = 화자. 재연결하면 epoch 를 올려서 온다.
 */
@Component
@ApiRole
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioWebSocketHandler.class);

    private final SttClient stt;
    private final UtterancePublisher publisher;
    private final Map<String, SttClient.SttSession> sessions = new ConcurrentHashMap<>();

    public AudioWebSocketHandler(SttClient stt, UtterancePublisher publisher) {
        this.stt = stt;
        this.publisher = publisher;
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
        sessions.put(ws.getId(), stt.open(callId, trackId, epoch, new SttClient.Listener() {
            @Override public void onFinal(SttClient.Segment s) {
                // captureTime 은 1안 업로더가 보내기 전까지 STT 확정 시각으로 대신한다
                publisher.publishSpeech(callId, trackId, epoch, s, s.finalAt());
            }
            @Override public void onError(Throwable error) {
                log.warn("STT 오류 callId={} trackId={}", callId, trackId, error);
                close(ws, CloseStatus.SERVER_ERROR);
            }
        }));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        SttClient.SttSession s = sessions.get(ws.getId());
        if (s == null) return;
        byte[] buf = new byte[message.getPayloadLength()];
        message.getPayload().get(buf);
        s.sendAudio(buf);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        SttClient.SttSession s = sessions.remove(ws.getId());
        if (s != null) s.close();
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    }

    private static void close(WebSocketSession ws, CloseStatus status) {
        try { ws.close(status); } catch (Exception ignored) { }
    }
}

package scamshield.pipeline.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 리턴제로 스트리밍 STT (WebSocket). 화자 한 명 = RTZR 세션 하나.
 *   wss://openapi.vito.ai/v1/transcribe:streaming?sample_rate=16000&encoding=LINEAR16...
 * 음성은 바이너리 프레임, 끝은 텍스트 "EOS". 응답 중 final=true 만 넘긴다.
 * 키는 서버 환경변수(.env)에만 둔다. 무료 계정은 동시 5채널.
 */
@Component
@ConditionalOnProperty(name = "scamshield.stt.mode", havingValue = "rtzr")
public class RtzrSttClient implements SttClient {

    private static final Logger log = LoggerFactory.getLogger(RtzrSttClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String query;
    private final long eosWaitMs;

    private String token;
    private long tokenExpireAt; // epoch 초

    public RtzrSttClient(ObjectMapper json,
                         @Value("${scamshield.stt.rtzr.base-url}") String baseUrl,
                         @Value("${scamshield.stt.rtzr.client-id}") String clientId,
                         @Value("${scamshield.stt.rtzr.client-secret}") String clientSecret,
                         @Value("${scamshield.stt.rtzr.query}") String query,
                         @Value("${scamshield.stt.rtzr.keywords}") String keywords,
                         @Value("${scamshield.stt.rtzr.eos-wait-ms}") long eosWaitMs) {
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new IllegalStateException("RTZR_CLIENT_ID / RTZR_CLIENT_SECRET 가 비어 있다 (backend/app/.env)");
        this.json = json;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.query = keywords.isBlank() ? query
                : query + "&keywords=" + URLEncoder.encode(keywords, StandardCharsets.UTF_8);
        this.eosWaitMs = eosWaitMs;
    }

    /** 토큰은 6시간짜리. 만료 10분 전에 새로 받는다. */
    synchronized String token() throws Exception {
        if (token != null && Instant.now().getEpochSecond() < tokenExpireAt - 600) return token;
        String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://" + baseUrl + "/v1/authenticate"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("RTZR 인증 실패 HTTP " + r.statusCode());
        JsonNode b = json.readTree(r.body());
        token = b.get("access_token").asString();
        tokenExpireAt = b.path("expire_at").asLong(Instant.now().getEpochSecond() + 6 * 3600);
        return token;
    }

    @Override
    public SttSession open(String callId, String trackId, int streamEpoch, Listener listener) {
        Receiver rx = new Receiver(trackId, streamEpoch, listener);
        WebSocket ws;
        try {
            ws = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("wss://" + baseUrl + "/v1/transcribe:streaming?" + query), rx)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            listener.onError(e);
            return new SttSession() {
                @Override public void sendAudio(byte[] pcm16) { }
                @Override public void close() { }
            };
        }
        log.info("RTZR 세션 열림 callId={} trackId={}", callId, trackId);

        return new SttSession() {
            private boolean closed;

            /** java.net.http.WebSocket 은 이전 전송이 끝나기 전에 다음을 보내면 예외 — 한 번에 하나씩. */
            @Override
            public synchronized void sendAudio(byte[] pcm16) {
                if (closed || rx.done.isDone()) return;
                ws.sendBinary(ByteBuffer.wrap(pcm16), true).join();
            }

            /** EOS 를 보내고 서버가 마지막 확정 결과를 보내고 닫을 때까지 잠깐 기다린다. */
            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                try {
                    if (!rx.done.isDone()) {
                        rx.eosSent = true;
                        ws.sendText("EOS", true).join();
                        rx.done.get(eosWaitMs, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    log.warn("RTZR EOS 대기 중 종료 trackId={}: {}", trackId, e.toString());
                } finally {
                    ws.abort();
                    log.info("RTZR 세션 닫힘 callId={} trackId={}", callId, trackId);
                }
            }
        };
    }

    /** 수신 쪽. 텍스트 프레임이 쪼개져 올 수 있어 last 까지 모은다. */
    private final class Receiver implements WebSocket.Listener {
        private final String trackId;
        private final int epoch;
        private final Listener listener;
        private final StringBuilder buf = new StringBuilder();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile boolean eosSent;

        Receiver(String trackId, int epoch, Listener listener) {
            this.trackId = trackId;
            this.epoch = epoch;
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handle(msg);
                } catch (Exception e) {
                    log.warn("RTZR 응답 처리 실패: {}", msg, e);
                }
            }
            ws.request(1);
            return null;
        }

        private void handle(String msg) {
            JsonNode n = json.readTree(msg);
            if (!n.path("final").asBoolean(false)) return;
            JsonNode alt = n.path("alternatives").path(0);
            String text = alt.path("text").asString("").strip();
            if (text.isEmpty()) return;
            long start = n.path("start_at").asLong();
            Float conf = alt.has("confidence") ? (float) alt.get("confidence").asDouble() : null;
            // segmentId: 같은 연결(epoch) 안에서 RTZR seq 가 유일하다
            listener.onFinal(new Segment(trackId + "-e" + epoch + "-" + n.path("seq").asLong(),
                    start, start + n.path("duration").asLong(), text, conf, Instant.now()));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            done.complete(null);
            // 우리가 EOS 를 안 보냈는데 닫혔다 = 무음 60초·한도 초과 등. 업로더가 epoch 올려 다시 붙는다
            if (!eosSent) listener.onError(new IllegalStateException("RTZR 연결 종료 " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            done.complete(null);
            listener.onError(error);
        }
    }
}

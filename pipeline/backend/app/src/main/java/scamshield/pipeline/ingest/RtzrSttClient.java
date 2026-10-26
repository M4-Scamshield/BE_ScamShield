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
 * RTZR 이 먼저 끊으면(무음 60초) 업로드 연결은 살려 두고 RTZR 쪽만 다시 붙는다.
 */
@Component
@ConditionalOnProperty(name = "scamshield.stt.mode", havingValue = "rtzr")
public class RtzrSttClient implements SttClient {

    private static final Logger log = LoggerFactory.getLogger(RtzrSttClient.class);
    private static final long BYTES_PER_MS = 32;      // 16kHz × 2바이트
    private static final long MIN_ALIVE_MS = 5_000;   // 이보다 짧게 살다 끊기면 "바로 끊김"
    private static final int MAX_QUICK_FAILURES = 3;

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
        Session s = new Session(callId, trackId, streamEpoch, listener);
        try {
            s.connect();
        } catch (Exception e) {
            s.closed = true;
            listener.onError(e);
        }
        return s;
    }

    /**
     * 업로드 연결 하나에 붙은 RTZR 연결. RTZR 이 먼저 닫으면(무음 60초 등) 업로드는 그대로 두고
     * 다음 음성이 올 때 RTZR 연결만 새로 연다. 연결이 곧바로 거듭 끊기면(한도 초과 등) 포기하고 onError.
     */
    private final class Session implements SttSession {
        private final String callId;
        private final String trackId;
        private final int epoch;
        private final Listener listener;
        private WebSocket ws;
        private Receiver rx;
        private int gen;             // 같은 업로드 안에서 RTZR 에 다시 붙은 횟수
        private long sentBytes;
        private long connectedAt;
        private int quickFailures;
        boolean closed;

        Session(String callId, String trackId, int epoch, Listener listener) {
            this.callId = callId;
            this.trackId = trackId;
            this.epoch = epoch;
            this.listener = listener;
        }

        /** RTZR 시각은 연결마다 0 부터라, 그동안 보낸 음성 길이를 더해 업로드 기준 시각으로 맞춘다. */
        void connect() throws Exception {
            Receiver next = new Receiver(trackId, epoch, gen, sentBytes / BYTES_PER_MS, listener);
            ws = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("wss://" + baseUrl + "/v1/transcribe:streaming?" + query), next)
                    .get(10, TimeUnit.SECONDS);
            rx = next;
            connectedAt = System.currentTimeMillis();
            log.info("RTZR 세션 열림 callId={} trackId={} gen={}", callId, trackId, gen);
        }

        /** java.net.http.WebSocket 은 이전 전송이 끝나기 전에 다음을 보내면 예외 — 한 번에 하나씩. */
        @Override
        public synchronized void sendAudio(byte[] pcm16) {
            if (closed) return;
            if (rx.done.isDone() && !reconnect()) return;
            ws.sendBinary(ByteBuffer.wrap(pcm16), true).join();
            sentBytes += pcm16.length;
        }

        /** 이번 조각은 버리고 다음 조각에서 다시 시도한다. 바로 끊기는 일이 이어지면 포기. */
        private boolean reconnect() {
            ws.abort();
            if (System.currentTimeMillis() - connectedAt < MIN_ALIVE_MS) quickFailures++;
            else quickFailures = 0;
            if (quickFailures >= MAX_QUICK_FAILURES) {
                closed = true;
                listener.onError(new IllegalStateException("RTZR 연결이 열리자마자 거듭 끊김 trackId=" + trackId));
                return false;
            }
            gen++;
            try {
                connect();
                return true;
            } catch (Exception e) {
                log.warn("RTZR 재연결 실패 trackId={} gen={}: {}", trackId, gen, e.toString());
                connectedAt = System.currentTimeMillis(); // 실패도 "바로 끊김" 으로 센다
                return false;
            }
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
    }

    /** 수신 쪽. 텍스트 프레임이 쪼개져 올 수 있어 last 까지 모은다. */
    private final class Receiver implements WebSocket.Listener {
        private final String trackId;
        private final int epoch;
        private final int gen;
        private final long offsetMs;
        private final StringBuilder buf = new StringBuilder();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile boolean eosSent;
        private final Listener listener;

        Receiver(String trackId, int epoch, int gen, long offsetMs, Listener listener) {
            this.trackId = trackId;
            this.listener = listener;
            this.epoch = epoch;
            this.gen = gen;
            this.offsetMs = offsetMs;
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
                    log.warn("RTZR 응답 처리 실패 trackId={} len={}: {}", trackId, msg.length(), e.toString()); // 원문은 통화 내용이라 남기지 않는다
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
            long start = offsetMs + n.path("start_at").asLong();
            Float conf = alt.has("confidence") ? (float) alt.get("confidence").asDouble() : null;
            // segmentId: RTZR seq 는 연결마다 0 부터라 (epoch, gen) 안에서만 유일하다
            listener.onFinal(new Segment(trackId + "-e" + epoch + "r" + gen + "-" + n.path("seq").asLong(),
                    start, start + n.path("duration").asLong(), text, conf, Instant.now()));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            done.complete(null);
            // 우리가 EOS 를 안 보냈는데 닫혔다 = 무음 60초·한도 초과 등. 다음 음성이 오면 Session 이 다시 붙는다
            if (!eosSent) log.info("RTZR 가 먼저 닫음 trackId={} gen={} status={}", trackId, gen, statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            done.complete(null);
            log.warn("RTZR 연결 오류 trackId={} gen={}: {}", trackId, gen, error.toString());
        }
    }
}

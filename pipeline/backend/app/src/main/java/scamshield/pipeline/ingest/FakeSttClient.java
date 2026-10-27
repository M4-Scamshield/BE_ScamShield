package scamshield.pipeline.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 가짜 STT. 음성 내용은 보지 않고, 정해진 바이트(기본 3초 분량)가 쌓일 때마다
 * fake/script.txt 의 다음 줄을 확정 발화로 낸다. 업로드 경로·지연 측정용.
 */
@Component
@ConditionalOnProperty(name = "scamshield.stt.mode", havingValue = "fake", matchIfMissing = true)
public class FakeSttClient implements SttClient {

    private static final int BYTES_PER_MS = 32; // 16kHz × 2바이트

    private final List<String> lines;
    private final int bytesPerUtterance;

    public FakeSttClient(@Value("${scamshield.stt.fake.bytes-per-utterance}") int bytesPerUtterance) {
        this.bytesPerUtterance = bytesPerUtterance;
        try {
            this.lines = new ClassPathResource("fake/script.txt").getContentAsString(StandardCharsets.UTF_8)
                    .lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public SttSession open(String callId, String trackId, int streamEpoch, Listener listener) {
        return new SttSession() {
            private long received;
            private long emittedUpTo;
            private final AtomicInteger next = new AtomicInteger();

            @Override
            public synchronized void sendAudio(byte[] pcm16) {
                received += pcm16.length;
                while (received - emittedUpTo >= bytesPerUtterance) emit(emittedUpTo + bytesPerUtterance);
            }

            @Override
            public synchronized void close() {
                if (received > emittedUpTo) emit(received);
            }

            private void emit(long upTo) {
                String text = lines.get(next.getAndIncrement() % lines.size());
                listener.onFinal(new Segment(trackId + "-" + next.get(),
                        emittedUpTo / BYTES_PER_MS, upTo / BYTES_PER_MS, text, null, Instant.now()));
                emittedUpTo = upTo;
            }
        };
    }
}

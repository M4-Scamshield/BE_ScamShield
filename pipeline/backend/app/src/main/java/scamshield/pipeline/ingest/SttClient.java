package scamshield.pipeline.ingest;

import java.time.Instant;

/**
 * STT 연결 지점(포트). 업체가 바뀌어도 이 인터페이스 뒤의 구현체만 바꾼다.
 * 구현: FakeSttClient(지금) / RtzrSttClient(리턴제로 스트리밍, 확정 후).
 *
 * 한 세션 = 한 화자의 업로드 연결 하나 (callId, trackId, streamEpoch).
 */
public interface SttClient {

    SttSession open(String callId, String trackId, int streamEpoch, Listener listener);

    interface SttSession extends AutoCloseable {
        /** PCM16 mono 16kHz little-endian 조각. 크기 자유. */
        void sendAudio(byte[] pcm16);

        /** 업로드가 끝났다. 남은 음성을 확정해 달라(리턴제로 FINALIZE에 해당). */
        @Override void close();
    }

    interface Listener {
        /** 확정(final) 구간만 넘긴다. MVP는 부분 결과를 쓰지 않는다. */
        void onFinal(Segment segment);

        void onError(Throwable error);
    }

    record Segment(String segmentId, long startMs, long endMs, String text, Float confidence, Instant finalAt) {}
}

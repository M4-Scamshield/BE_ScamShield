package scamshield.pipeline.common;

import java.time.Instant;

/**
 * Kafka 토픽 utterance 의 값. 키 = callId (같은 통화는 한 파티션 → 순서 보존).
 * 내 파트 내부 계약이다(ingest → scheduling). seq 는 여기 없다 — 접수 때 서버가 매긴다.
 *
 * kind = SPEECH(STT 확정 발화) | INPUT(사용자 입력: 링크·계좌)
 */
public record UtteranceEvent(
        String eventId,
        String kind,
        String callId,
        String trackId,
        int streamEpoch,
        String segmentId,
        int revision,
        Long startMs,
        Long endMs,
        String text,
        boolean isFinal,
        Float confidence,
        String inputType,
        Long replyToEvent,
        Instant capturedAt,
        Instant sttFinalAt,
        Instant publishedAt) {

    public static final String SPEECH = "SPEECH";
    public static final String INPUT = "INPUT";
}

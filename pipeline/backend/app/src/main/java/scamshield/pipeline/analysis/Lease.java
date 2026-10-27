package scamshield.pipeline.analysis;

/** 임대 한 건. token 이 맞아야 결과를 반영하거나 반납할 수 있다(늦게 온 이전 시도 거르기). */
public record Lease(long jobId, String callId, long coveredFrom, long coveredTo, int attempt,
                    String token, long baseStateVersion) {}

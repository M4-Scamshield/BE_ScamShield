package com.scamshield.domain.call.model;

/**
 * 통화 참가자.
 *
 * @param id 서버가 발급한 임시 ID (p1, p2, ...). 브라우저가 정할 수 없다
 * @param name 화면 표시용 이름 (본인 확인된 값 아님)
 * @param joinedAt 입장 시각 (epoch ms)
 */
public record Participant(String id, String name, long joinedAt) {}

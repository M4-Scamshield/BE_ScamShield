package com.scamshield.domain.call.dto.message;

/** 서버 → 남은 참가자. 퇴장 알림 */
public record PeerLeftMessage(String type, String id) {

    public static PeerLeftMessage of(String participantId) {
        return new PeerLeftMessage("peer-left", participantId);
    }
}

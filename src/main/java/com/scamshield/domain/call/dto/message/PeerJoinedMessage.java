package com.scamshield.domain.call.dto.message;

import com.scamshield.domain.call.model.Participant;

/** 서버 → 기존 참가자. 새 참가자 입장 알림 */
public record PeerJoinedMessage(String type, Participant peer) {

    public static PeerJoinedMessage of(Participant peer) {
        return new PeerJoinedMessage("peer-joined", peer);
    }
}

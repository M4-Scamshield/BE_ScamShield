package com.scamshield.domain.call.dto.message;

import com.scamshield.domain.call.model.Participant;
import java.util.List;

/** 서버 → 입장한 본인. 내 ID, 통화 ID, 먼저 와 있던 사람들 */
public record JoinedMessage(String type, Participant self, String callId, List<Participant> peers) {

    public static JoinedMessage of(Participant self, String callId, List<Participant> peers) {
        return new JoinedMessage("joined", self, callId, peers);
    }
}

package com.scamshield.domain.call.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scamshield.domain.call.dto.message.JoinRequest;
import com.scamshield.domain.call.dto.message.SignalRequest;
import com.scamshield.domain.call.error.CallErrorCode;
import com.scamshield.domain.call.repository.CallRoomRepository;
import com.scamshield.domain.call.service.CallService.JoinResult;
import com.scamshield.domain.call.service.CallService.LeaveResult;
import com.scamshield.global.config.call.CallProperties;
import com.scamshield.global.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class CallServiceTest {

    private CallService callService;

    @BeforeEach
    void setUp() {
        callService =
                new CallService(
                        new CallRoomRepository(), new CallProperties(2, List.of("stun:example")));
    }

    private JoinResult join(String sessionId, String room) {
        return callService.join(sessionId, new JoinRequest(room, "이름-" + sessionId));
    }

    @Test
    @DisplayName("입장하면 먼저 와 있던 사람 목록과 그 사람들의 세션을 돌려준다")
    void join_returnsOthers() {
        JoinResult first = join("s1", "room");
        JoinResult second = join("s2", "room");

        assertThat(first.others()).isEmpty();
        assertThat(second.callId()).isEqualTo(first.callId());
        assertThat(second.others()).extracting("id").containsExactly(first.self().id());
        assertThat(second.otherSessionIds()).containsExactly("s1");
    }

    @Test
    @DisplayName("1:1 통화라 3번째 입장은 ROOM_FULL")
    void join_roomFull() {
        for (int i = 1; i <= 2; i++) {
            join("s" + i, "room");
        }

        assertThatThrownBy(() -> join("s3", "room"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(CallErrorCode.ROOM_FULL);
        assertThat(CallErrorCode.ROOM_FULL.getWsCloseCode()).isEqualTo(4001);
    }

    @Test
    @DisplayName("같은 세션이 두 번 입장하면 ALREADY_JOINED")
    void join_twice() {
        join("s1", "room");

        assertThatThrownBy(() -> join("s1", "room"))
                .extracting("errorCode")
                .isEqualTo(CallErrorCode.ALREADY_JOINED);
    }

    @Test
    @DisplayName("signal 은 같은 방 상대에게만, from 은 서버가 정한 내 ID 로 전달된다")
    void relay_sameRoomOnly() {
        JoinResult a = join("s1", "room");
        JoinResult b = join("s2", "room");
        JoinResult other = join("s3", "other-room");
        var data = JsonNodeFactory.instance.objectNode().put("x", 1);

        CallService.Relay relay = callService.relay("s1", new SignalRequest(b.self().id(), data));
        assertThat(relay.targetSessionId()).isEqualTo("s2");
        assertThat(relay.message().from()).isEqualTo(a.self().id());

        assertThatThrownBy(() -> callService.relay("s1", new SignalRequest(other.self().id(), data)))
                .extracting("errorCode")
                .isEqualTo(CallErrorCode.PEER_NOT_IN_ROOM);
    }

    @Test
    @DisplayName("입장 전에 signal 을 보내면 NOT_JOINED")
    void relay_notJoined() {
        assertThatThrownBy(() -> callService.relay("nobody", new SignalRequest("p1", null)))
                .extracting("errorCode")
                .isEqualTo(CallErrorCode.NOT_JOINED);
    }

    @Test
    @DisplayName("마지막 사람이 나가면 통화가 끝나고, 같은 방 이름으로 다시 열면 새 callId")
    void leave_endsCall() {
        JoinResult a = join("s1", "room");
        join("s2", "room");

        LeaveResult first = callService.leave("s2").orElseThrow();
        assertThat(first.callEnded()).isFalse();
        assertThat(first.remainingSessionIds()).containsExactly("s1");

        LeaveResult last = callService.leave("s1").orElseThrow();
        assertThat(last.callEnded()).isTrue();
        assertThat(callService.leave("s1")).isEmpty();

        JoinResult reopened = join("s3", "room");
        assertThat(reopened.callId()).isNotEqualTo(a.callId());
    }

    @Test
    @DisplayName("방 이름과 이름은 앞뒤 공백을 지우고, 비어 있으면 기본값")
    void join_cleansNames() {
        JoinResult r = callService.join("s1", new JoinRequest("  ", "   "));

        assertThat(r.self().name()).isEqualTo("참가자");
        assertThat(callService.join("s2", new JoinRequest("demo", "a")).callId())
                .isEqualTo(r.callId());
    }
}

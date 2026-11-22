package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import scamshield.pipeline.access.CallAuth;

/** 방 하나 = 분석 하나. 참가자마다 토큰을 따로 받아도 같은 callId 에 붙는가. */
class RoomSessionTest extends PipelineTestSupport {

    @Autowired CallAuth auth;

    @Test
    @DisplayName("같은 방이면 같은 callId, 토큰은 참가자마다 다르고 둘 다 통한다")
    void sameRoomSharesCall() {
        CallAuth.Issued a = auth.issue("room-1");
        CallAuth.Issued b = auth.issue("room-1");
        assertEquals(a.callId(), b.callId());
        assertNotEquals(a.token(), b.token());
        assertTrue(auth.check(a.callId(), a.token()));
        assertTrue(auth.check(b.callId(), b.token()));
    }

    @Test
    @DisplayName("다른 방, 방 없는 요청은 각자 새 통화이고 남의 통화 토큰은 안 통한다")
    void otherRoomsAreSeparate() {
        CallAuth.Issued a = auth.issue("room-1");
        CallAuth.Issued b = auth.issue("room-2");
        CallAuth.Issued solo = auth.issue();
        assertNotEquals(a.callId(), b.callId());
        assertNotEquals(a.callId(), solo.callId());
        assertFalse(auth.check(a.callId(), b.token()));
        assertTrue(auth.check(solo.callId(), solo.token()));
    }
}

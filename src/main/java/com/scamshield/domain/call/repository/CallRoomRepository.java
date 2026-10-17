package com.scamshield.domain.call.repository;

import com.scamshield.domain.call.model.CallRoom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 통화방 저장소. 지금은 서버 메모리에만 둔다 (서버 1대 기준). 여러 대로 늘릴 때 Redis 등으로 바꾼다.
 *
 * <p>스레드 안전하지 않다. 호출하는 CallService 가 동기화한다.
 */
@Repository
public class CallRoomRepository {

    private final Map<String, CallRoom> byName = new HashMap<>();
    private final Map<String, CallRoom> byCallId = new HashMap<>();
    /** 시그널링 세션 ID → callId */
    private final Map<String, String> callIdBySession = new HashMap<>();

    public Optional<CallRoom> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Optional<CallRoom> findByCallId(String callId) {
        return Optional.ofNullable(byCallId.get(callId));
    }

    public Optional<CallRoom> findBySessionId(String sessionId) {
        return Optional.ofNullable(callIdBySession.get(sessionId)).map(byCallId::get);
    }

    public void save(CallRoom room) {
        byName.put(room.getName(), room);
        byCallId.put(room.getCallId(), room);
    }

    public void bindSession(String sessionId, CallRoom room) {
        callIdBySession.put(sessionId, room.getCallId());
    }

    public void unbindSession(String sessionId) {
        callIdBySession.remove(sessionId);
    }

    public void delete(CallRoom room) {
        byName.remove(room.getName());
        byCallId.remove(room.getCallId());
    }
}

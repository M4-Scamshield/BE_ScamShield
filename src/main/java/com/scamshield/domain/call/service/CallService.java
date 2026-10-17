package com.scamshield.domain.call.service;

import com.scamshield.domain.call.dto.message.JoinRequest;
import com.scamshield.domain.call.dto.message.SignalMessage;
import com.scamshield.domain.call.dto.message.SignalRequest;
import com.scamshield.domain.call.error.CallErrorCode;
import com.scamshield.domain.call.model.CallMember;
import com.scamshield.domain.call.model.CallRoom;
import com.scamshield.domain.call.model.Participant;
import com.scamshield.domain.call.repository.CallRoomRepository;
import com.scamshield.global.config.call.CallProperties;
import com.scamshield.global.error.DomainException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 통화방 입장/퇴장/신호 전달 규칙.
 *
 * <p>WebSocket 을 직접 다루지 않고 세션 ID 만 받는다. 누구에게 무엇을 보낼지는 결과로 돌려주고, 실제 전송은 핸들러가 한다.
 * 방 상태는 여러 WebSocket 스레드가 같이 바꾸므로 lock 하나로 묶는다.
 */
@Slf4j
@Service
public class CallService {

    private static final int MAX_ROOM_NAME = 64;
    private static final int MAX_NAME = 20;

    private final CallRoomRepository repository;
    private final CallProperties properties;
    private final Clock clock;
    private final AtomicLong nextParticipant = new AtomicLong(1);
    private final Object lock = new Object();

    @Autowired
    public CallService(CallRoomRepository repository, CallProperties properties) {
        this(repository, properties, Clock.systemDefaultZone());
    }

    CallService(CallRoomRepository repository, CallProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param self 입장한 본인
     * @param others 먼저 와 있던 사람들
     * @param otherSessionIds 먼저 와 있던 사람들의 세션 (peer-joined 를 보낼 곳)
     */
    public record JoinResult(
            String callId, Participant self, List<Participant> others, List<String> otherSessionIds) {}

    /** 전달할 메시지와 받을 세션 */
    public record Relay(String targetSessionId, SignalMessage message) {}

    /**
     * @param left 퇴장한 참가자
     * @param remainingSessionIds 남은 참가자 세션 (peer-left 를 보낼 곳)
     * @param callEnded 마지막 사람이 나가 통화가 끝났는지
     */
    public record LeaveResult(
            String room, Participant left, List<String> remainingSessionIds, boolean callEnded) {}

    public JoinResult join(String sessionId, JoinRequest request) {
        String roomName = clean(request.room(), MAX_ROOM_NAME, "demo");
        String name = clean(request.name(), MAX_NAME, "참가자");
        long now = clock.millis();
        synchronized (lock) {
            if (repository.findBySessionId(sessionId).isPresent()) {
                throw new DomainException(CallErrorCode.ALREADY_JOINED);
            }
            CallRoom room = repository.findByName(roomName).orElse(null);
            if (room != null && room.size() >= properties.maxParticipants()) {
                log.info("[{}] 정원 초과로 입장 거절 ({}명)", roomName, room.size());
                throw new DomainException(CallErrorCode.ROOM_FULL);
            }
            if (room == null) {
                room = CallRoom.open(roomName, now);
                repository.save(room);
            }

            List<CallMember> others = room.memberList();
            Participant self =
                    new Participant("p" + nextParticipant.getAndIncrement(), name, now);
            room.add(new CallMember(self, sessionId));
            repository.bindSession(sessionId, room);

            log.info(
                    "[{}] 입장 {} {} (현재 {}명, {})",
                    roomName,
                    self.id(),
                    name,
                    room.size(),
                    room.getCallId());
            return new JoinResult(
                            room.getCallId(),
                            self,
                            others.stream().map(CallMember::participant).toList(),
                            others.stream().map(CallMember::sessionId).toList());
        }
    }

    public Relay relay(String sessionId, SignalRequest request) {
        synchronized (lock) {
            CallRoom room =
                    repository
                            .findBySessionId(sessionId)
                            .orElseThrow(() -> new DomainException(CallErrorCode.NOT_JOINED));
            CallMember from =
                    room.memberBySession(sessionId)
                            .orElseThrow(() -> new DomainException(CallErrorCode.NOT_JOINED));
            // 같은 방 안에서만 전달한다
            CallMember to =
                    room.member(request.to())
                            .orElseThrow(() -> new DomainException(CallErrorCode.PEER_NOT_IN_ROOM));
            return new Relay(to.sessionId(), SignalMessage.of(from.participant().id(), request.data()));
        }
    }

    /** 입장하지 않았던 세션이면 empty */
    public Optional<LeaveResult> leave(String sessionId) {
        long now = clock.millis();
        synchronized (lock) {
            Optional<CallRoom> found = repository.findBySessionId(sessionId);
            repository.unbindSession(sessionId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            CallRoom room = found.get();
            Optional<CallMember> member = room.memberBySession(sessionId);
            if (member.isEmpty()) {
                return Optional.empty();
            }
            Participant left = member.get().participant();
            room.remove(left.id());

            boolean ended = room.isEmpty();
            if (ended) {
                repository.delete(room);
            }
            log.info("[{}] 퇴장 {} (남은 {}명)", room.getName(), left.id(), room.size());
            return Optional.of(
                    new LeaveResult(
                            room.getName(),
                            left,
                            room.memberList().stream().map(CallMember::sessionId).toList(),
                            ended));
        }
    }

    private static String clean(String value, int max, String fallback) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return fallback;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}

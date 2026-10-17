package com.scamshield.domain.call.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;

/**
 * 통화방.
 *
 * <p>방 이름은 재사용될 수 있으므로, 방이 새로 열릴 때마다 새 callId 를 발급해 통화를 구분한다.
 */
@Getter
public class CallRoom {

    private final String name;
    private final String callId;
    private final long startedAt;

    /** participantId → 참가자 (입장 순서 유지) */
    private final Map<String, CallMember> members = new LinkedHashMap<>();

    private CallRoom(String name, String callId, long startedAt) {
        this.name = name;
        this.callId = callId;
        this.startedAt = startedAt;
    }

    public static CallRoom open(String name, long now) {
        return new CallRoom(name, "call-" + UUID.randomUUID(), now);
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public void add(CallMember member) {
        members.put(member.participant().id(), member);
    }

    public Optional<CallMember> remove(String participantId) {
        return Optional.ofNullable(members.remove(participantId));
    }

    public Optional<CallMember> member(String participantId) {
        return Optional.ofNullable(members.get(participantId));
    }

    public Optional<CallMember> memberBySession(String sessionId) {
        return members.values().stream().filter(m -> m.sessionId().equals(sessionId)).findFirst();
    }

    public List<CallMember> memberList() {
        return List.copyOf(members.values());
    }

    public List<Participant> participants() {
        return members.values().stream().map(CallMember::participant).toList();
    }
}

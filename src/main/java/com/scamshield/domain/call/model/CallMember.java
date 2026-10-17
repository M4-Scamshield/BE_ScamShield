package com.scamshield.domain.call.model;

/** 방에 들어와 있는 참가자와 그 사람의 시그널링 WebSocket 세션 ID */
public record CallMember(Participant participant, String sessionId) {}

package com.scamshield.domain.call.dto.message;

/**
 * 입장 요청. 연결 후 첫 메시지.
 *
 * <pre>{"type":"join","room":"demo","name":"민수"}</pre>
 */
public record JoinRequest(String room, String name) implements SignalingRequest {}

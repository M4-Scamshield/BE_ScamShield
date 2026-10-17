package com.scamshield.domain.call.dto.response;

import java.util.List;

/**
 * 브라우저가 RTCPeerConnection 을 만들 때 쓸 설정.
 *
 * @param iceServers RTCConfiguration.iceServers 에 그대로 넣는 값
 * @param maxParticipants 방 최대 인원
 */
public record CallConfigResponse(List<IceServer> iceServers, int maxParticipants) {

    public record IceServer(List<String> urls) {}
}

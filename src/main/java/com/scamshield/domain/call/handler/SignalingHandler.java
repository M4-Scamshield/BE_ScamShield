package com.scamshield.domain.call.handler;

import com.scamshield.domain.call.dto.message.JoinRequest;
import com.scamshield.domain.call.dto.message.JoinedMessage;
import com.scamshield.domain.call.dto.message.PeerJoinedMessage;
import com.scamshield.domain.call.dto.message.PeerLeftMessage;
import com.scamshield.domain.call.dto.message.SignalRequest;
import com.scamshield.domain.call.dto.message.SignalingRequest;
import com.scamshield.domain.call.service.CallService;
import com.scamshield.domain.call.service.CallService.JoinResult;
import com.scamshield.domain.call.service.CallService.Relay;
import com.scamshield.global.websocket.JsonMessageHandler;
import com.scamshield.global.websocket.WsErrorResponder;
import com.scamshield.global.websocket.WsSender;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * /ws/signal 통화 시그널링. SDP/ICE 만 중계하고, 음성은 이 서버를 지나가지 않는다.
 */
@Component
public class SignalingHandler extends JsonMessageHandler<SignalingRequest> {

    private final CallService callService;

    public SignalingHandler(
            JsonMapper jsonMapper,
            WsSender sender,
            WsErrorResponder errors,
            CallService callService) {
        super(jsonMapper, sender, errors, SignalingRequest.class);
        this.callService = callService;
    }

    @Override
    protected void handle(WebSocketSession session, SignalingRequest message) {
        switch (message) {
            case JoinRequest join -> join(session, join);
            case SignalRequest signal -> relay(session, signal);
        }
    }

    private void join(WebSocketSession session, JoinRequest request) {
        JoinResult r = callService.join(session.getId(), request);
        sessions.send(session.getId(), JoinedMessage.of(r.self(), r.callId(), r.others()));
        r.otherSessionIds().forEach(id -> sessions.send(id, PeerJoinedMessage.of(r.self())));
    }

    private void relay(WebSocketSession session, SignalRequest request) {
        Relay r = callService.relay(session.getId(), request);
        sessions.send(r.targetSessionId(), r.message());
    }

    @Override
    protected void onClose(String sessionId, CloseStatus status) {
        callService
                .leave(sessionId)
                .ifPresent(
                        r ->
                                r.remainingSessionIds()
                                        .forEach(
                                                id ->
                                                        sessions.send(
                                                                id,
                                                                PeerLeftMessage.of(
                                                                        r.left().id()))));
    }
}

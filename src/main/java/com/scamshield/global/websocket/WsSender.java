package com.scamshield.global.websocket;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/** 객체를 JSON 으로 바꿔 WebSocket 으로 보낸다. 실패해도 예외를 던지지 않고 로그만 남긴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSender {

    private final JsonMapper jsonMapper;

    public void send(WebSocketSession session, Object message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
            }
        } catch (IOException | RuntimeException e) {
            if (session.isOpen()) {
                log.warn("WebSocket 전송 실패 {}: {}", session.getId(), e.toString());
            } else {
                // 상대가 막 연결을 끊은 경우. 정상적인 경쟁 상황이라 디버그로만 남긴다
                log.debug("닫히는 세션에 전송 실패 {}: {}", session.getId(), e.toString());
            }
        }
    }
}

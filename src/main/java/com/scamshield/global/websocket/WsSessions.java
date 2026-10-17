package com.scamshield.global.websocket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 한 엔드포인트에 연결된 세션 목록. 여러 스레드가 같은 세션에 동시에 보내도 안전하도록 세션을 감싸서 보관한다.
 *
 * <p>서비스는 세션 ID 만 다루고, 실제 전송은 핸들러가 이 클래스로 한다.
 */
public class WsSessions {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 256 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final WsSender sender;

    public WsSessions(WsSender sender) {
        this.sender = sender;
    }

    public WebSocketSession register(WebSocketSession raw) {
        WebSocketSession safe =
                new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        sessions.put(raw.getId(), safe);
        return safe;
    }

    public Optional<WebSocketSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public void send(String sessionId, Object message) {
        get(sessionId).ifPresent(s -> sender.send(s, message));
    }
}

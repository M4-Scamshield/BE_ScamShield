package com.scamshield.global.websocket;

import com.scamshield.global.error.DomainException;
import com.scamshield.global.error.GlobalErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 메시지를 주고받는 WebSocket 핸들러의 공통 베이스.
 *
 * <ul>
 *   <li>받은 텍스트를 {@code T} 로 변환해서 {@link #handle} 에 넘긴다 ("type" 필드로 하위 타입 결정)
 *   <li>{@link DomainException} 은 {@link WsErrorResponder} 가 에러 메시지/연결 종료로 바꾼다
 *   <li>하위 클래스는 비즈니스 흐름만 구현하면 된다 (try/catch 불필요)
 * </ul>
 */
@Slf4j
public abstract class JsonMessageHandler<T> extends TextWebSocketHandler {

    protected final WsSessions sessions;
    private final JsonMapper jsonMapper;
    private final WsErrorResponder errors;
    private final Class<T> messageType;

    protected JsonMessageHandler(
            JsonMapper jsonMapper, WsSender sender, WsErrorResponder errors, Class<T> messageType) {
        this.sessions = new WsSessions(sender);
        this.jsonMapper = jsonMapper;
        this.errors = errors;
        this.messageType = messageType;
    }

    /** 메시지 하나 처리. 규칙 위반은 DomainException 을 던지면 된다. */
    protected abstract void handle(WebSocketSession session, T message);

    /** 연결 종료 후 정리 (예외를 던져도 다른 세션에는 영향 없음) */
    protected void onClose(String sessionId, CloseStatus status) {}

    @Override
    public final void afterConnectionEstablished(WebSocketSession raw) {
        sessions.register(raw);
    }

    @Override
    protected final void handleTextMessage(WebSocketSession raw, TextMessage text) {
        WebSocketSession session = sessions.get(raw.getId()).orElse(raw);
        T message;
        try {
            message = jsonMapper.readValue(text.getPayload(), messageType);
        } catch (InvalidTypeIdException e) {
            log.debug("알 수 없는 메시지 type {}: {}", raw.getId(), e.getOriginalMessage());
            errors.respond(session, GlobalErrorCode.INVALID_INPUT_VALUE);
            return;
        } catch (JacksonException e) {
            log.debug("JSON 파싱 실패 {}: {}", raw.getId(), e.getOriginalMessage());
            errors.respond(session, GlobalErrorCode.INVALID_JSON);
            return;
        }

        try {
            handle(session, message);
        } catch (DomainException e) {
            log.info("[{}] {} {}", raw.getId(), e.getErrorCode().getCode(), e.getMessage());
            errors.respond(session, e.getErrorCode());
        } catch (RuntimeException e) {
            errors.respondUnexpected(session, e);
        }
    }

    @Override
    public final void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        try {
            onClose(raw.getId(), status);
        } catch (RuntimeException e) {
            log.error("연결 종료 처리 실패 {}", raw.getId(), e);
        } finally {
            sessions.unregister(raw.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("전송 오류 {}: {}", session.getId(), exception.toString());
    }
}

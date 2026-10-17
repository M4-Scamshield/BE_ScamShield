package com.scamshield.global.websocket;

import com.scamshield.global.error.ErrorCode;
import com.scamshield.global.error.GlobalErrorCode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 예외 응답. REST 의 GlobalExceptionHandler 와 같은 역할.
 *
 * <ol>
 *   <li>에러 메시지 {@code {"type":"error","code":...,"message":...}} 를 보낸다
 *   <li>{@link ErrorCode#getWsCloseCode()} 가 0 이 아니면 그 코드로 연결을 끊는다
 *   <li>예상하지 못한 예외는 GLOBAL_500 을 보내고 1011(SERVER_ERROR)로 끊는다
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsErrorResponder {

    private final WsSender sender;

    public void respond(WebSocketSession session, ErrorCode errorCode) {
        sender.send(session, WsErrorMessage.of(errorCode));
        if (errorCode.getWsCloseCode() != 0) {
            close(session, new CloseStatus(errorCode.getWsCloseCode(), errorCode.getCode()));
        }
    }

    public void respondUnexpected(WebSocketSession session, Exception e) {
        log.error("WebSocket 처리 중 예상하지 못한 오류 {}", session.getId(), e);
        sender.send(session, WsErrorMessage.of(GlobalErrorCode.INTERNAL_SERVER_ERROR));
        close(session, CloseStatus.SERVER_ERROR);
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("WebSocket 닫기 실패 {}: {}", session.getId(), e.toString());
        }
    }
}

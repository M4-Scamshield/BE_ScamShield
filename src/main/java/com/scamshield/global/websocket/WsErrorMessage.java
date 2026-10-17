package com.scamshield.global.websocket;

import com.scamshield.global.error.ErrorCode;

/**
 * WebSocket 으로 보내는 에러 메시지. REST 의 ApiResponse.error 와 같은 code/message 를 쓴다.
 *
 * <pre>{"type":"error","code":"CALL_001","message":"방이 가득 찼습니다."}</pre>
 */
public record WsErrorMessage(String type, String code, String message) {

    public static WsErrorMessage of(ErrorCode errorCode) {
        return new WsErrorMessage("error", errorCode.getCode(), errorCode.getMessage());
    }
}

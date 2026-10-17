package com.scamshield.global.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 인터페이스. 도메인마다 이 인터페이스를 구현한 enum 을 만들어 관리한다.
 *
 * <p>REST 에서는 {@link #getHttpStatus()} 로 응답 상태를 정하고, WebSocket 에서는 {@link
 * #getWsCloseCode()} 로 연결을 끊을지 정한다.
 */
public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();

    /**
     * WebSocket 에서 이 에러가 났을 때 연결을 끊는 close code (4000~4999). 0 이면 에러 메시지만 보내고 연결은 유지한다.
     */
    default int getWsCloseCode() {
        return 0;
    }
}

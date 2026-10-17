package com.scamshield.domain.call.error;

import com.scamshield.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 통화(시그널링) 에러. wsCloseCode 가 0 이 아니면 WebSocket 연결을 그 코드로 끊는다. */
@Getter
@RequiredArgsConstructor
public enum CallErrorCode implements ErrorCode {
    ROOM_FULL(HttpStatus.CONFLICT, "CALL_001", "방이 가득 찼습니다.", 4001),
    NOT_JOINED(HttpStatus.BAD_REQUEST, "CALL_002", "먼저 방에 입장해야 합니다.", 0),
    PEER_NOT_IN_ROOM(HttpStatus.NOT_FOUND, "CALL_003", "같은 방에 없는 참가자입니다.", 0),
    ALREADY_JOINED(HttpStatus.BAD_REQUEST, "CALL_004", "이미 방에 입장했습니다.", 0);

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final int wsCloseCode;
}

package com.scamshield.domain.call.dto.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 브라우저 → 서버 시그널링 메시지. "type" 필드로 구분한다. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = JoinRequest.class, name = "join"),
    @JsonSubTypes.Type(value = SignalRequest.class, name = "signal")
})
public sealed interface SignalingRequest permits JoinRequest, SignalRequest {}

package com.scamshield.domain.call.dto.message;

import tools.jackson.databind.JsonNode;

/** 서버 → 받는 사람. 발신자 위조를 막기 위해 from 은 서버가 붙인다 */
public record SignalMessage(String type, String from, JsonNode data) {

    public static SignalMessage of(String from, JsonNode data) {
        return new SignalMessage("signal", from, data);
    }
}

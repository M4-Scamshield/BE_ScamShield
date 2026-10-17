package com.scamshield.domain.call.dto.message;

import tools.jackson.databind.JsonNode;

/**
 * 특정 참가자에게 보내는 WebRTC 협상 데이터. 서버는 data 를 해석하지 않고 그대로 전달한다.
 *
 * <pre>{"type":"signal","to":"p2","data":{"description":{...}} 또는 {"candidate":{...}}}</pre>
 */
public record SignalRequest(String to, JsonNode data) implements SignalingRequest {}

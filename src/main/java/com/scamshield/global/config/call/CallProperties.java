package com.scamshield.global.config.call;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 통화 설정 (application.yml 의 call.*)
 *
 * @param maxParticipants 방 하나 최대 인원 (1:1 통화라 2)
 * @param stunUrls 브라우저에 내려줄 STUN 서버. TURN 은 자격 증명이 필요해 별도 추가
 */
@ConfigurationProperties("call")
public record CallProperties(
        @DefaultValue("2") int maxParticipants,
        @DefaultValue("stun:stun.l.google.com:19302") List<String> stunUrls) {}

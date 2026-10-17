package com.scamshield.domain.call.controller;

import com.scamshield.domain.call.dto.response.CallConfigResponse;
import com.scamshield.domain.call.dto.response.CallConfigResponse.IceServer;
import com.scamshield.global.config.call.CallProperties;
import com.scamshield.global.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallConfigController {

    private final CallProperties properties;

    /** 브라우저가 통화를 시작하기 전에 받아 가는 설정 (ICE 서버, 최대 인원) */
    @GetMapping("/config")
    public ApiResponse<CallConfigResponse> config() {
        return ApiResponse.success(
                new CallConfigResponse(
                        List.of(new IceServer(properties.stunUrls())),
                        properties.maxParticipants()));
    }
}

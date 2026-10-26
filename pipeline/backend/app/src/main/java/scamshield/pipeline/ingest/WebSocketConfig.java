package scamshield.pipeline.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import scamshield.pipeline.common.ApiRole;

@Configuration
@EnableWebSocket
@ApiRole
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler handler;

    public WebSocketConfig(AudioWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // TODO 배포 전: 허용 Origin 을 우리 도메인으로 좁힌다
        registry.addHandler(handler, "/ws/audio").setAllowedOriginPatterns("*");
    }

    /** Tomcat 기본 바이너리 한도는 8KB — 그보다 큰 조각은 1009 로 끊긴다. 1초(32KB)×4 까지 허용. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxBinaryMessageBufferSize(128 * 1024);
        return c;
    }
}

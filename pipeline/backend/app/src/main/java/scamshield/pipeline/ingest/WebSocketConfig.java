package scamshield.pipeline.ingest;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import scamshield.pipeline.access.CallAuth;
import scamshield.pipeline.common.ApiRole;

@Configuration
@EnableWebSocket
@ApiRole
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler handler;
    private final CallAuth auth;
    private final String[] allowedOrigins;

    public WebSocketConfig(AudioWebSocketHandler handler, CallAuth auth,
                           @Value("${scamshield.web.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.auth = auth;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/audio")
                .addInterceptors(new TokenCheck())
                .setAllowedOriginPatterns(allowedOrigins);
    }

    /** 업그레이드 전에 ?callId=..&token=.. 을 확인한다. 틀리면 101 대신 401. */
    private final class TokenCheck implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest r
                    && auth.check(r.getServletRequest().getParameter("callId"), CallAuth.tokenOf(r.getServletRequest())))
                return true;
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) { }
    }

    /** Tomcat 기본 바이너리 한도는 8KB — 그보다 큰 조각은 1009 로 끊긴다. 1초(32KB)×4 까지 허용. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxBinaryMessageBufferSize(128 * 1024);
        return c;
    }
}

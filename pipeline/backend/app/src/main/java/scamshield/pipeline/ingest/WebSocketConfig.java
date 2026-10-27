package scamshield.pipeline.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Pattern;
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

    private static final Pattern TRACK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EPOCH = Pattern.compile("\\d{1,6}");

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

    /**
     * 업그레이드 전에 ?callId=..&token=.. 을 확인한다. 틀리면 101 대신 401, 형식이 틀리면 400.
     * 확인한 값을 그대로 세션 속성에 넣는다 — 핸들러가 URI 를 따로 다시 읽으면 디코딩 차이로 다른 callId 가 될 수 있다.
     */
    private final class TokenCheck implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest r)) return reject(response, HttpStatus.BAD_REQUEST);
            HttpServletRequest req = r.getServletRequest();
            String callId = req.getParameter("callId");
            String trackId = req.getParameter("trackId");
            String epoch = req.getParameter("epoch") == null ? "0" : req.getParameter("epoch");
            if (trackId == null || !TRACK_ID.matcher(trackId).matches() || !EPOCH.matcher(epoch).matches())
                return reject(response, HttpStatus.BAD_REQUEST);
            if (!auth.check(callId, CallAuth.tokenOf(req))) return reject(response, HttpStatus.UNAUTHORIZED);
            attributes.put(AudioWebSocketHandler.CALL_ID, callId);
            attributes.put(AudioWebSocketHandler.TRACK_ID, trackId);
            attributes.put(AudioWebSocketHandler.EPOCH, Integer.parseInt(epoch));
            attributes.put(AudioWebSocketHandler.CLIENT_IP, req.getRemoteAddr());
            return true;
        }

        private boolean reject(ServerHttpResponse response, HttpStatus status) {
            response.setStatusCode(status);
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

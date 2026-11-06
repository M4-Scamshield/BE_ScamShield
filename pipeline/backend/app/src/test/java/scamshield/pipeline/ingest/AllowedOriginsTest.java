package scamshield.pipeline.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

/**
 * 업로드 WS 의 기본 허용 Origin 이 웹 화면(localhost)과 Discord 확장을 받고 그 밖은 막는지.
 * WebSocketConfig 와 같은 방식(setAllowedOriginPatterns)으로 Spring 의 Origin 검사를 그대로 돌린다.
 */
class AllowedOriginsTest {

    static final String EXTENSION = "chrome-extension://opdocepfbahoocfkahckhnbhdedkibim";

    /** application.yml 의 ${ALLOWED_ORIGINS:기본값} 에서 기본값을 꺼낸다 */
    static List<String> defaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties p = yaml.getObject();
        String raw = p.getProperty("scamshield.web.allowed-origins");
        Matcher m = Pattern.compile("^\\$\\{ALLOWED_ORIGINS:(.*)}$").matcher(raw);
        assertThat(m.matches()).as("기본값 형식: %s", raw).isTrue();
        return Arrays.stream(m.group(1).split(",")).map(String::trim).toList();
    }

    static boolean allowed(String origin) throws Exception {
        OriginHandshakeInterceptor check = new OriginHandshakeInterceptor();
        check.setAllowedOriginPatterns(defaults());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/audio");
        req.setServerName("api.scamshield.test");
        req.addHeader("Origin", origin);
        MockHttpServletResponse res = new MockHttpServletResponse();
        return check.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(res), null, new HashMap<>());
    }

    @Test
    void 확장과_로컬_화면은_받는다() throws Exception {
        assertThat(allowed(EXTENSION)).isTrue();
        assertThat(allowed("http://localhost:5173")).isTrue();
        assertThat(allowed("http://127.0.0.1:8088")).isTrue();
    }

    @Test
    void 다른_확장과_다른_사이트는_막는다() throws Exception {
        assertThat(allowed("chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).isFalse();
        assertThat(allowed("https://discord.com")).isFalse();
        assertThat(allowed("https://evil.example")).isFalse();
    }
}

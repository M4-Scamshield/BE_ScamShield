package scamshield.pipeline.analysis;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 진짜 AI: POST {url} 에 AnalysisRequest JSON, 응답 AnalysisResponse JSON. 외부 API 여도 같은 모양(AI2). */
@Component
@ConditionalOnProperty(name = "scamshield.agent.mode", havingValue = "http")
public class HttpAgentClient implements AgentClient {

    private final RestClient rest;

    public HttpAgentClient(RestClient.Builder builder,
                           @Value("${scamshield.agent.url}") String url,
                           @Value("${scamshield.agent.timeout-ms}") long timeoutMs,
                           @Value("${scamshield.executor.lease-seconds}") int leaseSeconds) {
        // 임대가 응답 대기보다 먼저 끝나면 같은 구간을 다른 실행기가 또 맡는다 → 설정 실수를 시작할 때 막는다
        if (leaseSeconds * 1000L <= timeoutMs) {
            throw new IllegalStateException("scamshield.executor.lease-seconds(" + leaseSeconds
                    + "s) 는 scamshield.agent.timeout-ms(" + timeoutMs + "ms) 보다 길어야 한다");
        }
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        // 버퍼링 = Content-Length 를 붙여 보낸다. JDK 클라이언트 기본은 chunked 라 일부 서버(파이썬 http.server)가 본문을 못 읽는다
        this.rest = builder.baseUrl(url).requestFactory(new BufferingClientHttpRequestFactory(f)).build();
    }

    @Override
    public AnalysisResponse analyze(AnalysisRequest request) {
        return rest.post().contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(AnalysisResponse.class);
    }
}

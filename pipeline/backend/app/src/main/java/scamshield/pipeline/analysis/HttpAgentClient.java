package scamshield.pipeline.analysis;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
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
                           @Value("${scamshield.agent.timeout-ms}") long timeoutMs) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.rest = builder.baseUrl(url).requestFactory(f).build();
    }

    @Override
    public AnalysisResponse analyze(AnalysisRequest request) {
        return rest.post().contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(AnalysisResponse.class);
    }
}

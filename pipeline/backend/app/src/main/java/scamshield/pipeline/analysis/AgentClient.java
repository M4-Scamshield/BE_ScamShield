package scamshield.pipeline.analysis;

/**
 * AI 연결 지점(포트). AI 담당이 HTTP 엔드포인트 하나를 주면 HttpAgentClient, 그 전엔 FakeAgentClient.
 * 한 번 부르면 사용자에게 보일 행동 하나를 돌려받는다(AI1·AI2 가정, 명세 2.2).
 */
public interface AgentClient {
    AnalysisResponse analyze(AnalysisRequest request) throws Exception;
}

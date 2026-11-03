"""scam-agent(LangGraph 2AS: JEV 판단 → Yes 면 Gemini 근거·대응)를 파이프라인 계약 ②(POST /analyze)로 감싸는 얇은 다리.

- agent 코드는 고치지 않는다. 팀 레포를 import 해서 CallPipeline.run_turn 을 부른다.
- 상태를 여기서 들고 있지 않는다. 요청의 state.transcript 를 새 thread 에 심고, 이번 구간만 돌린 뒤
  결과 transcript 를 newState 로 돌려준다 → 같은 요청을 재시도해도 발화가 두 번 쌓이지 않는다.
- 구간의 발화가 여러 개여도 agent 는 한 번만 돌린다(앞 발화는 transcript 에 심고 마지막 발화로 실행).
  run_turn 한 번 = JEV 한 번(+ Yes 면 Gemini 한 번) 이라 발화 수만큼 외부 API 를 부르지 않는다.

결과 변환 (판단은 agent, 여기서는 형식만 바꾼다)
  jev_decision.probability → score = round(p × 100)
  jev_decision.verdict     → action: Yes = WARN, No = OBSERVE
  analysis.response        → message (사용자 안내),  analysis.reason → reason (탐지 근거)

대기 시간: JEV 30초(agent 고정) + Gemini GEMINI_TIMEOUT_SECONDS(기본 20초) 안에 끝난다.
  파이프라인 쪽 agent.timeout-ms 와 executor.lease-seconds 는 이 합보다 길어야 한다(application.yml).

실행: uv run --project <scam-agent> python server.py [--offline] [--port 9000]
  --offline : JEV·Gemini 대신 agent 의 OfflineDetector/OfflineAnalyst (키 없이 연결 확인용)
  그 외     : TYPESAFE_API_KEY, GEMINI_API_KEY 필요 (없으면 시작할 때 멈춘다)
"""
import argparse
import json
import os
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 기본값은 env 가 없을 때만 계산한다 — 컨테이너(/bridge/server.py)에는 parents[2] 가 없다
AGENT_DIR = Path(os.environ.get("AGENT_DIR") or Path(__file__).resolve().parents[2] / "team" / "scam-agent")
sys.path.insert(0, str(AGENT_DIR))

from scamshield.graph import CallPipeline  # noqa: E402

# 그래프에서 END 로만 이어지는 노드. 여기로 상태를 심으면 다음 run_turn 이 START 부터 돈다
SEED_AS_NODE = "respond_with_llm"


def speaker(track_id: str | None) -> str:
    """trackId → agent 가 읽는 화자 표기. 분석을 켠 브라우저의 마이크가 me(보호 대상), 나머지는 상대."""
    return "나" if track_id == "me" else "상대"


def turn_text(t: dict) -> str:
    if t.get("kind") == "INPUT":
        return f"[사용자 입력 {t.get('inputType')}] {t['text']}"
    return f"[{speaker(t.get('trackId'))}] {t['text']}"


def to_response(out: dict, turns: list[dict]) -> dict:
    """agent 결과 → 계약 ② 응답. newState 는 호출한 쪽이 transcript 로 채운다."""
    decision = out["jev_decision"]
    analysis = out.get("analysis") or {}
    yes = decision["verdict"] == "Yes"
    return {
        "score": round(decision["probability"] * 100),
        "action": "WARN" if yes else "OBSERVE",
        "message": analysis.get("response"),
        "reason": analysis.get("reason"),
        "evidenceSeqs": [t["seq"] for t in turns] if yes else [],
    }


class Bridge:
    def __init__(self, pipeline: CallPipeline):
        self.pipeline = pipeline

    def analyze(self, req: dict) -> tuple[int, dict]:
        turns = req.get("turns") or []
        if not turns:
            return 400, {"error": "turns 가 비어 있다"}
        prior = (req.get("state") or {}).get("transcript") or []
        lines = [turn_text(t) for t in turns]
        # 요청마다 새 thread: 재시도·동시 실행이 서로 섞이지 않는다
        tid = f"{req['callId']}:{req.get('jobId')}:{req.get('attempt')}:{uuid.uuid4().hex[:8]}"
        graph = self.pipeline.graph
        try:
            seed = list(prior) + lines[:-1]
            if seed:
                graph.update_state({"configurable": {"thread_id": tid}}, {"transcript": seed}, as_node=SEED_AS_NODE)
            out = self.pipeline.run_turn(tid, lines[-1])
            if "error" in out:
                return 502, {"error": out["error"]}
            transcript = self.pipeline.state(tid).get("transcript", [])
        finally:
            graph.checkpointer.delete_thread(tid)
        return 200, {**to_response(out, turns), "newState": {"transcript": transcript}}


def make_handler(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/analyze":
                return self._send(404, {"error": "not found"})
            t0, req = time.monotonic(), None
            try:
                req = json.loads(self._body())
                code, body = bridge.analyze(req)
            except Exception as exc:  # 원인만 남기고 통화 내용은 로그에 쓰지 않는다
                code, body = 500, {"error": type(exc).__name__}
            err = body.get("error")
            self.log_message("analyze call=%s job=%s -> %d %s %.0fms", (req or {}).get("callId"),
                             (req or {}).get("jobId"), code,
                             body.get("action") or (err.get("code") if isinstance(err, dict) else err or ""),
                             (time.monotonic() - t0) * 1000)
            self._send(code, body)

        def _body(self) -> bytes:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                out = b""
                while (size := int(self.rfile.readline().split(b";")[0], 16)) > 0:
                    out += self.rfile.read(size)
                    self.rfile.readline()
                self.rfile.readline()
                return out
            return self.rfile.read(int(self.headers.get("Content-Length", 0)))

        def _send(self, code: int, body: dict):
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_request(self, code="-", size="-"):
            pass  # 기본 접근 로그 대신 위의 한 줄만

    return Handler


def gemini_model():
    """agent 의 GeminiAnalyst 기본 설정과 같되 대기 시간만 짧게(기본 120초 → GEMINI_TIMEOUT_SECONDS).
    실시간 경고라 오래 기다리는 것보다 실패 → 파이프라인 재시도가 낫다."""
    from langchain_google_genai import ChatGoogleGenerativeAI
    from scamshield.gemini import DEFAULT_MODEL

    key = os.environ.get("GEMINI_API_KEY", "").strip()
    model = os.environ.get("GEMINI_MODEL", DEFAULT_MODEL).strip()
    if not key or not model:
        sys.exit("GEMINI_API_KEY, GEMINI_MODEL 이 비어 있다 (키 없이 시험하려면 --offline)")
    return ChatGoogleGenerativeAI(model=model, api_key=key, vertexai=False, max_retries=0,
                                  timeout=float(os.environ.get("GEMINI_TIMEOUT_SECONDS", "20")),
                                  thinking_level="low")


def build_pipeline(offline: bool) -> CallPipeline:
    if offline:
        from scamshield.interfaces import OfflineAnalyst, OfflineDetector
        return CallPipeline(OfflineDetector(), OfflineAnalyst())
    from scamshield.gemini import GeminiAnalyst
    from scamshield.jev import JevClient
    from scamshield.state import PipelineError
    try:
        detector = JevClient.from_env()
    except PipelineError as exc:
        sys.exit(f"JEV 설정 오류: {exc.code} {exc.message}")
    return CallPipeline(detector, GeminiAnalyst(gemini_model()))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--offline", action="store_true", help="JEV·Gemini 대신 agent 의 오프라인 대역 (키 없이)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9000)
    a = p.parse_args()
    bridge = Bridge(build_pipeline(a.offline))
    server = ThreadingHTTPServer((a.host, a.port), make_handler(bridge))
    mode = "offline" if a.offline else "jev+gemini"
    print(f"agent-bridge {mode} http://{a.host}:{a.port}/analyze (agent={AGENT_DIR})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

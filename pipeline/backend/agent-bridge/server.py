"""scam-agent(LangGraph 2AS: JEV 판단 → Yes 면 Gemini 근거·대응)를 파이프라인 계약 ②(POST /analyze)로 감싸는 얇은 다리.

- agent 코드는 고치지 않는다. 팀 레포를 import 해서 CallPipeline.run_turn 을 부른다.
- 상태를 여기서 들고 있지 않는다. 요청의 state.transcript 를 새 thread 에 심고, 이번 구간만 돌린 뒤
  결과 transcript 를 newState 로 돌려준다 → 같은 요청을 재시도해도 발화가 두 번 쌓이지 않는다.
- 상대마다 따로 판단한다(나-A, 나-B …). 상대 발화는 transcript 에 "[상대:<trackId>]" 로 남기고,
  agent 에는 쌍마다 [나]·사용자 입력 + 그 상대의 말만 "[상대]" 로 넘긴다(agent 코드는 그대로).
  이번 구간에 말한 상대의 쌍만 다시 판단하고, [나]·사용자 입력이 새로 있으면 모든 쌍에 영향이 있어 전부 판단한다.
  상대가 아직 없으면 [나] 말만으로 한 번 판단한다. 표시가 없는 옛 "[상대]" 줄은 누구 말인지 몰라 모든 쌍에 넣는다.
- 쌍 하나 = run_turn 한 번(앞 발화는 transcript 에 심고 마지막 발화로 실행) = JEV 한 번(+ Yes 면 Gemini 한 번).
  응답은 가장 위험한 쌍(WARN 먼저, 다음 score)을 따르고 reason 앞에 "[<trackId>]" 를 붙인다.
  쌍 하나라도 실패하면 502 → 파이프라인이 구간 전체를 재시도한다.

결과 변환 (판단은 agent, 여기서는 형식만 바꾼다)
  jev_decision.probability → score = round(p × 100)
  jev_decision.verdict + analysis.is_phishing → action: Yes 이고 Gemini 도 피싱(true) = WARN, 그 외 OBSERVE
  analysis.response        → message (사용자 안내),  analysis.reason → reason (탐지 근거·오탐 근거)
  Gemini 가 JEV 의 Yes 를 오탐(false)으로 보면 score 를 SUSPECT(40) 미만으로 낮춰 경고를 내지 않는다.

대기 시간: JEV 30초(agent 고정) + Gemini GEMINI_TIMEOUT_SECONDS(기본 20초) 안에 끝난다.
  파이프라인 쪽 agent.timeout-ms 와 executor.lease-seconds 는 이 합보다 길어야 한다(application.yml).

실행: uv run --project <scam-agent> python server.py [--offline] [--port 9000]
  --offline : JEV·Gemini 대신 agent 의 OfflineDetector/OfflineAnalyst (키 없이 연결 확인용)
  그 외     : TYPESAFE_API_KEY, GEMINI_API_KEY 필요 (없으면 시작할 때 멈춘다)
"""
import argparse
import json
import os
import re
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


def turn_peer(t: dict) -> str | None:
    """상대 발화면 그 trackId, [나]·사용자 입력이면 None (모든 쌍에 들어간다)."""
    if t.get("kind") == "INPUT" or speaker(t.get("trackId")) == "나":
        return None
    return t.get("trackId")


def turn_text(t: dict) -> str:
    """newState 에 남기는 줄. 상대 발화는 쌍을 나눌 수 있게 trackId 를 붙인다."""
    if t.get("kind") == "INPUT":
        return f"[사용자 입력 {t.get('inputType')}] {t['text']}"
    peer = turn_peer(t)
    return f"[상대:{peer}] {t['text']}" if peer else f"[{speaker(t.get('trackId'))}] {t['text']}"


PEER_LINE = re.compile(r"^\[상대:([^\]]+)\] ")


def line_peer(line: str) -> str | None:
    m = PEER_LINE.match(line)
    return m.group(1) if m else None


def pair_lines(lines: list[str], peer: str | None) -> list[str]:
    """나-peer 쌍이 agent 에 넘길 줄. 다른 상대의 말은 빼고 표시는 agent 가 읽는 "[상대]" 로 되돌린다."""
    return [PEER_LINE.sub("[상대] ", line) for line in lines if line_peer(line) in (None, peer)]


# 파이프라인은 score 만으로도 경고 단계를 정한다(ResultApplier: 40 이상 SUSPECT, 70 이상 WARN)
FALSE_POSITIVE_MAX_SCORE = 39


def to_response(out: dict, turns: list[dict]) -> dict:
    """agent 결과 → 계약 ② 응답. newState 는 호출한 쪽이 transcript 로 채운다."""
    decision = out["jev_decision"]
    analysis = out.get("analysis") or {}
    yes = decision["verdict"] == "Yes"
    warn = yes and analysis.get("is_phishing") is True
    score = round(decision["probability"] * 100)
    if yes and not warn:
        score = min(score, FALSE_POSITIVE_MAX_SCORE)
    return {
        "score": score,
        "action": "WARN" if warn else "OBSERVE",
        "message": analysis.get("response"),
        "reason": analysis.get("reason"),
        "evidenceSeqs": [t["seq"] for t in turns] if warn else [],
    }


class Bridge:
    def __init__(self, pipeline: CallPipeline):
        self.pipeline = pipeline

    def analyze(self, req: dict) -> tuple[int, dict]:
        turns = req.get("turns") or []
        if not turns:
            return 400, {"error": "turns 가 비어 있다"}
        prior = (req.get("state") or {}).get("transcript") or []
        transcript = list(prior) + [turn_text(t) for t in turns]
        known = list(dict.fromkeys(p for p in map(line_peer, transcript) if p))
        if any(turn_peer(t) is None for t in turns):
            targets = known or [None]
        else:
            targets = list(dict.fromkeys(turn_peer(t) for t in turns))
        best = None
        for peer in targets:
            out = self._run(req, pair_lines(transcript, peer))
            if "error" in out:
                return 502, {"error": out["error"]}
            res = to_response(out, [t for t in turns if turn_peer(t) in (None, peer)])
            if peer and res["reason"]:
                res["reason"] = f"[{peer}] {res['reason']}"
            if best is None or (res["action"] == "WARN", res["score"]) > (best["action"] == "WARN", best["score"]):
                best = res
        return 200, {**best, "newState": {"transcript": transcript}}

    def _run(self, req: dict, lines: list[str]) -> dict:
        # 쌍마다 새 thread: 재시도·동시 실행·다른 쌍이 서로 섞이지 않는다
        tid = f"{req['callId']}:{req.get('jobId')}:{req.get('attempt')}:{uuid.uuid4().hex[:8]}"
        graph = self.pipeline.graph
        try:
            if lines[:-1]:
                graph.update_state({"configurable": {"thread_id": tid}}, {"transcript": lines[:-1]},
                                   as_node=SEED_AS_NODE)
            return self.pipeline.run_turn(tid, lines[-1])
        finally:
            graph.checkpointer.delete_thread(tid)


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

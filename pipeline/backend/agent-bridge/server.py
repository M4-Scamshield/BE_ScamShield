"""scam-agent(상태별 판단: Jev 로 상태 6개 → Yes 인 상태마다 Claude Haiku 판단)를 파이프라인 계약 ②(POST /analyze)로 감싸는 얇은 다리.

- agent 코드는 고치지 않는다. 팀 레포를 import 해서 CallPipeline.analyze 를 부른다.
- 상태를 여기서 들고 있지 않는다. 요청의 state 에서 대화와 agent 상태를 받아 넘기고, 결과를 newState 로 돌려준다
  → 같은 요청을 재시도해도 발화나 판단이 두 번 쌓이지 않는다.
  newState = {transcript: 방 전체 대화, agent: agent state}. agent 가 없는 옛 상태는 빈 상태로 본다.
- 통화방 하나 = 분석 하나. 참가자마다 자기 마이크만 올리고(trackId = 참가자 id), 여기서는 방 전체 대화를
  "[<trackId>] 말" 로 이어 붙여 한 번에 판단한다. 누가 보호 대상인지 정하지 않는다.
- 요청 하나 = analyze 한 번(history = 이전 대화, new_lines = 이번 구간의 줄과 seq) = Jev 한 번(+ Yes 인 상태마다 Haiku).
  PipelineError 면 502 → 파이프라인이 구간 전체를 재시도한다.
  발화 시각은 아직 BE 가 보내지 않아 요청을 받은 시각으로 대신한다(점수 감소를 잰다).

결과 변환 (판단은 agent, 여기서는 형식만 바꾼다)
  status 위험 → action WARN, 주의·정상 → OBSERVE
  score: 위험이면 그대로, 아니면 39 이하로 낮춘다(ResultApplier 가 40 이상을 SUSPECT 로 알리므로 위험일 때만 알림)
  message (사용자 안내)·reason (근거)·evidence_seqs → evidenceSeqs 는 agent 출력 그대로
    (agent 는 message·reason 을 가장 앞선 위험 상태 하나에서만 고른다 → 새 수법이 잡혀도 안 바뀔 수 있다)
  findings: 위험(risky)으로 판정된 수법 하나하나 = agent 상태 handled 를 형식만 바꾼 것. 먼저 잡힌 순서.
    {code, name, points, reason, message, evidenceSeqs}. 화면은 이걸로 수법별 근거를 따로 보여 준다.
    evidenceSeqs 는 agent 가 붙인 그대로(그 상태가 Yes 로 잡힌 묶음의 줄 전부, 화자 구분 없음)

대기 시간: Jev 10초 + Haiku 최대 2×12초(tool 왕복 1번, 상태끼리는 동시) ≈ 34초.
  파이프라인 쪽 agent.timeout-ms(55초)와 executor.lease-seconds 는 이보다 길어야 한다(application.yml).

실행: uv run --project <scam-agent> python server.py [--offline] [--port 9000]
  --offline : Jev·Haiku 대신 agent 의 오프라인 대역 (키 없이 연결 확인용)
  그 외     : TYPESAFE_API_KEY, ANTHROPIC_API_KEY 필요 (없으면 시작할 때 멈춘다)
"""
import argparse
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 기본값은 env 가 없을 때만 계산한다 — 컨테이너(/bridge/server.py)에는 parents[2] 가 없다
AGENT_DIR = Path(os.environ.get("AGENT_DIR") or Path(__file__).resolve().parents[2] / "team" / "scam-agent")
sys.path.insert(0, str(AGENT_DIR))

from scamshield.graph import CallPipeline, build_pipeline  # noqa: E402
from scamshield.state import NewLine, PipelineError  # noqa: E402
from scamshield.tactics import POINTS, PRIORITY, STATES  # noqa: E402


def turn_text(t: dict) -> str:
    """agent 에 넘기고 newState 에 남기는 줄. 화자는 trackId(참가자 id) 로 표시한다."""
    if t.get("kind") == "INPUT":
        return f"[사용자 입력 {t.get('inputType')}] {t['text']}"
    return f"[{t.get('trackId')}] {t['text']}"


# 파이프라인은 score 만으로도 경고 단계를 정한다(ResultApplier: 40 이상 SUSPECT, 70 이상 WARN)
NOT_DANGER_MAX_SCORE = 39


def findings(agent_state: dict | None) -> list[dict]:
    """위험으로 판정된 수법 목록. 먼저 판정된 것부터(같은 시각이면 agent 의 우선순위)."""
    handled = (agent_state or {}).get("handled") or {}
    risky = [(code, h) for code, h in handled.items() if h.get("judgment") == "risky"]
    order = {code: i for i, code in enumerate(PRIORITY)}
    risky.sort(key=lambda kv: (kv[1].get("judged_at") or 0, order.get(kv[0], len(order))))
    return [{
        "code": code,
        "name": STATES[code][0] if code in STATES else code,
        "points": POINTS[code][0] if code in POINTS else 0,
        "reason": h.get("reason") or None,
        "message": h.get("response") or None,
        "evidenceSeqs": sorted(h.get("evidence_seqs") or []),
    } for code, h in risky]


def to_response(outcome) -> dict:
    """agent 결과 → 계약 ② 응답. newState 는 호출한 쪽이 채운다."""
    warn = outcome.status == "위험"
    return {
        "score": outcome.score if warn else min(outcome.score, NOT_DANGER_MAX_SCORE),
        "action": "WARN" if warn else "OBSERVE",
        "message": outcome.message,
        "reason": outcome.reason,
        "evidenceSeqs": list(outcome.evidence_seqs),
        "findings": findings(outcome.state),
    }


class Bridge:
    def __init__(self, pipeline: CallPipeline):
        self.pipeline = pipeline

    def analyze(self, req: dict) -> tuple[int, dict]:
        now = time.time()  # 발화 시각 대신 요청을 받은 시각
        turns = req.get("turns") or []
        if not turns:
            return 400, {"error": "turns 가 비어 있다"}
        state = req.get("state") or {}
        prior = list(state.get("transcript") or [])
        lines = [turn_text(t) for t in turns]
        new_lines = [NewLine(seq=t["seq"], text=line) for t, line in zip(turns, lines)]
        try:
            outcome = self.pipeline.analyze(prior, new_lines, state.get("agent"), now)
        except PipelineError as exc:
            return 502, {"error": {"stage": exc.stage, "code": exc.code}}
        return 200, {**to_response(outcome), "newState": {"transcript": prior + lines, "agent": outcome.state}}


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


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--offline", action="store_true", help="Jev·Haiku 대신 agent 의 오프라인 대역 (키 없이)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9000)
    a = p.parse_args()
    try:
        pipeline = build_pipeline(offline=a.offline)
    except PipelineError as exc:  # 키 값은 찍지 않는다
        sys.exit(f"agent 설정 오류: {exc.message} (키 없이 시험하려면 --offline)")
    bridge = Bridge(pipeline)
    server = ThreadingHTTPServer((a.host, a.port), make_handler(bridge))
    mode = "offline" if a.offline else "jev+haiku"
    print(f"agent-bridge {mode} http://{a.host}:{a.port}/analyze (agent={AGENT_DIR})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

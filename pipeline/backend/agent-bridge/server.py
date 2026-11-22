"""scam-agent(상태별 판단: Jev 로 상태 6개 → Yes 인 상태마다 Claude Haiku 판단)를 파이프라인 계약 ②(POST /analyze)로 감싸는 얇은 다리.

- agent 코드는 고치지 않는다. 팀 레포를 import 해서 CallPipeline.analyze 를 부른다.
- 상태를 여기서 들고 있지 않는다. 요청의 state 에서 대화와 agent 상태를 받아 넘기고, 결과를 newState 로 돌려준다
  → 같은 요청을 재시도해도 발화나 판단이 두 번 쌓이지 않는다.
  newState = {transcript: 전체 대화, peers: {상대 trackId: agent state}}. 상대가 아직 없으면 키 "" 를 쓰고,
  첫 상대가 나타나면 그 상대로 옮긴다. peers 가 없는 옛 상태는 빈 상태로 본다.
- 상대마다 따로 판단한다(나-A, 나-B …). 상대 발화는 transcript 에 "[상대:<trackId>]" 로 남기고,
  agent 에는 쌍마다 [나]·사용자 입력 + 그 상대의 말만 "[상대]" 로 넘긴다.
  이번 구간에 말한 상대의 쌍만 다시 판단하고, [나]·사용자 입력이 새로 있으면 모든 쌍에 영향이 있어 전부 판단한다.
  상대가 아직 없으면 [나] 말만으로 한 번 판단한다. 표시가 없는 옛 "[상대]" 줄은 누구 말인지 몰라 모든 쌍에 넣는다.
  판단하지 않은 쌍의 상태는 그대로 둔다.
- 쌍 하나 = analyze 한 번(history = 이전 대화, new_lines = 이번 구간에서 그 쌍의 줄과 seq) = Jev 한 번(+ Yes 인 상태마다 Haiku).
  응답은 가장 위험한 쌍(WARN 먼저, 다음 score)을 따르고 reason 앞에 "[<trackId>]" 를 붙인다.
  쌍 하나라도 PipelineError 면 502 → 파이프라인이 구간 전체를 재시도한다.
  발화 시각은 아직 BE 가 보내지 않아 요청을 받은 시각으로 대신한다(점수 감소를 잰다).

결과 변환 (판단은 agent, 여기서는 형식만 바꾼다)
  status 위험 → action WARN, 주의·정상 → OBSERVE
  score: 위험이면 그대로, 아니면 39 이하로 낮춘다(ResultApplier 가 40 이상을 SUSPECT 로 알리므로 위험일 때만 알림)
  message (사용자 안내)·reason (근거)·evidence_seqs → evidenceSeqs 는 agent 출력 그대로

대기 시간: 쌍 하나에 Jev 10초 + Haiku 최대 2×12초(tool 왕복 1번) ≈ 34초.
  파이프라인 쪽 agent.timeout-ms(55초)와 executor.lease-seconds 는 이보다 길어야 한다(application.yml).
  쌍은 차례로 판단하므로 상대 둘 이상이 한 구간에 같이 말하면 넘을 수 있다.

실행: uv run --project <scam-agent> python server.py [--offline] [--port 9000]
  --offline : Jev·Haiku 대신 agent 의 오프라인 대역 (키 없이 연결 확인용)
  그 외     : TYPESAFE_API_KEY, ANTHROPIC_API_KEY 필요 (없으면 시작할 때 멈춘다)
"""
import argparse
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 기본값은 env 가 없을 때만 계산한다 — 컨테이너(/bridge/server.py)에는 parents[2] 가 없다
AGENT_DIR = Path(os.environ.get("AGENT_DIR") or Path(__file__).resolve().parents[2] / "team" / "scam-agent")
sys.path.insert(0, str(AGENT_DIR))

from scamshield.graph import CallPipeline, build_pipeline  # noqa: E402
from scamshield.state import NewLine, PipelineError  # noqa: E402


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
NOT_DANGER_MAX_SCORE = 39


def to_response(outcome) -> dict:
    """agent 결과 → 계약 ② 응답. newState 는 호출한 쪽이 채운다."""
    warn = outcome.status == "위험"
    return {
        "score": outcome.score if warn else min(outcome.score, NOT_DANGER_MAX_SCORE),
        "action": "WARN" if warn else "OBSERVE",
        "message": outcome.message,
        "reason": outcome.reason,
        "evidenceSeqs": list(outcome.evidence_seqs),
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
        prior = state.get("transcript") or []
        peers = dict(state.get("peers") or {})
        lines = [turn_text(t) for t in turns]
        transcript = list(prior) + lines
        known = list(dict.fromkeys(p for p in map(line_peer, transcript) if p))
        if "" in peers and known:  # 상대 없이 쌓인 판단은 첫 상대의 것으로 옮긴다
            peers.setdefault(known[0], peers.pop(""))
        if any(turn_peer(t) is None for t in turns):
            targets = known or [None]
        else:
            targets = list(dict.fromkeys(turn_peer(t) for t in turns))
        best = None
        for peer in targets:
            key = peer or ""
            new_lines = [NewLine(seq=t["seq"], text=PEER_LINE.sub("[상대] ", line))
                         for t, line in zip(turns, lines) if turn_peer(t) in (None, peer)]
            try:
                outcome = self.pipeline.analyze(pair_lines(prior, peer), new_lines, peers.get(key), now)
            except PipelineError as exc:
                return 502, {"error": exc.code}
            peers[key] = outcome.state
            res = to_response(outcome)
            if peer and res["reason"]:
                res["reason"] = f"[{peer}] {res['reason']}"
            if best is None or (res["action"] == "WARN", res["score"]) > (best["action"] == "WARN", best["score"]):
                best = res
        return 200, {**best, "newState": {"transcript": transcript, "peers": peers}}


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
            self.log_message("analyze call=%s job=%s -> %d %s %.0fms", (req or {}).get("callId"),
                             (req or {}).get("jobId"), code, body.get("action") or body.get("error", ""),
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

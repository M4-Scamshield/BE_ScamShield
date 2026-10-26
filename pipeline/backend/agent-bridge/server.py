"""scam-agent(LangGraph)를 파이프라인 계약 ②(POST /analyze)로 감싸는 얇은 다리.

- agent 코드는 고치지 않는다. 팀 레포를 import 해서 CallDemo.run_turn 을 부른다.
- 상태를 여기서 들고 있지 않는다. 요청의 state 를 새 thread 에 심고, 이번 turns 만 돌린 뒤
  결과 state 를 newState 로 돌려준다 → 같은 요청을 재시도해도 발화가 두 번 쌓이지 않는다.

실행: uv run --project <scam-agent> python server.py [--offline] [--port 9000]
"""
import argparse
import json
import os
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

AGENT_DIR = Path(os.environ.get("AGENT_DIR", Path(__file__).resolve().parents[2] / "team" / "scam-agent"))
sys.path.insert(0, str(AGENT_DIR))

from scamshield.graph import CallDemo  # noqa: E402

# agent phase → 파이프라인 action. SUSPECT 는 "기관명·사건번호를 물어보라"는 안내라 정보 요청(ACQUIRE)에 대응
ACTION = {"OBSERVE": "OBSERVE", "SUSPECT": "ACQUIRE", "VERIFY": "VERIFY", "WARN": "WARN"}
STATE_KEYS = ("transcript", "phase", "risk", "verification_results")


def speaker(track_id: str | None) -> str:
    """trackId → agent 가 읽는 화자 표기. 분석을 켠 브라우저의 마이크가 me(보호 대상), 나머지는 상대."""
    return "나" if track_id == "me" else "상대"


def turn_text(t: dict) -> str:
    if t.get("kind") == "INPUT":
        return f"[사용자 입력 {t.get('inputType')}] {t['text']}"
    return f"[{speaker(t.get('trackId'))}] {t['text']}"


class Bridge:
    def __init__(self, demo: CallDemo):
        self.demo = demo

    def analyze(self, req: dict) -> tuple[int, dict]:
        turns = req.get("turns") or []
        if not turns:
            return 400, {"error": "turns 가 비어 있다"}
        # 요청마다 새 thread: 재시도·동시 실행이 서로 섞이지 않는다
        tid = f"{req['callId']}:{req.get('jobId')}:{req.get('attempt')}:{uuid.uuid4().hex[:8]}"
        config = {"configurable": {"thread_id": tid}}
        graph = self.demo.graph
        try:
            prior = {k: v for k, v in (req.get("state") or {}).items() if k in STATE_KEYS}
            if prior:
                # warn 노드는 END 로만 이어진다 → 심은 뒤 다음 입력이 START 부터 돈다
                graph.update_state(config, prior, as_node="warn")
            out = None
            for t in turns:
                out = self.demo.run_turn(tid, turn_text(t))
                if "error" in out:
                    return 502, {"error": out["error"]}
            st = self.demo.state(tid)
        finally:
            graph.checkpointer.delete_thread(tid)

        phase = st.get("phase", "OBSERVE")
        risk = st.get("risk") or 0.0
        seqs = [t["seq"] for t in turns] if phase != "OBSERVE" else []
        return 200, {
            "score": round(risk * 100),
            "action": ACTION[phase],
            "message": st.get("response"),
            "evidenceSeqs": seqs,
            "newState": {k: st.get(k) for k in STATE_KEYS},
        }


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
                             (req or {}).get("jobId"), code, body.get("action", ""),
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
    p.add_argument("--offline", action="store_true", help="Gemini 대신 DemoRoles (키 없이)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9000)
    a = p.parse_args()
    if a.offline:
        from scamshield.roles import DemoRoles
        roles, mode = DemoRoles(), "offline"
    else:
        from scamshield.gemini import GeminiRoles
        roles, mode = GeminiRoles.from_env(), "gemini"
    server = ThreadingHTTPServer((a.host, a.port), make_handler(Bridge(CallDemo(roles))))
    print(f"agent-bridge {mode} http://{a.host}:{a.port}/analyze (agent={AGENT_DIR})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

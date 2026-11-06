"""다리 계약 테스트. agent 의 JEV·Gemini 대신 고정 대역을 끼워 응답 형식을 contract/*.json 과 맞춘다.

contract/*.json 은 Java(AgentContractTest)도 읽는다 → 한쪽만 바뀌면 둘 중 하나가 깨진다.
agent 가 또 바뀌면 여기부터 실패한다.

실행 (agent 의존성을 쓰므로 agent 프로젝트 환경에서):
  cd team/scam-agent && AGENT_DIR=$PWD uv run --locked pytest ../../backend/agent-bridge/tests -q
"""
import json
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import server  # noqa: E402
from scamshield.graph import CallPipeline  # noqa: E402

CONTRACT = HERE.parent / "contract"


class FixedDetector:
    def __init__(self, verdict, probability):
        self.verdict, self.probability = verdict, probability
        self.calls = []

    def detect(self, transcript):
        self.calls.append(list(transcript))
        return {"verdict": self.verdict, "probability": self.probability}


class FixedAnalyst:
    def __init__(self):
        self.calls = 0

    def analyze(self, state):
        self.calls += 1
        return {"reason": "기관 사칭 뒤 안전계좌 송금 요구", "response": "송금을 멈추고 기관 대표번호로 확인하세요."}


class FailingDetector:
    def detect(self, transcript):
        raise RuntimeError("down")


def request(turns, transcript=None):
    return {"callId": "c1", "jobId": 7, "attempt": 1, "coveredFrom": turns[0]["seq"], "coveredTo": turns[-1]["seq"],
            "context": [], "turns": turns, "state": {"transcript": transcript} if transcript is not None else None,
            "stateVersion": 0}


def speech(seq, track, text):
    return {"seq": seq, "kind": "SPEECH", "trackId": track, "text": text, "inputType": None}


def load(name):
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def test_yes_matches_contract_and_calls_agent_once():
    det, ana = FixedDetector("Yes", 0.87), FixedAnalyst()
    code, body = server.Bridge(CallPipeline(det, ana)).analyze(request(
        [speech(3, "peer-1", "서울중앙지검입니다"), speech(4, "peer-1", "안전계좌로 옮기세요")],
        transcript=["[나] 여보세요"]))
    assert code == 200
    assert body == load("response_yes.json")
    assert len(det.calls) == 1 and ana.calls == 1  # 발화 2개여도 JEV·Gemini 는 한 번
    assert det.calls[0] == ["[나] 여보세요", "[상대] 서울중앙지검입니다", "[상대] 안전계좌로 옮기세요"]


def test_no_matches_contract_and_skips_gemini():
    det, ana = FixedDetector("No", 0.12), FixedAnalyst()
    code, body = server.Bridge(CallPipeline(det, ana)).analyze(request([speech(1, "me", "저녁 뭐 먹을까")]))
    assert code == 200
    assert body == load("response_no.json")
    assert ana.calls == 0


def test_retry_with_same_state_does_not_duplicate_transcript():
    bridge = server.Bridge(CallPipeline(FixedDetector("No", 0.1), FixedAnalyst()))
    req = request([speech(2, "peer-1", "네")], transcript=["[나] 여보세요"])
    first, second = bridge.analyze(req)[1], bridge.analyze(req)[1]
    assert first["newState"] == second["newState"] == {"transcript": ["[나] 여보세요", "[상대] 네"]}


def test_agent_error_is_502():
    code, body = server.Bridge(CallPipeline(FailingDetector(), FixedAnalyst())).analyze(request([speech(1, "me", "여보세요")]))
    assert code == 502
    assert body["error"]["stage"] == "jev"


def test_empty_turns_is_400():
    assert server.Bridge(CallPipeline(FixedDetector("No", 0.1), FixedAnalyst())).analyze(
        {"callId": "c1", "turns": []})[0] == 400


@pytest.mark.parametrize("p, score", [(0.0, 0), (0.694, 69), (0.695, 70), (1.0, 100)])
def test_score_is_probability_times_100(p, score):
    body = server.to_response({"jev_decision": {"verdict": "No", "probability": p}, "analysis": None}, [])
    assert body["score"] == score

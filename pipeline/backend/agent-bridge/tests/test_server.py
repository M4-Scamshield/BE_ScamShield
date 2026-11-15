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
        return {"is_phishing": True, "reason": "기관 사칭 뒤 안전계좌 송금 요구",
                "response": "송금을 멈추고 기관 대표번호로 확인하세요."}


class FalsePositiveAnalyst:
    def analyze(self, state):
        return {"is_phishing": False, "reason": "카드사 본인 확인 절차이며 송금·인증번호 요구가 없다", "response": None}


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


def test_false_positive_matches_contract_and_raises_no_alert():
    """JEV Yes 를 Gemini 가 오탐으로 보면 WARN 도, score 로 인한 SUSPECT(40) 도 나가지 않는다."""
    code, body = server.Bridge(CallPipeline(FixedDetector("Yes", 0.84), FalsePositiveAnalyst())).analyze(
        request([speech(5, "peer-1", "카드사 이상거래 확인 전화입니다")]))
    assert code == 200
    assert body == load("response_false_positive.json")


def test_retry_with_same_state_does_not_duplicate_transcript():
    bridge = server.Bridge(CallPipeline(FixedDetector("No", 0.1), FixedAnalyst()))
    req = request([speech(2, "peer-1", "네")], transcript=["[나] 여보세요"])
    first, second = bridge.analyze(req)[1], bridge.analyze(req)[1]
    assert first["newState"] == second["newState"] == {"transcript": ["[나] 여보세요", "[상대:peer-1] 네"]}


class KeywordDetector:
    """'검찰' 이 들어간 대화만 Yes. 쌍마다 받은 transcript 를 남긴다."""

    def __init__(self):
        self.calls = []

    def detect(self, transcript):
        self.calls.append(list(transcript))
        yes = any("검찰" in line for line in transcript)
        return {"verdict": "Yes" if yes else "No", "probability": 0.9 if yes else 0.1}


GROUP = ["[나] 여보세요", "[상대:peer-0] 검찰입니다", "[상대:peer-1] 밥 먹었어?", "[상대:peer-2] 늦어서 미안"]


def test_only_peers_who_spoke_are_judged_with_their_own_lines():
    det = KeywordDetector()
    code, body = server.Bridge(CallPipeline(det, FixedAnalyst())).analyze(
        request([speech(5, "peer-1", "응 먹었어")], transcript=GROUP))
    assert code == 200
    assert det.calls == [["[나] 여보세요", "[상대] 밥 먹었어?", "[상대] 응 먹었어"]]  # 나-peer-1 쌍만, 다른 상대 말 없음
    assert body["action"] == "OBSERVE"  # peer-0 의 '검찰' 이 섞이지 않는다
    assert body["newState"]["transcript"] == GROUP + ["[상대:peer-1] 응 먹었어"]


def test_my_turn_judges_every_pair_and_reports_the_riskiest():
    det = KeywordDetector()
    code, body = server.Bridge(CallPipeline(det, FixedAnalyst())).analyze(
        request([speech(5, "me", "네 말씀하세요")], transcript=GROUP))
    assert code == 200
    assert [c[1] for c in det.calls] == ["[상대] 검찰입니다", "[상대] 밥 먹었어?", "[상대] 늦어서 미안"]
    assert all(c[-1] == "[나] 네 말씀하세요" for c in det.calls)
    assert body["action"] == "WARN"
    assert body["reason"] == "[peer-0] 기관 사칭 뒤 안전계좌 송금 요구"
    assert body["evidenceSeqs"] == [5]


def test_evidence_is_only_the_warned_pairs_turns():
    code, body = server.Bridge(CallPipeline(KeywordDetector(), FixedAnalyst())).analyze(request(
        [speech(1, "peer-0", "검찰입니다"), speech(2, "peer-1", "안녕"), speech(3, "me", "네")]))
    assert code == 200
    assert body["reason"].startswith("[peer-0] ")
    assert body["evidenceSeqs"] == [1, 3]


def test_untagged_old_peer_line_goes_to_every_pair():
    det = KeywordDetector()
    server.Bridge(CallPipeline(det, FixedAnalyst())).analyze(
        request([speech(3, "peer-1", "네")], transcript=["[상대] 검찰입니다"]))
    assert det.calls == [["[상대] 검찰입니다", "[상대] 네"]]


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

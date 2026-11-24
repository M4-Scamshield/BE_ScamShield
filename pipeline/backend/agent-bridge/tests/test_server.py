"""다리 계약 테스트. agent 의 CallPipeline 대신 고정 analyze 대역을 끼워 응답 형식을 contract/*.json 과 맞춘다.

contract/*.json 은 Java(AgentContractTest)도 읽는다 → 한쪽만 바뀌면 둘 중 하나가 깨진다.
agent 의 입구(analyze·NewLine·PipelineError)가 또 바뀌면 여기부터 실패한다.

실행 (agent 의존성을 쓰므로 agent 프로젝트 환경에서):
  cd team/scam-agent && AGENT_DIR=$PWD uv run --locked pytest ../../backend/agent-bridge/tests -q
"""
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import server  # noqa: E402

CONTRACT = HERE.parent / "contract"


def load(name):
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


@dataclass
class Outcome:
    """AnalysisOutcome 중 다리가 읽는 필드만."""
    status: str = "정상"
    score: int = 0
    message: str | None = None
    reason: str | None = None
    evidence_seqs: list[int] = field(default_factory=list)
    state: dict = field(default_factory=dict)


def count(history, new_lines, state):
    """기본 대역: 판단할 때마다 state.n 을 1 올린다."""
    return Outcome(state={"n": (state or {}).get("n", 0) + 1})


class FakePipeline:
    """analyze 호출을 남기고 judge(history, new_lines, state) 의 결과를 돌려준다."""

    def __init__(self, judge=count):
        self.judge = judge
        self.calls = []

    def analyze(self, history, new_lines, state, now):
        self.calls.append({"history": list(history), "new": [(n.seq, n.text) for n in new_lines],
                           "state": state, "now": now})
        return self.judge(history, new_lines, state)


def fixed(outcome):
    return FakePipeline(lambda *_: outcome)


def request(turns, transcript=None, agent=None):
    state = None
    if transcript is not None:
        state = {"transcript": transcript} | ({"agent": agent} if agent is not None else {})
    return {"callId": "c1", "jobId": 7, "attempt": 1, "coveredFrom": turns[0]["seq"], "coveredTo": turns[-1]["seq"],
            "context": [], "turns": turns, "state": state, "stateVersion": 0}


def speech(seq, track, text):
    return {"seq": seq, "kind": "SPEECH", "trackId": track, "text": text, "inputType": None}


def typed(seq, input_type, text):
    return {"seq": seq, "kind": "INPUT", "trackId": "USER", "text": text, "inputType": input_type}


def test_danger_matches_contract_and_calls_agent_once_with_all_new_lines():
    want = load("response_yes.json")
    pipe = fixed(Outcome("위험", 50, "송금을 멈추고 기관 대표번호로 확인하세요.", "기관 사칭 뒤 안전계좌 송금 요구", [2, 3, 4],
                         state=want["newState"]["agent"]))
    code, body = server.Bridge(pipe).analyze(request(
        [speech(3, "p2", "서울중앙지검입니다"), speech(4, "p2", "안전계좌로 옮기세요")], transcript=["[p1] 여보세요"]))
    assert code == 200
    assert body == want
    assert len(pipe.calls) == 1  # 새 줄 2개여도 agent 는 한 번
    call = pipe.calls[0]
    assert call["history"] == ["[p1] 여보세요"]
    assert call["new"] == [(3, "[p2] 서울중앙지검입니다"), (4, "[p2] 안전계좌로 옮기세요")]
    assert call["state"] is None  # agent 가 없는 예전 상태 = 빈 상태
    assert isinstance(call["now"], float)


def test_no_matches_contract():
    want = load("response_no.json")
    pipe = fixed(Outcome("정상", 12, state=want["newState"]["agent"]))
    code, body = server.Bridge(pipe).analyze(request([speech(1, "p1", "저녁 뭐 먹을까")]))
    assert code == 200
    assert body == want
    assert pipe.calls[0]["history"] == [] and pipe.calls[0]["new"] == [(1, "[p1] 저녁 뭐 먹을까")]


def test_caution_matches_contract_and_raises_no_alert():
    """주의는 화면 표시만: WARN 도, score 로 인한 SUSPECT(40) 도 나가지 않는다."""
    want = load("response_false_positive.json")
    pipe = fixed(Outcome("주의", 60, None, "카드사 본인 확인 절차이며 송금·인증번호 요구가 없다",
                         state=want["newState"]["agent"]))
    code, body = server.Bridge(pipe).analyze(request([speech(5, "p2", "카드사 이상거래 확인 전화입니다")]))
    assert code == 200
    assert body == want


@pytest.mark.parametrize("status, score, action, sent", [
    ("위험", 25, "WARN", 25), ("위험", 95, "WARN", 95),
    ("주의", 80, "OBSERVE", 39), ("정상", 100, "OBSERVE", 39), ("정상", 20, "OBSERVE", 20)])
def test_only_danger_keeps_score_above_39(status, score, action, sent):
    body = server.Bridge(fixed(Outcome(status, score))).analyze(request([speech(1, "p1", "네")]))[1]
    assert (body["action"], body["score"]) == (action, sent)


def test_message_reason_evidence_pass_through():
    out = Outcome("위험", 70, "끊으세요", "F1: 송금 요구", [2, 9])
    body = server.Bridge(fixed(out)).analyze(request([speech(9, "p2", "보내세요")]))[1]
    assert (body["message"], body["reason"], body["evidenceSeqs"]) == ("끊으세요", "F1: 송금 요구", [2, 9])


def handled(judgment, at, seqs, reason="r", response="m"):
    return {"judgment": judgment, "reason": reason, "response": response, "actions": [],
            "evidence_seqs": seqs, "judged_at": at}


def test_findings_list_each_risky_tactic_in_detection_order():
    """agent 는 reason·message 를 우선순위 첫 위험 상태(F1)에서만 고른다. findings 는 위험 상태를 하나하나,
    먼저 잡힌 순서로 낸다. 주의·정상 상태는 빠진다. 같은 시각이면 agent 우선순위(F1, F3, F2 …)."""
    state = {"handled": {
        "F1": handled("risky", 30.0, [9, 7], "송금 요구", "보내지 마세요"),
        "F3": handled("risky", 10.0, [3], "메신저로 이동", "링크 누르지 마세요"),
        "F6": handled("risky", 10.0, [2], "기관 사칭", ""),
        "F2": handled("uncertain", 20.0, [5]),
        "F4": handled("within_normal", 20.0, [6]),
    }}
    body = server.Bridge(fixed(Outcome("위험", 90, "보내지 마세요", "송금 요구", [2, 3, 7, 9], state=state))).analyze(
        request([speech(9, "p2", "보내세요")]))[1]
    assert body["findings"] == [
        {"code": "F3", "name": "통화 밖 이동", "points": 40, "reason": "메신저로 이동", "message": "링크 누르지 마세요",
         "evidenceSeqs": [3]},
        {"code": "F6", "name": "권위·절차 연출", "points": 10, "reason": "기관 사칭", "message": None,
         "evidenceSeqs": [2]},
        {"code": "F1", "name": "자산 넘기기", "points": 40, "reason": "송금 요구", "message": "보내지 마세요",
         "evidenceSeqs": [7, 9]},
    ]


def test_no_risky_tactic_means_no_findings():
    state = {"handled": {"F2": handled("uncertain", 1.0, [1])}}
    body = server.Bridge(fixed(Outcome("주의", 20, state=state))).analyze(request([speech(1, "p1", "네")]))[1]
    assert body["findings"] == []


def test_retry_with_same_state_gives_same_new_state():
    bridge = server.Bridge(FakePipeline())
    req = request([speech(2, "p2", "네")], transcript=["[p1] 여보세요"], agent={"n": 4})
    first, second = bridge.analyze(req)[1], bridge.analyze(req)[1]
    assert first["newState"] == second["newState"] == {"transcript": ["[p1] 여보세요", "[p2] 네"], "agent": {"n": 5}}


def test_whole_room_is_judged_once_with_every_speaker():
    """4명 방: 누가 말하든 방 전체 대화를 agent 한 번으로 판단한다."""
    pipe = FakePipeline()
    room = ["[p1] 여보세요", "[p2] 검찰입니다", "[p3] 밥 먹었어?"]
    code, body = server.Bridge(pipe).analyze(request(
        [speech(4, "p4", "늦어서 미안"), speech(5, "p1", "괜찮아")], transcript=room, agent={"n": 1}))
    assert code == 200
    assert len(pipe.calls) == 1
    assert pipe.calls[0]["history"] == room
    assert pipe.calls[0]["new"] == [(4, "[p4] 늦어서 미안"), (5, "[p1] 괜찮아")]
    assert pipe.calls[0]["state"] == {"n": 1}
    assert body["newState"] == {"transcript": room + ["[p4] 늦어서 미안", "[p1] 괜찮아"], "agent": {"n": 2}}


def test_user_input_is_labeled():
    pipe = FakePipeline()
    server.Bridge(pipe).analyze(request([typed(1, "LINK", "http://x.invalid/a.apk")]))
    assert pipe.calls[0]["new"] == [(1, "[사용자 입력 LINK] http://x.invalid/a.apk")]


class JevDown(server.PipelineError):
    """agent 의 PipelineError 생성자 인자에 기대지 않고 stage·code 만 둔다."""

    def __init__(self):
        Exception.__init__(self, "Jev 대기 초과")
        self.stage, self.code, self.message = "jev", "CALL_FAILED", "Jev 대기 초과"


def test_agent_error_is_502_with_stage_and_code_only():
    def fail(*_):
        raise JevDown()
    code, body = server.Bridge(FakePipeline(fail)).analyze(request([speech(1, "p1", "여보세요")]))
    assert (code, body) == (502, {"error": {"stage": "jev", "code": "CALL_FAILED"}})


def test_empty_turns_is_400():
    pipe = FakePipeline()
    assert server.Bridge(pipe).analyze({"callId": "c1", "turns": []})[0] == 400
    assert pipe.calls == []


def test_real_offline_agent_state_round_trips_through_json():
    """대역 대신 agent 의 오프라인 파이프라인으로 두 요청을 잇는다. agent state 형식이 바뀌면 여기서 깨진다."""
    from scamshield.graph import build_pipeline

    bridge = server.Bridge(build_pipeline(offline=True))
    code, first = bridge.analyze(request([speech(1, "p2", "서울중앙지검 수사관입니다. 계좌가 연루됐습니다.")]))
    assert code == 200
    state = json.loads(json.dumps(first["newState"]))
    code, second = bridge.analyze(request([speech(2, "p2", "지금 안전계좌로 송금하세요.")], **state))
    assert code == 200
    assert second["action"] == "WARN" and second["score"] > 39
    assert 2 in second["evidenceSeqs"]
    assert second["newState"]["agent"]["handled"]["F1"]["judgment"] == "risky"
    assert "F1" in [f["code"] for f in second["findings"]]
    assert all(f["reason"] and f["evidenceSeqs"] for f in second["findings"])
    json.dumps(second)

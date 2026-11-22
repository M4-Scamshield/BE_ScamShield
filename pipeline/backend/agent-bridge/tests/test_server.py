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


def keyword(history, new_lines, state):
    """'검찰' 이 들어간 쌍만 위험. 근거는 이번 새 줄의 seq."""
    if any("검찰" in line for line in history + [n.text for n in new_lines]):
        return Outcome("위험", 90, "끊고 확인하세요", "기관 사칭", [n.seq for n in new_lines], state={"risky": True})
    return Outcome("정상", 10, state={"risky": False})


def request(turns, transcript=None, peers=None):
    state = None
    if transcript is not None:
        state = {"transcript": transcript} | ({"peers": peers} if peers is not None else {})
    return {"callId": "c1", "jobId": 7, "attempt": 1, "coveredFrom": turns[0]["seq"], "coveredTo": turns[-1]["seq"],
            "context": [], "turns": turns, "state": state, "stateVersion": 0}


def speech(seq, track, text):
    return {"seq": seq, "kind": "SPEECH", "trackId": track, "text": text, "inputType": None}


def typed(seq, input_type, text):
    return {"seq": seq, "kind": "INPUT", "trackId": None, "text": text, "inputType": input_type}


def test_danger_matches_contract_and_calls_agent_once_with_all_new_lines():
    want = load("response_yes.json")
    pipe = fixed(Outcome("위험", 87, "송금을 멈추고 기관 대표번호로 확인하세요.", "기관 사칭 뒤 안전계좌 송금 요구", [3, 4],
                         state=want["newState"]["peers"]["peer-1"]))
    code, body = server.Bridge(pipe).analyze(request(
        [speech(3, "peer-1", "서울중앙지검입니다"), speech(4, "peer-1", "안전계좌로 옮기세요")],
        transcript=["[나] 여보세요"]))
    assert code == 200
    assert body == want
    assert len(pipe.calls) == 1  # 새 줄 2개여도 agent 는 한 번
    call = pipe.calls[0]
    assert call["history"] == ["[나] 여보세요"]
    assert call["new"] == [(3, "[상대] 서울중앙지검입니다"), (4, "[상대] 안전계좌로 옮기세요")]
    assert call["state"] is None  # peers 가 없는 예전 상태 = 빈 상태
    assert isinstance(call["now"], float)


def test_no_peer_matches_contract_and_uses_empty_key():
    want = load("response_no.json")
    pipe = fixed(Outcome("정상", 12, state=want["newState"]["peers"][""]))
    code, body = server.Bridge(pipe).analyze(request([speech(1, "me", "저녁 뭐 먹을까")]))
    assert code == 200
    assert body == want
    assert pipe.calls[0]["history"] == [] and pipe.calls[0]["new"] == [(1, "[나] 저녁 뭐 먹을까")]


def test_caution_matches_contract_and_raises_no_alert():
    """주의는 화면 표시만: WARN 도, score 로 인한 SUSPECT(40) 도 나가지 않는다."""
    want = load("response_false_positive.json")
    pipe = fixed(Outcome("주의", 60, None, "카드사 본인 확인 절차이며 송금·인증번호 요구가 없다",
                         state=want["newState"]["peers"]["peer-1"]))
    code, body = server.Bridge(pipe).analyze(request([speech(5, "peer-1", "카드사 이상거래 확인 전화입니다")]))
    assert code == 200
    assert body == want


@pytest.mark.parametrize("status, score, action, sent", [
    ("위험", 25, "WARN", 25), ("위험", 95, "WARN", 95),
    ("주의", 80, "OBSERVE", 39), ("정상", 100, "OBSERVE", 39), ("정상", 20, "OBSERVE", 20)])
def test_only_danger_keeps_score_above_39(status, score, action, sent):
    body = server.Bridge(fixed(Outcome(status, score))).analyze(request([speech(1, "me", "네")]))[1]
    assert (body["action"], body["score"]) == (action, sent)


def test_message_reason_evidence_pass_through_and_reason_gets_peer_prefix():
    out = Outcome("위험", 70, "끊으세요", "F1: 송금 요구", [2, 9])
    body = server.Bridge(fixed(out)).analyze(request([speech(9, "peer-7", "보내세요")]))[1]
    assert (body["message"], body["reason"], body["evidenceSeqs"]) == ("끊으세요", "[peer-7] F1: 송금 요구", [2, 9])
    body = server.Bridge(fixed(out)).analyze(request([speech(9, "me", "네")]))[1]
    assert body["reason"] == "F1: 송금 요구"  # 상대가 없으면 표시 없음


def test_retry_with_same_state_gives_same_new_state():
    bridge = server.Bridge(FakePipeline())
    req = request([speech(2, "peer-1", "네")], transcript=["[나] 여보세요"], peers={"peer-1": {"n": 4}})
    first, second = bridge.analyze(req)[1], bridge.analyze(req)[1]
    assert first["newState"] == second["newState"] == {
        "transcript": ["[나] 여보세요", "[상대:peer-1] 네"], "peers": {"peer-1": {"n": 5}}}


GROUP = ["[나] 여보세요", "[상대:peer-0] 검찰입니다", "[상대:peer-1] 밥 먹었어?", "[상대:peer-2] 늦어서 미안"]
PEERS = {"peer-0": {"n": 1}, "peer-1": {"n": 1}, "peer-2": {"n": 1}}


def test_only_peers_who_spoke_are_judged_and_others_keep_state():
    pipe = FakePipeline(keyword)
    code, body = server.Bridge(pipe).analyze(request([speech(5, "peer-1", "응 먹었어")], GROUP, PEERS))
    assert code == 200
    assert pipe.calls == [{"history": ["[나] 여보세요", "[상대] 밥 먹었어?"],  # 다른 상대 말 없음
                           "new": [(5, "[상대] 응 먹었어")], "state": {"n": 1}, "now": pipe.calls[0]["now"]}]
    assert body["action"] == "OBSERVE"  # peer-0 의 '검찰' 이 섞이지 않는다
    assert body["newState"] == {"transcript": GROUP + ["[상대:peer-1] 응 먹었어"],
                                "peers": {"peer-0": {"n": 1}, "peer-1": {"risky": False}, "peer-2": {"n": 1}}}


def test_my_turn_judges_every_pair_and_reports_the_riskiest():
    pipe = FakePipeline(keyword)
    code, body = server.Bridge(pipe).analyze(request([speech(5, "me", "네 말씀하세요")], GROUP, PEERS))
    assert code == 200
    assert [c["history"][1] for c in pipe.calls] == ["[상대] 검찰입니다", "[상대] 밥 먹었어?", "[상대] 늦어서 미안"]
    assert all(c["new"] == [(5, "[나] 네 말씀하세요")] for c in pipe.calls)
    assert (body["action"], body["score"], body["reason"], body["evidenceSeqs"]) == ("WARN", 90, "[peer-0] 기관 사칭", [5])
    assert body["newState"]["peers"] == {"peer-0": {"risky": True}, "peer-1": {"risky": False},
                                         "peer-2": {"risky": False}}


def test_two_peers_and_input_in_one_request_split_new_lines_by_pair():
    pipe = FakePipeline(keyword)
    code, body = server.Bridge(pipe).analyze(request(
        [speech(1, "peer-0", "검찰입니다"), speech(2, "peer-1", "안녕"), typed(3, "URL", "http://x.kr")]))
    assert code == 200
    assert [c["new"] for c in pipe.calls] == [[(1, "[상대] 검찰입니다"), (3, "[사용자 입력 URL] http://x.kr")],
                                              [(2, "[상대] 안녕"), (3, "[사용자 입력 URL] http://x.kr")]]
    assert body["reason"].startswith("[peer-0] ")
    assert body["evidenceSeqs"] == [1, 3]


def test_empty_key_state_moves_to_first_peer():
    pipe = FakePipeline()
    code, body = server.Bridge(pipe).analyze(request([speech(2, "peer-1", "검찰입니다")],
                                                     transcript=["[나] 여보세요"], peers={"": {"n": 3}}))
    assert code == 200
    assert pipe.calls[0]["state"] == {"n": 3}
    assert body["newState"]["peers"] == {"peer-1": {"n": 4}}


def test_old_state_without_peers_starts_empty():
    pipe = FakePipeline()
    code, body = server.Bridge(pipe).analyze(request([speech(5, "me", "네")], transcript=GROUP))
    assert code == 200
    assert [c["state"] for c in pipe.calls] == [None, None, None]
    assert body["newState"]["peers"] == {"peer-0": {"n": 1}, "peer-1": {"n": 1}, "peer-2": {"n": 1}}


def test_untagged_old_peer_line_goes_to_every_pair():
    pipe = FakePipeline()
    server.Bridge(pipe).analyze(request([speech(3, "peer-1", "네")], transcript=["[상대] 검찰입니다"]))
    assert pipe.calls[0]["history"] == ["[상대] 검찰입니다"]


class JevDown(server.PipelineError):
    """agent 의 PipelineError 생성자 인자에 기대지 않고 code 만 둔다."""

    def __init__(self):
        Exception.__init__(self, "Jev 대기 초과")
        self.code, self.message = "CALL_FAILED", "Jev 대기 초과"


def test_agent_error_is_502_with_code_only():
    def fail(*_):
        raise JevDown()
    code, body = server.Bridge(FakePipeline(fail)).analyze(request([speech(1, "me", "여보세요")]))
    assert (code, body) == (502, {"error": "CALL_FAILED"})


def test_empty_turns_is_400():
    pipe = FakePipeline()
    assert server.Bridge(pipe).analyze({"callId": "c1", "turns": []})[0] == 400
    assert pipe.calls == []

package scamshield.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 화면 전사 — 접수한 발화·입력이 outbox 의 utterance 이벤트로 빠짐·중복 없이 나가는가. */
class TranscriptTest extends PipelineTestSupport {

    static final String CALL = "C5";

    List<JsonNode> transcript() {
        return db.queryForList("""
                select cast(payload as char) from outbox
                where call_id = ? and event_type = 'utterance' order by event_seq""", String.class, CALL)
                .stream().map(json::readTree).toList();
    }

    @Test
    @DisplayName("발화마다 전사 이벤트가 하나씩, 접수 순서(seq)대로 생긴다")
    void everyUtteranceIsTranscribed() {
        accept(CALL, 1, "여보세요");
        accept(CALL, 2, "서울중앙지검입니다");
        intake.accept(input(CALL, 1, "LINK", "http://x.invalid/a.apk", 0));

        List<JsonNode> t = transcript();
        assertEquals(3, t.size());
        assertEquals(List.of(1L, 2L, 3L), t.stream().map(n -> n.get("seq").asLong()).toList());
        assertEquals("서울중앙지검입니다", t.get(1).get("text").asString());
        assertEquals("caller", t.get(1).get("trackId").asString());
        assertEquals("SPEECH", t.get(1).get("kind").asString());
        assertEquals("INPUT", t.get(2).get("kind").asString());
        assertEquals("LINK", t.get(2).get("inputType").asString());
    }

    @Test
    @DisplayName("Kafka 재전달로 같은 이벤트가 두 번 와도 전사는 한 줄")
    void redeliveryIsNotTranscribedTwice() {
        accept(CALL, 1, "여보세요");
        accept(CALL, 1, "여보세요");
        assertEquals(1, transcript().size());
    }

    @Test
    @DisplayName("전사와 경고·행동이 섞여도 eventSeq 는 겹치지 않고 빈틈없이 오른다")
    void eventSeqStaysGapless() throws Exception {
        accept(CALL, 1, "보내드린 링크로 앱 설치하세요");
        accept(CALL, 2, "검찰입니다 계좌가 연루됐습니다");
        runOne();
        accept(CALL, 3, "네");
        runOne();

        List<Long> seqs = db.queryForList(
                "select event_seq from outbox where call_id = ? order by event_seq", Long.class, CALL);
        for (int i = 0; i < seqs.size(); i++) assertEquals(i + 1, seqs.get(i));
        assertEquals(seqs.size(), count("select last_event_seq from call_state where call_id = ?", CALL));

        // payload 안의 eventSeq 가 SSE id(event_seq 열)와 같아야 클라이언트 중복 거르기가 맞다
        for (Map<String, Object> r : db.queryForList(
                "select event_seq, cast(payload as char) as p from outbox where call_id = ?", CALL)) {
            assertEquals(((Number) r.get("event_seq")).longValue(),
                    json.readTree((String) r.get("p")).get("eventSeq").asLong());
        }
    }
}

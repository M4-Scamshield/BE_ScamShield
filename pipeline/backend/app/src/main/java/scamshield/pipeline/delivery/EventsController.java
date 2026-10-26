package scamshield.pipeline.delivery;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import scamshield.pipeline.common.ApiRole;

/**
 * GET /api/calls/{callId}/events  (계약 ③ SSE)
 *  - event: alert | action, id: eventSeq (통화 안에서 단조 증가), data: JSON
 *  - 재접속하면 Last-Event-ID(헤더, 또는 ?lastEventId=) 다음부터 outbox 에서 재생한다
 *  - 등록을 먼저 하고 재생하므로 사이에 생긴 이벤트가 두 번 갈 수 있다 → 클라이언트는 id 로 거른다
 */
@RestController
@ApiRole
public class EventsController {

    private final SseHub hub;
    private final JdbcTemplate db;

    public EventsController(SseHub hub, JdbcTemplate db) {
        this.hub = hub;
        this.db = db;
    }

    @GetMapping(path = "/api/calls/{callId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String callId,
                             @RequestHeader(name = "Last-Event-ID", required = false) Long lastHeader,
                             @RequestParam(name = "lastEventId", required = false) Long lastParam) {
        long after = lastHeader != null ? lastHeader : lastParam != null ? lastParam : 0;
        SseEmitter emitter = new SseEmitter(0L);
        hub.register(callId, emitter);
        List<Map<String, Object>> rows = db.queryForList("""
                select event_seq, event_type, cast(payload as char) as payload from outbox
                where call_id = ? and event_seq > ? order by event_seq""", callId, after);
        for (Map<String, Object> r : rows) {
            if (!SseHub.send(emitter, ((Number) r.get("event_seq")).longValue(),
                    (String) r.get("event_type"), (String) r.get("payload"))) break;
        }
        return emitter;
    }

    /** 현재 상태 조회 (프론트가 첫 화면에 쓸 수 있음. 실시간 점수 push 여부는 FE 합의 전). */
    @GetMapping("/api/calls/{callId}")
    public Map<String, Object> state(@PathVariable String callId) {
        return db.queryForMap("""
                select call_id, current_score, historical_peak, alert_level, analyzed_to, latest_input_seq, last_event_seq
                from call_state where call_id = ?""", callId);
    }
}

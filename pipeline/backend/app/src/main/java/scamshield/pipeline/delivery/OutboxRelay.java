package scamshield.pipeline.delivery;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import scamshield.pipeline.common.ApiRole;

/**
 * outbox → SSE. 채택 트랜잭션이 커밋된 뒤에만 보이므로 "저장 안 된 경고를 보내는" 일은 없다.
 * sent_at = 이 인스턴스가 push 를 시도했다는 뜻. 못 받은 사용자는 재접속 재생으로 받는다.
 */
@Component
@ApiRole
public class OutboxRelay {

    private final JdbcTemplate db;
    private final SseHub hub;

    public OutboxRelay(JdbcTemplate db, SseHub hub) {
        this.db = db;
        this.hub = hub;
    }

    @Scheduled(fixedDelayString = "${scamshield.sse.relay-interval-ms}")
    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = db.queryForList("""
                select outbox_id, call_id, event_seq, event_type, cast(payload as char) as payload from outbox
                where sent_at is null order by outbox_id
                limit 100 for update skip locked""");
        for (Map<String, Object> r : rows) {
            hub.send((String) r.get("call_id"), ((Number) r.get("event_seq")).longValue(),
                    (String) r.get("event_type"), (String) r.get("payload"));
            db.update("update outbox set sent_at = now(6) where outbox_id = ?", r.get("outbox_id"));
        }
    }
}

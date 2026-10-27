package scamshield.pipeline.result;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** analyzedTo = 1번부터 빈틈없이 덮인 끝. 구간을 from 순으로 훑다 첫 빈틈에서 멈춘다. */
final class Coverage {

    private Coverage() {}

    static long analyzedTo(JdbcTemplate db, String callId) {
        List<long[]> segs = db.query("select from_seq, to_seq from analyzed_segment where call_id = ? order by from_seq",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, callId);
        long reach = 0;
        for (long[] s : segs) {
            if (s[0] > reach + 1) break;
            reach = Math.max(reach, s[1]);
        }
        return reach;
    }
}

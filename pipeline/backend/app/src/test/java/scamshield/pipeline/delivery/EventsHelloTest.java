package scamshield.pipeline.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import scamshield.pipeline.access.CallAuth;

/**
 * SSE 를 열자마자 첫 바이트(":ok" 주석)가 나가는지.
 * 이게 없으면 응답 헤더가 첫 하트비트(15초)까지 안 나가서, 확장·웹이 연결이 열렸는지 그만큼 늦게 안다 (11-07 실제 앱에서 4.6s·13.4s 측정).
 */
class EventsHelloTest {

    @Test
    void 연결하자마자_주석을_보내고_밀린_이벤트는_그_뒤에_보낸다() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("event_seq", 3L, "event_type", "alert", "payload", "{\"level\":\"WARN\"}")));
        CallAuth auth = mock(CallAuth.class); // require() 는 아무것도 안 함 = 토큰 통과
        EventsController controller = new EventsController(new SseHub(3, 10), db, auth, 1);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult r = mvc.perform(get("/api/analysis/calls/c1/events"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = r.getResponse().getContentAsString();
        assertThat(body).startsWith(":ok\n\n");
        assertThat(body).contains("id:3\nevent:alert\ndata:{\"level\":\"WARN\"}");
        assertThat(body.indexOf(":ok")).isLessThan(body.indexOf("id:3"));
    }
}

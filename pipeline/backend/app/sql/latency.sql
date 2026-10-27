-- 단계별 지연 분해 (명세 6절, Q1 "경고까지 몇 초"의 내역). MySQL 8.4.
-- 발화 하나 기준: 그 발화를 처음 덮은 결과(DONE)와 그 결과가 만든 outbox 까지.
-- 사용: (echo "set @prefix='run1-%';"; cat sql/latency.sql) | docker exec -i scamshield-mysql-1 mysql -t -uscamshield -plocaldev scamshield
-- 백분위는 순위 방식(nearest-rank): n 개 중 ceil(p·n) 번째 값. PostgreSQL percentile_cont(보간)와 소수점 아래가 조금 다를 수 있다.
with u as (
    select u.call_id, u.seq, u.stt_final_at, u.published_at, u.intake_at,
           (select r.result_id from analysis_result r join analysis_job j using (job_id)
             where j.call_id = u.call_id and u.seq between r.covered_from_seq and r.covered_to_seq
             order by r.adopted_at limit 1) as result_id
    from utterance u
    where u.call_id like @prefix
), d as (
    select u.*, r.agent_started_at, r.agent_finished_at, r.adopted_at,
           (select min(o.sent_at) from outbox o join alert a on a.call_id = o.call_id and a.event_seq = o.event_seq
             where a.result_id = r.result_id) as alert_sent_at
    from u join analysis_result r on r.result_id = u.result_id
), x as (
              select '1 stt_final→published' stage, timestampdiff(microsecond, stt_final_at, published_at)/1000 ms from d
    union all select '2 published→intake',    timestampdiff(microsecond, published_at, intake_at)/1000 from d
    -- 3: 작업이 이미 실행 중이면 그 작업이 끝날 때까지 기다린다 (통화당 LEASED 하나의 비용)
    union all select '3 intake→agent_start',  timestampdiff(microsecond, intake_at, agent_started_at)/1000 from d
    union all select '4 agent',               timestampdiff(microsecond, agent_started_at, agent_finished_at)/1000 from d
    union all select '5 agent_end→adopted',   timestampdiff(microsecond, agent_finished_at, adopted_at)/1000 from d
    union all select '6 adopted→sse_sent',    timestampdiff(microsecond, adopted_at, alert_sent_at)/1000 from d where alert_sent_at is not null
    union all select '∑ stt_final→adopted',   timestampdiff(microsecond, stt_final_at, adopted_at)/1000 from d
), ranked as (
    select stage, ms,
           row_number() over (partition by stage order by ms) rn,
           count(*)     over (partition by stage)               n
    from x
)
select stage, max(n) n,
       round(max(case when rn = ceil(0.50 * n) then ms end), 1) p50_ms,
       round(max(case when rn = ceil(0.95 * n) then ms end), 1) p95_ms,
       round(max(ms), 1) max_ms
from ranked group by stage order by stage;

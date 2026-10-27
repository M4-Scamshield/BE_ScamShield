-- ScamShield 파이프라인 스키마 V1 — MySQL 8.4 (2026-10-05 PostgreSQL 판에서 옮김)
-- 원본 규칙: backend/experiments/src/main/resources/schema.sql (PostgreSQL, 실험 18개 통과) + 10-01 Agent 상태·행동·사용자 입력.
-- 규칙 근거: docs/내_파트_명세.md 4절.
-- 옮기면서 바뀐 것:
--   timestamptz → datetime(6) (UTC 로 저장, JDBC connectionTimeZone=UTC) / jsonb → json / bigserial → auto_increment
--   부분 유니크 인덱스(where state='READY') → 생성 컬럼 + unique. MySQL unique 는 NULL 을 여러 개 허용한다.
--   outbox 미전송 부분 인덱스 → (sent_at, outbox_id) 일반 인덱스

-- 통화별 상태. scheduling(접수)과 result(채택)가 쓴다. 행 잠금(for update)이 통화 단위 직렬화 지점.
create table call_state (
    call_id                    varchar(64) primary key,
    latest_input_seq           bigint not null default 0,  -- 접수한 입력(발화·사용자 입력)의 마지막 seq
    accepted_analysis_revision bigint not null default 0,  -- 현재 점수를 만든 결과가 덮은 끝 seq
    analyzed_to                bigint not null default 0,  -- 빠짐없이 끝난 "연속" 지점 (구간 목록에서 계산)
    current_score              int    not null default 0,
    historical_peak            int    not null default 0,  -- 한 번 위험은 위험
    alert_level                int    not null default 0,  -- 0 없음 / 1 의심 / 2 경고
    current_covered_to         bigint not null default 0,
    last_event_seq             bigint not null default 0,  -- SSE id. 경고·행동이 같은 순번을 쓴다
    status                     varchar(16) not null default 'ACTIVE',
    created_at                 datetime(6) not null default current_timestamp(6)
);

-- 발화. seq 는 접수(Kafka 소비) 때 서버가 매긴다 — 파티션 순서 = 접수 순서라 뒤집히지 않는다.
create table utterance (
    call_id       varchar(64) not null,
    seq           bigint not null,
    event_id      varchar(64) not null,
    track_id      varchar(64) not null,     -- 화자(업로드 연결 = 화자)
    stream_epoch  int    not null default 0,
    segment_id    varchar(64),
    revision      int    not null default 1, -- STT 수정 버전. seq 와 다른 값
    start_ms      bigint,
    end_ms        bigint,
    text          text   not null,
    confidence    float,                      -- null = 모름 (0 아님)
    captured_at   datetime(6),                -- 클라이언트 캡처 시각 (지연 측정용, 순서에 안 씀)
    stt_final_at  datetime(6) not null,       -- STT 확정 수신
    published_at  datetime(6) not null,       -- Kafka 발행
    intake_at     datetime(6) not null default current_timestamp(6),
    primary key (call_id, seq),
    unique (event_id)                         -- 재전달 멱등
);

-- 사용자 입력(ACQUIRE/VERIFY 응답: 링크·계좌). 발화와 같은 seq 열에 들어간다.
create table user_input (
    call_id        varchar(64) not null,
    seq            bigint not null,
    event_id       varchar(64) not null,
    input_type     varchar(16) not null,      -- LINK | ACCOUNT | TEXT (이름은 FE·AI 합의 전 임시)
    value          text   not null,
    reply_to_event bigint,                    -- 어떤 행동(eventSeq)에 대한 답인가
    received_at    datetime(6) not null,
    intake_at      datetime(6) not null default current_timestamp(6),
    primary key (call_id, seq),
    unique (event_id)
);

create table analysis_job (
    job_id             bigint auto_increment primary key,
    call_id            varchar(64) not null,
    covered_from_seq   bigint not null,       -- analyzedTo+1 에서 시작
    covered_to_seq     bigint not null,
    snapshot_revision  bigint not null,
    state              varchar(16) not null default 'READY',  -- READY|LEASED|DONE|FAILED|SUPERSEDED
    attempt            int    not null default 0,
    owner_token        varchar(64),
    lease_until        datetime(6),
    base_state_version bigint,                -- 임대 때 읽은 agent_state.state_version (채택 CAS 기준)
    last_error         varchar(500),
    created_at         datetime(6) not null default current_timestamp(6),
    leased_at          datetime(6),
    done_at            datetime(6),
    -- 통화당 READY 하나, LEASED 하나. 해당 상태가 아니면 NULL 이라 유일성 검사에서 빠진다.
    -- LEASED 하나: Agent 상태가 통화 단위로 직렬이어야 한다
    ready_call_id      varchar(64) as (case when state = 'READY'  then call_id end) stored,
    leased_call_id     varchar(64) as (case when state = 'LEASED' then call_id end) stored,
    unique key analysis_job_one_ready (ready_call_id),
    unique key analysis_job_one_leased (leased_call_id),
    key analysis_job_pick (state, job_id),
    key analysis_job_call (call_id, state)
);

create table analysis_result (
    result_id         bigint auto_increment primary key,
    job_id            bigint not null,
    attempt           int    not null,
    attempt_token     varchar(64) not null,
    covered_from_seq  bigint not null,
    covered_to_seq    bigint not null,
    score             int    not null,
    action            varchar(16) not null,  -- OBSERVE|ACQUIRE|VERIFY|WARN (문자열, enum 확정 전)
    message           text,
    evidence_seqs     json   not null,
    adopted_current   boolean not null,
    state_applied     boolean not null,      -- newState CAS 성공 여부
    agent_started_at  datetime(6) not null,
    agent_finished_at datetime(6) not null,
    adopted_at        datetime(6) not null default current_timestamp(6),
    unique (job_id, attempt),
    foreign key (job_id) references analysis_job(job_id)
);

create table analyzed_segment (
    call_id   varchar(64) not null,
    from_seq  bigint not null,
    to_seq    bigint not null,
    result_id bigint not null,
    primary key (call_id, from_seq, to_seq),
    foreign key (result_id) references analysis_result(result_id)
);

create table alert (
    alert_id      bigint auto_increment primary key,
    call_id       varchar(64) not null,
    event_seq     bigint not null,
    result_id     bigint not null,
    level         int    not null,
    score         int    not null,
    evidence_seqs json   not null,
    created_at    datetime(6) not null default current_timestamp(6),
    unique (call_id, event_seq),
    unique (call_id, level),
    foreign key (result_id) references analysis_result(result_id)
);

-- Agent 상태 원본 (AI1 가정). 내용은 AI 쪽 자유, 서버는 해석하지 않는다.
create table agent_state (
    call_id       varchar(64) primary key,
    state         json   not null default (json_object()),
    state_version bigint not null default 0,
    updated_at    datetime(6) not null default current_timestamp(6)
);

-- 사용자에게 보인 행동 이력 (ACQUIRE/VERIFY). 경고와 같은 eventSeq 순번.
create table agent_action (
    action_id  bigint auto_increment primary key,
    call_id    varchar(64) not null,
    event_seq  bigint not null,
    result_id  bigint not null,
    action     varchar(16) not null,
    message    text,
    created_at datetime(6) not null default current_timestamp(6),
    unique (call_id, event_seq),
    foreign key (result_id) references analysis_result(result_id)
);

-- 전달 대기열 겸 SSE 재생 원천. 채택과 같은 트랜잭션에서 쓴다.
create table outbox (
    outbox_id  bigint auto_increment primary key,
    call_id    varchar(64) not null,
    event_seq  bigint not null,
    event_type varchar(16) not null,         -- alert | action
    payload    json   not null,
    created_at datetime(6) not null default current_timestamp(6),
    sent_at    datetime(6),
    unique (call_id, event_seq),
    key outbox_unsent (sent_at, outbox_id)
);

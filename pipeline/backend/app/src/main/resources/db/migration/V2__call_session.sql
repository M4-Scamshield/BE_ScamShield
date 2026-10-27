-- 통화별 접근 토큰 (업로드 WS · SSE · 상태 조회 · 사용자 입력).
-- 토큰 원문은 발급 응답에 한 번만 싣고, DB 에는 SHA-256 만 둔다.
create table call_session (
    call_id     varchar(64) primary key,
    token_hash  binary(32)  not null,
    created_at  datetime(6) not null default current_timestamp(6),
    expires_at  datetime(6) not null
);

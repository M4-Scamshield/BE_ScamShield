-- 방 하나 = 분석 세션 하나. 참가자마다 자기 토큰을 받는다.
-- room_key 는 시그널링 서버가 방이 열릴 때마다 새로 주는 callId (방 이름은 재사용되므로 쓰지 않는다).
alter table call_session add column room_key varchar(64) null, add constraint uk_call_session_room unique (room_key);

-- 통화 하나에 토큰 여러 개. 원문은 발급 응답에만, DB 에는 SHA-256 만 둔다.
create table call_token (
    token_hash  binary(32)  primary key,
    call_id     varchar(64) not null,
    expires_at  datetime(6) not null,
    key idx_call_token_call (call_id)
);

-- 이미 발급된 토큰도 계속 통하게 옮겨 둔다
insert into call_token(token_hash, call_id, expires_at)
select token_hash, call_id, expires_at from call_session;

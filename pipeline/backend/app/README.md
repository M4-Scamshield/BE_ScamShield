# scamshield-pipeline (내 파트 뼈대)

발화 수신 → Kafka → 작업 스케줄링 → Agent 호출 → 채택(정합성) → SSE.
설계·계약은 `docs/내_파트_명세.md`, 구조 그림은 `docs/diagrams/내_파트_구조.png`.

## 실행

```bash
# 1. Kafka·MySQL (포트는 127.0.0.1 에만 열린다: MySQL 53306, Kafka 59092)
cd infra && docker compose up -d

# 2. 앱 (JDK 21)
cd backend/app
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew bootRun            # 또는 ./gradlew bootJar && java -jar build/libs/scamshield-pipeline-0.1.0.jar
```

Flyway가 시작할 때 `V1__pipeline.sql`, `V2__call_session.sql`을 적용한다. 개발 페이지: http://localhost:8081/
(개발용 텍스트 입구 `/api/dev/**`는 `DEV_ENDPOINTS=true ./gradlew bootRun` 일 때만 열린다)

## 손으로 확인

통화마다 `POST /api/analysis/calls`로 callId·토큰을 받는다. 이후 요청은 `Authorization: Bearer <토큰>`
(EventSource·WebSocket 은 `?token=`). 토큰이 틀리면 401. SSE 는 통화당 3개를 넘으면 가장 오래된 연결을 닫고, 인스턴스 전체 상한(500)을 넘으면 429.

```bash
read C T < <(curl -s -XPOST localhost:8081/api/analysis/calls | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['callId'],d['token'])")
A="Authorization: Bearer $T"
curl -N -H "$A" localhost:8081/api/analysis/calls/$C/events &                 # SSE
curl -XPOST localhost:8081/api/dev/calls/$C/utterances -H 'Content-Type: application/json' \
     -d '{"trackId":"caller","text":"검찰입니다 통장이 연루됐습니다"}'              # → alert WARN (DEV_ENDPOINTS)
curl -XPOST localhost:8081/api/dev/calls/$C/utterances -H 'Content-Type: application/json' \
     -d '{"trackId":"caller","text":"보내드린 링크로 앱 설치하세요"}'                # → action ACQUIRE
curl -XPOST -H "$A" localhost:8081/api/analysis/calls/$C/inputs -H 'Content-Type: application/json' \
     -d '{"inputType":"LINK","value":"http://x.invalid/a.apk","replyToEvent":2}'   # → action VERIFY
curl -H "$A" localhost:8081/api/analysis/calls/$C                              # 현재 상태
curl -N -H "$A" -H 'Last-Event-ID: 1' localhost:8081/api/analysis/calls/$C/events  # 2번부터 재생
```

## 테스트

```bash
./gradlew test     # Docker 필요 — Testcontainers 가 MySQL 8.4 를 띄운다 (Kafka 는 안 씀)
# colima 면:
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test
```

| 클래스 | 보는 것 |
|---|---|
| `CoverageTest` | 분석 중 들어온 발화가 빠지지 않는가, 구멍에서 analyzedTo 가 멈추는가, FAILED·SUPERSEDED 구간을 다음 작업이 덮는가 |
| `CrashRecoveryTest` | 접수 커밋 전·후, 임대 뒤, AI 호출 뒤, 채택 뒤에 죽어도 수렴하는가. 좀비 토큰 거절, 재전송과 중복 생성 구분 |
| `WindowTest` | 한 번에 보내는 구간이 언제 수렴하고 언제 발산하는가 |
| `AgentStateTest` | 상태 CAS, 같은 안내 반복 억제, 경고·행동 eventSeq 순번 |

실행기·릴레이는 끄고(`scamshield.roles=none`) 단계를 직접 부른다. lease 만료는 기다리지 않고 `lease_until` 을 과거로 돌린다.

## 부하·지연 분해

```bash
python3 sql/load.py run1 30 5          # 30통화 × 화자 2 × 5발화 (DEV_ENDPOINTS=true 필요)
(echo "set @prefix='run1-%';"; cat sql/latency.sql) | docker exec -i scamshield-mysql-1 mysql -t -uscamshield -plocaldev scamshield
```

## 설정 손잡이 (`application.yml` 의 `scamshield.*`, 실행 인자로 덮어쓰기)

| 키 | 기본 | 뜻 |
|---|---|---|
| `roles` | `api,executor` | 이 프로세스가 맡을 역할 |
| `kafka.partitions` | 6 | 토픽 파티션 = 소비 스레드 수 |
| `executor.workers` | 4 | 동시에 도는 작업 수 (가상 스레드) |
| `executor.lease-seconds` / `max-attempts` | 30 / 3 | 임대 시간 / 시도 한도 |
| `agent.mode` | `fake` | `http`면 `agent.url`로 POST |
| `agent.fake.failure-rate` | 0.0 | 가짜 AI 실패 주입 |
| `agent.context-size` | 6 | 요청에 붙는 앞 문맥 개수 |
| `stt.mode` | `fake` | `rtzr`면 리턴제로 스트리밍. `.env`의 `STT_MODE`로도 켠다 |
| `stt.rtzr.keywords` | 사기 용어 7개 | 키워드 부스팅 (쉼표 구분, 한글·공백만) |
| `stt.max-sessions` | 5 | 동시 STT 세션(화자) 상한. 넘으면 업로드 WS 를 1013 으로 닫는다 |
| `stt.max-sessions-per-ip` | 4 | IP 하나의 동시 STT 세션(넘으면 1008). `STT_MAX_SESSIONS_PER_IP` |
| `stt.max-tracks-per-call` / `idle-seconds` | 4 / 30 | 통화당 화자 수(넘으면 1008, 같은 trackId 는 교체) / 음성이 안 오면 닫는 시간 |
| `access.create-per-minute` | 10 | IP 당 분당 통화 생성 수. 넘으면 429 |
| `sse.max-per-call` / `max-total` / `timeout-minutes` | 3 / 500 / 30 | SSE 연결 상한·수명 |
| `web.allowed-origins` | localhost | 업로드 WS 허용 Origin. `ALLOWED_ORIGINS` 로 덮어쓴다 |
| `dev.enabled` | false | 토큰 없는 개발용 입구. `DEV_ENDPOINTS=true` |

예: `java -jar ... --scamshield.executor.workers=16 --scamshield.agent.fake.failure-rate=0.3`

## 실제 STT (리턴제로)

`backend/app/.env` 에 키를 넣는다 (커밋 안 됨, 채팅·코드에 붙이지 않는다).

```
RTZR_CLIENT_ID=...
RTZR_CLIENT_SECRET=...
STT_MODE=rtzr
```

음성 파일로 확인 (마이크 없이):

```bash
say -v Yuna -o /tmp/a.aiff "서울중앙지검입니다. [[slnc 1500]] 안전계좌로 이체하세요." \
  && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/a.aiff /tmp/a.wav
python3 -c "import wave;w=wave.open('/tmp/a.wav');open('/tmp/a.pcm','wb').write(w.readframes(w.getnframes()))"
node tools/stream_pcm.mjs /tmp/a.pcm           # 통화 만들고 실시간 속도로 업로드 + SSE 출력
```

마이크로 확인: `http://localhost:8081/` 시험 페이지. 무료 계정은 동시 5채널(= 화자 5명).

## 아직 없는 것

계정 단위 제한(지금 통화 생성 제한은 IP 기준, 인스턴스 메모리), 분석 실패 알림, 다중 api 인스턴스 SSE 전달.

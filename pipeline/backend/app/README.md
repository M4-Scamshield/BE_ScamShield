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

Flyway가 시작할 때 `V1__pipeline.sql`을 적용한다. 개발 페이지: http://localhost:8081/

## 손으로 확인

```bash
curl -N localhost:8081/api/calls/c1/events &                          # SSE
curl -XPOST localhost:8081/api/dev/calls/c1/utterances -H 'Content-Type: application/json' \
     -d '{"trackId":"caller","text":"검찰입니다 통장이 연루됐습니다"}'    # → alert WARN
curl -XPOST localhost:8081/api/dev/calls/c1/utterances -H 'Content-Type: application/json' \
     -d '{"trackId":"caller","text":"보내드린 링크로 앱 설치하세요"}'      # → action ACQUIRE
curl -XPOST localhost:8081/api/calls/c1/inputs -H 'Content-Type: application/json' \
     -d '{"inputType":"LINK","value":"http://x.invalid/a.apk","replyToEvent":2}'  # → action VERIFY
curl localhost:8081/api/calls/c1                                       # 현재 상태
curl -N -H 'Last-Event-ID: 1' localhost:8081/api/calls/c1/events        # 2번부터 재생
```

## 부하·지연 분해

```bash
python3 sql/load.py run1 30 5          # 30통화 × 화자 2 × 5발화
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
node tools/stream_pcm.mjs /tmp/a.pcm c1        # 실시간 속도로 /ws/audio 에 업로드
```

마이크로 확인: `http://localhost:8081/` 시험 페이지. 무료 계정은 동시 5채널(= 화자 5명).

## 아직 없는 것

단위·통합 테스트(실험 18개 이식), 업로드 WS·SSE 인증(통화별 토큰), SSE 연결 상한, 분석 실패 알림, 다중 api 인스턴스 SSE 전달.

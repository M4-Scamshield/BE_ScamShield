# AWS 운영 배포 (2026-11-15~)

https://boostad.site 를 돌리는 설정. 서버마다 폴더 하나를 그 서버의 `~/deploy` 에 복사해서 쓴다.
코드는 각 서버 `~/src` 아래 팀 레포 clone 에서 빌드한다 (compose 의 `../src/...` 경로).

| 폴더 | 서버 (EC2 t3.small, ap-northeast-2) | 띄우는 것 | 밖에 여는 포트 |
|---|---|---|---|
| `app/` | scamshield-app (EIP) | caddy(80·443) · api(파이프라인 8081) · signal(시그널링 8080) · FE 정적 파일 | 80·443 |
| `kafka/` | scamshield-kafka | Kafka 3.9 KRaft 1대 | 없음 (9092 ← app·worker SG) |
| `worker/` | scamshield-worker | 다리(agent-bridge 9000) | 없음 (9000 ← app SG) |

DB 는 RDS MySQL 8.4 (퍼블릭 액세스 끔, 3306 ← app SG). 서버끼리는 사설 IP 로 붙는다.

## 처음 올리기 / 다시 올리기

```bash
# 각 서버에서
mkdir -p ~/src ~/deploy && cd ~/src
git clone https://github.com/M4-Scamshield/BE_ScamShield.git        # app·worker
git clone https://github.com/M4-Scamshield/FE_Scamshield.git        # app (npm ci && npm run build → dist/)
git clone https://github.com/M4-Scamshield/scam-agent.git           # worker
# 이 폴더의 app/ (또는 kafka/, worker/) 내용을 ~/deploy 로 복사하고
cp .env.example .env && chmod 600 .env   # 값은 서버에서 직접 채운다
docker compose up -d --build
```

코드만 바뀌었으면 `~/src/<레포>` 에서 `git pull` 후 `docker compose up -d --build <서비스>`.

## 고정값 (서버를 새로 만들면 바꿔야 하는 곳)

- `app/compose.yml`: Kafka `172.31.0.192:9092`, 다리 `http://172.31.15.97:9000/analyze` (사설 IP)
- `kafka/compose.yml`: `KAFKA_HOST` 기본값 = kafka 서버 사설 IP
- `app/compose.yml` `ALLOWED_ORIGINS`: 도메인 + Discord 확장 ID
- `app/Caddyfile`: 도메인

## 운영에서 겪은 것

- **api 전체 멈춤 (11-15)**: 기동 ~20분 뒤 `/api/analysis/*` 무응답. JDK 21 가상 스레드에서 Kafka 소비자가
  운반 스레드를 붙잡아(pinning) vCPU 2 에서 요청 스레드가 못 돌았다 → `SPRING_THREADS_VIRTUAL_ENABLED=false`.
- **Caddy 차단 순서**: `respond` 를 `handle` 밖에 두면 차단이 안 걸린다 → `handle @blocked` 를 맨 앞에.
- **HTTP/3 끔**: SG 가 UDP 443 을 안 열어서, 켜 두면 브라우저가 QUIC 을 먼저 시도하다 늦어진다.

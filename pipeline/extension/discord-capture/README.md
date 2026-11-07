# ScamShield Discord 음성 수집 (Chrome 확장)

Discord 웹(discord.com) 음성 통화에서 **화자별 음성**을 뽑아, 분석 서버의 기존 업로드 계약 그대로 보낸다.
서버 쪽(STT → 분석 → SSE)은 웹 통화와 같은 경로를 탄다.

```
discord.com 탭                                  확장 서비스 워커                 분석 서버
┌─────────────────────────────┐   postMessage  ┌──────────────┐  POST /api/analysis/calls
│ hook.js (MAIN world)        │ ─────────────▶ │ background.js│ ───────────────────────▶
│  RTCPeerConnection 'track'  │   relay.js     │  트랙마다 WS  │  WS /ws/audio?trackId=me
│  getUserMedia 마이크         │ (port, base64) │  재연결·keepalive│     /ws/audio?trackId=peer-0 …
│  → 16kHz PCM16 100ms 조각    │                └──────────────┘  (PCM16 mono 16kHz, 3200B)
│ relay.js 경고 배너 ◀──────── │ ◀── 경고 ──── background.js ◀── SSE GET /api/analysis/calls/{id}/events
└─────────────────────────────┘                 (팝업에도 목록)        (alert · action, Last-Event-ID 재생)
```

- 내 마이크 → `trackId=me` (서버에서 `[나]`), 상대마다 → `peer-<mid>` (서버에서 `[상대]`)
- Discord 는 연결 하나에 상대마다 수신 오디오 트랙을 따로 둔다 → 섞이기 전 화자별 음성을 그대로 받는다
- 소리가 처음 난 트랙만 업로드를 연다(최대 4개, 서버 `max-tracks-per-call` 과 같음). 5초 무음이면 100ms 무음을 보내 서버 idle 종료(30초)를 막는다
- 4개가 다 찼는데 다른 화자가 말하면 그 트랙은 팝업에 **상한 초과(안 올라감)** 로 보인다.
  열린 트랙 중 **30초 넘게 조용한 것**(`config.releaseSilentMs`)이 있으면 그 연결을 닫고(팝업: 조용해서 쉼) 기다리던 화자에게 자리를 준다.
  쉬던 트랙이 다시 말하면 자리가 있을 때 새 epoch 로 다시 연다
- 수집을 켜면 SSE 로 분석 결과를 받는다. `alert`(사기 의심·위험)는 **Discord 화면 오른쪽 위 배너**와 아이콘 배지 `!` 로,
  `alert`·`action` 모두 팝업 **경고** 목록에 뜬다(최근 20개). 끊기면 Last-Event-ID 로 이어 받고, 같은 id 는 한 번만 보인다
- 기본은 꺼짐. 팝업에서 **수집 시작**을 눌러야 보낸다. 탭을 닫으면 끝난다

## 써 보기 (개발용 수신 서버)

```bash
cd extension/discord-capture
npm install
npm run receiver          # http://127.0.0.1:8081, 받은 음성을 dev/out/<callId>/<trackId>-e<epoch>.wav 로 저장
```

1. `chrome://extensions` → 개발자 모드 → **압축해제된 확장 프로그램 로드** → 이 폴더
2. discord.com 탭을 **새로고침** (hook 이 페이지보다 먼저 돌아야 한다)
3. 음성 채널 입장 → 확장 아이콘 → 서버 `http://localhost:8081` → **수집 시작**
4. 팝업 표에 `나 (me)`, `peer-0`, `peer-1` … 이 뜨고 "보낸 초"가 올라가면 정상. 경고 옆이 "받는 중"이면 SSE 도 붙은 것
5. `dev/out/` 의 WAV 를 들어 본다. 화자마다 따로 녹음돼 있어야 한다
6. 경고 화면 보기: 수신기에 가짜 경고를 넣는다 (callId 는 팝업 맨 위)
   ```bash
   curl -X POST "http://127.0.0.1:8081/dev/alert?callId=<callId>" -H 'Content-Type: application/json' \
     -d '{"type":"alert","level":"WARN","score":68,"message":"송금 요구가 있었습니다","reason":"검찰 사칭"}'
   ```

녹음 파일에는 통화 음성이 들어 있다. `dev/out/` 은 git 에 넣지 않는다. 상대에게 알리고 쓴다.

## 실제 분석 서버에 붙일 때

- 팝업의 서버 주소를 분석 서버로 바꾼다 (예: `https://<서버>`; WS 는 자동으로 `wss://`)
- 확장 ID 는 manifest `key` 로 **`opdocepfbahoocfkahckhnbhdedkibim` 고정**이다(누가 어느 폴더에서 로드해도 같다).
  서버 기본값(`application.yml`)에는 이미 `chrome-extension://opdocepfbahoocfkahckhnbhdedkibim` 이 들어 있다.
- 배포에서 `ALLOWED_ORIGINS` 를 직접 줄 때는 뒤에 붙인다. 빠지면 WS 가 1006 으로 끊긴다.
  ```
  ALLOWED_ORIGINS=https://<우리 도메인>,chrome-extension://opdocepfbahoocfkahckhnbhdedkibim
  ```
- `key` 는 공개키라 비밀이 아니다. 짝이 되는 개인키는 버렸다(압축해제 로드엔 필요 없음). 웹 스토어에 올리면 스토어가 ID 를 새로 준다

## 시험

```bash
npx playwright install chromium   # 처음 한 번
npm test                          # = test:e2e + test:cap
SERVER=http://127.0.0.1:8081 npm run test:e2e   # 실제 Spring 앱(기본 8081)에 붙여서: 업로드·SSE 열림까지
```

- `test:e2e`: 가짜 Discord 페이지(연결 하나에 원격 트랙 2개 = 440Hz·880Hz 사인파, 가짜 마이크)를 확장을 얹은 Chromium 으로 열고,
  수신 서버에 저장된 WAV 를 검사한다: 화자별 파일 3개(me·peer-0·peer-1), 각 주파수, 길이, 끈 뒤 연결 종료.
  수신기에 경고 2개를 넣고 배너·팝업에 뜨는지, 다시 보내도(재생) 한 번만 보이는지도 본다.
- `test:cap`: 상대 5명 + 나 = 6트랙이 모두 말할 때 4개만 열리고 2개는 "상한 초과"로 보이는지,
  열린 상대 하나를 조용하게 하면(시험에선 2초) 자리를 내주고 기다리던 화자가 열리는지, 동시에 4개를 넘지 않는지.

## 파일

| 파일 | 하는 일 |
|---|---|
| `src/hook.js` | 페이지 안(MAIN world). RTCPeerConnection·getUserMedia 를 감싸 트랙을 찾고, `MediaStreamTrackProcessor` 로 PCM 을 뽑아 16kHz 로 바꾼다 |
| `src/relay.js` | 페이지 ↔ 서비스 워커 중계 (확장 메시지는 JSON 이라 PCM 을 base64 로), 경고 배너(shadow DOM) |
| `src/background.js` | 통화 생성, 트랙마다 WS 업로드, 재연결(epoch 증가)·keepalive·상한·자리 반납, SSE 받기 |
| `popup.*` | 서버 주소, 시작/끄기, 트랙별 상태, 경고 목록 |
| `dev/receiver.mjs` | 서버 계약만 흉내 내는 개발용 수신기 (STT·분석 없음, 상한 4·SSE·`/dev/alert`) |
| `test/` | 가짜 Discord 페이지와 E2E 시험 |

## 아직 모르는 것 / 안 한 것

- ~~실제 discord.com 에서 3명 이상일 때도 상대마다 트랙이 따로 오는지~~ → 11-06 확인: 6명+봇 채널에서 상대마다 트랙이 따로 옴, 업로드 4개 길이=벽시계·섞임 없음
- ~~5번째 화자부터 안 올라가고 팝업엔 "대기(소리 없음)"로 보임~~ → 11-07: "상한 초과"로 표시, 30초 조용한 트랙의 자리를 넘김.
  다만 4명이 계속 말하면 5번째는 계속 못 올라간다. 내 마이크(`me`)도 똑같이 기다린다(우선권 없음)
- `peer-<mid>` ↔ Discord 사용자 이름 연결 (지금은 팝업에 ssrc 만 보인다. 음성 게이트웨이 SPEAKING 이벤트로 연결 가능)
- ~~분석 결과(SSE)를 Discord 화면에 띄우는 것~~ → 11-07: 배너·배지·팝업 목록
- 경고에 어느 화자(`peer-*`)의 말이었는지 표시 (서버 alert 에 화자 정보가 아직 없다)

/*
 * 서비스 워커. 분석 서버와 이야기하는 곳은 여기 하나다.
 *
 * 서버 계약은 웹(FE PcmUploader)과 같다 → 서버 코드는 그대로 쓴다.
 *   POST {server}/api/analysis/calls                        → { callId, token, expiresInSeconds }
 *   WS   {server}/ws/audio?callId&token&trackId&epoch        ← PCM16 mono 16kHz, 100ms(3200바이트) 조각
 *   trackId: 내 마이크 "me", 상대 "peer-<mid>". 연결 = 화자. 다시 붙을 때 epoch 를 올린다.
 *
 *   GET  {server}/api/analysis/calls/{callId}/events (Bearer) → SSE alert | action  (경고를 팝업·Discord 화면에 띄운다)
 *
 * 한 번도 소리가 안 난 트랙은 연결을 열지 않는다 (서버 STT 자리·화자 상한 4개를 아끼려고).
 * 4개가 다 찼는데 새 화자가 말하면, 오래(releaseSilentMs) 조용한 트랙의 연결을 닫고 자리를 넘긴다.
 * 서버는 30초 동안 프레임이 없으면 닫으므로, 조용하면 5초마다 100ms 무음을 보낸다.
 * 사용자가 켤 때만 동작하고, Discord 탭이 닫히면 같이 꺼진다.
 */
const DEFAULT_SERVER = "http://localhost:8081";
const ACTIVATE_PEAK = 0.02;
const KEEPALIVE_MS = 5000;
const SILENCE = new Uint8Array(3200);
const MAX_ALERTS = 20;
/** 시험에서 줄여 쓰려고 바꿀 수 있게 둔다 (self.ScamShield.config) */
const config = {
  maxTracks: 4, // 서버 scamshield.stt.max-tracks-per-call 과 같게
  releaseSilentMs: 30000, // 이만큼 조용한 트랙은, 기다리는 화자가 있으면 자리를 내준다
  waitingFreshMs: 3000, // 기다리는 화자가 이 안에 말했어야 자리를 비운다
};

/** tabId → port (Discord 탭마다 relay 하나) */
const ports = new Map();
/** tabId → hook 이 마지막으로 알려 준 상태 */
const tabStatus = new Map();
/** 켜져 있는 수집. 한 번에 탭 하나 */
let session = null;
let lastStop = null;
/** 마지막으로 끈 수집의 경고 (팝업에서 끈 뒤에도 보이게) */
let lastAlerts = [];

const fromBase64 = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

async function getServer() {
  const { server } = await chrome.storage.local.get("server");
  return server || DEFAULT_SERVER;
}

function wsBase(server) {
  return server.replace(/^http/, "ws").replace(/\/+$/, "");
}

class Upload {
  constructor(trackId) {
    this.trackId = trackId;
    this.state = "idle"; // idle → connecting → open ⇄ retrying, rejected, stopped / waiting(상한) / released(자리 반납)
    this.detail = "";
    this.epoch = 0;
    this.retries = 0;
    this.ws = null;
    this.timer = undefined;
    this.active = false;
    this.sent = 0;
    this.dropped = 0;
    this.lastSentAt = 0;
    this.lastVoiceAt = 0;
    this.closing = false; // 자리 반납 중. 서버가 닫힘을 받을 때까지 자리를 차지한 것으로 센다
    this.stopped = false;
  }

  /** 상한 때문에 못 올라가는 화자: 소리를 내면 표시만 바꾸고 버린다 */
  wait(peak) {
    if (peak >= ACTIVATE_PEAK) this.lastVoiceAt = Date.now();
    if (this.state === "idle" || this.state === "released") {
      this.state = "waiting";
      this.detail = `상한 ${config.maxTracks}개`;
    }
    this.dropped++;
  }

  /** 조용한 트랙의 연결을 닫아 자리를 비운다. 다시 말하면 epoch 를 올려 새로 붙는다 */
  release() {
    this.active = false;
    this.closing = true;
    this.state = "released";
    this.detail = "조용해서 자리 넘김";
    clearTimeout(this.timer);
    const ws = this.ws;
    this.ws = null;
    this.epoch++;
    if (!ws || ws.readyState >= WebSocket.CLOSING) {
      this.closing = false;
      return;
    }
    ws.onclose = () => { this.closing = false; };
    ws.close(1000, "released");
  }

  push(bytes, peak) {
    if (this.stopped || this.state === "rejected") return;
    if (peak >= ACTIVATE_PEAK) this.lastVoiceAt = Date.now();
    if (!this.active) {
      if (peak < ACTIVATE_PEAK) return;
      this.active = true;
      this.openedAt = Date.now();
      this.connect("connecting");
    }
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(bytes);
      this.sent++;
      this.lastSentAt = Date.now();
    } else {
      this.dropped++; // 끊긴 동안의 음성은 버린다 (웹 업로더와 같은 정책)
    }
  }

  keepAlive(now) {
    if (this.ws?.readyState === WebSocket.OPEN && now - this.lastSentAt > KEEPALIVE_MS) {
      this.ws.send(SILENCE);
      this.lastSentAt = now;
    }
  }

  connect(state = "retrying") {
    if (this.stopped || !session) return;
    const { server, call } = session;
    this.state = state;
    if (state === "connecting") this.detail = "";
    const q = new URLSearchParams({ callId: call.callId, token: call.token, trackId: this.trackId, epoch: String(this.epoch) });
    const ws = new WebSocket(`${wsBase(server)}/ws/audio?${q}`);
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    ws.onopen = () => {
      this.retries = 0;
      this.state = "open";
      this.detail = "";
      this.lastSentAt = Date.now();
    };
    ws.onclose = (e) => {
      if (this.stopped || this.ws !== ws) return;
      if (e.code === 1008 && e.reason === "too many tracks") {
        // 반납한 자리를 서버가 아직 안 비웠거나 다른 곳에서 같은 통화로 붙음 → 다시 기다린다
        this.ws = null;
        this.active = false;
        this.epoch++;
        this.state = "waiting";
        this.detail = "서버 화자 상한";
        return;
      }
      if (e.code === 1008 || e.code === 1013 || (e.code === 1000 && e.reason === "replaced")) {
        this.state = "rejected";
        this.detail = e.code === 1013 ? "서버 STT 자리 없음" : e.reason || String(e.code);
        return;
      }
      this.epoch++;
      this.state = "retrying";
      this.detail = `code ${e.code}${e.code === 1006 ? " (연결 실패 — 서버 주소·ALLOWED_ORIGINS 확인)" : ""}`;
      const delay = Math.min(5000, 500 * 2 ** this.retries++);
      this.timer = setTimeout(() => this.connect(), delay);
    };
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.ws?.close(1000);
    this.ws = null;
    this.state = "stopped";
  }

  view() {
    const { trackId, state, detail, epoch, active, sent, dropped, lastVoiceAt } = this;
    return { trackId, state, detail, epoch, active, sent, dropped, seconds: Math.round(sent / 10), lastVoiceAt };
  }
}

async function startSession(tabId) {
  if (session) stopSession("다시 시작");
  const port = ports.get(tabId);
  if (!port) throw new Error("이 탭에서 Discord 수집 스크립트를 찾지 못했습니다. 탭을 새로고침하세요.");
  const server = await getServer();
  const res = await fetch(`${server.replace(/\/+$/, "")}/api/analysis/calls`, { method: "POST" });
  if (res.status === 429) throw new Error("잠시 뒤 다시 시도하세요 (통화 생성이 너무 잦음)");
  if (!res.ok) throw new Error(`분석 서버 연결 실패 (HTTP ${res.status})`);
  const call = await res.json();
  session = {
    tabId, server, call, startedAt: Date.now(), uploads: new Map(), errors: [],
    alerts: [], lastEventId: 0, events: { state: "connecting", detail: "" }, abort: new AbortController(),
  };
  lastAlerts = [];
  port.postMessage({ cmd: "start" });
  followEvents(session);
  chrome.action.setBadgeText({ text: "ON", tabId }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ color: "#DC2626" }).catch(() => {});
  return view();
}

function stopSession(reason = "사용자가 끔") {
  if (!session) return view();
  const { tabId, uploads } = session;
  ports.get(tabId)?.postMessage({ cmd: "stop" });
  for (const u of uploads.values()) u.stop();
  session.abort.abort();
  lastAlerts = session.alerts; // 끈 뒤에도 팝업에서 다시 볼 수 있게
  chrome.action.setBadgeText({ text: "", tabId }).catch(() => {});
  session = null;
  lastStop = { reason, at: Date.now() };
  return view();
}

function onPcm(tabId, m) {
  if (!session || session.tabId !== tabId) return;
  let u = session.uploads.get(m.trackId);
  if (!u) {
    u = new Upload(m.trackId);
    session.uploads.set(m.trackId, u);
  }
  // 서버 화자 상한(통화당 4)과 같게: 소리가 난 순서대로 4개까지만 연결을 연다
  if (!u.active && u.state !== "rejected" && !u.stopped) {
    const voiced = m.peak >= ACTIVATE_PEAK;
    const busy = [...session.uploads.values()].filter((x) => x.active || x.closing);
    if (busy.length >= config.maxTracks) {
      if (voiced || u.state === "waiting") u.wait(m.peak);
      if (voiced) releaseSilentFor(busy);
      return;
    }
    if (u.state === "waiting" && !voiced) return; // 자리가 나도 다시 말할 때 연다
  }
  u.push(fromBase64(m.b64), m.peak);
}

/** 기다리는 화자가 방금 말했을 때: 가장 오래 조용한 트랙 하나의 자리를 비운다 (닫힘이 끝나면 다음 조각에서 열린다) */
function releaseSilentFor(busy) {
  if (busy.some((x) => x.closing)) return; // 이미 하나 비우는 중
  const now = Date.now();
  const silent = busy
    .filter((x) => x.active && now - Math.max(x.lastVoiceAt, x.openedAt || 0) >= config.releaseSilentMs)
    .sort((a, b) => a.lastVoiceAt - b.lastVoiceAt)[0];
  silent?.release();
}

/*
 * 경고 받기. EventSource 는 헤더를 못 붙여서 fetch 로 SSE 를 직접 읽는다 (토큰은 Bearer 헤더 → URL·로그에 안 남음).
 * 끊기면 Last-Event-ID 로 다시 붙고, 서버가 outbox 에서 놓친 것부터 다시 보낸다. 같은 id 는 한 번만 쓴다.
 */
async function followEvents(s) {
  const url = `${s.server.replace(/\/+$/, "")}/api/analysis/calls/${encodeURIComponent(s.call.callId)}/events`;
  let retries = 0;
  while (session === s) {
    try {
      const headers = { Authorization: `Bearer ${s.call.token}`, Accept: "text/event-stream" };
      if (s.lastEventId) headers["Last-Event-ID"] = String(s.lastEventId);
      const res = await fetch(url, { headers, cache: "no-store", signal: s.abort.signal });
      if (res.status === 401) {
        s.events = { state: "rejected", detail: "토큰이 만료됨 — 수집을 다시 켜세요" };
        return;
      }
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
      s.events = { state: "open", detail: "" };
      retries = 0;
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buf = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf = (buf + value).replace(/\r\n?/g, "\n");
        let cut;
        while ((cut = buf.indexOf("\n\n")) >= 0) {
          onSseBlock(s, buf.slice(0, cut));
          buf = buf.slice(cut + 2);
        }
      }
      if (session !== s) return;
      s.events = { state: "retrying", detail: "서버가 연결을 닫음" };
    } catch (e) {
      if (session !== s || s.abort.signal.aborted) return;
      s.events = { state: "retrying", detail: String(e?.message || e) };
    }
    await new Promise((r) => setTimeout(r, Math.min(10000, 1000 * 2 ** retries++)));
  }
}

function onSseBlock(s, block) {
  let id = null, type = "message";
  const data = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue; // 하트비트 주석
    const i = line.indexOf(":");
    const field = i < 0 ? line : line.slice(0, i);
    const value = i < 0 ? "" : line.slice(i + 1).replace(/^ /, "");
    if (field === "id") id = Number(value);
    else if (field === "event") type = value;
    else if (field === "data") data.push(value);
  }
  if (!data.length || !Number.isFinite(id)) return;
  if (id <= s.lastEventId) return; // 재접속 재생으로 두 번 온 것
  s.lastEventId = id;
  let p;
  try {
    p = JSON.parse(data.join("\n"));
  } catch {
    return;
  }
  if (type !== "alert" && type !== "action") return;
  const a = {
    id, type, at: Date.now(),
    level: p.level ?? null, score: p.score ?? null, action: p.action ?? null,
    message: p.message ?? "", reason: p.reason ?? "",
  };
  s.alerts.push(a);
  if (s.alerts.length > MAX_ALERTS) s.alerts.shift();
  if (type === "alert") {
    const warn = a.level === "WARN";
    chrome.action.setBadgeText({ text: "!", tabId: s.tabId }).catch(() => {});
    chrome.action.setBadgeBackgroundColor({ color: warn ? "#DC2626" : "#B45309" }).catch(() => {});
    ports.get(s.tabId)?.postMessage({ cmd: "alert", alert: a });
  }
}

function view() {
  return {
    session: session && {
      tabId: session.tabId,
      server: session.server,
      callId: session.call.callId,
      startedAt: session.startedAt,
      uploads: [...session.uploads.values()].map((u) => u.view()),
      errors: session.errors.slice(-5),
      events: session.events,
    },
    alerts: session ? session.alerts : lastAlerts,
    tabs: [...ports.keys()].map((tabId) => ({ tabId, status: tabStatus.get(tabId) || null })),
    lastStop,
  };
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "discord-tab" || !port.sender?.tab) return;
  const tabId = port.sender.tab.id;
  ports.set(tabId, port);
  port.onMessage.addListener((m) => {
    if (m.type === "pcm") onPcm(tabId, m);
    else if (m.type === "status") {
      tabStatus.set(tabId, m);
      // 서비스 워커가 다시 떠서 수집 정보를 잃었는데 페이지는 켜져 있으면 끈다
      if (m.enabled && session?.tabId !== tabId) port.postMessage({ cmd: "stop" });
    } else if (m.type === "error" && session?.tabId === tabId) {
      session.errors.push(`${m.trackId}: ${m.message}`);
    }
  });
  port.onDisconnect.addListener(() => {
    if (ports.get(tabId) === port) ports.delete(tabId);
    tabStatus.delete(tabId);
    if (session?.tabId === tabId) stopSession("Discord 탭이 닫히거나 이동함");
  });
});

chrome.runtime.onMessage.addListener((m, _sender, reply) => {
  (async () => {
    if (m.cmd === "getState") return view();
    if (m.cmd === "start") return startSession(m.tabId);
    if (m.cmd === "stop") return stopSession();
    if (m.cmd === "setServer") {
      const url = new URL(m.server); // 형식이 틀리면 여기서 던진다
      if (!/^https?:$/.test(url.protocol)) throw new Error("http(s) 주소만 됩니다");
      await chrome.storage.local.set({ server: url.origin });
      return { server: url.origin };
    }
    if (m.cmd === "getServer") return { server: await getServer() };
    throw new Error("unknown cmd");
  })().then((r) => reply({ ok: true, ...r }), (e) => reply({ ok: false, error: String(e?.message || e) }));
  return true;
});

setInterval(() => {
  if (!session) return;
  const now = Date.now();
  for (const u of session.uploads.values()) u.keepAlive(now);
}, 1000);

// 테스트(Playwright)에서 서비스 워커에 직접 부르려고 노출한다
self.ScamShield = { startSession, stopSession, view, config };

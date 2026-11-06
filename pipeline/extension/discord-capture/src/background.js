/*
 * 서비스 워커. 분석 서버와 이야기하는 곳은 여기 하나다.
 *
 * 서버 계약은 웹(FE PcmUploader)과 같다 → 서버 코드는 그대로 쓴다.
 *   POST {server}/api/analysis/calls                        → { callId, token, expiresInSeconds }
 *   WS   {server}/ws/audio?callId&token&trackId&epoch        ← PCM16 mono 16kHz, 100ms(3200바이트) 조각
 *   trackId: 내 마이크 "me", 상대 "peer-<mid>". 연결 = 화자. 다시 붙을 때 epoch 를 올린다.
 *
 * 한 번도 소리가 안 난 트랙은 연결을 열지 않는다 (서버 STT 자리·화자 상한 4개를 아끼려고).
 * 서버는 30초 동안 프레임이 없으면 닫으므로, 조용하면 5초마다 100ms 무음을 보낸다.
 * 사용자가 켤 때만 동작하고, Discord 탭이 닫히면 같이 꺼진다.
 */
const DEFAULT_SERVER = "http://localhost:8081";
const ACTIVATE_PEAK = 0.02;
const KEEPALIVE_MS = 5000;
const MAX_TRACKS = 4;
const SILENCE = new Uint8Array(3200);

/** tabId → port (Discord 탭마다 relay 하나) */
const ports = new Map();
/** tabId → hook 이 마지막으로 알려 준 상태 */
const tabStatus = new Map();
/** 켜져 있는 수집. 한 번에 탭 하나 */
let session = null;
let lastStop = null;

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
    this.state = "idle"; // idle → connecting → open ⇄ retrying, rejected, stopped
    this.detail = "";
    this.epoch = 0;
    this.retries = 0;
    this.ws = null;
    this.timer = undefined;
    this.active = false;
    this.sent = 0;
    this.dropped = 0;
    this.lastSentAt = 0;
    this.stopped = false;
  }

  push(bytes, peak) {
    if (this.stopped || this.state === "rejected") return;
    if (!this.active) {
      if (peak < ACTIVATE_PEAK) return;
      this.active = true;
      this.connect();
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

  connect() {
    if (this.stopped || !session) return;
    const { server, call } = session;
    this.state = this.epoch === 0 ? "connecting" : "retrying";
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
    const { trackId, state, detail, epoch, active, sent, dropped } = this;
    return { trackId, state, detail, epoch, active, sent, dropped, seconds: Math.round(sent / 10) };
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
  session = { tabId, server, call, startedAt: Date.now(), uploads: new Map(), errors: [] };
  port.postMessage({ cmd: "start" });
  chrome.action.setBadgeText({ text: "ON", tabId }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ color: "#DC2626" }).catch(() => {});
  return view();
}

function stopSession(reason = "사용자가 끔") {
  if (!session) return view();
  const { tabId, uploads } = session;
  ports.get(tabId)?.postMessage({ cmd: "stop" });
  for (const u of uploads.values()) u.stop();
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
  if (!u.active && [...session.uploads.values()].filter((x) => x.active).length >= MAX_TRACKS) return;
  u.push(fromBase64(m.b64), m.peak);
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
    },
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
self.ScamShield = { startSession, stopSession, view };

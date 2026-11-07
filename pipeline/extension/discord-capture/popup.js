const $ = (id) => document.getElementById(id);
const send = (m) => chrome.runtime.sendMessage(m);
let activeTabId = null;
let running = false;

const LABEL = {
  idle: "대기(소리 없음)", connecting: "연결 중", open: "전송 중", retrying: "재연결", rejected: "거절됨", stopped: "멈춤",
  waiting: "상한 초과(안 올라감)", released: "조용해서 쉼",
};
const EVENTS = { connecting: "연결 중", open: "받는 중", retrying: "재연결", rejected: "끊김" };
const LEVEL = { WARN: "사기 위험", SUSPECT: "사기 의심" };

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

let alertsKey = null;

function renderAlerts(state) {
  const s = state.session;
  $("events").textContent = s?.events ? `${EVENTS[s.events.state] || s.events.state}${s.events.detail ? ` (${s.events.detail})` : ""}` : "";
  const alerts = [...(state.alerts || [])].reverse();
  const key = alerts.map((a) => a.id).join(",");
  if (key === alertsKey) return; // 1초마다 다시 그리면 스크롤이 튄다
  alertsKey = key;
  const list = $("alerts");
  list.replaceChildren();
  if (!alerts.length) {
    list.appendChild(el("li", "muted", "아직 경고가 없습니다"));
    return;
  }
  for (const a of alerts) {
    const li = el("li", a.level || "");
    const when = new Date(a.at).toLocaleTimeString();
    const title = a.type === "alert"
      ? `${LEVEL[a.level] || a.level}${a.score != null ? ` · 위험도 ${a.score}` : ""}`
      : `안내 (${a.action})`;
    li.appendChild(el("div", "t", `${title}  ${when}`));
    if (a.message) li.appendChild(el("div", "msg", a.message));
    if (a.reason) li.appendChild(el("div", "why", `근거: ${a.reason}`));
    list.appendChild(li);
  }
}

function render(state, tab) {
  const s = state.session;
  running = !!s;
  const hooked = state.tabs.find((t) => t.tabId === tab?.id);
  const onDiscord = /^https:\/\/discord\.com\//.test(tab?.url || "");
  const toggle = $("toggle");
  toggle.textContent = running ? "수집 끄기" : "수집 시작";
  toggle.className = running ? "danger" : "primary";
  toggle.disabled = !running && !hooked;
  $("where").textContent = running
    ? (s.tabId === tab?.id ? "이 탭에서 수집 중" : `다른 탭(${s.tabId})에서 수집 중`)
    : hooked ? "Discord 탭 준비됨" : onDiscord ? "탭을 새로고침하세요" : "Discord 웹 탭에서 여세요";
  $("call").textContent = s ? `통화 ${s.callId} → ${s.server}` : state.lastStop ? `꺼짐: ${state.lastStop.reason}` : "";
  $("error").textContent = s?.errors?.join("\n") || "";
  renderAlerts(state);

  const sources = hooked?.status?.sources || [];
  const uploads = new Map((s?.uploads || []).map((u) => [u.trackId, u]));
  const ids = [...new Set([...sources.map((x) => x.trackId), ...uploads.keys()])];
  $("rows").replaceChildren();
  if (!ids.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 4;
    td.className = "muted";
    td.textContent = "아직 잡힌 음성이 없습니다";
    tr.appendChild(td);
    $("rows").appendChild(tr);
    return;
  }
  for (const id of ids) {
    const src = sources.find((x) => x.trackId === id);
    const u = uploads.get(id);
    const tr = document.createElement("tr");
    const cells = [
      id === "me" ? "나 (me)" : id,
      src ? `${src.state}${src.muted ? "·muted" : ""}${src.ssrc ? ` ssrc ${src.ssrc}` : ""}` : "-",
      u ? `${LABEL[u.state] || u.state}${u.detail ? ` (${u.detail})` : ""}` : running ? LABEL.idle : "-",
      u ? String(u.seconds) : "0",
    ];
    for (const c of cells) {
      const td = document.createElement("td");
      td.textContent = c;
      tr.appendChild(td);
    }
    $("rows").appendChild(tr);
  }
}

async function refresh() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTabId = tab?.id ?? null;
  const r = await send({ cmd: "getState" });
  if (r.ok) render(r, tab);
}

$("toggle").onclick = async () => {
  $("error").textContent = "";
  $("toggle").disabled = true;
  const r = running ? await send({ cmd: "stop" }) : await send({ cmd: "start", tabId: activeTabId });
  if (!r.ok) $("error").textContent = r.error;
  refresh();
};

$("save").onclick = async () => {
  const r = await send({ cmd: "setServer", server: $("server").value.trim() });
  $("error").textContent = r.ok ? "" : r.error;
  if (r.ok) $("server").value = r.server;
};

send({ cmd: "getServer" }).then((r) => { if (r.ok) $("server").value = r.server; });
refresh();
setInterval(refresh, 1000);

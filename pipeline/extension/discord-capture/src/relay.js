/*
 * 확장 쪽 content script(ISOLATED world). hook.js(페이지 안) ↔ background.js(서비스 워커) 사이를 잇는다.
 *  - hook → background : pcm 조각, 상태, 오류
 *  - background → hook : start / stop
 *  - background → 이 탭 화면 : alert (경고 배너. 페이지 스타일과 섞이지 않게 shadow DOM 에 그린다)
 * 페이지 CSP·사설망 제한을 피하려고 서버 연결은 여기서 하지 않고 서비스 워커가 한다.
 * 확장 메시지는 JSON 으로 오가서 ArrayBuffer 를 base64 로 바꿔 넘긴다 (100ms = 3200바이트).
 */
(() => {
  const TAG = "__scamshield";
  let port = null;

  function toBase64(buf) {
    const bytes = new Uint8Array(buf);
    let s = "";
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }

  const LEVEL = { WARN: "사기 위험", SUSPECT: "사기 의심" };
  let banner = null;

  /** 서버 경고를 Discord 화면 오른쪽 위에 띄운다. 글은 textContent 로만 넣는다 (서버·AI 문장을 HTML 로 해석하지 않음) */
  function showAlert(a) {
    if (!banner) {
      const host = document.createElement("div");
      host.id = "scamshield-alert";
      const root = host.attachShadow({ mode: "open" });
      root.innerHTML = `<style>
        .box { position: fixed; top: 16px; right: 16px; z-index: 2147483647; width: 340px; box-sizing: border-box;
               font: 14px/1.5 -apple-system, "Apple SD Gothic Neo", sans-serif; color: #111827; background: #fff;
               border-left: 6px solid var(--c); border-radius: 6px; box-shadow: 0 8px 24px rgba(0,0,0,.35); padding: 12px 14px; }
        .head { display: flex; align-items: center; gap: 8px; font-weight: 700; color: var(--c); }
        .head .x { margin-left: auto; border: 0; background: none; font-size: 18px; line-height: 1; cursor: pointer; color: #6B7280; }
        .msg { margin-top: 6px; font-weight: 600; white-space: pre-wrap; }
        .why { margin-top: 6px; color: #374151; font-size: 12.5px; white-space: pre-wrap; }
        .why:empty { display: none; }
      </style>
      <div class="box" role="alert"><div class="head"><span class="title"></span><button class="x" title="닫기">×</button></div>
      <div class="msg"></div><div class="why"></div></div>`;
      root.querySelector(".x").onclick = () => { host.remove(); banner = null; };
      (document.body || document.documentElement).appendChild(host);
      banner = { host, root };
    }
    const { root } = banner;
    const warn = a.level === "WARN";
    root.querySelector(".box").style.setProperty("--c", warn ? "#DC2626" : "#B45309");
    root.querySelector(".title").textContent =
      `ScamShield · ${LEVEL[a.level] || "경고"}${a.score != null ? ` (위험도 ${a.score})` : ""}`;
    root.querySelector(".msg").textContent = a.message || "통화 내용에서 사기 징후가 보입니다.";
    root.querySelector(".why").textContent = a.reason ? `근거: ${a.reason}` : "";
  }

  function connect() {
    try {
      port = chrome.runtime.connect({ name: "discord-tab" });
    } catch {
      return; // 확장이 다시 로드됨 — 이 페이지의 relay 는 더 못 쓴다
    }
    port.onMessage.addListener((m) => {
      if (m?.cmd === "start" || m?.cmd === "stop" || m?.cmd === "status")
        window.postMessage({ [TAG]: "relay", cmd: m.cmd }, location.origin);
      else if (m?.cmd === "alert" && m.alert) showAlert(m.alert);
    });
    port.onDisconnect.addListener(() => {
      port = null;
      setTimeout(connect, 1000); // 서비스 워커가 다시 뜨면 다시 붙는다
    });
    window.postMessage({ [TAG]: "relay", cmd: "status" }, location.origin);
  }

  window.addEventListener("message", (e) => {
    if (e.source !== window || e.data?.[TAG] !== "hook" || !port) return;
    const { type } = e.data;
    try {
      if (type === "pcm") {
        port.postMessage({ type, trackId: e.data.trackId, seq: e.data.seq, peak: e.data.peak, b64: toBase64(e.data.pcm) });
      } else if (type === "status" || type === "error" || type === "ready") {
        const { [TAG]: _, ...rest } = e.data;
        port.postMessage(rest);
      }
    } catch {
      /* 포트가 막 끊긴 경우. 다음 연결에서 다시 보낸다 */
    }
  });

  connect();
})();

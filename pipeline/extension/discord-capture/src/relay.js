/*
 * 확장 쪽 content script(ISOLATED world). hook.js(페이지 안) ↔ background.js(서비스 워커) 사이를 잇는다.
 *  - hook → background : pcm 조각, 상태, 오류
 *  - background → hook : start / stop
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

  function connect() {
    try {
      port = chrome.runtime.connect({ name: "discord-tab" });
    } catch {
      return; // 확장이 다시 로드됨 — 이 페이지의 relay 는 더 못 쓴다
    }
    port.onMessage.addListener((m) => {
      if (m?.cmd === "start" || m?.cmd === "stop" || m?.cmd === "status")
        window.postMessage({ [TAG]: "relay", cmd: m.cmd }, location.origin);
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

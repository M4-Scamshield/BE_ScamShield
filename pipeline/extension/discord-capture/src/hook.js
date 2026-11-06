/*
 * Discord 페이지 안(MAIN world)에서 돈다. document_start 에 Discord 코드보다 먼저 실행돼야 한다.
 *
 * 하는 일
 *  1. RTCPeerConnection 을 감싸 원격 오디오 트랙(= 상대 한 명)을 모은다 → trackId "peer-<mid>"
 *  2. getUserMedia 를 감싸 Discord 가 잡은 내 마이크 트랙을 모은다 → trackId "me"
 *  3. 켜져 있을 때만(start) 트랙 복제본을 MediaStreamTrackProcessor 로 읽어
 *     PCM16 mono 16kHz 100ms(1600 샘플) 조각으로 바꿔 relay.js 로 넘긴다.
 *
 * 원본 트랙은 건드리지 않는다(clone 해서 읽고, 끄면 clone 만 멈춘다). Discord 통화 자체에는 영향이 없다.
 * DAVE 종단 암호화는 Discord 가 페이지 안에서 풀기 때문에 수신 트랙에는 이미 풀린 소리가 들어온다.
 * 트랙 ↔ Discord 사용자 대응은 아직 안 한다. 대신 SSRC 를 상태에 실어 둔다.
 */
(() => {
  if (window.__scamshieldCapture) return;
  window.__scamshieldCapture = true;

  const TAG = "__scamshield";
  const OUT_RATE = 16000;
  const CHUNK = 1600; // 100ms
  const STATUS_MS = 2000;

  /** trackId → { track, kind: "me"|"peer", receiver?, mid? } */
  const sources = new Map();
  /** trackId → Tap */
  const taps = new Map();
  /** 내 마이크 후보. Discord 가 여러 번 잡을 수 있어 가장 최근의 살아 있는 것을 쓴다 */
  const mics = [];
  const pcs = new Set();
  /** 연결마다 번호. Discord 는 보통 연결 하나지만, 둘 이상이면 mid 가 겹치므로 번호를 붙인다 */
  const pcNo = new WeakMap();
  let pcCount = 0;
  let enabled = false;
  let peerSeq = 0;

  const post = (msg, transfer) => window.postMessage({ [TAG]: "hook", ...msg }, location.origin, transfer || []);
  const safeId = (s) => String(s).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 40) || String(++peerSeq);

  // ---------- 리샘플러: 임의 입력 rate → 16kHz ----------
  function makeResampler(inRate) {
    const k = inRate / OUT_RATE;
    if (Number.isInteger(k)) {
      // 48k·32k·16k: k 개씩 평균(상자 필터 겸 솎아내기)
      let acc = 0, cnt = 0;
      return { inRate, process(x, out) {
        for (let i = 0; i < x.length; i++) {
          acc += x[i];
          if (++cnt === k) { out(acc / k); acc = 0; cnt = 0; }
        }
      } };
    }
    // 44.1k 등: 3탭 평균으로 살짝 거른 뒤 선형 보간
    let t = 0, prev = 0, p1 = 0, p2 = 0;
    return { inRate, process(raw, out) {
      const x = new Float32Array(raw.length);
      for (let i = 0; i < raw.length; i++) { x[i] = (raw[i] + p1 + p2) / 3; p2 = p1; p1 = raw[i]; }
      while (Math.floor(t) + 1 < x.length) {
        const i0 = Math.floor(t), f = t - i0;
        const a = i0 < 0 ? prev : x[i0];
        out(a + (x[i0 + 1] - a) * f);
        t += k;
      }
      t -= x.length;
      prev = x[x.length - 1];
    } };
  }

  // ---------- 트랙 하나를 PCM 조각으로 ----------
  class Tap {
    constructor(trackId, track) {
      this.trackId = trackId;
      this.clone = track.clone();
      this.reader = new MediaStreamTrackProcessor({ track: this.clone }).readable.getReader();
      this.res = null;
      this.buf = new Int16Array(CHUNK);
      this.n = 0;
      this.peak = 0;
      this.seq = 0;
      this.frames = 0;
      this.inRate = 0;
      this.stopped = false;
      this.loop();
    }

    async loop() {
      try {
        for (;;) {
          const { value, done } = await this.reader.read();
          if (done || this.stopped) { value?.close(); break; }
          try { this.push(value); } finally { value.close(); }
        }
      } catch (e) {
        if (!this.stopped) post({ type: "error", trackId: this.trackId, message: String(e?.message || e) });
      }
    }

    push(ad) {
      const len = ad.numberOfFrames, ch = ad.numberOfChannels;
      this.frames++;
      this.inRate = ad.sampleRate;
      if (!this.res || this.res.inRate !== ad.sampleRate) this.res = makeResampler(ad.sampleRate);
      const mono = new Float32Array(len);
      ad.copyTo(mono, { planeIndex: 0, format: "f32-planar" });
      if (ch > 1) {
        const tmp = new Float32Array(len);
        for (let c = 1; c < ch; c++) {
          ad.copyTo(tmp, { planeIndex: c, format: "f32-planar" });
          for (let i = 0; i < len; i++) mono[i] += tmp[i];
        }
        for (let i = 0; i < len; i++) mono[i] /= ch;
      }
      this.res.process(mono, (s) => this.sample(s));
    }

    sample(s) {
      const v = s > 1 ? 1 : s < -1 ? -1 : s;
      const a = v < 0 ? -v : v;
      if (a > this.peak) this.peak = a;
      this.buf[this.n++] = v < 0 ? v * 0x8000 : v * 0x7fff;
      if (this.n === CHUNK) {
        const pcm = this.buf.buffer;
        post({ type: "pcm", trackId: this.trackId, seq: this.seq++, peak: Math.round(this.peak * 1000) / 1000, pcm }, [pcm]);
        this.buf = new Int16Array(CHUNK);
        this.n = 0;
        this.peak = 0;
      }
    }

    stop() {
      this.stopped = true;
      this.reader.cancel().catch(() => {});
      this.clone.stop();
    }
  }

  function startTap(trackId) {
    stopTap(trackId);
    const src = sources.get(trackId);
    if (!src || src.track.readyState !== "live") return;
    if (typeof MediaStreamTrackProcessor === "undefined") {
      post({ type: "error", trackId, message: "이 브라우저는 MediaStreamTrackProcessor 를 지원하지 않습니다 (Chrome 94+ 필요)" });
      return;
    }
    try {
      taps.set(trackId, new Tap(trackId, src.track));
    } catch (e) {
      post({ type: "error", trackId, message: String(e?.message || e) });
    }
  }

  function stopTap(trackId) {
    taps.get(trackId)?.stop();
    taps.delete(trackId);
  }

  function addSource(trackId, track, meta) {
    if (!track || track.kind !== "audio") return;
    const prev = sources.get(trackId);
    if (prev && prev.track === track) return;
    sources.set(trackId, { track, ...meta });
    track.addEventListener("ended", () => onEnded(trackId, track), { once: true });
    if (enabled) startTap(trackId);
    status();
  }

  function onEnded(trackId, track) {
    if (sources.get(trackId)?.track !== track) return;
    sources.delete(trackId);
    stopTap(trackId);
    if (trackId === "me") useLatestMic();
    status();
  }

  // ---------- 상대: RTCPeerConnection ----------
  /** 첫 연결은 peer-<mid>, 그다음 연결부터 peer-c<n>-<mid> */
  function peerId(pc, mid) {
    const n = pcNo.get(pc) ?? 0;
    return "peer-" + (n ? `c${n}-` : "") + safeId(mid ?? ++peerSeq);
  }

  function onRemote(pc, transceiver, track) {
    if (track?.kind !== "audio") return;
    const mid = transceiver?.mid;
    addSource(peerId(pc, mid), track, { kind: "peer", receiver: transceiver?.receiver, mid });
  }

  function watch(pc) {
    pcs.add(pc);
    pcNo.set(pc, pcCount++);
    pc.addEventListener("track", (e) => onRemote(pc, e.transceiver, e.track));
    pc.addEventListener("connectionstatechange", () => { if (pc.connectionState === "closed") pcs.delete(pc); });
  }

  /** track 이벤트를 놓친 경우(우리보다 먼저 만들어진 연결 등)를 위한 보조 확인 */
  function scan() {
    for (const pc of pcs) {
      let list = [];
      try { list = pc.getTransceivers(); } catch { continue; }
      for (const t of list) {
        const track = t.receiver?.track;
        const dir = t.currentDirection || "";
        if (track?.kind === "audio" && track.readyState === "live" && dir.includes("recv")) {
          const id = peerId(pc, t.mid ?? "x");
          if (!sources.has(id)) addSource(id, track, { kind: "peer", receiver: t.receiver, mid: t.mid });
        }
      }
    }
  }

  const NativePC = window.RTCPeerConnection;
  if (NativePC) {
    const Wrapped = new Proxy(NativePC, {
      construct(target, args, newTarget) {
        const pc = Reflect.construct(target, args, newTarget);
        try { watch(pc); } catch { /* 감싸기 실패해도 Discord 는 그대로 돌아야 한다 */ }
        return pc;
      },
    });
    window.RTCPeerConnection = Wrapped;
    if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = Wrapped;
  }

  // ---------- 나: getUserMedia ----------
  function useLatestMic() {
    for (let i = mics.length - 1; i >= 0; i--) {
      if (mics[i].readyState === "live") { addSource("me", mics[i], { kind: "me" }); return; }
      mics.splice(i, 1);
    }
  }

  const md = navigator.mediaDevices;
  if (md?.getUserMedia) {
    const original = md.getUserMedia.bind(md);
    md.getUserMedia = async function (constraints) {
      const stream = await original(constraints);
      try {
        for (const t of stream.getAudioTracks()) {
          mics.push(t);
          t.addEventListener("ended", () => { if (sources.get("me")?.track !== t) mics.splice(mics.indexOf(t) >>> 0, 1); }, { once: true });
          addSource("me", t, { kind: "me" });
        }
      } catch { /* 무시 */ }
      return stream;
    };
  }

  // ---------- 상태·명령 ----------
  function ssrcOf(src) {
    try { return src.receiver?.getSynchronizationSources?.()[0]?.source ?? null; } catch { return null; }
  }

  function status() {
    post({
      type: "status",
      enabled,
      sources: [...sources].map(([trackId, s]) => ({
        trackId,
        kind: s.kind,
        state: s.track.readyState,
        muted: s.track.muted,
        ssrc: ssrcOf(s),
        tapping: taps.has(trackId),
        frames: taps.get(trackId)?.frames ?? 0,
        inRate: taps.get(trackId)?.inRate ?? 0,
      })),
    });
  }

  window.addEventListener("message", (e) => {
    if (e.source !== window || e.data?.[TAG] !== "relay") return;
    const { cmd } = e.data;
    if (cmd === "start" && !enabled) {
      enabled = true;
      scan();
      for (const id of sources.keys()) startTap(id);
    } else if (cmd === "stop" && enabled) {
      enabled = false;
      for (const id of [...taps.keys()]) stopTap(id);
    }
    status();
  });

  setInterval(() => { scan(); if (enabled || sources.size) status(); }, STATUS_MS);
  post({ type: "ready" });
})();

#!/usr/bin/env node
/*
 * 개발용 수신 서버. 분석 서버(파이프라인)와 같은 계약만 흉내 내고, 받은 음성을 화자별 WAV 로 저장한다.
 * STT·분석은 하지 않는다. "확장이 Discord 에서 화자별 음성을 제대로 뽑는가"만 확인하는 용도.
 *
 *   node dev/receiver.mjs [--port 8081] [--out dev/out]
 *   POST /api/analysis/calls  → { callId, token, expiresInSeconds }
 *   WS   /ws/audio?callId&token&trackId&epoch   (PCM16 mono 16kHz)
 *   저장: <out>/<callId>/<trackId>-e<epoch>.wav
 *
 * 녹음 파일에는 통화 음성이 들어 있다. out 폴더는 git 에 넣지 않는다(.gitignore).
 */
import { createServer } from "node:http";
import { randomBytes, randomUUID } from "node:crypto";
import { mkdirSync, openSync, writeSync, closeSync } from "node:fs";
import { join, resolve } from "node:path";
import { WebSocketServer } from "ws";

const arg = (name, def) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : def;
};
const PORT = Number(arg("port", 8081));
const OUT = resolve(arg("out", new URL("./out", import.meta.url).pathname));
const RATE = 16000;
const TRACK_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** callId → token */
const calls = new Map();
/** 열린 녹음 */
const open = new Set();

function wavHeader(dataBytes) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + dataBytes, 4); h.write("WAVE", 8);
  h.write("fmt ", 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(RATE, 24); h.writeUInt32LE(RATE * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(dataBytes, 40);
  return h;
}

class Recording {
  constructor(callId, trackId, epoch) {
    const dir = join(OUT, callId);
    mkdirSync(dir, { recursive: true });
    this.path = join(dir, `${trackId}-e${epoch}.wav`);
    this.fd = openSync(this.path, "w");
    writeSync(this.fd, wavHeader(0));
    this.trackId = trackId;
    this.bytes = 0;
    this.peak = 0;
    this.sumSq = 0;
    this.n = 0;
  }
  write(buf) {
    writeSync(this.fd, buf, 0, buf.length, 44 + this.bytes);
    this.bytes += buf.length;
    for (let i = 0; i + 1 < buf.length; i += 2) {
      const s = buf.readInt16LE(i) / 32768;
      const a = Math.abs(s);
      if (a > this.peak) this.peak = a;
      this.sumSq += s * s;
      this.n++;
    }
    writeSync(this.fd, wavHeader(this.bytes), 0, 44, 0); // 도중에 끊겨도 열리는 파일이 되게 매번 갱신
  }
  close() {
    closeSync(this.fd);
    open.delete(this);
  }
  line() {
    const rms = this.n ? Math.sqrt(this.sumSq / this.n) : 0;
    const db = rms > 0 ? (20 * Math.log10(rms)).toFixed(1) : "-inf";
    this.peak = 0; this.sumSq = 0; this.n = 0;
    return `${this.trackId.padEnd(12)} ${(this.bytes / 2 / RATE).toFixed(1).padStart(6)}s  최근 RMS ${db} dBFS`;
  }
}

const http = createServer((req, res) => {
  // 확장 서비스 워커는 host_permissions 로 CORS 를 건너뛰지만, 브라우저 페이지에서 시험할 때를 위해 열어 둔다
  res.setHeader("Access-Control-Allow-Origin", "*");
  if (req.method === "OPTIONS") { res.writeHead(204, { "Access-Control-Allow-Methods": "POST" }).end(); return; }
  if (req.method === "POST" && req.url === "/api/analysis/calls") {
    const callId = randomUUID().slice(0, 8);
    const token = randomBytes(16).toString("hex");
    calls.set(callId, token);
    console.log(`[call] 새 통화 ${callId}`);
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ callId, token, expiresInSeconds: 3600 }));
    return;
  }
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 128 * 1024 });
http.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://x");
  const q = url.searchParams;
  const callId = q.get("callId"), trackId = q.get("trackId"), epoch = q.get("epoch") || "0";
  const reject = (code) => { socket.end(`HTTP/1.1 ${code}\r\n\r\n`); };
  if (url.pathname !== "/ws/audio") return reject("404 Not Found");
  if (!trackId || !TRACK_ID.test(trackId) || !/^\d{1,6}$/.test(epoch)) return reject("400 Bad Request");
  if (calls.get(callId) !== q.get("token")) return reject("401 Unauthorized");
  wss.handleUpgrade(req, socket, head, (ws) => {
    const rec = new Recording(callId, trackId, epoch);
    open.add(rec);
    console.log(`[open] ${callId}/${trackId} epoch ${epoch} origin=${req.headers.origin || "-"} → ${rec.path}`);
    ws.on("message", (data, isBinary) => { if (isBinary) rec.write(Buffer.from(data)); });
    ws.on("close", (code) => {
      console.log(`[close] ${callId}/${trackId} code ${code}  ${(rec.bytes / 2 / RATE).toFixed(1)}s 저장`);
      rec.close();
    });
  });
});

setInterval(() => {
  if (!open.size) return;
  console.log(`--- ${new Date().toLocaleTimeString()}`);
  for (const r of open) console.log("  " + r.line());
}, 5000).unref();

http.listen(PORT, "127.0.0.1", () => console.log(`receiver: http://127.0.0.1:${PORT}  저장 위치 ${OUT}`));

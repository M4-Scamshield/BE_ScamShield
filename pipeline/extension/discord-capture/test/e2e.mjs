/*
 * 확장 전체 흐름 시험: 가짜 Discord 페이지 → hook → relay → 서비스 워커 → 개발용 수신 서버 → WAV.
 * 실제 Discord 대신 같은 구조(연결 하나에 원격 오디오 트랙 여러 개 + getUserMedia 마이크)를 쓴다.
 *
 *   npm test        (처음 한 번 npx playwright install chromium)
 *
 * 확인하는 것
 *  - 화자별 파일이 따로 생긴다: me 1개 + peer 2개
 *  - 각 peer 파일의 주파수가 보낸 사인파(440·880Hz)와 맞다 → 섞이지 않았고 16kHz 로 제대로 바뀌었다
 *  - 길이가 수집한 시간과 맞다 (빠진 조각이 많지 않다)
 *  - 끄면 업로드 연결이 닫힌다
 */
import { chromium } from "playwright";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { cpSync, mkdtempSync, readFileSync, readdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const RECV_PORT = 18081, PAGE_PORT = 18090;
const CAPTURE_MS = 6000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 1. 확장 사본: 테스트 페이지 주소에도 content script 가 붙게 matches 만 늘린다
const work = mkdtempSync(join(tmpdir(), "ss-ext-"));
const ext = join(work, "ext");
cpSync(ROOT, ext, { recursive: true, filter: (p) => !/node_modules|[/\\]dev[/\\]out|[/\\]test[/\\]?/.test(p.slice(ROOT.length)) });
const manifest = JSON.parse(readFileSync(join(ext, "manifest.json"), "utf8"));
for (const cs of manifest.content_scripts) cs.matches.push("http://127.0.0.1/*");
writeFileSync(join(ext, "manifest.json"), JSON.stringify(manifest, null, 2));
const out = join(work, "out");

// 2. 수신 서버 + 테스트 페이지 서버
const recv = spawn(process.execPath, [join(ROOT, "dev/receiver.mjs"), "--port", String(RECV_PORT), "--out", out], { stdio: ["ignore", "pipe", "inherit"] });
let recvLog = "";
recv.stdout.on("data", (d) => (recvLog += d));
const page = createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }).end(readFileSync(join(ROOT, "test/fake-discord.html")));
}).listen(PAGE_PORT, "127.0.0.1");
await sleep(500);

let context;
try {
  // 3. 확장을 얹은 Chromium (가짜 마이크, 권한 자동 허용)
  context = await chromium.launchPersistentContext(join(work, "profile"), {
    channel: "chromium",
    headless: true,
    args: [
      `--disable-extensions-except=${ext}`,
      `--load-extension=${ext}`,
      "--use-fake-ui-for-media-stream",
      "--use-fake-device-for-media-stream",
      "--autoplay-policy=no-user-gesture-required",
    ],
  });
  const sw = context.serviceWorkers()[0] || (await context.waitForEvent("serviceworker"));
  await sw.evaluate((s) => chrome.storage.local.set({ server: s }), `http://127.0.0.1:${RECV_PORT}`);

  const tab = await context.newPage();
  tab.on("console", (m) => m.type() === "error" && console.log("[page]", m.text()));
  await tab.goto(`http://127.0.0.1:${PAGE_PORT}/`);
  await tab.waitForFunction(() => window.__fake?.ready || window.__fake?.error, null, { timeout: 15000 });
  const fake = await tab.evaluate(() => window.__fake);
  assert.equal(fake.error, undefined, "가짜 Discord 페이지 오류: " + fake.error);
  assert.equal(fake.hooked, true, "hook.js 가 페이지 코드보다 먼저 돌지 않았다");

  // 4. 수집 시작 (팝업 버튼과 같은 함수)
  const tabId = await sw.evaluate(async () => (await chrome.tabs.query({ url: "http://127.0.0.1/*" }))[0].id);
  await sleep(1500); // relay 포트 연결·트랙 발견 대기
  const started = await sw.evaluate((id) => self.ScamShield.startSession(id), tabId);
  console.log("시작:", started.session.callId);
  await sleep(CAPTURE_MS);
  const mid = await sw.evaluate(() => self.ScamShield.view());
  console.log("수집 중 상태:", JSON.stringify(mid.session.uploads), JSON.stringify(mid.tabs[0]?.status?.sources));
  assert.deepEqual(mid.session.errors, [], "수집 중 오류");
  await sw.evaluate(() => self.ScamShield.stopSession("test"));
  await sleep(800);
} finally {
  await context?.close();
  page.close();
  recv.kill();
}

// 5. 결과 확인
console.log(recvLog.split("\n").filter((l) => l.startsWith("[")).join("\n"));
const [callDir] = readdirSync(out);
const files = readdirSync(join(out, callDir)).sort();
console.log("파일:", files);

function analyze(path) {
  const buf = readFileSync(path);
  const n = (buf.length - 44) / 2;
  const s = (i) => buf.readInt16LE(44 + i * 2);
  // 가운데 1초에서 영교차로 주파수 추정
  const from = Math.max(0, Math.floor(n / 2) - 8000), to = Math.min(n, from + 16000);
  let cross = 0, peak = 0;
  for (let i = from + 1; i < to; i++) {
    if ((s(i - 1) < 0) !== (s(i) < 0)) cross++;
    peak = Math.max(peak, Math.abs(s(i)));
  }
  return { seconds: n / 16000, hz: (cross / 2) / ((to - from) / 16000), peak: peak / 32768 };
}

const results = Object.fromEntries(files.map((f) => [f, analyze(join(out, callDir, f))]));
console.table(results);

const peers = files.filter((f) => f.startsWith("peer-"));
assert.ok(files.includes("me-e0.wav"), "내 마이크(me) 파일이 없다");
assert.deepEqual(peers, ["peer-0-e0.wav", "peer-1-e0.wav"], "상대 트랙이 2개로 나뉘지 않았다");
assert.ok(results["me-e0.wav"].peak > 0.05, "내 마이크 파일이 무음이다");
const freqs = peers.map((f) => results[f].hz).sort((a, b) => a - b);
assert.ok(Math.abs(freqs[0] - 440) < 440 * 0.03, `낮은 쪽 ${freqs[0].toFixed(1)}Hz ≠ 440`);
assert.ok(Math.abs(freqs[1] - 880) < 880 * 0.03, `높은 쪽 ${freqs[1].toFixed(1)}Hz ≠ 880`);
for (const f of peers) {
  assert.ok(results[f].seconds > (CAPTURE_MS / 1000) * 0.8, `${f} 길이 ${results[f].seconds.toFixed(2)}s 가 너무 짧다`);
  assert.ok(results[f].seconds < (CAPTURE_MS / 1000) + 1.5, `${f} 길이 ${results[f].seconds.toFixed(2)}s 가 너무 길다`);
}
assert.ok(/\[close\][^\n]*peer/.test(recvLog), "끈 뒤 업로드 연결이 닫히지 않았다");
rmSync(work, { recursive: true, force: true });
console.log("OK: 화자별 분리·주파수·길이·종료 확인");

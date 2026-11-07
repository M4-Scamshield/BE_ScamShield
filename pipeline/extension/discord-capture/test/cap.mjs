/*
 * 화자 4개 상한 시험: 가짜 Discord 상대 5명 + 내 마이크 = 6개 트랙, 모두 소리를 낸다.
 *
 *   npm run test:cap
 *
 * 확인하는 것
 *  - 업로드는 4개만 열리고, 나머지는 "waiting"(팝업: 상한 초과)으로 보인다 (예전엔 "대기(소리 없음)"으로 잘못 보였다)
 *  - 열린 상대 하나를 조용하게 만들면, releaseSilentMs 뒤 그 연결이 닫히고(released) 기다리던 화자가 그 자리로 열린다
 *  - 수신기(서버와 같은 상한 4)에서 동시에 열린 화자는 끝까지 4개를 넘지 않는다
 */
import { chromium } from "playwright";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { cpSync, mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const RECV_PORT = 18082, PAGE_PORT = 18091;
const RELEASE_MS = 2000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const work = mkdtempSync(join(tmpdir(), "ss-cap-"));
const ext = join(work, "ext");
cpSync(ROOT, ext, { recursive: true, filter: (p) => !/node_modules|[/\\]dev[/\\]out|[/\\]test[/\\]?/.test(p.slice(ROOT.length)) });
const manifest = JSON.parse(readFileSync(join(ext, "manifest.json"), "utf8"));
for (const cs of manifest.content_scripts) cs.matches.push("http://127.0.0.1/*");
writeFileSync(join(ext, "manifest.json"), JSON.stringify(manifest, null, 2));

const recv = spawn(process.execPath, [join(ROOT, "dev/receiver.mjs"), "--port", String(RECV_PORT), "--out", join(work, "out")], { stdio: ["ignore", "pipe", "inherit"] });
let recvLog = "";
recv.stdout.on("data", (d) => (recvLog += d));
const page = createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }).end(readFileSync(join(ROOT, "test/fake-discord.html")));
}).listen(PAGE_PORT, "127.0.0.1");
await sleep(500);

const table = (v) => console.table(v.session.uploads.map(({ trackId, state, detail, epoch, seconds }) => ({ trackId, state, detail, epoch, seconds })));
let context, before, after, silenced, popupRows, stats;
try {
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
  await sw.evaluate(({ s, ms }) => {
    self.ScamShield.config.releaseSilentMs = ms;
    return chrome.storage.local.set({ server: s });
  }, { s: `http://127.0.0.1:${RECV_PORT}`, ms: RELEASE_MS });

  const tab = await context.newPage();
  await tab.goto(`http://127.0.0.1:${PAGE_PORT}/?peers=5`);
  await tab.waitForFunction(() => window.__fake?.ready || window.__fake?.error, null, { timeout: 15000 });
  assert.equal((await tab.evaluate(() => window.__fake)).error, undefined);

  const tabId = await sw.evaluate(async () => (await chrome.tabs.query({ url: "http://127.0.0.1/*" }))[0].id);
  await sleep(1500);
  const started = await sw.evaluate((id) => self.ScamShield.startSession(id), tabId);
  await sleep(3000);

  before = await sw.evaluate(() => self.ScamShield.view());
  console.log("상한에 걸린 상태:");
  table(before);
  const popup = await context.newPage();
  await popup.goto(`chrome-extension://${(new URL(sw.url())).host}/popup.html`);
  await popup.waitForTimeout(1500);
  popupRows = await popup.locator("#rows").innerText();
  await popup.close();

  // 열린 상대 중 하나를 조용하게
  silenced = before.session.uploads.find((u) => u.state === "open" && u.trackId.startsWith("peer-")).trackId;
  const mid = Number(silenced.split("-").pop());
  await tab.evaluate((k) => window.__fake.silence(k), mid);
  console.log(`${silenced} 를 조용하게 함 → ${RELEASE_MS}ms 뒤 자리 반납 기대`);
  await sleep(RELEASE_MS + 2500);

  after = await sw.evaluate(() => self.ScamShield.view());
  console.log("자리 반납 뒤:");
  table(after);
  stats = await (await fetch(`http://127.0.0.1:${RECV_PORT}/dev/stats?callId=${started.session.callId}`)).json();
  console.log("수신기:", stats);
  await sw.evaluate(() => self.ScamShield.stopSession("test"));
  await sleep(500);
} finally {
  await context?.close();
  page.close();
  recv.kill();
}

const count = (v, st) => v.session.uploads.filter((u) => u.state === st).length;
const waitingBefore = before.session.uploads.filter((u) => u.state === "waiting").map((u) => u.trackId);
assert.equal(before.session.uploads.length, 6, "트랙 6개가 다 잡히지 않았다");
assert.equal(count(before, "open"), 4, "처음에 4개가 열려야 한다");
assert.equal(waitingBefore.length, 2, "상한에 걸린 2개가 waiting 으로 보여야 한다");
assert.match(popupRows, /상한 초과/, "팝업에 상한 초과 표시가 없다");

const s = after.session.uploads.find((u) => u.trackId === silenced);
assert.equal(s.state, "released", `조용한 ${silenced} 가 자리를 내주지 않았다 (${s.state})`);
const promoted = after.session.uploads.filter((u) => waitingBefore.includes(u.trackId) && u.state === "open");
assert.equal(promoted.length, 1, "기다리던 화자 하나가 자리를 받아야 한다");
assert.equal(count(after, "open"), 4, "반납 뒤에도 4개가 열려 있어야 한다");
assert.ok(stats.maxConcurrent <= 4, `수신기에서 동시에 ${stats.maxConcurrent}개가 열렸다`);
rmSync(work, { recursive: true, force: true });
console.log(`OK: 상한 4개 · 상한 초과 표시 · ${silenced} 자리 반납 → ${promoted[0].trackId} 열림 · 동시 최대 ${stats.maxConcurrent} (상한으로 닫힘 ${stats.tooMany}번)`);

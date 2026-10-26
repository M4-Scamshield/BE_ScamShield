// PCM16 mono 16kHz 파일을 실시간 속도로 /ws/audio 에 흘려 넣고, 같은 통화의 SSE 를 받아 찍는다 (업로드·인증 경로 시험용).
// 사용: node tools/stream_pcm.mjs <file.pcm> [trackId=caller] [host=localhost:8081]
//   통화(callId·토큰)는 매번 새로 만든다. 이어 붙이려면 CALL_ID, TOKEN 환경변수를 준다.
import { readFileSync } from 'node:fs';
const [file, trackId = 'caller', host = 'localhost:8081'] = process.argv.slice(2);
if (!file) { console.error('usage: node tools/stream_pcm.mjs <file.pcm> [trackId] [host]'); process.exit(1); }
const pcm = readFileSync(file);
const CHUNK = 16000 * 2 / 10; // 0.1초

let callId = process.env.CALL_ID, token = process.env.TOKEN;
if (!callId) ({ callId, token } = await (await fetch(`http://${host}/api/analysis/calls`, { method: 'POST' })).json());
console.log(`callId=${callId}`);

// SSE: 이벤트 줄만 찍는다
const sse = new AbortController();
fetch(`http://${host}/api/analysis/calls/${callId}/events`, { headers: { Authorization: `Bearer ${token}` }, signal: sse.signal })
  .then(async r => {
    console.log(`SSE ${r.status}`);
    const dec = new TextDecoder();
    for await (const b of r.body) for (const line of dec.decode(b).split('\n')) if (/^(id|event|data):/.test(line)) console.log('  ' + line);
  }).catch(() => {});

const ws = new WebSocket(`ws://${host}/ws/audio?callId=${callId}&token=${token}&trackId=${trackId}&epoch=0`);
ws.binaryType = 'arraybuffer';
ws.onerror = () => { console.log('업로드 연결 실패'); sse.abort(); };
ws.onclose = e => console.log(`closed ${e.code} ${e.reason}`);
ws.onopen = async () => {
  const t0 = Date.now();
  for (let off = 0, i = 0; off < pcm.length; off += CHUNK, i++) {
    ws.send(pcm.subarray(off, off + CHUNK));
    const wait = t0 + (i + 1) * 100 - Date.now();
    if (wait > 0) await new Promise(r => setTimeout(r, wait));
  }
  console.log(`sent ${pcm.length} bytes (${(pcm.length / 32000).toFixed(1)}s)`);
  await new Promise(r => setTimeout(r, 1500));
  ws.close();
  await new Promise(r => setTimeout(r, 4000)); // 마지막 분석 결과까지
  sse.abort();
};

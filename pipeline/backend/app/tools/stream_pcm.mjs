// PCM16 mono 16kHz 파일을 실시간 속도로 /ws/audio 에 흘려 넣는다 (RTZR·업로드 경로 시험용).
// 사용: node tools/stream_pcm.mjs <file.pcm> <callId> [trackId=caller] [host=localhost:8081]
import { readFileSync } from 'node:fs';
const [file, callId, trackId = 'caller', host = 'localhost:8081'] = process.argv.slice(2);
if (!file || !callId) { console.error('usage: node tools/stream_pcm.mjs <file.pcm> <callId> [trackId] [host]'); process.exit(1); }
const pcm = readFileSync(file);
const CHUNK = 16000 * 2 / 10; // 0.1초
const ws = new WebSocket(`ws://${host}/ws/audio?callId=${callId}&trackId=${trackId}&epoch=0`);
ws.binaryType = 'arraybuffer';
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
};

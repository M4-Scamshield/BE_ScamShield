"""간이 부하: 통화 N개 × 화자 2명 × 발화 K개를 동시에 넣는다 (dev 발화 API).
사용: python3 sql/load.py <prefix> [calls=30] [per_speaker=5]
결과는 README 의 latency.sql 명령(MySQL)으로 본다."""
import json, random, sys, threading, time, urllib.request

P = sys.argv[1]
CALLS = int(sys.argv[2]) if len(sys.argv) > 2 else 30
K = int(sys.argv[3]) if len(sys.argv) > 3 else 5

def post(c, t, x):
    r = urllib.request.Request(f"http://localhost:8081/api/dev/calls/{c}/utterances",
                               json.dumps({"trackId": t, "text": x}).encode(), {"Content-Type": "application/json"})
    urllib.request.urlopen(r).read()

def call(i):
    def spk(t):
        for _ in range(K):
            post(f"{P}-{i}", t, "대화")
            time.sleep(random.random() * 0.3)
    ts = [threading.Thread(target=spk, args=(t,)) for t in ("caller", "callee")]
    [x.start() for x in ts]; [x.join() for x in ts]

t0 = time.time()
th = [threading.Thread(target=call, args=(i,)) for i in range(CALLS)]
[x.start() for x in th]; [x.join() for x in th]
print(f"published {CALLS * 2 * K} in {time.time() - t0:.1f}s")

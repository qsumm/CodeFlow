#!/usr/bin/env python3
"""C4 压测:并发打读接口,统计 QPS 与 p50/p95/p99 延迟。
用法: python3 load_test.py [base] [concurrency] [total]
"""
import json, statistics, sys, threading, time, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:7011"
CONC = int(sys.argv[2]) if len(sys.argv) > 2 else 20
TOTAL = int(sys.argv[3]) if len(sys.argv) > 3 else 400

def login():
    req = urllib.request.Request(BASE + "/api/auth/login", data=json.dumps(
        {"username": "v2smoke", "password": "secret123"}).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())["data"]["access_token"]

TOKEN = login()
PATHS = ["/api/workitems?pageNum=1&pageSize=20", "/api/agents", "/api/squads",
         "/api/sdlcs", "/api/memories?pageNum=1&pageSize=10", "/api/audit-logs?pageNum=1&pageSize=20"]

lat = []
errors = [0]
lock = threading.Lock()

def worker(n):
    for i in range(n):
        p = PATHS[i % len(PATHS)]
        req = urllib.request.Request(BASE + p)
        req.add_header("Authorization", "Bearer " + TOKEN)
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                r.read()
            ms = (time.perf_counter() - t0) * 1000
            with lock:
                lat.append(ms)
        except Exception:
            with lock:
                errors[0] += 1

threads = [threading.Thread(target=worker, args=(TOTAL // CONC,)) for _ in range(CONC)]
t0 = time.perf_counter()
for t in threads: t.start()
for t in threads: t.join()
dur = time.perf_counter() - t0
lat.sort()
def pct(p):
    return lat[min(len(lat) - 1, int(len(lat) * p))] if lat else 0
print(f"请求 {len(lat)} 成功 / {errors[0]} 失败 | 并发 {CONC} | 时长 {dur:.2f}s | QPS {len(lat)/dur:.0f}")
print(f"延迟 ms: p50={pct(.5):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={max(lat) if lat else 0:.1f}")

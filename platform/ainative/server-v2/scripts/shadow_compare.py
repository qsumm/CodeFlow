#!/usr/bin/env python3
"""C2 双跑影子比对:v1(:7001) vs v2(:7011/:7012) 读接口一致性。

用法: python3 shadow_compare.py <v1_base> <v1_token> <v2_base> <v2_token>
比对:工单(按 id/title)、Agent(id/name)、执行器数量、流水线名称、审计总数。
"""
import json, sys, urllib.request

def get(base, path, token):
    req = urllib.request.Request(base + path)
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

def main():
    v1, t1, v2, t2 = sys.argv[1:5]
    rows = []
    def add(name, a, b, note=""):
        rows.append((name, a, b, "OK" if a == b else "DIFF", note))

    # 工单
    w1 = get(v1, "/api/workitems?pageNum=1&pageSize=100", t1)["data"]
    w2 = get(v2, "/api/workitems?pageNum=1&pageSize=100", t2)["data"]
    add("工单总数", w1["total"], w2["total"])
    t1map = {x["id"]: x["title"] for x in w1["list"]}
    t2map = {x["id"]: x["title"] for x in w2["list"]}
    common = sorted(set(t1map) & set(t2map))
    same = sum(1 for i in common if t1map[i] == t2map[i])
    add("工单标题一致(v1∩v2)", f"{same}/{len(common)}", f"{same}/{len(common)}",
        "共同 id 标题逐条比对")
    # Agent
    a1 = get(v1, "/api/agents", t1)["data"]
    a2 = get(v2, "/api/agents", t2)["data"]
    add("Agent 数", len(a1), len(a2))
    n1 = {x["id"]: x["name"] for x in a1}
    n2 = {x["id"]: x["name"] for x in a2}
    common_a = sorted(set(n1) & set(n2))
    same_a = sum(1 for i in common_a if n1[i] == n2[i])
    add("Agent 名称一致(v1∩v2)", f"{same_a}/{len(common_a)}", f"{same_a}/{len(common_a)}")
    # 执行器
    e1 = get(v1, "/api/executors", t1)["data"]
    e2 = get(v2, "/api/executors", t2)["data"]
    add("执行器数", len(e1), len(e2))
    # 流水线
    s1 = {x["id"]: x["name"] for x in get(v1, "/api/sdlcs", t1)["data"]}
    s2 = {x["id"]: x["name"] for x in get(v2, "/api/sdlcs", t2)["data"]}
    common_s = sorted(set(s1) & set(s2))
    same_s = sum(1 for i in common_s if s1[i] == s2[i])
    add("流水线名称一致(v1∩v2)", f"{same_s}/{len(common_s)}", f"{same_s}/{len(common_s)}")
    # 审计(v1 返回列表、v2 返回分页,兼容两种)
    au1 = get(v1, "/api/audit-logs?pageNum=1&pageSize=1", t1)["data"]
    au2 = get(v2, "/api/audit-logs?pageNum=1&pageSize=1", t2)["data"]
    n1_au = au1["total"] if isinstance(au1, dict) else len(au1)
    n2_au = au2["total"] if isinstance(au2, dict) else len(au2)
    add("审计总数(分页/列表口径)", n1_au, n2_au)

    print(f"{'指标':32} {'v1':>12} {'v2':>12}  结果")
    diffs = 0
    for name, a, b, st, *note in rows:
        print(f"{name:32} {str(a):>12} {str(b):>12}  {st}")
        if st == "DIFF":
            diffs += 1
    print(f"\n差异项: {diffs}/{len(rows)}")
    return 0

if __name__ == "__main__":
    sys.exit(main())

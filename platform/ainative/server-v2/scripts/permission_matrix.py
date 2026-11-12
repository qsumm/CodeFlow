#!/usr/bin/env python3
"""C5 权限矩阵用例:匿名/成员/管理员 三类身份 × 公共/业务/管理域 接口。"""
import json, sys, time, urllib.error, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:7011"

def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"http": e.code}

P = F = 0
def check(name, cond, detail=""):
    global P, F
    if cond: P += 1; print(f"  PASS  {name}")
    else: F += 1; print(f"  FAIL  {name}  {detail}")

def login(user, pwd):
    r = call("POST", "/api/auth/login", {"username": user, "password": pwd})
    return r["data"]["access_token"] if r.get("success") else None

def main():
    suffix = str(int(time.time()))[-6:]
    admin = login("v2smoke", "secret123")
    check("管理员登录", admin is not None)
    # 成员
    member_user = f"member_{suffix}"
    call("POST", "/api/auth/register", {"username": member_user, "password": "secret123"})
    member = login(member_user, "secret123")
    check("成员登录", member is not None)

    print("\n[匿名]")
    check("公开品牌可访问", call("GET", "/api/platform/branding/public").get("success") is True)
    check("业务接口被拒 10401", call("GET", "/api/workitems").get("code") == "10401")
    check("管理接口被拒 10401", call("GET", "/api/audit-logs").get("code") == "10401")

    print("\n[成员]")
    check("业务读可访问", call("GET", "/api/workitems", token=member).get("success") is True)
    check("业务写可访问(建工单)", call("POST", "/api/workitems",
        {"workType": "REQ", "title": "权限用例", "contentMd": "x"}, token=member).get("success") is True)
    check("审计查看被拒 10403", call("GET", "/api/audit-logs", token=member).get("code") == "10403")
    check("设置写入被拒 10403", call("PUT", "/api/settings/AI",
        {"items": [{"key": "k", "valueJson": "v"}]}, token=member).get("code") == "10403")
    check("品牌写入被拒 10403", call("PUT", "/api/platform/branding",
        {"platformName": "X"}, token=member).get("code") == "10403")
    check("IM 渠道写入被拒 10403", call("PUT", "/api/platform/im-channels/dingtalk",
        {"appKey": "x", "enabled": True}, token=member).get("code") == "10403")
    check("定时任务写入被拒 10403", call("POST", "/api/scheduled-tasks",
        {"name": "x", "cronExpr": "0 0 1 * * *"}, token=member).get("code") == "10403")

    print("\n[管理员]")
    check("审计查看通过", call("GET", "/api/audit-logs?pageNum=1&pageSize=1", token=admin).get("success") is True)
    check("设置写入通过", call("PUT", "/api/settings/AI",
        {"items": [{"key": "matrix", "valueJson": "ok"}]}, token=admin).get("success") is True)
    check("品牌写入通过", call("PUT", "/api/platform/branding",
        {"platformName": "CodeFlow", "themeKey": "ocean-blue", "primaryColor": "#2383e2"}, token=admin).get("success") is True)
    check("定时任务写入通过", call("POST", "/api/scheduled-tasks",
        {"name": f"matrix-{suffix}", "cronExpr": "0 0 2 * * *"}, token=admin).get("success") is True)

    print(f"\n===== 权限矩阵: {P} 通过 / {F} 失败 =====")
    return 0 if F == 0 else 1

if __name__ == "__main__":
    sys.exit(main())

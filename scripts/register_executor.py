#!/usr/bin/env python3
"""为指定数字员工注册一个 open-runtime 执行器,签发并保存连接配置。

用法:
  export AINATIVE_BASE_URL=http://localhost:7001
  export AINATIVE_ADMIN_USER=admin
  export AINATIVE_ADMIN_PASSWORD=...
  python scripts/register_executor.py --agent-id 10001 --out runtime.env

产出 --out 指定的 env 文件,可直接被 runtime 读取(含 executorId/token,chmod 600)。
"""
from __future__ import annotations

import argparse
import json
import os
import stat
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ADMIN_USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD")


def api(method: str, path: str, token: str | None = None, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--agent-id", type=int, required=True, help="数字员工 ID(平台 Agents 页可见)")
    ap.add_argument("--workspace-id", type=int, required=True, help="工作空间 ID")
    ap.add_argument("--executor-name", default=f"open-runtime-{int(time.time())}")
    ap.add_argument("--out", default="runtime.env", help="生成的 env 文件路径")
    ap.add_argument("--provider", default="mock", choices=["mock", "claude"])
    a = ap.parse_args()
    if not ADMIN_PASSWORD:
        raise SystemExit("请设置 AINATIVE_ADMIN_PASSWORD")

    login = api("POST", "/api/auth/login", body={"username": ADMIN_USER, "password": ADMIN_PASSWORD})["data"]
    access = api("POST", "/api/auth/refresh",
                 body={"refreshToken": login["refreshToken"], "workspaceId": a.workspace_id})["data"]["accessToken"]
    created = api("POST", f"/api/agents/{a.agent_id}/executors", token=access,
                  body={"name": a.executor_name, "clientKind": "QODER_CN_CLI",
                        "memoryMode": "none", "maxConcurrentDispatches": 1})["data"]

    lines = [f"AINATIVE_SERVER_WS_URL={'wss' if BASE.startswith('https') else 'ws'}://{BASE.split('//', 1)[1]}/ws/executor",
             f"AINATIVE_EXECUTOR_ID={created['id']}",
             f"AINATIVE_EXECUTOR_TOKEN={created['token']}",
             f"AINATIVE_PROVIDER={a.provider}"]
    out = Path(a.out)
    out.write_text("\n".join(lines) + "\n")
    out.chmod(stat.S_IRUSR | stat.S_IWUSR)
    print(f"executor {created['id']} registered; env written to {out} (chmod 600)")
    print("启动验证: set -a; . ./%s; set +a; open-runtime --once-smoke" % a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

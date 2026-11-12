#!/usr/bin/env python3
"""E2E orchestration against a local AINative server (read-only SQL for assertions).

Subcommands:
  provision   login -> apply solo template -> create executor -> write runtime env + e2e_state.json
  run         start the daemon (mock provider) in foreground until Ctrl+C or --once
  assert      poll server DB until workitem reaches DONE (or timeout), print verdict
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ADMIN_USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD")  # 必填:不提供默认密码
MYSQL_CONTAINER = os.environ.get("AINATIVE_MYSQL_CONTAINER", "deploy-local-mysql-1")
MYSQL_ROOT_PASSWORD = os.environ.get("AINATIVE_MYSQL_ROOT_PASSWORD", "change-root-me")
MYSQL_DB = os.environ.get("AINATIVE_MYSQL_DB", "ainative")
STATE = Path(__file__).resolve().parent.parent / "e2e_state.json"


def api(method: str, path: str, token: str | None = None, body: dict | None = None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {detail}") from e


def sql(query: str) -> list[str]:
    mysql = ["docker", "exec", MYSQL_CONTAINER, "mysql", "-uroot", f"-p{MYSQL_ROOT_PASSWORD}",
             MYSQL_DB, "-N", "-B", "-e"]
    out = subprocess.run(mysql + [query], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"sql failed: {out.stderr[:300]}")
    return [ln for ln in out.stdout.splitlines() if ln]


def provision():
    if not ADMIN_PASSWORD:
        raise SystemExit("请设置 AINATIVE_ADMIN_PASSWORD(平台管理员密码)")
    login = api("POST", "/api/auth/login", body={"username": ADMIN_USER, "password": ADMIN_PASSWORD})
    d = login["data"]
    ws = api("GET", "/api/workspaces/mine", token=d["accessToken"])
    workspaces = ws["data"]
    if not workspaces:
        raise SystemExit("no workspace; create one first in UI")
    ws_id = workspaces[0]["id"]
    ws_name = workspaces[0].get("name")
    print(f"workspace: {ws_id} ({ws_name})")
    tok = api("POST", "/api/auth/refresh",
              body={"refreshToken": d["refreshToken"], "workspaceId": ws_id})["data"]
    access = tok["accessToken"]

    templates = api("GET", "/api/squad-templates", token=access)["data"]
    solo = next((t for t in templates if "独立开发者" in str(t.get("name", ""))), templates[0])
    print(f"template: {solo['id']} {solo['name']}")
    applied = api("POST", f"/api/squad-templates/{solo['id']}/apply", token=access)["data"]
    squad_id, agents = applied["squadId"], applied["agents"]
    agent_id = agents[0]["agentId"]
    print(f"squad: {squad_id} agent: {agent_id} role={agents[0]['roleCode']}")

    created = api("POST", f"/api/agents/{agent_id}/executors", token=access,
                  body={"name": f"open-runtime-{int(time.time())}", "clientKind": "QODER_CN_CLI",
                        "memoryMode": "none", "maxConcurrentDispatches": 1})["data"]
    exec_id, exec_token = created["id"], created["token"]
    print(f"executor: {exec_id} (token captured)")

    state = {"workspaceId": ws_id, "squadId": squad_id, "agentId": agent_id,
             "executorId": exec_id, "token": exec_token,
             "accessToken": access, "refreshToken": d["refreshToken"]}
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1))
    env = (f"AINATIVE_SERVER_WS_URL=ws://localhost:7001/ws/executor\n"
           f"AINATIVE_EXECUTOR_ID={exec_id}\n"
           f"AINATIVE_EXECUTOR_TOKEN={exec_token}\n"
           f"AINATIVE_PROVIDER=mock\n"
           f"RUNTIME_DATA_DIR={Path(__file__).resolve().parent.parent / 'runtime-data'}\n"
           f"RUNTIME_LOG_LEVEL=info\n")
    (STATE.parent / "runtime.env").write_text(env)
    print(f"wrote {STATE.name} and runtime.env")


def create_workitem() -> int:
    st = json.loads(STATE.read_text())
    body = {"workType": "WORKITEM", "title": f"E2E 验证单 {int(time.time())}",
            "contentMd": "open-runtime 端到端验证:请完成本单并输出结论。",
            "priority": 2, "assigneeType": "AGENT", "assigneeRef": st["agentId"],
            "squadId": st["squadId"]}
    r = api("POST", "/api/workitems", token=st["accessToken"], body=body)
    wid = r["data"]["id"]
    print(f"workitem: {wid}")
    return wid


def run_daemon(once: bool = False):
    env = dict(os.environ)
    for line in (STATE.parent / "runtime.env").read_text().splitlines():
        k, _, v = line.partition("=")
        env[k] = v
    cmd = [sys.executable, "-m", "runtime.daemon"] + (["--once-smoke"] if once else [])
    os.chdir(Path(__file__).resolve().parent.parent)
    return subprocess.run(cmd, env=env).returncode


def dispatch_rows(wid: int) -> list[str]:
    return sql(f"SELECT id, agent_id, status, attempt FROM dispatch WHERE workitem_id={wid} ORDER BY id")


def workitem_state(wid: int) -> str:
    rows = sql(f"SELECT w.status_node_id, COALESCE(sn.node_type,'?'), COALESCE(sn.name,'?')"
               f" FROM workitem w LEFT JOIN sdlc_node sn ON sn.id=w.status_node_id WHERE w.id={wid}")
    return rows[0] if rows else "GONE"


def assert_e2e(wid: int, timeout_sec: int = 180):
    deadline = time.time() + timeout_sec
    seen = set()
    while time.time() < deadline:
        wi = workitem_state(wid)
        rows = dispatch_rows(wid)
        states = {r.split("\t")[2] for r in rows}
        seen |= states
        print(f"t+{int(timeout_sec - (deadline - time.time())):3}s workitem={wi} dispatches={rows}", flush=True)
        if any(r.split("\t")[2] in ("SUCCEEDED", "FAILED", "TIMEOUT") for r in rows) and "PASS_PROBE" == "NEVER":
            pass
        if rows and all(r.split("\t")[2] in ("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELED") for r in rows):
            final = [r.split("\t")[2] for r in rows]
            verdict = "PASS" if "FAILED" not in final and "TIMEOUT" not in final else "FAIL"
            print(f"{verdict}: all dispatches terminal={final}; workitem now at node={wi}")
            return 0 if verdict == "PASS" else 1
        time.sleep(5)
    print(f"TIMEOUT: last workitem={workitem_state(wid)} states={sorted(seen)}")
    return 2


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["provision", "create-workitem", "run", "smoke", "assert"])
    p.add_argument("--workitem-id", type=int)
    p.add_argument("--once", action="store_true")
    a = p.parse_args()
    if a.cmd == "provision":
        provision()
    elif a.cmd == "create-workitem":
        create_workitem()
    elif a.cmd == "run":
        sys.exit(run_daemon())
    elif a.cmd == "smoke":
        sys.exit(run_daemon(once=True))
    elif a.cmd == "assert":
        sys.exit(assert_e2e(a.workitem_id or json.loads(STATE.read_text()).get("lastWorkitemId", 0)))

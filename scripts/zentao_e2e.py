#!/usr/bin/env python3
"""禅道闭环 E2E 助手(只构造数据 + 只读断言;执行全部交给平台与数字员工)。

分工:
  本脚本 = 数据构造者(在禅道建需求/缺陷)+ 观察者(只读断言平台与禅道状态)
  平台   = 轮询拉取、派发数字员工、产出交付物、回写禅道
  数字员工 = 真正干活的(claude CLI + 模型 API)

凭据一律从环境变量读取,不入库不入日志。

子命令:
  bind                 在平台建禅道绑定(测试连接 → 选产品 → 保存)
  create-story         在禅道建需求(story),返回 story id
  create-bug           在禅道建缺陷(bug),返回 bug id
  list                 列出禅道产品/需求/缺陷(只读)
  wait-import          轮询平台,等外部工单入库(返回 workitem id)
  assign               把导入的工单指派给小队+Agent(触发平台派发)
  wait-done            轮询平台,等工单到达完成态
  show                 只读打印工单当前状态/事件/交付物
  verify-writeback     只读校验禅道侧状态与备注是否被回写
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / "e2e_state.json"

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ZT_BASE = os.environ.get("ZENTAO_BASE_URL", "http://localhost:8888")
ZT_ACCOUNT = os.environ.get("ZENTAO_ACCOUNT", "")
ZT_PASSWORD = os.environ.get("ZENTAO_PASSWORD", "")
ZT_PRODUCT = os.environ.get("ZENTAO_PRODUCT_ID", "1")


# ---------- 通用 ----------

def _json(method: str, url: str, body=None, headers=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, method=method, data=data)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode(errors="replace")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:400]
        raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}") from e


def api(method: str, path: str, token: str | None = None, body=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return _json(method, BASE + path, body=body, headers=headers)


def state() -> dict:
    return json.loads(STATE.read_text())


def zt_token() -> str:
    resp = _json("POST", f"{ZT_BASE}/api.php/v1/tokens",
                 body={"account": ZT_ACCOUNT, "password": ZT_PASSWORD})
    token = resp.get("token")
    if not token:
        raise SystemExit(f"禅道取 token 失败: {resp}")
    return token


def zt(method: str, path: str, body=None):
    return _json(method, f"{ZT_BASE}/api.php/v1{path}", body=body,
                 headers={"Token": zt_token()})


def sql(query: str) -> list[str]:
    import subprocess
    container = os.environ.get("AINATIVE_MYSQL_CONTAINER", "platform-mysql-1")
    root_pw = os.environ.get("AINATIVE_MYSQL_ROOT_PASSWORD", "change-root-me")
    db = os.environ.get("AINATIVE_MYSQL_DB", "ainative")
    out = subprocess.run(["docker", "exec", container, "mysql", "-uroot", f"-p{root_pw}",
                          db, "-N", "-B", "-e", query], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"sql failed: {out.stderr[:300]}")
    return [ln for ln in out.stdout.splitlines() if ln]


# ---------- 平台侧 ----------

def cmd_bind(args):
    """建禅道绑定:测试连接 → 搜索产品 → 保存绑定。"""
    token = state()["accessToken"]
    creds = {"baseUrl": ZT_BASE, "account": ZT_ACCOUNT, "password": ZT_PASSWORD}
    test = api("POST", "/api/integrations/zentao/bindings/test", token=token, body=creds)
    print("testConnection:", json.dumps(test.get("data"), ensure_ascii=False))
    if not (test.get("data") or {}).get("success"):
        raise SystemExit("连接测试未通过,终止")
    projects = api("POST", "/api/integrations/zentao/projects/search", token=token, body=creds)
    items = projects.get("data") or []
    print("products:", [(p.get("externalId"), p.get("name")) for p in items])
    chosen = next((p for p in items if str(p.get("externalId")) == str(ZT_PRODUCT)), None)
    if chosen is None:
        raise SystemExit(f"产品 {ZT_PRODUCT} 不在可见列表")
    body = dict(creds)
    body.update({
        "externalProjectId": str(chosen["externalId"]),
        "externalProjectName": chosen.get("name"),
        "pollIntervalSeconds": 15,
        "enabled": True,
    })
    created = api("POST", "/api/integrations/zentao/bindings", token=token, body=body)
    data = created.get("data") or {}
    print("binding:", json.dumps({k: data.get(k) for k in
                                   ("id", "provider", "externalProjectId", "externalProjectName",
                                    "baseUrl", "enabled", "pollIntervalSeconds")}, ensure_ascii=False))
    return data.get("id")


def cmd_list(args):
    print("products:", json.dumps(zt("GET", "/products"), ensure_ascii=False)[:400])
    for kind in ("stories", "bugs"):
        data = zt("GET", f"/products/{ZT_PRODUCT}/{kind}")
        print(f"{kind}: total={data.get('total')}")
        for item in (data.get(kind) or [])[:10]:
            print("  ", item.get("id"), item.get("title"), "status=", item.get("status"))


# ---------- 禅道侧数据构造 ----------

def cmd_create_story(args):
    """在禅道建需求(数据构造)。"""
    body = {
        "title": args.title,
        "spec": args.spec,
        "pri": args.pri,
        "category": args.category,
    }
    resp = zt("POST", f"/products/{ZT_PRODUCT}/stories?status=active", body=body)
    print("story created:", json.dumps({k: resp.get(k) for k in
                                        ("id", "title", "status", "spec")}, ensure_ascii=False))
    if args.out:
        Path(args.out).write_text(json.dumps({"storyId": resp.get("id"), "title": args.title},
                                             ensure_ascii=False, indent=1))
    return resp.get("id")


def cmd_create_bug(args):
    """在禅道建缺陷(数据构造)。"""
    body = {
        "title": args.title,
        "steps": args.steps,
        "pri": args.pri,
        "severity": args.severity,
        "type": "codeerror",
        "openedBuild": "Trunk",
    }
    if args.story_id:
        body["story"] = args.story_id
    resp = zt("POST", f"/products/{ZT_PRODUCT}/bugs", body=body)
    print("bug created:", json.dumps({k: resp.get(k) for k in
                                      ("id", "title", "status", "severity", "steps")}, ensure_ascii=False))
    if args.out:
        Path(args.out).write_text(json.dumps({"bugId": resp.get("id"), "title": args.title},
                                             ensure_ascii=False, indent=1))
    return resp.get("id")


# ---------- 观察(只读) ----------

def cmd_wait_import(args):
    """等外部工单入库(轮询 external_workitem_link)。"""
    deadline = time.time() + args.timeout
    prefix = args.external_prefix
    while time.time() < deadline:
        rows = sql(f"SELECT l.external_workitem_id, l.workitem_id, l.provider, l.sync_status "
                   f"FROM external_workitem_link l WHERE l.external_workitem_id LIKE '{prefix}%' "
                   f"ORDER BY l.id DESC")
        if rows:
            print("imported:", rows)
            return int(rows[0].split("\t")[1])
        time.sleep(3)
    raise SystemExit(f"超时:未等到 {prefix}* 入库")


def cmd_assign(args):
    """把导入的工单指派给小队 + Agent(触发平台派发)。"""
    token = state()["accessToken"]
    st = state()
    body = {"assigneeType": "AGENT", "assigneeRef": st["agentId"],
            "squadId": st["squadId"]}
    resp = api("PUT", f"/api/workitems/{args.workitem_id}/assignee", token=token, body=body)
    data = resp.get("data") or {}
    print("assigned:", json.dumps({k: data.get(k) for k in
                                   ("id", "title", "assigneeType", "assigneeRef", "statusNodeId",
                                    "sdlcId", "currentStepId")}, ensure_ascii=False))
    return data.get("id")


def cmd_wait_done(args):
    """等工单到达完成态(只读轮询)。"""
    deadline = time.time() + args.timeout
    last = None
    while time.time() < deadline:
        rows = sql(f"SELECT w.id, w.status_node_id, COALESCE(n.name,'?'), n.handler_type, "
                   f"w.assignee_type, COALESCE(w.assignee_ref,0) FROM workitem w "
                   f"LEFT JOIN sdlc_step n ON n.id=w.status_node_id WHERE w.id={args.workitem_id}")
        disp = sql(f"SELECT id, status, attempt FROM dispatch WHERE workitem_id={args.workitem_id} "
                   f"ORDER BY id")
        cur = (rows[0] if rows else "GONE")
        if cur != last:
            print(f"workitem={cur} dispatches={disp}", flush=True)
            last = cur
        if disp and all(r.split("\t")[1] in ("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELED") for r in disp):
            finals = [r.split("\t")[1] for r in disp]
            verdict = "PASS" if "FAILED" not in finals and "TIMEOUT" not in finals else "FAIL"
            print(f"{verdict}: dispatch finals={finals}; workitem={cur}")
            return 0 if verdict == "PASS" else 1
        time.sleep(5)
    print(f"TIMEOUT: workitem={last}")
    return 2


def cmd_show(args):
    wid = args.workitem_id
    rows = sql(f"SELECT w.id, w.title, w.work_type, w.status_node_id, COALESCE(n.name,'?'), "
               f"w.assignee_type, COALESCE(w.assignee_ref,0), COALESCE(w.sdlc_id,0) "
               f"FROM workitem w LEFT JOIN sdlc_step n ON n.id=w.status_node_id WHERE w.id={wid}")
    print("workitem:", rows[0] if rows else "GONE")
    print("link:", sql(f"SELECT provider, external_workitem_id, external_url, sync_status, "
                        f"source_status_name, last_sync_direction, last_error FROM "
                        f"external_workitem_link WHERE workitem_id={wid}"))
    print("events:", sql(f"SELECT event_type, actor_type, gmt_create FROM workitem_event "
                         f"WHERE workitem_id={wid} ORDER BY id"))
    print("dispatch:", sql(f"SELECT id, agent_id, status, attempt FROM dispatch "
                           f"WHERE workitem_id={wid} ORDER BY id"))
    print("comments:", sql(f"SELECT id, source_type, comment_id, status FROM workitem_comment_delivery "
                           f"WHERE workitem_id={wid} ORDER BY id"))
    print("artifacts:", sql(f"SELECT id, name, type FROM artifact WHERE workitem_id={wid} ORDER BY id"))


def cmd_verify_writeback(args):
    """只读校验:禅道侧状态/备注是否按平台动作被回写。"""
    kind, oid = args.kind, args.object_id
    detail = zt("GET", f"/{'bugs' if kind == 'bug' else 'stories'}/{oid}")
    print(f"{kind}-{oid}: status={detail.get('status')} resolution={detail.get('resolution')} "
          f"lastEditedBy={(detail.get('lastEditedBy') or {}).get('account')} "
          f"lastEditedDate={detail.get('lastEditedDate')}")
    actions = detail.get("actions") or []
    print(f"actions={len(actions)}")
    for a in actions[-5:]:
        print("  ", a.get("action"), "|", (a.get("comment") or "").replace("\n", " ")[:160],
              "|", a.get("date"))
    return 0


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("bind").set_defaults(func=cmd_bind)
    sub.add_parser("list").set_defaults(func=cmd_list)

    sp = sub.add_parser("create-story")
    sp.add_argument("--title", required=True)
    sp.add_argument("--spec", required=True)
    sp.add_argument("--pri", type=int, default=1)
    sp.add_argument("--category", default="feature")
    sp.add_argument("--out")
    sp.set_defaults(func=cmd_create_story)

    sb = sub.add_parser("create-bug")
    sb.add_argument("--title", required=True)
    sb.add_argument("--steps", required=True)
    sb.add_argument("--pri", type=int, default=1)
    sb.add_argument("--severity", type=int, default=3)
    sb.add_argument("--story-id")
    sb.add_argument("--out")
    sb.set_defaults(func=cmd_create_bug)

    si = sub.add_parser("wait-import")
    si.add_argument("--external-prefix", required=True, help="如 story- 或 bug-")
    si.add_argument("--timeout", type=int, default=180)
    si.set_defaults(func=cmd_wait_import)

    sa = sub.add_parser("assign")
    sa.add_argument("--workitem-id", type=int, required=True)
    sa.set_defaults(func=cmd_assign)

    sd = sub.add_parser("wait-done")
    sd.add_argument("--workitem-id", type=int, required=True)
    sd.add_argument("--timeout", type=int, default=900)
    sd.set_defaults(func=cmd_wait_done)

    ss = sub.add_parser("show")
    ss.add_argument("--workitem-id", type=int, required=True)
    ss.set_defaults(func=cmd_show)

    sv = sub.add_parser("verify-writeback")
    sv.add_argument("--kind", choices=["bug", "story"], required=True)
    sv.add_argument("--object-id", required=True)
    sv.set_defaults(func=cmd_verify_writeback)

    a = p.parse_args()
    sys.exit(a.func(a) or 0)

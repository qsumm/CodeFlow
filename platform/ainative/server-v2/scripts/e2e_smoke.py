#!/usr/bin/env python3
"""AINative Server v2 端到端冒烟用例(跨 10 域,stdlib only)。

用法: python3 scripts/e2e_smoke.py [base_url]
默认 http://localhost:7011;每个用例打印 PASS/FAIL,末尾汇总。
"""
import json
import sys
import time
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:7011"
PASS = 0
FAIL = 0
FAILED = []


def call(method, path, body=None, token=None, expect=200):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode() or "{}")
            return resp.status, payload
    except urllib.error.HTTPError as e:
        return e.code, {"raw": e.read().decode()[:200]}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        FAILED.append(name)
        print(f"  FAIL  {name}  {detail}")


def case(name):
    print(f"\n[{name}]")


def main():
    suffix = str(int(time.time()))[-6:]
    user = f"e2e_{suffix}"

    case("身份域")
    s, r = call("POST", "/api/auth/register", {"username": user, "password": "secret123", "nickname": "E2E", "email": f"{user}@example.com"})
    check("注册", s == 200 and r.get("success"), r)
    s, r = call("POST", "/api/auth/login", {"username": user, "password": "secret123"})
    check("登录", s == 200 and r.get("success"), r)
    token = r["data"]["access_token"] if r.get("success") else ""
    s, r = call("GET", "/api/auth/me", token=token)
    check("me", s == 200 and r.get("success") and r["data"]["username"] == user, r)
    s, r = call("POST", "/api/auth/login", {"username": user, "password": "bad"})
    check("错误密码被拒", r.get("code") == "10401", r)
    # 平台管理域用例需要管理员令牌(预置账号 v2smoke 为管理员)
    s, r = call("POST", "/api/auth/login", {"username": "v2smoke", "password": "secret123"})
    admin_token = r["data"]["access_token"] if r.get("success") else token

    case("工作项域")
    s, r = call("POST", "/api/workitems", {"workType": "REQ", "title": f"E2E 工单 {suffix}", "contentMd": "端到端验证", "priority": 2}, token=token)
    check("创建工单", s == 200 and r.get("success"), r)
    wid = r["data"]["id"] if r.get("success") else 0
    check("默认状态=新建", r.get("data", {}).get("statusName") == "新建", r.get("data", {}).get("statusName"))
    s, r = call("GET", f"/api/workitems/{wid}", token=token)
    check("工单详情", s == 200 and r.get("success"), r)
    s, r = call("GET", "/api/workitems?pageNum=1&pageSize=5", token=token)
    check("工单列表", s == 200 and r.get("success") and r["data"]["total"] >= 1, r)
    s, r = call("PUT", f"/api/workitems/{wid}/tags", {"tags": ["e2e", "smoke"]}, token=token)
    check("标签替换", r.get("success") and set(r["data"]["tags"]) == {"e2e", "smoke"}, r)
    s, r = call("GET", "/api/agents", token=token)
    agents = r.get("data", []) if r.get("success") else []
    if not agents:
        s, r = call("POST", "/api/agents", {"name": f"E2E Agent {suffix}", "roleCode": "FS_DEV"}, token=token)
        agents = [r["data"]] if r.get("success") else []
    agent_id = agents[0]["id"] if agents else 0
    s, r = call("PUT", f"/api/workitems/{wid}/assignee", {"assigneeType": "AGENT", "assigneeRef": agent_id}, token=token)
    check("指派 Agent", r.get("success") and r["data"]["assigneeType"] == "AGENT", r)
    s, r = call("POST", f"/api/workitems/{wid}/comments", {"contentMd": "E2E 评论"}, token=token)
    check("评论", r.get("success"), r)
    s, r = call("GET", f"/api/workitems/{wid}/unified-timeline", token=token)
    kinds = {x["kind"] for x in r.get("data", [])} if r.get("success") else set()
    check("统一时间线含事件+评论", {"EVENT", "COMMENT"} <= kinds, kinds)

    case("状态机 + 流转")
    s, r = call("GET", "/api/status-templates", token=token)
    check("状态模板列表", s == 200 and r.get("success") and len(r["data"]) >= 1, r)
    flow = r["data"][0]["id"]
    s, r = call("GET", f"/api/status-templates/{flow}", token=token)
    nodes = {n["name"]: n["id"] for n in r["data"]["nodes"]} if r.get("success") else {}
    check("模板节点", "新建" in nodes and "开发中" in nodes, nodes)
    s, r = call("POST", f"/api/workitems/{wid}/transition", {"toNodeId": nodes.get("开发中")}, token=token)
    check("合法流转 → 开发中", r.get("success") and r["data"]["statusName"] == "开发中", r)
    s, r = call("POST", f"/api/workitems/{wid}/transition", {"toNodeId": nodes.get("已发布")}, token=token)
    check("非法流转被拒(13004)", r.get("code") == "13004", r)

    case("小队与流水线域")
    s, r = call("POST", "/api/sdlcs", {"name": f"E2E 流水线 {suffix}"}, token=token)
    sid = r["data"]["id"] if r.get("success") else 0
    check("建流水线", s == 200 and r.get("success"), r)
    call("POST", f"/api/sdlcs/{sid}/steps", {"name": "分析", "kind": "WORK"}, token=token)
    s, r = call("POST", f"/api/sdlcs/{sid}/steps", {"name": "编码", "kind": "WORK"}, token=token)
    check("加步骤", r.get("success") and len(r["data"]["steps"]) == 2, r.get("data", {}).get("steps"))
    s, r = call("POST", f"/api/sdlcs/{sid}/enable", token=token)
    check("启用流水线", r.get("success") and r["data"]["status"] == "ACTIVE", r)
    s, r = call("POST", "/api/squads", {"name": f"E2E 小队 {suffix}"}, token=token)
    qid = r["data"]["id"] if r.get("success") else 0
    call("POST", f"/api/squads/{qid}/members", {"agentId": 10001, "roleCode": "FS_DEV"}, token=token)
    s, r = call("GET", f"/api/squads/{qid}", token=token)
    check("小队成员", r.get("success") and 10001 in r["data"]["memberAgentIds"], r)

    case("智能体域")
    s, r = call("POST", "/api/agents", {"name": f"E2E Agent {suffix}", "roleCode": "FS_DEV", "roleName": "全栈"}, token=token)
    aid = r["data"]["id"] if r.get("success") else 0
    check("建 Agent", s == 200 and r.get("success"), r)
    s, r = call("POST", f"/api/agents/{aid}/executors", {"name": "E2E 执行器", "clientKind": "CLAUDE_CODE"}, token=token)
    eid = r["data"]["id"] if r.get("success") else 0
    check("建执行器(一次性 token)", r.get("success") and r["data"]["token"].startswith("exec_"), r.get("data", {}).get("token"))
    s, r = call("POST", f"/api/executors/{eid}/heartbeat", {"status": "ONLINE"}, token=token)
    check("心跳上线", r.get("success") and r["data"]["status"] == "ONLINE", r)
    s, r = call("POST", "/api/mcp/tokens", {"name": "E2E MCP"}, token=token)
    check("MCP 令牌", r.get("success") and r["data"]["token"].startswith("mcp_"), r)

    case("会话澄清域")
    s, r = call("PUT", f"/api/workitems/{wid}/clarification", {"contentMd": "E2E 澄清结论"}, token=token)
    check("澄清结论保存", r.get("success") and r["data"]["status"] == "DONE", r)
    s, r = call("POST", f"/api/agents/{aid}/conversations", {"title": "E2E 会话"}, token=token)
    cid = r["data"]["id"] if r.get("success") else 0
    check("建会话", r.get("success"), r)
    s, r = call("POST", f"/api/agents/{aid}/conversations/{cid}/turns", {"contentMd": "你好"}, token=token)
    check("发消息", r.get("success") and r["data"]["seq"] == 1, r)
    s, r = call("POST", f"/api/agents/{aid}/conversations/{cid}/usage", {"workitemId": wid, "tokensIn": 100, "tokensOut": 50, "model": "E2E"}, token=token)
    check("用上报", r.get("success"), r)

    case("调度域")
    s, r = call("GET", f"/api/workitems/{wid}/delivery-progress", token=token)
    check("交付进度(空可接受)", s == 200 and r.get("success"), r)
    s, r = call("GET", "/api/dispatches?pageNum=1&pageSize=5", token=token)
    check("派发列表", s == 200 and r.get("success"), r)
    s, r = call("GET", "/api/workitems/10053/dispatch-requests", token=token)
    check("需求单列表", s == 200 and r.get("success"), r)

    case("外部连接域")
    s, r = call("POST", "/api/integrations/zentao/bindings", {"projectId": f"E2E{suffix}", "name": "E2E 禅道"}, token=token)
    bid = r["data"]["id"] if r.get("success") else 0
    check("建外部绑定", r.get("success"), r)
    s, r = call("GET", "/api/integrations/zentao/bindings", token=token)
    check("绑定列表", r.get("success") and any(b["id"] == bid for b in r["data"]), r)
    s, r = call("PUT", "/api/notifications/prefs", {"items": [{"type": "WORKITEM_ASSIGNED", "inApp": True, "imChannel": False}]}, token=token)
    check("通知偏好保存", r.get("success"), r)

    case("记忆域")
    s, r = call("POST", "/api/memories", {"title": f"E2E 记忆 {suffix}", "contentMd": "端到端记忆"}, token=token)
    mid = r["data"]["id"] if r.get("success") else 0
    check("建记忆", r.get("success"), r)
    s, r = call("GET", "/api/memories?keyword=E2E", token=token)
    check("记忆检索", r.get("success") and r["data"]["total"] >= 1, r)
    s, r = call("PUT", f"/api/memories/{mid}", {"title": f"E2E 记忆 {suffix} v2", "contentMd": "更新"}, token=token)
    check("更新记忆", r.get("success"), r)

    case("资产域")
    s, r = call("POST", "/api/repos", {"name": f"E2E 仓库 {suffix}", "url": f"https://git.example.com/e2e{suffix}.git"}, token=token)
    rid = r["data"]["id"] if r.get("success") else 0
    check("建仓库", r.get("success"), r)
    s, r = call("PUT", f"/api/repos/{rid}/conclusion", {"contentMd": "E2E 结论"}, token=token)
    check("仓库结论", r.get("success") and r["data"]["conclusion"] == "E2E 结论", r)
    s, r = call("POST", "/api/environment-variables", {"key": f"E2E_KEY_{suffix}", "value": "s3cret", "secret": True}, token=token)
    check("环境变量(掩码)", r.get("success") and r["data"]["value"] == "******", r)

    case("平台运维域")
    s, r = call("GET", "/api/platform/branding/public")
    check("公开品牌", s == 200 and r.get("success") and r["data"]["platformName"], r)
    s, r = call("PUT", "/api/settings/AI", {"items": [{"key": "default_model", "valueJson": "E2E-Model"}]}, token=admin_token)
    check("设置保存", r.get("success"), r)
    s, r = call("GET", "/api/settings/AI", token=admin_token)
    check("设置读取", r.get("success") and any(x["key"] == "default_model" for x in r["data"]), r)
    s, r = call("POST", "/api/notifications", {"eventType": "E2E", "title": "E2E 通知", "bodyMd": "内容"}, token=token)
    check("发通知", r.get("success"), r)
    s, r = call("GET", "/api/notifications/unread-count", token=token)
    check("未读计数", r.get("success") and r["data"] >= 1, r)
    s, r = call("POST", "/api/notifications/read-all", token=token)
    s, r = call("GET", "/api/notifications/unread-count", token=token)
    check("全读清零", r.get("success") and r["data"] == 0, r)
    s, r = call("POST", "/api/scheduled-tasks", {"name": f"E2E 任务 {suffix}", "cronExpr": "0 0 4 * * *"}, token=admin_token)
    tid = r["data"]["id"] if r.get("success") else 0
    check("建定时任务", r.get("success"), r)
    s, r = call("POST", f"/api/scheduled-tasks/{tid}/enable", token=admin_token)
    check("启用任务", r.get("success") and r["data"]["enabled"], r)
    s, r = call("PUT", "/api/platform/im-channels/dingtalk", {"appKey": "e2e-app", "enabled": True}, token=admin_token)
    check("IM 渠道配置", r.get("success"), r)
    s, r = call("PUT", "/api/platform/im-channels/selection", {"provider": "DINGTALK"}, token=admin_token)
    check("IM 选中渠道", r.get("success") and r["data"]["provider"] == "DINGTALK", r)
    s, r = call("GET", "/api/audit-logs?pageNum=1&pageSize=5", token=admin_token)
    check("审计日志可读", s == 200 and r.get("success"), r)

    case("清理(删工单)")
    s, r = call("DELETE", f"/api/workitems/{wid}", token=token)
    check("软删除", r.get("success"), r)
    s, r = call("GET", "/api/workitems/recycled", token=token)
    check("回收站可见", r.get("success") and any(w["id"] == wid for w in r["data"]["list"]), r)
    s, r = call("POST", f"/api/workitems/{wid}/restore", token=token)
    check("恢复", r.get("success"), r)

    print(f"\n===== 结果: {PASS} 通过 / {FAIL} 失败 / 共 {PASS + FAIL} =====")
    if FAILED:
        print("失败用例: " + "; ".join(FAILED))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

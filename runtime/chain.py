"""跨角色接棒:经平台 REST API(架构许可通道,见 SdlcDriver javadoc / docs/10 v2)。

配置(runtime.env):
  RUNTIME_HANDOFF_AGENT_ID   下一角色 agentId(必填才启用接棒)
  AINATIVE_API_BASE_URL    默认 http://localhost:7001
  AINATIVE_WORKSPACE_ID    工作空间 ID
  AINATIVE_REFRESH_TOKEN   平台 refresh token(401 时自动换取新 access token)
  AINATIVE_ACCESS_TOKEN    当前 access token(可选,建议给 refresh token 即可)
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

_state = {"access": None, "refresh": None, "base": None, "ws": None}


def _put(path: str, body: dict, token: str | None = None) -> dict:
    req = urllib.request.Request(_state["base"] + path, method="PUT",
                                 data=json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def _post(path: str, body: dict, token: str | None = None) -> dict:
    req = urllib.request.Request(_state["base"] + path, method="POST",
                                 data=json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def _ensure_token(cfg) -> str:
    if _state["access"] is None:
        _state["access"] = cfg.extra_env.get("AINATIVE_ACCESS_TOKEN") or None
        _state["refresh"] = cfg.extra_env.get("AINATIVE_REFRESH_TOKEN")
        _state["base"] = cfg.extra_env.get("AINATIVE_API_BASE_URL", "http://localhost:7001")
    if _state["access"] is None and _state["refresh"]:
        d = _post("/api/auth/refresh", {"refreshToken": _state["refresh"],
                                        "workspaceId": int(cfg.extra_env.get("AINATIVE_WORKSPACE_ID", 0))})["data"]
        _state["access"] = d["accessToken"]
    if _state["access"] is None:
        raise RuntimeError("no API token: set AINATIVE_ACCESS_TOKEN/REFRESH_TOKEN")
    return _state["access"]


def _switch_workspace_token(cfg) -> str | None:
    """把平台级令牌换成工作区级令牌(DEF-028)。

    平台登录返回的 access token 是"平台级"的,直接调 /api/workitems/{id} 会被拒
    (HTTP 403,code 11001「非该工作空间成员」)。工作区级令牌需先调用
    POST /api/workspaces/{id}/switch 换取。
    """
    ws = cfg.extra_env.get("AINATIVE_WORKSPACE_ID")
    if not ws:
        return None
    base = _state["base"]
    current = _state["access"] or _state["refresh"]
    req = urllib.request.Request(f"{base}/api/workspaces/{int(ws)}/switch", method="POST",
                                 data=b"{}")
    req.add_header("Content-Type", "application/json")
    if current:
        req.add_header("Authorization", f"Bearer {current}")
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.loads(r.read()).get("data") or {}
    token = data.get("accessToken")
    if token:
        _state["access"] = token
    return token


def _with_refresh_retry(cfg, fn):
    try:
        return fn(_ensure_token(cfg))
    except urllib.error.HTTPError as e:
        if e.code == 403 and _state["ws"] is None:
            # 平台级令牌 → 先换工作区级令牌再试一次(DEF-028)
            ws = cfg.extra_env.get("AINATIVE_WORKSPACE_ID")
            if ws:
                _state["ws"] = ws
                try:
                    _switch_workspace_token(cfg)
                    return fn(_ensure_token(cfg))
                except Exception:
                    pass
            raise
        if e.code != 401 or not _state["refresh"]:
            raise
        _state["access"] = None
        return fn(_ensure_token(cfg))


def _get(path: str, token: str) -> dict:
    req = urllib.request.Request(_state["base"] + path, method="GET")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def finalize_to_done(cfg, workitem_id: int) -> None:
    """把工单流转到其状态模板的 DONE 节点(链路终点收敛,等价于页面上的完工流转)。"""
    def call(tok):
        w = _get(f"/api/workitems/{workitem_id}", tok)["data"]
        nodes = _get(f"/api/status-templates/{w['templateId']}/nodes", tok)["data"]
        done = next((n for n in nodes if str(n.get("category", "")).upper() == "DONE"), None)
        if done is None:
            raise RuntimeError("no DONE node in status template")
        if done["id"] == w.get("statusNodeId"):
            return {"success": True, "message": "already at DONE"}
        body = {"toNodeId": done["id"], "fromNodeId": w.get("statusNodeId"),
                "expectedVersion": w.get("version")}
        return _post(f"/api/workitems/{workitem_id}/transition", body, tok)
    resp = _with_refresh_retry(cfg, call)
    if not resp.get("success"):
        raise RuntimeError(f"finalize failed: {resp.get('message')}")


def handoff_to_human(cfg, workitem_id: int, user_id: int | None = None) -> None:
    """把工单交还真人(平台合法操作:assigneeType=HUMAN + 真人 userId)。"""
    uid = user_id if user_id is not None else cfg.extra_env.get("AINATIVE_HUMAN_USER_ID")
    if uid is None:
        raise RuntimeError("no human user id for handoff (roster/ AINATIVE_HUMAN_USER_ID)")
    def call(tok):
        return _put(f"/api/workitems/{workitem_id}/assignee",
                    {"assigneeType": "HUMAN", "assigneeRef": int(uid)}, tok)
    resp = _with_refresh_retry(cfg, call)
    if not resp.get("success"):
        raise RuntimeError(f"handoff_to_human failed: {resp.get('message')}")


def reassign_to_agent(cfg, workitem_id: int, agent_id: int) -> None:
    """把工单重新指派给下一角色的数字员工(平台合法操作,等价于项目经理在页面点指派)。"""
    cfg.extra_env.setdefault("AINATIVE_API_BASE_URL", "http://localhost:7001")
    _state["base"] = cfg.extra_env["AINATIVE_API_BASE_URL"]
    body = {"assigneeType": "AGENT", "assigneeRef": agent_id}
    def call(tok):
        return _put(f"/api/workitems/{workitem_id}/assignee", body, tok)
    resp = _with_refresh_retry(cfg, call)
    if not resp.get("success"):
        raise RuntimeError(f"reassign failed: {resp.get('message')}")


def agent_comment(cfg, dispatch_id: int, content_md: str) -> dict:
    """数字员工备注:走平台 daemon 备注通道(executor token 认证,免 Bearer)。

    平台侧为该备注触发 WorkitemCommentCreatedEvent → outbox COMMENT_CREATE →
    外部工单平台(禅道等)备注回写。这是"交付结论回到源头平台"的正式通道。
    """
    base = cfg.extra_env.get("AINATIVE_API_BASE_URL", "http://localhost:7001")
    _state["base"] = base
    path = f"/api/daemon/dispatches/{int(dispatch_id)}/comments?token={cfg.executor_token}"
    req = urllib.request.Request(base + path, method="POST",
                                 data=json.dumps({"contentMd": content_md}).encode())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())

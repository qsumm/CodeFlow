"""PowerContext 记忆外挂客户端(可选依赖,stdlib-only,失败永不阻断)。

阶段二集成(见 docs/evaluations/PowerContext-评估.md §7):
- dispatch 开始前:PreparedContext 有界召回注入(拉取「跨工单记忆」)
- dispatch 结束后:把本次交付的关键决策/约束写回 Memory
- Scope 绑定 key:CodeFlow 工作空间 ID(与平台多租户一一对应)

环境变量(全部可选;不配 = 功能关闭):
  POWERCONTEXT_BASE_URL     服务地址,如 http://127.0.0.1:8077
  POWERCONTEXT_TIMEOUT_SEC  HTTP 超时(默认 8s;Hook 失败绝不阻断正常工作)

所有失败(网络/超时/格式)静默降级为 None,调用方按「无记忆」继续。
"""
from __future__ import annotations

import os
import json
import urllib.error
import urllib.request
from dataclasses import dataclass

DEFAULT_TIMEOUT = 8.0
_USER_AGENT = "codeflow-runtime/1.0 (powercontext integration)"


@dataclass(frozen=True)
class PowerContextSettings:
    base_url: str
    timeout_sec: float

    @classmethod
    def from_env(cls, env: dict | None = None) -> "PowerContextSettings | None":
        env = dict(os.environ if env is None else env)
        base = (env.get("POWERCONTEXT_BASE_URL") or "").strip().rstrip("/")
        if not base:
            return None
        try:
            timeout = float(env.get("POWERCONTEXT_TIMEOUT_SEC") or DEFAULT_TIMEOUT)
        except ValueError:
            timeout = DEFAULT_TIMEOUT
        return cls(base_url=base, timeout_sec=max(1.0, timeout))


def _post_json(url: str, body: dict, timeout: float) -> dict | None:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "User-Agent": _USER_AGENT},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, json.JSONDecodeError):
        return None


def _scope_binding_key(workspace_id: int) -> dict:
    """Scope 绑定 key:integration 固定 codeflow,external_id 为工作空间 ID。"""
    return {"integration": "codeflow", "kind": "workspace",
            "external_id": f"ws-{workspace_id}"}


def resolve_scope(workspace_id: int, *,
                  settings: PowerContextSettings | None = None) -> str | None:
    """解析 CodeFlow 工作空间 → PowerContext Scope ID;未绑定/失败返回 None。"""
    st = settings or PowerContextSettings.from_env()
    if st is None:
        return None
    d = _post_json(f"{st.base_url}/v1/scope-bindings/resolve",
                   {"binding_keys": [_scope_binding_key(workspace_id)],
                    "allow_default": False}, st.timeout_sec)
    return (d or {}).get("scope_id") or None


def prepare_context(scope_id: str, query: str, *,
                    settings: PowerContextSettings | None = None,
                    max_bytes: int = 2500) -> str | None:
    """Dispatch 前召回。返回注入文本;失败/无召回/未配置返回 None。"""
    st = settings or PowerContextSettings.from_env()
    if st is None:
        return None
    payload = {"scope_id": scope_id, "query": (query or "")[:2000], "max_bytes": max_bytes}
    d = _post_json(f"{st.base_url}/v1/context/prepare", payload, st.timeout_sec)
    if not d or d.get("status") != "ready":
        return None
    content = d.get("content") or ""
    return content[:max_bytes] if content else None


def remember(workspace_id: int, kind: str, text: str, *,
             settings: PowerContextSettings | None = None,
             idempotency_key: str | None = None) -> bool:
    """Dispatch 后写回关键决策/约束。返回是否成功;失败/未配置返回 False。"""
    st = settings or PowerContextSettings.from_env()
    if st is None:
        return False
    # 先解析绑定 key → scope_id(resolve),再 remember(require scope_id)
    scope_id = resolve_scope(workspace_id, settings=st)
    if not scope_id:
        return False
    body = {"scope_id": scope_id, "kind": kind, "text": text[:8000]}
    headers_extra = {"X-Idempotency-Key": idempotency_key} if idempotency_key else {}
    req = urllib.request.Request(
        f"{st.base_url}/v1/memory/remember",
        data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "User-Agent": _USER_AGENT, **headers_extra},
    )
    try:
        with urllib.request.urlopen(req, timeout=st.timeout_sec) as resp:
            return resp.status == 200
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
        return False


def bind_workspace(workspace_id: int, scope_id: str, *,
                   settings: PowerContextSettings | None = None) -> bool:
    """把 CodeFlow 工作空间绑定到 PowerContext Scope(一次性运维动作,也可用 curl 完成)。"""
    st = settings or PowerContextSettings.from_env()
    if st is None:
        return False
    req = urllib.request.Request(
        f"{st.base_url}/v1/scope-bindings", method="PUT",
        data=json.dumps({"scope_id": scope_id,
                         "key": _scope_binding_key(workspace_id)}).encode("utf-8"),
        headers={"Content-Type": "application/json", "User-Agent": _USER_AGENT},
    )
    try:
        with urllib.request.urlopen(req, timeout=st.timeout_sec) as resp:
            return resp.status == 200
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
        return False


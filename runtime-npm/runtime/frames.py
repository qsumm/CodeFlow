"""Protocol frames (up: executor -> server). Field names follow docs/scheduler-executor-protocol.md verbatim."""
from __future__ import annotations

from typing import Any

# CONVERSATION_DELIVERY_ACK_V1:声明后平台会话投递带 dispatchAttempt,ACK 必须回显(见 conversation.py)
PROTOCOL_FEATURES = ["OPEN_RUNTIME_V1", "dispatch_inventory_v1", "CONVERSATION_DELIVERY_ACK_V1"]


class FrameError(Exception):
    pass


def task_ack(dispatch_id: int) -> dict:
    return {"type": "TASK_ACK", "dispatchId": dispatch_id}


def task_progress(dispatch_id: int, result_summary: str, *, step_order: int | None = None,
                  step_id: str | None = None, step_name: str | None = None,
                  message: str | None = None, error: str | None = None) -> dict:
    f: dict[str, Any] = {"type": "TASK_PROGRESS", "dispatchId": dispatch_id, "resultSummary": result_summary}
    if step_order is not None: f["stepOrder"] = step_order
    if step_id is not None: f["stepId"] = step_id
    if step_name is not None: f["stepName"] = step_name
    if message is not None: f["message"] = message
    if error is not None: f["error"] = error
    return f


def task_result(dispatch_id: int, success: bool, result_summary: str, error: str = "") -> dict:
    return {"type": "TASK_RESULT", "dispatchId": dispatch_id, "success": success,
            "resultSummary": result_summary, "error": error or ""}


def task_handoff(dispatch_id: int, workitem_id: int, to: str, to_type: str = "AGENT",
                 next_role: str = "", reason: str = "") -> dict:
    return {"type": "TASK_HANDOFF", "dispatchId": dispatch_id, "workitemId": workitem_id,
            "to": to, "toType": to_type, "nextRole": next_role, "reason": reason}


def heartbeat(owned=None, running=None, capacity=1, client_started_at=None) -> dict:
    """dispatch_inventory_v1 心跳:owned/running 必须为数组且 owned ⊇ running(§reconciler 契约)。

    client_started_at:守护进程启动时刻(UTC ISO-8601)。进程级字段——重连不重置,
    只有守护进程重启才会产生新值;平台用它渲染「最近启动」。旧服务端未知字段会忽略。
    """
    owned = sorted(int(x) for x in (owned or []))
    running = sorted(int(x) for x in (running or []))
    frame = {"type": "HEARTBEAT", "protocolFeatures": list(PROTOCOL_FEATURES),
             "dispatchInventoryReady": True, "maxConcurrentDispatches": max(1, int(capacity)),
             "ownedDispatchIds": owned, "runningDispatchIds": running,
             "runningConversationTurnIds": []}
    if client_started_at:
        frame["clientStartedAt"] = client_started_at
    return frame


def decode_dispatch(payload: dict) -> dict:
    """Validate a downstream TASK_DISPATCH frame; returns normalized dict."""
    if payload.get("type") != "TASK_DISPATCH":
        raise FrameError("not a TASK_DISPATCH frame")
    for key in ("dispatchId", "executorId", "tenantId", "workitemId", "attempt", "downloadUrl"):
        if key not in payload or payload[key] in (None, ""):
            raise FrameError(f"TASK_DISPATCH missing field: {key}")
    return payload


def decode_frame(raw: str) -> dict:
    import json
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise FrameError(f"invalid JSON frame: {e}") from e
    if not isinstance(data, dict) or "type" not in data:
        raise FrameError("frame missing type")
    return data


def decode_conversation_turn(frame: dict) -> dict:
    """校验并提取 CONVERSATION_TURN 关键字段(缺失关键字段直接拒绝,不静默)。"""
    if frame.get("type") != "CONVERSATION_TURN":
        raise FrameError("not a CONVERSATION_TURN frame")
    missing = [k for k in ("conversationId", "turnId", "content") if frame.get(k) in (None, "")]
    if missing:
        raise FrameError(f"CONVERSATION_TURN missing {','.join(missing)}")
    return {
        "conversation_id": int(frame["conversationId"]),
        "turn_id": int(frame["turnId"]),
        "agent_id": int(frame["agentId"]) if frame.get("agentId") else None,
        "dispatch_attempt": int(frame["dispatchAttempt"]) if frame.get("dispatchAttempt") else 0,
        "content": str(frame["content"]),
        "cli_session_ref": frame.get("cliSessionRef") or None,
        "system_prompt": frame.get("systemPrompt") or None,
        "capability_url": frame.get("capabilityDownloadUrl") or None,
        "capability_sha256": frame.get("capabilitySha256") or None,
        "mcp_token": frame.get("mcpToken") or None,
    }

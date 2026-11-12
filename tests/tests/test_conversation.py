"""CONVERSATION_TURN 协议(runtime 侧)单元测试。"""
from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path

import pytest

from runtime import conversation
from runtime.frames import FrameError, decode_conversation_turn


def test_decode_conversation_turn_extracts_fields():
    frame = {"type": "CONVERSATION_TURN", "conversationId": 7, "turnId": 9, "agentId": 10001,
             "dispatchAttempt": 2, "content": "问题", "cliSessionRef": "sess-1",
             "systemPrompt": "sp", "capabilityDownloadUrl": "http://x/y.zip",
             "capabilitySha256": "abc", "mcpToken": "tok"}
    t = decode_conversation_turn(frame)
    assert t["conversation_id"] == 7 and t["turn_id"] == 9
    assert t["dispatch_attempt"] == 2 and t["content"] == "问题"
    assert t["cli_session_ref"] == "sess-1" and t["mcp_token"] == "tok"


def test_decode_conversation_turn_rejects_missing():
    with pytest.raises(FrameError):
        decode_conversation_turn({"type": "CONVERSATION_TURN", "conversationId": 7})
    with pytest.raises(FrameError):
        decode_conversation_turn({"type": "TASK_DISPATCH"})


def test_download_capability_verifies_sha256(tmp_path: Path):
    payload = b"capability-bytes"
    zip_path = tmp_path / "cap.zip"
    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr("skills.json", json.dumps({"mcpServers": []}))
    expected = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    out = conversation.download_capability(zip_path.as_uri(), expected, tmp_path / "out")
    assert out.read_bytes() == zip_path.read_bytes()
    with pytest.raises(ValueError):
        conversation.download_capability(zip_path.as_uri(), "0" * 64, tmp_path / "out2")


def test_ainative_mcp_server_injects_bearer_token(tmp_path: Path):
    capsule = tmp_path / "capsule"
    capsule.mkdir()
    (capsule / "skills.json").write_text(json.dumps({
        "mcpServers": [{"name": "ainative", "transport": "http",
                        "url": "http://localhost:7001/api/mcp", "required": True}]}))
    server = conversation.ainative_mcp_server(capsule, "tok-1")
    assert server["url"] == "http://localhost:7001/api/mcp"
    assert server["headers"]["Authorization"] == "Bearer tok-1"


def test_write_mcp_config_creates_mcp_json(tmp_path: Path):
    conversation.write_mcp_config(tmp_path, {"type": "http", "url": "u"})
    doc = json.loads((tmp_path / ".mcp.json").read_text())
    assert doc["mcpServers"]["ainative"]["url"] == "u"
    conversation.write_mcp_config(tmp_path, None)
    assert (tmp_path / ".mcp.json").exists()


def test_parse_result_reads_claude_json():
    raw = "\n".join([
        json.dumps({"type": "system", "subtype": "init"}),
        json.dumps({"type": "result", "subtype": "success", "is_error": False,
                    "result": "答案", "session_id": "s-9",
                    "usage": {"input_tokens": 10, "output_tokens": 3,
                              "cache_read_input_tokens": 2, "cache_creation_input_tokens": 1},
                    "modelUsage": {"mimo-v2.6-flash": {}}}),
    ])
    r = conversation.parse_result(raw)
    assert r["text"] == "答案" and r["session_id"] == "s-9" and r["is_error"] is False
    assert r["usage"]["inputTokens"] == 10 and r["model"] == "mimo-v2.6-flash"


def test_parse_result_handles_empty():
    r = conversation.parse_result("")
    assert r["is_error"] is True and r["text"] == ""


def test_turn_ack_echoes_dispatch_attempt_when_present():
    ack = conversation.turn_ack(7, 9, 2, "SUCCESS", reply_markdown="md", session_id="s")
    assert ack["dispatchAttempt"] == 2 and ack["status"] == "SUCCESS"
    assert ack["replyMarkdown"] == "md" and ack["sessionId"] == "s"
    ack0 = conversation.turn_ack(7, 9, 0, "FAILED", error="x")
    assert "dispatchAttempt" not in ack0 and ack0["error"] == "x"


def test_turn_ack_usage_shape():
    ack = conversation.turn_ack(7, 9, None, "SUCCESS",
                                usage={"inputTokens": 1, "outputTokens": 2}, model="m")
    assert ack["usage"]["provider"] == "claude" and ack["usage"]["model"] == "m"

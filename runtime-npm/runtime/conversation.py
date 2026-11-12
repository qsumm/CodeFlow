"""Clarification-conversation turn execution (CONVERSATION_TURN frames).

协议(平台侧 WsConversationTransport/InboundFrameRouter):
  平台 → runtime: CONVERSATION_TURN{conversationId,turnId,agentId,dispatchAttempt?,
             content,requestId,cliSessionRef?,systemPrompt?,capabilityDownloadUrl,
             capabilitySha256,capabilityHash,mcpToken}
  runtime → 平台: CONVERSATION_TURN_ACK{conversationId,turnId,dispatchAttempt?,
             status:SUCCESS|FAILED,error?,replyMarkdown?,sessionId?,usage?}

心跳声明 CONVERSATION_DELIVERY_ACK_V1 后,ACK 必须回显 dispatchAttempt,
平台据此做陈旧投递判定与投递确认。

claude 执行形态:每会话一个工作区;每回合下载 capability 包(sha256 校验)解包,
--resume 上一回合 sessionId 保持上下文;MCP(ainative) 以 Bearer mcpToken 注入,
数字员工由此读取工单与平台数据。
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path

from .logging_util import log
from .providers.claude_code import write_settings_file

CAPABILITY_TIMEOUT_SEC = 120
TURN_TIMEOUT_SEC = 30 * 60  # 平台 ~11min 会重投;dispatchAttempt 去重保证幂等


def download_capability(url: str, expected_sha256: str, dest_dir: Path) -> Path:
    """下载 capability 包并做 sha256 完整性校验(与平台 TaskPackager 计算口径一致)。"""
    dest_dir.mkdir(parents=True, exist_ok=True)
    zip_path = dest_dir / "capability.zip"
    tmp = zip_path.with_suffix(".part")
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=CAPABILITY_TIMEOUT_SEC) as resp, open(tmp, "wb") as f:
        while True:
            chunk = resp.read(256 * 1024)
            if not chunk:
                break
            f.write(chunk)
    digest = hashlib.sha256(tmp.read_bytes()).hexdigest()
    if expected_sha256 and digest.lower() != expected_sha256.lower():
        tmp.unlink(missing_ok=True)
        raise ValueError(f"capability sha256 mismatch: got {digest}, want {expected_sha256}")
    tmp.replace(zip_path)
    return zip_path


def extract_capability(zip_path: Path, dest_dir: Path) -> None:
    """解包 capability 包(覆盖式;包内为 skills.json/mcp 配置/仓库上下文等)。"""
    dest_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(dest_dir)


def ainative_mcp_server(capsule_dir: Path, mcp_token: str) -> dict | None:
    """从 capability 的 skills.json 取内置 ainative MCP 描述,注入 Bearer mcpToken。

    平台 authType=ainative-dispatch 的鉴权即 MCP 端点的 Authorization 头。
    """
    skills_path = capsule_dir / "skills.json"
    if not skills_path.exists():
        return None
    try:
        doc = json.loads(skills_path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as e:
        log.warn("conversation.skills_unreadable", error=str(e))
        return None
    for server in doc.get("mcpServers") or []:
        name = str(server.get("name", "")).lower()
        if name != "ainative":
            continue
        url = server.get("url")
        if not url:
            return None
        return {"type": "http" if server.get("transport", "http") == "http" else "sse",
                "url": url, "headers": {"Authorization": f"Bearer {mcp_token}"}}
    return None


def write_mcp_config(workdir: Path, server: dict | None) -> None:
    if not server:
        return
    (workdir / ".mcp.json").write_text(
        json.dumps({"mcpServers": {"ainative": server}}, ensure_ascii=False), encoding="utf-8")


def write_conversation_settings(workdir: Path, env: dict, mcp_token: str | None) -> str | None:
    """claude --settings:钉模型端点环境变量 + 预授权 ainative MCP 工具。

    -p 模式无权限弹窗,部分 CLI 版本对 --allowedTools 的服务器级简写不生效,
    settings 的 permissions.allow(服务器名 + 工具通配两种形式)是权威路径。
    """
    picked = {k: v for k, v in env.items()
              if k.startswith("ANTHROPIC_") or k == "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"}
    settings = {"env": picked}
    if mcp_token:
        settings["permissions"] = {"allow": ["mcp__ainative", "mcp__ainative__*"]}
    path = workdir / "claude-settings.json"
    path.write_text(json.dumps(settings, ensure_ascii=False), encoding="utf-8")
    return str(path)


def build_command(cli_binary: str, content: str, *, session_ref: str | None,
                  system_prompt: str | None, settings_file: str | None,
                  max_turns: int, allowed_tools: str | None = None) -> list[str]:
    argv = [cli_binary, "-p", content, "--output-format", "json"]
    if session_ref:
        argv += ["--resume", session_ref]
    if max_turns:
        argv += ["--max-turns", str(max_turns)]
    if settings_file:
        argv += ["--settings", settings_file]
    if system_prompt:
        argv += ["--append-system-prompt", system_prompt]
    if allowed_tools:
        argv += ["--allowedTools", allowed_tools]
    return argv


def parse_result(raw: str) -> dict:
    """解析 claude --output-format json 单行结果 → {text, session_id, is_error, usage}。"""
    out = {"text": "", "session_id": None, "is_error": True, "error": "empty output",
           "usage": None, "model": None}
    for line in (raw or "").splitlines():
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            data = json.loads(line)
        except ValueError:
            continue
        if data.get("type") != "result":
            continue
        out["text"] = data.get("result") or ""
        out["session_id"] = data.get("session_id")
        out["is_error"] = bool(data.get("is_error"))
        out["error"] = None if not out["is_error"] else (out["text"] or "claude reported error")
        usage = data.get("usage") or {}
        out["usage"] = {
            "inputTokens": int(usage.get("input_tokens") or 0),
            "outputTokens": int(usage.get("output_tokens") or 0),
            "cacheReadTokens": int(usage.get("cache_read_input_tokens") or 0),
            "cacheWriteTokens": int(usage.get("cache_creation_input_tokens") or 0),
        }
        model_usage = data.get("modelUsage") or {}
        if isinstance(model_usage, dict) and model_usage:
            out["model"] = next(iter(model_usage))
        break
    return out


def run_claude_turn(cli_binary: str, workdir: Path, content: str, *, session_ref: str | None,
                    system_prompt: str | None, settings_path: str | None, max_turns: int,
                    env: dict, timeout_sec: int = TURN_TIMEOUT_SEC,
                    allowed_tools: str | None = None) -> dict:
    """同步执行一回合对话;返回 parse_result 形态,附 exit_code/stderr 供失败诊断。"""
    argv = build_command(cli_binary, content, session_ref=session_ref,
                         system_prompt=system_prompt, settings_file=settings_path,
                         max_turns=max_turns, allowed_tools=allowed_tools)
    started = time.monotonic()
    try:
        proc = subprocess.run(argv, cwd=str(workdir), env=env, capture_output=True,
                              text=True, timeout=timeout_sec)
        result = parse_result(proc.stdout or "")
        result["exit_code"] = proc.returncode
        if proc.returncode != 0 and not result["text"]:
            result["is_error"] = True
            result["error"] = (proc.stderr or "")[-800:] or f"claude exited {proc.returncode}"
    except subprocess.TimeoutExpired:
        result = {"text": "", "session_id": None, "is_error": True,
                  "error": f"conversation turn timeout after {timeout_sec}s",
                  "usage": None, "model": None, "exit_code": None}
    result["duration_ms"] = int((time.monotonic() - started) * 1000)
    return result


def turn_ack(conversation_id: int, turn_id: int, dispatch_attempt: int | None,
             status: str, *, reply_markdown: str = "", error: str = "",
             session_id: str | None = None, usage: dict | None = None,
             model: str | None = None) -> dict:
    frame = {"type": "CONVERSATION_TURN_ACK", "conversationId": int(conversation_id),
             "turnId": int(turn_id), "status": status,
             "replyMarkdown": reply_markdown or "", "error": error or ""}
    if dispatch_attempt:
        frame["dispatchAttempt"] = int(dispatch_attempt)
    if session_id:
        frame["sessionId"] = session_id
    if usage:
        frame["usage"] = {"provider": "claude", "model": model or "", **usage}
    return frame


def register_mcp_server(cli_binary: str, workdir: Path, env: dict, url: str, mcp_token: str) -> bool:
    """以 local scope 注册 ainative MCP(用户添加即受信,headless 免批准)。

    token 每回合轮换(turn-bound),-f 覆盖旧注册。失败仅告警:工具不可用时数字员工
    仍可凭 capability 包内上下文作答。
    """
    # 该 CLI 版本无 force 旗标:先 remove(不存在则报错,忽略)再 add 实现每回合轮换覆盖
    subprocess.run([cli_binary, "mcp", "remove", "--scope", "local", "ainative"],
                   cwd=str(workdir), env=env, capture_output=True, text=True, timeout=60)
    argv = [cli_binary, "mcp", "add", "--transport", "http", "--scope", "local",
            "ainative", url, "--header", f"Authorization: Bearer {mcp_token}"]
    try:
        proc = subprocess.run(argv, cwd=str(workdir), env=env, capture_output=True,
                              text=True, timeout=60)
        if proc.returncode != 0:
            log.warn("conversation.mcp_add_failed", code=proc.returncode,
                     stderr=(proc.stderr or "")[-200:])
            return False
        return True
    except (OSError, subprocess.TimeoutExpired) as e:
        log.warn("conversation.mcp_add_error", error=str(e))
        return False

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
import os
import signal
import subprocess
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

from .logging_util import log
from .providers.claude_code import write_settings_file

CAPABILITY_TIMEOUT_SEC = 120

# 数字员工在自己沙箱工作区里完成交付所需的基础权限:读写文件、查文件、跑命令。
# 少了这些,非交互模式下 CLI 会直接拒绝写入("Writes are being blocked by the
# environment's permission layer"),澄清与交付都只能"嘴上说说"。
WORKSPACE_WRITE_TOOLS = ["Read", "Write", "Edit", "Glob", "Grep", "Bash"]
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
    # 权限:非交互(-p)模式下未被 allow 的操作会被直接拒绝,不是"问一下"。
    # 只放行 MCP 的话,数字员工连文件都写不了 —— 澄清时"文件落盘被拒"、
    # 交付时"代码写不出来"都是这条导致的。这里把写文件与命令一并放行:
    # 运行节点在自己的沙箱工作区里执行,放开的是它自己的工作区。
    allow = list(WORKSPACE_WRITE_TOOLS)
    if mcp_token:
        allow += ["mcp__ainative", "mcp__ainative__*"]
    settings["permissions"] = {"allow": allow}
    path = workdir / "claude-settings.json"
    path.write_text(json.dumps(settings, ensure_ascii=False), encoding="utf-8")
    return str(path)


def _popen_group(argv: list[str], workdir: Path, env: dict):
    """以独立进程组启动 CLI。

    超时必须整组杀(同任务派发路径的 DEF-009):只杀父进程的话,它拉起的子进程
    (MCP 服务、模型管理器等)仍握着 stdout 管道,逐行读会一直阻塞到超时之后,
    表现为「对话卡住不动」——2026-11-23 用 codex 实测复现。
    stdin 显式关闭:codex exec 发现 stdin 不是终端时会一直等「追加输入」。
    """
    return subprocess.Popen(argv, cwd=str(workdir), env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            start_new_session=True)


def _kill_group(proc: subprocess.Popen) -> None:
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        proc.kill()


def _run_group(argv: list[str], workdir: Path, env: dict, timeout_sec: int) -> tuple[int, str, str]:
    """阻塞式跑完 CLI,返回 (退出码, stdout, stderr);超时整组杀后抛 TimeoutExpired。"""
    proc = _popen_group(argv, workdir, env)
    try:
        out, err = proc.communicate(timeout=timeout_sec)
    except subprocess.TimeoutExpired:
        _kill_group(proc)
        try:
            proc.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            pass
        raise
    return proc.returncode, out or "", err or ""


def build_command(cli_binary: str, content: str, *, session_ref: str | None,
                  system_prompt: str | None, settings_file: str | None,
                  max_turns: int, allowed_tools: str | None = None,
                  stream: bool = False) -> list[str]:
    if stream:
        # stream-json 逐行输出:文字/思考增量(content_block_delta)、工具调用、最终 result
        # 各占一行,运行器边读边上报,浏览器端才能边生成边显示。
        argv = [cli_binary, "-p", content, "--output-format", "stream-json", "--verbose",
                "--include-partial-messages"]
    else:
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
    # acceptEdits:文件写入自动放行(非交互模式没有弹窗可点,不放开就是直接拒绝)
    argv += ["--permission-mode", "acceptEdits"]
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
        if data.get("subtype") and data.get("subtype") != "success":
            out["subtype"] = data.get("subtype")
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


def parse_stream_line(line: str, state: dict) -> list[tuple[str, dict]]:
    """stream-json 的一行 → 0..n 个 (eventType, payload),与平台 CONVERSATION_TURN_EVENT 对齐。

    事件类型(前端时间线 timeline.ts 同名节点):
      text        文字增量      payload {type, content}
      thinking    思考增量      payload {type, content}
      tool_use    工具调用      payload {type, callId, tool, status, input}
      tool_result 工具结果      payload {type, callId, status, output}

    state 跨行累积「当前这条助手消息已通过增量发出多少字」:上游不支持逐字流式时
    CLI 不会产 text_delta,只在 assistant 行里给整段文字 —— 此时若一个字都还没发过,
    就把整段补发出去(兜底:最差也是一条消息一截,不会丢内容)。
    """
    line = (line or "").strip()
    if not line.startswith("{"):
        return []
    try:
        data = json.loads(line)
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    kind = data.get("type")
    out: list[tuple[str, dict]] = []
    if kind == "stream_event":
        event = data.get("event") or {}
        if event.get("type") != "content_block_delta":
            return []
        delta = event.get("delta") or {}
        if delta.get("type") == "text_delta" and delta.get("text"):
            # 增量先于 assistant 汇总行到达、且不带消息 id:先记入 pending,
            # 等汇总行到达时认领(见 assistant 分支),否则汇总行会把同一段文字再发一遍
            state["pending"] = int(state.get("pending", 0)) + len(delta["text"])
            out.append(("text", {"type": "text", "content": delta["text"]}))
        elif delta.get("type") == "thinking_delta" and delta.get("thinking"):
            out.append(("thinking", {"type": "thinking", "content": delta["thinking"]}))
        return out
    if kind == "assistant":
        message = data.get("message") or {}
        message_id = message.get("id")
        if message_id != state.get("message_id"):
            state["message_id"] = message_id
            # 本条消息已通过增量发出的正文 = 之前累积的待归属增量
            state["emitted"] = int(state.get("pending", 0))
            state["pending"] = 0
        for block in message.get("content") or []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "text" and block.get("text"):
                if int(state.get("emitted", 0)) == 0:
                    state["emitted"] = len(block["text"])
                    out.append(("text", {"type": "text", "content": block["text"]}))
            elif block.get("type") == "tool_use":
                try:
                    tool_input = json.dumps(block.get("input") or {}, ensure_ascii=False)
                except (TypeError, ValueError):
                    tool_input = str(block.get("input"))
                out.append(("tool_use", {"type": "tool_use", "callId": block.get("id"),
                                         "tool": block.get("name"), "status": "started",
                                         "input": tool_input}))
        return out
    if kind == "user":
        message = data.get("message") or {}
        content = message.get("content")
        blocks = content if isinstance(content, list) else []
        for block in blocks:
            if not isinstance(block, dict) or block.get("type") != "tool_result":
                continue
            body = block.get("content")
            if isinstance(body, list):
                body = "".join(str(c.get("text", "")) for c in body if isinstance(c, dict))
            out.append(("tool_result", {"type": "tool_result", "callId": block.get("tool_use_id"),
                                        "status": "error" if block.get("is_error") else "completed",
                                        "output": str(body or "")[:4000]}))
        return out
    return []


def run_claude_turn(cli_binary: str, workdir: Path, content: str, *, session_ref: str | None,
                    system_prompt: str | None, settings_path: str | None, max_turns: int,
                    env: dict, timeout_sec: int = TURN_TIMEOUT_SEC,
                    allowed_tools: str | None = None,
                    on_event=None) -> dict:
    """同步执行一回合对话;返回 parse_result 形态,附 exit_code/stderr 供失败诊断。

    传入 on_event(eventType, payload) 时切到流式模式:边读边把文字/思考/工具事件回调出去,
    最终 result 行仍按原口径解析,返回值与非流式完全一致。
    """
    stream = on_event is not None
    started_wall = time.time()
    argv = build_command(cli_binary, content, session_ref=session_ref,
                         system_prompt=system_prompt, settings_file=settings_path,
                         max_turns=max_turns, allowed_tools=allowed_tools, stream=stream)
    started = time.monotonic()
    if not stream:
        try:
            code, stdout, stderr = _run_group(argv, workdir, env, timeout_sec)
            result = parse_result(stdout)
            result["exit_code"] = code
            if code != 0 and not result["text"]:
                result["is_error"] = True
                result["error"] = _failure_detail("claude", code, stderr, None)
        except subprocess.TimeoutExpired:
            result = {"text": "", "session_id": None, "is_error": True,
                      "error": f"conversation turn timeout after {timeout_sec}s",
                      "usage": None, "model": None, "exit_code": None}
        result["duration_ms"] = int((time.monotonic() - started) * 1000)
        return _diagnose_claude_failure(result, workdir, max_turns, started_wall)

    lines: list[str] = []
    stderr_tail: list[str] = []
    timed_out = threading.Event()

    def _on_timeout() -> None:
        timed_out.set()
        _kill_group(proc)

    def _drain_stderr() -> None:
        try:
            stderr_tail.append(proc.stderr.read()[-800:] or "")
        except Exception:  # noqa: BLE001 - stderr 只用于诊断,读不到不影响回合
            pass

    proc = _popen_group(argv, workdir, env)
    watchdog = threading.Timer(timeout_sec, _on_timeout)
    watchdog.daemon = True
    watchdog.start()
    threading.Thread(target=_drain_stderr, daemon=True).start()
    state: dict = {}
    line_types: dict[str, int] = {}
    event_count = 0
    try:
        for line in proc.stdout:
            lines.append(line)
            stripped = line.strip()
            if stripped.startswith("{"):
                try:
                    lt = json.loads(stripped).get("type")
                    if isinstance(lt, str):
                        line_types[lt] = line_types.get(lt, 0) + 1
                except ValueError:
                    pass
            events = parse_stream_line(line, state)
            event_count += len(events)
            for event_type, payload in events:
                # 上报失败不能拖垮回合:事件只是体验增强,权威内容在最终 ACK 里
                try:
                    on_event(event_type, payload)
                except Exception as e:  # noqa: BLE001
                    log.warn("conversation.event_emit_failed", error=repr(e))
        proc.wait(timeout=60)
    finally:
        watchdog.cancel()
    result = parse_result("".join(lines))
    result["exit_code"] = proc.returncode
    # 流式统计:出问题时能立刻看出是「上游没吐增量」还是「我们没解析出来」
    result["stream_events"] = event_count
    result["stream_line_types"] = line_types
    if timed_out.is_set():
        result = {"text": "", "session_id": None, "is_error": True,
                  "error": f"conversation turn timeout after {timeout_sec}s",
                  "usage": None, "model": None, "exit_code": proc.returncode}
    elif proc.returncode != 0 and not result["text"]:
        result["is_error"] = True
        result["error"] = _failure_detail("claude", proc.returncode, "".join(stderr_tail), None)
    result["duration_ms"] = int((time.monotonic() - started) * 1000)
    return _diagnose_claude_failure(result, workdir, max_turns, started_wall)


def turn_ack(conversation_id: int, turn_id: int, dispatch_attempt: int | None,
             status: str, *, reply_markdown: str = "", error: str = "",
             session_id: str | None = None, usage: dict | None = None,
             model: str | None = None, provider: str = "claude") -> dict:
    frame = {"type": "CONVERSATION_TURN_ACK", "conversationId": int(conversation_id),
             "turnId": int(turn_id), "status": status,
             "replyMarkdown": reply_markdown or "", "error": error or ""}
    if dispatch_attempt:
        frame["dispatchAttempt"] = int(dispatch_attempt)
    if session_id:
        frame["sessionId"] = session_id
    if usage:
        # 服务端按 input_tokens / output_tokens 取值(ConversationTurnService.handleAck);
        # 此前只带驼峰键,服务端取不到,会话用量从未入库。
        frame["usage"] = {"provider": provider, "model": model or "", **usage,
                          "input_tokens": int(usage.get("inputTokens") or 0),
                          "output_tokens": int(usage.get("outputTokens") or 0)}
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


# ───────────────────────── 稳定性:失败诊断与步数上限收尾 ─────────────────────────

MAX_TURNS_MESSAGE = ("AI 已达到单回合步数上限({max_turns} 步),本轮未能给出最终回复。"
                     "可调大运行节点的 AINATIVE_AGENT_MAX_TURNS,或把任务拆小后重发。")
WRAPUP_MAX_TURNS = 8
WRAPUP_PROMPT = ("你已达到本轮的步数上限。请不要再调用任何工具,基于已经拿到的结果立即给出最终回复:"
                 "先说明已完成的内容,再说明未完成的部分与原因,最后给出下一步建议。")


def _diagnose_claude_failure(result: dict, workdir: Path, max_turns: int, started_wall: float) -> dict:
    """CLI 没给出最终回复时,从会话转录里读出真实死因。

    背景:撞上 --max-turns 时 CLI 以 exit 1 退出,单结果模式下既不输出 result 行、
    stderr 也是空的,此前被笼统报成「claude exited 1」,用户无从判断该怎么办。
    只看本轮开始之后改动过的转录,避免把上一轮的残留标记误判成本轮死因。
    """
    if not result.get("is_error") or result.get("text") or result.get("exit_code") is None:
        if result.get("subtype") != "error_max_turns":
            return result
    facts = _transcript_facts(workdir, since=started_wall)
    if facts["max_turns"] is None and result.get("subtype") != "error_max_turns":
        return result
    result["max_turns_hit"] = True
    result["partial_text"] = facts["last_text"]
    result["error"] = MAX_TURNS_MESSAGE.format(max_turns=facts["max_turns"] or max_turns)
    if not result.get("session_id"):
        result["session_id"] = facts["session_id"]
    return result


def _transcript_facts(workdir: Path, since: float | None = None) -> dict:
    """读 HOME/.claude/projects 下本轮的会话转录:步数上限标记、会话 id、最后一段正文。"""
    out = {"max_turns": None, "session_id": None, "last_text": ""}
    root = Path(workdir) / ".claude" / "projects"
    if not root.is_dir():
        return out
    files = [f for f in root.rglob("*.jsonl") if since is None or f.stat().st_mtime >= since - 1]
    files.sort(key=lambda f: f.stat().st_mtime, reverse=True)
    for path in files[:3]:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        # 上限标记与结尾文字都在转录尾部,只看最后一段足够
        for line in reversed(lines[-120:]):
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if not isinstance(row, dict):
                continue
            if out["session_id"] is None and row.get("sessionId"):
                out["session_id"] = row["sessionId"]
            attachment = row.get("attachment") or {}
            if attachment.get("type") == "max_turns_reached" and out["max_turns"] is None:
                out["max_turns"] = attachment.get("maxTurns")
            if not out["last_text"] and row.get("type") == "assistant":
                blocks = (row.get("message") or {}).get("content") or []
                texts = [b.get("text") for b in blocks
                         if isinstance(b, dict) and b.get("type") == "text" and b.get("text")]
                if texts:
                    out["last_text"] = str(texts[-1])
        if out["max_turns"] is not None:
            break
    return out


# ───────────────────────── 多执行器:对话执行按执行器分流 ─────────────────────────

CONVERSATION_KINDS = ("claude", "codex", "cursor", "gemini")
# codex 的 MCP 令牌不写进配置,通过环境变量名间接引用(见 codex 文档 mcp_servers.<name>.bearer_token_env_var)
CODEX_MCP_TOKEN_ENV = "AINATIVE_MCP_BEARER"


def conversation_kind(provider: str | None) -> str:
    """运行节点声明的执行器种类;未声明或不在名单内时按 claude 处理(历史行为)。"""
    kind = (provider or "claude").strip().lower()
    return kind if kind in CONVERSATION_KINDS else "claude"


def write_conversation_mcp(kind: str, workdir: Path, url: str, token: str) -> None:
    """给各执行器挂上平台 MCP。claude 走 .mcp.json(由调用方另行注册);codex 走命令行
    配置覆盖项(见 _codex_argv),不落文件;cursor / gemini 读工作目录下的项目级配置。"""
    headers = {"Authorization": f"Bearer {token}"}
    if kind == "cursor":
        config = workdir / ".cursor" / "mcp.json"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(json.dumps({"mcpServers": {"ainative": {"url": url, "headers": headers}}}),
                          encoding="utf-8")
    elif kind == "gemini":
        config = workdir / ".gemini" / "settings.json"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(json.dumps({"mcpServers": {"ainative": {"httpUrl": url, "headers": headers}}}),
                          encoding="utf-8")


def _prepend_system_prompt(system_prompt: str | None, content: str) -> str:
    """没有 --append-system-prompt 的 CLI:人设前置到提示词正文。"""
    prompt = (system_prompt or "").strip()
    return f"{prompt}\n\n---\n\n{content}" if prompt else content


def _spawn(argv: list[str], workdir: Path, env: dict, timeout_sec: int, on_event, parse_line,
           state: dict | None = None):
    """通用执行骨架:逐行读 stdout 交给 parse_line,超时由看门狗杀进程,返回原始结果。"""
    started = time.monotonic()
    lines: list[str] = []
    stderr_tail: list[str] = []
    timed_out = threading.Event()

    def _on_timeout() -> None:
        timed_out.set()
        _kill_group(proc)

    def _drain_stderr() -> None:
        try:
            stderr_tail.append(proc.stderr.read()[-800:] or "")
        except Exception:  # noqa: BLE001 - stderr 只用于诊断,读不到不影响回合
            pass

    proc = _popen_group(argv, workdir, env)
    watchdog = threading.Timer(timeout_sec, _on_timeout)
    watchdog.daemon = True
    watchdog.start()
    threading.Thread(target=_drain_stderr, daemon=True).start()
    try:
        for line in proc.stdout:
            lines.append(line)
            if parse_line is not None:
                # 事件流必须无条件解析:会话 id、失败原因、用量都从这里来,
                # 不能因为调用方不要流式推送就跳过
                for event_type, payload in parse_line(line, state if state is not None else {}):
                    if on_event is None:
                        continue
                    try:
                        on_event(event_type, payload)
                    except Exception as e:  # noqa: BLE001
                        log.warn("conversation.event_emit_failed", error=repr(e))
        proc.wait(timeout=60)
    finally:
        watchdog.cancel()
    return {"exit_code": proc.returncode, "stdout": "".join(lines), "timed_out": timed_out.is_set(),
            "stderr": "".join(stderr_tail), "duration_ms": int((time.monotonic() - started) * 1000)}


def _timeout_result(kind: str, timeout_sec: int) -> dict:
    return {"text": "", "session_id": None, "is_error": True,
            "error": f"{kind} 对话回合超时({timeout_sec} 秒)后被终止", "usage": None, "model": None,
            "exit_code": None}


def _failure_detail(kind: str, exit_code: int, stderr: str, hint: str | None) -> str:
    """失败必须带原始报错:CLI 的 stderr / 事件流里的错误,绝不只报「exited N」。"""
    detail = (hint or "").strip() or (stderr or "").strip()
    if not detail:
        detail = f"{kind} CLI 以退出码 {exit_code} 结束,且没有输出任何报错信息"
    return detail[-800:]


def _codex_argv(cli_binary: str, workdir: Path, prompt: str, last_file: Path,
                session_ref: str | None, mcp: dict | None) -> list[str]:
    # codex exec [resume <会话id>] <提示词> [选项];--json 逐行输出事件;-o 落最终回复
    argv = [cli_binary, "exec"]
    if session_ref:
        argv += ["resume", str(session_ref)]
    argv += [prompt, "--json", "--skip-git-repo-check", "-s", "read-only", "-C", str(workdir), "-o", str(last_file)]
    if mcp and mcp.get("url"):
        argv += ["-c", f'mcp_servers.ainative.url="{mcp["url"]}"',
                 "-c", f'mcp_servers.ainative.bearer_token_env_var="{CODEX_MCP_TOKEN_ENV}"',
                 "-c", "mcp_servers.ainative.enabled=true"]
    return argv


def _parse_codex_line(line: str, state: dict) -> list[tuple[str, dict]]:
    """codex --json 的一行事件 → 0..n 个 (eventType, payload)。

    state 由调用方提供,跨行累积 thread_id / 回合失败原因 / 传输层重连次数。
    文字只在 item.completed 时整条给出(codex 不吐逐字增量),所以 codex 的流式粒度
    是「一条消息一截」。
    """
    line = (line or "").strip()
    if not line.startswith("{"):
        return []
    try:
        data = json.loads(line)
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    kind = data.get("type")
    if kind == "thread.started":
        state["session_id"] = data.get("thread_id")
        return []
    if kind == "turn.failed":
        error = data.get("error") or {}
        state["failure"] = error.get("message") if isinstance(error, dict) else str(error)
        return []
    if kind == "error":
        state["transport_errors"] = int(state.get("transport_errors", 0)) + 1
        if data.get("message"):
            state["last_transport_error"] = str(data["message"])
        return []
    if kind == "turn.completed":
        usage = data.get("usage") or {}
        state["usage"] = {"inputTokens": int(usage.get("input_tokens") or 0),
                          "outputTokens": int(usage.get("output_tokens") or 0),
                          "cacheReadTokens": int(usage.get("cached_input_tokens") or 0),
                          "cacheWriteTokens": 0}
        return []
    if kind != "item.completed":
        return []
    item = data.get("item") or {}
    item_type = item.get("type")
    if item_type == "agent_message" and item.get("text"):
        return [("text", {"type": "text", "content": item["text"]})]
    if item_type == "reasoning" and item.get("text"):
        return [("thinking", {"type": "thinking", "content": item["text"]})]
    if item_type == "command_execution":
        return [("tool_use", {"type": "tool_use", "callId": item.get("id"), "tool": "shell",
                              "status": "completed", "input": item.get("command"),
                              "output": str(item.get("aggregated_output") or "")[:4000]})]
    if item_type == "mcp_tool_call":
        return [("tool_use", {"type": "tool_use", "callId": item.get("id"),
                              "tool": f'{item.get("server")}.{item.get("tool")}', "status": "completed",
                              "input": json.dumps(item.get("arguments") or {}, ensure_ascii=False),
                              "output": json.dumps(item.get("result"), ensure_ascii=False)[:4000]})]
    return []


def _run_codex_turn(cli_binary: str, workdir: Path, content: str, *, session_ref, system_prompt,
                    env: dict, timeout_sec: int, mcp: dict | None, on_event) -> dict:
    last_file = Path(workdir) / "codex-last-message.txt"
    try:
        last_file.unlink()
    except OSError:
        pass
    argv = _codex_argv(cli_binary, workdir, _prepend_system_prompt(system_prompt, content),
                       last_file, session_ref, mcp)
    child_env = dict(env)
    if mcp and mcp.get("token"):
        child_env[CODEX_MCP_TOKEN_ENV] = mcp["token"]
    state: dict = {}
    raw = _spawn(argv, workdir, child_env, timeout_sec, on_event, _parse_codex_line, state)
    if raw["timed_out"]:
        return _timeout_result("codex", timeout_sec)
    text = last_file.read_text(encoding="utf-8", errors="replace").strip() if last_file.exists() else ""
    result = {"text": text, "session_id": state.get("session_id") or session_ref, "is_error": True,
              "error": None, "usage": state.get("usage"), "model": None, "exit_code": raw["exit_code"],
              "duration_ms": raw["duration_ms"]}
    if raw["exit_code"] == 0 and text:
        result["is_error"] = False
        return result
    hint = state.get("failure")
    if not hint and state.get("transport_errors"):
        # 传输层反复重连后失败:把最后一条网络错误带出来,而不是只有退出码
        hint = f'与模型服务通信失败(重连 {state["transport_errors"]} 次):{state.get("last_transport_error")}'
    result["error"] = _failure_detail("codex", raw["exit_code"], raw["stderr"], hint)
    return result


def _run_json_result_turn(kind: str, cli_binary: str, workdir: Path, content: str, *, session_ref,
                          system_prompt, env: dict, timeout_sec: int) -> dict:
    """cursor-agent:输出与 claude 同构的 result 行;续聊 --resume;无逐字流式。"""
    argv = [cli_binary, "-p", _prepend_system_prompt(system_prompt, content), "--output-format", "json",
            "--force"]
    if session_ref:
        argv += ["--resume", str(session_ref)]
    try:
        code, stdout, stderr = _run_group(argv, workdir, env, timeout_sec)
    except subprocess.TimeoutExpired:
        return _timeout_result(kind, timeout_sec)
    result = parse_result(stdout)
    result["exit_code"] = code
    if code == 0 and not result["is_error"]:
        return result
    hint = result["error"] if result.get("subtype") else None
    result["is_error"] = True
    result["error"] = _failure_detail(kind, code, stderr, hint)
    return result


def _run_gemini_turn(cli_binary: str, workdir: Path, content: str, *, system_prompt,
                     env: dict, timeout_sec: int) -> dict:
    """gemini -p --output-format json:输出 {"response": "..."};非交互模式不保留会话。"""
    argv = [cli_binary, "-p", _prepend_system_prompt(system_prompt, content), "--output-format", "json"]
    try:
        code, stdout, stderr = _run_group(argv, workdir, env, timeout_sec)
    except subprocess.TimeoutExpired:
        return _timeout_result("gemini", timeout_sec)
    text = ""
    try:
        parsed = json.loads(stdout.strip() or "null")
        if isinstance(parsed, dict) and isinstance(parsed.get("response"), str):
            text = parsed["response"].strip()
    except ValueError:
        text = stdout.strip()
    result = {"text": text, "session_id": None, "is_error": not (code == 0 and text),
              "error": None, "usage": None, "model": None, "exit_code": code,
              "duration_ms": 0}
    if result["is_error"]:
        result["error"] = _failure_detail("gemini", code, stderr, None)
    return result


def run_conversation_turn(kind: str, cli_binary: str, workdir: Path, content: str, *, session_ref,
                          system_prompt, settings_path, max_turns: int, env: dict,
                          timeout_sec: int = TURN_TIMEOUT_SEC, allowed_tools=None,
                          mcp: dict | None = None, on_event=None, wrapup_rounds: int = 1) -> dict:
    """执行一回合澄清对话(各执行器同一返回形态,见 run_claude_turn)。

    kind:
      claude   stream-json 逐字流式;撞步数上限时自动续一轮「收尾」(wrapup_rounds)
      codex    codex exec --json;事件流式(一条消息一截);--resume 续聊;MCP 走配置覆盖项
      cursor   --output-format json;--resume 续聊;MCP 走工作目录 .cursor/mcp.json
      gemini   --output-format json;不保留会话;MCP 走工作目录 .gemini/settings.json

    cursor / gemini 按各家 CLI 的公开参数实现;本次改动所在的机器未安装这两个 CLI,
    未能做真机验证 —— 失败时错误信息会原样带回 CLI 的报错,参数若有出入能直接看出来。
    """
    kind = conversation_kind(kind)
    if kind == "codex":
        return _run_codex_turn(cli_binary, workdir, content, session_ref=session_ref,
                               system_prompt=system_prompt, env=env, timeout_sec=timeout_sec,
                               mcp=mcp, on_event=on_event)
    if kind == "cursor":
        return _run_json_result_turn("cursor", cli_binary, workdir, content, session_ref=session_ref,
                                     system_prompt=system_prompt, env=env, timeout_sec=timeout_sec)
    if kind == "gemini":
        return _run_gemini_turn(cli_binary, workdir, content, system_prompt=system_prompt,
                                env=env, timeout_sec=timeout_sec)

    result = run_claude_turn(cli_binary, workdir, content, session_ref=session_ref,
                             system_prompt=system_prompt, settings_path=settings_path,
                             max_turns=max_turns, allowed_tools=allowed_tools, env=env,
                             timeout_sec=timeout_sec, on_event=on_event)
    if not (result.get("max_turns_hit") and wrapup_rounds > 0 and result.get("session_id")):
        return result
    # 撞步数上限不硬失败:续一轮「收尾」,让 AI 停止调用工具、基于已有结果给出交代
    wrap = run_claude_turn(cli_binary, workdir, WRAPUP_PROMPT, session_ref=result["session_id"],
                           system_prompt=system_prompt, settings_path=settings_path,
                           max_turns=WRAPUP_MAX_TURNS, allowed_tools=allowed_tools, env=env,
                           timeout_sec=timeout_sec, on_event=on_event)
    if not wrap["is_error"] and wrap["text"].strip():
        wrap["session_id"] = wrap.get("session_id") or result["session_id"]
        wrap["truncated"] = True
        return wrap
    # 收尾也失败:保留「步数上限」这个真实死因,不覆盖成收尾失败的错误
    result["wrapup_error"] = wrap.get("error")
    return result

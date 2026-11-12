"""Daemon entry point: WS session, heartbeat, dispatch worker, recovery."""
from __future__ import annotations

import argparse
import json
import signal
import socket
import sys
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlencode

from . import frames
from .config import Config, ConfigError, load_config
from .logging_util import JsonLogger, log
from .packager import PackageError, prepare
from .providers.base import COLLAPSE, RunContext
from .providers.claude_code import write_settings_file
from .providers.mock import MockProvider
from .store import StateStore
from .wsclient import WsClient, WsClosed, WsIdleTimeout

PROVIDERS = {"mock": MockProvider}


def make_provider(cfg: Config):
    if cfg.provider == "mock":
        return MockProvider()
    if cfg.provider == "claude":
        from .providers.claude_code import ClaudeCodeProvider
        return ClaudeCodeProvider(cfg.agent_cmd, cfg.agent_timeout_sec, cfg.agent_max_turns,
                                  cfg.extra_env, allowed_tools=cfg.agent_allowed_tools)
    if cfg.provider in ("gemini", "codex", "cursor"):
        from .providers.text_cli import TextCliProvider
        return TextCliProvider(kind=cfg.provider, cli_binary=cfg.agent_cmd,
                               timeout_sec=cfg.agent_timeout_sec)
    raise ConfigError(f"unknown provider: {cfg.provider}")


class Daemon:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.store = StateStore(str(Path(cfg.data_dir) / "runtime-state.db"))
        self.provider = make_provider(cfg)
        self.ws: WsClient | None = None
        self._stop = threading.Event()
        self._ws_lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="dispatch")
        self._inflight: set[int] = set()
        self._inflight_lock = threading.Lock()
        # 澄清会话(BL:CONVERSATION_TURN 协议实现):独立线程池,不阻塞派发与心跳
        self._conv_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="conversation")
        self._conv_inflight: set[int] = set()
        self._conv_inflight_lock = threading.Lock()
        self._conv_done: dict[int, dict] = {}  # turn_id -> ack frame(重投幂等重放)
        self._heartbeat_thread: threading.Thread | None = None

    def _start_heartbeat_once(self) -> None:
        """One heartbeat thread per daemon process; leaked threads flood the server (DEF-008)."""
        if self._heartbeat_thread is None or not self._heartbeat_thread.is_alive():
            self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop,
                                                      name="heartbeat", daemon=True)
            self._heartbeat_thread.start()

    # ---- config for WS URL -------------------------------------------------
    def _connect(self) -> WsClient:
        query = urlencode({"executorId": self.cfg.executor_id, "token": self.cfg.executor_token})
        hostport = self.cfg.ws_hostport
        if ":" in hostport:
            host, _, port_s = hostport.rpartition(":")
            port = int(port_s)
        else:
            host, port = hostport, 443 if self.cfg.use_tls else 80
        ws = WsClient(host, port, f"{self.cfg.ws_path}?{query}", use_tls=self.cfg.use_tls, timeout=60)
        ws.connect()
        with self._ws_lock:
            self.ws = ws
        return ws

    def send_frame(self, frame: dict, *, dispatch_id: int | None = None, durable: bool = True) -> None:
        if not durable:
            self._send_now(json.dumps(frame, ensure_ascii=False))
            return
        outbox_id = self.store.enqueue_frame(frame["type"], frame, dispatch_id)
        self._flush_one(outbox_id)

    def _send_now(self, raw: str) -> None:
        with self._ws_lock:
            ws = self.ws
        if ws is None:
            raise WsClosed("not connected")
        ws.send_text(raw)

    def _flush_one(self, outbox_id: int | None) -> None:
        if outbox_id is None:
            return
        row = self.store.conn.execute("SELECT payload FROM outbox WHERE id=?", (outbox_id,)).fetchone()
        if not row:
            return
        self._send_now(row[0])
        self.store.mark_sent(outbox_id)

    def replay_pending(self) -> None:
        for row in self.store.pending_frames():
            frame = json.loads(row[3])
            ftype = frame.get("type")
            # ACK/PROGRESS are transitional; only RESULT/HANDOFF must survive reconnects.
            if ftype in ("TASK_RESULT", "TASK_HANDOFF"):
                with self._ws_lock:
                    ws = self.ws
                if ws is None:
                    return
                ws.send_text(row[3])
                self.store.mark_sent(row[0])
                log.info("frame.replayed", type=ftype, dispatch_id=row[1])
            else:
                self.store.mark_sent(row[0])

    # ---- heartbeat ----------------------------------------------------------
    def _heartbeat_loop(self):
        while not self._stop.is_set():
            try:
                with self._inflight_lock:
                    inflight = sorted(self._inflight)
                self.send_frame(frames.heartbeat(inflight, inflight, self.cfg.max_concurrent), durable=False)
            except (WsClosed, OSError, socket.timeout) as e:
                log.warn("heartbeat.send_failed", error=str(e))
            self._stop.wait(self.cfg.heartbeat_sec)

    # ---- PowerContext 记忆外挂(可选) ----------------------------------------
    def _powercontext_recall(self, dispatch: dict, capsule) -> str | None:
        """拉取跨工单记忆;任何失败都返回 None(绝不阻断)。"""
        if not self.cfg.powercontext_base_url:
            return None
        try:
            from .powercontext_client import (PowerContextSettings, resolve_scope,
                                              prepare_context)
            st = PowerContextSettings(base_url=self.cfg.powercontext_base_url,
                                      timeout_sec=self.cfg.powercontext_timeout_sec)
            wid = int(dispatch.get("workspaceId") or self.cfg.executor_id)
            scope_id = resolve_scope(wid, settings=st)
            if not scope_id:
                return None
            title = str(capsule.workitem_md)[:400]
            return prepare_context(scope_id, title, settings=st)
        except Exception as e:  # noqa: BLE001
            log.warn("powercontext.recall_failed", error=repr(e))
            return None

    def _powercontext_remember(self, dispatch: dict, capsule, summary: str) -> None:
        """把本次交付的关键决策写回 PowerContext;任何失败静默。"""
        if not self.cfg.powercontext_base_url or not self.cfg.powercontext_remember:
            return
        try:
            from .powercontext_client import (PowerContextSettings, resolve_scope, remember)
            st = PowerContextSettings(base_url=self.cfg.powercontext_base_url,
                                      timeout_sec=self.cfg.powercontext_timeout_sec)
            wid = int(dispatch.get("workspaceId") or self.cfg.executor_id)
            scope_id = resolve_scope(wid, settings=st)
            if not scope_id:
                return
            title = (capsule.workitem_md or "").strip().splitlines()[0][:120]
            text = f"[{title}] {summary[:600]}"
            remember(wid, "decision", text, settings=st,
                     idempotency_key=f"codeflow-dispatch-{dispatch.get('dispatchId')}")
            log.info("powercontext.remembered", dispatch_id=dispatch.get("dispatchId"))
        except Exception as e:  # noqa: BLE001
            log.warn("powercontext.remember_error", error=repr(e))

    # ---- dispatch worker ----------------------------------------------------
    def _emit_progress(self, dispatch_id: int, run_event):
        frame = frames.task_progress(
            dispatch_id, run_event.type,
            step_order=run_event.step_order, step_id=run_event.step_id,
            step_name=run_event.step_name, message=run_event.message)
        self.send_frame(frame, dispatch_id=dispatch_id)

    def _run_dispatch(self, dispatch: dict):
        did = int(dispatch["dispatchId"])
        try:
            self.send_frame(frames.task_ack(did), dispatch_id=did)
            self.store.update_run(did, state="preparing")
            pkg_dir, capsule = prepare(dispatch, Path(self.cfg.data_dir) / "workspaces")
            self.store.update_run(did, state="running", capsule_dir=str(pkg_dir),
                                  session_ref=self.store.get_session(did))
            from .providers.base import SdlcStep
            steps = capsule.sdlc.get("steps") or []
            if steps:  # 协议时序:一次 dispatch 内按 sdlc.json 逐步执行(§4)
                plan = [SdlcStep.from_capsule_step(s, i + 1) for i, s in enumerate(steps)]
            else:
                plan = [SdlcStep(f"step-{did}", 1, "execute", "完成工单当前步骤")]

            workdir = pkg_dir / "workspace"
            self.provider.prepare(capsule, workdir)
            # PowerContext 记忆外挂(可选):拉取跨工单记忆,落成注入文件供 prompt 装配
            pc_note = self._powercontext_recall(dispatch, capsule)
            if pc_note:
                try:
                    (workdir / "deliverables").mkdir(parents=True, exist_ok=True)
                    (workdir / "deliverables" / "powercontext-context.md").write_text(
                        pc_note, encoding="utf-8")
                    log.info("powercontext.recalled", dispatch_id=did, bytes=len(pc_note))
                except OSError as e:
                    log.warn("powercontext.write_failed", dispatch_id=did, error=repr(e))
            session_ref = self.store.get_session(did)
            artifacts, failures, summaries = [], [], []
            steps_done = 0
            for step in plan:
                ctx = RunContext(dispatch_id=did, capsule=capsule,
                                 workdir=type("W", (), {"root": workdir})(),
                                 prev_session=session_ref,
                                 emit_event=lambda ev: self._emit_progress(did, ev))
                result = self.provider.run(step, ctx)
                for ev in result.events:
                    self.store.add_event(did, ev.type, step_order=ev.step_order,
                                         step_id=ev.step_id, step_name=ev.step_name)
                artifacts.extend(result.artifacts)
                summaries.append(f"[{step.name}] {result.summary}"[:400])
                if result.session_ref:
                    session_ref = result.session_ref
                    self.store.set_session(did, session_ref)
                if result.state != "completed":
                    failures.append(f"step {step.step_id}({step.name}): {result.state} {result.error}".strip())
                    break  # SDLC 串行:失败即止,由服务端 onFail 决策
                steps_done += 1
            success = not failures
            summary = ("; ".join(summaries) if success else "; ".join(failures))[:2000] or "completed"
            self.send_frame(frames.task_result(did, success, summary, ""), dispatch_id=did)
            self.store.mark_terminal(did, success)
            log.info("dispatch.finished", dispatch_id=did, success=success, steps_done=steps_done)
            if not success and self.cfg.handoff_human_on_fail:
                try:
                    from .chain import handoff_to_human
                    handoff_to_human(self.cfg, int(dispatch["workitemId"]), self._human_id(capsule))
                    log.info("chain.handed_to_human", dispatch_id=did, workitem=dispatch["workitemId"])
                except Exception as e:  # noqa: BLE001
                    log.error("chain.human_handoff_failed", dispatch_id=did, error=repr(e))
            if success:
                # 数字员工交付备注(多源回写):把最终结论送回平台 → 外部工单平台(禅道等)
                try:
                    from .chain import agent_comment
                    agent_comment(self.cfg, did, summary)
                    log.info("chain.agent_comment_posted", dispatch_id=did, workitem=dispatch["workitemId"])
                except Exception as e:  # noqa: BLE001 - 备注失败不影响交付结果
                    log.warn("chain.agent_comment_failed", dispatch_id=did, error=repr(e))
                # PowerContext 记忆写回:本次交付的关键决策/约束(可选,失败不影响)
                try:
                    self._powercontext_remember(dispatch, capsule, summary)
                except Exception as e:  # noqa: BLE001
                    log.warn("powercontext.remember_failed", dispatch_id=did, error=repr(e))
                self._chain_next_role(dispatch, capsule, did)
        except PackageError as e:
            log.error("dispatch.package_failed", dispatch_id=did, error=str(e))
            self.send_frame(frames.task_result(did, False, "package verification failed", str(e)), dispatch_id=did)
            self.store.mark_terminal(did, False)
        except Exception as e:  # noqa: BLE001 - worker must never die silently
            log.error("dispatch.crashed", dispatch_id=did, error=repr(e))
            try:
                self.send_frame(frames.task_result(did, False, "runtime internal error", repr(e)), dispatch_id=did)
                self.store.mark_terminal(did, False)
            except Exception:  # noqa: BLE001
                pass
            if self.cfg.handoff_human_on_fail:
                try:
                    from .chain import handoff_to_human
                    handoff_to_human(self.cfg, int(dispatch["workitemId"]),
                                     self._human_id(capsule if 'capsule' in dir() else None))
                except Exception as he:  # noqa: BLE001
                    log.error("chain.human_handoff_failed", dispatch_id=did, error=repr(he))
        finally:
            with self._inflight_lock:
                self._inflight.discard(did)


    @staticmethod
    def _human_id(capsule):
        humans = (capsule.roster.get("humanTeammates") or []) if capsule else []
        return humans[0].get("userId") if humans else None

    # ---- 跨角色接棒(P0.5):架构许可的通道 = 执行器经平台 API 显式发起(SdlcDriver javadoc) ----
    def _chain_next_role(self, dispatch: dict, capsule, did: int):
        nxt_id = self.cfg.handoff_agent_id
        if not nxt_id:
            if self.cfg.chain_finalize:
                try:
                    from .chain import finalize_to_done
                    finalize_to_done(self.cfg, int(dispatch["workitemId"]))
                    log.info("chain.finalized", dispatch_id=did, workitem=dispatch["workitemId"])
                except Exception as e:  # noqa: BLE001
                    log.error("chain.finalize_failed", dispatch_id=did, error=repr(e))
            return
        try:
            from .chain import reassign_to_agent  # 局部导入避免无配置时的依赖
            role = ""
            for tm in (capsule.roster.get("digitalTeammates") or []):
                if str(tm.get("agentId")) == str(nxt_id):
                    role = str(tm.get("roleCode") or tm.get("roleName") or "")
                    break
            self.send_frame(frames.task_handoff(
                did, int(dispatch["workitemId"]), role or f"agent-{nxt_id}",
                "AGENT", role, "auto-chain to next role"), dispatch_id=did)
            reassign_to_agent(self.cfg, int(dispatch["workitemId"]), nxt_id)
            log.info("chain.reassigned", dispatch_id=did, next_agent=nxt_id, role=role)
        except Exception as e:  # noqa: BLE001 - 接棒失败不改变已成功的 dispatch 终态
            log.error("chain.failed", dispatch_id=did, error=repr(e))

    def on_dispatch(self, payload: dict):
        did = int(payload["dispatchId"])
        if self.store.is_terminal(did):
            log.info("dispatch.duplicate_terminal", dispatch_id=did)
            self.send_frame(frames.task_result(did, False, "duplicate dispatch for already finished task", ""),
                            dispatch_id=did) if False else None
            # terminal success flag decides: replay stored outcome instead of re-running
            row = self.store.conn.execute("SELECT success FROM terminal WHERE dispatch_id=?", (did,)).fetchone()
            success = bool(row and row[0])
            self.send_frame(frames.task_result(did, success, "idempotent replay of terminal dispatch", ""),
                            dispatch_id=did)
            return
        with self._inflight_lock:
            if did in self._inflight:
                log.info("dispatch.duplicate_inflight", dispatch_id=did)
                return
            self._inflight.add(did)
        if not self.store.create_run(payload, self.provider.name):
            log.info("dispatch.duplicate_tracked", dispatch_id=did)
            return
        log.info("dispatch.accepted", dispatch_id=did, workitem=payload.get("workitemId"))
        self._pool.submit(self._run_dispatch, payload)

    # ---- main loop ----------------------------------------------------------
    def run_forever(self):
        backoff = 1.0
        recovery_done = False
        while not self._stop.is_set():
            try:
                ws = self._connect()
                log.info("ws.connected", server=self.cfg.ws_hostport, executor_id=self.cfg.executor_id)
                backoff = 1.0
                self._start_heartbeat_once()
                if not recovery_done:
                    # Recovery is a STARTUP operation: reconnects must never touch in-flight runs (DEF-007).
                    self._recover()
                    recovery_done = True
                self.replay_pending()
                while not self._stop.is_set():
                    try:
                        frame = ws.recv_json()
                    except WsIdleTimeout:
                        # 读空闲属正常(数据流以出站为主):保持连接,不做重连(否则每 60s 抖动一次)
                        log.debug("ws.idle", detail="no inbound frame within read window")
                        continue
                    if frame.get("type") == "TASK_DISPATCH":
                        data = frames.decode_dispatch(frame)
                        self.on_dispatch(data)
                    elif frame.get("type") == "CONVERSATION_TURN":
                        self.on_conversation_turn(frames.decode_conversation_turn(frame))
                    else:
                        log.debug("frame.ignored", type=frame.get("type"))
            except WsClosed as e:
                if isinstance(e, WsIdleTimeout):
                    # Heartbeats are outbound-only; idle reads are normal. Keep the session.
                    log.debug("ws.idle", detail="no inbound frame within read window")
                    continue
                log.warn("ws.closed", error=str(e))
            except (OSError, socket.timeout) as e:
                log.warn("ws.error", error=repr(e))
            finally:
                with self._ws_lock:
                    self.ws = None
            if self._stop.is_set():
                break
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)

    def _recover(self):
        """Handle runs left active by a previous process.

        本进程不认领旧派发(owned=[]),PAUSING/RUNNING 残留由服务端调和器显式收敛为失败
        并走 SDLC onFail 重试;此处仅清本地台账,避免僵尸 run。
        """
        for row in self.store.active_runs():
            did = row["dispatch_id"]
            if self.store.is_terminal(did):
                continue
            log.warn("recovery.active_run", dispatch_id=did, state=row["state"])
            self.store.mark_terminal(did, False)

    # ---- 澄清会话(CONVERSATION_TURN) ----

    def on_conversation_turn(self, turn: dict) -> None:
        turn_id = turn["turn_id"]
        with self._conv_inflight_lock:
            if turn_id in self._conv_inflight:
                log.info("conversation.duplicate_inflight", turn_id=turn_id)
                return
            done = self._conv_done.get(turn_id)
            if done is not None:
                # 平台重投同一回合:重放既有 ACK(dispatchAttempt 回显为新帧值),不重跑
                self._conv_inflight.add(turn_id)
                self._conv_pool.submit(self._reack_conversation_turn, turn, done)
                return
            self._conv_inflight.add(turn_id)
        self._conv_pool.submit(self._run_conversation_turn, turn)

    def _reack_conversation_turn(self, turn: dict, done: dict) -> None:
        try:
            frame = dict(done)
            if turn.get("dispatch_attempt"):
                frame["dispatchAttempt"] = int(turn["dispatch_attempt"])
            self.send_frame(frame, durable=False)
            log.info("conversation.reacked", turn_id=turn["turn_id"])
        finally:
            with self._conv_inflight_lock:
                self._conv_inflight.discard(turn["turn_id"])

    def _run_conversation_turn(self, turn: dict) -> None:
        from . import conversation as conv
        turn_id = turn["turn_id"]
        try:
            workdir = Path(self.cfg.data_dir) / "conversations" / str(turn["conversation_id"])
            capsule_dir = workdir / "capsule"
            if turn.get("capability_url"):
                zip_path = conv.download_capability(turn["capability_url"],
                                                    turn.get("capability_sha256") or "", capsule_dir)
                conv.extract_capability(zip_path, capsule_dir)
            settings_path = conv.write_conversation_settings(workdir, self.cfg.extra_env or {},
                                                             turn.get("mcp_token"))
            conv.write_mcp_config(workdir, conv.ainative_mcp_server(capsule_dir, turn.get("mcp_token") or ""))
            mcp_server = conv.ainative_mcp_server(capsule_dir, turn.get("mcp_token") or "")
            if mcp_server and turn.get("mcp_token"):
                conv.register_mcp_server(self.cfg.agent_cmd, workdir,
                                         dict(os.environ) | (self.cfg.extra_env or {}) | {"HOME": str(workdir)},
                                         mcp_server["url"], turn["mcp_token"])
            # MCP 工具预授权:settings.permissions.allow 为权威路径,此处通配双保险
            allowed = ",".join(t for t in (self.cfg.agent_allowed_tools or "",
                                           "mcp__ainative,mcp__ainative__*" if turn.get("mcp_token") else "")
                               if t) or None
            result = conv.run_claude_turn(
                self.cfg.agent_cmd, workdir, turn["content"],
                session_ref=turn.get("cli_session_ref"),
                system_prompt=turn.get("system_prompt"),
                settings_path=settings_path,
                max_turns=self.cfg.agent_max_turns,
                allowed_tools=allowed,
                env=dict(os.environ) | (self.cfg.extra_env or {}) | {"HOME": str(workdir)},
            )
            if result["is_error"]:
                ack = conv.turn_ack(turn["conversation_id"], turn_id, turn.get("dispatch_attempt"),
                                    "FAILED", error=result.get("error") or "claude failed")
            else:
                ack = conv.turn_ack(turn["conversation_id"], turn_id, turn.get("dispatch_attempt"),
                                    "SUCCESS", reply_markdown=result["text"],
                                    session_id=result.get("session_id"),
                                    usage=result.get("usage"), model=result.get("model"))
            with self._conv_inflight_lock:
                self._conv_done[turn_id] = ack  # 幂等重放留底
                while len(self._conv_done) > 200:  # 只留最近回合,防无界增长
                    self._conv_done.pop(next(iter(self._conv_done)))
            self.send_frame(ack, durable=False)
            log.info("conversation.turned", turn_id=turn_id,
                     success=not result["is_error"], duration_ms=result.get("duration_ms"))
        except Exception as e:  # noqa: BLE001 - 会话失败必须回 ACK,不能黑洞
            log.error("conversation.failed", turn_id=turn_id, error=repr(e))
            try:
                from . import conversation as conv
                self.send_frame(conv.turn_ack(turn["conversation_id"], turn_id,
                                              turn.get("dispatch_attempt"), "FAILED",
                                              error=repr(e)[:800]), durable=False)
            except Exception:  # noqa: BLE001
                pass
        finally:
            with self._conv_inflight_lock:
                self._conv_inflight.discard(turn_id)

    def stop(self, *_):
        self._stop.set()
        with self._ws_lock:
            if self.ws is not None:
                self.ws.close()


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    # 平台启动命令形态:`ainative connect --ws-url ... --token ... --executor-id ...`。
    # `connect` 子命令后的全部 CLI 参数都会转成配置覆盖(优先级高于环境变量)。
    if argv and argv[0] == 'connect':
        argv = argv[1:]
    parser = argparse.ArgumentParser(prog="ainative")
    parser.add_argument('connect_cmd', nargs='?', choices=['connect'], help=argparse.SUPPRESS)
    parser.add_argument("--once-smoke", action="store_true", help="connect + heartbeat once, then exit 0")
    parser.add_argument("--check-config", action="store_true")
    parser.add_argument("--ws-url", help="platform websocket endpoint (AINATIVE_SERVER_WS_URL)")
    parser.add_argument("--token", help="executor token (AINATIVE_EXECUTOR_TOKEN)")
    parser.add_argument("--executor-id", help="executor id (AINATIVE_EXECUTOR_ID)")
    parser.add_argument("--provider", help="mock | claude | gemini | codex | cursor (AINATIVE_PROVIDER)")
    parser.add_argument("--memory-mode", help=argparse.SUPPRESS)  # 平台概念,runtime 不区分
    parser.add_argument("--max-tasks", help="max concurrent tasks (RUNTIME_MAX_CONCURRENT)")
    parser.add_argument("--model", help="model id passed through to the provider CLI (ANTHROPIC_MODEL)")
    parser.add_argument("--reasoning-effort", help=argparse.SUPPRESS)
    parser.add_argument("--context-window", help=argparse.SUPPRESS)
    parser.add_argument("--token-aware-enable", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--workspace-root", help=argparse.SUPPRESS)
    parser.add_argument("--name", help=argparse.SUPPRESS)
    parser.add_argument("--debug", action="store_true", help="verbose runtime logging")
    args = parser.parse_args(argv)
    overrides: dict = {}
    if args.ws_url:
        overrides["AINATIVE_SERVER_WS_URL"] = args.ws_url
    if args.token:
        overrides["AINATIVE_EXECUTOR_TOKEN"] = args.token
    if args.executor_id:
        overrides["AINATIVE_EXECUTOR_ID"] = args.executor_id
    if args.provider:
        overrides["AINATIVE_PROVIDER"] = args.provider
    if args.max_tasks:
        overrides["RUNTIME_MAX_CONCURRENT"] = args.max_tasks
    if args.model:
        overrides["ANTHROPIC_MODEL"] = args.model
    if args.reasoning_effort:
        overrides["ANTHROPIC_REASONING_EFFORT"] = args.reasoning_effort
    if args.context_window:
        overrides["ANTHROPIC_CONTEXT_WINDOW"] = args.context_window
    if args.debug:
        overrides["RUNTIME_LOG_LEVEL"] = "debug"
    try:
        cfg = load_config(overrides)
    except ConfigError as e:
        log.error("config.invalid", error=str(e))
        return 2
    if args.check_config:
        log.info("config.ok", provider=cfg.provider, executor_id=cfg.executor_id, server=cfg.server_ws_url)
        return 0
    d = Daemon(cfg)
    signal.signal(signal.SIGINT, d.stop)
    signal.signal(signal.SIGTERM, d.stop)
    if args.once_smoke:
        try:
            d._connect()
            d.send_frame(frames.heartbeat(), durable=False)
            log.info("smoke.ok", executor_id=cfg.executor_id)
            time.sleep(1.0)
            return 0
        except (WsClosed, OSError) as e:
            log.error("smoke.failed", error=repr(e))
            return 1
        finally:
            d.stop()
    log.info("daemon.start", provider=cfg.provider, data_dir=cfg.data_dir)
    d.run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())

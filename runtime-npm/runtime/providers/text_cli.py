"""文本型 CLI provider:Gemini / Codex / Cursor CLI。

与 claude 的 stream-json 协议不同,这三家 CLI 以纯文本 stdout 返回结果:
  gemini  gemini -p "<prompt>"
  codex   codex exec "<prompt>"
  cursor  cursor-agent -p "<prompt>"
stdout 全文作为该步骤摘要;exit 0 即成功。凭据走各家 CLI 的环境变量/本机配置
(GEMINI_API_KEY / OPENAI_API_KEY 等),不经 runtime 转手;HOME 保持调用方环境,
允许读本机已有的 CLI 登录态。
"""
from __future__ import annotations

import os
import signal
import subprocess
import threading
import time
from pathlib import Path

from .base import RunContext, RunResult, RuntimeEvent, SdlcStep

KIND_DEFAULT_CMD = {"gemini": "gemini", "codex": "codex", "cursor": "cursor-agent"}


class TextCliProvider:
    name = "text-cli"

    def __init__(self, kind: str, cli_binary: str, timeout_sec: int = 1800):
        if kind not in KIND_DEFAULT_CMD:
            raise ValueError(f"unknown text-cli kind: {kind}")
        self.kind = kind
        self.name = kind
        self.cli_binary = cli_binary
        self.timeout_sec = timeout_sec

    def _argv(self, prompt: str) -> list[str]:
        if self.kind in ("gemini", "cursor"):
            return [self.cli_binary, "-p", prompt]
        return [self.cli_binary, "exec", prompt]  # codex

    def prepare(self, capsule, workdir: Path) -> None:
        (workdir / "deliverables").mkdir(parents=True, exist_ok=True)

    def _prompt(self, step: SdlcStep, capsule) -> str:
        parts = [
            f"# 工单\n{capsule.workitem_md}",
            f"# 当前步骤({step.step_id},顺序 {step.step_order}):{step.name}",
            f"目标:{step.goal}",
        ]
        if step.deliverables:
            parts.append("要求的产物:" + ", ".join(step.deliverables))
        if step.done_gate:
            parts.append(f"完成门:{step.done_gate}")
        parts.append("完成后把产物写入 deliverables/ 目录。")
        return "\n\n".join(parts)

    def run(self, step: SdlcStep, ctx: RunContext) -> RunResult:
        started = RuntimeEvent("step.started", step.step_order, step.step_id, step.name)
        if ctx.emit_event:
            ctx.emit_event(started)

        argv = self._argv(self._prompt(step, ctx.capsule))
        env = dict(os.environ)
        # start_new_session:超时 killpg 不连坐 daemon(同 claude provider,DEF-009)
        proc = subprocess.Popen(argv, cwd=str(ctx.workdir.root), env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                start_new_session=True)

        def _kill_tree():
            time.sleep(self.timeout_sec)
            if proc.poll() is None:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    proc.kill()

        watcher = threading.Thread(target=_kill_tree, daemon=True)
        watcher.start()
        timed_out = False
        try:
            out, err = proc.communicate(timeout=self.timeout_sec + 5)
        except subprocess.TimeoutExpired:
            timed_out = True
            out, err = proc.communicate()
        watcher.join(timeout=1)

        text = (out or b"").decode("utf-8", "replace").strip()
        if timed_out:
            state, summary, error = "timed_out", "", f"{self.kind} timeout after {self.timeout_sec}s"
        elif proc.returncode == 0:
            state, summary, error = "completed", text or f"step {step.step_id} completed", ""
        else:
            state = "failed"
            summary = f"{self.kind} exited with {proc.returncode}"
            error = (err or b"").decode("utf-8", "replace")[-2000:] or summary

        artifacts = sorted((ctx.workdir.root / "deliverables").glob("*"))
        completed = RuntimeEvent("step.completed", step.step_order, step.step_id, step.name,
                                 message=state)
        if ctx.emit_event:
            ctx.emit_event(completed)
        return RunResult(state=state, summary=summary[:2000], error=error,
                         events=[started, completed], artifacts=artifacts, session_ref=None)

    def cleanup(self, run_record: dict) -> None:
        pass

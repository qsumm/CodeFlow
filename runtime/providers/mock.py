"""MockProvider: deterministic execution for pipeline verification (no model)."""
from __future__ import annotations

import time
from pathlib import Path

from .base import RunContext, RunResult, RuntimeEvent


class MockProvider:
    name = "mock"

    def prepare(self, capsule, workdir: Path) -> None:
        (workdir / "deliverables").mkdir(parents=True, exist_ok=True)
        (workdir / "README.md").write_text(
            f"# mock workdir\nrole={capsule.manifest.get('roleCode')}\n", encoding="utf-8")

    def run(self, step, ctx: RunContext) -> RunResult:
        started = RuntimeEvent("step.started", step.step_order, step.step_id, step.name)
        if ctx.emit_event:
            ctx.emit_event(started)
        time.sleep(0.2)  # simulate work
        safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in step.step_id)
        out = ctx.workdir.root / "deliverables" / f"{safe}.md"
        out.write_text(
            f"# {step.name}\n\n"
            f"role: {ctx.capsule.manifest.get('roleCode')}\n"
            f"step: {step.step_id} (order {step.step_order})\n"
            f"goal: {step.goal}\n"
            f"done gate: {step.done_gate}\n\n"
            "## 结论\n\n本文件由 ainative MockProvider 生成,用于验证平台派发-执行-回传链路。\n",
            encoding="utf-8")
        completed = RuntimeEvent("step.completed", step.step_order, step.step_id, step.name,
                                 message=f"deliverable: {out.name}")
        if ctx.emit_event:
            ctx.emit_event(completed)
        return RunResult(state="completed",
                         summary=f"step {step.step_id} ({step.name}) completed by mock provider",
                         events=[started, completed], artifacts=[out])

    def cleanup(self, run_record: dict) -> None:
        pass

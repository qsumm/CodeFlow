"""Provider contract, RunState, runtime events."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

RUN_STATES = ("assigned", "preparing", "package_ready", "workspace_ready", "running",
              "collecting", "uploading", "blocked", "recovering",
              "completed", "failed", "cancelled", "timed_out")

# CollapseRunState (protocol §7.2): client RunState -> server coarse state
COLLAPSE = {
    "assigned": "DISPATCHED",
    "preparing": "ACKED", "package_ready": "ACKED", "workspace_ready": "ACKED",
    "running": "RUNNING", "collecting": "RUNNING", "uploading": "RUNNING",
    "blocked": "RUNNING", "recovering": "RUNNING",
    "completed": "SUCCEEDED", "failed": "FAILED", "cancelled": "CANCELED", "timed_out": "TIMEOUT",
}


@dataclass
class SdlcStep:
    step_id: str
    step_order: int
    name: str
    goal: str
    deliverables: list[str] = field(default_factory=list)
    done_gate: str = ""

    @classmethod
    def from_capsule_step(cls, s: dict, order: int) -> "SdlcStep":
        return cls(
            step_id=str(s.get("stepId") or s.get("id") or f"step-{order}"),
            step_order=int(s.get("stepOrder") or order),
            name=str(s.get("name") or s.get("stepName") or s.get("title") or f"step-{order}"),
            goal=str(s.get("goal") or s.get("description") or s.get("instruction") or ""),
            deliverables=list(s.get("deliverables") or []),
            done_gate=str(s.get("doneGate") or s.get("completionGate") or ""),
        )


@dataclass
class RuntimeEvent:
    type: str                       # step.started | step.completed | agent.progress
    step_order: int | None = None
    step_id: str | None = None
    step_name: str | None = None
    message: str | None = None


@dataclass
class RunResult:
    state: str                      # completed | failed | timed_out | cancelled
    summary: str
    error: str = ""
    events: list[RuntimeEvent] = field(default_factory=list)
    artifacts: list[Path] = field(default_factory=list)
    session_ref: str | None = None


@dataclass
class Workdir:
    root: Path                      # provider working directory


@dataclass
class RunContext:
    dispatch_id: int
    capsule: object                 # packager.Capsule
    workdir: Workdir
    prev_session: str | None = None
    emit_event: object = None       # callable(RuntimeEvent)


class Provider(Protocol):
    name: str

    def prepare(self, capsule, workdir: Path) -> None: ...
    def run(self, step: SdlcStep, ctx: RunContext) -> RunResult: ...

    def run_wrapup(self, step: SdlcStep, ctx: RunContext) -> RunResult:
        """撞步数上限后的收尾轮:基于已有产出给出最终交代(不再调工具)。

        默认不支持 —— 供应商不支持时返回原样失败,上层据此保留真实死因。
        """
        return RunResult(state="failed", summary="wrapup unsupported", error="")
    def cleanup(self, run_record: dict) -> None: ...

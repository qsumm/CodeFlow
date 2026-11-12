"""CodeFlow runtime - configuration (env only, stdlib)."""
from __future__ import annotations

import os
from pathlib import Path
from dataclasses import dataclass, field


class ConfigError(Exception):
    pass


@dataclass
class Config:
    server_ws_url: str
    executor_id: int
    executor_token: str
    provider: str = "mock"
    agent_cmd: str = "claude"
    agent_timeout_sec: int = 1800
    agent_max_turns: int = 40
    agent_allowed_tools: str = ""
    handoff_agent_id: int | None = None
    chain_finalize: bool = False
    handoff_human_on_fail: bool = False
    data_dir: str = "./runtime-data"
    heartbeat_sec: int = 30
    max_concurrent: int = 1
    log_level: str = "info"
    powercontext_base_url: str = ""
    powercontext_timeout_sec: float = 8.0
    powercontext_remember: bool = True
    extra_env: dict = field(default_factory=dict)

    @property
    def ws_path(self) -> str:
        # ws://host:port/ws/executor -> path with query appended later
        parts = self.server_ws_url.split("://", 1)
        if len(parts) != 2 or parts[0] not in ("ws", "wss"):
            raise ConfigError(f"AINATIVE_SERVER_WS_URL must start with ws:// or wss://: {self.server_ws_url}")
        hostpath = parts[1]
        path = "/" + hostpath.split("/", 1)[1] if "/" in hostpath else "/ws/executor"
        if path.rstrip("/") != "/ws/executor":
            raise ConfigError(f"WS path must be /ws/executor, got: {path}")
        return path

    @property
    def ws_hostport(self) -> str:
        return self.server_ws_url.split("://", 1)[1].split("/", 1)[0]

    @property
    def use_tls(self) -> bool:
        return self.server_ws_url.startswith("wss://")


def load_config(env: dict | None = None) -> Config:
    env = dict(os.environ if env is None else env)
    missing = [k for k in ("AINATIVE_SERVER_WS_URL", "AINATIVE_EXECUTOR_ID", "AINATIVE_EXECUTOR_TOKEN") if not env.get(k)]
    if missing:
        raise ConfigError("missing required env: " + ", ".join(missing))
    provider = env.get("AINATIVE_PROVIDER", "mock")
    if provider not in ("mock", "claude", "gemini", "codex", "cursor"):
        raise ConfigError(f"AINATIVE_PROVIDER must be mock|claude|gemini|codex|cursor, got: {provider}")
    # 各 provider 的默认 CLI 命令;可用 AINATIVE_AGENT_CMD 覆盖(绝对路径/自定义名)。
    cfg = Config(
        server_ws_url=env["AINATIVE_SERVER_WS_URL"],
        executor_id=int(env["AINATIVE_EXECUTOR_ID"]),
        executor_token=env["AINATIVE_EXECUTOR_TOKEN"],
        provider=provider,
        agent_cmd=env.get("AINATIVE_AGENT_CMD",
                          {"claude": "claude", "gemini": "gemini", "codex": "codex",
                           "cursor": "cursor-agent"}.get(provider, "claude")),
        agent_timeout_sec=int(env.get("AINATIVE_AGENT_TIMEOUT_SEC", "1800")),
        agent_max_turns=int(env.get("AINATIVE_AGENT_MAX_TURNS", "40")),
        agent_allowed_tools=env.get("AINATIVE_AGENT_ALLOWED_TOOLS", ""),
        handoff_agent_id=int(env["RUNTIME_HANDOFF_AGENT_ID"]) if env.get("RUNTIME_HANDOFF_AGENT_ID") else None,
        chain_finalize=env.get("RUNTIME_CHAIN_FINALIZE", "0") in ("1", "true", "yes"),
        handoff_human_on_fail=env.get("RUNTIME_HANDOFF_HUMAN_ON_FAIL", "0") in ("1", "true", "yes"),
        # 绝对化:工作目录会作为 claude CLI 的 cwd,相对路径会让 --settings 解析失败(DEF-026)
        data_dir=str(Path(env.get("RUNTIME_DATA_DIR", "./runtime-data")).expanduser().resolve()),
        heartbeat_sec=int(env.get("RUNTIME_HEARTBEAT_SEC", "30")),
        max_concurrent=int(env.get("RUNTIME_MAX_CONCURRENT", "1")),
        log_level=env.get("RUNTIME_LOG_LEVEL", "info"),
        # PowerContext 记忆外挂(可选):不配 POWERCONTEXT_BASE_URL 即关闭
        powercontext_base_url=(env.get("POWERCONTEXT_BASE_URL") or "").strip().rstrip("/"),
        powercontext_timeout_sec=float(env.get("POWERCONTEXT_TIMEOUT_SEC") or "8.0"),
        powercontext_remember=env.get("POWERCONTEXT_REMEMBER", "1") in ("1", "true", "yes"),
        extra_env={k: v for k, v in env.items()
                  if k.startswith(("ANTHROPIC_", "AINATIVE_AGENT_"))
                  or k in ("AINATIVE_ACCESS_TOKEN", "AINATIVE_REFRESH_TOKEN",
                           "AINATIVE_API_BASE_URL", "AINATIVE_WORKSPACE_ID")},
    )
    cfg.ws_path  # validate URL shape eagerly
    return cfg

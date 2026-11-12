import pytest
from pathlib import Path

from runtime.config import ConfigError, load_config

BASE = {
    "AINATIVE_SERVER_WS_URL": "ws://localhost:7001/ws/executor",
    "AINATIVE_EXECUTOR_ID": "900001",
    "AINATIVE_EXECUTOR_TOKEN": "tok",
}


def test_ok_defaults():
    c = load_config(BASE)
    assert c.provider == "mock" and c.executor_id == 900001
    assert c.ws_path == "/ws/executor" and c.ws_hostport == "localhost:7001"
    assert c.use_tls is False


def test_missing_required():
    with pytest.raises(ConfigError):
        load_config({"AINATIVE_SERVER_WS_URL": "ws://x/ws/executor"})


def test_bad_url():
    with pytest.raises(ConfigError):
        load_config({**BASE, "AINATIVE_SERVER_WS_URL": "http://x/ws/executor"})


def test_bad_path():
    with pytest.raises(ConfigError):
        load_config({**BASE, "AINATIVE_SERVER_WS_URL": "ws://x/other"})


def test_bad_provider():
    with pytest.raises(ConfigError):
        load_config({**BASE, "AINATIVE_PROVIDER": "openrouter"})


def test_agent_cmd_defaults_per_provider():
    """各 provider 有默认 CLI 命令,可用 AINATIVE_AGENT_CMD 覆盖。"""
    assert load_config({**BASE, "AINATIVE_PROVIDER": "claude"}).agent_cmd == "claude"
    assert load_config({**BASE, "AINATIVE_PROVIDER": "gemini"}).agent_cmd == "gemini"
    assert load_config({**BASE, "AINATIVE_PROVIDER": "codex"}).agent_cmd == "codex"
    assert load_config({**BASE, "AINATIVE_PROVIDER": "cursor"}).agent_cmd == "cursor-agent"
    custom = load_config({**BASE, "AINATIVE_PROVIDER": "gemini", "AINATIVE_AGENT_CMD": "/opt/bin/gemini"})
    assert custom.agent_cmd == "/opt/bin/gemini"


def test_data_dir_is_resolved_to_absolute():
    """DEF-026:相对 RUNTIME_DATA_DIR 会让 claude CLI 的 --settings 相对 cwd 解析失败。"""
    c = load_config({**BASE, "RUNTIME_DATA_DIR": "./runtime-data"})
    assert Path(c.data_dir).is_absolute()
    assert c.data_dir == str(Path("./runtime-data").resolve())


def test_settings_path_stays_absolute_with_relative_data_dir():
    """工作目录(claude CLI 的 cwd)必须能定位到 claude-settings.json。"""
    c = load_config({**BASE, "RUNTIME_DATA_DIR": "./runtime-data"})
    settings = Path(c.data_dir) / "workspaces" / "w" / "workspace" / "claude-settings.json"
    assert settings.is_absolute()

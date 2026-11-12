import json

from runtime.providers.base import SdlcStep
from runtime.providers.claude_code import build_command, parse_stream
from runtime.providers.mock import MockProvider


def test_build_command_without_session():
    argv = build_command("claude", "hi", max_turns=0)
    assert argv[:3] == ["claude", "-p", "hi"]
    assert "--output-format" in argv and "stream-json" in argv
    assert "--resume" not in argv


def test_build_command_with_session_and_flags():
    argv = build_command("claude", "hi", session_ref="s-9", max_turns=10,
                         system_prompt="你是数字员工", allowed_tools="Read,Edit")
    assert argv[argv.index("--resume") + 1] == "s-9"
    assert argv[argv.index("--max-turns") + 1] == "10"
    assert argv[argv.index("--allowedTools") + 1] == "Read,Edit"
    assert argv[argv.index("--append-system-prompt") + 1] == "你是数字员工"


def test_settings_file_written_and_pinned(tmp_path):
    from runtime.providers.claude_code import write_settings_file
    env = {"ANTHROPIC_BASE_URL": "https://x/anthropic", "ANTHROPIC_AUTH_TOKEN": "tp-x",
           "ANTHROPIC_MODEL": "m", "UNRELATED": "1"}
    p = write_settings_file(tmp_path / "s.json", env)
    cfg = json.loads(open(p).read())
    assert cfg["env"]["ANTHROPIC_BASE_URL"] == "https://x/anthropic"
    assert "UNRELATED" not in cfg["env"]
    argv = build_command("claude", "hi", settings_file=p)
    assert argv[argv.index("--settings") + 1] == p


STREAM = "\n".join([
    json.dumps({"type": "system", "subtype": "init", "session_id": "sess-abc"}),
    json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": "动手"}]}}),
    "not json line",
    "",
    json.dumps({"type": "result", "subtype": "success", "result": "完成"}),
])


def test_parse_stream_ok():
    events = []
    sess, text, err = parse_stream(STREAM.splitlines(), emit=events.append, step_ref=(1, "s1", "开发"))
    assert sess == "sess-abc"
    assert "动手" in text and "完成" in text
    assert err is False
    assert any(e.type == "agent.progress" for e in events)


def test_parse_stream_error():
    lines = [json.dumps({"type": "result", "subtype": "error_max_turns", "result": "卡住了", "is_error": True})]
    _, text, err = parse_stream(lines)
    assert err is True and "卡住" in text


def test_mock_provider(tmp_path):
    from types import SimpleNamespace
    capsule = SimpleNamespace(manifest={"roleCode": "dev"})
    ctx = SimpleNamespace(capsule=capsule, workdir=SimpleNamespace(root=tmp_path),
                          emit_event=None, prev_session=None, dispatch_id=1)
    p = MockProvider()
    p.prepare(capsule, tmp_path)
    step = SdlcStep("s1", 1, "开发", "实现功能", deliverables=["x.md"], done_gate="自测通过")
    res = p.run(step, ctx)
    assert res.state == "completed"
    assert [e.type for e in res.events] == ["step.started", "step.completed"]
    assert res.artifacts and res.artifacts[0].exists()


def _text_ctx(tmp_path):
    from types import SimpleNamespace
    return SimpleNamespace(
        capsule=SimpleNamespace(workitem_md="# 工单\n内容", sdlc={}, identity={}, manifest={}),
        workdir=SimpleNamespace(root=tmp_path),
        emit_event=None,
    )


def _fake_cli(tmp_path, body: str):
    import stat
    cli = tmp_path / "fake-cli"
    cli.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    cli.chmod(cli.stat().st_mode | stat.S_IEXEC)
    return cli


def test_text_cli_gemini_success_and_argv(tmp_path):
    from runtime.providers.text_cli import TextCliProvider
    cli = _fake_cli(tmp_path, 'echo "ARGV:[$0 $1]"; echo "gemini 收到需求" > deliverables/out.md\n')
    p = TextCliProvider(kind="gemini", cli_binary=str(cli), timeout_sec=30)
    p.prepare(None, tmp_path)  # 与 daemon 一致:run 前建 deliverables/
    step = SdlcStep("s1", 1, "需求分析", "理解需求")
    res = p.run(step, _text_ctx(tmp_path))
    assert res.state == "completed"
    assert "ARGV:" in res.summary and "-p" in res.summary  # gemini/cursor 走 -p 单参
    assert res.artifacts and res.artifacts[0].name == "out.md"
    assert "gemini 收到需求" in res.artifacts[0].read_text(encoding="utf-8")
    assert [e.type for e in res.events] == ["step.started", "step.completed"]


def test_text_cli_codex_uses_exec_subcommand(tmp_path):
    from runtime.providers.text_cli import TextCliProvider
    cli = _fake_cli(tmp_path, 'echo "MODE:$1"; echo codex-done\n')
    p = TextCliProvider(kind="codex", cli_binary=str(cli), timeout_sec=30)
    step = SdlcStep("s1", 1, "实现", "编码")
    res = p.run(step, _text_ctx(tmp_path))
    assert res.state == "completed"
    assert "MODE:exec" in res.summary


def test_text_cli_failure_captures_stderr(tmp_path):
    from runtime.providers.text_cli import TextCliProvider
    cli = _fake_cli(tmp_path, 'echo "boom: no api key" >&2; exit 3\n')
    p = TextCliProvider(kind="cursor", cli_binary=str(cli), timeout_sec=30)
    step = SdlcStep("s1", 1, "交付", "产出")
    res = p.run(step, _text_ctx(tmp_path))
    assert res.state == "failed"
    assert "boom: no api key" in res.error
    assert "exited with 3" in res.summary


def test_make_provider_builds_text_cli_kinds():
    from runtime.config import load_config
    from runtime.daemon import make_provider
    from runtime.providers.text_cli import TextCliProvider
    base = {"AINATIVE_SERVER_WS_URL": "ws://h/ws/executor", "AINATIVE_EXECUTOR_ID": "1",
            "AINATIVE_EXECUTOR_TOKEN": "t"}
    for kind in ("gemini", "codex", "cursor"):
        cfg = load_config({**base, "AINATIVE_PROVIDER": kind})
        p = make_provider(cfg)
        assert isinstance(p, TextCliProvider) and p.kind == kind

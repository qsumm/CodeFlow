import urllib.error
from unittest import mock

from runtime import chain
from runtime.config import load_config

BASE = {"AINATIVE_SERVER_WS_URL": "ws://x/ws/executor", "AINATIVE_EXECUTOR_ID": "1",
        "AINATIVE_EXECUTOR_TOKEN": "t", "AINATIVE_PROVIDER": "mock",
        "RUNTIME_HANDOFF_AGENT_ID": "200002"}
CFG = load_config(BASE)


def test_reassign_payload(monkeypatch):
    seen = {}
    monkeypatch.setattr(chain, "_put", lambda p, b, token=None: seen.update(path=p, body=b) or {"success": True})
    chain._state.update({"access": "tok", "refresh": None, "base": "http://x"})
    chain.reassign_to_agent(CFG, 10005, 200002)
    assert seen["path"] == "/api/workitems/10005/assignee"
    assert seen["body"] == {"assigneeType": "AGENT", "assigneeRef": 200002}


def test_401_refresh_retry(monkeypatch):
    calls = []
    def fake_post(path, body, token=None):
        calls.append(token)
        if len(calls) == 1:
            raise urllib.error.HTTPError(path, 401, "expired", None, None)
        return {"success": True}
    monkeypatch.setattr(chain, "_put", fake_post)
    monkeypatch.setattr(chain, "_ensure_token", lambda cfg: "new")
    chain._state.update({"access": "old", "refresh": "rt", "base": "http://x"})
    chain.reassign_to_agent(CFG, 1, 2)
    assert calls == ["new", "new"]  # _ensure_token 被全程 mock:首调即返回新 token

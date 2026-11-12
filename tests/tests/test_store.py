import json

import pytest

from runtime.store import StateStore

DISPATCH = {"dispatchId": 300001, "tenantId": 100001, "workitemId": 500001, "attempt": 1}


@pytest.fixture()
def store(tmp_path):
    return StateStore(str(tmp_path / "state.db"))


def test_run_tracking_and_idempotency(store):
    assert store.create_run(DISPATCH, "mock") is True
    assert store.create_run(DISPATCH, "mock") is False
    assert store.run_state(300001) == "received"
    store.update_run(300001, state="running")
    assert store.run_state(300001) == "running"


def test_outbox_pending_and_replay(store):
    i1 = store.enqueue_frame("TASK_RESULT", frames_result(1, True))
    store.enqueue_frame("TASK_ACK", {"type": "TASK_ACK", "dispatchId": 2})
    pend = store.pending_frames()
    assert len(pend) == 2
    store.mark_sent(i1)
    pend = store.pending_frames()
    assert len(pend) == 1 and pend[0]["frame_type"] == "TASK_ACK"


def frames_result(did, ok):
    return {"type": "TASK_RESULT", "dispatchId": did, "success": ok, "resultSummary": "s", "error": ""}


def test_terminal_blocks_and_replays(store):
    store.create_run(DISPATCH, "mock")
    assert store.is_terminal(300001) is False
    store.mark_terminal(300001, True)
    assert store.is_terminal(300001) is True
    assert store.run_state(300001) == "completed"
    # active_runs no longer includes it
    assert all(r["dispatch_id"] != 300001 for r in store.active_runs())


def test_active_runs_recovered(store):
    store.create_run(DISPATCH, "mock")
    store.update_run(300001, state="running")
    assert [r["dispatch_id"] for r in store.active_runs()] == [300001]


def test_events(store):
    store.create_run(DISPATCH, "mock")
    store.add_event(300001, "step.started", step_order=1, step_id="s1", step_name="开发", payload={"k": "v"})
    row = store.conn.execute("SELECT type, step_name, payload FROM events").fetchone()
    assert row[0] == "step.started" and row[1] == "开发" and json.loads(row[2])["k"] == "v"


def test_session_ref(store):
    store.create_run(DISPATCH, "claude")
    assert store.get_session(300001) is None
    store.set_session(300001, "sess-1")
    assert store.get_session(300001) == "sess-1"

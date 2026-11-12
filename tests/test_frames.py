import json

from runtime import frames


def test_ack_shape():
    assert frames.task_ack(300001) == {"type": "TASK_ACK", "dispatchId": 300001}


def test_result_shape():
    f = frames.task_result(300001, False, "boom", "err")
    assert f == {"type": "TASK_RESULT", "dispatchId": 300001, "success": False,
                 "resultSummary": "boom", "error": "err"}
    assert frames.task_result(1, True, "ok")["error"] == ""


def test_progress_optional_fields():
    f = frames.task_progress(1, "step.started", step_order=2, step_id="s2", step_name="开发")
    assert f["stepOrder"] == 2 and f["stepId"] == "s2" and f["stepName"] == "开发"
    bare = frames.task_progress(1, "agent.progress")
    assert "stepOrder" not in bare and "message" not in bare


def test_heartbeat_features():
    f = frames.heartbeat()
    assert f["type"] == "HEARTBEAT" and "OPEN_RUNTIME_V1" in f["protocolFeatures"]
    assert json.dumps(f)


def test_handoff_defaults():
    f = frames.task_handoff(1, 2, "reviewer")
    assert f["toType"] == "AGENT" and f["nextRole"] == ""


def test_decode_dispatch_missing_field():
    import pytest
    from runtime.frames import FrameError
    with pytest.raises(FrameError):
        frames.decode_dispatch({"type": "TASK_DISPATCH", "dispatchId": 1})
    ok = frames.decode_dispatch({"type": "TASK_DISPATCH", "dispatchId": 1, "executorId": 2,
                                 "tenantId": 3, "workitemId": 4, "attempt": 1, "downloadUrl": "http://x"})
    assert ok["dispatchId"] == 1


def test_decode_frame_bad_json():
    import pytest
    from runtime.frames import FrameError
    with pytest.raises(FrameError):
        frames.decode_frame("not json")
    with pytest.raises(FrameError):
        frames.decode_frame("[1,2]")


def test_heartbeat_inventory_contract():
    f = frames.heartbeat(owned=[300002, 300001], running=[300002], capacity=2)
    assert f["dispatchInventoryReady"] is True
    assert f["ownedDispatchIds"] == [300001, 300002]
    assert f["runningDispatchIds"] == [300002]
    assert f["maxConcurrentDispatches"] == 2
    assert "dispatch_inventory_v1" in f["protocolFeatures"]
    assert f["runningConversationTurnIds"] == []


def test_heartbeat_defaults_empty():
    f = frames.heartbeat()
    assert f["ownedDispatchIds"] == [] and f["runningDispatchIds"] == []
    assert f["maxConcurrentDispatches"] == 1

"""Native sequences bind only the public capture ID into structure paging."""

import pytest

import test_workflows as native

bridge = native.bridge


def capture_plan(*, hint=True, tail_bind=None, tail_args=None):
    steps = []
    if hint:
        steps.append(native.action("hint", tool="mcb_interact", args={"action": "use", **native.POSITION,
            "side": 2, "sneaking": False}))
    steps.extend([
        native.read("capture", tool="mcb_world_structure", args={**native.POSITION, "runtimeOffset": 0},
            fields=["runtimeObservation.hasMore"]),
        native.read("tail", tool="mcb_world_structure", args=tail_args if tail_args is not None else {
            **native.POSITION, "runtimeOffset": 128},
            bind=tail_bind if tail_bind is not None else {"captureId": {"step": "capture", "path": "runtimeObservation.captureId"}},
            fields=["runtimeObservation.captureId", "runtimeObservation.hasMore", "runtimeObservation.nextRuntimeOffset"]),
    ])
    return native.plan(*steps)


def enable_hint(state):
    state["health_flags"].update(interactionSneaking=True, interactionBlockTargeting=True, interactionViewBinding=True)


@pytest.mark.parametrize("hint", [True, False])
async def test_hint_capture_and_bound_tail_execute_in_one_native_sequence(bridge, hint):
    adapter, state = bridge
    enable_hint(state)

    def structure():
        args = state["calls"][-1]["args"]
        offset = args["runtimeOffset"]
        if offset:
            assert args["captureId"] == "actual-public-native-capture"
        return {"runtimeObservation": {"captureId": "actual-public-native-capture", "runtimeOffset": offset,
            "nextRuntimeOffset": offset + 128, "hasMore": offset == 0}}

    state["data"]["world.structure"] = structure
    result = await adapter.call_tool("mcb_sequence", capture_plan(hint=hint))
    data = result.structuredContent["data"]
    assert not result.isError and data["status"] == "completed", data
    calls = [call["args"] for call in state["calls"] if call["operation"] == "world.structure"]
    assert calls == [{**native.POSITION, "runtimeOffset": 0},
        {**native.POSITION, "runtimeOffset": 128, "captureId": "actual-public-native-capture"}]
    capture = next(step for step in data["steps"] if step["id"] == "capture")
    assert "runtimeObservation.captureId" not in capture["observation"]["values"]
    tail = next(step for step in data["steps"] if step["id"] == "tail")
    assert tail["observation"]["values"]["runtimeObservation.hasMore"] is False
    assert data["acknowledgedActions"] == int(hint)
    assert native.operations(state).count("player.interact") == int(hint)
    assert native.operations(state).count("client.background") == int(hint)


@pytest.mark.parametrize("value,code", [(None, "INVALID_ARGUMENTS"), (False, "INVALID_ARGUMENTS"),
    (17, "INVALID_ARGUMENTS"), ([], "INVALID_ARGUMENTS"), ({}, "INVALID_ARGUMENTS"),
    ("", "INVALID_ARGUMENTS"), ("x" * 129, "INVALID_ARGUMENTS"), ("missing", "MISSING_BINDING")])
async def test_missing_or_untyped_capture_binding_stops_before_tail_without_replaying_hint(bridge, value, code):
    adapter, state = bridge
    enable_hint(state)
    state["data"]["world.structure"] = {"runtimeObservation": {} if value == "missing" else {"captureId": value}}
    arguments = capture_plan()
    arguments["steps"].append(native.action("later"))
    result = await adapter.call_tool("mcb_sequence", arguments)
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == code, data
    assert [step["status"] for step in data["steps"]] == ["completed", "completed", "failed", "not_executed"]
    assert native.operations(state).count("world.structure") == native.operations(state).count("player.interact") == 1
    assert "player.hotbar" not in native.operations(state)
    assert data["outcomeUnknown"] is False


@pytest.mark.parametrize("key", ["piece", "limit", "runtimeOffset", "query", "arbitrary"])
async def test_capture_binding_fix_does_not_allow_other_structure_arguments(bridge, key):
    adapter, state = bridge
    arguments = capture_plan(tail_args=native.POSITION.copy(),
        tail_bind={key: {"step": "capture", "path": "runtimeObservation.captureId"}})
    result = await adapter.call_tool("mcb_sequence", arguments)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


async def test_capture_id_binding_cannot_be_used_for_another_tool(bridge):
    adapter, state = bridge
    arguments = capture_plan()
    arguments["steps"][-1]["tool"] = "mcb_world_inspect"
    arguments["steps"][-1]["args"] = native.POSITION.copy()
    result = await adapter.call_tool("mcb_sequence", arguments)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


@pytest.mark.parametrize("fault", ["literal_conflict", "future"])
async def test_capture_binding_retains_plan_reference_guards(bridge, fault):
    adapter, state = bridge
    arguments = capture_plan()
    if fault == "literal_conflict":
        arguments["steps"][-1]["args"]["captureId"] = "literal"
    else:
        arguments["steps"][-1]["bind"]["captureId"]["step"] = "future"
        arguments["steps"].append(native.read("future", tool="mcb_world_structure", args=native.POSITION.copy()))
    result = await adapter.call_tool("mcb_sequence", arguments)
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert state["calls"] == []


async def test_native_stale_capture_refusal_is_preserved_without_new_capture_or_retry(bridge):
    adapter, state = bridge
    enable_hint(state)

    def first_capture():
        state["action_error"] = {"code": "stale_capture", "message": "The actual native capture expired."}
        return {"runtimeObservation": {"captureId": "actual-capture", "hasMore": True}}

    state["data"]["world.structure"] = first_capture
    result = await adapter.call_tool("mcb_sequence", capture_plan())
    data = result.structuredContent["data"]
    assert result.isError and data["error"]["code"] == "stale_capture", data
    assert native.operations(state).count("world.structure") == 2
    assert native.operations(state).count("player.interact") == 1
    assert data["acknowledgedActions"] == 1 and data["outcomeUnknown"] is False

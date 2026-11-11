import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import validate_tool_arguments
from minecraft_codex_bridge.validation import execution_validator


def test_follow_uses_one_native_entrypoint_and_exact_task_cancel():
    spec = TOOL_BY_NAME["mcb_follow"]
    assert spec.operation == "player.follow" and not spec.takeover
    for args in (
        {"action": "start", "player": "TestPlayer", "expectedWorld": "fixture"},
        {"action": "status"},
        {"action": "stop", "taskId": "exact-task"},
    ):
        validate_tool_arguments(spec.name, {**args, "expectedSessionId": "session"})
    for args in (
        {"action": "start", "player": "TestPlayer"},
        {"action": "stop"},
        {"action": "开始", "player": "TestPlayer", "expectedWorld": "fixture"},
    ):
        with pytest.raises(BridgeError):
            validate_tool_arguments(spec.name, {**args, "expectedSessionId": "session"})


def test_nearby_target_is_generic_and_does_not_accept_public_execution_override():
    args = {"expectedSessionId": "session", "expectedWorld": "fixture", "dimension": 0,
            "goal": {"type": "nearby", "center": {"x": 4, "y": 64, "z": 8}}}
    validate_tool_arguments("mcb_navigate", args)
    for strategy in ("walking", "ability_flight", "developer_teleport"):
        execution_validator("mcb_navigate").validate({**args, "strategy": strategy})
        with pytest.raises(BridgeError):
            validate_tool_arguments("mcb_navigate", {**args, "strategy": strategy})
    with pytest.raises(BridgeError):
        validate_tool_arguments("mcb_navigate", {**args, "goal": {**args["goal"], "radius": 20}})

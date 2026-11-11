from minecraft_codex_bridge.server import tool_validator
from minecraft_codex_bridge.tools import TOOL_BY_NAME


def test_compact_schematic_is_exclusive_and_bounded():
    context = {"dimension": 0, "expectedWorld": "fixture", "expectedSessionId": "session", "anchor": {"x": 0, "y": 64, "z": 0}}
    args = {**context, "palette": [{"kind": "air"}], "boxes": [{"min": {"x": 0, "y": 0, "z": 0}, "max": {"x": 31, "y": 3, "z": 31}, "palette": 0}], "includePlacementOrder": False}
    validator = tool_validator("mcb_schematic_goal")
    assert not list(validator.iter_errors(args))
    assert list(validator.iter_errors({**args, "cells": [{"x": 0, "y": 0, "z": 0, "kind": "air"}]}))
    assert list(validator.iter_errors({**args, "palette": [{"kind": "block"}]}))
    assert list(validator.iter_errors({**args, "boxes": args["boxes"] * 257}))
    assert list(validator.iter_errors({**args, "includePlacementOrder": "false"}))


def test_input_task_cancellation_has_no_implicit_takeover_or_stale_wildcard():
    status, cancel = TOOL_BY_NAME["mcb_task_status"], TOOL_BY_NAME["mcb_task_cancel"]
    assert status.read_only and not status.takeover
    assert not cancel.read_only and not cancel.takeover
    assert status.operation == cancel.operation == "player.task"
    assert not list(tool_validator(status.name).iter_errors({"expectedSessionId": "session"}))
    args = {"action": "cancel", "expectedSessionId": "session", "taskId": "task"}
    assert not list(tool_validator(cancel.name).iter_errors(args))
    assert list(tool_validator(cancel.name).iter_errors({"expectedSessionId": "session"}))
    assert list(tool_validator(cancel.name).iter_errors({**args, "taskId": ""}))
    assert list(tool_validator(status.name).iter_errors(args))

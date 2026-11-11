import asyncio
import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration
from minecraft_codex_bridge.server import tool_validator
from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_READ_NAMES


def test_goal_schema_keeps_legacy_bounds_and_exclusive_coordinates():
    validator = tool_validator("mcb_navigate")
    context = {"dimension": 0, "expectedWorld": "fixture", "expectedSessionId": "session"}
    goal = {"type": "box", "min": {"x": 1, "y": 64, "z": 1}, "max": {"x": 3, "y": 64, "z": 3}}
    assert not list(validator.iter_errors({**context, "goal": goal}))
    assert not list(validator.iter_errors({**context, "goal": goal, "includeDetails": True}))
    assert list(validator.iter_errors({**context, "goal": goal, "includeDetails": "yes"}))
    assert list(validator.iter_errors({**context, "goal": goal, "x": 1}))
    assert list(validator.iter_errors({**context, "goal": goal, "strategy": "developer_teleport"}))
    assert list(validator.iter_errors({**context, "goal": goal, "timeout_ms": 6501}))
    assert not list(validator.iter_errors({**context, "goal": goal, "timeout_ms": 20000, "segmented": True}))


def test_generic_schematic_is_discoverable_and_read_only():
    tool = TOOL_BY_NAME["mcb_schematic_goal"]
    assert tool.read_only and tool.operation == "structure.goal" and not tool.takeover
    assert tool.name in SEQUENCE_READ_NAMES
    context = {"dimension": 0, "expectedWorld": "fixture", "expectedSessionId": "session", "anchor": {"x": 0, "y": 64, "z": 0}}
    block = {"x": 0, "y": 0, "z": 0, "kind": "block", "alternatives": [{"registryName": "gregtech:gt.blockmachines", "metadata": 3, "metaTileId": 42}]}
    assert not list(tool_validator(tool.name).iter_errors({**context, "cells": [block]}))
    negative = {**block, "x": -2, "y": -1, "z": -3}
    assert not list(tool_validator(tool.name).iter_errors({**context, "cells": [negative]}))
    assert list(tool_validator(tool.name).iter_errors({**context, "cells": [{**negative, "y": -2049}]}))
    assert list(tool_validator(tool.name).iter_errors({**context, "cells": [{"x": 0, "y": 0, "z": 0, "kind": "block"}]}))
    assert list(tool_validator(tool.name).iter_errors({**context, "cells": [{**block, "kind": "air"}]}))
    assert not list(tool_validator(tool.name).iter_errors({**context, "cells": [{"x": 0, "y": -1, "z": 0, "kind": "air"}]}))


def test_interaction_goal_keeps_faces_identity_and_client_owned_aim():
    validator = tool_validator("mcb_navigate")
    context = {"dimension": 180, "expectedWorld": "Debug", "expectedSessionId": "session"}
    target = {"x": -81, "y": 157, "z": -114, "allowedSides": [5],
              "targetIdentity": {"registryName": "gregtech:gt.blockmachines", "metaTileId": 19085}}
    goal = {"type": "interaction", "targets": [target]}
    assert not list(validator.iter_errors({**context, "goal": goal}))
    assert not list(validator.iter_errors({**context, "goal": goal, "timeout_ms": 15000}))
    assert list(validator.iter_errors({**context, "goal": goal, "timeout_ms": 15001}))
    for extra in ({"lookAt": {"x": 0, "y": 64, "z": 0}}, {"waypoints": []}, {"strategy": "ability_flight"}, {"segmented": True}):
        assert list(validator.iter_errors({**context, "goal": goal, **extra}))
    assert list(validator.iter_errors({**context, "goal": {"type": "interaction", "targets": [{**target, "allowedSides": [6]}]}}))


@pytest.mark.parametrize("segmented,millis,expected,interaction", [(False, 6000, 10, False), (True, 6000, 10, False), (True, 20000, 22, False), (False, 14500, 16.5, True)])
def test_only_explicit_long_navigation_extends_transport(segmented, millis, expected, interaction):
    observed = []
    def handler(request):
        observed.append(request.extensions["timeout"]["read"])
        body = json.loads(request.content)
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": {"status": "completed"}})
    async def run():
        client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "fixture-token"}), transport=httpx.MockTransport(handler))
        try:
            arguments = {"segmented": segmented, "timeout_ms": millis}
            if interaction: arguments["goal"] = {"type": "interaction", "targets": [{"x": 0, "y": 64, "z": 0}]}
            await client.call("player.navigate", arguments)
        finally:
            await client.aclose()
    asyncio.run(run())
    assert observed == [expected]

"""QA MCP protocol guards, bounded data and exact native report semantics."""

import json

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, Configuration, READ_OPERATIONS
from minecraft_codex_bridge.server import Adapter
from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_ACTION_NAMES, SEQUENCE_READ_NAMES
from qa_examples import READ_QA, ACTION_QA
from test_workflows import bridge


def test_policy_and_owned_clear_contracts_are_explicit():
    import jsonschema
    for level in ("auto", "off", "observe", "test", "manage"):
        jsonschema.validate({"action": "set", "qa": level}, TOOL_BY_NAME["mcb_policy"].schema)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate({"action": "set", "qa": "测试"}, TOOL_BY_NAME["mcb_policy"].schema)
    args = {"command": "clear", "args": ["983d4171-8cca-4bab-b19d-5d2dd88f9775"], "expectedWorld": "world"}
    jsonschema.validate(args, TOOL_BY_NAME["mcb_qa_command"].schema)
    for invalid in ([], ["latest"], ["1-1-1-1-1"]):
        with pytest.raises(jsonschema.ValidationError):
            jsonschema.validate({**args, "args": invalid}, TOOL_BY_NAME["mcb_qa_command"].schema)


@pytest.fixture
async def qa_bridge():
    state = {"calls": [], "data": {}, "protected": False, "caps": [spec.operation for spec in TOOL_BY_NAME.values() if spec.operation]}

    async def respond(request):
        body = json.loads(request.content)
        operation = body["operation"]
        state["calls"].append(body)
        if operation == "bridge.health":
            data = {"protocolVersion": 1, "sessionId": "session", "profile": "developer", "capabilities": state["caps"],
                    "background": {"enabled": state["protected"], "helperInstalled": state["protected"], "pauseOnLostFocus": not state["protected"]}}
        elif operation == "client.background":
            state["protected"] = True
            data = {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False}
        else:
            data = state["data"].get(operation, {"available": False, "reason": "public_api_unavailable"})
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": True, "data": data})

    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "qa-test-token"}), transport=httpx.MockTransport(respond))
    try:
        yield Adapter(client), state
    finally:
        await client.aclose()


@pytest.mark.parametrize("name", READ_QA)
async def test_qa_reads_preserve_native_correlation_and_long_values(qa_bridge, name):
    adapter, state = qa_bridge
    spec = TOOL_BY_NAME[name]
    data = {"available": True, "correlation": "unverified", "source": "native-global", "owned": False,
            "freshSinceStart": True, "entries": [{"quantity": "9223372036854775807", "status": "FAILED"}],
            "hasMore": True, "nextOffset": 32, "partial": True}
    state["data"][spec.operation] = data
    result = await adapter.call_tool(name, READ_QA[name])
    assert not result.isError and result.structuredContent["data"] == data
    assert not state["protected"] and spec.operation in READ_OPERATIONS
    assert name in SEQUENCE_READ_NAMES
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", spec.operation]


@pytest.mark.parametrize("name", ACTION_QA)
async def test_qa_actions_take_over_once_and_preserve_unavailable(qa_bridge, name):
    adapter, state = qa_bridge
    result = await adapter.call_tool(name, ACTION_QA[name])
    assert result.structuredContent["data"] == {"available": False, "reason": "public_api_unavailable"}
    spec = TOOL_BY_NAME[name]
    assert name in SEQUENCE_ACTION_NAMES and spec.operation not in READ_OPERATIONS
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "client.background", spec.operation]
    assert state["calls"][-1]["args"] == ACTION_QA[name]


@pytest.mark.parametrize("name,args", [
    ("mcb_qa_run", {}), ("mcb_qa_run", {"expectedWorld": "test", "selector": "x" * 257}),
    ("mcb_qa_cancel", {"expectedWorld": "test"}),
    ("mcb_qa_command", {"expectedWorld": "test", "command": "shell"}),
    ("mcb_qa_command", {"expectedWorld": "test", "command": "labels", "args": ["list\n/stop"]}),
    ("mcb_qa_command", {"expectedWorld": "test", "command": "labels", "args": ["x"] * 9}),
    ("mcb_qa_catalog", {"limit": 65}), ("mcb_qa_results", {"limit": 33}),
    ("mcb_qa_status", {"runId": ""}), ("mcb_qa_results", {"path": "arbitrary.json"}),
])
async def test_qa_invalid_requests_never_reach_client(qa_bridge, name, args):
    adapter, state = qa_bridge
    result = await adapter.call_tool(name, args)
    assert result.isError and not state["calls"]


async def test_qa_details_are_explicit_and_preserve_coverage(qa_bridge):
    adapter, state = qa_bridge
    data = {"entries": [{"status": "FAILED", "failureMessage": "Not formed", "detailsCoverage": "requested",
                         "failureTrace": "public trace", "detailsTruncated": False}]}
    state["data"]["qa.results"] = data
    result = await adapter.call_tool("mcb_qa_results", {"includeDetails": True})
    assert result.structuredContent["data"] == data
    assert state["calls"][-1]["args"] == {"includeDetails": True}
    result = await adapter.call_tool("mcb_qa_results", {"includeDetails": 1})
    assert result.isError


async def test_qa_missing_runtime_capability_never_dispatches_run(qa_bridge):
    adapter, state = qa_bridge
    state["caps"].remove("qa.run")
    result = await adapter.call_tool("mcb_qa_run", ACTION_QA["mcb_qa_run"])
    assert result.isError and result.structuredContent["error"]["original_action_executed"] is False
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]


@pytest.mark.parametrize("availability", ["capability_absent", "mod_not_loaded", "mode_off", "public_api_unavailable"])
async def test_optional_qa_does_not_block_normal_client_or_server_reads(qa_bridge, availability):
    adapter, state = qa_bridge
    if availability == "capability_absent":
        state["caps"] = [operation for operation in state["caps"] if not operation.startswith("qa.")]
    else:
        state["data"]["qa.catalog"] = {"available": False, "reason": availability}
    state["data"]["client.snapshot"] = {"worldLoaded": True, "world": {"singleplayer": False, "dimension": 0}}
    state["data"]["server.observe"] = {"ready": True, "connectionId": "server-epoch", "qaExecutionSupported": False}

    # Use the same adapter before and after the optional provider is refused.
    before = await adapter.call_tool("mcb_snapshot", {})
    assert not before.isError and before.structuredContent["data"]["worldLoaded"] is True
    optional = await adapter.call_tool("mcb_qa_catalog", {"section": "capabilities"})
    if availability == "capability_absent":
        assert optional.isError
        assert not any(call["operation"].startswith("qa.") for call in state["calls"])
    else:
        assert not optional.isError
        assert optional.structuredContent["data"] == {"available": False, "reason": availability}
    remote = await adapter.call_tool("mcb_server_observe", {"kind": "status", "expectedSessionId": "session"})
    after = await adapter.call_tool("mcb_snapshot", {})
    assert not remote.isError and remote.structuredContent["data"]["ready"] is True
    assert remote.structuredContent["data"]["qaExecutionSupported"] is False
    assert not after.isError and after.structuredContent["data"] == before.structuredContent["data"]
    assert not state["protected"]
    assert {call["operation"] for call in state["calls"]} <= {
        "bridge.health", "client.snapshot", "qa.catalog", "server.observe"}


async def test_qa_sequence_binds_fresh_ticket_without_dropping_ownership_warning(bridge):
    adapter, state = bridge
    state["data"]["qa.run"] = {"runId": "fresh-run", "state": "running", "correlation": "unverified"}
    state["data"]["qa.status"] = {"runId": "fresh-run", "nativeBatchActive": False, "state": "completion_unverified",
                                  "correlation": "unverified", "owned": False, "freshSinceStart": True}
    state["data"]["qa.results"] = {"runId": "fresh-run", "source": "native-global", "correlation": "unverified",
                                   "owned": False, "freshSinceStart": True, "entries": [{"status": "PASSED"}]}
    result = await adapter.call_tool("mcb_sequence", {
        "expectedSessionId": "session", "expectedWorld": "test-world", "steps": [
            {"id": "run", "kind": "action", "tool": "mcb_qa_run", "args": {"expectedWorld": "test-world"}},
            {"id": "wait", "kind": "wait", "tool": "mcb_qa_status", "bind": {"runId": {"step": "run", "path": "runId"}},
             "condition": {"path": "nativeBatchActive", "operator": "equals", "value": False}, "fields": ["state"]},
            {"id": "cases", "kind": "read", "tool": "mcb_qa_results", "bind": {"runId": {"step": "run", "path": "runId"}}, "fields": ["entries"]},
        ]})
    assert not result.isError and result.structuredContent["data"]["status"] == "completed"
    qa_calls = [call for call in state["calls"] if call["operation"].startswith("qa.")]
    assert [call["operation"] for call in qa_calls] == ["qa.run", "qa.status", "qa.results"]
    assert all(call["args"]["runId"] == "fresh-run" for call in qa_calls[1:])
    encoded = json.dumps(result.structuredContent)
    assert '"correlation": "unverified"' in encoded and '"owned": false' in encoded

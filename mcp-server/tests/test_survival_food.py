"""Food requests through the public MCP schema, policy gate and once-only native adapter."""

import pytest

from minecraft_codex_bridge.application.adapter import Adapter
from minecraft_codex_bridge.tools import TOOL_BY_NAME, SEQUENCE_ACTION_NAMES


APPLE = {"action": "eat", "slot": 2, "registryName": "minecraft:apple", "metadata": 0}
AXE = {"action": "healing_axe", "slot": 8, "registryName": "ExtraUtilities:defoliageAxe", "metadata": 0}


class FoodClient:
    def __init__(self, mode="survival", receipt=None):
        self.calls = []
        self.health = {"capabilities": ["survival.food", "client.background"],
                       "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False},
                       "operationPolicy": {"available": True, "selected": {"mode": mode, "readOnly": False}}}
        self.receipt = receipt if receipt is not None else {"status": "eaten", "complete": True, "foodGainVerified": True}

    async def invoke(self, op, args):
        self.calls.append((op, args))
        assert op == "bridge.health"
        return self.health

    async def call(self, op, args):
        self.calls.append((op, args))
        assert op == "survival.food"
        return self.receipt


def test_registered_single_use_and_sequence_contract():
    spec = TOOL_BY_NAME["mcb_food"]
    assert spec.operation == "survival.food" and spec.takeover and not spec.read_only and not spec.idempotent
    assert spec.name in SEQUENCE_ACTION_NAMES


@pytest.mark.parametrize("change", [{"action": "eat_all"}, {"slot": 9}, {"slot": True}, {"metadata": -1},
                                   {"registryName": ""}, {"timeout_ms": 249}, {"timeout_ms": 6501},
                                   {"repeat": True}])
async def test_invalid_food_calls_never_probe_or_take_over(change):
    client = FoodClient()
    result = await Adapter(client)._call_tool("mcb_food", {**APPLE, **change})
    assert result.isError and result.structuredContent["error"]["code"] == "INVALID_ARGUMENTS"
    assert client.calls == []


@pytest.mark.parametrize("mode", ["survival", "survival_efficient", "creative", "full"])
@pytest.mark.parametrize("args,receipt", [
    (APPLE, {"complete": True, "status": "eaten", "useSubmitted": True, "foodGainVerified": True}),
    (APPLE, {"complete": True, "status": "already_full", "useSubmitted": False, "foodGainVerified": False}),
    (AXE, {"complete": True, "status": "replenished", "useSubmitted": False, "passiveWhileSelected": True}),
    (AXE, {"complete": True, "status": "selected_at_full", "useSubmitted": False, "foodGainVerified": False}),
])
async def test_all_writable_policy_ceilings_use_original_native_receipt_once(mode, args, receipt):
    client = FoodClient(mode, receipt)
    result = await Adapter(client)._call_tool("mcb_food", args)
    assert not result.isError and result.structuredContent["data"] == receipt
    assert client.calls == [("bridge.health", {}), ("survival.food", args)]


@pytest.mark.parametrize("policy,capabilities,code", [
    ({"available": True, "selected": {"readOnly": True}}, ["survival.food", "client.background"], "POLICY_DENIED"),
    ({"available": False}, ["survival.food", "client.background"], "POLICY_DENIED"),
    ({"available": True, "selected": {"readOnly": False}}, ["client.background"], "UNSUPPORTED_CAPABILITY"),
])
async def test_readonly_unbound_or_old_mod_denies_before_takeover(policy, capabilities, code):
    client = FoodClient()
    client.health.update(operationPolicy=policy, capabilities=capabilities, background={})
    result = await Adapter(client)._call_tool("mcb_food", APPLE)
    error = result.structuredContent["error"]
    assert result.isError and error["code"] == code
    assert client.calls == [("bridge.health", {})]


@pytest.mark.parametrize("receipt", [{"status": "observation_timeout", "complete": False, "partial": True,
                                     "useSubmitted": True, "foodGainVerified": False, "replayAllowed": False},
                                    {"status": "submitted"}, {}])
async def test_partial_and_incomplete_receipts_are_preserved_without_replay(receipt):
    client = FoodClient(receipt=receipt)
    result = await Adapter(client)._call_tool("mcb_food", APPLE)
    assert result.isError and result.structuredContent["error"]["code"] == "FOOD_UNCONFIRMED"
    assert result.structuredContent["data"] == receipt
    assert client.calls == [("bridge.health", {}), ("survival.food", APPLE)]

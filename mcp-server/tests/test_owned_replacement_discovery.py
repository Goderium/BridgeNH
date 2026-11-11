"""Actual DTO-backed native replacement must survive sealed task discovery."""

import copy
import dataclasses

import pytest

from minecraft_codex_bridge.bridge import BridgeError, Configuration
from minecraft_codex_bridge.workflows import _workflow_discovery
from test_owned_replacement_consumer import CONTRACT, OP, actual_r20, anyio_backend, consumer, make_consumer

pytestmark = pytest.mark.anyio


async def discover(task, state):
    health = await task.call("bridge.health", {})
    task._workflow_discovery = _workflow_discovery(task.tracked_client, task.credentials, health)
    return health


@pytest.mark.parametrize("port_index", range(6))
@pytest.mark.parametrize("capability_form", ["list", "dict"])
async def test_actual_health_discovery_bind_dispatches_one_native_replacement(actual_r20, port_index, capability_form):
    task, state = make_consumer(actual_r20, port_index)
    if capability_form == "dict":
        state["health"]["capabilities"] = {operation: True for operation in state["health"]["capabilities"]}
    await discover(task, state)
    assert task.native_owned_replacement_available() is True
    await task.replace_ports()
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "world.inspect_many", OP]
    port = task.selected_ports[0]
    assert port["placementConfirmed"] and port["supplyBinding"]["sourceIdentityAndCountVerified"]
    assert state["calls"][-1]["args"]["expectedPlacedIdentity"]["metaTileId"] == task.materials[port["hotbarSlot"]]["item"]["metadata"]
    assert state["calls"][-1]["includeContext"]["requireBackground"] is True


@pytest.mark.parametrize("advertised", ["missing", False])
async def test_absent_or_false_discovered_capability_keeps_legacy_without_new_rpc(consumer, advertised):
    task, state = consumer
    if advertised == "missing":
        state["health"].pop("nativeOwnedBlockReplacement")
    else:
        state["health"]["nativeOwnedBlockReplacement"] = advertised
    state["health"]["nativeOwnedBlockReplacementContract"] = {"version": "not advertised"}
    await discover(task, state)
    assert task.native_owned_replacement_available() is False
    legacy = []
    async def dig(*args, **kwargs):
        legacy.append("dig")
    async def place(*args, **kwargs):
        legacy.append("place")
    task.dig_owned, task.place = dig, place
    await task.replace_ports()
    assert legacy == ["dig", "place"]
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "world.inspect_many"]


@pytest.mark.parametrize("advertised", [None, 0, 1, 1.0, "true", [], {}])
async def test_wrong_discovered_flag_type_is_not_silently_demoted(consumer, advertised):
    task, state = consumer
    state["health"]["nativeOwnedBlockReplacement"] = advertised
    with pytest.raises(BridgeError) as error:
        await discover(task, state)
    assert error.value.code == "MALFORMED_RESPONSE"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]
    assert not task.side_effects


@pytest.mark.parametrize("key", list(CONTRACT))
@pytest.mark.parametrize("fault", ["missing", "wrong_type", "wrong_value"])
async def test_true_discovery_refuses_every_invalid_contract_field_before_action(consumer, key, fault):
    task, state = consumer
    contract = state["health"]["nativeOwnedBlockReplacementContract"]
    if fault == "missing":
        contract.pop(key)
    elif fault == "wrong_type":
        contract[key] = True if type(CONTRACT[key]) is int else 1
    else:
        contract[key] = 2 if type(CONTRACT[key]) is int else False if type(CONTRACT[key]) is bool else "changed"
    with pytest.raises(BridgeError) as error:
        await discover(task, state)
    assert error.value.code == "MALFORMED_RESPONSE"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]
    assert not task.side_effects


@pytest.mark.parametrize("fault", ["missing", "null", "list", "extra"])
async def test_true_discovery_refuses_unavailable_or_extended_contract(consumer, fault):
    task, state = consumer
    if fault == "missing":
        state["health"].pop("nativeOwnedBlockReplacementContract")
    elif fault == "extra":
        state["health"]["nativeOwnedBlockReplacementContract"]["guessedOwnership"] = True
    else:
        state["health"]["nativeOwnedBlockReplacementContract"] = None if fault == "null" else []
    with pytest.raises(BridgeError) as error:
        await discover(task, state)
    assert error.value.code == "MALFORMED_RESPONSE"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]


@pytest.mark.parametrize("fault", ["operation", "context_action", "plan_read", "combined"])
async def test_discovery_retains_native_consumer_dependency_refusals_without_fallback(consumer, fault):
    task, state = consumer
    health = state["health"]
    if fault == "operation":
        health["capabilities"].remove(OP)
    elif fault == "context_action":
        health["contextRpc"]["actions"].remove(OP)
    elif fault == "plan_read":
        health["contextRpc"]["reads"].remove("player.plan_approach")
    else:
        health["worldInspectManyPlayerInventory"] = False
    await discover(task, state)
    with pytest.raises(BridgeError) as error:
        await task.replace_ports()
    assert error.value.code == "BLUEPRINT_TASK_UNAVAILABLE"
    assert error.value.details["reason"] == "native_owned_replacement_contract_unavailable"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]
    assert not task.side_effects


async def test_source_and_bound_contract_mutation_cannot_change_sealed_discovery(consumer):
    task, state = consumer
    health = await discover(task, state)
    health["nativeOwnedBlockReplacement"] = False
    health["nativeOwnedBlockReplacementContract"]["fullPlayerSlotConservation"] = False
    health["capabilities"].clear()
    discovery = task._workflow_discovery
    first = discovery.bind(task.tracked_client, task.credentials, task.args["expectedSessionId"])
    assert first["nativeOwnedBlockReplacement"] is True
    assert first["nativeOwnedBlockReplacementContract"] == CONTRACT
    first["nativeOwnedBlockReplacementContract"]["oldTilePolicy"] = "present"
    first["nativeOwnedBlockReplacement"] = False
    assert task.native_owned_replacement_available() is True
    with pytest.raises(dataclasses.FrozenInstanceError):
        discovery.owned_replacement_contract = ()
    await task.replace_ports()
    assert [call["operation"] for call in state["calls"]] == ["bridge.health", "world.inspect_many", OP]


@pytest.mark.parametrize("fault", ["seal", "client", "configuration", "url", "token", "session"])
async def test_discovered_replacement_keeps_identity_and_runtime_binding_before_rpc(consumer, fault):
    task, state = consumer
    await discover(task, state)
    if fault == "seal":
        task._workflow_discovery = dataclasses.replace(task._workflow_discovery, seal=object())
    elif fault == "client":
        task.tracked_client = task.client
    elif fault == "configuration":
        task.tracked_client.configuration = Configuration(copy.deepcopy(task.client.configuration.environ))
    elif fault == "session":
        task.args["expectedSessionId"] = "different-session"
    else:
        task.client.configuration.environ["MCB_URL" if fault == "url" else "MCB_TOKEN"] = "http://127.0.0.1:25672" if fault == "url" else "changed"
    with pytest.raises(BridgeError) as error:
        await task.replace_ports()
    assert error.value.code == "SESSION_CHANGED"
    assert [call["operation"] for call in state["calls"]] == ["bridge.health"]
    assert not task.side_effects

"""Only exact typed zero-count sync may continue a bounded fresh pure-read wait."""

import copy

import anyio
import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.conveniences import json_size
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.workflows import (
    _pending_zero_inventory_available, _validate_player_inventory_observation,
    _workflow_discovery, run_workflow,
)
from test_inspect_many_player_inventory import (
    POINT, PLAYER_ID, advertised, combined_args, combined_construction, observation,
)
from test_construction_tasks import native_construction
import test_native_observation_wait as native


def finalize(data, evidence):
    meta = data["playerInventoryObservation"]
    meta["inventoryBytes"] = json_size(data["playerInventory"])
    for _ in range(8):
        meta["actualObservationBytes"] = json_size({"data": data, "context": evidence})


def install(instance, state, *, counts=(0, None), version=1, fault=None, owners=None):
    seen = []
    def health(value):
        if version is not None:
            value["worldInspectManyPlayerInventoryContract"]["pendingZeroVersion"] = version
    state["health_mutation"] = health
    def hook(data, evidence):
        number = len(seen)
        seen.append(copy.deepcopy(data))
        inventory, meta = data["playerInventory"], data["playerInventoryObservation"]
        snapshot = evidence["snapshot"]
        snapshot["paused"] = False
        snapshot["serverControl"] = {"available": True, "source": "integrated_server_tick", "sessionId": "session",
            "worldName": "new-world", "dimension": 0, "sampleId": number + 1, "ageMillis": 0,
            "playerId": PLAYER_ID, "openContainer": {"windowId": 0, "isInventoryContainer": True}}
        count = counts[min(number, len(counts) - 1)]
        item = {"registryName": "minecraft:stone", "metadata": 0, "count": count, "hasTag": False, "displayName": "Actual stack"}
        inventory["slots"][20]["item"] = item if count is not None else None
        if version == 1:
            pending = type(count) is int and count == 0
            meta.update(pendingZeroVersion=1, pendingZero=pending, inventoryComplete=not pending)
        if owners:
            owner_data = owners(number)
            data.update(owner_data)
            for row in data["results"]:
                if "inventory" in row["data"]:
                    row["data"]["inventory"]["name"] = "Actual owner"
                    for slot in row["data"]["inventory"]["slots"]:
                        if slot["item"] is not None:
                            slot["item"].setdefault("displayName", "Actual product")
        if fault:
            fault(data, evidence, number)
        finalize(data, evidence)
    state["composite_mutation"] = hook
    return seen


def plan(*, kind="wait", args=None, timeout_ms=500):
    read = {"id": "fresh", "kind": kind, "tool": "mcb_world_inspect_many", "args": args or combined_args(),
        "fields": ["playerInventory", "playerInventoryObservation", "results"]}
    if kind == "wait":
        read.update(condition={"path": "results.0.data.registryName", "operator": "equals", "value": "minecraft:air"},
            timeout_ms=timeout_ms, interval_ms=20)
    return {"expectedSessionId": "session", "expectedWorld": "new-world", "timeout_ms": 2000,
        "steps": [{"id": "one_action", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 7}}, read,
            {"id": "later", "kind": "action", "tool": "mcb_hotbar", "args": {"slot": 1}}]}


@pytest.mark.parametrize("counts", [(0, None), (0, 1), (0, 0, None), (None,), (1,)])
async def test_pending_only_rereads_then_a_new_complete_null_or_positive_inventory_passes(combined_construction, counts):
    client, state = combined_construction
    seen = install(client, state, counts=counts)
    result = await run_workflow(client, plan(), anyio.Lock())
    assert result["status"] == "completed"
    pending = sum(count == 0 for count in counts)
    assert result["steps"][1].get("pendingZeroReads", 0) == pending
    assert len(seen) == pending + 1
    reads = [row for row in state["calls"] if row["args"].get("includePlayerInventory")]
    assert all(row["args"].get("allowPendingZero") is True for row in reads)
    assert len({row["requestId"] for row in reads}) == len(reads)
    assert sum(row["operation"] == "player.hotbar" for row in state["calls"]) == 2
    meta = result["steps"][1]["observation"]["values"]["playerInventoryObservation"]
    assert meta["inventoryComplete"] is True and meta["pendingZero"] is False


@pytest.mark.parametrize("fault", ["negative", "bool_count", "missing_count", "later_bad_slot", "bad_cursor", "wrong_actor", "server_actor", "container", "paused", "missing_pending"])
async def test_a_zero_stack_never_masks_other_invalid_full_evidence(combined_construction, fault):
    client, state = combined_construction
    def broken(data, evidence, number):
        inventory = data["playerInventory"]
        snapshot = evidence["snapshot"]
        if fault == "negative": inventory["slots"][20]["item"]["count"] = -1
        elif fault == "bool_count": inventory["slots"][20]["item"]["count"] = False
        elif fault == "missing_count": inventory["slots"][20]["item"].pop("count")
        elif fault == "later_bad_slot": inventory["slots"][40].pop("item")
        elif fault == "bad_cursor": inventory["cursorItem"] = {"count": 0}
        elif fault == "wrong_actor": inventory["playerId"] = "wrong"
        elif fault == "server_actor": snapshot["serverControl"]["playerId"] = "wrong"
        elif fault == "container": snapshot["serverControl"]["openContainer"]["windowId"] = True
        elif fault == "paused": snapshot["paused"] = True
        elif fault == "missing_pending": data["playerInventoryObservation"].pop("pendingZero")
    seen = install(client, state, fault=broken)
    result = await run_workflow(client, plan(), anyio.Lock())
    assert result["status"] == "failed" and len(seen) == 1
    assert result["steps"][1].get("pendingZeroReads", 0) == 0 and result["steps"][2]["status"] == "not_executed"
    assert sum(row["operation"] == "player.hotbar" for row in state["calls"]) == 1


@pytest.mark.parametrize("change", ["actor", "container"])
async def test_actor_or_container_cannot_change_while_the_zero_wait_is_pending(combined_construction, change):
    client, state = combined_construction
    def changed(data, evidence, number):
        if number == 1:
            if change == "actor":
                data["playerInventory"]["playerId"] = evidence["snapshot"]["player"]["playerId"] = "another"
                evidence["snapshot"]["serverControl"]["playerId"] = "another"
            else:
                evidence["snapshot"]["serverControl"]["openContainer"]["windowId"] = 1
    seen = install(client, state, fault=changed)
    result = await run_workflow(client, plan(), anyio.Lock())
    assert result["status"] == "failed" and result["error"]["code"] == "INVENTORY_CONTEXT_CHANGED"
    assert len(seen) == 2 and result["steps"][1]["pendingZeroReads"] == 1
    assert result["steps"][2]["status"] == "not_executed"


async def test_original_wait_deadline_stops_pending_zero_without_replaying_action(combined_construction):
    client, state = combined_construction
    seen = install(client, state, counts=(0,))
    result = await run_workflow(client, plan(timeout_ms=80), anyio.Lock())
    assert result["status"] == "failed" and result["error"]["code"] == "WAIT_TIMEOUT"
    assert 1 <= len(seen) <= 6 and result["steps"][1]["pendingZeroReads"] == len(seen)
    assert result["steps"][2]["status"] == "not_executed"
    assert sum(row["operation"] == "player.hotbar" for row in state["calls"]) == 1
    assert result["steps"][0]["dispatched"] is True and result["steps"][0]["requestId"]


@pytest.mark.parametrize("version", [None, 2])
async def test_old_or_unknown_version_never_receives_the_internal_option(combined_construction, version):
    client, state = combined_construction
    seen = install(client, state, version=version)
    result = await run_workflow(client, plan(), anyio.Lock())
    assert result["status"] == "failed" and len(seen) == 1
    assert all("allowPendingZero" not in row["args"] for row in state["calls"])


@pytest.mark.parametrize("version", [True, "1", 0, -1])
def test_pending_version_is_exact_integer_not_boolean_or_coerced_text(version):
    health = {"profile": "developer", **advertised(), "contextRpc": {"version": 1, "reads": ["world.inspect_many"], "actions": []}}
    health["worldInspectManyPlayerInventoryContract"]["pendingZeroVersion"] = version
    with pytest.raises(BridgeError):
        _pending_zero_inventory_available(health)


async def test_direct_sequence_read_stays_strict_and_cannot_confirm_zero(combined_construction):
    client, state = combined_construction
    seen = install(client, state)
    result = await run_workflow(client, plan(kind="read"), anyio.Lock())
    assert result["status"] == "failed" and len(seen) == 1
    assert all("allowPendingZero" not in row["args"] for row in state["calls"])
    assert result["steps"][2]["status"] == "not_executed"
    assert list(tool_validator("mcb_world_inspect_many").iter_errors({**combined_args(), "allowPendingZero": True}))


@pytest.mark.parametrize("products,word", [((1, 0), "decreased"), ((2, 3), "outside the exact finite expected delta"), ((2, 1), "decreased")])
async def test_pending_client_stack_does_not_hide_owner_quantity_drift_between_native_windows(combined_construction, products, word):
    client, state = combined_construction
    args = native.arguments()
    args.update(positions=[POINT.copy()], dimension=0)
    def owners(number):
        return native.observation(args, products=products[min(number, 1)])
    seen = install(client, state, counts=(0,), owners=owners)
    parameters = plan(args=args)
    parameters["steps"][1]["condition"] = {"path": "nativeWait.matched", "operator": "equals", "value": True}
    result = await run_workflow(client, parameters, anyio.Lock())
    assert result["status"] == "failed" and len(seen) == 2
    assert word in result["error"]["message"]
    assert result["steps"][1]["pendingZeroReads"] == 1 and result["steps"][2]["status"] == "not_executed"
    assert sum(row["operation"] == "player.hotbar" for row in state["calls"]) == 1

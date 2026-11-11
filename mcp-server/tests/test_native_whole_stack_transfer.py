"""Bounded ordinary whole-stack route; missing support chooses old route before any dispatch."""
import copy
import json
from types import SimpleNamespace

import httpx
import pytest

from minecraft_codex_bridge.bridge import BridgeClient, BridgeError, Configuration, _ContextData, _REQUEST_CONTEXT
from minecraft_codex_bridge.server import invoke_control
from minecraft_codex_bridge.whole_stack_transfer import (
    CONTAINER, OPERATION, execute, failure_fields, invoke_standalone, plan, validate_receipt,
)
from minecraft_codex_bridge.validation import tool_validator
from minecraft_codex_bridge.tools import SEQUENCE_ACTION_NAMES
from minecraft_codex_bridge.recipe_tasks import _RecipeTask
from minecraft_codex_bridge.multi_recipe_tasks import _MultiRecipeTask, _bag
from test_inspect_many_player_inventory import advertised, add_composite, PLAYER_ID


OWNER = {"x": 0, "y": 64, "z": 0, "dimension": 0, "registryName": "gregtech:gt.blockmachines", "metaTileId": 1, "metaClass": "example.Machine"}
ITEM = {"registryName": "minecraft:stone", "metadata": 4, "count": 8, "hasTag": False}


def setup(direction="player_to_machine"):
    capability = {"version": 1, "operation": OPERATION, "containerClass": CONTAINER, "hasTag": False,
        "emptyDestinationOnly": True, "serverIntermediateRequired": True, "fullNbtConservation": True,
        "nativeClicks": 2, "allPlayerSlots": 41, "maxMillis": 8000, "automaticReplayAllowed": False}
    health = {**advertised(), "profile": "developer", "capabilities": [OPERATION, "world.inspect_many"], "wholeStackTransfer": capability,
        "contextRpc": {"version": 1, "reads": ["world.inspect_many"], "actions": [OPERATION]}}
    player = {"slot": 0, "nativeContainerSlotId": 0, "playerInventoryIndex": 9, "playerInventoryBindingVerified": True,
        "machineInventoryBindingVerified": False, "inventoryBinding": "public_isHere_unique_actual_player_inventory_slot",
        "inventorySource": "current_player_inventory", "item": copy.deepcopy(ITEM) if direction == "player_to_machine" else None}
    machine = {"slot": 1, "nativeContainerSlotId": 1, "machineInventoryIndex": 1, "machineInventoryBindingVerified": True,
        "playerInventoryBindingVerified": False, "machineInventoryBinding": "public_native_slot_actual_machine_handler_identity",
        "machineInventoryOwner": copy.deepcopy(OWNER), "item": copy.deepcopy(ITEM) if direction == "machine_to_player" else None}
    gui = {"screenId": "screen", "windowId": 4, "totalSlots": 2, "truncated": False, "slots": [player, machine],
        "wholeStackTransfer": {**capability, "supported": True, "owner": copy.deepcopy(OWNER)}}
    task = SimpleNamespace(approach_health=health, args={"expectedSessionId": "session", "expectedWorld": "world"},
        opened_gui={"screenId": "screen", "windowId": 4, "side": 2}, actual_survival=False, phases=[])
    source, destination = (player, machine) if direction == "player_to_machine" else (machine, player)
    return task, gui, source, destination


def arguments(direction="player_to_machine"):
    task, gui, source, destination = setup(direction)
    return plan(task, gui, source, destination, ITEM, 8, direction, OWNER)


def snapshot():
    return {"sessionId": "session", "profile": "developer", "worldLoaded": True, "clientTicks": 10,
        "world": {"worldName": "world", "worldFolder": "world", "dimension": 0},
        "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False},
        "player": {"health": 20, "playerId": PLAYER_ID, "selectedHotbarSlot": 7}}


def receipt(args):
    return {"version": 1, "operation": OPERATION, "status": "completed", "sessionId": "session", "screenId": "screen", "windowId": 4,
        **{key: copy.deepcopy(args[key]) for key in ("direction", "owner", "sourceSlot", "destinationSlot", "playerInventoryIndex", "machineInventoryIndex")},
        "count": 8, "clicksDispatched": 2, "clicksCompleted": 2, "serverPickupConfirmed": True, "serverPlaceConfirmed": True,
        "serverConfirmed": True, "allPlayerSlots": 41, "allMachineSlots": 4, "fullNbtConservation": True, "unrelatedSlotsPreserved": True,
        "selectedHotbarSlot": 7, "cursorEmpty": True, "outcomeUnknown": False, "automaticReplayAllowed": False,
        "clientCursor": None, "serverCursor": None, "elapsedMillis": 125.0}


def after(args):
    owner = {**OWNER, "source": "integrated_server", "metadata": 0,
        "gregtech": {key: OWNER[key] for key in ("metaTileId", "metaClass")},
        "inventory": {"totalSlots": 4, "truncated": False, "slots": [{"index": index,
            "item": {**ITEM, "displayName": "Actual item"} if args["direction"] == "player_to_machine" and index == 1 else None} for index in range(4)]}}
    player = {"totalSlots": 41, "truncated": False, "cursorItem": None, "selectedHotbarSlot": 7,
        "slots": [{"index": index, "item": copy.deepcopy(ITEM) if args["direction"] == "machine_to_player" and index == 9 else None} for index in range(41)]}
    return owner, player


@pytest.mark.parametrize("direction", ["player_to_machine", "machine_to_player"])
def test_both_exact_bound_whole_stack_routes_are_publicly_typed(direction):
    args = arguments(direction)
    tool_validator("mcb_transfer_whole_stack").validate(args)
    assert args["expectedItem"] == ITEM and args["owner"] == OWNER
    assert "mcb_transfer_whole_stack" not in SEQUENCE_ACTION_NAMES  # Fixed AFTER cannot be omitted by a raw workflow action.


@pytest.mark.parametrize("fault", ["old_bridge", "old_window", "unsupported_window", "partial", "missing_guard", "missing_after", "unsupported_slot"])
def test_unsupported_route_is_chosen_before_dispatch(fault):
    task, gui, source, destination = setup()
    amount = 8
    if fault == "old_bridge": task.approach_health.pop("wholeStackTransfer")
    elif fault == "old_window": gui.pop("wholeStackTransfer")
    elif fault == "unsupported_window": gui["wholeStackTransfer"]["supported"] = False
    elif fault == "partial": amount = 4
    elif fault == "missing_guard": task.approach_health["contextRpc"]["actions"] = []
    elif fault == "missing_after": task.approach_health.pop("worldInspectManyPlayerInventory")
    elif fault == "unsupported_slot": destination["machineInventoryBindingVerified"] = False
    assert plan(task, gui, source, destination, ITEM, amount, "player_to_machine", OWNER) is None


@pytest.mark.parametrize("field,value", [("version", True), ("version", 2), ("nativeClicks", True), ("nativeClicks", 3),
    ("maxMillis", 8001), ("allPlayerSlots", 40), ("hasTag", True), ("fullNbtConservation", False),
    ("serverIntermediateRequired", False), ("automaticReplayAllowed", True), ("containerClass", "example.Lookalike")])
def test_malformed_advertised_contract_never_silently_selects_old_route(field, value):
    task, gui, source, destination = setup()
    task.approach_health["wholeStackTransfer"][field] = value
    with pytest.raises(BridgeError): plan(task, gui, source, destination, ITEM, 8, "player_to_machine", OWNER)


@pytest.mark.parametrize("fault", ["owner", "native_id", "tagged", "duplicate_player", "duplicate_machine", "screen", "window", "face", "truncated"])
def test_changed_bound_current_window_is_refused_before_native_click(fault):
    task, gui, source, destination = setup()
    if fault == "owner": gui["wholeStackTransfer"]["owner"]["metaTileId"] += 1
    elif fault == "native_id": source["nativeContainerSlotId"] = 99
    elif fault == "tagged": source["item"]["hasTag"] = True
    elif fault == "duplicate_player": gui["slots"].append(copy.deepcopy(source))
    elif fault == "duplicate_machine": gui["slots"].append(copy.deepcopy(destination))
    elif fault == "screen": gui["screenId"] = "changed"
    elif fault == "window": gui["windowId"] += 1
    elif fault == "face": task.opened_gui["side"] = True
    elif fault == "truncated": gui["truncated"] = True
    with pytest.raises(BridgeError): plan(task, gui, source, destination, ITEM, 8, "player_to_machine", OWNER)


@pytest.mark.parametrize("field,value", [("clicksDispatched", True), ("clicksDispatched", 1), ("clicksCompleted", 1),
    ("serverPickupConfirmed", False), ("serverPlaceConfirmed", False), ("serverConfirmed", False), ("allPlayerSlots", 40),
    ("fullNbtConservation", False), ("unrelatedSlotsPreserved", False), ("cursorEmpty", False), ("clientCursor", ITEM),
    ("serverCursor", ITEM), ("outcomeUnknown", True), ("automaticReplayAllowed", True), ("elapsedMillis", float("nan")),
    ("elapsedMillis", 8001), ("selectedHotbarSlot", True), ("allMachineSlots", 257)])
def test_receipt_needs_real_intermediate_final_and_complete_typed_conservation(field, value):
    args = arguments(); value_receipt = receipt(args); value_receipt[field] = value
    with pytest.raises(BridgeError): validate_receipt(value_receipt, args)


@pytest.mark.parametrize("direction", ["player_to_machine", "machine_to_player"])
@pytest.mark.parametrize("fault", [None, "transport", "receipt", "before", "after_missing", "after_owner", "after_cursor", "after_selection", "after_coverage", "after_machine_count", "after_source", "after_machine_coverage"])
async def test_internal_native_dispatch_has_one_fixed_after_and_never_falls_back(direction, fault):
    task = setup(direction)[0]; args = arguments(direction); calls = []
    owner, player = after(args)
    async def call(operation, actual_args):
        calls.append(operation); assert operation == OPERATION and actual_args == args
        assert _REQUEST_CONTEXT.get()["requireBackground"] is True
        if fault == "transport": raise BridgeError("TRANSPORT_ERROR", "Unknown native pickup", outcome_unknown=True)
        actual_receipt = receipt(args)
        if fault == "receipt": actual_receipt["serverPickupConfirmed"] = False
        evidence = {"version": 1, "operation": OPERATION, "phase": "before", "snapshot": snapshot()}
        if fault == "before": evidence["phase"] = "after"
        return _ContextData(actual_receipt, evidence)
    async def read_owner_phase(positions):
        calls.append("fixed_after"); assert positions == [{key: OWNER[key] for key in ("x", "y", "z", "dimension")}]
        if fault == "after_missing": return None
        if fault == "after_owner": owner["gregtech"]["metaTileId"] += 1
        if fault == "after_cursor": player["cursorItem"] = copy.deepcopy(ITEM)
        if fault == "after_selection": player["selectedHotbarSlot"] = 6
        if fault == "after_coverage": player["slots"].pop(); player["totalSlots"] -= 1
        if fault == "after_machine_coverage": owner["inventory"]["slots"].pop(); owner["inventory"]["totalSlots"] -= 1
        if fault == "after_machine_count":
            destination = owner["inventory"]["slots"][1] if direction == "player_to_machine" else player["slots"][9]
            destination["item"]["count"] -= 1
        if fault == "after_source":
            source = player["slots"][9] if direction == "player_to_machine" else owner["inventory"]["slots"][1]
            source["item"] = copy.deepcopy(ITEM)
        return [owner], player
    task.call, task.read_owner_phase, task.validate_snapshot = call, read_owner_phase, lambda value: None
    if fault:
        with pytest.raises(BridgeError) as error: await execute(task, args)
        assert error.value.details["outcome_unknown"] is True
        assert task.phases[-1]["status"] == "failed"
    else:
        actual_owner, actual_player = await execute(task, args)
        assert actual_owner == owner and actual_player == player and task.phases[-1]["status"] == "completed"
    assert calls.count(OPERATION) == 1 and calls.count("fixed_after") == (0 if fault in {"transport", "receipt", "before"} else 1)


@pytest.mark.parametrize("dead_after", [False, True])
async def test_standalone_public_transfer_requires_its_fixed_actual_after(dead_after):
    args = arguments(); health = setup()[0].approach_health; calls = []
    class Client:
        async def call(self, operation, actual_args):
            calls.append(operation)
            if operation == OPERATION:
                return _ContextData(receipt(args), {"version": 1, "operation": OPERATION, "phase": "before", "snapshot": snapshot()})
            assert operation == "world.inspect_many"
            owner, player = after(args)
            data = {"source": "integrated_server", "dimension": 0, "results": [{"ok": True, "data": owner}]}
            after_snapshot = snapshot()
            if dead_after: after_snapshot["player"]["health"] = 0
            evidence = add_composite(data, after_snapshot, player, actual_args)
            return _ContextData(data, evidence)
    if dead_after:
        with pytest.raises(BridgeError) as error: await invoke_standalone(Client(), args, health)
        assert error.value.details["outcome_unknown"] is True
    else:
        result = await invoke_standalone(Client(), args, health)
        assert result["serverPickupConfirmed"] is True
    assert calls == [OPERATION, "world.inspect_many"]


@pytest.mark.parametrize("background_failed", [False, True])
async def test_standalone_control_takes_current_background_before_native_dispatch(background_failed):
    args = arguments(); health = setup()[0].approach_health; calls = []
    health["capabilities"].append("client.background")
    health["background"] = {"enabled": False, "helperInstalled": False, "pauseOnLostFocus": True}
    class Client:
        async def invoke(self, operation, actual_args):
            calls.append(operation); assert operation == "bridge.health"; return health
        async def call(self, operation, actual_args):
            calls.append(operation)
            if operation == "client.background":
                if background_failed: raise BridgeError("TAKEOVER_REFUSED", "Protection unavailable")
                return snapshot()["background"]
            if operation == OPERATION:
                return _ContextData(receipt(args), {"version": 1, "operation": OPERATION, "phase": "before", "snapshot": snapshot()})
            assert operation == "world.inspect_many"
            owner, player = after(args)
            data = {"source": "integrated_server", "dimension": 0, "results": [{"ok": True, "data": owner}]}
            return _ContextData(data, add_composite(data, snapshot(), player, actual_args))
    if background_failed:
        with pytest.raises(BridgeError) as error: await invoke_control(Client(), OPERATION, args)
        assert error.value.details["original_action_executed"] is False
        assert calls == ["bridge.health", "client.background"]
    else:
        await invoke_control(Client(), OPERATION, args)
        assert calls == ["bridge.health", "client.background", OPERATION, "world.inspect_many"]


@pytest.mark.parametrize("dispatched,completed", [(0, 0), (1, 0), (1, 1), (2, 1), (2, 2)])
def test_failure_checkpoint_preserves_last_observed_cursor_without_claiming_current(dispatched, completed):
    details = {"stage": "pickup", "clicksDispatched": dispatched, "clicksCompleted": completed,
        "outcome_unknown": dispatched > 0, "automaticReplayAllowed": False, "cursorCleared": False,
        "knownReceipt": {"clientCursorObserved": True, "serverCursorObserved": False, "clientCursor": copy.deepcopy(ITEM),
            "clientCursorStage": "pickup", "cursorObservationScope": "last_observed_native"}}
    assert failure_fields(details)["knownReceipt"]["clientCursor"] == ITEM
    details["cursorCleared"] = True
    with pytest.raises(ValueError): failure_fields(details)


@pytest.mark.parametrize("fault", [None, "missing_cursor", "contradictory_count", "cleared"])
async def test_transport_retains_native_failure_checkpoint_and_never_replays(fault):
    calls = []
    checkpoint = {"stage": "server_pickup_confirmation", "clicksDispatched": 1, "clicksCompleted": 1,
        "outcome_unknown": True, "automaticReplayAllowed": False, "cursorCleared": False,
        "knownReceipt": {"clientCursorObserved": True, "serverCursorObserved": True, "clientCursor": ITEM,
            "serverCursor": None, "clientCursorStage": "pickup", "serverCursorStage": "server_before", "cursorObservationScope": "last_observed_native"}}
    if fault == "missing_cursor": checkpoint["knownReceipt"].pop("clientCursor")
    elif fault == "contradictory_count": checkpoint["clicksCompleted"] = 2
    elif fault == "cleared": checkpoint["cursorCleared"] = True
    async def respond(request):
        body = json.loads(request.content); calls.append(body["operation"])
        return httpx.Response(200, json={"requestId": body["requestId"], "ok": False,
            "error": {"code": "whole_stack_context_changed", "message": "Native state changed after pickup", **checkpoint}})
    client = BridgeClient(Configuration({"MCB_URL": "http://127.0.0.1:25671", "MCB_TOKEN": "whole-stack-test"}), transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BridgeError) as error: await client.call(OPERATION, arguments())
        assert error.value.details["outcome_unknown"] is True and calls == [OPERATION]
        if fault: assert error.value.code == "MALFORMED_RESPONSE"
        else: assert error.value.details["knownReceipt"]["clientCursor"] == ITEM and error.value.details["clicksDispatched"] == 1
    finally:
        await client.aclose()


@pytest.mark.parametrize("direction", ["player_to_machine", "machine_to_player"])
async def test_real_recipe_feed_and_retrieval_branches_reuse_the_one_fixed_after(direction):
    task, gui, source, destination = setup(direction)
    args = arguments(direction); owner_after, player_after = after(args); calls = []
    gui = _ContextData(gui, {"version": 1, "operation": "gui.inspect", "phase": "after", "snapshot": snapshot()})
    before_owner = copy.deepcopy(owner_after); before_player = copy.deepcopy(player_after)
    if direction == "player_to_machine":
        before_player["slots"][9]["item"] = copy.deepcopy(ITEM)
        before_owner["inventory"]["slots"][1]["item"] = None
    else:
        before_player["slots"][9]["item"] = None
        before_owner["inventory"]["slots"][1]["item"] = copy.deepcopy(ITEM)
    async def call(operation, actual_args):
        calls.append(operation)
        if operation == "inventory.inspect": return before_player
        assert operation == OPERATION
        return _ContextData(receipt(args), {"version": 1, "operation": OPERATION, "phase": "before", "snapshot": snapshot()})
    async def read_owner_phase(positions):
        calls.append("owner_phase")
        if direction == "machine_to_player" and OPERATION not in calls: return [before_owner], before_player
        return [owner_after], player_after
    async def current_gui(name): return gui
    async def workflow(*args, **kwargs): raise AssertionError("Eligible whole-stack transfer must not dispatch the legacy click/wait sequence")
    async def validate_player(player): return None
    task.call, task.read_owner_phase, task.validate_snapshot = call, read_owner_phase, lambda value: None
    task.reuse_gui = task.open_gui = current_gui
    task.workflow = workflow
    task.input_gui_binding = task.catalyst_gui_binding = None
    task.input_gui_slot, task.catalyst_gui_slot = 1, None
    task.single_inventory = False
    task.protected_player_indices = lambda: set()
    task.ordinary_source_slots = lambda slots, **kwargs: [row for row in slots if row.get("playerInventoryBindingVerified", "index" in row) is True]
    if direction == "player_to_machine":
        await _RecipeTask.transfer(task, ITEM, 8, 1, "material", close_after=False)
        assert calls == ["inventory.inspect", OPERATION, "owner_phase"]
    else:
        task.position = lambda name: {key: OWNER[key] for key in ("x", "y", "z", "dimension")}
        task.multi_output_owner = task.input_inventory_owner = OWNER
        task.validate_single_tanks = lambda value: None
        task.output_source = lambda *args, **kwargs: source
        task.fluid_resources = SimpleNamespace(player_indices=set(), validate_player=validate_player)
        task.item_bag = lambda slots, **kwargs: _bag(slots)
        task.retrievals = []
        await _MultiRecipeTask.retrieve_product(task, ITEM, 8, 0)
        assert calls == ["owner_phase", OPERATION, "owner_phase"] and task.retrievals[0]["count"] == 8
    assert task.phases[-1]["status"] == "completed"

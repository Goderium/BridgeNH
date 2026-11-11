"""AE goal failure boundaries, native dispatch cardinality and exact identity."""
from copy import deepcopy
import anyio
import pytest

from minecraft_codex_bridge import ae_orders, ae_tasks
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate, invoke_control
from minecraft_codex_bridge.placement_attachment import CONTRACT
from test_workflows import bridge, operations

COMMON = {"expectedSessionId": "session", "expectedWorld": "test-world", "dimension": 0}
TARGET = {"x": 1, "y": 64, "z": 2, "targetIdentity": {"registryName": "ae:host", "metadata": 0}, "allowedSides": [5]}
OWNER = {"x": 1, "y": 64, "z": 2, "dimension": 0}
ITEM = {"registryName": "mod:output", "metadata": 7}
START = {**COMMON, "action": "start", "requestId": "order-1", "terminal": TARGET, "item": ITEM, "amount": 3}
RESUME = {**COMMON, "action": "resume", "requestId": "order-1"}
RESOURCE = {"item": {**ITEM, "hasTag": False}, "resourceId": "ae:7", "identityNbt": "{exact}", "identityTruncated": False, "craftable": True, "amount": "3"}
PORTABLE = {"kind": "portable", "playerId": "player", "dimension": 0, "playerSlot": 3,
            "registryName": "ae:wireless", "metadata": 0, "networkKey": "native-key"}


@pytest.mark.parametrize("change", [{}, {"dimension": 1}, {"networkKey": "other"}, {"playerSlot": 4}, {"playerId": "other"}])
def test_portable_owner_exact_binding(change):
    task = ae_tasks.AeTask(None, COMMON, None)
    if change:
        with pytest.raises(BridgeError, match="Portable"):
            task.verify_owner({"state": {"owner": {**PORTABLE, **change}}}, {"portable": PORTABLE})
    else:
        task.verify_owner({"state": {"owner": PORTABLE}}, {"portable": PORTABLE})
    _validate("mcb_ae_order", {**START, "terminal": {"portable": PORTABLE}})
    with pytest.raises(BridgeError):
        _validate("mcb_ae_order", {**START, "terminal": {"portable": {**PORTABLE, "playerSlot": 9}}})


@pytest.mark.parametrize("failure", [None, "old_bridge", "wrong_item", "blocked_ray"])
async def test_portable_open_uses_one_native_use_and_no_world_approach(failure):
    calls = []
    class Portable(ae_orders.OrderTask):
        async def step(self, tool, args=None, **kwargs):
            _validate(tool, args or {})
            calls.append((tool, args or {}))
            if tool == "mcb_inventory": return {"cursorItem": None, "slots": [{"index": 3, "item": {**PORTABLE, "count": 2 if failure == "wrong_item" else 1}}]}
            if tool == "mcb_snapshot": return {"player": {"yaw": 71}, "target": {"type": "block" if failure == "blocked_ray" else "miss"}}
            if tool == "mcb_gui": return {"screenOpen": True, "screenId": "portable", "windowId": 6}
            if tool == "mcb_ae": return {"state": {"owner": PORTABLE}}
        async def world(self, target): pytest.fail("portable owner is not a world position")
        async def approach(self, *a, **kw): pytest.fail("opening a portable terminal requires no walking")
    task = Portable(None, {**START, "terminal": {"portable": PORTABLE}}, None, {})
    task.health = {"aePortableOwnerVersion": 0 if failure == "old_bridge" else 1}
    if failure:
        with pytest.raises(BridgeError): await task.terminal()
        assert not any(name == "mcb_interact" for name, _ in calls)
    else:
        assert (await task.terminal())["windowId"] == 6
        uses = [args for name, args in calls if name == "mcb_interact"]
        assert uses == [{"action": "use", "expectedHeld": {"slot": 3, "registryName": "ae:wireless", "metadata": 0}}]


@pytest.mark.parametrize("name,args", [
    ("mcb_ae_configure", {**COMMON, "targets": [{"target": TARGET, "priority": -10, "settings": {"BLOCK": "YES"}}]}),
    ("mcb_ae_install", {**COMMON, "targets": [{"attachment": TARGET, "hotbarSlot": 0, "expectedPart": {"registryName": "ae:part", "metadata": 1, "side": 5}, "placement": "host"}]}),
    ("mcb_ae_order", START), ("mcb_ae_order", RESUME),
    ("mcb_ae_action", {"screenId": "s", "action": "priority_set", "priority": -2147483648}),
    ("mcb_ae_action", {"screenId": "s", "action": "host_setting", "setting": "BLOCK", "value": "YES", "expectedValue": "NO"}),
])
def test_goal_schemas(name, args):
    _validate(name, args)


@pytest.mark.parametrize("args", [
    {**START, "amount": 0}, {**START, "amount": 65}, {**START, "amount": True},
    {**START, "timeout_ms": 60001}, {**RESUME, "item": ITEM}, {**START, "requestId": ""},
])
def test_invalid_orders(args):
    with pytest.raises(BridgeError): _validate("mcb_ae_order", args)


async def test_real_guarded_workflow_preserves_empty_slot_indices(bridge):
    adapter, state = bridge
    state["data"]["inventory.inspect"] = {"slots": [{"index": 0, "item": {"count": 1}}, {"index": 5, "item": None}], "cursorItem": None}
    task = ae_tasks.AeTask(adapter.client, COMMON, invoke_control)
    await task.empty_hand()
    assert [c["args"] for c in state["calls"] if c["operation"] == "player.hotbar"] == [{"slot": 5}]


async def test_old_bridge_stops_before_takeover(bridge):
    adapter, state = bridge
    result = await adapter.call_tool("mcb_ae_configure", {**COMMON, "targets": [{"target": TARGET, "priority": 1}]})
    assert result.isError
    assert result.structuredContent["error"]["code"] == "POLICY_UNAVAILABLE"
    assert operations(state) == ["bridge.health"]


async def test_batch_failure_keeps_completed_targets(monkeypatch):
    async def initialize(self): pass
    async def configure(self, declaration):
        if declaration["priority"] == 2: raise BridgeError("owner_changed", "fixture")
        return {"after": declaration["priority"]}
    monkeypatch.setattr(ae_tasks.AeTask, "initialize", initialize)
    monkeypatch.setattr(ae_tasks.AeTask, "configure", configure)
    result = await ae_tasks.run_configure(None, {**COMMON, "targets": [{"priority": n} for n in (1, 2, 3)]}, anyio.Lock(), None)
    assert result["completed"] == [{"after": 1}] and result["failedIndex"] == 1
    assert result["replayAllowed"] is False


async def test_priority_needs_authoritative_readback():
    class Config(ae_tasks.AeTask):
        sent = []
        async def configuration(self, target): return {"priority": 0, "settings": {}}
        async def approach(self, *a, **kw): pass
        async def gui(self): return {"screenId": "s", "screen": "appeng.client.gui.implementations.GuiPriority"}
        async def ae(self, *a): return {"state": {"owner": OWNER}}
        async def step(self, tool, args=None, **kw):
            _validate(tool, args or {})
            self.sent.append(args)
        async def wait(self, reader, predicate, **kw):
            value = await reader()
            if not predicate(value): raise BridgeError("AE_OBSERVATION_TIMEOUT", "not saved")
            return value
    task = Config(None, COMMON, None)
    with pytest.raises(BridgeError, match="not saved"):
        await task.configure({"target": TARGET, "priority": 100})
    assert [s["action"] for s in task.sent] == ["priority_open", "priority_set"]


@pytest.fixture
def orders(monkeypatch):
    state = {"actions": [], "native_calls": [], "native_state": "crafted", "lose_start": False, "lose_take": False,
             "simulation": False, "inventory_full": False, "changed_preview": False, "unknown_order": False, "records": {}}
    class Fixture(ae_orders.OrderTask):
        async def initialize(self): pass
        async def terminal(self):
            if state.get("return_failed") and self.record["phase"] == "collecting":
                raise BridgeError("APPROACH_ARRIVAL_UNCONFIRMED", "return stopped")
            return {"screenId": "terminal", "windowId": 1}
        async def resource(self, gui): return deepcopy(RESOURCE)
        async def gui(self):
            screen = "GuiCraftConfirm" if "craft_request" in state["actions"] else "GuiCraftAmount"
            return {"screenId": screen, "screen": "appeng.client.gui.implementations." + screen, "windowId": 2}
        async def ae(self, screen, scope):
            if scope == "owner": return {"state": {"owner": OWNER}}
            resource = {**RESOURCE, "amount": "4" if state["changed_preview"] else "3"}
            return {"state": {"startReady": not state["simulation"], "simulation": state["simulation"],
                "bytesUsed": "50", "requested": resource, "selectedCpuSerial": 8}, "entries": [{"section": "missing"}]}
        async def step(self, tool, args=None, **kw):
            _validate(tool, args or {})
            if kw.get("action"): state["actions"].append(args["action"])
            if tool == "mcb_inventory":
                assert args == {"includeNbt": True}
                state["inventory_reads"] = state.get("inventory_reads", 0) + 1
                return state.get("inventory", {"truncated": False, "cursorItem": None,
                    "slots": [{"index": 10, "item": {**ITEM, "count": 3, "hasTag": False}}]})
        async def native(self, action, **extra):
            state["native_calls"].append(action)
            if action == "status" and state["unknown_order"]: raise BridgeError("unknown_order", "no native receipt")
            if action == "start" and state["lose_start"]: raise BridgeError("TRANSPORT_ERROR", "response lost")
            if action == "status" and state["native_state"] == "running": raise BridgeError("AE_GOAL_TIMEOUT", "still running")
            if action == "take":
                if state["inventory_full"]: raise BridgeError("inventory_full", "empty slot needed")
                state["native_state"] = "completed"
                if state["lose_take"]: raise BridgeError("TRANSPORT_ERROR", "response lost")
            return {"state": state["native_state"], "craftingId": "actual-link", "delivery": {
                "slot": 10, "amount": 3, "serverConfirmed": True, "fullNbtConservation": True,
                "cursorEmpty": True, "item": {**ITEM, "count": 3, "hasTag": False}}}
    monkeypatch.setattr(ae_orders, "OrderTask", Fixture)
    async def run(args=START):
        _validate("mcb_ae_order", args)
        return await ae_orders.run_order(None, args, anyio.Lock(), None, state["records"])
    return state, run


async def test_order_completes_and_duplicate_id_never_replays(orders):
    state, run = orders
    assert (await run())["status"] == "completed"
    assert (await run())["status"] == "completed"
    assert state["actions"] == ["craft_open", "craft_request"]
    assert state["native_calls"] == ["start", "status", "take"]
    assert state["inventory_reads"] == 1


@pytest.mark.parametrize("inventory", [
    {"truncated": False, "cursorItem": None, "slots": []},
    {"truncated": False, "cursorItem": ITEM, "slots": [{"index": 10, "item": {**ITEM, "count": 3, "hasTag": False}}]},
    {"truncated": False, "cursorItem": None, "slots": [{"index": 10, "item": {**ITEM, "count": 2, "hasTag": False}}]},
    {"truncated": False, "cursorItem": None, "slots": [{"index": 10, "item": {**ITEM, "count": 3, "hasTag": True, "nbt": "{}"}}]},
])
async def test_delivery_sync_timeout_resumes_without_another_pickup(orders, monkeypatch, inventory):
    state, run = orders
    original_wait = ae_orders.OrderTask.wait
    async def bounded(self, reader, predicate, **kw):
        if self.record["phase"] != "delivery_sync":
            return await original_wait(self, reader, predicate, **kw)
        observed = await reader()
        if not predicate(observed):
            raise BridgeError("AE_OBSERVATION_TIMEOUT", "client pending")
        return observed
    monkeypatch.setattr(ae_orders.OrderTask, "wait", bounded)
    state["inventory"] = inventory
    result = await run()
    assert result["status"] == "delivery_sync" and result["resumable"]
    assert result["native"]["state"] == "completed" and "clientDelivery" not in result
    state.pop("inventory")
    result = await run(RESUME)
    assert result["status"] == "completed" and result["clientDelivery"]["confirmed"]
    assert state["native_calls"] == ["start", "status", "take"]


async def test_delivery_wait_retains_one_second_read_cadence(orders, monkeypatch):
    state, run = orders
    state["inventory"] = {"truncated": False, "cursorItem": None, "slots": []}
    sleeps = []
    async def tick(seconds):
        sleeps.append(seconds)
        state.pop("inventory")
    monkeypatch.setattr(ae_orders.anyio, "sleep", tick)
    assert (await run())["status"] == "completed"
    assert sleeps == [1.0] and state["inventory_reads"] == 2


@pytest.mark.parametrize("nbt,truncated,accepted", [
    ('{nested:{b:2,a:1},seq:[0:1b,1:2b]}', False, True),
    ('{seq:[0:1b,1:2b],nested:{a:1,b:2}}', False, True),
    ('{seq:[0:2b,1:1b],nested:{a:1,b:2}}', False, False),
    ('{seq:[0:1b,1:2b],nested:{a:1L,b:2}}', False, False),
    ('{}', False, False),
    ('{seq:[0:1b,1:2b],nested:{a:1,b:2}}', True, False),
])
async def test_delivery_full_tag_comparison_preserves_types_lists_and_truncation(nbt, truncated, accepted):
    task = object.__new__(ae_orders.OrderTask)
    expected = {**ITEM, "count": 3, "hasTag": True, "nbt": '{seq:[0:1b,1:2b],nested:{a:1,b:2}}'}
    task.args = {"amount": 3}
    task.record = {"phase": "delivery_sync", "native": {"delivery": {
        "slot": 10, "amount": 3, "cursorEmpty": True, "item": expected}}}
    calls = []
    async def step(tool, args, **kw):
        calls.append((tool,args))
        return {"truncated":False,"cursorItem":None,"slots":[{"index":10,"item":{**expected,"nbt":nbt,"nbtTruncated":truncated}}]}
    async def wait(reader, predicate):
        if not predicate(await reader()): raise BridgeError("AE_OBSERVATION_TIMEOUT","client pending")
    task.step, task.wait = step, wait
    if accepted:
        await task.confirm_delivery()
        assert task.record["phase"] == "completed" and task.record["clientDelivery"]["fullNbtCompared"]
    else:
        with pytest.raises(BridgeError): await task.confirm_delivery()
        assert task.record["phase"] == "delivery_sync" and "clientDelivery" not in task.record
    assert calls == [("mcb_inventory", {"includeNbt": True})]


@pytest.mark.parametrize("failure", ["lose_start", "lose_take", "inventory_full"])
async def test_resume_never_duplicates_submission_or_successful_pickup(orders, failure):
    state, run = orders
    state[failure] = True
    assert (await run())["resumable"]
    state[failure] = False
    result = await run(RESUME)
    assert result["status"] == "completed"
    assert state["native_calls"].count("start") == 1
    assert state["native_calls"].count("take") == (2 if failure == "inventory_full" else 1)


async def test_pending_job_is_resumed_without_recalculation(orders):
    state, run = orders
    state["native_state"] = "running"
    assert (await run())["resumable"]
    state["native_state"] = "crafted"
    assert (await run(RESUME))["status"] == "completed"
    assert state["actions"].count("craft_request") == 1


async def test_failed_return_preserves_crafted_order_and_resume_never_resubmits(orders):
    state, run = orders
    state["return_failed"] = True
    result = await run()
    assert result["status"] == "collecting" and result["resumable"]
    assert result["native"]["state"] == "crafted" and "take" not in state["native_calls"]
    state["return_failed"] = False
    assert (await run(RESUME))["status"] == "completed"
    assert state["native_calls"] == ["start", "status", "status", "take"]


@pytest.mark.parametrize("flag", ["simulation", "changed_preview"])
async def test_missing_inputs_or_changed_preview_never_start(orders, flag):
    state, run = orders
    state[flag] = True
    result = await run()
    assert result["status"] != "completed" and not state["native_calls"]


async def test_conflicting_request_id_cannot_change_product(orders):
    state, run = orders
    await run()
    with pytest.raises(BridgeError, match="different goal"):
        await run({**START, "amount": 4})
    assert state["native_calls"].count("start") == 1


async def test_unknown_resume_never_starts(orders):
    state, run = orders
    with pytest.raises(BridgeError, match="absent"):
        await run(RESUME)
    assert not state["actions"] and not state["native_calls"]


async def test_absent_native_receipt_after_uncertain_start_is_not_resubmitted(orders):
    state, run = orders
    state["lose_start"] = True
    await run()
    state["unknown_order"] = True
    result = await run(RESUME)
    assert result["status"] == "start_unknown" and not result["resumable"]
    await run(RESUME)
    assert state["native_calls"].count("start") == 1


@pytest.mark.parametrize("craft_seconds,expected_reads", [(15, 3), (30, 6)])
async def test_completion_wait_uses_bounded_windows_instead_of_fast_polls(craft_seconds, expected_reads):
    elapsed, calls = [0.0], []

    class Client:
        async def invoke(self, operation, args):
            assert operation == "ae.order" and args["action"] == "status"
            assert all(args[k] == START[k] for k in (*COMMON, "requestId"))
            calls.append(args)
            elapsed[0] = min(craft_seconds, elapsed[0] + args["waitMillis"] / 1000)
            return {"state": "crafted" if elapsed[0] >= craft_seconds else "running",
                    "statusWait": {"version": 1, "requestedMillis": args["waitMillis"],
                                   "windowExpired": True, "finalStateFresh": True}}

    task = ae_orders.OrderTask(Client(), {**START, "timeout_ms": 60000}, None, {})
    task.health = {"aeOrderStatusWaitVersion": 1}
    task.remaining = lambda: int((60 - elapsed[0]) * 1000)
    assert (await task.wait_for_order())["state"] == "crafted"
    assert len(calls) == expected_reads and task.actions == 0
    assert all(c["waitMillis"] == 5000 for c in calls)


async def test_native_wait_reserves_outer_deadline_and_retains_pending_receipt():
    elapsed, windows = [0], []

    class Client:
        async def invoke(self, operation, args):
            windows.append(args["waitMillis"])
            elapsed[0] += args["waitMillis"]
            return {"state": "running", "craftingId": "bound-link", "statusWait": {
                "version": 1, "requestedMillis": args["waitMillis"], "windowExpired": True, "finalStateFresh": True}}

    task = ae_orders.OrderTask(Client(), START, None, {})
    task.health = {"aeOrderStatusWaitVersion": 1}
    task.remaining = lambda: 3500 - elapsed[0]
    with pytest.raises(BridgeError) as error:
        await task.wait_for_order()
    assert error.value.code == "AE_GOAL_TIMEOUT"
    assert windows == [2000, 0] and task.record["native"]["craftingId"] == "bound-link"
    assert task.actions == 0


@pytest.mark.parametrize("version", [None, True, "1", 0])
async def test_legacy_status_fallback_is_bounded_and_lower_frequency(monkeypatch, version):
    calls, sleeps = [], []

    class Client:
        async def invoke(self, operation, args):
            calls.append(args)
            assert "waitMillis" not in args
            return {"state": "running" if len(calls) == 1 else "crafted"}

    async def sleep(seconds):
        sleeps.append(seconds)

    monkeypatch.setattr(ae_orders.anyio, "sleep", sleep)
    task = ae_orders.OrderTask(Client(), START, None, {})
    task.health = {"aeOrderStatusWaitVersion": version}
    task.remaining = lambda: 200
    assert (await task.wait_for_order())["state"] == "crafted"
    assert sleeps == [.2] and len(calls) == 2


@pytest.mark.parametrize("response", [{}, {"statusWait": {"version": 1, "requestedMillis": 5000,
    "windowExpired": False, "finalStateFresh": True}}])
async def test_unconfirmed_native_wait_cannot_create_a_busy_loop(response):
    calls = []

    class Client:
        async def invoke(self, operation, args):
            calls.append(args)
            return {"state": "running", **response}

    task = ae_orders.OrderTask(Client(), START, None, {})
    task.health = {"aeOrderStatusWaitVersion": 1}
    with pytest.raises(BridgeError) as error:
        await task.wait_for_order()
    assert error.value.code == "AE_WAIT_UNCONFIRMED" and len(calls) == 1


@pytest.mark.parametrize("state", ["crafted", "completed", "canceled", "start_unknown", "take_unknown"])
async def test_wait_returns_terminal_states_without_an_extra_sleep(monkeypatch, state):
    calls = []

    class Client:
        async def invoke(self, operation, args):
            calls.append(args)
            return {"state": state}

    async def forbidden_sleep(seconds):
        pytest.fail("Native terminal events must return immediately")

    monkeypatch.setattr(ae_orders.anyio, "sleep", forbidden_sleep)
    task = ae_orders.OrderTask(Client(), START, None, {})
    task.health = {"aeOrderStatusWaitVersion": 1}
    assert (await task.wait_for_order())["state"] == state
    assert len(calls) == 1 and task.actions == 0


@pytest.mark.parametrize("mode", ["valid", "ambiguous", "truncated", "stalled"])
async def test_exact_terminal_scan_requires_complete_unique_resource(mode):
    class Scan(ae_orders.OrderTask):
        async def step(self, tool, args=None, **kw):
            _validate(tool, args)
            if tool == "mcb_ae":
                return {"entries": [RESOURCE] * (2 if mode == "ambiguous" else 1),
                        "nextOffset": 0, "hasMore": mode == "stalled", "scanTruncated": mode == "truncated"}
    task = Scan(None, START, None, {})
    if mode == "valid": assert (await task.resource({"screenId": "s"}))["resourceId"] == "ae:7"
    else:
        with pytest.raises(BridgeError): await task.resource({"screenId": "s"})


def test_untagged_does_not_match_tagged_or_truncated_output():
    assert ae_orders.matches({**ITEM, "hasTag": False}, ITEM)
    assert not ae_orders.matches({**ITEM, "hasTag": True, "nbt": "{}"}, ITEM)
    assert not ae_orders.matches({**ITEM, "hasTag": True, "nbt": "{a:1}", "nbtTruncated": True}, {**ITEM, "nbt": "{a:1}"})


@pytest.mark.parametrize("placement,side,x", [("adjacent", 4, 2), ("host", 5, 1)])
async def test_install_pins_host_or_neighbor_without_mutation_fallback(placement, side, x):
    part = {"registryName": "ae:part", "metadata": 1, "side": side}
    class Install(ae_tasks.AeTask):
        async def approach(self, *a, **kw):
            assert kw["excluded_build_envelope"] == {"minX": x, "maxX": x, "minY": 64, "maxY": 64, "minZ": 2, "maxZ": 2}
        async def world(self, target): return {**OWNER, "source": "integrated_server", "registryName": "ae:host", "metadata": 0}
        async def step(self, tool, args=None, **kw):
            _validate(tool, args)
            assert args["x"] == x and args["partPlacement"] == placement
            binding = args["attachmentBinding"]
            return {"serverConfirmed": True, "attachmentBindingVerified": True, "clientAttachmentBindingVerified": True,
                "serverAttachmentBindingVerified": True, "fullNbtConservation": True, "allPlayerSlots": 41,
                "attachmentBinding": binding, "attachment": {k: binding[k] for k in ("x", "y", "z", "side")},
                "serverSourceConsumption": 1, "serverTarget": {"parts": [part]}}
    task = Install(None, COMMON, None)
    task.health = {"placementAttachmentBinding": CONTRACT, "contextRpc": {"version": 1, "reads": [], "actions": ["player.place_block"]}}
    result = await task.install({"attachment": TARGET, "hotbarSlot": 0, "expectedPart": part, "placement": placement})
    assert result["target"]["x"] == x and result["sourceConsumption"] == 1


@pytest.mark.parametrize("mode", ["survival", "creative", "short", "wrong_item", "wrong_meta", "conflict", "truncated", "missing_slot", "duplicate_slot", "cursor", "unknown_mode", "unknown_count"])
async def test_install_batch_material_preflight_is_all_or_nothing(monkeypatch, mode):
    part = {"registryName": "ae:part", "metadata": 1, "side": 5}
    targets = [{"attachment": {**TARGET, "x": TARGET["x"] + index}, "hotbarSlot": 0,
                "expectedPart": deepcopy(part), "placement": "host"} for index in range(3)]
    count = 1 if mode in ("creative", "short") else 3
    item = {"registryName": "ae:other" if mode == "wrong_item" else "ae:part", "metadata": 2 if mode == "wrong_meta" else 1, "count": None if mode == "unknown_count" else count}
    inventory = {"slots": [{"index": i, "item": deepcopy(item) if i == 0 else None} for i in range(40)],
                 "totalSlots": 40, "truncated": mode == "truncated", "cursorItem": item if mode == "cursor" else None}
    if mode == "missing_slot": inventory["slots"].pop()
    if mode == "duplicate_slot": inventory["slots"][1]["index"] = 0
    if mode == "conflict": targets[-1]["expectedPart"]["metadata"] = 2
    calls = []
    class Install(ae_tasks.AeTask):
        async def initialize(self): pass
        async def step(self, tool, args=None, **kw):
            calls.append(tool)
            if tool == "mcb_snapshot":
                return {"world": {"dimension": 0}, "player": {"creative": None if mode == "unknown_mode" else mode == "creative"}}
            assert tool == "mcb_inventory"
            return inventory
        async def install(self, declaration):
            calls.append("place")
            return {"serverConfirmed": True, "sourceConsumption": 0 if mode == "creative" else 1}
    monkeypatch.setattr(ae_tasks, "AeTask", Install)
    result = await ae_tasks.run_configure(None, {**COMMON, "targets": targets}, anyio.Lock(), None, install=True)
    if mode in ("survival", "creative"):
        assert result["status"] == "completed" and len(result["completed"]) == 3
        assert calls == ["mcb_snapshot", "mcb_inventory", "place", "place", "place"]
        preflight = result["materialPreflight"]
        assert preflight["sufficient"] and preflight["mode"] == mode
        assert preflight["materials"][0]["required"] == count
        assert sum(r["sourceConsumption"] for r in result["completed"]) == (3 if mode == "survival" else 0)
    else:
        assert result["status"] == "failed" and result["completed"] == [] and result["failedIndex"] is None
        assert "place" not in calls and result["dispatchedActionAttempts"] == 0
        if mode in ("short", "wrong_item", "wrong_meta"):
            assert result["error"]["code"] == "AE_MATERIALS_INSUFFICIENT"


async def test_material_preflight_combines_mounts_but_keeps_distinct_sources():
    class Materials(ae_tasks.AeTask):
        async def step(self, tool, args=None, **kw):
            if tool == "mcb_snapshot": return {"world": {"dimension": 0}, "player": {"creative": False}}
            return {"slots": [{"index": i, "item": {"registryName": "ae:part", "metadata": i, "count": 2} if i < 2 else None} for i in range(40)],
                    "totalSlots": 40, "truncated": False, "cursorItem": None}
    targets = [{"attachment": TARGET, "hotbarSlot": slot, "expectedPart": {"registryName": "ae:part", "metadata": slot, "side": side},
                "placement": "host" if side == 5 else "adjacent"} for slot, side in [(0, 5), (0, 4), (1, 5)]]
    result = await Materials(None, {**COMMON, "targets": targets}, None).installation_materials()
    assert [(r["hotbarSlot"], r["placements"], r["required"]) for r in result["materials"]] == [(0, 2, 2), (1, 1, 1)]

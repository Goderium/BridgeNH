"""Exact public attachment routing and refusal; native geometry is covered by Java fixtures."""
import copy

import pytest
from jsonschema import ValidationError

from minecraft_codex_bridge.bridge import BridgeError, _REQUEST_CONTEXT
from minecraft_codex_bridge.placement_attachment import CONTRACT, from_plan, require_capability, validate_receipt
from minecraft_codex_bridge.server import invoke_control
from minecraft_codex_bridge.validation import tool_validator
from test_construction_tasks import CASING, CONTROLLER, key, native_construction, stack
from test_survival_structure_seeds import task, install_finite_native_receipts
from test_native_approach_tasks import construction_task, install_planner


def health():
    return {"sessionId": "session", "capabilities": ["player.place_block", "client.background"],
        "background": {"enabled": True, "helperInstalled": True, "pauseOnLostFocus": False},
        "placementAttachmentBinding": copy.deepcopy(CONTRACT),
        "contextRpc": {"version": 1, "reads": [], "actions": ["player.place_block"]}}


TARGET = {"x": 0, "y": 64, "z": 0, "dimension": 0}
SELECTED = {"x": 1, "y": 64, "z": 0, "dimension": 0, "side": 4,
    "source": "integrated_server", "registryName": "minecraft:stone", "metadata": 0}
FRONTIER = [({"x": 1, "y": 64, "z": 0}, 4)]


def binding():
    return from_plan(health(), TARGET, SELECTED, FRONTIER)


def arguments(bound=True):
    result = {**TARGET, "hotbarSlot": 6, "expectedWorld": "world", "replace": False}
    if bound:
        result["attachmentBinding"] = binding()
    return result


def receipt(bound=None):
    bound = binding() if bound is None else bound
    return {"attachmentBinding": copy.deepcopy(bound), "attachmentBindingVerified": True,
        "clientAttachmentBindingVerified": True, "serverAttachmentBindingVerified": True,
        "serverConfirmed": True, "fullNbtConservation": True, "allPlayerSlots": 41,
        "attachment": {key: bound[key] for key in ("x", "y", "z", "side")}}


@pytest.mark.parametrize("gt", [False, True])
def test_schema_and_plan_bind_only_exact_public_identity(gt):
    selected = copy.deepcopy(SELECTED)
    if gt:
        selected["gregtech"] = {"metaTileId": 604, "metaClass": "public.ActualBasic", "unrelatedPower": 128}
    bound = from_plan(health(), TARGET, selected, FRONTIER)
    tool_validator("mcb_place_block").validate({**arguments(False), "attachmentBinding": bound})
    assert bound == {"version": 1, **{key: selected[key] for key in ("x", "y", "z", "dimension", "side", "registryName", "metadata")},
        **({"gregtech": {"metaTileId": 604, "metaClass": "public.ActualBasic"}} if gt else {})}
    selected["metadata"] = 9
    assert bound["metadata"] == 0


@pytest.mark.parametrize("key", list(CONTRACT))
def test_missing_advertised_contract_field_refuses_before_action(key):
    value = health(); value["placementAttachmentBinding"].pop(key)
    with pytest.raises(BridgeError) as caught:
        require_capability(value)
    assert caught.value.details["outcome_unknown"] is False


@pytest.mark.parametrize("fault", ["missing", "extra", "bool_version", "bool_slots", "guard_missing", "not_dict"])
def test_unsupported_or_malformed_capability_does_not_authorize_frontier(fault):
    value = health()
    if fault == "missing": value.pop("placementAttachmentBinding")
    elif fault == "extra": value["placementAttachmentBinding"]["extra"] = 1
    elif fault == "bool_version": value["placementAttachmentBinding"]["version"] = True
    elif fault == "bool_slots": value["placementAttachmentBinding"]["allPlayerSlots"] = True
    elif fault == "guard_missing": value["contextRpc"]["actions"] = []
    else: value["placementAttachmentBinding"] = []
    with pytest.raises(BridgeError): require_capability(value)


@pytest.mark.parametrize("change", [
    {"source": "client"}, {"dimension": 1}, {"dimension": True}, {"side": True}, {"side": 5},
    {"x": -1}, {"metadata": True}, {"metadata": -1}, {"registryName": ""}, {"y": 256},
    {"gregtech": None}, {"gregtech": {"metaTileId": True, "metaClass": "public.Machine"}},
    {"gregtech": {"metaTileId": 604, "metaClass": ""}},
])
def test_no_stale_foreign_or_incomplete_planner_attachment_is_dispatched(change):
    selected = {**copy.deepcopy(SELECTED), **change}
    with pytest.raises(BridgeError) as caught:
        from_plan(health(), TARGET, selected, FRONTIER)
    assert caught.value.details["outcome_unknown"] is False


@pytest.mark.parametrize("change", [
    {"version": True}, {"side": 6}, {"extra": 1}, {"gregtech": {"metaTileId": 604}}, {"x": 30000001},
])
def test_public_schema_rejects_malformed_binding(change):
    args = arguments(); args["attachmentBinding"].update(change)
    with pytest.raises(ValidationError): tool_validator("mcb_place_block").validate(args)


@pytest.mark.parametrize("fault", ["missing_client", "missing_server", "missing_all41", "wrong_face", "bool_version", "nbt_missing"])
def test_post_dispatch_receipt_failure_is_unknown_and_cannot_replay(fault):
    result = receipt()
    if fault == "missing_client": result.pop("clientAttachmentBindingVerified")
    elif fault == "missing_server": result["serverAttachmentBindingVerified"] = False
    elif fault == "missing_all41": result["allPlayerSlots"] = 40
    elif fault == "wrong_face": result["attachment"]["side"] = 5
    elif fault == "bool_version": result["attachmentBinding"]["version"] = True
    else: result.pop("fullNbtConservation")
    with pytest.raises(BridgeError) as caught: validate_receipt(result, binding())
    assert caught.value.details["outcome_unknown"] is True


def setup_frontier(client, state, monkeypatch):
    instance = task(client)
    point = {**CONTROLLER, "x": CONTROLLER["x"] + 2, "y": 2}
    owned = {**point, "x": point["x"] + 1}
    state["blocks"][key(owned)] = CASING.copy()
    state["items"][6] = stack(CASING)
    instance.materials[6] = {"item": CASING.copy(), "count": 1}
    instance._survival_frontier_binding = [(owned, 4)]
    calls = install_finite_native_receipts(instance, state, monkeypatch)
    return instance, point, owned, calls


async def test_actual_frontier_place_sends_exact_binding_and_preserves_real_ledger(native_construction, monkeypatch):
    client, state = native_construction
    instance, point, owned, calls = setup_frontier(client, state, monkeypatch)
    captured = []
    original = instance.workflow
    async def workflow(phase, steps):
        captured.extend(copy.deepcopy(step) for step in steps if step.get("tool") == "mcb_place_block")
        return await original(phase, steps)
    monkeypatch.setattr(instance, "workflow", workflow)
    record = {}
    await instance.place(point, 6, "frontier", record)
    sent = captured[0]["args"]["attachmentBinding"]
    assert len(captured) == len(calls) == 1 and all(sent[axis] == owned[axis] for axis in ("x", "y", "z")) and sent["side"] == 4
    assert record["placementConfirmed"] and record["serverConsumptionConfirmed"]
    assert len(instance.survival_material_ledger) == 1 and state["items"][6] is None
    assert "serverAttachmentBindingVerified" in captured[0]["fields"]


@pytest.mark.parametrize("fault", ["capability", "guard", "retained_proof", "foreign_proof"])
async def test_frontier_missing_proof_refuses_before_any_native_place(native_construction, monkeypatch, fault):
    client, state = native_construction
    instance, point, owned, calls = setup_frontier(client, state, monkeypatch)
    if fault == "capability": instance.approach_health.pop("placementAttachmentBinding")
    elif fault == "guard": instance.approach_health["contextRpc"]["actions"] = []
    else:
        async def unbound(point, slot, record):
            if fault == "foreign_proof":
                instance._planned_placement_attachment = {**SELECTED, **point, "dimension": instance.dimension,
                    "x": point["x"] - 1, "side": 5}
            return owned, 4, []
        monkeypatch.setattr(instance, "supported_placement_approach", unbound)
    with pytest.raises(BridgeError): await instance.place(point, 6, "refused")
    assert calls == [] and key(point) not in state["blocks"] and state["items"][6]["count"] == 1


async def test_invalid_confirmation_retains_one_real_deduction_without_retry(native_construction, monkeypatch):
    client, state = native_construction
    instance, point, owned, calls = setup_frontier(client, state, monkeypatch)
    calls = install_finite_native_receipts(instance, state, monkeypatch, mutate=lambda result: result.update(serverAttachmentBindingVerified=False))
    record = {}
    with pytest.raises(BridgeError) as caught: await instance.place(point, 6, "unknown", record)
    assert caught.value.details["outcome_unknown"] is True and len(calls) == 1
    assert record["placementConfirmed"] and record["serverConsumptionConfirmed"] and state["items"][6] is None
    assert len(instance.survival_material_ledger) == 1


async def test_unbound_place_keeps_existing_path_without_new_capability(native_construction, monkeypatch):
    client, state = native_construction
    instance, point, owned, calls = setup_frontier(client, state, monkeypatch)
    instance._survival_frontier_binding = None
    instance.approach_health.pop("placementAttachmentBinding")
    await instance.place(point, 6, "legacy")
    assert len(calls) == 1 and state["items"][6] is None


async def test_real_planned_approach_retains_full_public_attachment(native_construction):
    client, state = native_construction
    install_planner(client, state)
    instance = construction_task(client, state)
    point = {**CONTROLLER, "y": 2}
    xyz, side, steps = await instance.planned_approach(point, intent="place_block", allowed_sides=(1,), hotbar_slot=6)
    retained = instance._planned_placement_attachment
    assert all(retained[axis] == xyz[axis] for axis in ("x", "y", "z")) and retained["side"] == side
    assert retained["source"] == "integrated_server" and retained["registryName"] and type(retained["metadata"]) is int
    assert steps and not state["placements"]


class Client:
    def __init__(self, *, refused=False, corrupt=False):
        self.calls = []; self.refused = refused; self.corrupt = corrupt
    async def invoke(self, operation, args):
        assert operation == "bridge.health"
        return health()
    async def call(self, operation, args):
        self.calls.append((operation, copy.deepcopy(args), copy.deepcopy(_REQUEST_CONTEXT.get())))
        if self.refused:
            raise BridgeError("face_not_visible", "exact face refused", outcome_unknown=False)
        result = receipt(args["attachmentBinding"])
        if self.corrupt: result["serverAttachmentBindingVerified"] = False
        return result


async def test_standalone_bound_place_uses_one_guarded_request():
    client = Client()
    await invoke_control(client, "player.place_block", arguments())
    assert len(client.calls) == 1 and client.calls[0][2] == {"version": 1, "expectedSessionId": "session",
        "expectedProfile": "developer", "expectedWorld": "world", "requireBackground": True}


async def test_standalone_ordinary_place_enters_same_native_accounting_without_binding_capability():
    class Ordinary(Client):
        async def invoke(self, operation, args):
            value = health();value.pop("placementAttachmentBinding");return value
        async def call(self, operation, args):
            self.calls.append((operation, copy.deepcopy(args), copy.deepcopy(_REQUEST_CONTEXT.get())))
            assert "attachmentBinding" not in args
            return {"actualSurvival": True, "serverConfirmed": True}
    client=Ordinary()
    result=await invoke_control(client,"player.place_block",arguments(False))
    assert result["serverConfirmed"] is True and len(client.calls)==1
    assert client.calls[0][2]=={"version":1,"expectedSessionId":"session","expectedProfile":"developer","expectedWorld":"world","requireBackground":True}


@pytest.mark.parametrize("refused,corrupt", [(True, False), (False, True)])
async def test_standalone_refusal_or_unknown_never_reselects_or_replays(refused, corrupt):
    client = Client(refused=refused, corrupt=corrupt)
    with pytest.raises(BridgeError) as caught: await invoke_control(client, "player.place_block", arguments())
    assert len(client.calls) == 1 and caught.value.details["outcome_unknown"] is corrupt
    if corrupt:
        assert caught.value.details["knownReceipt"]["serverAttachmentBindingVerified"] is False
        assert caught.value.details["automaticReplayAllowed"] is False

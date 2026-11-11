from copy import deepcopy
import pytest
from minecraft_codex_bridge.ae_tasks import AeTask
from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import _validate
from test_ae_goals import COMMON, TARGET

PART = {"registryName": "appliedenergistics2:item.ItemMultiPart", "metadata": 220, "side": 4}
TARGET = {**TARGET, "targetIdentity": {"registryName": "gregtech:gt.blockmachines", "metaTileId": 70}}

@pytest.mark.parametrize("mode", ["rotate", "creative_rotate", "already", "no_tool", "old_bridge", "old_gui", "old_endpoint", "bad_endpoint", "no_sync", "bad_gui", "bad_inventory_sync", "facing_changed", "other_machine", "fluid_part", "host"])
async def test_front_only_ports_use_one_validated_wrench_click_before_install(mode):
    observed = {"gregtech": {"metaClass": "gregtech.api.metatileentity.implementations.MTEHatchInputBus",
        "orientation": {"facing": {"side": 5 if mode == "already" else 2}}}}
    if mode == "other_machine": observed["gregtech"]["metaClass"] = "other.Machine"
    part = deepcopy(PART)
    if mode == "fluid_part": part["registryName"] = "ae2fc:part_fluid_storage_bus"
    calls = []
    class Task(AeTask):
        async def approach(self, target, **kwargs):
            _validate('mcb_approach', {**COMMON, 'targets': [target]})
            calls.append(('wrench_endpoint', (target, kwargs)))
            assert target['wrenchFacing'] is True and kwargs['open_gui'] is False
            assert kwargs['excluded_build_envelope'] == {'minX':2,'maxX':2,'minY':64,'maxY':64,'minZ':2,'maxZ':2}
            if mode == 'bad_endpoint': raise BridgeError('AE_APPROACH_UNCONFIRMED', 'Centre is blocked')
            return {'status':'completed'}
        async def step(self, tool, args=None, **kw):
            _validate(tool, args or {})
            calls.append((tool, args))
            if tool == "mcb_snapshot": return {"player": {"creative": mode == "creative_rotate"}}
            if tool == "mcb_inventory":
                return {"cursorItem": None, "truncated": False, "slots": [] if mode == "no_tool" else [
                    {"index": 2, "item": {"registryName": "gregtech:gt.metatool.01", "metadata": 16, "hasTag": True, "count": 1}}]}
            if tool == "mcb_interact":
                if mode != "no_sync": observed["gregtech"]["orientation"]["facing"]["side"] = 5
                return {"accepted": True, "nativeGtWrenchFacingVersion": 1}
            if tool == "mcb_gui":
                return {"windowId": 2 if mode == "bad_gui" else 0, "containerClass": "net.minecraft.inventory.ContainerPlayer",
                    "cursorItem": None, "containerSynchronization": {"fullNativeSlotsEqual": mode != "bad_inventory_sync", "fullNbtCompared": True}}
            if tool == "mcb_close_gui" and mode == "facing_changed": observed["gregtech"]["orientation"]["facing"]["side"] = 2
        async def world(self, target): return deepcopy(observed)
        async def wait(self, reader, predicate, **kw):
            value = await reader()
            if not predicate(value): raise BridgeError("AE_OBSERVATION_TIMEOUT", "server did not rotate")
            return value
    task = Task(None, COMMON, None)
    task.health = {} if mode == "old_bridge" else {"nativeGtWrenchFacingVersion": 1, "guiNativeWaitVersion": True if mode == "old_gui" else 1, "approachWrenchFacingVersion": True if mode == "old_endpoint" else 1}
    if mode in {"no_tool", "old_bridge", "old_gui", "old_endpoint", "bad_endpoint", "no_sync", "bad_gui", "bad_inventory_sync", "facing_changed"}:
        with pytest.raises(BridgeError): await task.orient_item_port(TARGET, deepcopy(observed), part, False)
    else:
        _, proof = await task.orient_item_port(TARGET, deepcopy(observed), part, mode == "host")
        assert proof["nativeUseSubmitted"] is True if mode in {"rotate", "creative_rotate"} else not proof.get("nativeUseSubmitted", False)
        if mode == "rotate": assert proof["inventorySynchronized"] is True
    uses = [args for name, args in calls if name == "mcb_interact"]
    assert len(uses) == (1 if mode in {"rotate", "creative_rotate", "no_sync", "bad_gui", "bad_inventory_sync", "facing_changed"} else 0)
    if uses: assert uses[0]["side"] == 5 and uses[0]["wrenchFacing"] and uses[0]["sneaking"] is False
    names=[name for name,_ in calls]
    if uses: assert names.index('wrench_endpoint') < names.index('mcb_interact')
    assert names.count("mcb_open_inventory") == (1 if mode in {"rotate", "bad_gui", "bad_inventory_sync", "facing_changed"} else 0)
    if mode=="rotate": assert names.index("mcb_interact") < names.index("mcb_open_inventory") < names.index("mcb_gui") < names.index("mcb_close_gui")

@pytest.mark.parametrize("change", [{"action":"attack"}, {"sneaking": True}, {"targetPart": PART}, {"side": None}, {"expectedHeld": None}])
def test_wrench_schema_refuses_ambiguous_native_actions(change):
    args = {"action":"use", "x":1,"y":64,"z":2,"side":5,"wrenchFacing":True,
        "expectedHeld":{"slot":2,"registryName":"gregtech:gt.metatool.01","metadata":16}, **change}
    with pytest.raises(BridgeError): _validate("mcb_interact",args)

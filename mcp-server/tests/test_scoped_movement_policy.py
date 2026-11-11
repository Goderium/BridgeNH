import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.operation_policy import movement_arguments
from minecraft_codex_bridge.tools import TOOL_BY_NAME
from minecraft_codex_bridge.validation import validate_tool_arguments


class Client:
    def __init__(self, implementation):
        self.calls = []
        self.implementation = implementation

    async def invoke(self, operation, args):
        self.calls.append((operation, args))
        return {"readiness": {"localIntegratedServer": True}, "operationPolicy": {
            "available": True, "effective": {"movementImplementation": self.implementation}}}


@pytest.mark.parametrize("name", ["mcb_navigate", "mcb_approach", "mcb_ae_configure", "mcb_ae_install", "mcb_ae_order", "mcb_recipe_once", "mcb_blueprint_build"])
@pytest.mark.parametrize("implementation", ["walking", "ability_flight", "developer_teleport"])
async def test_all_entrypoints_use_one_saved_selection(name, implementation):
    client = Client(implementation)
    args = {"expectedWorld": "world", "actualSurvival": True}
    selected = await movement_arguments(client, name, args)
    key = "movementStrategy" if name in {"mcb_recipe_once", "mcb_blueprint_build"} else "strategy"
    assert selected[key] == implementation and key not in args
    assert client.calls == [("bridge.health", {})]
    assert not {"strategy", "movementStrategy"} & TOOL_BY_NAME[name].schema["properties"].keys()


async def test_unknown_capability_never_submits_movement():
    client = Client("unknown")
    with pytest.raises(BridgeError):
        await movement_arguments(client, "mcb_navigate", {})
    assert len(client.calls) == 1


def test_single_policy_tool_and_canonical_names():
    assert "mcb_profile" not in TOOL_BY_NAME
    assert TOOL_BY_NAME["mcb_policy"].operation == "bridge.policy"
    for mode in ("survival", "survival_efficient", "creative", "full"):
        validate_tool_arguments("mcb_policy", {"action": "set", "mode": mode, "movement": "path"})
    with pytest.raises(BridgeError):
        validate_tool_arguments("mcb_policy", {"action": "set", "movement": "ability_flight"})
    for key, value in [("mode", "生存"), ("mode", "生存高效"), ("mode", "创造"), ("mode", "完全支配"), ("movement", "寻路")]:
        with pytest.raises(BridgeError):
            validate_tool_arguments("mcb_policy", {"action": "set", key: value})


@pytest.mark.parametrize("name", ["mcb_recipe_once", "mcb_blueprint_build"])
async def test_legacy_creative_composite_stops_before_preparation(name):
    client = Client("ability_flight")
    with pytest.raises(BridgeError) as failure:
        await movement_arguments(client, name, {})
    assert failure.value.code == "MOVEMENT_POLICY_UNSUPPORTED"
    assert client.calls == [("bridge.health", {})]

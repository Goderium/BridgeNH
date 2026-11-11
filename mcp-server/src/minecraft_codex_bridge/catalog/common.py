"""Common tool schemas and native operation declarations."""

from dataclasses import (
    dataclass,
)
from typing import (
    Any,
)


def obj(properties: dict[str, Any] | None = None, required: list[str] | None = None) -> dict[str, Any]:
    return {"type": "object", "properties": properties or {}, "required": required or [], "additionalProperties": False}


def integer(low: int, high: int) -> dict[str, Any]:
    return {"type": "integer", "minimum": low, "maximum": high}


def decimal_quantity(maximum: int) -> dict[str, Any]:
    """Canonical positive decimal text, bounded without losing long precision."""
    digits = str(maximum)
    alternatives = [f"[1-9][0-9]{{0,{len(digits) - 2}}}"]
    for index, digit in enumerate(digits):
        low, high = (1 if index == 0 else 0), int(digit) - 1
        if high >= low:
            prefix = digits[:index]
            character = str(low) if low == high else f"[{low}-{high}]"
            alternatives.append(prefix + character + f"[0-9]{{{len(digits) - index - 1}}}")
    alternatives.append(digits)
    return {"type": "string", "minLength": 1, "maxLength": len(digits),
            "pattern": "^(?:" + "|".join(alternatives) + ")$", "not": {"pattern": "[^0-9]"}}


def named_actions(properties: dict[str, Any], variants: dict[str, tuple[list[str], list[str], dict[str, Any]]]) -> dict[str, Any]:
    common = ["screenId", "action"]
    return {**obj({"screenId": SCREEN_ID, "action": {"type": "string", "enum": list(variants)}, **properties}, common),
            "oneOf": [{"properties": {"action": {"const": action}}, "propertyNames": {"enum": common + allowed},
                       "required": required, **conditions} for action, (allowed, required, conditions) in variants.items()]}


POSITION = {"x": integer(-30_000_000, 30_000_000), "y": integer(0, 255), "z": integer(-30_000_000, 30_000_000)}


DIMENSION = integer(-2_147_483_648, 2_147_483_647)


QUERY = {"type": "string", "maxLength": 128}


REGISTRY_NAME = {"type": "string", "minLength": 1, "maxLength": 256}


AE_PART_IDENTITY_SCHEMA = obj({"side": {**integer(0, 6), "description": "Actual AE mounting direction: down0/up1/north2/south3/west4/east5/center6. This is independent of the ray hit face and allowedSides."}, "registryName": REGISTRY_NAME, "metadata": integer(0, 32767)}, ["side", "registryName", "metadata"])


EXPECTED_WORLD = {"type": "string", "minLength": 1, "maxLength": 128}


EXPECTED_SESSION = {"type": "string", "minLength": 1, "maxLength": 128}


LIVE_ID = {"type": "string", "minLength": 1, "maxLength": 128}


LONG_QUANTITY = decimal_quantity(9_223_372_036_854_775_807)


TEXT_INPUT = {"type": "string", "maxLength": 1024}


POSITIONS = {"type": "array", "minItems": 1, "maxItems": 16, "items": obj(POSITION, ["x", "y", "z"])}


JSON_PATH = {"type": "string", "minLength": 1, "maxLength": 256, "pattern": r"^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$"}


SCREEN_ID = {"type": "string", "minLength": 1, "maxLength": 128}


@dataclass(frozen=True)
class ToolSpec:
    name: str
    operation: str | None
    description: str
    schema: dict[str, Any]
    read_only: bool = False
    takeover: bool = False
    idempotent: bool | None = None

    @property
    def destructive(self) -> bool:
        # Arbitrary GUI callbacks, chat, inventory drops, world edits and composed
        # workflows retain the conservative destructive classification.
        return not self.read_only and self.operation not in NONDESTRUCTIVE_OPERATIONS


NONDESTRUCTIVE_OPERATIONS = frozenset({
    "bridge.policy", "client.background", "client.mouse", "client.unfocused", "client.window_action",
    "player.look", "player.hotbar", "player.flight", "player.flight_check", "dev.teleport",
    "gui.pointer", "gui.open_inventory", "gui.open_baubles", "gui.open_pause_menu", "gui.close",
    "nei.open", "nei.navigate", "nei.preview_control", "world.scan_config",
    "client.load_world", "client.connect", "client.leave_world", "client.exit",
})


TAKEOVER_OPERATIONS = frozenset({
    "client.window_action",
    "player.look", "player.hotbar", "player.input", "player.navigate", "player.interact", "player.place_block", "structure.survival_build",
    "gui.slot_click", "gui.transfer_whole_stack", "gui.open_inventory", "gui.open_baubles", "gui.open_pause_menu", "gui.click", "gui.key", "gui.close", "gui.pointer",
    "chat.send", "inventory.supply", "machine.set_working", "dev.teleport", "player.flight", "player.flight_check",
    "client.load_world", "client.connect", "client.leave_world", "client.exit",
    "nei.open", "nei.navigate", "nei.item_action", "nei.preview_control", "nei.bookmark_action", "ae.action", "gui.widget_action",
    "qa.run", "qa.command", "qa.cancel",
})


def policy_schema(schema):
    """Hide executor selection from public tools; native workflow schemas retain their proofs."""
    if isinstance(schema, list):
        result = [policy_schema(item) for item in schema
                  if not (isinstance(item, dict) and "movementStrategy" in repr(item.get("if", {})))]
        return schema if result == schema else result
    if not isinstance(schema, dict):
        return schema
    result = {}
    for key, value in schema.items():
        if key == "properties":
            result[key] = {name: policy_schema(item) for name, item in value.items()
                           if name not in {"strategy", "movementStrategy"}}
        elif key == "required":
            result[key] = [name for name in value if name not in {"strategy", "movementStrategy"}]
        else:
            result[key] = policy_schema(value)
    return schema if result == schema else result

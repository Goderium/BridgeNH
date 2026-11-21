"""Public schema for an interruptible survival material order."""

from .common import DIMENSION, EXPECTED_SESSION, EXPECTED_WORLD, LONG_QUANTITY, REGISTRY_NAME, ToolSpec, integer, obj
from ..survival_gather import GATHER_SCHEMA
from ..survival_storage import ENDPOINT_SCHEMA


def command(action, fields, required=(), mode=None):
    fixed = {"action": {"const": action}}
    if mode:
        fixed["mode"] = {"const": mode}
    return {"properties": fixed, "propertyNames": {"enum": ["action", "orderId", *fields]},
            "required": list(required)}


context = ["expectedSessionId", "expectedWorld", "dimension"]
guard = ["expectedRevision", *context]
order_fields = ["mode", "quantity", "item", "destination", *context]
order_properties = {
    "orderId": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": "^[A-Za-z0-9_-]+$"},
    "action": {"enum": ["open", "status", "advance", "reconcile", "cancel"]},
    "mode": {"enum": ["gather", "replenish"]}, "expectedRevision": integer(1, 2147483647),
    "quantity": LONG_QUANTITY,
    "item": obj({"registryName": REGISTRY_NAME, "damage": integer(0, 32767),
                 "nbt": {"type": "string", "minLength": 1, "maxLength": 4096}}, ["registryName", "damage"]),
    "destination": ENDPOINT_SCHEMA, "sources": {"type": "array", "minItems": 1, "maxItems": 16,
                                               "uniqueItems": True, "items": ENDPOINT_SCHEMA},
    "gather": GATHER_SCHEMA, "maxSteps": {**integer(1, 32), "default": 16},
    "expectedWorld": EXPECTED_WORLD, "expectedSessionId": EXPECTED_SESSION, "dimension": DIMENSION,
}

SURVIVAL_ORDER = ToolSpec("mcb_survival_order", None,
    "Gather and deliver a specified quantity, or replenish a storage endpoint up to a target from ordered sources. "
    "Use the survival guide for exact identities, supported endpoints and recovery. Open with a unique orderId, "
    "then advance with its latest revision. Inspect status after interruption. Reconcile the recorded change before continuing.",
    {**obj(order_properties, ["action", "orderId"]), "oneOf": [
        command("open", [*order_fields, "gather"], [*order_fields, "gather"], "gather"),
        command("open", [*order_fields, "sources"], [*order_fields, "sources"], "replenish"),
        command("status", []), command("advance", [*guard, "maxSteps"], guard),
        command("reconcile", guard, guard), command("cancel", ["expectedRevision"], ["expectedRevision"])]},
    idempotent=False)

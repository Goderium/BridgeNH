"""Bounded declarations for reusable process and site checks."""
from .ae_task_schema import obj, integer, POS, IDENTITY

ID = {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_-]*$", "maxLength": 48}
QUANTITY = {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "maxLength": 64}
AMOUNT = obj({"resource": ID, "amount": QUANTITY}, ("resource", "amount"))
RESOURCE_IDENTITY = {"oneOf": [
    obj({"kind": {"const": "item"}, "registryName": {"type": "string", "minLength": 1, "maxLength": 256},
         "metadata": integer(0, 32767), "nbt": {"type": "string", "maxLength": 4096}}, ("kind", "registryName", "metadata")),
    obj({"kind": {"const": "fluid"}, "fluidName": {"type": "string", "minLength": 1, "maxLength": 256},
         "nbt": {"type": "string", "maxLength": 4096}}, ("kind", "fluidName")),
]}
RESOURCE = obj({"id": ID, "identity": RESOURCE_IDENTITY,
    "role": {"enum": ["feed", "external", "intermediate", "product", "cycle"]},
    "initial": QUANTITY, "expectedFinal": QUANTITY}, ("id", "identity", "role", "initial"))
RECIPE = obj({"id": ID, "machine": ID, "batches": integer(1, 1000000),
    "inputs": {"type": "array", "items": AMOUNT, "minItems": 1, "maxItems": 32},
    "outputs": {"type": "array", "items": AMOUNT, "minItems": 1, "maxItems": 32},
    "catalysts": {"type": "array", "items": AMOUNT, "maxItems": 32},
    "source": {"type": "string", "minLength": 1, "maxLength": 256}},
    ("id", "machine", "batches", "inputs", "outputs", "source"))
PROCESS_SCHEMA = obj({
    "resources": {"type": "array", "items": RESOURCE, "minItems": 1, "maxItems": 128},
    "machines": {"type": "array", "items": ID, "uniqueItems": True, "minItems": 1, "maxItems": 64},
    "recipes": {"type": "array", "items": RECIPE, "minItems": 1, "maxItems": 64},
}, ("resources", "machines", "recipes"))

POSITION = obj(POS, tuple(POS))
REQUIREMENT = obj({"path": {"type": "string", "minLength": 1, "maxLength": 192},
    "operator": {"enum": ["equals", "not_equals", "gt", "gte", "lt", "lte"]},
    "value": {"type": ["string", "integer", "number", "boolean", "null"]}}, ("path", "operator", "value"))
MACHINE = obj({"id": ID, "position": POSITION, "metaTileId": integer(0, 32767),
    "requirements": {"type": "array", "items": REQUIREMENT, "maxItems": 16}}, ("id", "position", "metaTileId"))
ENDPOINT = obj({"id": ID, "position": POSITION, "targetIdentity": IDENTITY,
    "mountingSide": integer(-1, 6), "network": ID}, ("id", "position", "targetIdentity", "mountingSide", "network"))
BOX = obj({prefix + axis.upper(): value for prefix in ("min", "max") for axis, value in POS.items()},
          tuple(prefix + axis.upper() for prefix in ("min", "max") for axis in POS))
VOLUME = obj({"id": ID, "kind": {"enum": ["machine", "ae", "pipe", "clearance"]}, "bounds": BOX}, ("id", "kind", "bounds"))
SITE_SCHEMA = obj({"dimension": integer(-2147483648, 2147483647),
    "machines": {"type": "array", "items": MACHINE, "maxItems": 16},
    "endpoints": {"type": "array", "items": ENDPOINT, "maxItems": 64},
    "clearance": {"type": "array", "items": obj({"id": ID, "position": POSITION}, ("id", "position")), "maxItems": 128},
    "layout": {"type": "array", "items": VOLUME, "maxItems": 128},
    "isolatedNetworks": {"type": "array", "maxItems": 32, "items": {"type": "array", "items": ID, "minItems": 2, "maxItems": 2, "uniqueItems": True}},
}, ("dimension",))
SITE_SCHEMA["anyOf"] = [{"required": [key], "properties": {key: {"minItems": 1}}}
                        for key in ("machines", "endpoints", "clearance", "layout")]

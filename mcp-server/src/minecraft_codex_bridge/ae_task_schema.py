"""Bounded AE goal declarations; no executable caller plans."""

def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}

def integer(lo, hi):
    return {"type": "integer", "minimum": lo, "maximum": hi}

ID = {"type": "string", "minLength": 1, "maxLength": 128}
ITEM = obj({"registryName": ID, "metadata": integer(0, 32767)}, ("registryName", "metadata"))
PART = obj({**ITEM["properties"], "side": integer(0, 6)}, ("registryName", "metadata", "side"))
POS = {"x": integer(-29999996, 29999996), "y": integer(0, 255), "z": integer(-29999996, 29999996)}
IDENTITY = obj({**ITEM["properties"], "part": PART, "metaTileId": integer(0, 32767)}, ("registryName",))
TARGET = obj({**POS, "targetIdentity": IDENTITY, "allowedSides": {"type": "array", "minItems": 1, "maxItems": 6, "uniqueItems": True, "items": integer(0, 5)}}, (*POS, "targetIdentity"))
COMMON = {"expectedSessionId": ID, "expectedWorld": ID, "dimension": integer(-2147483648, 2147483647),
          "strategy": {"enum": ["walking", "ability_flight", "developer_teleport"], "default": "walking"},
          "timeout_ms": {**integer(1000, 60000), "default": 30000}}
REQUIRED = ("expectedSessionId", "expectedWorld", "dimension")
SETTINGS = obj({"BLOCK": {"enum": ["YES", "NO"]}, "STORAGE_FILTER": {"enum": ["NONE", "EXTRACTABLE_ONLY"]},
                "ADVANCED_BLOCKING_MODE": {"enum": ["DEFAULT", "BLOCK_ON_ALL"]}})
SETTINGS["minProperties"] = 1
CONFIG_TARGET = obj({"target": TARGET, "priority": integer(-2147483648, 2147483647), "settings": SETTINGS}, ("target",))
CONFIG_TARGET["anyOf"] = [{"required": ["priority"]}, {"required": ["settings"]}]
CONFIGURE_SCHEMA = obj({**COMMON, "targets": {"type": "array", "minItems": 1, "maxItems": 32, "items": CONFIG_TARGET}}, (*REQUIRED, "targets"))
ATTACHMENT = obj({**TARGET["properties"], "allowedSides": {"type": "array", "minItems": 1, "maxItems": 1, "items": integer(0, 5)}}, (*POS, "targetIdentity", "allowedSides"))
INSTALL_TARGET = obj({"attachment": ATTACHMENT, "hotbarSlot": integer(0, 8), "expectedPart": PART, "placement": {"enum": ["adjacent", "host"], "default": "adjacent"}}, ("attachment", "hotbarSlot", "expectedPart"))
CHECKPOINT = obj({"version": {"const": 1},
    "planFingerprint": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
    **{key: {"type": "array", "items": integer(0, 31), "uniqueItems": True, "maxItems": 32}
       for key in ("completedIndices", "uncertainIndices", "pendingIndices")}},
    ("version", "planFingerprint", "completedIndices", "uncertainIndices", "pendingIndices"))
INSTALL_SCHEMA = obj({**COMMON, "targets": {"type": "array", "minItems": 1, "maxItems": 32, "items": INSTALL_TARGET},
                      "resume": CHECKPOINT}, (*REQUIRED, "targets"))
ORDER_ITEM = obj({**ITEM["properties"], "nbt": {"type": "string", "maxLength": 4096}}, ("registryName", "metadata"))
PORTABLE_OWNER = obj({"kind": {"const": "portable"}, "playerId": ID, "dimension": COMMON["dimension"],
                      "playerSlot": integer(0, 8), **ITEM["properties"], "networkKey": ID},
                     ("kind", "playerId", "dimension", "playerSlot", "registryName", "metadata", "networkKey"))
ORDER_TERMINAL = {"oneOf": [TARGET, obj({"portable": PORTABLE_OWNER}, ("portable",))]}
ORDER_SCHEMA = obj({**COMMON, "action": {"enum": ["start", "resume"]}, "requestId": ID,
                    "terminal": ORDER_TERMINAL, "item": ORDER_ITEM, "amount": integer(1, 64)},
                   (*REQUIRED, "action", "requestId"))
ORDER_SCHEMA["oneOf"] = [
    {"properties": {"action": {"const": "start"}}, "required": ["terminal", "item", "amount"]},
    {"properties": {"action": {"const": "resume"}}, "not": {"anyOf": [{"required": [key]} for key in ("terminal", "item", "amount")]}},
]

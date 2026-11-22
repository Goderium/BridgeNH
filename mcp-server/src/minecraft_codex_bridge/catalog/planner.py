"""Bounded offline factory-planning request."""

from .common import integer, obj

RESOURCE = {
    "type": "string",
    "minLength": 3,
    "maxLength": 220,
    "pattern": r"^(?:fluid:[A-Za-z0-9_.:/|-]+(?: +[A-Za-z0-9_.:/|-]+)*|[A-Za-z0-9_.|-]+:[A-Za-z0-9_.:/|-]+@[0-9]{1,5})(?![\s\S])",
}
STACK = obj({"resource": RESOURCE, "amount": integer(1, 1_000_000)}, ["resource", "amount"])
MACHINE = obj(
    {
        "handler": {"type": "string", "minLength": 1, "maxLength": 160},
        "machine": RESOURCE,
        "count": integer(1, 256),
        "coilTier": {"type": "string", "minLength": 1, "maxLength": 64,
                     "description": "Installed standard EBF coil key from the reference catalog (e.g. kanthal)."},
        "energyHatch": {**RESOURCE, "description": "One standard energy input hatch per EBF, matching tier."},
        "tier": {
            "enum": [
                "ULV",
                "LV",
                "MV",
                "HV",
                "EV",
                "IV",
                "LuV",
                "ZPM",
                "UV",
                "UHV",
                "UEV",
                "UIV",
                "UMV",
                "UXV",
            ]
        },
    },
    ["handler", "machine", "count", "tier"],
)
MACHINE["allOf"] = [{
    "if": {"properties": {"handler": {"const": "gt.recipe.blastfurnace"}}},
    "then": {"required": ["coilTier", "energyHatch"]},
    "else": {"not": {"anyOf": [{"required": ["coilTier"]}, {"required": ["energyHatch"]}]}},
}]
CHOICE = obj(
    {"resource": RESOURCE, "recipeId": {"type": "string", "pattern": "^[0-9]+-[a-f0-9]{20}$"}},
    ["resource", "recipeId"],
)

PRODUCTION_PLAN_SCHEMA = obj(
    {
        "target": STACK,
        "inventory": {"type": "array", "maxItems": 128, "items": STACK},
        "feedstocks": {
            "type": "array",
            "maxItems": 128,
            "uniqueItems": True,
            "items": RESOURCE,
            "description": "Declare exact raw feed boundaries; a shortage here remains a deficit instead of searching for another producer.",
        },
        "machines": {"type": "array", "maxItems": 32, "items": MACHINE},
        "horizonTicks": integer(1, 72_000_000),
        "recipeChoices": {"type": "array", "maxItems": 32, "items": CHOICE},
        "expectedRevision": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
    },
    ["target", "inventory", "machines", "horizonTicks"],
)

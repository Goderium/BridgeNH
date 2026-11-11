"""Explicit task posture declarations; native equipment permission remains authoritative."""

SURVIVAL_POSTURE_PROPERTIES = {
    "survivalOperationPosture": {"enum": ["grounded", "ability_flight"], "default": "grounded",
        "description": "Explicit actual-survival operation posture. ability_flight requires already real matching client/server survival flight and native equipment allowFlying throughout bootstrap, placement, shell and GUI actions; it never enables abilities or falls back to walking/teleport."},
    "finishFlying": {"type": "boolean",
        "description": "Only for survivalOperationPosture=ability_flight. Omission/true retains confirmed flight after all real acceptance. False requests one final normal ability-flight landing at the explicit finalLandingPosition after all interactions and inventory acceptance."},
    "finalLandingPosition": {"type": "object", "additionalProperties": False,
        "properties": {"x": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999},
            "y": {"type": "number", "minimum": 0, "maximum": 253},
            "z": {"type": "number", "minimum": -29_999_999, "maximum": 29_999_999}},
        "required": ["x", "y", "z"],
        "description": "Exact continuous feet XYZ for explicitly requested final landing. Native loaded/swept-body/support/inertia and stable matching actual client/server arrival remain required. No cell-centering, inferred safe landing, replay or fallback."},
}

SURVIVAL_POSTURE_CONSTRAINTS = [
    {"if": {"required": ["survivalOperationPosture"], "properties": {"survivalOperationPosture": {"const": "ability_flight"}}},
        "then": {"required": ["actualSurvival", "movementStrategy"],
            "properties": {"actualSurvival": {"const": True}, "movementStrategy": {"const": "ability_flight"}}}},
    {"if": {"anyOf": [{"required": ["finishFlying"]}, {"required": ["finalLandingPosition"]}]},
        "then": {"required": ["survivalOperationPosture"], "properties": {"survivalOperationPosture": {"const": "ability_flight"}}}},
    {"if": {"required": ["finishFlying"], "properties": {"finishFlying": {"const": False}}},
        "then": {"required": ["finalLandingPosition"]}},
    {"if": {"required": ["finalLandingPosition"]},
        "then": {"required": ["finishFlying"], "properties": {"finishFlying": {"const": False}}}},
]

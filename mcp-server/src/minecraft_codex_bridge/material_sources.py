"""Shared explicit native material-source declarations; no inferred source mode."""

AE_MATERIAL_SOURCE_SCHEMA = {
    "type": "object",
    "properties": {
        "type": {"const": "ae_cells"},
        "mode": {"type": "string", "enum": ["finite", "creative_infinite"]},
        "selector": {"type": "string", "enum": ["tile", "part"]},
        "x": {"type": "integer", "minimum": -30_000_000, "maximum": 30_000_000},
        "y": {"type": "integer", "minimum": 0, "maximum": 255},
        "z": {"type": "integer", "minimum": -30_000_000, "maximum": 30_000_000},
        "side": {"type": "integer", "minimum": 0, "maximum": 5},
        "expectedGridId": {"type": "string", "minLength": 36, "maxLength": 36,
            "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            "description": "Exact canonical public grid UUID from a fresh mcb_ae_material_source; never infer it."},
    },
    "required": ["type", "mode", "selector", "x", "y", "z", "side", "expectedGridId"],
    "additionalProperties": False,
}

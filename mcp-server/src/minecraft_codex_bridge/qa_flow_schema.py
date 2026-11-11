"""Shared immutable declared-flow schemas for direct QA and complete task entry points."""


def obj(properties, required):
    return {"type": "object", "additionalProperties": False, "properties": properties, "required": required}


def integer(low, high):
    return {"type": "integer", "minimum": low, "maximum": high}


REGISTRY_NAME = {"type": "string", "minLength": 1, "maxLength": 256}
DECLARED_FLOW_ITEM_SCHEMA = obj({"registryName": REGISTRY_NAME, "metadata": integer(0, 32766)}, ["registryName", "metadata"])
DECLARED_FLOW_INPUT_SCHEMA = obj({**DECLARED_FLOW_ITEM_SCHEMA["properties"], "count": integer(0, 2147483647)}, ["registryName", "metadata", "count"])
DECLARED_FLOW_OUTPUT_SCHEMA = obj({**DECLARED_FLOW_ITEM_SCHEMA["properties"], "count": integer(1, 2147483647)}, ["registryName", "metadata", "count"])
DECLARED_FLOW_FLUID_SCHEMA = obj({"fluid": {"type": "string", "minLength": 1, "maxLength": 128}, "amount": integer(1, 2147483647)}, ["fluid", "amount"])
DECLARED_BLUEPRINT_CHANNEL_SCHEMA = obj({
    "name": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": "^[a-z0-9_.-]+$"},
    "value": integer(1, 64),
}, ["name", "value"])
DECLARED_FLOW_RECIPE_SCHEMA = obj({
    "map": REGISTRY_NAME,
    "inputs": {"type": "array", "minItems": 0, "maxItems": 8, "items": DECLARED_FLOW_INPUT_SCHEMA},
    "outputs": {"type": "array", "minItems": 0, "maxItems": 8, "items": DECLARED_FLOW_OUTPUT_SCHEMA},
    "outputChances": {"type": "array", "minItems": 0, "maxItems": 8, "items": integer(0, 10000)},
    "fluidInputs": {"type": "array", "minItems": 0, "maxItems": 8, "items": DECLARED_FLOW_FLUID_SCHEMA},
    "fluidOutputs": {"type": "array", "minItems": 0, "maxItems": 8, "items": DECLARED_FLOW_FLUID_SCHEMA},
    "eut": integer(1, 2147483647), "durationTicks": integer(1, 2147483647),
}, ["map", "inputs", "outputs", "fluidInputs", "fluidOutputs", "eut", "durationTicks"])
# JSON Schema cannot compare array lengths directly; the declared bound is only eight slots.
DECLARED_FLOW_RECIPE_SCHEMA["allOf"] = [{
    "if": {"properties": {"outputs": {"minItems": size, "maxItems": size}}, "required": ["outputs"]},
    "then": {"properties": {"outputChances": {"minItems": size, "maxItems": size}}},
} for size in range(9)]
DECLARED_FLOW_RECIPE_SCHEMA["allOf"].append({
    "if": {"properties": {"outputChances": {"contains": {"not": {"const": 10000}}}}, "required": ["outputChances"]},
    "then": {"properties": {"fluidInputs": {"maxItems": 1}, "fluidOutputs": {"maxItems": 1}}},
})
DECLARED_DETERMINISTIC_RECIPE_SCHEMA = {**DECLARED_FLOW_RECIPE_SCHEMA, "properties": {
    **DECLARED_FLOW_RECIPE_SCHEMA["properties"], "outputChances": {
        **DECLARED_FLOW_RECIPE_SCHEMA["properties"]["outputChances"], "items": {"const": 10000}}}}
DECLARED_LEGACY_RECIPE_SCHEMA = {**DECLARED_DETERMINISTIC_RECIPE_SCHEMA, "properties": {
    **DECLARED_DETERMINISTIC_RECIPE_SCHEMA["properties"],
    **{key: {**DECLARED_FLOW_RECIPE_SCHEMA["properties"][key], "maxItems": 1} for key in ("fluidInputs", "fluidOutputs")}}}
DECLARED_FLOW_MANUFACTURE_SCHEMA = {"oneOf": [DECLARED_LEGACY_RECIPE_SCHEMA,
    obj({"source": {"const": "public_crafting"}, "outputCount": integer(1, 64)}, ["source", "outputCount"])]}
DECLARED_FLOW_UPGRADE_SCHEMA = {**obj({
    "item": DECLARED_FLOW_ITEM_SCHEMA, "manufacturingRecipe": DECLARED_FLOW_MANUFACTURE_SCHEMA,
    "expectedRemainingCount": integer(0, 1), "waitTicks": integer(1, 400),
    "expectedFeedbackLines": {"type": "array", "minItems": 0, "maxItems": 8, "items": {"type": "string", "minLength": 1, "maxLength": 512}},
    "publicFeedback": obj({"getMaxParallelRecipes": obj({"before": integer(0, 2147483647), "after": integer(0, 2147483647),
        "formed": integer(1, 2147483647)}, ["formed"])}, ["getMaxParallelRecipes"]),
}, ["item", "manufacturingRecipe"]), "anyOf": [
    {"required": ["expectedFeedbackLines"], "properties": {"expectedFeedbackLines": {"minItems": 1}}},
    {"required": ["publicFeedback"]}]}
DECLARED_MACHINE_FLOW_SCHEMA = obj({
    "caseName": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": "^[A-Za-z0-9_.-]+$"},
    "machine": DECLARED_FLOW_ITEM_SCHEMA, "recipe": DECLARED_FLOW_RECIPE_SCHEMA,
    "voltageTier": integer(1, 14), "assumedVoltageTier": integer(1, 14), "constructionAmount": integer(1, 64),
    "machineMode": integer(0, 2147483647),
    "blueprintChannels": {"type": "array", "minItems": 0, "maxItems": 16, "uniqueItems": True,
        "items": DECLARED_BLUEPRINT_CHANNEL_SCHEMA},
    "fixtureTemplate": {"type": "string", "enum": ["tstqa:core_flow_cell", "tstqa:general_small_flow_cell",
        "tstqa:general_medium_flow_cell", "tstqa:general_large_cube_flow_cell", "tstqa:general_atelier_flow_cell", "tstqa:general_molecule_flow_cell", "tstqa:general_dynamic_flow_cell"]},
    "fixtureBounds": obj({**{prefix + axis: integer(-256, 256) for prefix in ("min", "max") for axis in "XYZ"},
        "facing": {"enum": ["NORTH", "SOUTH", "EAST", "WEST"]},
        "coordinateSpace": {"const": "controller_relative_world"}},
        [prefix + axis for prefix in ("min", "max") for axis in "XYZ"] + ["facing", "coordinateSpace"]),
    "upgrades": {"type": "array", "minItems": 0, "maxItems": 4, "items": DECLARED_FLOW_UPGRADE_SCHEMA},
}, ["caseName", "machine", "recipe", "voltageTier", "fixtureTemplate"])
DECLARED_MACHINE_FLOW_SCHEMA["allOf"] = [{
    "if": {"properties": {"fixtureTemplate": {"const": "tstqa:general_dynamic_flow_cell"}}, "required": ["fixtureTemplate"]},
    "then": {"required": ["fixtureBounds"]}, "else": {"not": {"required": ["fixtureBounds"]}},
}]
DECLARED_MACHINE_FLOW_SCHEMA["allOf"].append({
    "if": {"properties": {"fixtureTemplate": {"const": "tstqa:core_flow_cell"}}, "required": ["fixtureTemplate"]},
    "then": {"properties": {"recipe": DECLARED_LEGACY_RECIPE_SCHEMA}},
})
DECLARED_MACHINE_FLOWS_SCHEMA = {"type": "array", "minItems": 1, "maxItems": 10, "uniqueItems": True, "items": DECLARED_MACHINE_FLOW_SCHEMA}

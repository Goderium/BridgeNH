"""Process-local validators for immutable bundled schemas, never live game data."""

from functools import lru_cache
import json
from itertools import islice

import jsonschema


@lru_cache(maxsize=128)
def tool_validator(name: str) -> jsonschema.Draft202012Validator:
    # Lazy import avoids the task/schema registration dependency cycle.
    from .tools import TOOL_BY_NAME
    return jsonschema.Draft202012Validator(TOOL_BY_NAME[name].schema)


@lru_cache(maxsize=16)
def execution_validator(name: str) -> jsonschema.Draft202012Validator:
    """Validate internal executor arguments against the unchanged bounded contract."""
    from .tools import INTERNAL_EXECUTION_SCHEMAS
    return jsonschema.Draft202012Validator(INTERNAL_EXECUTION_SCHEMAS[name])


def _property_names(schema):
    """Only bundled schema names may appear in diagnostic input paths."""
    pending, names, seen = [schema], set(), set()
    for _ in range(4096):
        if not pending:
            break
        node = pending.pop()
        if id(node) in seen:
            continue
        seen.add(id(node))
        if isinstance(node, dict):
            properties = node.get("properties", {})
            if isinstance(properties, dict):
                names.update(key for key in properties if isinstance(key, str) and len(key) <= 96)
            pending.extend(value for value in node.values() if isinstance(value, (dict, list)))
        elif isinstance(node, list):
            pending.extend(value for value in node if isinstance(value, (dict, list)))
    return names


def _schema_issue(error, names, schema=None):
    path = list(islice(error.absolute_path, 17))
    result = {"argument_path": [value if type(value) is int or value in names else "*" for value in path[:16]],
              "rule": error.validator}
    if len(path) > 16:
        result["argument_path_truncated"] = True
    schema = error.schema if schema is None else schema
    schema = schema if isinstance(schema, dict) else {}
    properties = schema.get("properties")
    property_names = schema.get("propertyNames", {})
    named_keys = property_names.get("enum") if isinstance(property_names, dict) else None
    if isinstance(named_keys, list) and all(isinstance(key, str) for key in named_keys):
        properties = dict.fromkeys(named_keys)
    if isinstance(properties, dict):
        keys = list(islice(properties, 65))
        result["allowed_keys"] = [key for key in keys[:64] if isinstance(key, str) and len(key) <= 96]
        result["allowed_keys_complete"] = len(properties) <= 64 and len(result["allowed_keys"]) == len(properties)
        result["additional_keys_allowed"] = not isinstance(named_keys, list) and (schema.get("additionalProperties", True) is not False or bool(schema.get("patternProperties")))
    if error.validator == "required" and isinstance(error.instance, dict):
        required = list(schema.get("required", []))
        for union in ("oneOf", "anyOf"):
            variants = schema.get(union)
            if isinstance(variants, list) and 0 < len(variants) <= 64 and all(isinstance(branch, dict) for branch in variants):
                common = set.intersection(*(set(branch.get("required", [])) for branch in variants))
                required.extend(key for key in sorted(common) if key not in required)
        missing = [key for key in required if key not in error.instance]
        result["missing_keys"] = [key for key in missing[:64] if isinstance(key, str) and len(key) <= 96]
        result["missing_keys_complete"] = len(missing) <= 64 and len(result["missing_keys"]) == len(missing)
    expected = schema.get("type")
    if isinstance(expected, str) or isinstance(expected, list) and len(expected) <= 7 and all(isinstance(value, str) for value in expected):
        result["expected_type"] = expected
    choices = schema.get("enum", [schema["const"]] if "const" in schema else None)
    if isinstance(choices, list):
        values = [value for value in choices[:16] if value is None or type(value) in (bool, int, float)
                  or isinstance(value, str) and len(value) <= 96]
        result["allowed_values"] = values
        result["allowed_values_complete"] = len(values) == len(choices)
    for key in ("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minItems", "maxItems", "minLength", "maxLength"):
        if type(schema.get(key)) in (int, float):
            result[key] = schema[key]
    return result


def schema_error_details(name, error):
    """Bounded hints from the selected bundled schema, never ValidationError.message or input values."""
    names = _property_names(tool_validator(name).schema)
    result = {"tool": name, **_schema_issue(error, names), "validation_scope": "selected_tool_arguments"}
    if error.context:
        context = error.context
        variants = error.schema.get(error.validator) if isinstance(error.schema, dict) else None
        if error.validator in {"oneOf", "anyOf"} and isinstance(error.instance, dict) and isinstance(variants, list) and 0 < len(variants) <= 64:
            selectors = [set(key for key, value in branch.get("properties", {}).items()
                             if isinstance(value, dict) and ("const" in value or "enum" in value))
                         if isinstance(branch, dict) else set() for branch in variants]
            for key in sorted(set.intersection(*selectors))[:8]:
                if key not in error.instance:
                    continue
                matches = [index for index, branch in enumerate(variants)
                           if tool_validator(name).evolve(schema=branch["properties"][key]).is_valid(error.instance[key])]
                if len(matches) != 1:
                    continue
                chosen = [issue for issue in error.context if issue.relative_schema_path and issue.relative_schema_path[0] == matches[0]]
                if not chosen:
                    continue
                result["selected_alternative"] = {"index": matches[0], "discriminator": key}
                hint = _schema_issue(error, names, variants[matches[0]])
                result.update({field: hint[field] for field in ("allowed_keys", "allowed_keys_complete", "additional_keys_allowed") if field in hint})
                context = chosen
                for issue in chosen:
                    if issue.validator == "required" and issue.absolute_path == error.absolute_path:
                        hint = _schema_issue(issue, names)
                        result.update({field: hint[field] for field in ("missing_keys", "missing_keys_complete", "allowed_keys", "allowed_keys_complete", "additional_keys_allowed") if field in hint})
                        break
                break
        # These are alternatives, not a merged set of mandatory keys. Do not
        # invent a branch when a discriminated union has not matched.
        pending, alternatives, examined = list(islice(context, 16)), [], 0
        limited = len(context) > 16
        while pending and examined < 16 and len(alternatives) < 4:
            issue = pending.pop(0); examined += 1
            if issue.context:
                children = list(islice(issue.context, 17 - examined))
                limited |= len(children) > 16 - examined
                pending[:0] = children[:16 - examined]
                continue
            alternative = _schema_issue(issue, names)
            if alternative not in alternatives:
                alternatives.append(alternative)
        result["alternatives"] = alternatives
        result["alternatives_complete"] = not limited and not pending
    # A bounded number of Unicode schema names may still be large in UTF-8.
    # Keep actionable primary hints and mark every reduced list incomplete.
    while len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > 8192:
        if result.get("alternatives"):
            result["alternatives"].pop()
            result["alternatives_complete"] = False
            continue
        for key in ("allowed_keys", "allowed_values", "missing_keys"):
            if result.get(key):
                result[key] = result[key][:len(result[key]) // 2]
                result[key + "_complete"] = False
                break
        else:
            break
    return result


def validate_tool_arguments(name, arguments):
    """Shared CLI/adapter/workflow validation; no configuration read, takeover or game RPC."""
    from .bridge import BridgeError
    from .tools import TOOL_BY_NAME
    if name not in TOOL_BY_NAME:
        raise BridgeError("UNKNOWN_TOOL", "No such Minecraft bridge tool.")
    try:
        json.dumps(arguments, allow_nan=False)
        tool_validator(name).validate(arguments)
        if name == "mcb_world_scan":
            from .scan_regions import validate_scan_region
            validate_scan_region(arguments)
    except jsonschema.ValidationError as error:
        raise BridgeError("INVALID_ARGUMENTS", "Arguments do not satisfy this tool's input schema.",
                          **schema_error_details(name, error)) from None
    except (ValueError, TypeError, RecursionError):
        raise BridgeError("INVALID_ARGUMENTS", "Arguments must contain finite JSON values.") from None

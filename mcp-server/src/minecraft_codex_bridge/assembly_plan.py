"""Bounded declarations compiled into existing native construction and AE goals."""
from __future__ import annotations

import copy

from .ae_task_schema import ATTACHMENT, CONFIG_TARGET, INSTALL_TARGET, TARGET, obj, integer
from .bridge import BridgeError

AXES = ("x", "y", "z")
VECTORS = ((0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0))
OFFSET = obj({axis: integer(-128, 128) for axis in AXES}, AXES)
NAME = {"type": "string", "minLength": 1, "maxLength": 48, "pattern": "^[a-zA-Z0-9_.-]+$"}


def require(value, reason, message):
    if not value:
        raise BridgeError("ASSEMBLY_PLAN_INVALID", message, reason=reason)


def port_groups_schema(roles, selector, item):
    return {"type": "array", "minItems": 1, "maxItems": 8, "items": obj({
        "id": NAME, "role": {"enum": list(roles)}, "groupSelector": selector,
        "hotbarSlot": integer(0, 8), "item": item,
        "installPhase": {"enum": ["before_shell", "after_shell"], "default": "before_shell",
            "description": "Explicit creative-only after_shell replaces this task's confirmed public casing after native construction; no tile removal or failed-placement replay. Default before_shell requires native preservation."},
        "positions": {"type": "array", "minItems": 1, "maxItems": 32, "uniqueItems": True, "items": OFFSET},
        "expectedRoleIndices": {"type": "array", "minItems": 1, "maxItems": 32, "uniqueItems": True, "items": integer(0, 127)},
    }, ("id", "role", "groupSelector", "hotbarSlot", "item", "positions")),
        "description": "Ordered controller-relative world-XYZ positions in an exact public hint group. Groups with the same role and exact item may share one real source stack; survival demand is summed across all groups using it, total ports <=32. IDs become groupId.0, groupId.1, etc. Optional expectedRoleIndices must match the position count and the actual controller registered list indices after formation, for ordered recipe inputs. Existing inventory only; ports_first avoids projected debug-bus replacement. Optional installPhase=after_shell explicitly defers a creative group to existing tile-free owned-casing replacement; it requires public casing candidates and final actual registration. Preinstalled ports still require preservation; deferred ports are reported separately, never as preserved. A caller-pinned empty cell must belong to the complete exact public group, but partial category labels or empty suggestions do not establish role acceptance or rejection. Empty suggestions cannot authorize after-shell replacement. The declared role/item needs native placement, phase-specific identity checks and actual registered-role acceptance."}


def relative_target(schema):
    return obj({**{k: copy.deepcopy(v) for k, v in schema["properties"].items() if k not in AXES},
        "anchor": {"type": "string", "minLength": 1, "maxLength": 64}, "offset": OFFSET},
        (*[k for k in schema["required"] if k not in AXES], "anchor", "offset"))


REL_TARGET = relative_target(TARGET)
REL_INSTALL = copy.deepcopy(INSTALL_TARGET)
REL_INSTALL["properties"]["attachment"] = relative_target(ATTACHMENT)
REL_INSTALL["properties"].update(layoutRole={"enum": ["equipment", "io_routing"]},
    layoutPlacement={"enum": ["above_ground", "hidden_bottom"]})
REL_CONFIG = copy.deepcopy(CONFIG_TARGET)
REL_CONFIG["properties"]["target"] = REL_TARGET
NETWORK = obj({"id": NAME, "endpoints": {"type": "array", "minItems": 1, "maxItems": 32,
    "items": obj({"target": REL_TARGET, "nodeSide": integer(0, 6)}, ("target", "nodeSide"))}}, ("id", "endpoints"))
CONNECTION_SCHEMA = obj({
    "install": {"type": "array", "maxItems": 32, "items": REL_INSTALL},
    "configure": {"type": "array", "maxItems": 32, "items": REL_CONFIG},
    "networks": {"type": "array", "maxItems": 16, "items": NETWORK},
}, ())
CONNECTION_SCHEMA["anyOf"] = [{"required": [key], "properties": {key: {"minItems": 1}}}
    for key in ("install", "configure", "networks")]
CONNECTION_SCHEMA["description"] = (
    "Relative AE part installation/configuration and optional same-grid/distinct-grid assertions. Anchor is controller or a pinned portGroup member ID. "
    "Installation order is literal; allowedSides and expectedPart.side declare input/return faces, configure supplies native priority/settings. "
    "All install materials are checked before construction; sources must be separate from construction. "
    "With placement planning, installed cells automatically join layoutComponents as equipment above ground by default; only explicitly declared pure IO can be hidden_bottom. "
    "Native per-part and per-setting acknowledgements are retained; network assertions require aeWorldNetworkVersion=1 and one batch of <=16 host cells/32 endpoints. "
    "nodeSide=6 selects the sole public node of a mounted part; block hosts use their actual public side. "
    "Uses preserved inventory without restoreInventory. No mutation replay or inferred machine recipe order.")


def expand_ports(arguments):
    args = copy.deepcopy(arguments)
    ports = args.setdefault("requestedPorts", [])
    groups = args.get("portGroups", [])
    if not groups:
        return args
    require(not args.get("generateMaterials", False), "port_groups_existing_materials", "Port groups use real inventory sources.")
    require(args.get("constructionOrder", "ports_first") == "ports_first", "port_groups_order", "Port groups must precede the shell.")
    args["constructionOrder"] = "ports_first"
    names, positions = set(), set()
    for group in groups:
        phase = group.get("installPhase", "before_shell")
        require(phase in ("before_shell", "after_shell"), "port_group_install_phase", "Choose an explicit supported port installation phase.")
        require(phase != "after_shell" or not args.get("actualSurvival", False),
            "survival_after_shell_unsupported", "After-shell groups reuse creative casing replacement; survival replacement accounting is not supported.")
        require(group["id"] not in names and group["id"] != "controller", "port_group_id", "Port group IDs must be unique and not controller.")
        names.add(group["id"])
        require("expectedRoleIndices" not in group or len(group["expectedRoleIndices"]) == len(group["positions"]),
            "port_group_order_length", "Expected native role indices must match every declared group position.")
        for ordinal, offset in enumerate(group["positions"]):
            point = tuple(offset[a] for a in AXES)
            require(point != (0, 0, 0) and point not in positions, "port_group_position_overlap", "Declared ports must have unique positions outside the controller cell.")
            positions.add(point)
            port = {k: copy.deepcopy(group[k]) for k in ("role", "hotbarSlot", "groupSelector")}
            port.update(id=group["id"] + "." + str(ordinal), portGroup=group["id"], ordinal=ordinal,
                relativePosition=copy.deepcopy(offset), declaredItem=copy.deepcopy(group["item"]), installPhase=phase)
            if port["role"] == "item_output":
                port["ordinaryOutputItem"] = copy.deepcopy(group["item"])
            if "expectedRoleIndices" in group:
                port["expectedRoleIndex"] = group["expectedRoleIndices"][ordinal]
            ports.append(port)
    require(len(ports) <= 32, "port_group_budget", "At most 32 native port placements are allowed per task.")
    return args


def construction_slots(args):
    """Explicit groups may share identical role/item stock; old declarations stay strict."""
    reserved = {args["blueprintHotbarSlot"]}
    if "supportHotbarSlot" in args:
        require(args["supportHotbarSlot"] not in reserved, "declaration_overlap", "Blueprint and support sources must differ.")
        reserved.add(args["supportHotbarSlot"])
    owners = {}
    for index, port in enumerate(args["requestedPorts"]):
        slot = port["hotbarSlot"]
        owner = ("group", port["role"], port["declaredItem"]["registryName"], port["declaredItem"]["metadata"]) \
            if "portGroup" in port else ("legacy", index)
        require(slot not in reserved and (slot not in owners or owners[slot] == owner), "declaration_overlap",
            "Only explicit port groups with identical role and item may share a source slot.")
        owners[slot] = owner
    return sorted(reserved | owners.keys())


def verify_group_materials(args, materials, survival):
    sources = {}
    for group in args.get("portGroups", []):
        slot = group["hotbarSlot"]
        source = sources.setdefault(slot, {"item": group["item"], "role": group["role"], "count": 0})
        require(source["item"] == group["item"] and source["role"] == group["role"],
            "declaration_overlap", "A shared group source requires identical declared role and item.")
        source["count"] += len(group["positions"])
    for slot, demand in sources.items():
        source = materials[slot]
        require(source["item"] == demand["item"] and source["count"] >= (demand["count"] if survival else 1),
            "port_group_material_shortage", "Each shared source needs its exact declared real item and sufficient aggregate count across all groups before any build write.")


def compile_connections(args):
    """Resolve only explicit coordinates; runtime ports must later match these exact cells."""
    plan = args.get("connectionPlan")
    if plan is None:
        return None
    require(not args.get("generateMaterials", False) and not args.get("restoreInventory", False),
        "connection_inventory_preservation", "Connection plans use preserved inventory without restoreInventory; native AE consumption has its own ledger.")
    anchors = {"controller": {a: 0 for a in AXES}}
    anchors.update({p["id"]: p["relativePosition"] for p in args["requestedPorts"] if "relativePosition" in p})
    def resolve(ref):
        require(ref["anchor"] in anchors, "connection_anchor_unknown", "Connection anchors must be controller or an exact pinned port ID.")
        offset = {a: anchors[ref["anchor"]][a] + ref["offset"][a] for a in AXES}
        require(all(-128 <= v <= 128 for v in offset.values()), "connection_bounds", "Connection offsets exceed the bounded assembly envelope.")
        target = {k: copy.deepcopy(v) for k, v in ref.items() if k not in ("anchor", "offset")}
        target.update({a: args["controller"][a] + offset[a] for a in AXES})
        require(0 <= target["y"] <= 255 and all(-29999996 <= target[a] <= 29999996 for a in ("x", "z")),
            "connection_world_bounds", "Resolved AE target lies outside world bounds.")
        return target
    result = {"install": [], "configure": [], "networks": [], "layoutComponents": []}
    occupied = set()
    for index, row in enumerate(plan.get("install", [])):
        declaration = {k: copy.deepcopy(v) for k, v in row.items() if k not in ("layoutRole", "layoutPlacement")}
        declaration["attachment"] = resolve(row["attachment"])
        side = declaration["attachment"]["allowedSides"][0]
        same = declaration.get("placement") == "host"
        require(row["expectedPart"]["side"] in (6, side if same else side ^ 1), "connection_part_side", "Mounted part must face the declared attachment.")
        cell = {a: declaration["attachment"][a] + (0 if same else VECTORS[side][i]) for i, a in enumerate(AXES)}
        key = (*[cell[a] for a in AXES], row["expectedPart"]["side"])
        require(key not in occupied, "connection_duplicate_mount", "A plan cannot install two parts into one mount.")
        occupied.add(key)
        offset = {a: cell[a] - args["controller"][a] for a in AXES}
        require(0 <= cell["y"] <= 255 and all(-128 <= v <= 128 for v in offset.values())
            and all(-29999996 <= cell[a] <= 29999996 for a in ("x", "z")), "connection_bounds", "Installed cell exceeds the bounded assembly/world envelope.")
        role, placement = row.get("layoutRole", "equipment"), row.get("layoutPlacement", "above_ground")
        require(placement != "hidden_bottom" or role == "io_routing", "connection_hidden_equipment", "Only explicit pure IO can reserve the hidden bottom layer.")
        result["layoutComponents"].append({"id": "ae-plan-" + str(index), "role": role, "placement": placement,
            "relativeBounds": {"min": offset.copy(), "max": offset.copy()}})
        result["install"].append(declaration)
    result["configure"] = [{**copy.deepcopy(row), "target": resolve(row["target"])} for row in plan.get("configure", [])]
    names = set()
    for network in plan.get("networks", []):
        require(network["id"] not in names, "connection_network_id", "Network IDs must be unique.")
        names.add(network["id"])
        result["networks"].append({"id": network["id"], "endpoints": [
            {**copy.deepcopy(row), "target": resolve(row["target"])} for row in network["endpoints"]]})
    require(sum(len(n["endpoints"]) for n in result["networks"]) <= 32, "connection_network_budget", "At most 32 explicit network endpoints per plan.")
    require(len({tuple(e["target"][a] for a in AXES) for n in result["networks"] for e in n["endpoints"]}) <= 16,
        "connection_network_host_budget", "Network acceptance uses one owner-thread batch with at most 16 distinct host cells.")
    return result


async def install_connections(task, plan, report):
    """Configure an installed part while nearby, retaining declared config order.

    Only the next configuration may be advanced, and only onto the exact part
    just confirmed by the native install receipt. All other configurations wait.
    """
    configure_index = 0
    for index, declaration in enumerate(plan["install"]):
        report.update(stage="install", pendingIndex=index)
        installed = await task.install(declaration)
        report["installed"].append(installed)
        while configure_index < len(plan["configure"]):
            config = plan["configure"][configure_index]
            target = config["target"]
            if (not all(target.get(axis) == installed["target"].get(axis) for axis in AXES)
                    or target.get("targetIdentity", {}).get("part") != installed.get("part")):
                break
            report.update(stage="configure", pendingIndex=configure_index)
            report["configured"].append(await task.configure(config))
            configure_index += 1
    for index in range(configure_index, len(plan["configure"])):
        report.update(stage="configure", pendingIndex=index)
        report["configured"].append(await task.configure(plan["configure"][index]))


async def verify_networks(task, networks):
    evidence, grids = [], set()
    targets = [e["target"] for n in networks for e in n["endpoints"]]
    observations = iter(await task.worlds(targets) if targets else [])
    for declaration in networks:
        endpoints = []
        for endpoint in declaration["endpoints"]:
            target = endpoint["target"]
            world = next(observations)
            mounting_side = target["targetIdentity"].get("part", {}).get("side", -1)
            hosts = [h for h in world.get("ae", {}).get("hosts", []) if h.get("mountingSide") == mounting_side]
            nodes = hosts[0].get("network", {}).get("nodes", []) if len(hosts) == 1 else []
            matches = [n for n in nodes if n.get("side") == endpoint["nodeSide"] and n.get("available") is True]
            require(len(matches) == 1 and isinstance(matches[0].get("gridId"), str) and bool(matches[0]["gridId"]),
                "connection_network_unverified", "Every endpoint requires its exact current public node/grid; missing evidence is not isolation.")
            endpoints.append({"target": target, "node": matches[0]})
        ids = {e["node"]["gridId"] for e in endpoints}
        require(len(ids) == 1 and not ids & grids, "connection_network_mismatch", "Each declared network must share one grid distinct from the other declared networks.")
        grids.update(ids)
        evidence.append({"id": declaration["id"], "endpoints": endpoints, "gridId": next(iter(ids))})
    return {"verified": True, "networks": evidence, "nonAtomic": True,
        "scope": "Declared endpoint membership/isolation in one bounded owner-thread batch; active power and recipe input ordering are not inferred."}

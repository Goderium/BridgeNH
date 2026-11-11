"""Finite native placement of a small, explicitly requested unfinished shell tail."""

from .recipe_tasks import _require


def validate_terminal(value, material_source):
    batches = value.get("batches")
    _require(material_source is None and value.get("status") == "no_progress"
        and value.get("nativeDone") is False and value.get("partial") is True
        and value.get("outcomeUnknown") is False and value.get("formationVerified") is False
        and value.get("materialAccountingComplete") is True
        and isinstance(batches, list) and 1 <= len(batches) <= 32
        and type(value.get("callsStarted")) is int and type(value.get("callsCompleted")) is int
        and value["callsStarted"] == value["callsCompleted"] == len(batches)
        and isinstance(batches[-1], dict) and type(batches[-1].get("nativeReturn")) is int
        and batches[-1]["nativeReturn"] == 0
        and batches[-1].get("committedItems") == [] and batches[-1].get("playerDeductions") == [],
        "survival_shell_tail_terminal_unavailable", "Only a fully completed, known no-progress player-material callback may precede separate air-cell placement; no native callback is replayed.")


def allocate(points, choices, inventory):
    """Reserve all materials before any tail placement, allowing shared sources."""
    counts = {row["index"]: row["item"]["count"] for row in inventory if row["item"] is not None}
    plan = []
    for point in sorted(points, key=lambda p: (len(choices.get((p["x"], p["y"], p["z"]), [])), p["y"], p["x"], p["z"])):
        options = choices.get((point["x"], point["y"], point["z"]), [])
        choice = next((c for c in options if c["publicCandidate"].get("count") == 1 and counts.get(c["sourceIndex"], 0) > 0), None)
        _require(choice is not None, "survival_shell_tail_materials_unavailable",
            "Every remaining captured air cell needs a fresh exact non-tile candidate and sufficient aggregate finite player materials before placement.")
        counts[choice["sourceIndex"]] -= 1
        plan.append({**choice, "position": point})
    return sorted(plan, key=lambda c: (c["position"]["y"], c["position"]["z"], c["position"]["x"]))


async def finish_air_tail(task):
    from .construction_tasks import _key, _xyz, _survival_inventory_entries
    task.phase = "survival_shell_tail_preflight"
    _require(task.actual_survival and task.survival_ae_source is None and task.survival_capture_points
        and task._survival_frontier_binding is None and task._frontier_seed_borrow is None,
        "survival_shell_tail_context_unavailable", "Tail completion requires this task's complete captured positions and settled player material ownership.")
    report = task.survival_shell_completion = {"status": "preflight", "maxCells": 16, "placements": [],
        "nativeBuildReplayed": False, "nativeDone": False, "formationVerified": False,
        "scope": "Only literal-air captured public non-tile candidates are placed; existing blocks are never replaced. Actual formation remains a separate native check."}
    await task.verify_declared_ports("ports_before_air_tail", preserved=True, before_shell_only=True)
    task.phase = "survival_shell_tail_preflight"
    points = list(task.survival_capture_points.values())
    actual = {}
    await task.frontier_pass_blocks(points, actual)
    _require(all(actual[_key(point)].get("loaded") is True for point in points),
        "survival_shell_tail_unloaded", "Unloaded geometry cannot be treated as an empty shell cell.")
    missing = [point for point in points if actual[_key(point)]["registryName"] == "minecraft:air"]
    report["remainingPositions"] = [_xyz(point) for point in missing]
    _require(len(missing) <= 16, "survival_shell_tail_too_large", "The independent native placement continuation is limited to16 exact cells.")
    _require(all("tileClass" not in actual[_key(point)] for point in missing)
        and not any(_key(port["position"]) in {_key(point) for point in missing} for port in task.selected_ports),
        "survival_shell_tail_port_missing", "Existing declared ports cannot be adopted as missing shell material.")
    for point in missing:
        task.survival_position_candidates.pop(_key(point), None)
    await task.read_position_candidates(missing)
    inventory = await task.frontier_inventory()
    plan = allocate(missing, task.frontier_seed_choices(inventory, missing), _survival_inventory_entries(inventory))
    report.update(status="placing", materialsPreflightConfirmed=True)
    for choice in plan:
        point = choice["position"]
        current = await task.block(point)
        _require(current["registryName"] == "minecraft:air" and "tileClass" not in current,
            "survival_shell_tail_changed", "A planned air cell changed; never replace it or repeat a prior action.")
        slot = await task.prepare_verified_frontier_seed(choice)
        record = {"position": _xyz(point), "item": dict(choice["item"]), "sourcePlayerInventoryIndex": choice["sourceIndex"],
            "publicCandidate": choice["publicCandidate"], "automaticReplayAllowed": False}
        report["placements"].append(record)
        await task.place(point, slot, "survival_shell_tail_place", record)
        await task.release_frontier_seed_source()
    # Fresh native placement receipts already prove each result and exact finite deduction.
    # Keep nativeDone=false: caller still has to run ordinary formation/registered-role acceptance.
    report.update(status="completed", placedCells=len(plan), materialsAccounted=True)

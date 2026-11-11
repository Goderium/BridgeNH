"""Fixed, read-only operating checks over existing guarded workflows.

Knowledge is versioned advice, never a machine-specific control adapter or a
claim that a successful observation establishes production acceptance.
"""
from copy import deepcopy
from .conveniences import json_size


GUIDANCE = {
    "machine": {
        "scope": "Generic loaded GT machine public state; local developer authority required.",
        "evidence": "MCB MachineDataAdapter; GT5U 5.09.54.205 MTEMultiBlockBase.runMachine/stopMachine and ProcessingLogic.validateAndCalculateRecipe/applyRecipe.",
        "checks": [
            "Read actual owner/dimension, state, structureFormed, modes, shutdownReason and checkRecipeResult. Missing or unavailable getters remain unknown.",
            "A cached successful recipe message can remain visible after structural shutdown. Reconcile current active/progress and native shutdown/structure diagnostics; follow the GUI's invalid-block coordinate before repairing, then recheck and observe another actual cycle.",
            "GT5U 5.09.54.205 base behavior: disabling allowedToWork prevents the next batch but can let the current batch finish. Fault stop clears progress and pending outputs. Overrides may differ; do not equate these controls.",
            "Recipe-start hooks can fail after input consumption. Before retrying, reconcile actual input/output quantities; failure does not imply rollback.",
            "Completion does not universally wait for output space. Verify actual products and remaining inputs, preserving NBT and decimal long quantities. Cached lastRecipe/calculation, progress reset or recipe counters alone do not prove delivery.",
            "Check actual voltage, amperage, stored energy, maintenance, recipe conditions and public structure limits separately. NEI display overclock is not machine EU/t; custom glass/coil or mode restrictions need their own public evidence.",
            "Before connecting logistics, inspect each actual hatch/bus work face independently of controller alignment. After replacing a component, require a fresh native structure check and observe structureFormed; successful placement alone does not establish formation.",
            "For cross-dimension operation, cover the union of complete structure bounds, external IO, power, storage and both network endpoints with actual force loading. A loaded controller or claimed chunk does not prove its whole site is force loaded.",
            "A displayed bonus reaching its cap does not prove that the last completed cycle used that bonus. Verify the machine's accounting order and observe a subsequent completed cycle plus actual output or account change; keep multiplier, throughput and net energy separate.",
        ],
        "nextTools": ["mcb_machine_watch", "mcb_machine_inspect", "mcb_world_inspect", "mcb_recipe_once"],
        "boundary": "No per-machine rule table, recipe simulation, AE network traversal, material generation or machine control. Reuse goal-workflow acceptance instead of repeating this check around it.",
    },
    "ae": {
        "scope": "The currently open AE GUI's synchronized client data, one page.",
        "evidence": "MCB AeDataAdapter/AeControlAdapter; GTNH AE2 knowledge notes (1080/AE2FC 1.5.113, documentation-level guidance, not live compatibility proof).",
        "checks": [
            "Use the returned screen/window and canonical resourceId; virtual resources, phantom pattern slots and real inventory slots are different namespaces.",
            "A processing pattern describes inputs and expected outputs; it does not execute the target machine. Check catalysts, containers, chance outputs, full NBT, exact counts and omitted pages after NEI transfer.",
            "requestedPending or startReady=false means preview is not ready. Observe synchronization without resending craft_request; no automatic start.",
            "A blocked job may need CPU, interface pending sends, machine inputs and output return-path evidence. CPU cancellation does not prove interface buffers are empty, and visible external storage does not prove CPU receipt.",
        ],
        "nextTools": ["mcb_ae", "mcb_ae_action", "mcb_operation_check"],
        "boundary": "Does not open another screen, enumerate a whole network, clear pending buffers, encode patterns or start/cancel a job. Use ae_buffers only for an explicitly identified loaded owner with advertised support.",
    },
    "ae_buffers": {
        "scope": "Explicit loaded owner AE details via world.inspect(includeAe=true); local developer capability required.",
        "evidence": "MCB AeWorldAdapter and existing world.inspect includeAe contract.",
        "checks": [
            "Check each mounted host, pending-send coverage and native cell resources. Missing/unavailable data is unknown, never an empty buffer.",
            "Bind owner, dimension, mounting side and resource identity. This owner is not the entire network or every interface belonging to a CPU job.",
            "After cancellation, reconcile pending sends, actual machine inputs and in-flight outputs before another order. Do not flush, extract or resubmit automatically.",
        ],
        "nextTools": ["mcb_world_inspect", "mcb_ae"],
        "boundary": "AE buffer observation only; it does not grant general machine control or prove production/cancellation completion.",
    },
    "nei": {
        "scope": "Current NEI view and one visible-recipe page, pinned to the same screen and context token.",
        "evidence": "MCB NEI view/native-control contracts; knowledge notes integration/nei-native-controls and validation/mcb-wiki-workflow.",
        "checks": [
            "Use original handler/recipe indices and complete resource identities. Preserve coverage, nextOffset and contextToken; omitted entries are unknown.",
            "Recipe/display-overclock values are lookup evidence, not actual inventory, machine processing or server production. Chance units and nonconsumed tools need their original semantics.",
            "NEI ghosts and structure candidates are configuration/planning templates. Mark through the supported target adapter, verify synchronized configuration and release leftovers; never grant material to configure a survival ghost slot.",
        ],
        "nextTools": ["mcb_nei_view", "mcb_nei", "mcb_nei_navigate", "mcb_nei_item_action"],
        "boundary": "No search, page navigation, overlay transfer, bookmark editing, acquisition, rendering capture or global recipe enumeration. AE pattern acceptance belongs to AE; production acceptance belongs to the machine workflow.",
    },
}


def plan(arguments):
    kind = arguments["kind"]
    target = arguments.get("target", {})
    if kind in {"machine", "ae_buffers"}:
        tool = "mcb_machine_inspect" if kind == "machine" else "mcb_world_inspect"
        args = {**target, **({"includeAe": True} if kind == "ae_buffers" else {})}
        fields = (["x", "y", "z", "dimension", "metaTileId", "metaClass", "state", "modes",
            "shutdownReason", "checkRecipeResult", "processing", "orientation", "components",
            "outputStorage", "fluidInputs", "coverage", "budget"] if kind == "machine" else
            ["x", "y", "z", "dimension", "registryName", "metadata", "tileClass", "ae", "coverage"])
        steps = [{"id": kind, "kind": "read", "tool": tool, "args": args, "fields": fields}]
        if kind == "ae_buffers":
            steps[:0] = [
                {"id": "ae_capability", "kind": "read", "tool": "mcb_health", "fields": ["aeWorldReadVersion", "profile"]},
                {"id": "ae_supported", "kind": "assert", "source": "ae_capability",
                 "condition": {"path": "aeWorldReadVersion", "operator": "equals", "value": 1}},
                {"id": "ae_authority", "kind": "assert", "source": "ae_capability",
                 "condition": {"path": "profile", "operator": "equals", "value": "developer"}},
            ]
    else:
        binding = {"screenId": {"step": "gui", "path": "screenId"}}
        steps = [{"id": "gui", "kind": "read", "tool": "mcb_gui",
            "fields": ["screenId", "windowId", "screenKind", "screenOpen", "coverage"]}]
        if kind == "ae":
            steps.append({"id": "ae", "kind": "read", "tool": "mcb_ae", "bind": binding,
                "args": {"scope": arguments.get("scope", "auto"), "offset": 0, "limit": 16}})
        else:
            steps.extend([
                {"id": "nei_summary", "kind": "read", "tool": "mcb_nei_view", "bind": binding,
                 "args": {"section": "summary", "limit": 1}},
                {"id": "nei_recipes", "kind": "read", "tool": "mcb_nei_view",
                 "bind": {**binding, "contextToken": {"step": "nei_summary", "path": "contextToken"}},
                 "args": {"section": "recipes", "scope": "visible", "offset": 0, "limit": 4}},
            ])
    return {"expectedSessionId": arguments["expectedSessionId"],
        **({"expectedWorld": arguments["expectedWorld"]} if "expectedWorld" in arguments else {}),
        "timeout_ms": arguments.get("timeout_ms", 10000), "max_return_bytes": 57344, "steps": steps}


def bounded_check_result(result):
    """Keep verdicts/counts and exact retained rows, with explicit omissions."""
    result["outputComplete"] = True
    for key in ("schedule", "checks", "balances", "issues", "pairwiseBatchMultiples", "recipeBatches", "externalInputs", "blocked", "observations"):
        rows = result.get(key)
        if not isinstance(rows, list) or json_size(result) <= 65536:
            continue
        total = len(rows)
        result["outputComplete"] = False
        omitted = result.setdefault("omissions", {}).setdefault(key, {"total": total, "returned": total})
        while result[key] and json_size(result) > 65536:
            result[key] = result[key][:len(result[key]) // 2]
            omitted["returned"] = len(result[key])
    return result


async def run_check(client, arguments, lock, *, runner):
    if arguments["kind"] == "process":
        from .production_planning import check_process
        return bounded_check_result(check_process(arguments["process"]))
    if arguments["kind"] == "site":
        from .site_checks import check_site
        return bounded_check_result(await check_site(client, arguments, lock, runner=runner))
    result = await runner(client, plan(arguments), lock)
    return {**result, "checkKind": arguments["kind"], "completionScope": "read_only_observations",
        "productionVerified": False, "automaticReplayAllowed": False,
        "guidance": deepcopy(GUIDANCE[arguments["kind"]])}

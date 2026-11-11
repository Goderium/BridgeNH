from copy import deepcopy
import anyio
import pytest

from minecraft_codex_bridge.site_checks import check_site, layout_checks
from minecraft_codex_bridge.server import _validate
from minecraft_codex_bridge.workflows import validate_plan, project

POS = {"x": 0, "y": 64, "z": 0}
ARGS = {"kind": "site", "expectedSessionId": "s", "expectedWorld": "w", "site": {"dimension": 180,
    "machines": [{"id": "furnace", "position": POS, "metaTileId": 1000}],
    "endpoints": [{"id": "bus", "position": {**POS, "x": 1}, "targetIdentity": {"registryName": "gregtech:gt.blockmachines", "metaTileId": 2718},
                   "mountingSide": -1, "network": "main"}]}}


def observations():
    machine = {**POS, "source": "integrated_server", "dimension": 180, "metaTileId": 1000, "state": {"allowedToWork": True},
        "processing": {"structureFormed": True, "repairStatus": 6, "idealMaintenanceStatus": 6},
        "components": {"hasMore": False, "softBudgetPartial": False, "offset": 0, "nextOffset": 1, "total": 1,
            "entries": [{**POS, "y": 67, "dimension": 180, "metaTileId": 96, "role": "muffler", "index": 0, "valid": True,
                         "metaClass": "gregtech.api.metatileentity.implementations.MTEHatchMuffler"}]}}
    owners = {(0, 67, 0): {**POS, "y": 67, "dimension": 180, "gregtech": {"metaTileId": 96,
        "orientation": {"facing": {"available": True, "side": 1}}}},
        (0, 68, 0): {**POS, "y": 68, "dimension": 180, "registryName": "minecraft:air"},
        (1, 64, 0): {**POS, "x": 1, "dimension": 180, "registryName": "gregtech:gt.blockmachines", "gregtech": {"metaTileId": 2718},
            "ae": {"hosts": [{"mountingSide": -1, "network": {"complete": True,
                "nodes": [{"available": True, "active": True, "gridId": "actual-grid", "side": 6}]}}]}}}
    return machine, owners


async def execute(arguments, machine, owners, owner_context=None):
    calls = []
    async def runner(client, plan, lock):
        _validate("mcb_sequence", plan)
        validate_plan(plan, False)
        steps = []
        for step in plan["steps"]:
            assert step["kind"] == "read"
            calls.append(step)
            if step["tool"] == "mcb_machine_inspect":
                value = machine(step) if callable(machine) else machine
            else:
                value = {"source": "integrated_server", "dimension": 180, "results": [
                    {"ok": tuple(p[a] for a in "xyz") in owners, "data": owners.get(tuple(p[a] for a in "xyz"), {})}
                    for p in step["args"]["positions"]]}
                if owner_context is not None:
                    value.update(owner_context)
            steps.append({"id": step["id"], "observation": project(value, step)})
        return {"status": "completed", "steps": steps, "completedSteps": len(steps)}
    _validate("mcb_operation_check", arguments)
    result = await check_site(None, arguments, anyio.Lock(), runner=runner)
    return result, calls


async def test_formed_machine_online_bus_and_real_exhaust_check_use_three_bounded_reads():
    result, calls = await execute(ARGS, *observations())
    assert result["declaredChecksPassed"] and len(calls) == 3
    assert not result["productionVerified"] and not result["logisticsTransferVerified"]


def component_pages():
    machine, owners = observations()
    muffler = {**machine["components"]["entries"][0], "index": 1}
    header = {"total": 2, "roles": [{"role": "energy", "count": 1}, {"role": "muffler", "count": 1}],
              "directoryFingerprint": "stable-directory"}
    first = deepcopy(machine)
    first["components"] = {**header, "offset": 0, "nextOffset": 1, "hasMore": True, "softBudgetPartial": True,
                           "entries": [{"index": 0, "role": "energy", "valid": True}]}
    second = deepcopy(machine)
    second["components"] = {**header, "offset": 1, "nextOffset": 2, "hasMore": False, "softBudgetPartial": False,
                            "entries": [muffler]}
    return [first, second], owners


@pytest.mark.parametrize("remote", [False, True])
async def test_partial_cold_page_continues_once_and_checks_later_exhaust_without_restarting_site(remote):
    pages, owners = component_pages()
    context = {"source": "remote_server_tick", "sessionId": "s", "connectionId": "one"} if remote else None
    if context:
        for page in pages: page.update(context)
    result, calls = await execute(ARGS, lambda step: pages[step["args"].get("componentOffset", 0)], owners, context)
    assert result["declaredChecksPassed"] and len(calls) == 4
    assert calls[1]["args"]["componentOffset"] == 1 and calls[1]["args"]["componentLimit"] == 128
    assert any(c["check"] == "literal_air_clearance" and c["status"] == "passed" for c in result["checks"])
    assert next(c for c in result["checks"] if c["check"] == "component_coverage")["pages"] == 2


@pytest.mark.parametrize("fault", ["fingerprint", "total", "roles", "owner", "connection", "session", "source", "index",
                                    "offset", "nextOffset", "no_progress", "complete_but_partial", "missing_fingerprint"])
async def test_component_continuation_does_not_splice_changed_or_incomplete_evidence(fault):
    pages, owners = component_pages()
    context = {"source": "remote_server_tick", "sessionId": "s", "connectionId": "one"}
    for page in pages: page.update(context)
    p = pages[1]["components"]
    if fault == "fingerprint": p["directoryFingerprint"] = "changed"
    if fault == "total": p["total"] = 3; p["hasMore"] = True
    if fault == "roles": p["roles"] = [{"role": "energy", "count": 2}]
    if fault == "owner": pages[1]["x"] = 99
    if fault == "connection": pages[1]["connectionId"] = "two"
    if fault == "session": pages[1]["sessionId"] = "other"
    if fault == "source": pages[1]["source"] = "client"
    if fault == "index": p["entries"][0]["index"] = 0
    if fault == "offset": p["offset"] = 0
    if fault == "nextOffset": p["nextOffset"] = 1
    if fault == "no_progress": p.update(entries=[], nextOffset=1, hasMore=True)
    if fault == "complete_but_partial": p["softBudgetPartial"] = True
    if fault == "missing_fingerprint": pages[0]["components"].pop("directoryFingerprint")
    result, calls = await execute(ARGS, lambda step: pages[step["args"].get("componentOffset", 0)], owners, context)
    assert not result["declaredChecksPassed"] and result["unknownChecks"] > 0
    assert len([c for c in calls if c["tool"] == "mcb_machine_inspect"]) <= 2


async def test_continuation_shares_original_deadline(monkeypatch):
    from minecraft_codex_bridge import site_checks
    clock = [0.0]
    monkeypatch.setattr(site_checks.time, "monotonic", lambda: clock[0])
    pages, owners = component_pages()
    def machine(step):
        clock[0] = 11.0
        return pages[0]
    result, calls = await execute(ARGS, machine, owners)
    assert not result["declaredChecksPassed"] and len(calls) == 1
    assert result["checks"][-1]["error"]["code"] == "SITE_CHECK_TIMEOUT"


async def test_component_continuations_have_a_total_call_cap():
    pages, owners = component_pages()
    def machine(step):
        value = deepcopy(pages[0]); p = value["components"]; offset = step["args"].get("componentOffset", 0)
        p.update(total=40, offset=offset, nextOffset=offset+1, entries=[{"index": offset, "role": "energy", "valid": True}])
        return value
    result, calls = await execute(ARGS, machine, owners)
    assert not result["declaredChecksPassed"]
    assert len([c for c in calls if c["tool"] == "mcb_machine_inspect"]) == 33
    assert next(c for c in result["checks"] if c["check"] == "component_coverage")["reason"] == "component_page_limit"


async def test_invalid_component_on_last_page_remains_a_failure():
    pages, owners = component_pages()
    pages[1]["components"]["entries"][0]["valid"] = False
    result, _ = await execute(ARGS, lambda step: pages[step["args"].get("componentOffset", 0)], owners)
    assert result["readiness"] == "blocked"
    assert any(c["check"] == "component_valid" and c["status"] == "failed" for c in result["checks"])


async def test_directory_larger_than_native_page_limit_uses_same_continuation():
    pages, owners = component_pages()
    all_entries = [{"index": i, "role": "energy", "valid": True} for i in range(129)]
    for page, offset, end in ((pages[0], 0, 128), (pages[1], 128, 129)):
        page["components"].update(total=129, offset=offset, nextOffset=end, hasMore=end<129,
                                  softBudgetPartial=False, entries=all_entries[offset:end],
                                  roles=[{"role": "energy", "count": 129}])
    result, calls = await execute(ARGS, lambda step: pages[bool(step["args"].get("componentOffset", 0))], owners)
    assert result["declaredChecksPassed"]
    assert calls[1]["args"]["componentOffset"] == 128
    assert next(c for c in result["checks"] if c["check"] == "component_coverage")["total"] == 129


@pytest.mark.parametrize("fault", [None, "missing_session", "changed_connection", "client_source"])
async def test_remote_site_keeps_one_authoritative_connection_across_machine_ae_and_clearance(fault):
    machine, owners = observations()
    context = {"source": "remote_server_tick", "sessionId": "s", "connectionId": "connection-one"}
    machine.update(context)
    if fault == "missing_session": context.pop("sessionId")
    if fault == "changed_connection": context["connectionId"] = "connection-two"
    if fault == "client_source": context["source"] = "client"
    result, calls = await execute(ARGS, machine, owners, context)
    assert result["declaredChecksPassed"] is (fault is None)
    if fault is None: assert len(calls) == 3
    else: assert result["unknownChecks"] > 0


@pytest.mark.parametrize("failure", ["exhaust", "maintenance", "disabled", "offline", "missing", "partial", "identity"])
async def test_failure_and_missing_data_are_not_ready(failure):
    machine, owners = observations()
    if failure == "exhaust": owners[(0, 68, 0)]["registryName"] = "minecraft:stone"
    if failure == "maintenance": machine["processing"]["repairStatus"] = 5
    if failure == "disabled": machine["state"]["allowedToWork"] = False
    if failure == "offline": owners[(1, 64, 0)]["ae"]["hosts"][0]["network"]["nodes"][0]["active"] = False
    if failure == "missing": del owners[(0, 68, 0)]
    if failure == "partial": machine["components"]["hasMore"] = True
    if failure == "identity": owners[(1, 64, 0)]["gregtech"]["metaTileId"] = 123
    result, _ = await execute(ARGS, machine, owners)
    assert not result["declaredChecksPassed"]
    assert result["readiness"] == ("unknown" if failure in {"missing", "partial"} else "blocked")


async def test_missing_machine_specific_requirement_stays_unknown():
    arguments = deepcopy(ARGS)
    arguments["site"]["machines"][0]["requirements"] = [{"path": "processing.coilHeat", "operator": "gte", "value": 2140}]
    result, _ = await execute(arguments, *observations())
    assert result["readiness"] == "unknown"


async def test_same_network_and_explicit_isolation_checked_separately():
    arguments = deepcopy(ARGS)
    arguments["site"]["endpoints"].append({**arguments["site"]["endpoints"][0], "id": "other", "network": "subnet"})
    arguments["site"]["isolatedNetworks"] = [["main", "subnet"]]
    result, _ = await execute(arguments, *observations())
    assert any(c["check"] == "isolated_from_subnet" and c["status"] == "failed" for c in result["checks"])


def test_pipe_ae_overlap_and_exhaust_clearance_are_caught_before_installation():
    box = {"minX": 53, "maxX": 55, "minY": 5, "maxY": 5, "minZ": 85, "maxZ": 85}
    values = [{"id": "ae", "kind": "ae", "bounds": box}, {"id": "pipe", "kind": "pipe", "bounds": box}]
    assert layout_checks(values)[0]["status"] == "failed"
    values[1]["bounds"] = {**box, "minY": 6, "maxY": 6}
    assert layout_checks(values)[0]["status"] == "passed"


async def test_maximum_overlap_report_is_bounded_without_losing_failed_verdict():
    from minecraft_codex_bridge.operation_checks import run_check
    from minecraft_codex_bridge.conveniences import json_size
    args = {"kind": "site", "expectedSessionId": "s", "expectedWorld": "w", "site": {"dimension": 180,
        "layout": [{"id": "volume_" + str(i) + "x" * 30, "kind": "machine", "bounds": {
            "minX": 0, "maxX": 1, "minY": 64, "maxY": 65, "minZ": 0, "maxZ": 1}} for i in range(128)]}}
    async def runner(*a, **kw): pytest.fail("layout math should not access the game")
    result = await run_check(None, args, anyio.Lock(), runner=runner)
    assert result["readiness"] == "blocked" and result["failedChecks"] == 8128
    assert not result["outputComplete"] and json_size(result) <= 65536

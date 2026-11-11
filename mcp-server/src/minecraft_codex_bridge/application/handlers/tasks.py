"""Composable operations sharing one adapter state and workflow lock."""
from __future__ import annotations

from ...workflows import run_workflow
from ...recipe_tasks import run_recipe_once
from ...construction_tasks import run_blueprint_build
from ...placement_tasks import run_placement_preflight
from ...structure_transform import run_structure_transform
from ...inventory_moves import run_inventory_moves
from ...visit_planning import run_visit_plan
from ...qa_tasks import run_qa_test_once
from ...interaction_approach import run_interaction_approach, approach_summary
from ...operation_checks import run_check
from ...ae_tasks import run_configure
from ...ae_orders import run_order
from ..results import _result, completed_result
from ..control import invoke_control
from ..reads import recipe_pages

async def handle_qa_test_once(adapter, name, arguments, started_at):
    data = await run_qa_test_once(adapter.client, arguments, adapter.workflow_lock, runner=run_workflow, started_at=started_at)
    return completed_result(data)


async def handle_recipe_once(adapter, name, arguments, started_at):
    data = await run_recipe_once(adapter.client, arguments, adapter.workflow_lock, runner=run_workflow, query_reader=recipe_pages)
    return completed_result(data)


async def handle_approach(adapter, name, arguments, started_at):
    data = await run_interaction_approach(adapter.client, arguments, adapter.workflow_lock, control=invoke_control)
    if not arguments.get("includeDetails", False):
        data = approach_summary(data)
    return _result({"ok": True, "data": data})


async def handle_ae_order(adapter, name, arguments, started_at):
    data = await run_order(adapter.client, arguments, adapter.workflow_lock, invoke_control, adapter.ae_order_records)
    return _result({"ok": data["status"] == "completed" or data["resumable"], "data": data},
                   error=data["status"] != "completed" and not data["resumable"])


async def handle_ae_configure(adapter, name, arguments, started_at):
    data = await run_configure(adapter.client, arguments, adapter.workflow_lock, invoke_control, install=name == "mcb_ae_install")
    return completed_result(data)


async def handle_blueprint_build(adapter, name, arguments, started_at):
    data = await run_blueprint_build(adapter.client, arguments, adapter.workflow_lock, runner=run_workflow)
    return completed_result(data)


async def handle_placement_preflight(adapter, name, arguments, started_at):
    data = await run_placement_preflight(adapter.client, arguments)
    return completed_result(data)


async def handle_structure_transform(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await run_structure_transform(adapter.client, arguments)})


async def handle_inventory_moves(adapter, name, arguments, started_at):
    data = await run_inventory_moves(adapter.client, arguments, adapter.workflow_lock, invoke_control)
    return completed_result(data)


async def handle_plan_visits(adapter, name, arguments, started_at):
    return _result({"ok": True, "data": await run_visit_plan(adapter.client, arguments)})


async def handle_sequence(adapter, name, arguments, started_at):
    if "call" in arguments:
        from ...bridge import BridgeError
        raise BridgeError("SUPERVISED_MCP_REQUIRED", "Single-tool compatibility calls require the supervised MCP worker and its recovery guards.", outcome_unknown=False)
    data = await run_workflow(adapter.client, arguments, adapter.workflow_lock, observe=name == "mcb_observe")
    return completed_result(data)


async def handle_operation_check(adapter, name, arguments, started_at):
    data = await run_check(adapter.client, arguments, adapter.workflow_lock, runner=run_workflow)
    return completed_result(data)

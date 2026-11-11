"""Explicit application routes. Native tools use Adapter.invoke_native_tool."""

from ..tools import TOOL_BY_NAME
from .handlers import local, observations, tasks

HANDLERS = {
    'mcb_dialogue': local.handle_dialogue,
    'mcb_recording': local.handle_recording,
    'mcb_run_log': local.handle_run_log,
    'mcb_knowledge': local.handle_knowledge,
    'mcb_qa_test_once': tasks.handle_qa_test_once,
    'mcb_recipe_once': tasks.handle_recipe_once,
    'mcb_gui': observations.handle_gui,
    'mcb_approach': tasks.handle_approach,
    'mcb_ae_order': tasks.handle_ae_order,
    'mcb_ae_configure': tasks.handle_ae_configure,
    'mcb_ae_install': tasks.handle_ae_configure,
    'mcb_blueprint_build': tasks.handle_blueprint_build,
    'mcb_placement_preflight': tasks.handle_placement_preflight,
    'mcb_structure_transform': tasks.handle_structure_transform,
    'mcb_inventory_moves': tasks.handle_inventory_moves,
    'mcb_plan_visits': tasks.handle_plan_visits,
    'mcb_sequence': tasks.handle_sequence,
    'mcb_observe': tasks.handle_sequence,
    'mcb_wait_for': observations.handle_wait_for,
    'mcb_recipe_query': observations.handle_recipe_query,
    'mcb_machine_capture': observations.handle_machine_capture,
    'mcb_interact_sample': observations.handle_interact_sample,
    'mcb_ae_material_source': observations.handle_ae_material_source,
    'mcb_help': local.handle_help,
    'mcb_operation_check': tasks.handle_operation_check,
    'mcb_launch': local.handle_launch,
    'mcb_diagnostics': local.handle_diagnostics,
    'mcb_logs': local.handle_logs,
    'mcb_capture': observations.handle_capture,
    'mcb_machine_watch': observations.handle_machine_watch,
}


def validate_handlers():
    unknown = set(HANDLERS) - TOOL_BY_NAME.keys()
    missing = {name for name, spec in TOOL_BY_NAME.items() if spec.operation is None} - HANDLERS.keys()
    if unknown or missing:
        raise RuntimeError(f"Invalid application routes: unknown={sorted(unknown)}, missing={sorted(missing)}")


validate_handlers()

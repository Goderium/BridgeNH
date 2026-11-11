"""Ae tool schemas and native operation declarations."""

from .common import (
    DIMENSION,
    LIVE_ID,
    LONG_QUANTITY,
    POSITION,
    SCREEN_ID,
    TEXT_INPUT,
    ToolSpec,
    integer,
    named_actions,
    obj,
)
from .nei import (
    GHOST_SOURCE_SCHEMA,
)


AE_ACTION_SCHEMA = named_actions({
    "priority": integer(-2147483648, 2147483647), "expectedValue": {"type": "string", "minLength": 1, "maxLength": 64},
    "text": TEXT_INPUT, "field": {"type": "string", "enum": ["resources", "inputs", "outputs", "names", "crafting", "substitute", "beSubstitute", "inverted"]},
    "setting": {"type": "string", "enum": ["SORT_BY", "SORT_DIRECTION", "VIEW_MODE", "BLOCK", "STORAGE_FILTER", "ADVANCED_BLOCKING_MODE"]}, "value": {}, "type": LIVE_ID, "enabled": {"type": "boolean"},
    "resourceId": LIVE_ID, "mode": {"type": "string", "minLength": 1, "maxLength": 64}, "amount": LONG_QUANTITY,
    "lite": {"type": "boolean"}, "autoStart": {"type": "boolean"}, "follow": {"type": "boolean"},
    "side": {"type": "string", "enum": ["input", "output"]}, "slot": integer(0, 1023), "playerSlot": integer(0, 35),
    "clear": {"const": True}, "cursor": {"const": True}, "source": GHOST_SOURCE_SCHEMA, "factor": {"type": "integer", "enum": [-8, -2, 2, 8]}, "page": integer(0, 127),
    "move": {"type": "boolean"}, "wholeStack": {"type": "boolean"}, "serial": integer(-1, 2_147_483_647),
    "expectedCpuSerial": integer(0, 2_147_483_647), "paused": {"type": "boolean"},
}, {
    "priority_open": ([], [], {}),
    "priority_set": (["priority"], ["priority"], {}),
    "host_setting": (["setting", "value", "expectedValue"], ["setting", "value", "expectedValue"], {"properties": {
        "setting": {"enum": ["BLOCK", "STORAGE_FILTER", "ADVANCED_BLOCKING_MODE"]},
        "value": {"enum": ["YES", "NO", "NONE", "EXTRACTABLE_ONLY", "DEFAULT", "BLOCK_ON_ALL"]}}}),
    "terminal_search": (["text", "field"], ["text"], {"properties": {"action": {"const": "terminal_search"}, "field": {"type": "string", "enum": ["resources", "inputs", "outputs", "names"]}}}),
    "terminal_setting": (["setting", "value"], ["setting", "value"], {"properties": {"action": {"const": "terminal_setting"}, "setting": {"enum": ["SORT_BY", "SORT_DIRECTION", "VIEW_MODE"]}, "value": {"type": "string", "minLength": 1, "maxLength": 64}}}),
    "terminal_type_filter": (["type", "enabled"], ["type", "enabled"], {}),
    "resource_take": (["resourceId", "mode"], ["resourceId"], {"properties": {"action": {"const": "resource_take"}, "mode": {"type": "string", "enum": ["stack", "single", "half", "inventory"]}}}),
    "resource_deposit": (["mode"], [], {"properties": {"action": {"const": "resource_deposit"}, "mode": {"type": "string", "enum": ["all", "single"]}}}),
    "resource_container": (["resourceId", "mode"], ["resourceId", "mode"], {"properties": {"action": {"const": "resource_container"}, "mode": {"type": "string", "enum": ["fill_single", "fill_all", "drain_single", "drain_all"]}}}),
    "craft_open": (["resourceId"], ["resourceId"], {}),
    "craft_request": (["amount", "mode", "lite", "autoStart", "follow"], ["amount"], {}),
    "crafting_output": (["slot", "mode"], [], {"properties": {"action": {"const": "crafting_output"}, "mode": {"type": "string", "enum": ["single", "stack", "inventory"], "default": "single"}}}),
    "pattern_set": (["side", "slot", "amount", "resourceId", "playerSlot", "clear"], ["side", "slot"], {"oneOf": [
        {"required": ["resourceId", "amount"], "not": {"anyOf": [{"required": ["playerSlot"]}, {"required": ["clear"]}]}},
        {"required": ["playerSlot", "amount"], "not": {"anyOf": [{"required": ["resourceId"]}, {"required": ["clear"]}]}},
        {"required": ["clear"], "not": {"anyOf": [{"required": ["resourceId"]}, {"required": ["playerSlot"]}, {"required": ["amount"]}]}},
    ]}),
    "cell_config_set": (["slot", "resourceId", "playerSlot", "cursor", "source"], ["slot"], {"properties": {"action": {"const": "cell_config_set"}, "slot": integer(0, 62)}, "oneOf": [
        {"required": [key], "not": {"anyOf": [{"required": [other]} for other in ("resourceId", "playerSlot", "cursor", "source") if other != key]}}
        for key in ("resourceId", "playerSlot", "cursor", "source")
    ]}),
    "cell_config_clear": (["slot"], ["slot"], {"properties": {"action": {"const": "cell_config_clear"}, "slot": integer(0, 62)}}),
    "pattern_mode": (["field", "value"], ["field", "value"], {"properties": {"action": {"const": "pattern_mode"}, "field": {"type": "string", "enum": ["crafting", "substitute", "beSubstitute", "inverted"]}, "value": {"type": "boolean"}}}),
    "pattern_multiply": (["factor"], ["factor"], {}), "pattern_page": (["page"], ["page"], {}),
    "pattern_encode": (["move", "wholeStack"], [], {"if": {"required": ["wholeStack"], "properties": {"wholeStack": {"const": True}}}, "then": {"required": ["move"], "properties": {"move": {"const": True}}}}),
    "pattern_clear": ([], [], {}), "preview_start": (["follow"], [], {}), "cpu_select": (["serial"], ["serial"], {}),
    "job_cancel": (["expectedCpuSerial"], ["expectedCpuSerial"], {}), "job_pause": (["expectedCpuSerial", "paused"], ["expectedCpuSerial", "paused"], {}),
})


T_MCB_AE = ToolSpec("mcb_ae", "ae.inspect", "Read current AE terminal/pattern/cell_config/preview/CPU/interface/requests synchronized client data by named scope (auto default), with exact screenId. cell_config reports the actual CellWorkbench phantom configuration and current inserted-cell acceptedType; its slot indices are independent of ordinary container slots. Paginated entries preserve canonical screen-bound resourceId/type/NBT; large amounts are decimal strings. The resource resolver retains actual AEStack identity even if identityNbt is truncated; never reconstruct a target from an icon or short NBT. Read coverage/truncation/nextOffset/hasMore/budget, including pending sync. A newly opened preview can return requestedPending=true, requested=null and startReady=false; wait for startReady without resending craft_request. Configuration is not stored contents or proof of server acceptance. No whole-network/unloaded-resource guarantee, automatic pagination or screenshots.", obj({"screenId": SCREEN_ID, "scope": {"type": "string", "enum": ["auto", "terminal", "pattern", "cell_config", "preview", "cpu", "interfaces", "requests", "configuration", "owner"], "default": "auto"}, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 128), "default": 64}}, ["screenId"]), True)


T_MCB_AE_MATERIAL_SOURCE = ToolSpec("mcb_ae_material_source", "ae.material_source", "Read one bound public ICellContainer anchor through a guarded local developer AFTER context. Requires exact session/world/dimension, x/y/z, side and selector=tile|part plus existing background protection; it preserves user choices. Returns real public gridId, node/actionable activity, network power, extract permission, nativeEnergyItemAllowance (integer0..energyProbeItems) and sourceMode finite|creative_infinite|mixed|unknown. supported materialSource binds all eight keys for mcb_survival_build; never guess expectedGridId. This is readiness/source evidence, not extraction, stored contents or formation proof. Unknown modes/API/permissions remain explicit; no network enumeration or mutation.", obj({
        "expectedSessionId": LIVE_ID, "expectedWorld": LIVE_ID, "dimension": DIMENSION, **POSITION,
        "side": integer(0, 5), "selector": {"type": "string", "enum": ["tile", "part"]},
        "energyProbeItems": {**integer(1, 65_536), "default": 65_536}},
        ["expectedSessionId", "expectedWorld", "dimension", "x", "y", "z", "side", "selector"]), True)


T_MCB_AE_ACTION = ToolSpec("mcb_ae_action", "ae.action", "Apply an exact named AE operation: terminal search/settings/type filters, resource take/deposit/container fill or drain, craft open/request, crafting_output, pattern edits/mode/multiply/page/encode/clear, CellWorkbench single-slot cell_config_set/clear, preview start, CPU selection or guarded job pause/cancel. crafting_output uses the current real SlotCraftingTerm and one native crafting packet: single crafts one recipe to the cursor, stack crafts up to one output stack to the cursor, inventory crafts up to one output stack into player inventory. slot is optional for a unique output; mode defaults to single. No exact amount/batches; maxRecipeExecutions/maxOutputCount are upper bounds, serverConfirmed=false. cell_config_set requires slot0..62 and exactly one current resourceId/playerSlot/cursor=true/source. source is a nonconsuming ghost object: observed neiItemId preserves full NEI NBT; registryName/metadata creates an untagged template, fluidName/amount creates the native display template, playerSlot copies an owned filled cell or item. Ghost marking works in survival without give or acquisition. Successful source marking auto-releases NEI fake cursors; inspect ghostRelease for cleanup, never replay a submitted config edit to release it. This uses the native AE synchronized config delta, not an icon drag or MUI callback. Bind a uniquely matched NEI panel ghostSource as source in one mcb_sequence when useful. No top-level amount is accepted: a resourceId is copied as a unit configuration identity; real ItemStacks use native count-one phantom conversion or accepted container content. Config edits require a recognized inserted cell, its actual accepted type and a visible native phantom slot; clear affects only that slot. Use resourceIds and advertised typeFilters from current mcb_ae. Quantities for other actions are positive decimal strings through Long.MAX_VALUE. Native GUI/container packets and CellWorkbench synchronized inventory deltas request the normal native flush before returning and preserve server validation; submitted does not prove completion. Reinspect after ticks; no hover dependency, OS input, screenshots or silent retries.", AE_ACTION_SCHEMA)

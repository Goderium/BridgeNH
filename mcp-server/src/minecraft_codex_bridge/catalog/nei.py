"""Nei tool schemas and native operation declarations."""

from ..recipe_tasks import (
    BACKEND_INPUTS_SCHEMA,
)
from .common import (
    DIMENSION,
    LIVE_ID,
    POSITION,
    REGISTRY_NAME,
    SCREEN_ID,
    TEXT_INPUT,
    ToolSpec,
    integer,
    named_actions,
    obj,
)


RESEARCH_READ_SCHEMA = {
    **obj({"researchKey": {"type": "string", "minLength": 1, "maxLength": 256},
        "title": {"type": "string", "minLength": 1, "maxLength": 256},
        "category": {"type": "string", "minLength": 1, "maxLength": 128},
        "section": {"type": "string", "enum": ["summary", "pages", "page_text", "dependencies", "page_links"], "default": "summary"},
        "pageIndex": integer(0, 65535), "offset": {**integer(0, 1048576), "default": 0},
        "limit": integer(1, 8192)}),
    "oneOf": [{"required": ["researchKey"], "not": {"required": ["title"]}},
              {"required": ["title"], "not": {"required": ["researchKey"]}}],
    "allOf": [
        {"if": {"required": ["section"], "properties": {"section": {"enum": ["page_text", "page_links"]}}},
         "then": {"required": ["pageIndex"]}, "else": {"not": {"required": ["pageIndex"]}}},
        {"if": {"not": {"required": ["section"]}},
         "then": {"properties": {"offset": {"const": 0}, "limit": {"const": 1}}}},
        {"if": {"required": ["section"], "properties": {"section": {"const": "summary"}}},
         "then": {"properties": {"offset": {"const": 0}, "limit": {"const": 1}}}},
        {"if": {"required": ["section"], "properties": {"section": {"enum": ["pages", "dependencies", "page_links"]}}},
         "then": {"properties": {"limit": integer(1, 32)}}},
    ],
}


RECIPE_ITEM_SOURCE = {
    **obj({"source": {"type": "string", "enum": ["registry", "inventory"]},
           "registryName": REGISTRY_NAME, "metadata": integer(0, 32_767), "slot": integer(0, 255)}, ["source"]),
    "oneOf": [
        {"properties": {"source": {"const": "registry"}}, "required": ["registryName"],
         "propertyNames": {"enum": ["source", "registryName", "metadata"]}},
        {"properties": {"source": {"const": "inventory"}}, "required": ["slot"],
         "propertyNames": {"enum": ["source", "slot"]}},
    ],
    "examples": [{"source": "registry", "registryName": "minecraft:chest", "metadata": 0},
                 {"source": "inventory", "slot": 0}],
    "description": "Typed selector; source is required even for registry templates. Inventory is client-side for a public map catalog; authoritative for a permitted machine/backend query.",
}


RECIPE_CATALOG_QUERY_SCHEMA = {
    **obj({"map": REGISTRY_NAME, **POSITION, "dimension": DIMENSION,
           "input": RECIPE_ITEM_SOURCE, "output": RECIPE_ITEM_SOURCE,
           "cursor": {"type": "string", "minLength": 1, "maxLength": 64},
           "limit": {**integer(1, 16), "default": 4}, "maxPages": {**integer(1, 16), "default": 1}}),
    "oneOf": [
        {"required": ["cursor"], "propertyNames": {"enum": ["cursor", "dimension", "limit", "maxPages"]}},
        {"required": ["map"], "propertyNames": {"enum": ["map", "dimension", "input", "output", "limit", "maxPages"]}},
        {"required": ["x", "y", "z"], "not": {"anyOf": [{"required": ["map"]}, {"required": ["cursor"]}]}},
    ],
}


RECIPE_BACKEND_QUERY_SCHEMA = {
    **obj({"mode": {"const": "backend_match"}, **POSITION, "dimension": DIMENSION,
           "inputs": {**BACKEND_INPUTS_SCHEMA, "minItems": 0}, "output": RECIPE_ITEM_SOURCE,
           "fluidInputs": {"type": "array", "maxItems": 8,
               "items": obj({"fluid": {"type": "string", "minLength": 1, "maxLength": 128},
                             "amount": integer(1, 2_147_483_647)}, ["fluid", "amount"])},
           "fluids": {"type": "array", "maxItems": 0}, "specialSlot": {"type": "null"}},
          ["mode", "x", "y", "z"]),
    "anyOf": [
        {"required": ["inputs"], "properties": {"inputs": {"minItems": 1}}},
        {"required": ["fluidInputs"], "properties": {"fluidInputs": {"minItems": 1}}},
    ],
}


RECIPE_QUERY_SCHEMA = {
    **obj({**RECIPE_CATALOG_QUERY_SCHEMA["properties"], **RECIPE_BACKEND_QUERY_SCHEMA["properties"]}),
    "oneOf": [RECIPE_CATALOG_QUERY_SCHEMA, RECIPE_BACKEND_QUERY_SCHEMA],
}


NEI_OPEN_SCHEMA = {
    **obj({"screenId": {"type": "string", "maxLength": 128}, "mode": {"type": "string", "enum": ["crafting", "usage"]},
           "source": {"type": "string", "enum": ["registry", "inventory", "container", "recipe"]},
           "registryName": REGISTRY_NAME, "metadata": {**integer(0, 32_767), "default": 0}, "slot": integer(0, 2_147_483_647),
           "recipeIndex": integer(0, 100_000), "group": {"type": "string", "enum": ["inputs", "outputs", "other"], "default": "inputs"},
           "position": {**integer(0, 63), "default": 0}, "alternative": {**integer(0, 31), "default": 0}}, ["screenId", "mode", "source"]),
    "oneOf": [
        {"properties": {"source": {"const": "registry"}}, "required": ["registryName"], "propertyNames": {"enum": ["screenId", "mode", "source", "registryName", "metadata"]}},
        {"properties": {"source": {"const": "inventory"}, "slot": integer(0, 255)}, "required": ["slot"], "propertyNames": {"enum": ["screenId", "mode", "source", "slot"]}},
        {"properties": {"source": {"const": "container"}}, "required": ["slot"], "propertyNames": {"enum": ["screenId", "mode", "source", "slot"]}},
        {"properties": {"source": {"const": "recipe"}}, "required": ["recipeIndex"], "propertyNames": {"enum": ["screenId", "mode", "source", "recipeIndex", "group", "position", "alternative"]}},
    ],
}


NEI_NAVIGATE_SCHEMA = named_actions({"index": integer(0, 100_000), "shift": {"type": "boolean", "default": False},
    "ttl_ms": {**integer(100, 60_000), "default": 15000}, "saveCount": {"type": "boolean", "default": True},
    "taskId": {"type": "string", "minLength": 1, "maxLength": 128},
    "retention": {"type": "string", "enum": ["temporary", "permanent", "future"]}}, {
    "handler": (["index"], ["index"], {}), "page": (["index"], ["index"], {}), "back": ([], [], {}),
    "overlay": (["index", "shift"], ["index"], {}),
    "pause": (["index", "ttl_ms"], ["index"], {}), "resume": ([], [], {}),
    "bookmark": (["index", "saveCount", "taskId", "retention"], ["index"], {"dependentRequired": {"retention": ["taskId"]}}),
})

NEI_BOOKMARK_READ_SCHEMA = obj({"screenId": SCREEN_ID, "contextToken": LIVE_ID,
    "offset": integer(0, 4096), "limit": integer(1, 128)}, ["screenId"])

NEI_BOOKMARK_ACTION_SCHEMA = named_actions({
    "contextToken": LIVE_ID, "entryId": LIVE_ID, "targetEntryId": LIVE_ID, "namespaceId": LIVE_ID, "sourceNamespaceId": LIVE_ID,
    "entryIds": {"type": "array", "items": LIVE_ID, "minItems": 1, "maxItems": 512, "uniqueItems": True},
    "neiItemId": LIVE_ID, "count": integer(0, 2_147_483_647), "groupId": integer(0, 2_147_483_647),
    "taskId": {"type": "string", "minLength": 1, "maxLength": 128},
    "retention": {"type": "string", "enum": ["temporary", "permanent", "future"]},
    "releaseRetained": {"type": "boolean", "default": False},
    "startRow": integer(0, 127), "endRow": integer(0, 127), "targetRow": integer(0, 127),
    "viewMode": {"type": "string", "enum": ["DEFAULT", "TODO_LIST"]},
    "collapsed": {"type": "boolean"}, "crafting": {"type": "boolean"}, "after": {"type": "boolean"},
    "scope": {"type": "string", "enum": ["item", "recipe", "group"]}, "delta": integer(-1_000_000, 1_000_000),
}, {
    "add_item": (["contextToken", "neiItemId", "count", "groupId", "taskId", "retention"], ["contextToken", "neiItemId", "taskId"], {}),
    "retain": (["contextToken", "entryIds", "taskId", "retention"], ["contextToken", "entryIds", "taskId", "retention"], {}),
    "cleanup": (["contextToken", "taskId"], ["contextToken", "taskId"], {}),
    "remove": (["contextToken", "entryIds", "releaseRetained"], ["contextToken", "entryIds"], {}),
    "remove_recipe": (["contextToken", "entryIds", "releaseRetained"], ["contextToken", "entryIds"], {}),
    "remove_group": (["contextToken", "groupId", "releaseRetained"], ["contextToken", "groupId"], {}),
    "namespace_select": (["contextToken", "namespaceId"], ["contextToken", "namespaceId"], {}),
    "namespace_new": (["contextToken"], ["contextToken"], {}),
    "group_rows": (["contextToken", "startRow", "endRow", "groupId"], ["contextToken", "startRow", "endRow"], {}),
    "group_mode": (["contextToken", "groupId", "viewMode", "collapsed", "crafting"], ["contextToken", "groupId"],
                   {"anyOf": [{"required": ["viewMode"]}, {"required": ["collapsed"]}, {"required": ["crafting"]}]}),
    "move_item": (["contextToken", "entryId", "targetEntryId", "targetRow", "groupId", "after"], ["contextToken", "entryId"],
                  {"oneOf": [{"required": ["targetEntryId"], "not": {"anyOf": [{"required": ["targetRow"]}, {"required": ["groupId"]}, {"required": ["after"]}]}},
                             {"required": ["targetRow", "groupId"], "not": {"required": ["targetEntryId"]}}]}),
    "move_group": (["contextToken", "groupId", "targetRow", "sourceNamespaceId"], ["contextToken", "groupId", "targetRow"], {}),
    "recipe_collapse": (["contextToken", "entryId", "collapsed"], ["contextToken", "entryId", "collapsed"], {}),
    "quantity": (["contextToken", "scope", "entryId", "groupId", "delta"], ["contextToken", "scope", "delta"],
                 {"if": {"properties": {"scope": {"const": "group"}}},
                  "then": {"required": ["groupId"], "not": {"required": ["entryId"]}},
                  "else": {"required": ["entryId"], "not": {"required": ["groupId"]}}}),
})

T_MCB_NEI_BOOKMARKS = ToolSpec("mcb_nei_bookmarks", "nei.bookmarks", "Read actual NEI bookmark namespaces, groups, exact entry IDs and session-local retention. Requires current screenId. Active-namespace entries are paged; reuse contextToken for later pages. All namespace metadata is bounded to 64 namespaces/4096 entries. Reads never adopt old bookmarks as temporary. Refresh after state/layout changes. Retention is conservative: after a client/world/native reload, unknown entries remain untouched.", NEI_BOOKMARK_READ_SCHEMA, True)
T_MCB_NEI_BOOKMARK_ACTION = ToolSpec("mcb_nei_bookmark_action", "nei.bookmark_action", "Native NEI bookmark operations with exact screenId/contextToken. add_item uses observed neiItemId (full NBT), count defaults zero; new entries get taskId and temporary/permanent/future retention. retain explicitly classifies current entryIds; reuse never adopts existing entries. cleanup removes only this task's temporary entries across namespaces in one bounded call (max512); linked recipes containing retained/untracked entries are preserved and reported. remove of a recipe bookmark deletes its complete same-group/same-namespace recipe, including inputs, outputs and associated items; copies elsewhere remain. remove_recipe explicitly selects that unit, remove_group selects a whole group. Permanent/future entries need releaseRetained=true after deciding they are no longer needed. group_rows uses current visible startRow/endRow: omit groupId to create, use0 to ungroup, or an existing group. group_mode sets viewMode/crafting/collapsed. move_item/move_group use native ordering and current rows; sourceNamespaceId optionally moves a group from another page into the active page. recipe_collapse sets collapsed for an actual crafting-group recipe. Native view/chain constraints still apply. quantity applies signed native multiplier delta to item/recipe/group. namespace_new selects the native trailing empty collection; namespace_select uses a returned ID, never an old page index. Changes call NEI's native save; memory verification does not prove disk durability. Inspect status/changed/deleted/skipped and returned view; partial/unknown results must not be replayed. This never touches real inventory, encoded patterns or OS input.", NEI_BOOKMARK_ACTION_SCHEMA)


NEI_VIEW_COMMON = ["screenId", "section", "contextToken", "offset", "limit"]


NEI_DETAIL_COMMON = [*NEI_VIEW_COMMON, "target", "includeTooltips", "advanced"]


NEI_DETAIL_RECIPE = ["handlerIndex", "recipeIndex"]


NEI_MODIFIERS_SCHEMA = {**obj({key: {"type": "boolean"} for key in ("shift", "control", "alt")}), "minProperties": 1, "maxProperties": 3}


NEI_PREVIEW_POSITION_SCHEMA = obj({"x": integer(-33_554_432, 33_554_431), "y": integer(-2048, 2047), "z": integer(-33_554_432, 33_554_431)}, ["x", "y", "z"])


NEI_PREVIEW_BLOCK_SELECTORS = ["previewBlockSource", "previewPosition"]


NEI_PREVIEW_BLOCK_SOURCE_CONDITION = {
    "if": {"required": ["previewBlockSource"], "properties": {"previewBlockSource": {"const": "position"}}},
    "then": {"required": ["previewPosition"]},
    "else": {"not": {"required": ["previewPosition"]}},
}


NEI_DETAIL_BRANCHES = [
    {"required": ["target"], "properties": {"target": {"const": "rendered_text"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target"]}},
    {"required": ["target", "itemIndex"], "properties": {"target": {"const": "rendered_text_value"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "itemIndex"]}},
    {"required": ["target"], "properties": {"target": {"enum": ["hover_tooltip_lines", "hover_hotkeys"]}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target"]}},
    {"required": ["target", "lineIndex"], "properties": {"target": {"const": "hover_tooltip_text"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "lineIndex"]}},
    {"required": ["target"], "properties": {"target": {"const": "preview_block"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", *NEI_PREVIEW_BLOCK_SELECTORS]}, **NEI_PREVIEW_BLOCK_SOURCE_CONDITION},
    {"required": ["target"], "properties": {"target": {"const": "preview_hint_text"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", *NEI_PREVIEW_BLOCK_SELECTORS]}, **NEI_PREVIEW_BLOCK_SOURCE_CONDITION},
    {"required": ["target"], "properties": {"target": {"const": "preview_textures"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", *NEI_PREVIEW_BLOCK_SELECTORS, "textureSide"]}, **NEI_PREVIEW_BLOCK_SOURCE_CONDITION},
    {"required": ["target"], "properties": {"target": {"enum": ["preview_native_model", "preview_ctm_context", "preview_render_state", "preview_render_geometry"]}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", *NEI_PREVIEW_BLOCK_SELECTORS]}, **NEI_PREVIEW_BLOCK_SOURCE_CONDITION},
    {"required": ["target"], "properties": {"target": {"const": "preview_candidate_groups"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target"]}},
    {"required": ["target", "groupIndex"], "properties": {"target": {"const": "preview_candidate_items"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "groupIndex"]}},
    {"required": ["target", "groupIndex", "alternativeIndex"], "properties": {"target": {"const": "preview_candidate_tooltip_lines"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "groupIndex", "alternativeIndex", "advanced", "modifiers"]}},
    {"required": ["target", "groupIndex", "alternativeIndex", "lineIndex"], "properties": {"target": {"const": "preview_candidate_tooltip_text"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "groupIndex", "alternativeIndex", "lineIndex", "advanced", "modifiers"]}},
    {"required": ["target", "groupIndex", "alternativeIndex"], "properties": {"target": {"const": "preview_candidate_nbt"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "groupIndex", "alternativeIndex"]}},
    {"required": ["target", "groupIndex", "alternativeIndex"], "properties": {"target": {"const": "preview_candidate_visuals"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "groupIndex", "alternativeIndex"]}},
    {"required": ["target", "recipeIndex"], "properties": {"target": {"const": "positions"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, *NEI_DETAIL_RECIPE, "positionKind"]}},
    {"required": ["target", "recipeIndex", "positionIndex"], "properties": {"target": {"enum": ["alternatives", "badges"]}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, *NEI_DETAIL_RECIPE, "positionKind", "positionIndex"]}},
    {"required": ["target", "recipeIndex"], "properties": {"target": {"const": "metadata"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, *NEI_DETAIL_RECIPE]}},
    {"required": ["target", "recipeIndex", "metadataIndex"], "properties": {"target": {"const": "metadata_value"}, "limit": {**integer(1, 8192), "default": 2048}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, *NEI_DETAIL_RECIPE, "metadataIndex", "valuePath"]}},
    {"required": ["target"], "properties": {"target": {"const": "widgets"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, "widgetSource"]}},
    {"required": ["target", "itemIndex"], "properties": {"target": {"const": "bookmark_permutations"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, "itemIndex"]}},
    {"required": ["target"], "properties": {"target": {"const": "settings"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, "settingSource", "configPath"]}},
    {"required": ["target", "subsetPath"], "properties": {"target": {"const": "subset_items"}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": [*NEI_DETAIL_COMMON, "subsetPath"]}},
    {"required": ["target"], "properties": {"target": {"enum": ["catalysts", "subsets", "tree_items", "tree_roots", "tree_edges", "tree_stats", "preview_blocks", "preview_controls"]}, "limit": {**integer(1, 64), "default": 16}},
     "propertyNames": {"enum": NEI_DETAIL_COMMON}},
]


for detail_target in ("tooltip_lines", "tooltip_text", "nbt"):
    for stack_source, selectors, required_selectors in (
        ("recipe", [*NEI_DETAIL_RECIPE, "positionKind", "positionIndex", "alternativeIndex"], ["recipeIndex"]),
        ("panel", ["panel", "itemIndex"], ["stackSource", "itemIndex"]),
        ("catalyst", ["itemIndex"], ["stackSource", "itemIndex"]),
        ("tree_item", ["itemIndex"], ["stackSource", "itemIndex"]),
        ("tree_stat", ["itemIndex"], ["stackSource", "itemIndex"]),
    ):
        text_page = detail_target in ("tooltip_text", "nbt")
        detail_properties = {"target": {"const": detail_target}, "stackSource": {"const": stack_source},
            "limit": {**integer(1, 8192 if text_page else 64), "default": 2048 if text_page else 16}}
        tooltip_selectors = [] if detail_target == "nbt" else ["tooltipSource", "modifiers"]
        if detail_target != "nbt" and stack_source == "recipe":
            tooltip_selectors.append("badgeIndex")
        if detail_target != "nbt" and stack_source != "recipe":
            detail_properties["tooltipSource"] = {"const": "item"}
        line_selectors = ["lineIndex"] if detail_target == "tooltip_text" else []
        detail_branch = {"required": ["target", *required_selectors, *line_selectors],
            "properties": detail_properties,
            "propertyNames": {"enum": [*NEI_DETAIL_COMMON, "stackSource", *selectors, *tooltip_selectors, *line_selectors]}}
        if detail_target != "nbt" and stack_source == "recipe":
            detail_branch.update({"if": {"required": ["tooltipSource"], "properties": {"tooltipSource": {"const": "badge"}}},
                "then": {"required": ["badgeIndex"]}, "else": {"not": {"required": ["badgeIndex"]}}})
        NEI_DETAIL_BRANCHES.append(detail_branch)


for stack_source, selectors, required_selectors in (
    ("recipe", [*NEI_DETAIL_RECIPE, "positionKind", "positionIndex", "alternativeIndex"], ["recipeIndex"]),
    ("panel", ["panel", "itemIndex"], ["stackSource", "itemIndex"]),
    ("catalyst", ["itemIndex"], ["stackSource", "itemIndex"]),
    ("tree_item", ["itemIndex"], ["stackSource", "itemIndex"]),
    ("tree_stat", ["itemIndex"], ["stackSource", "itemIndex"]),
):
    NEI_DETAIL_BRANCHES.append({"required": ["target", *required_selectors],
        "properties": {"target": {"const": "item_visuals"}, "stackSource": {"const": stack_source}, "limit": {**integer(1, 64), "default": 16}},
        "propertyNames": {"enum": [*NEI_VIEW_COMMON, "target", "stackSource", *selectors]}})


NEI_DETAIL_SCHEMA = {"required": ["section", "target"], "properties": {"section": {"const": "detail"}}, "oneOf": NEI_DETAIL_BRANCHES}


NEI_VIEW_SCHEMA = {
    **obj({"screenId": SCREEN_ID,
        "section": {"type": "string", "enum": ["summary", "handlers", "recipes", "recipe", "panel", "settings", "tree", "preview", "rendered", "tooltip", "coverage", "detail"], "default": "summary"},
        "contextToken": LIVE_ID, "offset": {**integer(0, 2_147_483_647), "default": 0}, "limit": integer(1, 8192),
        "scope": {"type": "string", "enum": ["visible", "handler", "all"]},
        "handlerIndex": integer(0, 2_147_483_647), "recipeIndex": integer(0, 2_147_483_647),
        "panel": {"type": "string", "enum": ["item", "history", "craftables", "bookmarks"], "default": "item"},
        "groupId": integer(0, 2_147_483_647), "x": integer(0, 32_767), "y": integer(0, 32_767),
        "target": {"type": "string", "enum": ["positions", "alternatives", "badges", "tooltip_lines", "tooltip_text", "nbt", "metadata", "metadata_value", "catalysts", "widgets", "subsets", "tree_items", "tree_roots", "tree_edges", "tree_stats", "bookmark_permutations", "preview_blocks", "preview_controls", "settings", "subset_items", "rendered_text", "rendered_text_value", "preview_block", "preview_hint_text", "preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt", "preview_candidate_visuals", "item_visuals", "preview_textures", "preview_native_model", "preview_ctm_context", "preview_render_state", "preview_render_geometry", "hover_tooltip_lines", "hover_tooltip_text", "hover_hotkeys"]},
        "positionKind": {"type": "string", "enum": ["inputs", "outputs", "other"], "default": "inputs"},
        "positionIndex": integer(0, 2_147_483_647), "alternativeIndex": integer(0, 2_147_483_647),
        "stackSource": {"type": "string", "enum": ["recipe", "panel", "catalyst", "tree_item", "tree_stat"], "default": "recipe"},
        "itemIndex": integer(0, 2_147_483_647), "lineIndex": integer(0, 2_147_483_647), "metadataIndex": integer(0, 2_147_483_647),
        "tooltipSource": {"type": "string", "enum": ["item", "position", "handler", "badge"], "default": "item"},
        "badgeIndex": integer(0, 2_147_483_647),
        "groupIndex": integer(0, 2_147_483_647), "previewBlockSource": {"type": "string", "enum": ["selected", "hover", "position"], "default": "selected"},
        "previewPosition": NEI_PREVIEW_POSITION_SCHEMA, "textureSide": integer(0, 5), "modifiers": NEI_MODIFIERS_SCHEMA,
        "valuePath": {"type": "array", "maxItems": 8, "items": integer(0, 2_147_483_647)},
        "widgetSource": {"type": "string", "enum": ["layout", "recipe", "tree", "tree_stats", "options"], "default": "layout"},
        "settingSource": {"type": "string", "enum": ["global", "world"], "default": "global"},
        "configPath": {"type": "string", "maxLength": 512, "default": ""},
        "subsetPath": {"type": "string", "minLength": 1, "maxLength": 512},
        "includeTooltips": {"type": "boolean", "default": False}, "advanced": {"type": "boolean", "default": False}}, ["screenId"]),
    "oneOf": [
        {"properties": {"section": {"const": "summary"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        {"required": ["section"], "properties": {"section": {"const": "handlers"}, "limit": {**integer(1, 64), "default": 16}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        {"required": ["section"], "properties": {"section": {"const": "recipes"}, "limit": {**integer(1, 16), "default": 16},
            "scope": {"type": "string", "enum": ["visible", "handler"], "default": "visible"}},
         "propertyNames": {"enum": [*NEI_VIEW_COMMON, "scope", "handlerIndex", "includeTooltips", "advanced"]}},
        {"required": ["section", "recipeIndex"], "properties": {"section": {"const": "recipe"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": [*NEI_VIEW_COMMON, "recipeIndex", "handlerIndex", "includeTooltips", "advanced"]}},
        {"required": ["section"], "properties": {"section": {"const": "panel"}, "limit": {**integer(1, 64), "default": 16},
            "scope": {"type": "string", "enum": ["visible", "all"], "default": "visible"}},
         "propertyNames": {"enum": [*NEI_VIEW_COMMON, "panel", "scope", "includeTooltips", "advanced"]}},
        {"required": ["section"], "properties": {"section": {"const": "settings"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        {"required": ["section"], "properties": {"section": {"const": "tree"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": [*NEI_VIEW_COMMON, "groupId"]}},
        {"required": ["section"], "properties": {"section": {"const": "preview"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        {"required": ["section"], "properties": {"section": {"const": "rendered"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        {"required": ["section", "x", "y"], "properties": {"section": {"const": "tooltip"}, "offset": {"const": 0}, "limit": {**integer(1, 1), "default": 1}},
         "propertyNames": {"enum": [*NEI_VIEW_COMMON, "x", "y", "includeTooltips", "advanced"]}},
        {"required": ["section"], "properties": {"section": {"const": "coverage"}, "limit": {**integer(1, 32), "default": 16}},
         "propertyNames": {"enum": NEI_VIEW_COMMON}},
        NEI_DETAIL_SCHEMA,
    ],
    "if": {"required": ["offset"], "properties": {"offset": {"minimum": 1}}},
    "then": {"required": ["contextToken"]},
}


NEI_PREVIEW_CONTROL_SCHEMA = {
    **obj({"screenId": SCREEN_ID, "contextToken": LIVE_ID,
        "action": {"type": "string", "enum": ["set_channel", "set_tier", "set_layer", "press_control", "set_camera", "select_block", "clear_selection", "preview_render_capture"]},
        "previewBlockSource": {"type": "string", "enum": ["selected", "hover", "position"], "default": "selected"},
        "previewPosition": NEI_PREVIEW_POSITION_SCHEMA,
        "channel": {"type": "string", "minLength": 1, "maxLength": 128},
        "value": integer(-1, 2_147_483_647), "controlIndex": integer(0, 63),
        "rotationYaw": {"type": "number", "minimum": -89.9, "maximum": 89.9},
        "rotationPitch": {"type": "number", "minimum": 0, "maximum": 360},
        "zoom": {"type": "number", "minimum": 3, "maximum": 999},
        "center": obj({axis: {"type": "number", "minimum": -33_554_432, "maximum": 33_554_431} for axis in ("x", "y", "z")}, ["x", "y", "z"]),
        "position": obj({"x": integer(-33_554_432, 33_554_431), "y": integer(-2048, 2047), "z": integer(-33_554_432, 33_554_431)}, ["x", "y", "z"])}, ["screenId", "contextToken", "action"]),
    "oneOf": [
        {"properties": {"action": {"const": "set_channel"}, "value": integer(0, 2_147_483_647)},
         "required": ["channel", "value"], "propertyNames": {"enum": ["screenId", "contextToken", "action", "channel", "value"]}},
        {"properties": {"action": {"const": "set_tier"}, "value": integer(1, 2_147_483_647)},
         "required": ["value"], "propertyNames": {"enum": ["screenId", "contextToken", "action", "value"]}},
        {"properties": {"action": {"const": "set_layer"}},
         "required": ["value"], "propertyNames": {"enum": ["screenId", "contextToken", "action", "value"]}},
        {"properties": {"action": {"const": "press_control"}},
         "required": ["controlIndex"], "propertyNames": {"enum": ["screenId", "contextToken", "action", "controlIndex"]}},
        {"properties": {"action": {"const": "set_camera"}},
         "required": ["rotationYaw", "rotationPitch", "zoom"],
         "propertyNames": {"enum": ["screenId", "contextToken", "action", "rotationYaw", "rotationPitch", "zoom", "center"]}},
        {"properties": {"action": {"const": "select_block"}},
         "required": ["position"], "propertyNames": {"enum": ["screenId", "contextToken", "action", "position"]}},
        {"properties": {"action": {"const": "clear_selection"}},
         "propertyNames": {"enum": ["screenId", "contextToken", "action"]}},
        {"properties": {"action": {"const": "preview_render_capture"}},
         "propertyNames": {"enum": ["screenId", "contextToken", "action", *NEI_PREVIEW_BLOCK_SELECTORS]}, **NEI_PREVIEW_BLOCK_SOURCE_CONDITION},
    ],
}


GHOST_SOURCE_SCHEMA = {"oneOf": [
    obj({"registryName": REGISTRY_NAME, "metadata": integer(0, 32766), "count": integer(1, 2_147_483_647)}, ["registryName"]),
    obj({"playerSlot": integer(0, 35), "count": integer(1, 2_147_483_647)}, ["playerSlot"]),
    obj({"fluidName": {"type": "string", "minLength": 1, "maxLength": 128}, "amount": integer(1, 2_147_483_647)}, ["fluidName"]),
    obj({"neiItemId": LIVE_ID, "count": integer(1, 2_147_483_647)}, ["neiItemId"]),
]}


NEI_ITEM_ACTION_SCHEMA = named_actions({"text": TEXT_INPUT, "neiItemId": LIVE_ID, "count": integer(1, 64)}, {
    "search": (["text"], ["text"], {}),
    "take": (["neiItemId", "count"], ["neiItemId"], {}),
    "ghost_pick": (["neiItemId"], ["neiItemId"], {}),
    "release": ([], [], {}),
})


T_MCB_RESEARCH = ToolSpec("mcb_research", "research.read", "Read an exact NEI-linked Thaumonomicon research key or an unambiguous visible localized title through the current public research registry. Returns bounded player-visible summary, localized pages/text, dependencies or page item/recipe links; title indexing and missing/ambiguous/locked/unsupported data stay explicit. Prefer the researchLinks already returned with a current NEI recipe or summary to a broad search. Read current player knowledge before claiming page access; a research title is navigation evidence, not proof all machine prerequisites are satisfied. No research granting, book automation, Java execution, screenshots or world edits.", RESEARCH_READ_SCHEMA, True)


T_MCB_NEI = ToolSpec("mcb_nei", "nei.inspect", "Read bounded current NEI GuiRecipe data using exact screenId: original visible recipe references, actual PositionedStack items/candidates and supported GT raw item/fluid/EUt/duration/chance/metadata plus displayOverclock. Display overclock is the NEI calculation, not a real machine result. Inspect coverage/positionsTruncated/softBudgetPartial/budget: incomplete empty outputs/other may be unread. chance uses raw 0.01% units. includeTooltips defaults false and reads actual ItemStack.getTooltip when requested. One visible-reference offset/limit page (at most 16); no automatic full enumeration or screenshots, and no global recipe graph. Prefer this live data before local source.", obj({"screenId": SCREEN_ID, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 16), "default": 4}, "includeTooltips": {"type": "boolean", "default": False}, "advanced": {"type": "boolean", "default": False}}, ["screenId"]), True)


T_MCB_NEI_VIEW = ToolSpec("mcb_nei_view", "nei.view", "Read structured current NEI UI data with exact screenId. Default summary returns compact surface counts/coverage and a session/screen/state-bound contextToken; reuse it across sections and require it for offset>0. Sections: handlers, recipes (scope=visible|handler, optional handlerIndex), one recipe by original recipeIndex, panel (item|history|craftables|bookmarks, scope=visible|all), settings, tree, preview, rendered, tooltip, coverage and detail. all covers only the currently loaded filtered panel/namespace list. Panel entries expose bounded screen/view-bound neiItemId and ghostSource={neiItemId} for ghost marking and explicit creative NEI acquisition; bind a uniquely matched entry ghostSource to source in mcb_sequence; keep the ID rather than rebuilding identity from truncated NBT. section=detail pages positions, alternatives, badges, tooltip_lines/tooltip_text, nbt, metadata/metadata_value, catalysts, widgets, subsets, tree_items/tree_roots/tree_edges/tree_stats, bookmark_permutations, preview_blocks/preview_controls, settings, subset_items, rendered_text/rendered_text_value, hover_tooltip_lines/hover_tooltip_text/hover_hotkeys, preview_block/preview_hint_text/preview_textures and preview_candidate_groups/preview_candidate_items/preview_candidate_tooltip_lines/preview_candidate_tooltip_text/preview_candidate_nbt/preview_candidate_visuals and item_visuals. Detail settings pages existing global/world ConfigTag children by configPath; subset_items requires an existing subsetPath. Recipe stack targets use original recipeIndex/positionKind/positionIndex/alternativeIndex; panel/catalyst/tree sources use original itemIndex. metadata_value selects metadataIndex and optional array-index valuePath. tooltipSource=item|position|handler|badge selects public recipe text; badge requires badgeIndex. tooltip_text requires lineIndex. Text targets nbt/tooltip_text/metadata_value/rendered_text_value/hover_tooltip_text/preview_hint_text/preview_candidate_tooltip_text/preview_candidate_nbt accept limit1..8192, default2048 Unicode codepoints; metadata arrays clamp effectiveLimit64 and report data.unit=entries. Other detail lists max64/default16. Inspect status/complete/hasMore/nextOffset/coverage/byteLimited/softBudgetPartial and data.unit before absence or completeness claims; preserve exact NBT fragments, long quantities and data.offsetUnit=unicode_codepoints for text continuation. stale_context refuses drift without fallback. Other page limits: recipes16, handlers/panel64, coverage32, default16; nonpaged summary/recipe/settings/tree/preview/rendered/tooltip and detail preview_block require offset0/limit1. includeTooltips/advanced default false; explicit tooltip targets read actual text. tooltip requires scaled x/y and retains actual ItemStack tooltip data where available. preview reads the current 3D renderer model and advertised channels/tiers/controls, without rebuilding a structure. data.channels.entries contains exact channel/controlIndex/minimum/maximum/value/explicitTier/effectiveTriggerTier; channels.masterTier is the default when explicitTier=0. preview_controls entries expose controlIndex/nativeAction and channel where applicable. preview_blocks preserves model coordinates, controller-relative coordinates and coordinateSpace; these are a preview scene, never actual world positions or guaranteed visible pixels. For one block use detail preview_block (compact current block), preview_hint_text (full hint text) or preview_textures (actual native face/layer/resource/UV/color descriptors). previewBlockSource=selected (default), hover, or position; only position requires complete previewPosition={x,y,z} in preview scene coordinates. textureSide=0..5 selects down/up/north/south/west/east; omit it for all six faces. Preview candidates use the current selected position cache, never an implicit model scan. preview_candidate_groups pages native groups; preview_candidate_items requires original groupIndex; tooltip_lines/text and nbt candidates also require original alternativeIndex, and tooltip_text requires lineIndex. If that cache is unavailable, observe its specific reason or select a block using the existing native preview action, then read again. Candidate item reads do not implicitly expand tooltips. preview_candidate_visuals requires original groupIndex/alternativeIndex and reads that actual selected-cache ItemStack. item_visuals uses the existing stackSource=recipe (default, requires recipeIndex and optional position/alternative selectors), panel (requires itemIndex, optional panel), catalyst, tree_item or tree_stat (each requires itemIndex) to read a real observed ItemStack. Both visuals targets page entries at most64/default16 across all native inventory faces/icon passes, reject textureSide/advanced/modifiers/includeTooltips, and expose exact native icons/UVs/colors and available GT inventory texture layers. Retain visualSource/itemRendererClass/itemRendererStatus/itemVisualKind/inventoryTextureSource/inventoryRenderContext/inventoryModelStatus/visualCoverage and model/resource limits. A Forge custom IItemRenderer class does not expose arbitrary geometry/shaders through its public interface; no renderItem invocation or pixel inference is made, and that specific gap stays explicit. Tooltip getter targets tooltip_lines/tooltip_text and preview_candidate_tooltip_lines/preview_candidate_tooltip_text optionally accept modifiers={shift,control,alt}: strict booleans including false; omitted keys retain physical state. These are scoped queries, not held keys. advanced is independent. Require actual hook/support and modifier diagnostics before claiming parity; unsupported hooks must retain a specific unavailable reason, never silently infer expanded text. Completed-frame rendered and hover targets retain real physical modifiers and refuse virtual redraw requests. rendered reads a bounded completed-frame text capture; detail rendered_text pages actual FontRenderer draw entries and rendered_text_value reads itemIndex text by Unicode codepoints. hover_tooltip_lines/hover_tooltip_text read the complete native tooltip list observed at the actual hover point, before display truncation; hover_hotkeys pages native hotkeys from that completed frame. They need no x/y, never move hover, and reject advanced/modifiers; hover_tooltip_text requires lineIndex. No completed tooltip frame is explicitly unavailable, rather than an empty-content proof. Frame continuation pins that captured frame; a new offset0 read without contextToken refreshes the frame. Read frameId/frameAgeMillis/observerActive/droppedTextDraws/boundedFramePartial before claiming coverage. Captured x/y are FontRenderer draw arguments in coordinateSpace=font_draw_arguments; screenPositionStatus=unmapped and visibilityStatus retain that draw commands are not guaranteed visible pixels. No screen-coordinate or clipping reconstruction is inferred. Read getter/provider-backed state, supported public handler text and captured normal FontRenderer draws; custom fonts, texture text, unseen frames and unavailable internals remain explicit gaps naming the visible content and actual missing API/cache/renderer cause. Texture descriptors do not prove arbitrary TESR geometry or final pixels. GT display calculations are not actual machine processing. Four detail targets preview_native_model/preview_ctm_context/preview_render_state/preview_render_geometry page the latest explicitly captured native model, CTM context, GL render state or bounded tessellator geometry for the same selected/hover/position block selectors; limit1..64/default16. They accept no textureSide/advanced/modifiers/includeTooltips/captureId and never arm a capture. First use mcb_nei_preview_control(action=preview_render_capture) with the exact current screenId/contextToken and same selectors, then allow the existing natural GUI frame to complete. Read captureId/captureStatus/naturalFramesObserved/ownerPosition/observerNanos and bounds maximumObserverNanos/maximumVertices/maximumEntries/captureLimited. Capture stores no pixel data, invokes no additional draw, and graphicsComplete remains false with explicit gaps; GL state and submitted vertices never establish visible pixels. No input, drawing, screenshots, automatic enumeration or takeover. Existing NEI read/open/navigation tools remain available.", NEI_VIEW_SCHEMA, True)


T_MCB_RECIPE_QUERY = ToolSpec("mcb_recipe_query", "recipes.query", "Read public runtime GT recipes without NEI GUI navigation. Map-only public catalogs and client: cursors use the current client public NEI registry in player/developer/LAN/remote modes; they are not authoritative server inventory observations. Machine coordinates/backend queries retain owned integrated developer or remote operator gates; inspect health.recipeQueryAccess before dispatch. Typed input/output selectors REQUIRE source, e.g. {source:registry,registryName:minecraft:chest,metadata:0}. Explicit mode=backend_match with loaded machine coordinates performs one public native backend find with actual voltage/map amperage and independent copies. inputs has 0..8 ordered positive-count untagged item registry templates; fluidInputs has 0..8 exact public Forge FluidRegistry names and amounts1..2147483647, without NBT. Either may be omitted/empty, but combined inputs must be nonempty. Fluid names are case-sensitive runtime names, not item/block registry IDs; unknown names are refused. It verifies the first native output rather than searching for a later desired output; lookupComplete/candidateValidated are required for that candidate and registered catalog completeness is never claimed. Backend mode forbids map/cursor/input/limit/maxPages and retains only empty legacy fluids/null specialSlot. fluidInputs is backend-only and is refused in catalog/cursor queries. Known finite inputs should use backend_match directly. Default catalog mode: choose loaded developer machine x/y/z or exact RecipeMap name, optionally input/output actual inventory stack or registry template. Each native page:512 candidates,16 results,2ms soft budget,128KiB. Explicit maxPages=2..16 advances only empty pages until a match, completion, page cap or10s deadline, reducing agent round trips; default1. Returns raw stacks, catalyst/chance/EU/t/duration/public conditions with coverage, not proof of executability; dynamic backend recipes may be unavailable. Continue using nextCursor plus original dimension/limit; cursor consumed once, session/world/map bound. Check complete/partial/filterIncomplete/hasMore; empty partial pages do not prove absence. No retries or screenshots; existing NEI methods remain available.", RECIPE_QUERY_SCHEMA, True, idempotent=False)


T_MCB_NEI_OPEN = ToolSpec("mcb_nei_open", "nei.open", "Open NEI crafting/usage by named action for an exact current source: registry template, actual inventory/container stack, or current handler original recipeIndex/group/position/alternative. Live stack sources preserve NBT; registryName/metadata is a template. screenId must match current screen, and an empty screenId means no GUI. Uses the normal NEI API without physical hover, R/U keys, OS input or screenshots. Read GUI/NEI after submission; no silent retry.", NEI_OPEN_SCHEMA)


T_MCB_NEI_NAVIGATE = ToolSpec("mcb_nei_navigate", "nei.navigate", "Native current NEI controls under exact screenId. handler/page use native index; overlay/bookmark/pause use the original visible recipeIndex as index, bound to the current handler and native widget. overlay invokes the actual custom handler's native button, normally returns from NEI's temporary EmptyContainer to its bound first GUI and verifies the destination before transfer; shift is only the declared transfer modifier. The button canFillCraftingGrid diagnostic includes missing physical materials, so false alone does not mean an AE ghost pattern cannot transfer; the actual handler and enabled native button decide. Submission is not acceptance: read synchronized AE pattern inputs/outputs. bookmark saves the whole native recipe, saveCount defaults true; returns actual saved items/long quantities/recipeId, preserving an existing bookmark rather than toggling it off. savedItemsComplete reports receipt bounds; panel=bookmarks reads full identities. pause holds logical Shift for normal NEI update/render reads, requires background takeover and observed keyboard hook; ttl_ms100..60000/default15000. resume releases only this hold. Screen/page/handler/world/takeover change or expiry also releases it; physical Shift is preserved. Holding is not proof the recipe paused: compare client recipe frames, resume then verify cycling. No OS input, TST-specific handler adaptation, screenshots or automatic replay.", NEI_NAVIGATE_SCHEMA)


T_MCB_NEI_ITEM_ACTION = ToolSpec("mcb_nei_item_action", "nei.item_action", "Native NEI search, phantom release or explicit creative acquisition under exact screenId. release() cancels both item/bookmark fake cursors and pending NEI drags; it preserves real held stacks, inventory, configured markers and bookmarks. No blank-position search, GUI clicks, drops or acquisition permission is needed. Idempotent local ghostRelease reports remaining state. Successful semantic ghost marking already auto-releases; use explicit release for leftovers from native/manual dragging. Cleanup failure never means replay the submitted marking. search(text) sets the actual NEI search field, including when covered by a small workbench; filtering is asynchronous, so read the resulting panel before selecting. mcb_nei_view(section=panel) entries expose screen/view-bound neiItemId preserving the actual full NBT. ghost_pick(neiItemId) uses the exact currently visible item-panel hit and native middle press/release, returning actual afterPress/afterRelease phantom states. It requires empty real/fake cursors and never generates inventory; prefer semantic ghostSource marking for routine configuration. A callback or false ghost state is not success. take(neiItemId,count=1) supports bounded expanded main inventories, counts actual offhand contents but checks ordinary slots 0..35 for capacity, and requires creative mode plus developer profile, a player-inventory GUI with its player-inventory tab selected in creative mode (mcb_open_inventory selects it on inventoryPlayerTabVersion>=1), empty cursor, enough player space and native NEI permission. Creative catalog tabs refuse before acquisition because they suppress ordinary backpack synchronization. It submits one finite normal NEI left-click acquisition, never claims pickup/server confirmation and never retries or falls back. Verify the exact both-side inventory increase against inventoryCountBefore/expectedInventoryDelta. Survival marking must use ghost sources via mcb_ae_action(cell_config_set,source) or supported MUI ghost_drag, without give/take or real inventory generation. AE virtual slots are not MUI widgets; verify actual synchronized configuration and unchanged inventory, never a drag icon. In mcb_sequence bind a unique panel entry neiItemId to take, or ghostSource to the configuration source. Remove temporary real marker items manually after use.", NEI_ITEM_ACTION_SCHEMA)


T_MCB_NEI_PREVIEW_CONTROL = ToolSpec("mcb_nei_preview_control", "nei.preview_control", "Operate the current supported NEI structure preview through native slider/button callbacks. Read mcb_nei_view(section=preview) and detail target=preview_controls first, then supply exact current screenId/contextToken. set_channel requires the advertised exact channel name and value (0 clears the explicit channel); set_tier changes the existing tier slider (value>=1); set_layer selects a preview layer (-1 shows all). Values must also satisfy the actual current advertised slider range; no silent clamping. press_control requires one freshly observed controlIndex0..63 and invokes that verified native button: project can place in-world structure hints, NEI can change the screen/overlay, copy can update the held projector's channels, highlight changes preview emphasis. Inspect the control's advertised action/side effects before pressing. Dynamic indices are not persistent IDs. set_camera requires rotationYaw (-89.9..89.9, plugin vertical angle), rotationPitch (0..360, horizontal angle with 360 normalized), zoom (3..999), and optional complete finite center={x,y,z} in preview scene coordinates. select_block requires an exact current preview scene position={x,y,z} belonging to the currently rendered/translucent set; it invokes the native candidate-selection path. clear_selection uses that same native path to clear it. These coordinates are never world coordinates. preview_render_capture explicitly arms one bounded capture of an already occurring natural preview GUI frame, using previewBlockSource=selected(default)|hover|position and complete previewPosition only for position. It requires exact screenId/contextToken, rejects channel/value/controlIndex/camera/selection selectors, returns pending captureId/captureStatus/ownerPosition, and accepts no requested renderer code or draw path. An arm receipt can provide a new contextToken with contextRefreshed=true/requiresViewRefresh=false; use that exact returned token instead of the stale prior token, without an extra context-only read. This remains a pending receipt. Read the matching preview_native_model/preview_ctm_context/preview_render_state/preview_render_geometry detail only after the natural frame completes; passive getters never arm or force drawing. Bounded metadata and vertex data retain pixelDataStored=false, graphicsComplete=false and explicit gaps. Returns only the native callback receipt with previousValue/value and compact previewAfter where available. requiresViewRefresh means reread a fresh view without the old token after the operation; a receipt or preview is never proof of world placement, structure formation or recipe execution. Stale/unsupported contexts or controls are refused; no alternate input path, physical mouse, screenshots, mutation retry or automatic pagination. Original NEI/GUI controls remain available.", NEI_PREVIEW_CONTROL_SCHEMA, idempotent=False)

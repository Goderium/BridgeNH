"""Client tool schemas and native operation declarations."""

from .common import (
    EXPECTED_SESSION,
    EXPECTED_WORLD,
    QUERY,
    REGISTRY_NAME,
    ToolSpec,
    integer,
    obj,
)


T_MCB_HEALTH = ToolSpec("mcb_health", "bridge.health", "Check the local Minecraft 1.7.10 bridge, current session/profile, versions, supported operations and bounded readiness. readiness observes this owning-thread state only; it does not prove future loading or machine formation. Follow its conditional nextTool without inferring authorization. A direct SDK-served health call additionally reports mcpAdapter process PID, catalog generation/fingerprint, bounded schema-only refresh support and current|restart_required source status. tools/list_changed delivery is not proof that this client refreshed: clientCatalogRefresh remains not_observable. Backend/helper/dispatch/permission changes require this server to restart. Workflow-projected bridge health does not add MCP process diagnostics. Versioned GTNH NEI/AE help is available for unfamiliar controls. Guides work without a running client.", obj(), True)


T_MCB_SNAPSHOT = ToolSpec("mcb_snapshot", "client.snapshot", "Read current player, world, screen, focus, pause and crosshair state. screenOpen is a boolean; screenKind is a stable built-in category (health.screenKinds). Raw screen and screenId remain available. Require positive current player health before inventory/mutation. Developer serverControl is a fresh real server tick receipt: after close/selection require sampleId newer than the before-action sample plus matching actual server container/hotbar and client synchronization; submission alone is insufficient. Player profile shows normal player-visible data.", obj(), True)


T_MCB_SCREENSHOT = ToolSpec("mcb_screenshot", "client.screenshot", "Capture the actual Minecraft framebuffer as temporary PNG image content only when visual rendering/layout evidence is necessary or the user explicitly permits it. Prefer live GUI, inventory, item and tooltip data; consult local source only when those data are insufficient. Do not persist screenshot data by default; retain the judgment as structured data or text when possible.", obj(), True)


T_MCB_POLICY = ToolSpec("mcb_policy", "bridge.policy", "Read or persist operation policy for this save/server; local /mcb policy uses the same endpoint. Selected intent is intersected with live authority. Modes: survival, survival_efficient, creative, full. Movement path classifies actual flight; tp requires normal command permission and supported backend, otherwise resolves path before action. QA auto|off|observe|test|manage only narrows the mode ceiling; creative defaults to reviewed test execution, full to management. Own-run status/normal stop remain available after downgrade. readOnly blocks new QA and fixture cleanup; terrainChanges is permission only, not a new digging planner. Config: config/codexbridge/policies.json. scope=defaults edits defaults; current scope remains isolated. reload validates external edits; no stale permissions are persisted.", obj({"action":{"enum":["status","set","reload"],"default":"status"},"scope":{"enum":["current","defaults"]},"mode":{"enum":["survival","survival_efficient","creative","full"]},"movement":{"enum":["path","tp"]},"readOnly":{"type":"boolean"},"terrainChanges":{"type":"boolean"},"qa":{"enum":["auto","off","observe","test","manage"]}}))


T_MCB_DIAGNOSTICS = ToolSpec("mcb_diagnostics", "client.diagnostics", "Read bounded client JVM/heap/FPS and integrated-server tick execution timings. Execution timing samples do not measure TPS.", obj(), True)


T_MCB_BINDINGS = ToolSpec("mcb_bindings", "input.bindings", "Read the actually registered runtime vanilla/Forge key bindings, descriptions, categories, key codes/names, held/physical state and current modifiers. Pages use query/offset/limit (at most 128); check coverage and budget. Does not consume isPressed, synthesize keys or capture screenshots. Use this live data before static guide defaults or options files; named GUI/NEI/AE actions are preferred for control.", obj({"query": QUERY, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 128), "default": 64}}), True)


T_MCB_MODS = ToolSpec("mcb_mods", "client.mods", "Search the FML active mod list with actual loadedSource. includeEvidence=true adds runtime main-class origin and observation-time disk hashes (not proof of already-loaded bytes). MCB executingBridge includes the executing compiled source fingerprint captured with preInit origin. includeCandidates=true requires a query matching exactly one loaded mod; scans sibling jars by mcmod.info modid/main-class entry, reports bounded duplicate evidence, never deletes files or guesses loader rejection reasons.", obj({"query": QUERY, "offset": integer(0, 10_000), "limit": integer(1, 128), "includeEvidence": {"type": "boolean", "default": False}, "includeCandidates": {"type": "boolean", "default": False}}), True)


T_MCB_MESSAGES = ToolSpec("mcb_messages", "client.messages", "Read bounded recent in-game chat/system messages after a sequence number. Does not read arbitrary files or send messages.", obj({"since": {"type": "integer", "minimum": 0}, "query": QUERY, "limit": integer(1, 128)}), True)


T_MCB_RECORDING = ToolSpec("mcb_recording", None, "Read passive player recordings without taking control. Prefer view=summary for one-call history review: validates the fixed JSONL file, summarizes commands, GUI input/transitions, movement, slot baselines/deltas and grouped server block/AE-part changes. No custom readback script needed. view=list pages existing recordings by offset; view=events returns bounded raw events with nextSince, optional kinds filter. Disk views work offline and after client/MCP restart, use only this selected instance's mcb-recordings directory, and default to its most recently modified recording. Bind returned recordingId for further calls. Inspect integrity gaps/payloadOmissions/incompleteTailBytes and summary truncation. Disk reads do not observe enabled/queue/flushed counters; absent stop marker means unknown. Default view=live preserves the native latest-memory reader and its enabled/stopReason/rejectedEvents/flushedSeq/historyGap fields. Enable with /mcb record on, stop with /mcb record off, inspect with /mcb record status. Repeated on preserves the active recording. Intent is not success, later state is not causal proof, grouped transitions are not an action count. No replay, screenshots, OS input, arbitrary paths or cross-instance fallback.", obj({"view": {"enum": ["live", "list", "events", "summary"], "default": "live"}, "recordingId": {"type": "string", "maxLength": 64}, "since": {"type": "integer", "minimum": 0, "maximum": 9_223_372_036_854_775_807}, "limit": integer(1, 128), "offset": integer(0, 257), "kinds": {"type": "array", "minItems": 1, "maxItems": 16, "uniqueItems": True, "items": {"type": "string", "minLength": 1, "maxLength": 128, "pattern": "^[a-z][a-z0-9_]*$"}}}) | {"allOf": [
        {"if": {"properties": {"view": {"const": "list"}}, "required": ["view"]}, "then": {"propertyNames": {"enum": ["view", "limit", "offset"]}}},
        {"if": {"properties": {"view": {"const": "live"}}}, "then": {"propertyNames": {"enum": ["view", "recordingId", "since", "limit"]}}},
        {"if": {"properties": {"view": {"enum": ["events", "summary"]}}, "required": ["view"]}, "then": {"propertyNames": {"enum": ["view", "recordingId", "since", "limit", "kinds"]}, "properties": {"recordingId": {"pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}}}}
    ]}, True)


T_MCB_ITEMS = ToolSpec("mcb_items", "registry.items", "Search and page registered item identities, at most 128 entries per call. Does not grant or spawn items.", obj({"query": QUERY, "offset": integer(0, 100_000), "limit": integer(1, 128)}), True)


T_MCB_ITEM = ToolSpec("mcb_item", "registry.item", "Inspect one registered item identity and optional metadata, including a count-one stack's bounded ItemStack.getTooltip and public fluidContainer data where supported. source=registry_count_one_template/countOneTemplate=true is a registry template, not an actual inventory stack. Fluid amount/capacity are per observed stack, never an inferred 1000mB constant or multiplied by count. For actual NBT contents use mcb_gui_tooltip on the real stack; available=false or capacity.available=false is unknown. Consult local source only when client GUI/inventory/item data are insufficient.", obj({"registryName": REGISTRY_NAME, "metadata": integer(0, 32_767)}, ["registryName"]), True)


T_MCB_ITEM_VARIANTS = ToolSpec("mcb_item_variants", "registry.variants", "Search and page a registered item's bounded creative variants, at most 128 per call. This lists variants without granting items.", obj({"registryName": REGISTRY_NAME, "query": QUERY, "offset": integer(0, 100_000), "limit": integer(1, 128)}, ["registryName"]), True)


T_MCB_WORLDS = ToolSpec("mcb_worlds", "client.worlds", "Read and page the local saved-world list. Use an exact folder from this list for mcb_load_world; listing does not create or modify a world.", obj({"query": QUERY, "offset": integer(0, 100_000), "limit": integer(1, 128)}), True)


T_MCB_CONNECT_SERVER = ToolSpec("mcb_connect_server", "client.connect", "Schedule a native Minecraft server connection from the normal main menu. Requires current health session, plain host and separate port. Uses the public Forge connection lifecycle and current launcher account authentication, without OS mouse/keyboard or server-list edits. A scheduled receipt is not login/QA readiness; observe lifecycle, snapshot and serverBridge. Never automatically replay a connection.", obj({"host": {"type":"string","minLength":1,"maxLength":253,"pattern":r"^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$"}, "port":integer(1,65535), "expectedSessionId":EXPECTED_SESSION}, ["host","port","expectedSessionId"]))


T_MCB_LOAD_WORLD = ToolSpec("mcb_load_world", "client.load_world", "Schedule loading an existing local world from the main menu, using its exact saved-world folder and current health session ID. MCP first ensures full background takeover; the mod still requires that mode and can return background_required if it changes before execution. No new world is created. Returns scheduled/ticketId; observe snapshot.lifecycle/worldLoaded for completion. Never retried.", obj({"folder": {"type": "string", "minLength": 1, "maxLength": 256, "pattern": r"^(?!\.{1,2}$)[^/\\\x00-\x1F:]+$"}, "expectedSessionId": EXPECTED_SESSION}, ["folder", "expectedSessionId"]))


T_MCB_LEAVE_WORLD = ToolSpec("mcb_leave_world", "client.leave_world", "Schedule normal save/leave of the owned integrated world (including LAN host), or native server disconnect without requiring server MCB, OP or developer. Use current health.lifecycleTarget.expectedWorld and sessionId. Exact local world name/folder and existing authenticated remote names also remain supported. MCP ensures background takeover; execution rechecks native connection/world/player identities. Returns scheduled/ticketId; observe snapshot.lifecycle/worldLoaded for completion. Never retried.", obj({"expectedWorld": EXPECTED_WORLD, "expectedSessionId": EXPECTED_SESSION}, ["expectedWorld", "expectedSessionId"]))


T_MCB_EXIT = ToolSpec("mcb_exit", "client.exit", "Schedule normal client exit with current health sessionId. When a world is loaded also supply health.lifecycleTarget.expectedWorld; normally saves the owned integrated world including LAN, or disconnects a native server without server MCB/OP/developer. Exact local and authenticated remote world names remain supported. MCP ensures background takeover; execution rechecks connection/world/player identities. A scheduled acknowledgment does not prove exit completed. Never retried.", obj({"expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD}, ["expectedSessionId"]))


T_MCB_BACKGROUND = ToolSpec("mcb_background", "client.background", "Explicit combined background mode: enabled=true protects against physical mouse input and continues running while unfocused; enabled=false restores normal physical mouse input and the prior pauseOnLostFocus setting. Client startup preserves normal mouse and the user's pause setting. MCP control actions automatically ensure full takeover, while reads and these manual switches preserve your choice. Read health/snapshot.background afterwards. Never automatically retried.", obj({"enabled": {"type": "boolean"}}, ["enabled"]))


T_MCB_MOUSE = ToolSpec("mcb_mouse", "client.mouse", "Set only physical mouse handling: enabled=true restores normal manual mouse input; enabled=false selects passive mouse protection. Leaves the unfocused-running setting unchanged. Read health/snapshot.background afterwards. Never automatically retried.", obj({"enabled": {"type": "boolean"}}, ["enabled"]))


T_MCB_UNFOCUSED = ToolSpec("mcb_unfocused", "client.unfocused", "Set only unfocused-running behavior: enabled=true continues running while unfocused; enabled=false removes the override and restores the previously saved pauseOnLostFocus setting. Leaves physical mouse handling unchanged. Read health/snapshot.background afterwards. Never automatically retried.", obj({"enabled": {"type": "boolean"}}, ["enabled"]))

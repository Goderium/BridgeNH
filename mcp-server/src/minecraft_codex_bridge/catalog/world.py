"""World tool schemas and native operation declarations."""

from ..material_sources import (
    AE_MATERIAL_SOURCE_SCHEMA,
)
from ..native_wait import (
    NATIVE_WAIT_SCHEMA,
)
from .common import (
    DIMENSION,
    EXPECTED_SESSION,
    EXPECTED_WORLD,
    LIVE_ID,
    POSITION,
    POSITIONS,
    QUERY,
    REGISTRY_NAME,
    ToolSpec,
    integer,
    obj,
)


INSPECT_SCHEMA = obj({**POSITION, "dimension": integer(-2_147_483_648, 2_147_483_647),
    "includeAe": {"type": "boolean", "default": False, "description": "Developer-only bounded owner AE details: per-mounted-host pending sends and native cell resources. Read capability aeWorldReadVersion first; aeWorldNetworkVersion=1 additionally exposes public node/grid IDs (part node side6, block node sides0..6) and activity. Missing nodes do not prove isolation. Ordinary reads skip these enumerations."}}, ["x", "y", "z"])


OUTPUT_RESOURCE_QUERY_SCHEMA = {"oneOf": [
    obj({"kind": {"const": "item"}, "registryName": REGISTRY_NAME, "metadata": integer(0, 32_766)},
        ["kind", "registryName", "metadata"]),
    obj({"kind": {"const": "fluid"}, "fluidName": {"type": "string", "minLength": 1, "maxLength": 128}},
        ["kind", "fluidName"]),
]}


MACHINE_INSPECT_SCHEMA = obj({**POSITION, "dimension": DIMENSION,
    "componentOffset": {**integer(0, 100_000), "default": 0}, "componentLimit": {**integer(1, 128), "default": 32},
    "inputOffset": {**integer(0, 16384), "default": 0}, "inputLimit": {**integer(1, 128), "default": 32},
    "outputQueries": {"type": "array", "minItems": 1, "maxItems": 8, "items": OUTPUT_RESOURCE_QUERY_SCHEMA}}, ["x", "y", "z"])


INSPECT_MANY_SCHEMA = obj({"positions": {**POSITIONS, "maxItems": 128}, "dimension": DIMENSION,
    "compact": {"type": "boolean", "default": False},
    "includeAe": {"type": "boolean", "default": False},
    "includePlayerInventory": {"type": "boolean", "default": False},
    "playerInventoryByteBudget": integer(1024, 131_072),
    "observationByteBudget": integer(8192, 1_048_576), "nativeWait": NATIVE_WAIT_SCHEMA}, ["positions"])


INSPECT_MANY_SCHEMA["allOf"] = [{"if": {"required": ["compact"], "properties": {"compact": {"const": True}}},
    "else": {"properties": {"positions": {"maxItems": 16}}}},
    {"if": {"required": ["includePlayerInventory"], "properties": {"includePlayerInventory": {"const": True}}},
     "then": {"required": ["playerInventoryByteBudget", "observationByteBudget"],
        "properties": {"compact": {"const": False}}},
     "else": {"not": {"anyOf": [{"required": [key]} for key in ("playerInventoryByteBudget", "observationByteBudget")]}}},
    {"if": {"required": ["nativeWait"]}, "then": {"required": ["dimension", "includePlayerInventory", "compact"],
        "properties": {"includePlayerInventory": {"const": True}, "compact": {"const": False}, "positions": {"maxItems": 16}}}},
    {"if": {"required": ["includeAe"], "properties": {"includeAe": {"const": True}}},
     "then": {"properties": {"compact": {"const": False}, "positions": {"maxItems": 16}}}}]


WORLD_SCAN_SCHEMA = {
    **obj({**POSITION, "x": integer(-30_000_000, 29_999_999), "z": integer(-30_000_000, 29_999_999), "dimension": DIMENSION, "radius": integer(0, 128), "radiusChunks": integer(1, 8), "verticalRadiusChunks": integer(1, 8),
        "bounds": obj({prefix + axis: integer(0, 255) if axis == "Y" else integer(-30_000_000, 29_999_999)
            for prefix in ("min", "max") for axis in ("X", "Y", "Z")}, [prefix + axis for prefix in ("min", "max") for axis in ("X", "Y", "Z")]),
        "directional": obj({"facing": {"type": "string", "enum": ["north", "east", "south", "west"]},
            **{key: integer(0, 128) for key in ("forward", "backward", "left", "right", "up", "down")}},
            ["facing", "forward", "backward", "left", "right", "up", "down"]),
        "airSemantics": {"type": "string", "enum": ["block_is_air", "minecraft_air"], "default": "block_is_air"},
        "cursor": {"type": "string", "minLength": 1, "maxLength": 128}, "limit": integer(1, 512)}),
    "dependentRequired": {"x": ["y", "z"], "y": ["x", "z"], "z": ["x", "y"]},
    "oneOf": [
        {"required": ["cursor"], "propertyNames": {"enum": ["cursor", "limit"]}},
        {"required": ["bounds"], "propertyNames": {"enum": ["bounds", "dimension", "limit", "airSemantics"]}},
        {"required": ["directional"], "propertyNames": {"enum": ["directional", "x", "y", "z", "dimension", "limit", "airSemantics"]}},
        {"not": {"anyOf": [{"required": [key]} for key in ("cursor", "bounds", "directional")]},
            "allOf": [{"not": {"required": ["radius", key]}} for key in ("radiusChunks", "verticalRadiusChunks")]},
    ],
}


STRUCTURE_SCHEMA = {
    **obj({**POSITION, "registryName": {**REGISTRY_NAME, "pattern": r"^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+$", "not": {"pattern": r"[\s\x00-\x1F\x7F]"}}, "metadata": integer(0, 32_766), "dimension": DIMENSION, "piece": {"type": "string", "minLength": 1, "maxLength": 128}, "offsetA": integer(-2048, 2048), "offsetB": integer(-2048, 2048), "offsetC": integer(-2048, 2048), "variant": integer(1, 64), "offset": integer(0, 2_097_152), "limit": integer(1, 128), "query": QUERY, "boundsOnly": {"type": "boolean", "default": False},
        "captureId": {"type": "string", "minLength": 1, "maxLength": 128}, "runtimeOffset": {**integer(0, 16384), "default": 0}, "floorBlockY": integer(0, 254),
        "candidatePositions": {"type": "array", "minItems": 1, "maxItems": 16, "uniqueItems": True,
            "items": obj({**POSITION, "group": integer(0, 127)}, ["x", "y", "z", "group"]),
            "description": "Read fresh public getBlocksToPlace suggestions at exact captured XYZ/group. Complete known zero differs from unknown or partial data; suggestions never prove full acceptance or formation."},
        "candidateOffset": integer(0, 15), "includeSurvivalInventory": {"type": "boolean", "default": False,
            "description": "With an exact current placed-controller capture, copy every actual server player slot/full NBT and empty cursor. Read-only finite source evidence; never client inventory or a grant."}}),
    "oneOf": [
        {"required": ["x", "y", "z"], "not": {"anyOf": [{"required": ["registryName"]}, {"required": ["metadata"]}]}},
        {"required": ["registryName", "metadata"], "propertyNames": {"enum": ["registryName", "metadata", "dimension", "piece", "variant", "offset", "limit", "query", "boundsOnly"]}},
    ],
    "dependentRequired": {"offsetA": ["offsetB", "offsetC", "piece"], "offsetB": ["offsetA", "offsetC", "piece"], "offsetC": ["offsetA", "offsetB", "piece"],
        "candidatePositions": ["captureId", "x", "y", "z"], "candidateOffset": ["candidatePositions"], "includeSurvivalInventory": ["captureId", "x", "y", "z"]},
    "allOf": [{"if": {"required": ["runtimeOffset"], "properties": {"runtimeOffset": {"minimum": 1}}}, "then": {"required": ["captureId"]}},
        {"if": {"required": ["boundsOnly"], "properties": {"boundsOnly": {"const": True}}}, "then": {"required": ["piece"], "properties": {"offset": {"const": 0}},
            "not": {"anyOf": [{"required": [key]} for key in ("captureId", "runtimeOffset", "floorBlockY")]}}},
        {"if": {"required": ["candidatePositions"]}, "then": {"not": {"anyOf": [{"required": [key]} for key in
            ("registryName", "metadata", "piece", "offsetA", "offsetB", "offsetC", "variant", "offset", "query", "boundsOnly", "runtimeOffset", "floorBlockY")]}}}],
}


STRUCTURE_SCHEMA["properties"]["alignmentFrames"] = {"type": "boolean", "const": True}


STRUCTURE_SCHEMA["allOf"] = STRUCTURE_SCHEMA["allOf"] + [{"if": {"required": ["alignmentFrames"]}, "then": {
    "required": ["x", "y", "z"], "propertyNames": {"enum": ["x", "y", "z", "dimension", "alignmentFrames", "variant"]}}}]


MACHINE_CAPTURE_SCHEMA = {
    **obj({"expectedSessionId": EXPECTED_SESSION, "expectedWorld": EXPECTED_WORLD, "dimension": DIMENSION,
           "positions": {"type": "array", "minItems": 1, "maxItems": 8, "items": obj(POSITION, ["x", "y", "z"])},
           "includeNbt": {"type": "boolean", "default": False,
               "description": "Explicit developer opt-in to the tile's complete normal writeToNBT serialization; not all transient live memory."},
           "captureId": {"type": "string", "minLength": 1, "maxLength": 64},
           "offset": integer(0, 262144), "limit": {**integer(1, 128), "default": 64},
           "maxPages": {**integer(1, 16), "default": 4}},
          ["expectedSessionId", "expectedWorld"]),
    "oneOf": [
        {"required": ["positions"], "not": {"required": ["captureId"]}, "properties": {"offset": {"const": 0}}},
        {"required": ["captureId"], "not": {"anyOf": [{"required": [key]} for key in ("positions", "includeNbt")]}},
    ],
}


RAY_VECTOR = obj({"x": {"type": "number", "minimum": -30_000_000, "maximum": 30_000_000},
                  "y": {"type": "number", "minimum": -4096, "maximum": 4096},
                  "z": {"type": "number", "minimum": -30_000_000, "maximum": 30_000_000}}, ["x", "y", "z"])


SPATIAL_SCHEMA = {
    **obj({"mode": {"type": "string", "enum": ["raycast", "positions"]}, "dimension": DIMENSION,
           "positions": {**POSITIONS, "maxItems": 128}, "width": {"type": "number", "minimum": 0.1, "maximum": 4},
           "height": {"type": "number", "minimum": 0.1, "maximum": 8}, "offset": {**integer(0, 127), "default": 0},
           "limit": {**integer(1, 128), "default": 32}, "from": RAY_VECTOR, "to": RAY_VECTOR,
           "distance": {"type": "number", "minimum": 0.1, "maximum": 128}, "includeEntities": {"type": "boolean", "default": True}}, ["mode"]),
    "oneOf": [
        {"properties": {"mode": {"const": "positions"}}, "required": ["positions"], "propertyNames": {"enum": ["mode", "dimension", "positions", "width", "height", "offset", "limit"]}},
        {"properties": {"mode": {"const": "raycast"}}, "propertyNames": {"enum": ["mode", "dimension", "from", "to", "distance", "includeEntities"]},
         "dependentRequired": {"from": ["to"], "to": ["from"]}, "not": {"required": ["from", "distance"]}},
    ],
}


STATE_DIFF_SCHEMA = {
    **obj({"sections": {"type": "array", "items": {"type": "string", "enum": ["player", "inventory", "gui", "messages"]},
                        "minItems": 1, "maxItems": 4, "uniqueItems": True, "default": ["player", "inventory", "gui"]},
           "baselineId": LIVE_ID, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 128), "default": 64}}),
    "oneOf": [{"not": {"required": ["baselineId"]}}, {"required": ["baselineId"], "not": {"required": ["sections"]}}],
}


T_MCB_MACHINE_CAPTURE = ToolSpec("mcb_machine_capture", "machine.capture", "Explicit high-privilege local developer observation. Capture 1..8 existing loaded tiles in one owning-server callback/world tick, then page frozen values by captureId/offset; never combine fresh reads as one capture. Exact expectedSessionId/expectedWorld required on every page. MCP maxPages1..16 (default4) coalesces the same frozen capture within10s/512KiB, validating identity/tick/progress; hasMore/nextOffset retain the continuation. includeNbt=true invokes normal tile writeToNBT, exposing the complete serialized tree as typed path entries, including all native Debug fluid/phantom configuration fields that the installed mod serializes. NBT is optional, not all transient live memory; publicState is only the named getter subset. captureId is never a production round ID; no lock over mod background threads. 16MiB/262144 entries, 96KiB/page, 4 retained captures, 30s TTL. Oversized or failed capture is refused rather than published truncated; complete means this page reaches the end, not that live-memory coverage is exhaustive. No NBT loading/setters/world changes/chunk loading; independent remote capture unavailable; LAN host uses the owned integrated context.", MACHINE_CAPTURE_SCHEMA, True, idempotent=False)


T_MCB_WAILA = ToolSpec("mcb_waila", "waila.inspect", "Read current crosshair Waila provider text and optional small NBT preview (includeNbt/advanced default false). Check complete/completeness/providers/providerNbt and target identity. WAILA_PARTIAL preserves partial text and bounded exception types/codes/frames; it does not diagnose the game's overlay. nbtSnapshotTruncated describes only the external preview, independent of complete provider input. cacheTimerAgeMillis is the request-throttle timer age, not payload age; exact payload age is unavailable. Cached data is not an arbitrary-block or authoritative machine query. Prefer before source/screenshots; no aim changes, refresh requests or drawing.", obj({"includeNbt": {"type": "boolean", "default": False}, "advanced": {"type": "boolean", "default": False}}), True)


T_MCB_WORLD_INSPECT = ToolSpec("mcb_world_inspect", "world.inspect", "Inspect one block in an already loaded chunk. Developer-only tile/machine details are gated by the mod and require a local integrated test world. World fluids.tanks entries are flat: index, capacity, fluid as the registered fluid-name string, and amount as the actual quantity; empty tanks may omit fluid and amount. Do not read tank.fluid.amount. GUI FluidSlot.fluid.value is a separate nested client-cache structure, not the world tank DTO; verify real tank quantities here after native fluid actions.", INSPECT_SCHEMA, True)


T_MCB_WORLD_SCAN = ToolSpec("mcb_world_scan", "world.scan", "Read one bounded page of already loaded non-air blocks. Choose radius mode, inclusive absolute bounds={minX,minY,minZ,maxX,maxY,maxZ}, or directional={facing:north|east|south|west,forward,backward,left,right,up,down} with all six extents0..128. Explicit bounds/directional cannot mix with radii; bounds forbids an anchor. Explicit regions must stay inside X/Z[-30000000,29999999], Y[0,255] with each explicit span<=263; clipping is refused. Radius/directional extents remain0..128. Directional/radius calls may omit x/y/z to use current player feet; provided coordinates must be complete. Horizontal/vertical radii use current scan configuration (initially 1 chunk=16 blocks each), with explicit chunk radii 1..8. Legacy radius is equal horizontal/vertical block radius0..128 and cannot mix with chunk radii. Default airSemantics=block_is_air may omit air-like custom blocks. When health advertises worldScanMinecraftAir, first-page airSemantics=minecraft_air counts only registry minecraft:air as air and returns custom air-like blocks as non-air. The immutable mode is echoed on every page. Continue only with nextCursor plus optional limit1..512; cursors are opaque, expiring and consumed once after successful response delivery. Inspect airSemantics, requestedRegionFullyObserved, unloaded coverage, page, visitedSpans, complete and stopReason; zero progress can mean the current tick budget is exhausted. publicMaterial solid/liquid/replaceable are public material properties, not full collision/support-face proof. Never automatically repeat a cursor or drain all pages; no chunks are loaded and no machine inventories are returned.", WORLD_SCAN_SCHEMA, True, idempotent=False)


T_MCB_SCAN_CONFIG = ToolSpec("mcb_scan_config", "world.scan_config", "Read probe/cache defaults and cache counters with {}; hot-set probe radiusChunks/verticalRadiusChunks (1..8), independent local cacheRadiusChunks/cacheVerticalRadiusChunks (1..32), and cacheMaxMiB (64..16384). Probe defaults are 1 chunk=16 blocks per axis; cache defaults are 4 chunks=64 blocks per axis and a lazy 2048 MiB retention budget. Unspecified settings stay unchanged. All supplied values validate atomically; changes invalidate scan cursors and clear cache. Startup defaults come from Forge scan config; runtime overrides are memory-only and do not trigger takeover. The section LRU verifies live block/meta and world/chunk identity on every read, expires idle sections after 5 minutes, and never loads chunks or caches entities, tile contents or final collision proofs. Only audited exact native block/air geometry is reused for planning; dynamic collision and final movement validation stay live. The budget is conservative reference/entry accounting, not total JVM memory or preallocation. Never automatically retried.", obj({"radiusChunks": integer(1, 8), "verticalRadiusChunks": integer(1, 8), "cacheRadiusChunks": integer(1, 32), "cacheVerticalRadiusChunks": integer(1, 32), "cacheMaxMiB": integer(64, 16384)}))


T_MCB_WORLD_STRUCTURE = ToolSpec("mcb_world_structure", "world.structure", "Read public StructureLib interfaces for a loaded local developer controller (x/y/z), or an existing registered prototype (exact registryName+metadata, no placed coordinates). Prototype reads reuse the same bounded piece discovery/geometry and fixed public primary/secondary descriptions, create no tile, and expose controller=null, piece_origin, worldPositionsAvailable=false, fullMachineBoundsAvailable=false and structureFormationVerified=false. Candidate piece bounds never establish active/repeated pieces or a complete machine footprint. Prototype mode rejects controller offsets, runtime captures and floor planning; select an observed piece and inspect bounds completeness, or use the real native NEI preview for the complete current construction variant. After real non-sneaking blueprint use, runtimeObservation supplies exact visited XYZ/ABC, descriptions and placement suggestions for that trigger. With the exact captureId and controller, candidatePositions (1..16 unique XYZ/group) reads fresh public getBlocksToPlace at each captured position, never a representative group list; candidateOffset/nextCandidateOffset resumes an ordered positive prefix. Known zero differs from unknown, partial, unloaded or truncated suggestions; candidateObservation never proves acceptance or formation. includeSurvivalInventory=true returns the actual bound server player all41 slots/full NBT and empty cursor, for finite source selection only. runtimeOffset/nextRuntimeOffset page the original captured coordinate list independently of definition offset; continuation requires the returned captureId and rejects changed/expired captures. query filters groups, limit bounds each page to128. observedBounds/capturedVisitsComplete describe this invocation only, never a complete machine. Optional floorBlockY returns bound-based minimumControllerY/raiseBlocks planning when current mode/context and full visit bounds are verified; missing context or partial bounds is unavailable. Check eventCaptureTruncated/pageLimited/hasMore/coverage. Hints do not prove formation or complete chamber rules. Standalone candidatePositions/includeSurvivalInventory reads automatically bind the fresh current local developer session/world/dimension and validate their native AFTER observation; no sequence wrapper is needed and existing background choices are preserved. Without hints, pieceBounds incrementally summarizes up to2,097,152 compiled entries; inspect complete/partial. boundsOnly=true with one exact piece and offset0 computes only the incremental navigation summary, without descriptions/candidates/runtime or world observations; repeat the same offset-zero request while partial. Geometry work retains a4ms cooperative deadline and65,536 entries per call. Only the inherited native default physical flag and exact audited immutable StructureUtility.step types are memoized; unknown overriding getters remain fresh per occurrence and completed unknown summaries are rescanned. cache reports5minute idle TTL, resource/session/definition-array invalidation, count limits and a conservative1GiB reference-slot retention budget, which is not a bound on the GT-owned transitive graph or a JVM heap measurement. No live world or formed state is cached. Omit piece to discover available pieces. A piece without offsetA/B/C is origin geometry only: worldPositionsAvailable=false and no observed blocks. All three explicit offsets are needed for aligned definition positions; public API does not provide automatic controller anchors. Public descriptions/candidates are partial provider information, not complete hatch/module/count rules or validation. variant1..64 is only a planning trigger count. Check lowest layer, floor and required-air conflicts before native building; no automatic page draining or screenshots.", STRUCTURE_SCHEMA, True)


T_MCB_SURVIVAL_BUILD = ToolSpec("mcb_survival_build", "structure.survival_build", "Run bounded public native survivalConstruct batches at the actual controller with the real held blueprint, exact session/world/dimension and living survival actor. Optional requireSurvivalFlight=true tightens every actual native client/server actor and full body check to real permitted airborne survival; omission preserves prior behavior. Omitted materialSource uses player inventory. Explicit ae_cells requires every source key and a fresh mcb_ae_material_source grid binding; finite and deliberately selected creative_infinite modes stay distinct. Honors native budget<=65536/cooldown without clear/give or ability writes. Require complete per-batch committed/source ledgers: player deductions for player source; actual backing-cell extraction/count evidence and unchanged full player inventory for AE. Unknown/unaccounted sources are unsupported. nativeDone still needs independent formed/registered-role acceptance. No-progress/deadline/uncertain dispatch never authorize replay. For a fresh blueprint_build with finite player materials, opt-in survivalBuild.finishAirTail reuses captured public candidates and ordinary placement for at most16 remaining air cells; do not restart a whole build on an existing controller. Optional excludedBuildEnvelope is a bounded captured future-body exclusion; each actual native client/server body must remain outside before shell callbacks, with no formation claim.", obj({
        "expectedSessionId": {"type": "string", "minLength": 1, "maxLength": 128},
        "expectedWorld": {"type": "string", "minLength": 1, "maxLength": 128}, "dimension": DIMENSION,
        **POSITION, "blueprintHotbarSlot": integer(0, 8), "side": integer(0, 5), "requireSurvivalFlight": {"type": "boolean", "default": False},
        "excludedBuildEnvelope": obj({"minX": integer(-29_999_999, 29_999_999), "minY": integer(0, 255), "minZ": integer(-29_999_999, 29_999_999),
            "maxX": integer(-29_999_999, 29_999_999), "maxY": integer(0, 255), "maxZ": integer(-29_999_999, 29_999_999)}, ["minX", "minY", "minZ", "maxX", "maxY", "maxZ"]),
        "elementBudget": integer(1, 65_536), "materialSource": AE_MATERIAL_SOURCE_SCHEMA, "maxBatches": {**integer(1, 32), "default": 1},
        "timeout_ms": {**integer(500, 8000), "default": 7500}},
        ["expectedSessionId", "expectedWorld", "dimension", "x", "y", "z", "blueprintHotbarSlot", "side"]), False, idempotent=False)


T_MCB_WORLD_INSPECT_MANY = ToolSpec("mcb_world_inspect_many", "world.inspect_many", "Read loaded owner positions. dimension is top-level; positions contain only x/y/z. No includeInventory argument: ordinary noncompact owner data already includes available machine inventory. Noncompact supports1..16 points; developer compact=true supports1..128 block identities. Separate includePlayerInventory=true needs the advertised developer capability, compact=false, both playerInventoryByteBudget/observationByteBudget and existing full background protection; standalone calls bind the current session/world/dimension from a fresh snapshot, while sequences retain their explicit expectedWorld; it adds complete fresh client playerInventory and AFTER context, with no partial inventory on overflow. Advertised nativeObservationWaitVersion=1 optionally enables nativeWait with exact owner bindings and only fixed item_delta, state_equal, fluid_match predicates on normal server END ticks; at most 2 observers, 16 owners/conditions, 6500ms window and 8000ms total dispatch including fresh final evidence. The shared 4ms tick work budget cannot preempt a callback; partial/late/unknown receipts refuse continuation. No arbitrary methods, expressions or ticking. Reads are non-atomic. Check every point plus requestedCount/processedCount/nextOffset/complete/partial; partial covers only the ordered requestIndex prefix, so continue the original unprocessed suffix. Unloaded/unknown/partial data never prove all-clear; no chunk loading/generation.", INSPECT_MANY_SCHEMA, True)


T_MCB_WORLD_TILES = ToolSpec("mcb_world_tiles", "world.tiles", "Developer-only discovery of nearby already loaded tile entities in a sphere of radius 1..64 (default 16). Requires a local integrated world; candidate scan is capped by the mod, so inspect totalMatchedIsExact/candidateLimitReached. Does not load chunks.", obj({**POSITION, "dimension": DIMENSION, "radius": {**integer(1, 64), "default": 16}, "query": QUERY, "offset": integer(0, 100_000), "limit": integer(1, 128)}, ["x", "y", "z"]), True)


T_MCB_MACHINE_INSPECT = ToolSpec("mcb_machine_inspect", "machine.inspect", "Read loaded GT/TST machine state, shutdown/checkRecipe results, modes, processing, public components/ports, fixed public structureDescription/machineDescription/structureTooltip (no physical Shift) and available upgrades on the owning server thread in the existing local integrated developer context (not opened to LAN). For projections use processing.structureFormed for current formation, processing.structureChanged for structure changes, state.* for basic machine state and components.entries for registered ports. Public IModularizedMachine.getAllModularHatches adds registered modular_hatch entries, moduleType and IExecutionCore state/main-machine identity; components.modularHatches reports availability. Registration is not an exhaustive physical scan or chamber rule. needProgressingTimeTicks preserves its getter meaning; core eut has no inferred recipe/controller semantics. signedCalculatedEUt is current calculated controller EU/t, negative consumes; actualEnergyUsagePerTick is consumption while negative. lastMatchedRecipe and lastProcessingCalculation can be stale caches, not proof of a running recipe. Supply componentOffset/componentLimit (max 128, default 32); inspect coverage/errors/components.nextOffset/hasMore/budget. Optional outputQueries (1..8) declares untagged item keys {kind:item,registryName,metadata:0..32766} or fluid keys {kind:fluid,fluidName} for public local output-provider cache lookup. Existing aggregate outputStorage totals remain available by default. outputStorage.resourceQuery reports schemaVersion=1, source=public_provider_cached_amount, scope=local_output_provider_cache_only, requestedCount, complete, ordered entries and the actual owner metaTileId/metaClass/XYZ/dimension. Each available entry echoes its queryIndex/kind and resolved no-NBT item or fluid identity; cachedAmount is an exact nonnegative long decimal string through 9223372036854775807. Require complete coverage and available=true for every declared entry before interpreting a quantity; incomplete or unavailable queries are unknown, never zero. Bind fresh before/after reads to the same actual owner and session for per-identity deltas. This lookup neither enumerates the cache nor counts the connected network or inserted cell, and does not itself prove production. Preserve actual identity and long quantities. fluidInputs separately pages the public getFluidList by inputOffset/inputLimit, retaining all original null slot indices and totalSlots/configuredFluids; unavailable is unknown, not a first-fluid fallback. Debug fluids are configured templates, not finite materials. Tags are bounded summaries; explicit local mcb_machine_capture(includeNbt=true) supplies frozen complete serialized NBT when needed. No per-machine adaptations, structure validation, recipe reset, inventory mutation or cache enumeration.", MACHINE_INSPECT_SCHEMA, True)


T_MCB_ENTITIES = ToolSpec("mcb_entities", "world.entities", "Read a bounded page of already loaded live entities and available identity/name/position/motion/rotation/bounds/health/item/owner fields. Optional complete x/y/z defaults to current player feet; radiusChunks/verticalRadiusChunks (1..8) use session scan defaults. Explicit intersectBounds=true retains every actual AABB intersecting the complete query box plus unknown bounds candidates; inspect boundsMode/queryBounds/unknownBoundsCandidates and each boundsStatus. Default uses entity positions. Source follows profile and dimension; no chunks or entities are forced loaded. Use offset/limit (at most 128) and inspect coverage/budget/nextOffset/hasMore. Missing fields are unknown; no automatic full scan or screenshots.", {**obj({**POSITION, "dimension": DIMENSION, "radiusChunks": integer(1, 8), "verticalRadiusChunks": integer(1, 8), "intersectBounds": {"type": "boolean", "default": False}, "query": QUERY, "offset": {**integer(0, 100_000), "default": 0}, "limit": {**integer(1, 128), "default": 64}}), "dependentRequired": {"x": ["y", "z"], "y": ["x", "z"], "z": ["x", "y"]}}, True)


T_MCB_SPATIAL = ToolSpec("mcb_spatial", "world.spatial", "Read loaded-world raycast or bounded position occupancy/collision/support/liquid/light data, using the current profile/dimension. positions mode accepts 1..128 points and optional player-size width/height with explicit offset/limit; raycast accepts paired from/to (distance 0.1..128 validated by mod) or current-view distance, includeEntities defaults true. Inspect loaded/unknown/partial/coverage/budget, and use actual boxes instead of guessing walkability from screenshots. Does not pathfind, move the player or force-load chunks.", SPATIAL_SCHEMA, True)


T_MCB_STATE_DIFF = ToolSpec("mcb_state_diff", "state.diff", "Create an immutable bounded player/inventory/gui/messages baseline, or compare a baselineId to fresh current structured state without screenshots. Initial sections default player/inventory/gui; continuation cannot change sections. offset/nextOffset index baseline comparison nodes, not changed-entry count: unchanged or budget-only pages can have no changes while advancing. Inspect hasMore/coverage/timestamps/valueTruncated/budget. Baselines expire/context-invalidate; no consumption, automatic advance or full-page loop. First call stores a baseline so this read-only tool is non-idempotent and cannot be polled by wait_for.", STATE_DIFF_SCHEMA, True, idempotent=False)


T_MCB_MACHINE_WORK = ToolSpec("mcb_machine_work", "machine.set_working", "Developer-only enable/disable of a GT machine's normal allowed-to-work state. Requires a local integrated world matching expectedWorld and the expected meta-tile ID at the coordinates. Uses fixed public enable/disable methods; does not reset recipes. Never automatically retried.", obj({**POSITION, "dimension": DIMENSION, "expectedWorld": EXPECTED_WORLD, "expectedMetaTileId": integer(0, 32_767), "working": {"type": "boolean"}}, ["x", "y", "z", "expectedWorld", "expectedMetaTileId", "working"]))

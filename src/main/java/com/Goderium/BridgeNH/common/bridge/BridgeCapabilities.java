package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.client.control.ClientWindowControls;
import com.Goderium.BridgeNH.client.gui.GuiCallbacks;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.gui.ScreenSemantics;
import com.Goderium.BridgeNH.client.gui.SodiumSettingsControls;
import com.Goderium.BridgeNH.client.gui.VanillaSettingsControls;
import com.Goderium.BridgeNH.client.navigation.ApproachPlanner;
import com.Goderium.BridgeNH.client.navigation.NavigationGoal;
import com.Goderium.BridgeNH.client.navigation.NavigationSegments;
import com.Goderium.BridgeNH.client.navigation.SchematicGoal;
import com.Goderium.BridgeNH.common.ae.AeOrderAdapter;
import com.Goderium.BridgeNH.common.inventory.InspectManyPlayerInventory;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransfer;
import com.Goderium.BridgeNH.common.machine.MachineObservationCapture;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaAdapter;
import com.Goderium.BridgeNH.common.structure.JumpPlacement;
import com.Goderium.BridgeNH.common.structure.NativeGtWrench;
import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacement;
import com.Goderium.BridgeNH.common.structure.PlacementAfterObservation;
import com.Goderium.BridgeNH.common.structure.PlacementAttachment;
import com.Goderium.BridgeNH.common.structure.PlacementFacing;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAlignmentFrames;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.common.world.ScanRegion;
import com.Goderium.BridgeNH.common.world.StateDiffAdapter;
import com.Goderium.BridgeNH.common.world.WorldInspectManyAdapter;
import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.config.ScanSettings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Public capability declarations, grouped independently of action dispatch.
 * No capability result is cached; GameBridge supplies current readiness inputs.
 */
@com.google.common.annotations.VisibleForTesting
public final class BridgeCapabilities {
    private static final String[] OPERATIONS = {"bridge.health", "bridge.policy", "client.snapshot", "gui.inspect", "inventory.inspect", "baubles.inspect", "gui.open_baubles", "gui.open_pause_menu",
            "world.inspect", "world.scan", "client.screenshot", "player.look", "player.hotbar", "player.input", "player.task", "player.follow",
            "server.observe", "agent.dialogue", "structure.goal", "player.interact", "player.place_block", "player.plan_approach", "player.navigate", "player.replace_owned_block", "player.flight", "player.flight_check", "gui.slot_click", WholeStackTransfer.OPERATION, "gui.close", "gui.open_inventory", "gui.click", "gui.key", "gui.pointer", "chat.send",
            "client.diagnostics", "client.window", "client.window_action", "client.mods", "client.messages", "client.recording", "registry.items", "registry.item", "registry.variants",
            "world.inspect_many", "world.tiles", "machine.set_working", "dev.teleport", "client.worlds", "client.load_world",
            "client.connect", "client.leave_world", "client.exit", "client.background", "client.mouse", "client.unfocused", "world.scan_config",
            "world.structure", "gui.tooltip", "nei.inspect", "nei.view", "nei.bookmarks", "nei.bookmark_action", "research.read", "nei.open", "nei.navigate", "nei.item_action", "nei.preview_control", "recipes.query", "machine.inspect", MachineObservationCapture.OPERATION, "waila.inspect",
            "ae.inspect", "ae.material_source", "ae.action", AeOrderAdapter.OPERATION, "gui.widgets", "gui.widget_action", "world.entities", "world.spatial", "input.bindings", "state.diff",
            "qa.catalog", "qa.run", "qa.status", "qa.results", "qa.command", "qa.cancel", "inventory.supply", "structure.survival_build"};

    static void append(JsonObject result, JsonObject recording, ScanSettings scanSettings, Iterable<String> inputKeys,
            boolean publicCatalog, boolean localBackend, boolean remoteBackend) {
        operations(result, recording);
        interaction(result);
        recipes(result, publicCatalog, localBackend, remoteBackend);
        movement(result);
        observations(result);
        limits(result, scanSettings);
        input(result, inputKeys);
    }

    private static void operations(JsonObject result, JsonObject recording) {
        JsonArray capabilities = new JsonArray();
        for (String operation : OPERATIONS) {
            if ("gui.click".equals(operation) && !GuiCallbacks.supportsClick()) continue;
            if ("gui.key".equals(operation) && !GuiCallbacks.supportsKey()) continue;
            capabilities.add(new JsonPrimitive(operation));
        }
        result.add("capabilities", capabilities);
        result.addProperty("playerActivityRecordingVersion", 1);
        result.add("playerActivityRecording", recording);
        result.add("contextRpc", GuardedContext.capabilities());
    }

    private static void interaction(JsonObject result) {
        result.addProperty("interactionSneaking", true);
        result.addProperty("interactionBlockTargeting", true);
        result.addProperty("interactionPartTargetingVersion", 1);
        result.addProperty("aeWorldReadVersion", 1);
        result.addProperty("aeWorldNetworkVersion", 1);
        result.addProperty("machineDataAccessRoleVersion", 1);
        result.addProperty("interactionViewBinding", true);
        result.addProperty("placementFacingVersion", PlacementFacing.VERSION);
        result.addProperty("placementFacingCreativeVersion", 1);
        result.addProperty("jumpPlacementVersion", JumpPlacement.VERSION);
        result.addProperty("nativeHeldAttackVersion", 1);
        result.addProperty("interactionHeldBindingVersion", 1);
        result.addProperty("interactionAttackViewBinding", true);
        result.addProperty("approachWrenchFacingVersion", NativeGtWrench.APPROACH_VERSION);
        result.addProperty("interactionHitPointVersion", 1);
        result.addProperty("structureAlignmentFramesVersion", StructureAlignmentFrames.VERSION);
        result.addProperty("clientMenuControlsVersion", VanillaSettingsControls.VERSION);
        result.addProperty("clientStartupRecoveryVersion", 1);
    }

    private static void recipes(JsonObject result, boolean publicCatalog, boolean localBackend, boolean remoteBackend) {
        result.addProperty("recipesQuery", true);
        JsonObject recipeAccess = new JsonObject();
        recipeAccess.addProperty("version", 1);
        recipeAccess.addProperty("publicCatalog", publicCatalog);
        recipeAccess.addProperty("catalogSource", "client_runtime_public_nei_registry");
        recipeAccess.addProperty("catalogIncludesHiddenRecipes", false);
        recipeAccess.addProperty("catalogInventorySource", "current_client_inventory_not_server_receipt");
        recipeAccess.addProperty("catalogCursorPrefix", "client:");
        recipeAccess.addProperty("localMachineBackend", localBackend);
        recipeAccess.addProperty("remoteMachineBackend", remoteBackend);
        recipeAccess.addProperty("machineBackendRequirement", "Existing owned integrated developer context (including LAN host) or authenticated remote operator read. Game command permissions are separate.");
        result.add("recipeQueryAccess", recipeAccess);
        JsonObject captureAccess = new JsonObject(); captureAccess.addProperty("version", 1);
        captureAccess.addProperty("available", localBackend);
        captureAccess.addProperty("localOnly", true); captureAccess.addProperty("explicitNbtOptIn", true);
        captureAccess.addProperty("maxTargets", 8); captureAccess.addProperty("maxCaptureBytes", MachineObservationCapture.MAX_BYTES);
        captureAccess.addProperty("maxPageBytes", MachineObservationCapture.PAGE_BYTES); captureAccess.addProperty("ttlMillis", 30000);
        captureAccess.addProperty("coverage", "Frozen public getter subset plus optional native serialized NBT. Not all live memory; captureId is not a production round ID.");
        result.add("machineCaptureAccess", captureAccess);
    }

    private static void movement(JsonObject result) {
        result.addProperty("nativeBlockPlacement", true);
        result.addProperty("nativeSupplyPreserveInventoryVersion", 1);
        result.addProperty("inventoryPlayerTabVersion", 1);
        result.addProperty("aeGoalWorkflowVersion", 1);
        result.addProperty("aePortableOwnerVersion", 1);
        result.addProperty("aeOrderStatusWaitVersion", 1);
        result.addProperty("placementAfterObservationVersion", PlacementAfterObservation.VERSION);
        result.addProperty("nativeApproachPlanning", true);
        result.addProperty("nativeNavigation", true); result.addProperty("approachCurrentOnly", true);
        result.addProperty("nativeNavigationGoalVersion", 1); result.addProperty("nativeNavigationSegmentsVersion", 1);
        result.addProperty("nativeNearbyGoalVersion",1); result.addProperty("nativePlayerFollowVersion",1);
        JsonObject follow=new JsonObject(); follow.addProperty("integratedWorldOnly",true); follow.addProperty("targetRefreshMillis",1000);
        follow.addProperty("arrivalRadius",3); follow.addProperty("resumeRadius",4); follow.addProperty("maxTargetDistance",28);
        follow.addProperty("requiredClearBodyRays",2); follow.addProperty("totalBodyRays",3); follow.addProperty("transient",true);
        result.add("playerFollowContract",follow);
        result.addProperty("nativeCrosshairArrivalVersion", 1);
        JsonObject routeContract=new JsonObject(); routeContract.addProperty("reachableCandidateComparison",true); routeContract.addProperty("maxCandidateRouteAttempts",16);
        routeContract.addProperty("aggregateNodes",32768); routeContract.addProperty("aggregateGeometrySamples",131072); routeContract.addProperty("flexiblePlanningGates",true);
        routeContract.addProperty("automaticDiggingOrBridging",false); result.add("navigationGoalContract",routeContract);
        result.addProperty("nativeNavigationCompactReceiptVersion", 1); result.addProperty("genericSchematicGoalVersion", 1);
        JsonObject schematicContract = new JsonObject(); schematicContract.addProperty("comparisonOnly", true);
        schematicContract.addProperty("autoExecution", false); schematicContract.addProperty("formationProved", false);
        schematicContract.addProperty("blockMetadataRotation", false); schematicContract.addProperty("maxCells", 512);
        schematicContract.addProperty("maxCompactExpandedCells",SchematicGoal.MAX_CELLS); schematicContract.addProperty("paletteAndRegions",true);
        schematicContract.addProperty("missingOnlyNeighbourReads",true);
        result.add("genericSchematicGoalContract", schematicContract);
        JsonObject inputContract=new JsonObject(); inputContract.addProperty("statusTool","mcb_task_status"); inputContract.addProperty("cancelTool","mcb_task_cancel");
        inputContract.addProperty("exactTaskIdRequired",true); inputContract.addProperty("cancelPreservesAgentMode",true); result.add("inputTaskContract",inputContract);
        result.add("navigationCostModel", NavigationCosts.settings());
        result.addProperty("approachNormalReachVersion", 1);
        result.addProperty("approachSharedTargetsVersion", 1);
        result.addProperty("approachWalkingSegmentsVersion", 1);
        result.addProperty("walkingInteractionGoalVersion", 2);
        result.addProperty("interactionBuildEnvelopeVersion", 1);
        result.addProperty("nativeGtWrenchFacingVersion", 1);
        result.addProperty("guiNativeWaitVersion", 1);
        result.addProperty("approachMovementStrategyVersion", ApproachPlanner.MOVEMENT_STRATEGY_VERSION);
        result.addProperty("approachOperationPostureVersion", ApproachPlanner.OPERATION_POSTURE_VERSION);
        result.addProperty("approachClientPhaseClockVersion", ApproachPlanner.CLIENT_PHASE_CLOCK_VERSION);
        result.addProperty("survivalFlightPostureVersion", 1);
        result.addProperty("approachBuildEnvelopeVersion", 1); result.addProperty("nativeWalkingStepVersion", 1); result.addProperty("survivalBuildEnvelopeVersion", 1);
        result.addProperty("inputKeyDiagnostics", true);
        result.addProperty("nativeOwnedBlockReplacement", true);
        result.add("nativeOwnedBlockReplacementContract", OwnedBlockReplacement.capabilities());
        result.add("nativeSurvivalConstructionContract", SurvivalConstructionAdapter.capabilities());
        JsonArray approachIntents = new JsonArray();
        for (String intent : new String[] {"place_block", "use", "attack"}) approachIntents.add(new JsonPrimitive(intent));
        result.add("approachPlanningIntents", approachIntents);
    }

    private static void observations(JsonObject result) {
        result.addProperty("neiBookmarksVersion", 1);
        result.addProperty("neiBookmarkOperations", "nei.bookmarks observes exact namespace/entry IDs and contextToken; nei.bookmark_action manages native items, recipes, groups, ordering, quantities, namespaces and task-owned cleanup. Limits:64 namespaces,4096 observed entries,512 deletions. Permanent/future entries are retained; world/reload ownership loss preserves unknown entries. Native save is requested, not a disk-durability receipt.");
        result.addProperty("worldInspectManyCompact", true);
        result.addProperty("nativeObservationWaitVersion", 1);
        result.addProperty("worldInspectManyPlayerInventory", true);
        result.add("worldInspectManyPlayerInventoryContract", InspectManyPlayerInventory.capabilities());
        result.addProperty("worldScanBounds", true);
        result.addProperty("worldScanDirectional", true);
        result.addProperty("worldScanMinecraftAir", true);
        result.addProperty("worldBlockPublicMaterial", true);
        result.add("screenKinds", ScreenSemantics.kinds());
        result.addProperty("sodiumSettingsControlsVersion", SodiumSettingsControls.VERSION);
        result.addProperty("clientWindowControlsVersion", ClientWindowControls.VERSION);
        result.addProperty("flightControl", "Local developer creative mode; player.flight set sends normal player abilities, snapshot.player.serverFlight confirms later server ticks. Jump off the ground before enabling.");
        result.addProperty("guiStructureRecheck", "Public GT MUI2 tooltip/texture semantics; gui.widget_action recheck_structure invokes the current normal two-state button once.");
        result.addProperty("guiPhantomInputs", "Mark without give: MUI ghost_drag or AE cell_config_set(source). Sources: observed neiItemId (actual full NEI NBT), registryName/metadata, playerSlot (owned cell/item copy), fluidName/amount (native display template). nei.view panel exposes IDs bound to this screen/view. AE config indices are independent of real container slots; its delta flush submits the native confirmation directly. Read actual synchronized config and inventory, never a drag icon. Survival marking cannot generate stock. Successful source marks auto-release NEI fakes; nei.item_action release cancels native/manual leftovers, preserving real cursor and config. ghostRelease reports cleanup separately; never replay marking to release it. Debug finite amounts are virtual availability; clean temporary creative real markers manually.");
        result.addProperty("researchRead", "Exact NEI-linked public Thaumcraft research keys, current player-visible pages and bounded continuations; no research granting.");
        result.addProperty("guiLegacyTextFields", true);
        result.addProperty("guiInputReceipt", "Native callback completion and before/after client widget observations are separate from actual server-effect confirmation.");
        result.addProperty("serverPauseGuard", true);
        result.add("qaPrivateDimensionRoute", PrivateDimensionQaAdapter.contract());
    }

    private static void limits(JsonObject result, ScanSettings scanSettings) {
        JsonObject limits = new JsonObject();
        limits.addProperty("inputTicks", 200);
        limits.addProperty("scanRadius", ScanSettings.MAX_CHUNKS * 16);
        limits.addProperty("scanRadiusChunks", ScanSettings.MAX_CHUNKS);
        limits.addProperty("scanBoundsAxisSpan", ScanRegion.MAX_AXIS_SPAN);
        limits.addProperty("scanDirectionalExtent", ScanRegion.MAX_EXTENT);
        limits.addProperty("scanResults", 512);
        limits.addProperty("scanCacheRadiusChunks", ScanSettings.MAX_CACHE_CHUNKS);
        limits.addProperty("scanCacheMaxMiB", ScanSettings.MAX_CACHE_MIB);
        limits.addProperty("scanCellVisitsPerTick", WorldScan.CELLS_PER_TICK);
        limits.addProperty("scanSectionProbesPerTick", WorldScan.PROBES_PER_TICK);
        limits.addProperty("scanNanosPerTick", WorldScan.NANOS_PER_TICK);
        limits.addProperty("scanBlockBytesPerPage", WorldScan.BLOCK_BYTES_PER_PAGE);
        limits.addProperty("scanOpenCursors", WorldScan.MAX_STATES);
        limits.addProperty("approachCandidates", ApproachPlanner.MAX_CANDIDATES);
        limits.addProperty("approachServerRays", ApproachPlanner.MAX_RAYS);
        limits.addProperty("approachNanosPerPhase", ApproachPlanner.WORK_NANOS);
        limits.addProperty("scanCursorIdleMillis", WorldScan.IDLE_NANOS / 1000000L);
        result.add("scanDefaults", scanSettings.inspect());
        result.add("scanCache", WorldObservationsAdapter.cacheStatus());
        limits.addProperty("inventorySlots", 256);
        limits.addProperty("guiSlots", 512);
        limits.addProperty("screenshotBytes", 8 * 1024 * 1024);
        limits.addProperty("screenshotMaxDimension", 1600);
        limits.addProperty("requestTimeoutMillis", 8000);
        limits.addProperty("navigationDefaultTimeoutMillis", 6000); limits.addProperty("navigationUnsegmentedMaxTimeoutMillis", 6500); limits.addProperty("navigationSegmentedTimeoutMillis", 60000);
        limits.addProperty("navigationDistanceBlocks", 32); limits.addProperty("navigationSegmentedDistanceBlocks", NavigationSegments.MAX_DISTANCE);
        limits.addProperty("navigationMaxWindows", NavigationSegments.MAX_SEGMENTS); limits.addProperty("navigationGoalCandidates", NavigationGoal.MAX_CANDIDATES);
        limits.addProperty("genericSchematicCells", 512); limits.addProperty("genericSchematicRelativeExtent", 2048);
        limits.addProperty("catalogPage", 128);
        limits.addProperty("itemVariants", 8192);
        limits.addProperty("messageHistory", 128);
        limits.addProperty("inspectManyPositions", 16);
        limits.addProperty("inspectManyCompactPositions", WorldInspectManyAdapter.POSITION_LIMIT);
        limits.addProperty("inspectManyCompactResultBytes", WorldInspectManyAdapter.BYTE_LIMIT);
        limits.addProperty("inspectManyCompactNanosPerTick", WorldInspectManyAdapter.WORK_BUDGET_NANOS);
        limits.addProperty("inspectManyPlayerInventorySlots", InspectManyPlayerInventory.SLOT_LIMIT);
        limits.addProperty("inspectManyPlayerInventoryResultBytes", InspectManyPlayerInventory.INVENTORY_BYTE_LIMIT);
        limits.addProperty("inspectManyPlayerInventoryObservationBytes", InspectManyPlayerInventory.OBSERVATION_BYTE_LIMIT);
        limits.addProperty("inspectManyPlayerInventoryNanos", InspectManyPlayerInventory.WORK_BUDGET_NANOS);
        limits.addProperty("batchPointBytes", 48 * 1024);
        limits.addProperty("tileRadius", 64);
        limits.addProperty("tileCandidates", 8192);
        limits.addProperty("tooltipLines", 32);
        limits.addProperty("tooltipLineCharacters", 512);
        limits.addProperty("guiWidgets", GuiDataAdapter.WIDGET_LIMIT);
        limits.addProperty("structurePositions", StructureAdapter.PAGE_LIMIT);
        limits.addProperty("structureEntriesPerPage", StructureAdapter.SCAN_LIMIT);
        limits.addProperty("structureCompiledEntries", StructureAdapter.COMPILED_LIMIT);
        limits.addProperty("structureResultBytes", StructureAdapter.BYTE_LIMIT);
        limits.addProperty("structureSoftBudgetNanos", StructureAdapter.SOFT_BUDGET_NANOS);
        limits.addProperty("entityResults", 128);
        limits.addProperty("spatialPositions", 128);
        limits.addProperty("structuredObservationBytes", 128 * 1024);
        limits.addProperty("stateBaselines", StateDiffAdapter.MAX_BASELINES);
        limits.addProperty("stateBaselineBytes", StateDiffAdapter.BASELINE_BYTES);
        result.add("limits", limits);
    }

    private static void input(JsonObject result, Iterable<String> inputKeys) {
        JsonArray keys = new JsonArray();
        for (String key : inputKeys) keys.add(new JsonPrimitive(key));
        result.add("inputKeys", keys);
        JsonArray modes = new JsonArray();
        for (int mode : new int[] {0, 1, 2, 3, 4, 6}) modes.add(new JsonPrimitive(mode));
        result.add("slotClickModes", modes);
        result.add("wholeStackTransfer", WholeStackTransfer.capabilities());
        result.add("placementAttachmentBinding", PlacementAttachment.capabilities());
        result.addProperty("worldPlayerData", "Loaded client block registry, metadata and tile class only.");
        result.addProperty("worldDeveloperData", "Loaded integrated-server blocks, inventories, fluids and optional public GT/TST getters.");
        result.addProperty("guiInputSource", "Use gui.widget_action for advertised widgets, ae.action for AE resources/patterns/cell_config, nei.open/navigate for recipes, nei.item_action for NEI search/phantom release/explicit creative acquisition, and nei.preview_control for preview controls. NEI ghost sources never need give. Ordinary real slots use gui.slot_click; AE virtual slots use semantic actions rather than cached-hover coordinate clicks.");
        JsonArray navigationKeys = new JsonArray();
        for (int key : GuiCallbacks.NAVIGATION_KEYS) navigationKeys.add(new JsonPrimitive(key));
        result.add("guiNavigationKeys", navigationKeys);
        result.addProperty("guiCharacterInput", "keyCode 0 with one printable character; normal chat uses native permissions, command-block screens require local developer profile");
    }
}

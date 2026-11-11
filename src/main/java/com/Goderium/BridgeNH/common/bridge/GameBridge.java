package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.client.control.BackgroundControl;
import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.client.control.ClientDisplayState;
import com.Goderium.BridgeNH.client.control.ClientWindowControls;
import com.Goderium.BridgeNH.client.control.InputDiagnostics;
import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import com.Goderium.BridgeNH.client.control.RuntimeBindingsAdapter;
import com.Goderium.BridgeNH.client.gui.BaublesInventoryAdapter;
import com.Goderium.BridgeNH.client.gui.ContainerSyncAdapter;
import com.Goderium.BridgeNH.client.gui.CreativeGuiAdapter;
import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiCallbacks;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.gui.GuiObservationWait;
import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import com.Goderium.BridgeNH.client.gui.ScreenSemantics;
import com.Goderium.BridgeNH.client.gui.VanillaSettingsControls;
import com.Goderium.BridgeNH.client.gui.WailaDataAdapter;
import com.Goderium.BridgeNH.client.lifecycle.LifecycleTasks;
import com.Goderium.BridgeNH.client.lifecycle.StartupScreenRecovery;
import com.Goderium.BridgeNH.client.lifecycle.WorldLifecycle;
import com.Goderium.BridgeNH.client.navigation.ApproachPlanner;
import com.Goderium.BridgeNH.client.navigation.CrosshairArrival;
import com.Goderium.BridgeNH.client.navigation.DeveloperDimensionTravel;
import com.Goderium.BridgeNH.client.navigation.FlightCapabilityEvidence;
import com.Goderium.BridgeNH.client.navigation.FlightPathPlanner;
import com.Goderium.BridgeNH.client.navigation.FollowTarget;
import com.Goderium.BridgeNH.client.navigation.GroundedStep;
import com.Goderium.BridgeNH.client.navigation.InteractionHitPoint;
import com.Goderium.BridgeNH.client.navigation.InteractionNavigationGoal;
import com.Goderium.BridgeNH.client.navigation.NavigationStartSettle;
import com.Goderium.BridgeNH.client.navigation.NearbyNavigationGoal;
import com.Goderium.BridgeNH.client.navigation.PlayerNavigation;
import com.Goderium.BridgeNH.client.navigation.SchematicGoal;
import com.Goderium.BridgeNH.client.navigation.SurvivalFlightPosture;
import com.Goderium.BridgeNH.client.navigation.TeleportSafety;
import com.Goderium.BridgeNH.client.navigation.WalkingPathPlanner;
import com.Goderium.BridgeNH.client.navigation.WalkingSweepProof;
import com.Goderium.BridgeNH.client.nei.NeiControlAdapter;
import com.Goderium.BridgeNH.client.nei.NeiBookmarkAdapter;
import com.Goderium.BridgeNH.client.nei.NeiDataAdapter;
import com.Goderium.BridgeNH.client.nei.NeiItemControlAdapter;
import com.Goderium.BridgeNH.client.nei.NeiModifierHold;
import com.Goderium.BridgeNH.client.nei.NeiNativeRenderProvider;
import com.Goderium.BridgeNH.client.nei.NeiOverlayAdapter;
import com.Goderium.BridgeNH.client.nei.NeiPreviewProvider;
import com.Goderium.BridgeNH.client.nei.NeiPreviewResources;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter;
import com.Goderium.BridgeNH.client.nei.TooltipModifiers;
import com.Goderium.BridgeNH.client.recording.ClientMessages;
import com.Goderium.BridgeNH.client.recording.PlayerActivityRecorder;
import com.Goderium.BridgeNH.client.render.FramebufferCapture;
import com.Goderium.BridgeNH.common.ae.AeControlAdapter;
import com.Goderium.BridgeNH.common.ae.AeDataAdapter;
import com.Goderium.BridgeNH.common.ae.AeNativeControls;
import com.Goderium.BridgeNH.common.ae.AeOrderAdapter;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.common.ae.AeSurvivalSource;
import com.Goderium.BridgeNH.common.dialogue.AgentDialogueMailbox;
import com.Goderium.BridgeNH.common.inventory.ClientCatalog;
import com.Goderium.BridgeNH.common.inventory.InspectManyPlayerInventory;
import com.Goderium.BridgeNH.common.inventory.InventoryCapacity;
import com.Goderium.BridgeNH.common.inventory.NativeHotbarSwap;
import com.Goderium.BridgeNH.common.inventory.NativeSupplyAdapter;
import com.Goderium.BridgeNH.common.inventory.PlayerSlotBinding;
import com.Goderium.BridgeNH.common.inventory.SlotAcceptance;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransfer;
import com.Goderium.BridgeNH.common.machine.DeveloperMachineTools;
import com.Goderium.BridgeNH.common.machine.GregTechAdapter;
import com.Goderium.BridgeNH.common.machine.MachineDataAdapter;
import com.Goderium.BridgeNH.common.machine.MachineObservationCapture;
import com.Goderium.BridgeNH.common.machine.MachineSlotBinding;
import com.Goderium.BridgeNH.common.qa.HorizonQaAdapter;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaAdapter;
import com.Goderium.BridgeNH.common.qa.RemoteQaAdapter;
import com.Goderium.BridgeNH.common.recipe.RecipeQueryAdapter;
import com.Goderium.BridgeNH.common.recipe.ResearchDataAdapter;
import com.Goderium.BridgeNH.common.structure.BogoCreativeInput;
import com.Goderium.BridgeNH.common.structure.JumpPlacement;
import com.Goderium.BridgeNH.common.structure.NativeGtWrench;
import com.Goderium.BridgeNH.common.structure.NativePartPlacement;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacement;
import com.Goderium.BridgeNH.common.structure.PlacementAfterObservation;
import com.Goderium.BridgeNH.common.structure.PlacementAttachment;
import com.Goderium.BridgeNH.common.structure.PlacementFacing;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.common.world.StateDiffAdapter;
import com.Goderium.BridgeNH.common.world.WorldInspectionAdapter;
import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.FlightPolicy;
import com.Goderium.BridgeNH.config.OperationPolicies;
import com.Goderium.BridgeNH.config.OperationPolicyRules;
import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.config.TeleportPolicy;
import com.Goderium.BridgeNH.loader.ClientBootstrap;
import com.Goderium.BridgeNH.network.RemoteGuiWait;
import com.Goderium.BridgeNH.network.RemoteReadClient;
import com.Goderium.BridgeNH.network.RemoteReadMailbox;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.network.RemoteReadServer;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;
import com.Goderium.BridgeNH.util.JvmMemoryDiagnostics;
import com.Goderium.BridgeNH.util.LoadedModEvidence;
import com.Goderium.BridgeNH.util.Tags;
import com.Goderium.BridgeNH.util.TickSamples;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCommandBlock;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class GameBridge {
    private final GameQueue clientQueue = new GameQueue(32);
    private final GameQueue serverQueue = new GameQueue(32);
    private final GameQueue renderQueue = new GameQueue(4);
    private final NativeObservationWait nativeWaits = new NativeObservationWait();
    private final java.util.List<ApproachPlanner.EyeRead> approachEyeReads = new java.util.ArrayList<ApproachPlanner.EyeRead>();
    private final String sessionId = UUID.randomUUID().toString();
    private final PlayerActivityRecorder activity = new PlayerActivityRecorder(sessionId, this::identifyScreen);
    private volatile String profile = "player";
    final OperationPolicies policies = new OperationPolicies();
    private volatile ServerContext serverContext;
    private volatile boolean remoteConnection;
    private volatile ServerIdentity serverIdentity;
    private volatile FlightObservation serverFlight;
    private World previousWorld;
    private GuiScreen buttonScreen;
    private List<?> buttons;
    private GuiScreen identifiedScreen;
    private String screenId = "";
    private final Map<String, KeyBinding> held = new LinkedHashMap<String, KeyBinding>();
    private final Map<String, Integer> heldKeyCodes = new LinkedHashMap<String, Integer>();
    private final InputDiagnostics inputDiagnostics = new InputDiagnostics();
    private final NeiBookmarkAdapter neiBookmarks = new NeiBookmarkAdapter();
    private int remainingInputTicks;
    private long inputDeadline;
    private long clientTicks;
    private final ClientMessages messages = new ClientMessages();
    private final ClientCatalog catalog = new ClientCatalog();
    private final TickSamples serverSamples = new TickSamples();
    private IntegratedServer tickServer;
    private long serverTickStart;
    private long frameWindowStart;
    private long lastFrameSample;
    private int frameCount;
    private double measuredFps;
    private final LifecycleTasks lifecycle = new LifecycleTasks();
    private final StartupScreenRecovery startupRecovery = new StartupScreenRecovery();
    private boolean loadingFrameActive;
    private final BackgroundControl background = new BackgroundControl();
    private final ScanSettings scanSettings;
    private final WorldScan clientScans = new WorldScan();
    private final WorldScan serverScans = new WorldScan();
    private final StructureAdapter structures = new StructureAdapter();
    private final StateDiffAdapter stateDiff = new StateDiffAdapter();
    private final HorizonQaAdapter qa = new HorizonQaAdapter();
    private final RecipeQueryAdapter recipes = new RecipeQueryAdapter();
    private final RecipeQueryAdapter clientRecipes = new RecipeQueryAdapter();
    private final MachineObservationCapture machineCaptures = new MachineObservationCapture();
    private final AeOrderAdapter aeOrders = new AeOrderAdapter();
    private volatile long scanEpoch;
    // Server-thread only. Retain one actual actor cooldown across RPCs, never a world/inventory cache.
    private Object survivalCooldownServer;
    private String survivalCooldownPlayer;
    private long survivalCallbackCompleted;
    private long serverControlSequence;
    private volatile PlayerNavigation.State navigation;
    private String inputTaskId;
    private final FlightCapabilityEvidence flightCapability = new FlightCapabilityEvidence();
    private final GuiObservationWait guiWaits = new GuiObservationWait();
    private final GuiObservationWait navigationStartWaits = new GuiObservationWait();
    private volatile GuiObservationWait.ContainerEvidence guiServerContainer;
    private volatile RuntimeException guiServerContainerFailure;
    private volatile GuiObservationWait.ContainerEvidence guiResyncRequest;
    private volatile boolean guiResyncSubmitted;

    public GameBridge() { this(new ScanSettings()); }

    /** Bootstrap and native input callbacks retain ownership of the recorder here. */
    public void registerActivityListeners() {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(activity);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(activity);
    }

    public void recordActivityCommand(String text) { activity.command(text, "native_gui_chat"); }
    public void recordActivityMouse(GuiScreen screen) { activity.mouse(screen); }
    public void recordActivityKey(GuiScreen screen) { activity.keyboard(screen); }
    public void recordActivitySlot(int window, int slot, int button, int mode) { activity.slot(window, slot, button, mode); }
    public JsonObject localRecording(JsonObject args) { return activity.control(args); }
    public void shutdownActivity() { activity.shutdown(); }

    public GameBridge(ScanSettings scanSettings) {
        this.scanSettings = scanSettings == null ? new ScanSettings() : scanSettings;
        WorldObservationsAdapter.configureCache(this.scanSettings);
    }

    public String runtimeSessionId() { return sessionId; }

    public void initializePolicies(java.nio.file.Path path) {
        try { policies.initialize(path); }
        catch (BridgeException failure) { /* Health and reload remain usable; invalid config fails closed. */ }
    }

    /** HTTP and local commands share this owning-thread policy endpoint. */
    public JsonObject policy(JsonObject args) {
        ExpandedGuiApi.keys(args, "action", "scope", "mode", "movement", "readOnly", "terrainChanges", "qa");
        String action = ExpandedGuiApi.option(args, "action", "status", "status", "set", "reload");
        if (!"set".equals(action) && args.entrySet().size() > (args.has("action") ? 1 : 0))
            throw new BridgeException("invalid_args", "Only set accepts policy options.");
        refreshServerContext(Minecraft.getMinecraft());
        if (!"status".equals(action)) {
            releaseInput(); nativeWaits.invalidateAll("Operation policy changed."); aeOrders.invalidateWaits();
            if ("reload".equals(action)) policies.reload(); else policies.update(args);
            invalidateScans();
        }
        return policyStatus(Minecraft.getMinecraft());
    }

    private boolean canTeleport(Minecraft client) {
        FlightObservation sample = serverFlight;
        return client.thePlayer != null && serverContext != null && sample != null && sample.server == serverContext.server
                && sample.playerId.equals(client.thePlayer.getUniqueID()) && sample.dimension == client.thePlayer.dimension
                && System.nanoTime() - sample.capturedAt < TimeUnit.SECONDS.toNanos(1) && sample.canTeleport;
    }

    private JsonObject policyStatus(Minecraft client) {
        JsonObject result = policies.inspect(), effective = new JsonObject();
        boolean authority = serverContext != null || RemoteReadClient.INSTANCE.operatorReady();
        boolean creative = client.thePlayer != null && client.thePlayer.capabilities.isCreativeMode;
        boolean tp = canTeleport(client), fly = client.thePlayer != null && client.thePlayer.capabilities.allowFlying;
        String chosen = policies.selected().get("movement").getAsString();
        effective.addProperty("machineRead", policies.permits("machineRead") && authority);
        effective.addProperty("supply", policies.permits("supply") && (creative || tp));
        effective.addProperty("direct", policies.permits("direct") && (serverContext != null && (creative || tp) || RemoteReadClient.INSTANCE.operatorReady()));
        effective.addProperty("navigationExecutionSupported", serverContext != null);
        effective.addProperty("teleportExecutionSupported", serverContext != null);
        effective.addProperty("canTeleport", tp); effective.addProperty("canFly", fly);
        effective.addProperty("movement", "tp".equals(chosen) && tp ? "tp" : "path");
        boolean flying = fly && client.thePlayer.capabilities.isFlying;
        effective.addProperty("flying", flying);
        effective.addProperty("movementImplementation", OperationPolicyRules.execution(chosen, flying, tp));
        effective.addProperty("movementReason", "tp".equals(chosen) ? tp ? "current_tp_permission" : "tp_unavailable_using_path" : flying ? "current_flying_state" : "current_ground_movement");
        effective.addProperty("automaticTerrainChanges", false);
        effective.add("qa", qaPolicyStatus(client));
        result.add("effective", effective); return result;
    }

    private JsonObject qaPolicyStatus(Minecraft client) {
        JsonObject value = QaPolicy.context(policies);
        boolean authority = serverContext != null || RemoteReadClient.INSTANCE.operatorReady();
        boolean worldReady = client.theWorld != null && client.thePlayer != null;
        boolean installationKnown = !remoteConnection || RemoteReadClient.INSTANCE.worldIdentity() != null;
        boolean installed = remoteConnection ? RemoteReadClient.INSTANCE.qaInstalled() : cpw.mods.fml.common.Loader.isModLoaded("horizonqa");
        boolean ready = worldReady && (serverContext != null || remoteConnection && installationKnown);
        boolean actualMutation = authority && (client.thePlayer != null && client.thePlayer.capabilities.isCreativeMode
                || canTeleport(client) || RemoteReadClient.INSTANCE.operatorReady());
        int rank = QaPolicy.rank(policies.qaLevel());
        value.addProperty("selected", policies.selected().get("qa").getAsString());
        value.addProperty("installed", installed); value.addProperty("discover", true);
        value.addProperty("installationKnown", installationKnown);
        value.addProperty("observe", installed && ready && authority && rank >= 1);
        value.addProperty("test", installed && ready && actualMutation && rank >= 2 && !policies.selected().get("readOnly").getAsBoolean());
        value.addProperty("manage", installed && ready && actualMutation && rank >= 3 && !policies.selected().get("readOnly").getAsBoolean());
        value.addProperty("reason", !worldReady ? "world_unavailable" : !installationKnown ? "remote_unavailable" : !installed ? "mod_not_loaded" : !ready ? "world_unavailable" : !authority ? "permission_denied" : rank == 0 ? "policy_denied" : policies.selected().get("readOnly").getAsBoolean() ? "read_only" : "");
        value.addProperty("executionRequiresReviewedCase", rank < 3);
        value.addProperty("runnerSupportMustBeDiscovered", true);
        return value;
    }

    private JsonObject qaDiscoveryFallback(JsonObject args) {
        Minecraft client = Minecraft.getMinecraft(); refreshServerContext(client);
        if (args.has("expectedSessionId") && !sessionId.equals(JsonData.string(args, "expectedSessionId")))
            throw new BridgeException("stale_session", "QA discovery client runtime changed.");
        if (args.has("expectedConnectionId")) RemoteReadClient.INSTANCE.requireCurrent(JsonData.string(args, "expectedConnectionId"));
        JsonObject policy = qaPolicyStatus(client);
        if (policy.get("observe").getAsBoolean()) return null;
        JsonObject value = new JsonObject(); value.addProperty("available", false);
        value.addProperty("section", "capabilities"); value.addProperty("reason", policy.get("reason").getAsString());
        value.addProperty("installed", policy.get("installed").getAsBoolean()); value.addProperty("sessionId", sessionId);
        value.addProperty("source", "mcb_capability_discovery"); value.add("policy", policy);
        value.addProperty("submitted", false); value.addProperty("sideEffectsPossible", false); value.addProperty("outcomeUnknown", false);
        return value;
    }

    /** Executed before dispatch for EVERY authenticated native call, including composed-tool primitives. */
    public JsonObject prepareOperation(String operation, JsonObject supplied, GameQueue.Control control) throws ExecutionException, InterruptedException {
        return clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); refreshServerContext(client);
            JsonObject args = supplied.deepCopy();
            boolean creative = client.thePlayer != null && client.thePlayer.capabilities.isCreativeMode;
            OperationPolicyRules.check(operation, args, policies, serverContext != null || RemoteReadClient.INSTANCE.operatorReady(), creative, canTeleport(client) || RemoteReadClient.INSTANCE.operatorReady());
            if (("gui.key".equals(operation) || "gui.click".equals(operation)) && client.currentScreen != null
                    && (client.currentScreen instanceof net.minecraft.client.gui.GuiChat || client.currentScreen instanceof GuiCommandBlock))
                OperationPolicyRules.require(policies.permits("direct") && !policies.selected().get("readOnly").getAsBoolean(), "Chat/command GUI input requires full mutable policy.");
            if (PlayerNavigation.OPERATION.equals(operation) || "player.plan_approach".equals(operation)) {
                String key = PlayerNavigation.OPERATION.equals(operation) ? "strategy" : "movementStrategy";
                String chosen = policies.selected().get("movement").getAsString();
                String requested = args.has(key) ? args.get(key).getAsString() : chosen;
                if ("path".equals(requested) || "tp".equals(requested)) {
                    OperationPolicyRules.require(requested.equals(chosen), "Change persistent movement with bridge.policy before requesting a different movement policy.");
                    requested = OperationPolicyRules.execution(chosen, client.thePlayer != null && client.thePlayer.capabilities.allowFlying && client.thePlayer.capabilities.isFlying, canTeleport(client));
                    if (args.has("goal") && "interaction".equals(JsonData.string(args.getAsJsonObject("goal"), "type"))) requested = "walking";
                    args.addProperty(key, requested);
                }
                if ("developer_teleport".equals(requested)) OperationPolicyRules.require("tp".equals(chosen) && canTeleport(client), "TP policy requires current normal tp permission.");
                if ("ability_flight".equals(requested)) OperationPolicyRules.require(client.thePlayer != null && client.thePlayer.capabilities.allowFlying, "Flight requires current real ability.");
            }
            if (!OperationPolicyRules.control(operation) && !"chat.send".equals(operation)) {
                final long revision = policies.revision();
                control.guardExecution(() -> { if (policies.revision() != revision) throw new BridgeException("policy_changed", "Policy or world changed; queued operation stopped without replay."); });
            }
            return args;
        }, 8000, control), 8000);
    }

    /** This method is called by HTTP workers and does not touch any Minecraft object. */
    public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
        if (QaPolicy.discovery(operation, args)) {
            JsonObject discovery = clientQueue.await(clientQueue.submit(() -> qaDiscoveryFallback(args), 8000, control), 8000);
            if (discovery != null) return discovery;
        }
        if (args.has("waitFor")) {
            if (!"gui.inspect".equals(operation)) throw new BridgeException("invalid_args", "waitFor belongs only to gui.inspect.");
            throw new BridgeException("invalid_request", "Native GUI synchronization requires its exact developer AFTER context.");
        }
        if (args.has("nativeWait")) throw new BridgeException("invalid_request", "Native owner waiting requires its developer combined AFTER guard.");
        if ("recipes.query".equals(operation) && clientRecipeQuery(args))
            return clientQueue.await(clientQueue.submit(() -> clientRecipeCatalog(args), 8000, control), 8000);
        if (RemoteReadProtocol.OPERATION.equals(operation)) return dispatchRemoteRead(args,control);
        if (AgentDialogueMailbox.OPERATION.equals(operation)) return dispatchRemoteQa(operation,args,control);
        if (remoteConnection && (RemoteReadProtocol.QA.contains(operation)
                || RemoteReadProtocol.OBSERVATIONS.contains(operation) && "developer".equals(profile)))
            return dispatchRemoteQa(operation,args,control);
        if (SchematicGoal.OPERATION.equals(operation)) return dispatchSchematic(args,control);
        if ("dev.teleport".equals(operation)) return dispatchTeleport(args, control);
        if ("player.flight_check".equals(operation)) return dispatchFlightCheck(args, control);
        if (PlayerNavigation.OPERATION.equals(operation)) {
            PlayerNavigation.Request request = new PlayerNavigation.Request(args); JsonObject expected = new JsonObject();
            expected.addProperty("version", 1); expected.addProperty("expectedSessionId", request.session); expected.addProperty("expectedProfile", "developer");
            expected.addProperty("expectedWorld", request.world); expected.addProperty("requireBackground", true);
            return dispatchNavigation(request, control, GuardedContext.parse(operation, expected)).data;
        }
        if (WholeStackTransfer.OPERATION.equals(operation)) throw new BridgeException("invalid_request", "Whole-stack transfer requires its developer BEFORE context guard.");
        if (OwnedBlockReplacement.OPERATION.equals(operation)) throw new BridgeException("invalid_request", "Owned native replacement requires its developer context guard.");
        if (SurvivalConstructionAdapter.OPERATION.equals(operation)) throw new BridgeException("invalid_request", "Native survival construction requires its developer context guard.");
        if (AeSurvivalSource.INSPECT_OPERATION.equals(operation)) throw new BridgeException("invalid_request", "AE source inspection requires its developer AFTER context guard.");
        InspectManyPlayerInventory.request(operation, args, false, "developer".equals(profile));
        if ("qa.status".equals(operation) && PrivateDimensionQaAdapter.waitMillis(args) > 0)
            return dispatchQaStatus(args, control);
        if (AeOrderAdapter.OPERATION.equals(operation) && AeOrderAdapter.waitMillis(args) > 0)
            return dispatchAeOrderStatus(args, control);
        if ("player.interact".equals(operation) && GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false))
            throw new BridgeException("invalid_request", "Airborne survival use requires its unchanged developer action context guard.");
        if ("player.plan_approach".equals(operation)) return approach(args, control, null).data;
        if ("client.screenshot".equals(operation)) {
            return renderQueue.await(renderQueue.submit(() -> FramebufferCapture.capture(Minecraft.getMinecraft()), 8000, control), 8000);
        }
        if ("structure.goal".equals(operation) || "world.tiles".equals(operation) || "world.structure".equals(operation) || "machine.inspect".equals(operation) || MachineObservationCapture.OPERATION.equals(operation) || "recipes.query".equals(operation)
                || AeOrderAdapter.OPERATION.equals(operation) || "machine.set_working".equals(operation) || "dev.teleport".equals(operation) || "inventory.supply".equals(operation) || operation.startsWith("qa.") || BaublesInventoryAdapter.serverRead(operation, args)) {
            requireUnpausedServer();
            return serverQueue.await(serverQueue.submit(() -> developerOperation(operation, args), 8000, control), 8000);
        }
        if (("world.inspect".equals(operation) || "world.scan".equals(operation) || "world.inspect_many".equals(operation)
                || "world.entities".equals(operation) || "world.spatial".equals(operation)) && "developer".equals(profile) && policies.permits("machineRead")) {
            requireUnpausedServer();
            return serverQueue.await(serverQueue.submit(() -> worldOperation(operation, args, true, control), 8000, control), 8000);
        }
        return clientQueue.await(clientQueue.submit(() -> clientOperation(operation, args, control), 8000, control), 8000);
    }

    private JsonObject dispatchRemoteQa(String operation,JsonObject args,GameQueue.Control control) throws ExecutionException,InterruptedException {
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(8000);
        final Object[] binding=new Object[2];
        RemoteReadMailbox.Pending pending=clientQueue.await(clientQueue.submit(() -> {
            Minecraft client=Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
            if(!"developer".equals(profile) || !RemoteReadClient.INSTANCE.operatorReady()) throw new BridgeException("permission_denied","Remote QA/authoritative observations require developer profile and server operator level 2.");
            if (RemoteReadProtocol.OBSERVATIONS.contains(operation) && !policies.permits("machineRead"))
                throw new BridgeException("permission_denied","Remote owner observations require the selected enhanced observation policy.");
            if(args.has("expectedSessionId") && !sessionId.equals(JsonData.string(args,"expectedSessionId"))) throw new BridgeException("stale_session","QA client runtime changed.");
            String connection=RemoteReadClient.INSTANCE.connectionId();
            if(args.has("expectedConnectionId") && !connection.equals(JsonData.string(args,"expectedConnectionId"))) throw new BridgeException("stale_world","QA server connection changed.");
            JsonObject submitted=new JsonParser().parse(args.toString()).getAsJsonObject();
            submitted.remove("expectedSessionId"); submitted.remove("expectedConnectionId");
            JsonObject envelope=submitted;
            if(RemoteReadProtocol.QA.contains(operation)) {
                envelope=new JsonObject(); envelope.addProperty("clientSessionId",sessionId); envelope.add("arguments",submitted);
                envelope.add("qaPolicy", QaPolicy.context(policies));
            }
            binding[0]=connection; binding[1]=client.thePlayer.dimension;
            return RemoteReadClient.INSTANCE.begin(operation,envelope,connection);
        },8000,control),8000);
        Runnable cancel=() -> RemoteReadClient.INSTANCE.mailbox.cancel(pending);
        boolean action=RemoteQaAdapter.action(operation) || AgentDialogueMailbox.OPERATION.equals(operation);
        if(!action) control.onReadCancellation(cancel);
        try {
            JsonObject response; long started=System.nanoTime();
            try { response=pending.future.get(Math.min(6500,observationRemaining(deadline)),TimeUnit.MILLISECONDS); }
            catch(java.util.concurrent.TimeoutException failure) { throw new BridgeException(action ? "qa_outcome_unknown" : "timeout",action ? "QA reply timed out after submission. Inspect the run; never replay automatically." : "Remote observation timed out."); }
            finally { control.recordNativeAwait(System.nanoTime()-started); }
            final JsonObject delivered=response; long remaining=observationRemaining(deadline);
            return clientQueue.await(clientQueue.submit(() -> {
                Minecraft client=Minecraft.getMinecraft(); requirePlayer(client);
                if(client.thePlayer.dimension!=(Integer)binding[1] || !"developer".equals(profile)) throw new BridgeException("qa_context_changed","QA player dimension or profile changed.");
                JsonObject data=RemoteReadClient.INSTANCE.accept(pending,delivered,(String)binding[0]);
                data.addProperty("sessionId",sessionId);
                if (AgentDialogueMailbox.OPERATION.equals(operation)) data.addProperty("connectionId", (String)binding[0]);
                return data;
            },remaining,control),remaining);
        } finally { if(!action) control.clearReadCancellation(cancel); cancel.run(); }
    }
    private void requireObservationClient(JsonObject args) {
        Minecraft client=Minecraft.getMinecraft(); requirePlayer(client);
        if(!sessionId.equals(CatalogData.text(args,"expectedSessionId",128,""))) throw new BridgeException("stale_world","Client runtime session changed.");
        if(client.thePlayer.dimension!=JsonData.integer(args,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE)) throw new BridgeException("stale_world","Client dimension changed.");
    }
    private JsonObject dispatchRemoteRead(JsonObject args,GameQueue.Control control) throws ExecutionException,InterruptedException {
        RemoteReadProtocol.keys(args,"kind","expectedSessionId","dimension","expectedConnectionId","positions","includeInventory","x","y","z","componentOffset","componentLimit");
        String kind=CatalogData.text(args,"kind",16,"status");
        if("status".equals(kind)) return clientQueue.await(clientQueue.submit(() -> {
            if(!sessionId.equals(CatalogData.text(args,"expectedSessionId",128,""))) throw new BridgeException("stale_world","Client runtime session changed.");
            JsonObject data=RemoteReadClient.INSTANCE.status(); data.addProperty("sessionId",sessionId); return data;
        },8000,control),8000);
        if(!RemoteReadProtocol.READS.contains(kind) || "hello".equals(kind)) throw new BridgeException("invalid_args","Expected status/self/blocks/machine.");
        JsonObject read=new JsonObject();
        String[] keys="self".equals(kind) ? new String[]{"includeInventory"} : "blocks".equals(kind) ? new String[]{"positions"} : new String[]{"x","y","z","componentOffset","componentLimit"};
        for(String key:keys) if(args.has(key)) read.add(key,args.get(key));
        if("blocks".equals(kind)) RemoteReadProtocol.positions(read);
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(8000);
        return remoteRead(kind,read,CatalogData.text(args,"expectedConnectionId",128,""),args,control,deadline);
    }
    private long observationRemaining(long deadline) {
        long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if(millis<=0) throw new BridgeException("timeout","Read/comparison budget expired; no mutation submitted."); return millis;
    }
    private JsonObject remoteRead(String kind,JsonObject read,String connection,JsonObject args,GameQueue.Control control,long deadline) throws ExecutionException,InterruptedException {
        return remoteReadBatch(kind,java.util.Collections.singletonList(read),connection,args,control,deadline).get(0);
    }
    /** One client submit/accept per four-page window. Only HTTP workers await remote futures. */
    private java.util.List<JsonObject> remoteReadBatch(String kind,java.util.List<JsonObject> reads,String connection,JsonObject args,GameQueue.Control control,long deadline) throws ExecutionException,InterruptedException {
        if(reads.isEmpty() || reads.size()>RemoteReadMailbox.MAX_PENDING) throw new BridgeException("invalid_args","A remote read window accepts 1..4 pages.");
        long remaining=observationRemaining(deadline);
        java.util.List<RemoteReadMailbox.Pending> pending=clientQueue.await(clientQueue.submit(() -> {
            requireObservationClient(args); java.util.List<RemoteReadMailbox.Pending> values=new java.util.ArrayList<RemoteReadMailbox.Pending>();
            try { for(JsonObject read:reads) values.add(RemoteReadClient.INSTANCE.begin(kind,read,connection)); return values; }
            catch(RuntimeException failure) { for(RemoteReadMailbox.Pending value:values) RemoteReadClient.INSTANCE.mailbox.cancel(value); throw failure; }
        },remaining,control),remaining);
        Runnable cancellation=() -> { for(RemoteReadMailbox.Pending value:pending) RemoteReadClient.INSTANCE.mailbox.cancel(value); };
        control.onReadCancellation(cancellation);
        try {
            final java.util.List<JsonObject> responses=new java.util.ArrayList<JsonObject>(); long started=System.nanoTime();
            try { for(RemoteReadMailbox.Pending value:pending) responses.add(value.future.get(Math.min(6500,observationRemaining(deadline)),TimeUnit.MILLISECONDS)); }
            catch(java.util.concurrent.TimeoutException failure) { throw new BridgeException("timeout","Server read window timed out; no mutation submitted."); }
            finally { control.recordNativeAwait(System.nanoTime()-started); }
            remaining=observationRemaining(deadline);
            return clientQueue.await(clientQueue.submit(() -> {
                requireObservationClient(args); java.util.List<JsonObject> results=new java.util.ArrayList<JsonObject>();
                for(int i=0;i<pending.size();i++) { JsonObject data=RemoteReadClient.INSTANCE.accept(pending.get(i),responses.get(i),connection); data.addProperty("sessionId",sessionId); results.add(data); }
                return results;
            },remaining,control),remaining);
        } finally { control.clearReadCancellation(cancellation); cancellation.run(); }
    }
    private JsonObject dispatchSchematic(JsonObject args,GameQueue.Control control) throws ExecutionException,InterruptedException {
        final SchematicGoal.Comparison comparison=new SchematicGoal.Comparison(args);
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(8000);
        final Object[] identity=new Object[3];
        final JsonObject clientIdentity=clientQueue.await(clientQueue.submit(() -> {
            requireObservationClient(args); Minecraft client=Minecraft.getMinecraft();
            boolean local=client.isSingleplayer();
            if(local) { ServerContext context=requireDeveloperContext(); requireExpectedWorld(context,args); }
            JsonObject data=new JsonObject(); data.addProperty("local",local);
            data.addProperty("x",client.thePlayer.posX); data.addProperty("y",client.thePlayer.boundingBox.minY); data.addProperty("z",client.thePlayer.posZ);
            data.addProperty("creative",client.thePlayer.capabilities.isCreativeMode);
            if(!local) {
                String connection=CatalogData.text(args,"expectedConnectionId",128,""); RemoteReadClient.INSTANCE.requireCurrent(connection);
                if(!client.theWorld.getWorldInfo().getWorldName().equals(JsonData.string(args,"expectedWorld"))) throw new BridgeException("stale_world","Remote client world label changed.");
                data.addProperty("connectionId",connection);
            }
            identity[0]=client.thePlayer; identity[1]=client.theWorld; identity[2]=client.getNetHandler(); return data;
        },observationRemaining(deadline),control),observationRemaining(deadline));
        final java.util.Map<String,JsonObject> observations=new java.util.LinkedHashMap<String,JsonObject>();
        final Object[] serverIdentity=new Object[3];
        readSchematicPositions(comparison.observationPositions(),observations,args,control,deadline,clientIdentity,serverIdentity);
        while(true) {
            long remaining=observationRemaining(deadline);
            boolean classified=clientQueue.await(clientQueue.submit(() -> {
                requireObservationClient(args); Minecraft client=Minecraft.getMinecraft();
                if(identity[0]!=client.thePlayer || identity[1]!=client.theWorld || identity[2]!=client.getNetHandler()) throw new BridgeException("stale_world","Comparison client changed.");
                return comparison.classify((x,y,z)->observations.get(x+":"+y+":"+z),clientIdentity.get("creative").getAsBoolean(),System.nanoTime()+WalkingPathPlanner.WORK_NANOS);
            },remaining,control),remaining);
            if(classified) break;
        }
        JsonArray supports=new JsonArray();
        for(JsonElement value:comparison.supportPositions()) { JsonObject p=value.getAsJsonObject(); if(!observations.containsKey(p.get("x").getAsInt()+":"+p.get("y").getAsInt()+":"+p.get("z").getAsInt())) supports.add(p); }
        readSchematicPositions(supports,observations,args,control,deadline,clientIdentity,serverIdentity);
        while(true) {
            if(control.isCancelled()) throw new BridgeException("cancelled","Client comparison cancelled; no mutation submitted.");
            long remaining=observationRemaining(deadline);
            JsonObject result=clientQueue.await(clientQueue.submit(() -> {
                requireObservationClient(args); Minecraft client=Minecraft.getMinecraft();
                if(identity[0]!=client.thePlayer || identity[1]!=client.theWorld || identity[2]!=client.getNetHandler()) throw new BridgeException("stale_world","Client comparison context changed.");
                if(!clientIdentity.get("local").getAsBoolean()) RemoteReadClient.INSTANCE.requireCurrent(clientIdentity.get("connectionId").getAsString());
                if(!comparison.advance((x,y,z) -> observations.get(x+":"+y+":"+z),clientIdentity.get("x").getAsDouble(),clientIdentity.get("y").getAsDouble(),clientIdentity.get("z").getAsDouble(),
                        clientIdentity.get("creative").getAsBoolean(),System.nanoTime()+WalkingPathPlanner.WORK_NANOS)) return null;
                JsonObject response=comparison.finish(); response.addProperty("sessionId",sessionId); response.addProperty("dimension",client.thePlayer.dimension);
                response.addProperty("observationSource",clientIdentity.get("local").getAsBoolean() ? "integrated_server_tick" : "remote_server_tick");
                if(clientIdentity.has("connectionId")) response.add("connectionId",clientIdentity.get("connectionId")); return response;
            },remaining,control),remaining);
            if(result!=null) return result;
        }
    }


    private void readSchematicPositions(JsonArray positions,java.util.Map<String,JsonObject> observations,JsonObject args,GameQueue.Control control,long deadline,JsonObject clientIdentity,Object[] serverIdentity) throws ExecutionException,InterruptedException {
        int window=clientIdentity.get("local").getAsBoolean() ? 1 : RemoteReadMailbox.MAX_PENDING;
        for(int offset=0;offset<positions.size();offset+=RemoteReadProtocol.MAX_POSITIONS*window) {
            java.util.List<JsonObject> reads=new java.util.ArrayList<JsonObject>();
            for(int start=offset;start<Math.min(positions.size(),offset+RemoteReadProtocol.MAX_POSITIONS*window);start+=RemoteReadProtocol.MAX_POSITIONS) {
                JsonArray page=new JsonArray(); for(int i=start;i<Math.min(positions.size(),start+RemoteReadProtocol.MAX_POSITIONS);i++) page.add(positions.get(i));
                JsonObject read=new JsonObject(); read.add("positions",page); reads.add(read);
            }
            JsonArray page=reads.get(0).getAsJsonArray("positions");
            JsonObject data;
            if(clientIdentity.get("local").getAsBoolean()) {
                requireUnpausedServer();
                data=serverQueue.await(serverQueue.submit(() -> {
                    ServerContext context=requireDeveloperContext(); requireExpectedWorld(context,args);
                    EntityPlayerMP actor=(EntityPlayerMP)operationPlayer(true);
                    if(actor.isDead || actor.dimension!=args.get("dimension").getAsInt()) throw new BridgeException("stale_world","Local schematic actor/dimension changed.");
                    if(serverIdentity[0]==null) { serverIdentity[0]=actor; serverIdentity[1]=actor.worldObj; serverIdentity[2]=context.server; }
                    if(serverIdentity[0]!=actor || serverIdentity[1]!=actor.worldObj || serverIdentity[2]!=context.server) throw new BridgeException("stale_world","Local observation context changed.");
                    JsonObject result=new JsonObject(); JsonArray blocks=new JsonArray();
                    for(JsonElement value:page) { JsonObject p=value.getAsJsonObject(); blocks.add(RemoteReadServer.block(actor,p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt())); }
                    result.add("blocks",blocks); return result;
                },observationRemaining(deadline),control),observationRemaining(deadline));
            } else {
                data=new JsonObject(); JsonArray blocks=new JsonArray();
                for(JsonObject result:remoteReadBatch("blocks",reads,clientIdentity.get("connectionId").getAsString(),args,control,deadline))
                    for(JsonElement value:result.getAsJsonArray("blocks")) blocks.add(value);
                data.add("blocks",blocks);
            }
            for(JsonElement value:data.getAsJsonArray("blocks")) { JsonObject row=value.getAsJsonObject(); observations.put(row.get("x").getAsInt()+":"+row.get("y").getAsInt()+":"+row.get("z").getAsInt(),row); }
        }
    }
    private void requireUnpausedServer() {
        ServerContext context = serverContext;
        if (context != null && context.paused)
            throw new BridgeException("world_paused", "The local world is paused. Inspect the current GUI and resume it before server operations; no operation was queued.");
    }

    /** Reuse the native AE completion observer and the existing cancellable read/owner queues. */
    private JsonObject dispatchAeOrderStatus(JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
        final int requested = AeOrderAdapter.waitMillis(args);
        final long started = System.nanoTime(), deadline = started + TimeUnit.MILLISECONDS.toNanos(requested + 1500);
        final java.util.concurrent.atomic.AtomicReference<AeOrderAdapter.StatusWait> owned = new java.util.concurrent.atomic.AtomicReference<AeOrderAdapter.StatusWait>();
        Runnable cancellation = () -> { AeOrderAdapter.StatusWait wait = owned.get(); if (wait != null) wait.cancel(); };
        control.onReadCancellation(cancellation);
        try {
            requireUnpausedServer();
            AeStatusRead binding = serverQueue.await(serverQueue.submit(() -> {
                EntityPlayerMP player = aeOrderPlayer(args);
                ServerContext context = requireDeveloperContext();
                requireAeWaitContext(context, player);
                AeOrderAdapter.StatusWait wait = aeOrders.statusWait(player, args); owned.set(wait);
                if (control.isCancelled()) wait.cancel();
                return new AeStatusRead(context, player, scanEpoch, wait);
            }, observationRemaining(deadline), control), observationRemaining(deadline));
            long effective = Math.min(requested, Math.max(1, observationRemaining(deadline) - 1000));
            final boolean signaled;
            long awaited = System.nanoTime();
            try { signaled = binding.wait.await(effective); }
            finally { control.recordNativeAwait(System.nanoTime() - awaited); }
            return serverQueue.await(serverQueue.submit(() -> {
                EntityPlayerMP player = aeOrderPlayer(args);
                ServerContext context = requireDeveloperContext();
                requireAeWaitContext(context, player);
                if (player != binding.player || player.worldObj != binding.world || context.server != binding.server || scanEpoch != binding.epoch)
                    throw new BridgeException("ae_context_changed", "The bound AE order actor or loaded world changed during status waiting.");
                JsonObject current = aeOrders.perform(player, args);
                JsonObject receipt = new JsonObject(); receipt.addProperty("version", 1);
                receipt.addProperty("requestedMillis", requested); receipt.addProperty("effectiveMillis", effective);
                receipt.addProperty("signaled", signaled); receipt.addProperty("windowExpired", !signaled);
                receipt.addProperty("terminal", !"running".equals(current.get("state").getAsString()));
                receipt.addProperty("readOnly", true); receipt.addProperty("finalStateFresh", true);
                receipt.addProperty("elapsedMillis", Math.max(0, System.nanoTime() - started) / 1000000D);
                receipt.addProperty("source", "native_ae_completion_listener"); current.add("statusWait", receipt);
                observationRemaining(deadline);
                return current;
            }, observationRemaining(deadline), control), observationRemaining(deadline));
        } finally {
            AeOrderAdapter.StatusWait wait = owned.get(); if (wait != null) wait.close();
            control.clearReadCancellation(cancellation);
        }
    }

    private static void requireAeWaitContext(ServerContext context, EntityPlayerMP player) {
        if (context.paused || System.nanoTime() - context.capturedAt > 1000000000L || player.isDead
                || player.dimension != context.dimension || player.worldObj == null || player.worldObj.isRemote
                || player.worldObj.provider == null || player.worldObj.provider.dimensionId != player.dimension)
            throw new BridgeException("ae_context_changed", "AE status waiting requires a fresh unpaused authoritative player/world context.");
    }

    private static final class AeStatusRead {
        final EntityPlayerMP player;
        final World world;
        final IntegratedServer server;
        final long epoch;
        final AeOrderAdapter.StatusWait wait;
        AeStatusRead(ServerContext context, EntityPlayerMP player, long epoch, AeOrderAdapter.StatusWait wait) {
            this.player = player; world = player.worldObj; server = context.server; this.epoch = epoch; this.wait = wait;
        }
    }

    /** HTTP worker: server tickets register/check only; the normal game lifecycle keeps ticking during get(). */
    private JsonObject dispatchQaStatus(JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
        final int requested = PrivateDimensionQaAdapter.waitMillis(args);
        final String runId = CatalogData.text(args, "runId", 128, "");
        if (runId.isEmpty()) throw new BridgeException("invalid_args", "Status waiting requires one explicit run ID.");
        final long started = System.nanoTime(), deadline = started + TimeUnit.MILLISECONDS.toNanos(8000);
        requireUnpausedServer();
        QaStatusWait wait = serverQueue.await(serverQueue.submit(() -> {
            ServerContext context = requireDeveloperContext();
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (player.isDead) throw new BridgeException("world_unavailable", "The authoritative server player is unavailable.");
            // Native HQA retains its existing global status and ownership semantics without a completion wait.
            if (player.dimension == 0 && player.worldObj != null && player.worldObj.provider.dimensionId == 0)
                return new QaStatusWait(developerOperation("qa.status", args));
            requireQaWaitContext(context, player);
            return new QaStatusWait(context, player, scanEpoch, PrivateDimensionQaAdapter.completion(player, sessionId, runId));
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
        if (wait.fallback != null) return wait.fallback;
        // Leave one second inside the existing eight-second dispatch deadline for a fresh owning-thread guard/read.
        long effective = Math.min(requested, Math.max(0, qaWaitRemaining(deadline) - 1000));
        String terminal;
        long nativeAwaitStarted = System.nanoTime();
        try { terminal = PrivateDimensionQaAdapter.awaitCompletion(wait.completion, effective); }
        finally { control.recordNativeAwait(System.nanoTime() - nativeAwaitStarted); }
        if (terminal != null) PrivateDimensionQaAdapter.requireStatusRun(
                PrivateDimensionQaAdapter.response(terminal, sessionId, wait.dimension, wait.provider), runId, true);
        return serverQueue.await(serverQueue.submit(() -> {
            ServerContext context = requireDeveloperContext();
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            requireQaWaitContext(context, player);
            if (context.server != wait.server || player.worldObj != wait.world || player.dimension != wait.dimension
                    || !context.playerId.equals(wait.playerId) || scanEpoch != wait.epoch
                    || context.screenIdentity != wait.screen || !context.screenId.equals(wait.screenId))
                throw new BridgeException("qa_context_changed", "The actual QA player, world or client context changed during status waiting.");
            JsonObject current = developerOperation("qa.status", args);
            PrivateDimensionQaAdapter.requireStatusRun(current, runId, terminal != null);
            JsonObject receipt = new JsonObject(); receipt.addProperty("supported", true);
            receipt.addProperty("requestedMillis", requested); receipt.addProperty("effectiveMillis", effective);
            receipt.addProperty("completed", terminal != null); receipt.addProperty("timedOut", terminal == null);
            receipt.addProperty("elapsedMillis", Math.max(0, System.nanoTime() - started) / 1000000.0);
            receipt.addProperty("source", "normal_server_tick_completion"); current.add("statusWait", receipt);
            return current;
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
    }

    private void requireQaWaitContext(ServerContext context, EntityPlayerMP player) {
        if (context.paused || System.nanoTime() - context.capturedAt > 1000000000L || player.isDead
                || player.dimension != context.dimension || player.worldObj == null || player.worldObj.isRemote
                || player.worldObj.provider == null || player.worldObj.provider.dimensionId != player.dimension)
            throw new BridgeException("qa_context_changed", "QA status waiting requires a fresh unpaused authoritative player/world context.");
    }

    private static long qaWaitRemaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new BridgeException("timeout", "The bounded QA status-read deadline expired; the run is not cancelled.");
        return remaining;
    }

    private static final class QaStatusWait {
        final JsonObject fallback;
        final CompletableFuture<?> completion;
        final Object server, world, screen;
        final UUID playerId;
        final int dimension;
        final String provider, screenId;
        final long epoch;
        QaStatusWait(JsonObject fallback) {
            this.fallback = fallback; completion = null; server = world = screen = null; playerId = null;
            dimension = 0; provider = screenId = ""; epoch = 0;
        }
        QaStatusWait(ServerContext context, EntityPlayerMP player, long epoch, CompletableFuture<?> completion) {
            fallback = null; this.completion = completion; server = context.server; world = player.worldObj;
            screen = context.screenIdentity; playerId = context.playerId; dimension = player.dimension;
            provider = player.worldObj.provider.getClass().getName(); screenId = context.screenId; this.epoch = epoch;
        }
    }

    /** Context work always runs on its owning game thread; server reads keep their post-read client guard. */
    public GuardedContext.Result dispatchGuarded(String operation, JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
        if (!guard.operation.equals(operation)) throw new BridgeException("invalid_request", "Context operation identity changed.");
        if (args.has("waitFor")) {
            if (!"gui.inspect".equals(operation) || guard.action) throw new BridgeException("invalid_args", "waitFor belongs only to a GUI AFTER read.");
            return dispatchGuiWait(args, control, guard);
        }
        NativeObservationWait.requireOperation(operation, args);
        if (PlayerNavigation.OPERATION.equals(operation)) { guard.requireNavigation(); return dispatchNavigation(new PlayerNavigation.Request(args), control, guard); }
        if (WholeStackTransfer.OPERATION.equals(operation)) {
            guard.requireWholeStackTransfer(); WholeStackTransfer.Request request = new WholeStackTransfer.Request(args);
            return WholeStackTransfer.dispatch(clientQueue, serverQueue, control, request, wholeStackAccess(request, guard), guard);
        }
        if (OwnedBlockReplacement.OPERATION.equals(operation)) {
            guard.requireOwnedReplacement(); OwnedBlockReplacement.Request request = new OwnedBlockReplacement.Request(args);
            return OwnedBlockReplacement.dispatch(clientQueue, serverQueue, control, new ReplacementStages(request, guard));
        }
        if (SurvivalConstructionAdapter.OPERATION.equals(operation)) {
            guard.requireSurvivalOperation(); SurvivalConstructionAdapter.Request request = new SurvivalConstructionAdapter.Request(args);
            return SurvivalConstructionAdapter.dispatch(clientQueue, serverQueue, control, request, new SurvivalBuildStages(request, guard));
        }
        if (AeSurvivalSource.INSPECT_OPERATION.equals(operation)) {
            guard.requireAeMaterialSourceRead(); return dispatchAeMaterialSource(new AeSurvivalSource.InspectionRequest(args), guard, control);
        }
        if ("player.place_block".equals(operation)) {
            if (GuiDataAdapter.booleanArg(args, "jumpUnderfoot", false)) {
                JumpPlacementStages stages = new JumpPlacementStages(args, guard);
                try { return SurvivalConstructionAdapter.dispatchPlacement(clientQueue, serverQueue, control, stages); }
                finally {
                    try { clientQueue.await(clientQueue.submit(() -> { stages.release(); return null; }, 250), 250); }
                    catch (ExecutionException | InterruptedException | BridgeException cleanup) {
                        if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                        // The existing six-tick input owner still releases on its native deadline/context change.
                    }
                }
            }
            return SurvivalConstructionAdapter.dispatchPlacement(clientQueue, serverQueue, control, new SurvivalPlacementStages(args, guard));
        }
        InspectManyPlayerInventory.Request inventoryRequest = InspectManyPlayerInventory.request(operation, args, true, "developer".equals(profile));
        if (inventoryRequest != null) guard.requirePlayerInventoryRead();
        if (args.has("nativeWait")) {
            guard.requirePlayerInventoryRead();
            return dispatchNativeObservation(args, control, guard, inventoryRequest);
        }
        if ("player.interact".equals(operation) && GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false))
            return dispatchSurvivalFlightUse(args, control, guard);
        if ("player.plan_approach".equals(operation)) return approach(args, control, guard);
        boolean serverRead = !guard.action && (SchematicGoal.OPERATION.equals(operation) || "world.structure".equals(operation) || "machine.inspect".equals(operation) || BaublesInventoryAdapter.serverRead(operation, args)
                || ("developer".equals(profile) && policies.permits("machineRead") && ("world.inspect".equals(operation) || "world.inspect_many".equals(operation) || "world.spatial".equals(operation))));
        if (serverRead) {
            if (remoteConnection && (inventoryRequest != null || !RemoteReadProtocol.OBSERVATIONS.contains(operation)))
                throw new BridgeException("unsupported", "This combined owner read has no remote implementation; no local server work was queued.");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(8000);
            JsonObject data;
            if (inventoryRequest == null) data = dispatch(operation, args, control);
            else {
                requireUnpausedServer();
                data = serverQueue.await(serverQueue.submit(() -> worldOperation(operation, args, true, control), 8000, control), 8000);
            }
            long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new BridgeException("timeout", "The bounded server read completed without time for its fresh client context.");
            return clientQueue.await(clientQueue.submit(() -> {
                if (inventoryRequest != null) {
                    Minecraft client = Minecraft.getMinecraft();
                    return InspectManyPlayerInventory.after(inventoryRequest, data, () -> inspectManyPlayerInventory(client, inventoryRequest.allowPendingZero), () -> {
                        JsonObject current = snapshot(client);
                        if (client.thePlayer != null && current.has("player")) current.getAsJsonObject("player").addProperty("playerId", client.thePlayer.getUniqueID().toString());
                        return current;
                    }, guard);
                }
                Minecraft client = Minecraft.getMinecraft();
                JsonObject context = snapshot(client); guard.check(context);
                if ("remote_server_tick".equals(PlayerNavigation.text(data, "source"))) {
                    RemoteReadClient.INSTANCE.requireCurrent(JsonData.string(data, "connectionId"));
                    if (client.thePlayer == null || client.thePlayer.dimension != JsonData.integer(data, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE))
                        throw new BridgeException("stale_world", "Remote owner dimension changed before its AFTER guard.");
                }
                if ("world.structure".equals(operation) && args.has("candidatePositions")) {
                    data.add("candidateObservation", structures.blueprints().candidates(client, sessionId, args, data));
                    context = snapshot(client); guard.check(context);
                }
                return guard.result(data, context);
            }, remaining, control), remaining);
        }
        return clientQueue.await(clientQueue.submit(() -> guard.execute(() -> snapshot(Minecraft.getMinecraft()),
                () -> clientOperation(operation, args, control)), 8000, control), 8000);
    }

    private GuardedContext.Result dispatchGuiWait(JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
        ExpandedGuiApi.keys(args, "waitFor");
        if (!args.get("waitFor").isJsonObject()) throw new BridgeException("invalid_args", "waitFor must be a bounded object.");
        final GuiObservationWait.Request request = new GuiObservationWait.Request(args.getAsJsonObject("waitFor"));
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        GuiObservationWait.Job job = clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
            if (remoteConnection && (!"developer".equals(profile) || !policies.permits("machineRead")))
                throw new BridgeException("permission_denied","Remote GUI synchronization requires enhanced developer observation.");
            final RemoteGuiWait remote = remoteConnection ? new RemoteGuiWait() : null;
            if (remote == null) requireDeveloperContext();
            guard.check(snapshot(client));
            final Object world = client.theWorld, player = client.thePlayer;
            final int dimension = client.thePlayer.dimension;
            guiServerContainer = null; guiServerContainerFailure = null;
            guiResyncRequest = null; guiResyncSubmitted = false;
            return guiWaits.register(request, new GuiObservationWait.Access() {
                private GuiScreen boundScreen;
                private int boundWindow = -1;
                private JsonObject diagnostic = new JsonObject();
                public JsonObject diagnostic() { return diagnostic; }
                public void close() { if (remote != null) remote.cancel(); }
                public void check() {
                    guard.check(snapshot(client));
                    if (remote != null) remote.check();
                    if (client.theWorld != world || client.thePlayer != player || client.thePlayer.dimension != dimension
                            || client.thePlayer.isDead || client.isGamePaused())
                        throw new BridgeException("gui_context_changed", "The native GUI observer's living actor/world/dimension changed.");
                    if (boundScreen != null && (client.currentScreen != boundScreen || client.thePlayer.openContainer == null
                            || client.thePlayer.openContainer.windowId != boundWindow))
                        throw new BridgeException("stale_window", "The observed GUI/window changed while awaiting declared content.");
                }
                public JsonObject read() {
                    diagnostic = new JsonObject(); diagnostic.addProperty("screenObserved", client.currentScreen instanceof GuiContainer);
                    diagnostic.addProperty("screenClass", client.currentScreen == null ? "" : client.currentScreen.getClass().getName());
                    if (!(client.currentScreen instanceof GuiContainer)) return null;
                    Container container = ((GuiContainer) client.currentScreen).inventorySlots;
                    // Survival inventory is a real synchronized window (id 0), too.
                    if (container == null || client.thePlayer.openContainer != container) return null;
                    if (boundScreen == null) { boundScreen = client.currentScreen; boundWindow = container.windowId; }
                    if (guiServerContainerFailure != null) throw guiServerContainerFailure;
                    GuiObservationWait.ContainerEvidence server = guiServerContainer;
                    GuiObservationWait.ContainerEvidence local = new GuiObservationWait.ContainerEvidence(container, System.nanoTime());
                    if (remote != null) {
                        JsonObject synchronization=remote.read(local,diagnostic);
                        if (synchronization==null) return null;
                        JsonObject result=gui(client); result.add("containerSynchronization",synchronization); return result;
                    }
                    diagnostic.add("container", local.diagnostic(server));
                    if (server == null || System.nanoTime() - server.captured > TimeUnit.MILLISECONDS.toNanos(1000)) {
                        diagnostic.addProperty("reason", server == null ? "server_sample_pending" : "server_sample_stale"); return null;
                    }
                    diagnostic.addProperty("nativeResyncSubmitted",guiResyncSubmitted);
                    int displayInitializations = local.displayInitializationCount(server);
                    diagnostic.addProperty("phantomDisplayInitializationChecked",true);
                    diagnostic.addProperty("phantomDisplayInitializedCount",Math.max(0,displayInitializations));
                    if (displayInitializations<0) {
                        if (local.window==server.window && local.className.equals(server.className) && local.slots.length==server.slots.length
                                && java.util.Arrays.equals(local.ghostKeys,server.ghostKeys) && java.util.Arrays.equals(local.fluidKeys,server.fluidKeys)
                                && !guiResyncSubmitted) guiResyncRequest=server;
                        return null;
                    }
                    JsonObject result = gui(client);
                    result.add("containerSynchronization",local.synchronization("client_and_integrated_server_owner_threads",System.nanoTime()-server.captured,guiResyncSubmitted,displayInitializations)); return result;
                }
            });
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
        Runnable cancellation = job::cancel; control.onReadCancellation(cancellation);
        try {
            while (true) {
                long began = System.nanoTime(); final JsonObject data;
                try { data = job.await(qaWaitRemaining(deadline)); }
                finally { control.recordNativeAwait(System.nanoTime() - began); }
                GuardedContext.Result accepted = clientQueue.await(clientQueue.submit(() -> {
                    Minecraft client = Minecraft.getMinecraft();
                    JsonObject result = job.verifyFinal(data);
                    if (result == null) return null;
                    JsonObject after = snapshot(client); guard.check(after); return guard.result(result, after);
                }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
                if (accepted != null) return accepted;
            }
        } finally {
            job.cancel(); control.clearReadCancellation(cancellation); guiServerContainer = null; guiServerContainerFailure = null;
            guiResyncRequest = null;
        }
    }

    /** Opt-in native use preflight on both owners; normal action/context handling remains unchanged. */
    private GuardedContext.Result dispatchSurvivalFlightUse(JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
        if (!"use".equals(JsonData.string(args, "action")) || !args.has("x") || !args.has("y") || !args.has("z"))
            throw new BridgeException("invalid_args", "Airborne survival interaction requires one explicit normal use target.");
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        final SurvivalFlightUseBinding binding = clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client); refreshServerContext(client);
            JsonObject current = snapshot(client); guard.check(current); ServerContext context = requireDeveloperContext();
            SurvivalFlightPosture.actor(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode(), client.thePlayer.capabilities.allowFlying, client.thePlayer.capabilities.isFlying);
            SurvivalFlightPosture.snapshot(current, sessionId, client.thePlayer.getUniqueID().toString(), context.server.getWorldName(), client.thePlayer.dimension);
            return new SurvivalFlightUseBinding(context, client, current.getAsJsonObject("player"),
                    survivalFlightUseProof(client.thePlayer, client.playerController.getBlockReachDistance(), args, deadline, "client"), scanEpoch);
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
        final JsonObject serverProof = serverQueue.await(serverQueue.submit(() -> {
            ServerContext context = requireDeveloperContext(); EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (context.server != binding.server || !context.playerId.equals(binding.playerId) || scanEpoch != binding.epoch
                    || context.paused || System.nanoTime() - context.capturedAt > 1000000000L || player.isDead
                    || player.dimension != binding.dimension || player.worldObj == null || player.worldObj.isRemote
                    || player.worldObj.provider.dimensionId != binding.dimension || player.ridingEntity != null || player.riddenByEntity != null)
                throw new BridgeException("stale_world", "Airborne survival use authoritative actor/world changed before its read.");
            SurvivalFlightPosture.actor(player.capabilities.isCreativeMode, player.capabilities.isCreativeMode, player.capabilities.allowFlying, player.capabilities.isFlying);
            binding.inventory.require(player.inventory, player.inventory.getItemStack());
            requireSurvivalFlightUsePose(binding.pose, player);
            if (player.theItemInWorldManager == null || player.theItemInWorldManager.thisPlayerMP != player
                    || player.theItemInWorldManager.theWorld != player.worldObj)
                throw new BridgeException("unsupported", "Airborne survival use actual native server reach manager is unavailable.");
            double nativeReach = player.theItemInWorldManager.getBlockReachDistance();
            if (!Double.isFinite(nativeReach) || nativeReach <= 0 || nativeReach > 32)
                throw new BridgeException("unsupported", "Airborne survival use actual native server reach is invalid.");
            JsonObject proof = survivalFlightUseProof(player, Math.min(binding.reach, nativeReach), args, deadline, "integrated_server");
            if (!binding.clientProof.get("targetIdentity").equals(proof.get("targetIdentity")))
                throw new BridgeException("target_unverified", "Airborne survival use actual client/server target identities differ.");
            proof.addProperty("capturedNanos", System.nanoTime()); return proof;
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
        return clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client); refreshServerContext(client); ServerContext context = requireDeveloperContext();
            JsonObject current = snapshot(client); guard.check(current);
            if (context.server != binding.server || client.theWorld != binding.clientWorld || !context.playerId.equals(binding.playerId)
                    || scanEpoch != binding.epoch || client.thePlayer.dimension != binding.dimension || client.thePlayer.isDead
                    || System.nanoTime() - serverProof.get("capturedNanos").getAsLong() > 1000000000L
                    || Math.abs(client.playerController.getBlockReachDistance() - binding.reach) > .0001)
                throw new BridgeException("stale_world", "Airborne survival use context changed before its normal native action.");
            SurvivalFlightPosture.snapshot(current, sessionId, binding.playerId.toString(), context.server.getWorldName(), binding.dimension);
            binding.inventory.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            requireSurvivalFlightUsePose(binding.pose, client.thePlayer);
            JsonObject clientProof = survivalFlightUseProof(client.thePlayer, binding.reach, args, deadline, "client");
            if (!binding.clientProof.get("targetIdentity").equals(clientProof.get("targetIdentity")))
                throw new BridgeException("target_unverified", "Airborne survival use actual target changed before native action.");
            GuardedContext.Result result = guard.execute(() -> snapshot(client), () -> interact(client, args));
            serverProof.remove("capturedNanos"); result.data.addProperty("survivalFlightRequired", true);
            result.data.add("serverSurvivalFlightUseProof", serverProof); result.data.add("clientSurvivalFlightUseProof", clientProof); return result;
        }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
    }
    private static final class SurvivalFlightUseBinding {
        final Object server, clientWorld;
        final UUID playerId;
        final int dimension;
        final long epoch;
        final double reach;
        final JsonObject pose, clientProof;
        final OwnedBlockReplacement.InventorySnapshot inventory;
        SurvivalFlightUseBinding(ServerContext context, Minecraft client, JsonObject pose, JsonObject proof, long epoch) {
            server = context.server; clientWorld = client.theWorld; playerId = context.playerId; dimension = client.thePlayer.dimension;
            this.epoch = epoch; reach = client.playerController.getBlockReachDistance(); this.pose = pose; clientProof = proof;
            inventory = new OwnedBlockReplacement.InventorySnapshot(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
        }
    }
    private static void requireSurvivalFlightUsePose(JsonObject pose, EntityPlayer player) {
        if (Math.abs(player.posX - pose.get("x").getAsDouble()) > .0001 || Math.abs(player.boundingBox.minY - pose.get("feetY").getAsDouble()) > .0001
                || Math.abs(player.posZ - pose.get("z").getAsDouble()) > .0001 || Math.abs(player.rotationYaw - pose.get("yaw").getAsFloat()) > .01
                || Math.abs(player.rotationPitch - pose.get("pitch").getAsFloat()) > .01 || player.inventory.currentItem != pose.get("selectedHotbarSlot").getAsInt())
            throw new BridgeException("stale_world", "Airborne survival use retains exact native position, camera and selected-source guards.");
    }
    private static JsonObject survivalFlightUseProof(EntityPlayer player, double reach, JsonObject args, long deadline, String source) {
        int x = JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), side = args.has("side") ? JsonData.integer(args, "side", 0, 5) : 1;
        JsonObject proof = WorldObservationsAdapter.spaceAt(player.worldObj, player, NativePlacementAdapter.actorBody(player.boundingBox), deadline, false);
        SurvivalFlightPosture.space(proof); proof.addProperty("source", source); proof.addProperty("worldTick", player.worldObj.getTotalWorldTime());
        NativePlacementAdapter.Access access = NativePlacementAdapter.access(player.worldObj, player.dimension);
        Vec3 eye = player instanceof EntityPlayerMP ? SurvivalConstructionAdapter.serverEye(player.posX, player.posY, player.posZ, player.getEyeHeight()) : player.getPosition(1F);
        if (!Double.isFinite(reach) || reach <= 0 || reach > 32 || !access.loaded(x, y, z)
                || NativePlacementAdapter.distanceToBlock(eye, x, y, z) > reach
                || !NativePlacementAdapter.loadedRay(access, eye, NativePlacementAdapter.facePoint(x, y, z, side), y))
            throw new BridgeException("out_of_reach", "Airborne survival use requires loaded native geometry inside normal reach.");
        MovingObjectPosition hit = NativePlacementAdapter.visibleFace(access, eye, reach, x, y, z, side);
        if (hit == null) throw new BridgeException("face_not_visible", "Airborne survival use requires a fresh native ray to its explicit face.");
        JsonObject target = access.state(x, y, z).description; target.remove("source");
        JsonObject gt = GregTechAdapter.identity(player.worldObj.getTileEntity(x, y, z)); if (gt != null) target.add("gregtech", gt);
        proof.add("targetIdentity", target); proof.addProperty("normalReach", reach); proof.addProperty("side", hit.sideHit);
        proof.addProperty("hitX", hit.hitVec.xCoord); proof.addProperty("hitY", hit.hitVec.yCoord); proof.addProperty("hitZ", hit.hitVec.zCoord); return proof;
    }

    /** Register/check on game owners; only the HTTP worker awaits normal END-tick read readiness. */
    private GuardedContext.Result dispatchNativeObservation(JsonObject args, GameQueue.Control control, GuardedContext guard,
            InspectManyPlayerInventory.Request inventoryRequest) throws ExecutionException, InterruptedException {
        if (inventoryRequest == null) throw new BridgeException("invalid_request", "Native waits require complete player inventory and AFTER context.");
        final NativeObservationWait.Request request = new NativeObservationWait.Request(args);
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        final java.util.concurrent.atomic.AtomicReference<NativeObservationWait.Job> owned = new java.util.concurrent.atomic.AtomicReference<NativeObservationWait.Job>();
        Runnable cancellation = () -> { NativeObservationWait.Job job = owned.get(); if (job != null) { job.cancel(); job.close(); } };
        control.onReadCancellation(cancellation);
        try {
            final NativeWaitClient clientBinding = clientQueue.await(clientQueue.submit(() -> {
                Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
                JsonObject current = snapshot(client); guard.check(current); ServerContext context = requireDeveloperContext();
                if (client.isGamePaused() || client.thePlayer.isDead || client.thePlayer.inventory.getItemStack() != null)
                    throw new BridgeException("native_wait_context_changed", "Native observation requires an unpaused living actor and empty cursor.");
                return new NativeWaitClient(context, client.theWorld, client.thePlayer.getEntityId(), client.thePlayer.inventory.currentItem, scanEpoch);
            }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
            final NativeWaitServer binding = serverQueue.await(serverQueue.submit(() -> {
                NativeWaitServer value = new NativeWaitServer(request, clientBinding);
                NativeObservationWait.Job job = nativeWaits.register(request, value, deadline); owned.set(job);
                if (control.isCancelled()) { job.cancel(); job.close(); }
                return value;
            }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
            NativeObservationWait.Job job = owned.get();
            final String status;
            long nativeAwaitStarted = System.nanoTime();
            try { status = job.await(Math.min(request.millis, Math.max(1, qaWaitRemaining(deadline) - 1500))); }
            finally { control.recordNativeAwait(System.nanoTime() - nativeAwaitStarted); }
            final JsonObject data = serverQueue.await(serverQueue.submit(() -> {
                long observedAt = System.nanoTime();
                binding.check();
                binding.checkOwners();
                JsonObject fresh = inspectMany(binding.world, args, true);
                boolean matched = request.matches(fresh); binding.check();
                binding.checkOwners();
                if (System.nanoTime() >= deadline) throw new BridgeException("timeout", "Fresh final owner evidence arrived after the total read deadline.");
                String finalStatus = System.nanoTime() - observedAt >= NativeObservationWait.TICK_WORK_NANOS ? "partial" : status;
                fresh.add("nativeWait", job.receipt(finalStatus, matched)); return fresh;
            }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
            return clientQueue.await(clientQueue.submit(() -> {
                Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client); ServerContext context = requireDeveloperContext();
                clientBinding.check(context);
                if (client.theWorld != clientBinding.world || client.thePlayer.getEntityId() != clientBinding.entityId
                        || client.thePlayer.isDead || client.thePlayer.inventory.currentItem != clientBinding.slot || client.thePlayer.inventory.getItemStack() != null)
                    throw new BridgeException("native_wait_context_changed", "The actual client actor or controls changed before final AFTER evidence.");
                GuardedContext.Result result = InspectManyPlayerInventory.after(inventoryRequest, data, () -> inspectManyPlayerInventory(client, inventoryRequest.allowPendingZero), () -> {
                    JsonObject current = snapshot(client); current.getAsJsonObject("player").addProperty("playerId", client.thePlayer.getUniqueID().toString()); return current;
                }, guard);
                if (System.nanoTime() >= deadline) throw new BridgeException("timeout", "Complete owner/player AFTER evidence arrived after the total read deadline.");
                return result;
            }, qaWaitRemaining(deadline), control), qaWaitRemaining(deadline));
        } finally {
            control.clearReadCancellation(cancellation); NativeObservationWait.Job job = owned.get(); if (job != null) job.close();
        }
    }
    private final class NativeWaitClient {
        final Object server, world, screen; final UUID actor; final String screenId; final int dimension, entityId, slot; final long epoch;
        NativeWaitClient(ServerContext context, Object world, int entityId, int slot, long epoch) {
            server = context.server; this.world = world; screen = context.screenIdentity; screenId = context.screenId;
            actor = context.playerId; dimension = context.dimension; this.entityId = entityId; this.slot = slot; this.epoch = epoch;
        }
        void check(ServerContext context) {
            if (context.server != server || context.dimension != dimension || !actor.equals(context.playerId) || context.screenIdentity != screen
                    || !screenId.equals(context.screenId) || context.paused || !context.developer || scanEpoch != epoch
                    || System.nanoTime() - context.capturedAt > 1000000000L)
                throw new BridgeException("native_wait_context_changed", "Actual world, actor, GUI or context changed during the bounded read.");
        }
    }
    private final class NativeWaitServer implements NativeObservationWait.Access {
        final NativeObservationWait.Request request; final NativeWaitClient client; final EntityPlayerMP player; final World world;
        final Container container; final int window; final ItemStack held; final Block[] blocks; final TileEntity[] tiles; final Object[] metas;
        private boolean ownersBound;
        final double x, y, z; final float yaw, pitch;
        NativeWaitServer(NativeObservationWait.Request request, NativeWaitClient client) {
            this.request = request; this.client = client; player = (EntityPlayerMP) operationPlayer(true); world = player.worldObj;
            container = player.openContainer; window = container == null ? -1 : container.windowId;
            held = player.getHeldItem() == null ? null : player.getHeldItem().copy(); x = player.posX; y = player.posY; z = player.posZ; yaw = player.rotationYaw; pitch = player.rotationPitch;
            blocks = new Block[request.owners.size()]; tiles = new TileEntity[request.owners.size()]; metas = new Object[request.owners.size()]; check();
            for (int i = 0; i < request.owners.size(); i++) {
                NativeObservationWait.Owner owner = request.owners.get(i); requireLoaded(owner);
                blocks[i] = world.getBlock(owner.x, owner.y, owner.z); tiles[i] = world.getTileEntity(owner.x, owner.y, owner.z);
                if (tiles[i] == null || tiles[i].isInvalid()) throw new BridgeException("native_wait_identity_changed", "The actual loaded owner tile is unavailable.");
                if (owner.metaClass != null) {
                    metas[i] = AdapterAccess.call(tiles[i], "getMetaTileEntity");
                    if (metas[i] == null || !owner.metaClass.equals(metas[i].getClass().getName()))
                        throw new BridgeException("native_wait_identity_changed", "The actual public GT meta object is unavailable.");
                }
                owner.check(block(world, owner.x, owner.y, owner.z, true));
            }
            ownersBound = true; check();
        }
        private void requireLoaded(NativeObservationWait.Owner owner) {
            if (owner.dimension != client.dimension || world.provider.dimensionId != owner.dimension
                    || !world.getChunkProvider().chunkExists(owner.x >> 4, owner.z >> 4) || !world.blockExists(owner.x, owner.y, owner.z))
                throw new BridgeException("chunk_unloaded", "Native observation reads only the declared already-loaded actual dimension.");
        }
        public void check() {
            client.check(requireDeveloperContext());
            if (world == null || world.isRemote || DimensionManager.getWorld(client.dimension) != world || player.worldObj != world
                    || player.isDead || player.dimension != client.dimension || player.getEntityId() != client.entityId
                    || operationPlayer(true) != player || player.inventory.currentItem != client.slot || player.inventory.getItemStack() != null
                    || player.openContainer != container || container == null || container.windowId != window
                    || !ItemStack.areItemStacksEqual(held, player.getHeldItem()) || player.posX != x || player.posY != y || player.posZ != z
                    || player.rotationYaw != yaw || player.rotationPitch != pitch)
                throw new BridgeException("native_wait_context_changed", "Actual server player, world, container, selection, held stack or pose changed during the read.");
            if (ownersBound) checkOwners();
        }
        public JsonObject read(NativeObservationWait.Owner owner) {
            int index = request.owners.indexOf(owner); checkObject(index);
            JsonObject result = block(world, owner.x, owner.y, owner.z, true); checkObject(index); return result;
        }
        void checkOwners() {
            for (int i = 0; i < request.owners.size(); i++) {
                checkObject(i);
            }
        }
        private void checkObject(int index) {
            NativeObservationWait.Owner owner = request.owners.get(index); requireLoaded(owner);
            if (world.getBlock(owner.x, owner.y, owner.z) != blocks[index] || world.getBlockMetadata(owner.x, owner.y, owner.z) != owner.metadata
                    || world.getTileEntity(owner.x, owner.y, owner.z) != tiles[index] || tiles[index].isInvalid()
                    || owner.metaClass != null && (AdapterAccess.call(tiles[index], "getMetaTileEntity") != metas[index]
                        || AdapterAccess.call(metas[index], "getBaseMetaTileEntity") != tiles[index]))
                throw new BridgeException("native_wait_identity_changed", "An actual block, metadata, tile or public GT meta object changed during observation.");
        }
        public long worldTick() { return world.getTotalWorldTime(); }
    }

    /** Three owning-queue read barriers; no force-loading, native mutation or client objects on the server thread. */
    private GuardedContext.Result dispatchAeMaterialSource(AeSurvivalSource.InspectionRequest request, GuardedContext guard, GameQueue.Control control) throws ExecutionException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        final AeSourceReadBinding binding = clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
            JsonObject snapshot = snapshot(client); guard.check(snapshot); ServerContext context = requireDeveloperContext();
            if (!sessionId.equals(request.session)) throw new BridgeException("stale_session", "AE inspection expectedSessionId changed.");
            JsonObject worldArgs = new JsonObject(); worldArgs.addProperty("expectedWorld", request.world); requireExpectedWorld(context, worldArgs);
            if (context.dimension != request.dimension || client.thePlayer.dimension != request.dimension || client.theWorld.provider.dimensionId != request.dimension)
                throw new BridgeException("DIMENSION_CHANGED", "AE source inspection only reads the actor's current actual dimension.");
            if (context.paused || client.thePlayer.isDead) throw new BridgeException("invalid_state", "AE source inspection requires an unpaused actual living actor.");
            return new AeSourceReadBinding(context, client.theWorld, scanEpoch);
        }, 8000, control), 8000);
        long remaining = qaWaitRemaining(deadline);
        JsonObject data = serverQueue.await(serverQueue.submit(() -> {
            ServerContext context = requireDeveloperContext();
            if (context.server != binding.server || !context.playerId.equals(binding.actorId) || context.dimension != request.dimension
                    || context.paused || System.nanoTime() - context.capturedAt > 1000000000L || scanEpoch != binding.epoch)
                throw new BridgeException("stale_world", "AE source inspection authoritative context changed before its read.");
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (player.isDead || player.worldObj == null || player.worldObj.isRemote || player.dimension != request.dimension || player.worldObj.provider.dimensionId != request.dimension)
                throw new BridgeException("invalid_state", "AE source inspection actual authoritative actor/world is unavailable.");
            JsonObject result = AeSurvivalSource.inspect(player, request); result.addProperty("actorUuid", player.getUniqueID().toString());
            result.addProperty("actorEntityId", player.getEntityId()); return result;
        }, remaining, control), remaining);
        remaining = qaWaitRemaining(deadline);
        return clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client); ServerContext context = requireDeveloperContext();
            if (context.server != binding.server || !context.playerId.equals(binding.actorId) || client.theWorld != binding.clientWorld
                    || client.thePlayer.dimension != request.dimension || client.thePlayer.isDead || scanEpoch != binding.epoch
                    || client.thePlayer.getEntityId() != data.get("actorEntityId").getAsInt())
                throw new BridgeException("stale_world", "AE source inspection actor/world changed before its AFTER receipt.");
            JsonObject snapshot = snapshot(client); guard.check(snapshot); return guard.result(data, snapshot);
        }, remaining, control), remaining);
    }
    private static final class AeSourceReadBinding {
        final Object server, clientWorld;
        final UUID actorId;
        final long epoch;
        AeSourceReadBinding(ServerContext context, Object clientWorld, long epoch) {
            server = context.server; actorId = context.playerId; this.clientWorld = clientWorld; this.epoch = epoch;
        }
    }

    /** HTTP response completion only updates pure lifecycle and scan delivery metadata. */
    public void responseFinished(GameQueue.Control control, boolean delivered) {
        lifecycle.responseFinished(control, delivered);
        clientScans.responseFinished(control, delivered);
        serverScans.responseFinished(control, delivered);
    }

    /** Called only by local commands or queued HTTP controls on the client thread. */
    public JsonObject localControl(String control, boolean enabled) {
        if("cancel".equals(control)) { releaseInput(); navigation=null; return controlStatus(); }
        Minecraft client = Minecraft.getMinecraft(); JsonObject result = background.change(client, control, enabled, lifecycle.active(), this::releaseInput);
        if (!background.active(client)) LogicalGuiPointer.clear("takeover_released"); return result;
    }

    public JsonObject controlStatus() {
        JsonObject result = background.inspect(Minecraft.getMinecraft());
        result.add("lifecycle", lifecycle.inspect());
        result.add("scan", scanSettings.inspect());
        result.add("cache", WorldObservationsAdapter.cacheStatus());
        JsonObject request=new JsonObject(); request.addProperty("expectedSessionId",sessionId); result.add("inputTask",inputTask(request));
        return result;
    }

    /** Client-thread defaults. Runtime changes are session-only; startup values come from the Forge config. */
    public JsonObject scanConfig(JsonObject args) {
        long before = scanSettings.revision();
        JsonObject result = scanSettings.apply(args);
        if (before != scanSettings.revision()) {
            WorldObservationsAdapter.configureCache(scanSettings);
            invalidateScans();
        }
        result.add("cache", WorldObservationsAdapter.cacheStatus());
        return result;
    }

    private void invalidateScans() {
        flightCapability.clear();
        scanEpoch++;
        clientScans.invalidate();
        serverScans.invalidate();
        WorldObservationsAdapter.clearCache();
        structures.invalidate();
        stateDiff.invalidate();
        AeDataAdapter.invalidate();
        GuiControlAdapter.invalidate();
        recipes.invalidate();
        clientRecipes.invalidate();
        machineCaptures.clear();
    }

    @SubscribeEvent
    public void onCacheWorldUnload(WorldEvent.Unload event) { WorldObservationsAdapter.unloadCache(event.world, null, null); }

    @SubscribeEvent
    public void onCacheChunkUnload(ChunkEvent.Unload event) {
        Chunk chunk = event.getChunk(); WorldObservationsAdapter.unloadCache(event.world, chunk.xPosition, chunk.zPosition);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            clientTicks++;
            AeNativeControls.observe(client.currentScreen);
            if (clientTicks % 20 == 0) machineCaptures.expire();
            WorldObservationsAdapter.maintainCache();
            NeiPreviewResources.ensureTracking(client);
            structures.maintain(NeiPreviewResources.context());
            background.tick(client);
            maintainNavigation(client);
            LogicalGuiPointer.tick();
            NeiModifierHold.tick();
            observeStartup(client);
            lifecycle.tick();
            if (previousWorld != client.theWorld) {
                releaseInput();
                inputDiagnostics.invalidate();
                LogicalGuiPointer.clear("world_changed"); NeiViewAdapter.invalidateRendered();
                profile = "player";
                previousWorld = client.theWorld;
                invalidateScans();
                qa.invalidate();
            }
            refreshServerContext(client);
            if (serverContext == null || !serverContext.developer || serverContext.paused || client.thePlayer == null || client.thePlayer.isDead) {
                nativeWaits.invalidateAll("The client world, profile, pause or living actor changed during native observation.");
                aeOrders.invalidateWaits();
            }
            structures.blueprints().validateClient(client, sessionId, "developer".equals(profile));
            identifyScreen(client.currentScreen);
            if (!held.isEmpty() && (client.thePlayer == null || client.currentScreen != null || client.isGamePaused() || !OwnedKeyState.active("player.input")
                    || System.nanoTime() >= inputDeadline)) releaseInput();
            clientScans.beginTick();
            clientQueue.drain(8);
            maintainFollow(client);
            if(background.active(client) && navigation==null && held.isEmpty() && client.currentScreen==null && !client.isGamePaused())
                WorldObservationsAdapter.warmCache(client.theWorld,client.thePlayer);
            for (Map.Entry<String, KeyBinding> entry : held.entrySet()) {
                KeyBinding binding = entry.getValue(); int code = heldKeyCodes.get(entry.getKey()); OwnedKeyState.hold("player.input", binding, code); KeyBinding.setKeyBindState(code, true);
                inputDiagnostics.writeNative("player.input", entry.getKey(), binding, code, true, clientTicks);
            }
        } else {
            maintainApproachEyeReads();
            guiWaits.tick();
            navigationStartWaits.tick();
            PlayerNavigation.State stepState = navigation;
            if (stepState != null && stepState.step != null && !stepState.abort && client.thePlayer == stepState.playerIdentity
                    && client.theWorld == stepState.worldIdentity && client.thePlayer != null && client.thePlayer.movementInput != null
                    && client.currentScreen == null && !client.isGamePaused()) {
                try { stepState.step.observeForwardTick(clientTicks, client.thePlayer.movementInput.moveForward); }
                catch (BridgeException error) {
                    stepState.failure = error; stepState.abort = true; releaseNavigationKeys(stepState);
                    if (navigation == stepState) navigation = null;
                }
            }
            if (inputDiagnostics.sampling()) inputDiagnostics.tickEnd(clientTicks, actualMovementInput(client));
            if (!held.isEmpty() && --remainingInputTicks <= 0) releaseInput();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ServerContext context = serverContext;
            tickServer = context != null && context.server == MinecraftServer.getServer() ? context.server : null;
            serverTickStart = System.nanoTime();
        }
        if (event.phase == TickEvent.Phase.END) {
            ServerContext context = serverContext;
            if (context != null && context.server == MinecraftServer.getServer()) {
                serverIdentity = new ServerIdentity(context.server, context.server.getWorldName(), context.server.getFolderName());
                if (context.server == tickServer) serverSamples.record(tickServer, System.nanoTime() - serverTickStart, System.nanoTime());
            }
            serverScans.beginTick();
            qa.tick(MinecraftServer.getServer());
            serverQueue.drain(8);
            captureFlight(serverContext);
            nativeWaits.tick();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) AeNativeControls.observe(Minecraft.getMinecraft().currentScreen);
        if (event.phase == TickEvent.Phase.END) {
            long now = System.nanoTime();
            if (frameWindowStart == 0) frameWindowStart = now;
            frameCount++;
            if (now - frameWindowStart >= 1000000000L) {
                measuredFps = frameCount * 1000000000D / (now - frameWindowStart);
                lastFrameSample = now;
                frameWindowStart = now;
                frameCount = 0;
            }
            if (!held.isEmpty() && System.nanoTime() >= inputDeadline) releaseInput();
            renderQueue.drain(1);
            Minecraft client = Minecraft.getMinecraft();
            clientQueue.drainIf(() -> renderClientReady(client), () -> {
                refreshServerContext(client);
                structures.blueprints().validateClient(client, sessionId, "developer".equals(profile));
                identifyScreen(client.currentScreen);
            });
        }
    }

    private boolean renderClientReady(Minecraft client) {
        return "developer".equals(profile) && held.isEmpty() && navigation == null && !lifecycle.active()
                && client.theWorld != null && client.theWorld == previousWorld && client.thePlayer != null
                && !client.isGamePaused() && WorldLifecycle.integrated(client) && background.active(client);
    }

    /** Forge startup prompts run a nested loading loop without normal client/render ticks. */
    public void loadingFrame() {
        if (loadingFrameActive) return;
        loadingFrameActive = true;
        try {
            Minecraft client = Minecraft.getMinecraft();
            observeStartup(client);
            clientQueue.drainIf(() -> startupFrameReady(client), () -> identifyScreen(client.currentScreen));
            observeStartup(client);
        } finally { loadingFrameActive = false; }
    }

    private boolean startupFrameReady(Minecraft client) {
        return client.theWorld == null && client.thePlayer == null && held.isEmpty() && navigation == null
                && StartupScreenRecovery.startupScreen(client.currentScreen);
    }

    private void observeStartup(Minecraft client) {
        if (startupRecovery.observe(client.currentScreen)) lifecycle.startupCanceled();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (!event.isCanceled() && event.message != null) messages.add(event.message.getUnformattedText(), clientTicks);
    }

    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        buttonScreen = event.gui;
        buttons = event.buttonList;
    }

    private List<?> nativeButtons(GuiScreen screen) {
        return GuiCallbacks.buttons(screen, buttonScreen == screen ? buttons : null);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (startupRecovery.observe(event.gui)) lifecycle.startupCanceled();
        releaseInput();
        AeDataAdapter.invalidate();
        GuiControlAdapter.invalidate();
        identifiedScreen = event.gui;
        screenId = event.gui == null ? "" : UUID.randomUUID().toString();
    }

    private String identifyScreen(GuiScreen screen) {
        if (identifiedScreen != screen) {
            identifiedScreen = screen;
            screenId = screen == null ? "" : UUID.randomUUID().toString();
        }
        return screenId;
    }

    private void refreshServerContext(Minecraft client) {
        remoteConnection=client.theWorld!=null && !client.isSingleplayer();
        IntegratedServer server = WorldLifecycle.integrated(client) ? client.getIntegratedServer() : null;
        boolean local = client.theWorld != null && client.thePlayer != null && server != null;
        String derived = local || RemoteReadClient.INSTANCE.operatorReady() ? "developer" : "player";
        if (!derived.equals(profile)) {
            profile = derived;
            invalidateScans();
        }
        String scope = "", label = "menu";
        if (local && serverIdentity != null && serverIdentity.server == server) {
            scope = "sp:" + serverIdentity.folder; label = serverIdentity.folder;
        } else if (remoteConnection && client.getCurrentServerData() != null) {
            scope = "mp:" + OperationPolicies.endpoint(client.getCurrentServerData().serverIP); label = "server";
        }
        policies.bind(scope, label);
        serverContext = local ? new ServerContext(server, client.thePlayer.dimension, "developer".equals(profile),
                client.thePlayer.getUniqueID(), client.currentScreen != null, client.isGamePaused(), System.nanoTime(), client.currentScreen, identifyScreen(client.currentScreen)) : null;
    }

    private JsonObject clientOperation(String operation, JsonObject args, GameQueue.Control control) {
        Minecraft client = Minecraft.getMinecraft();
        switch (operation) {
            case "bridge.policy": return policy(args);
            case "bridge.health": return health(client);
            case "client.snapshot": return snapshot(client);
            case "client.worlds": return WorldLifecycle.list(client, args);
            case "client.load_world": case "client.connect": case "client.leave_world": case "client.exit": return scheduleLifecycle(client, operation, args, control);
            case "client.background": case "client.mouse": case "client.unfocused":
                JsonElement enabled = args.get("enabled");
                if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
                    throw new BridgeException("invalid_args", "enabled must be a boolean.");
                return localControl(operation.substring("client.".length()), enabled.getAsBoolean());
            case "client.diagnostics": return diagnostics(client);
            case "client.mods": return catalog.mods(args);
            case "client.messages": return messages.inspect(args, sessionId);
            case "client.recording": return activity.read(args);
            case "world.scan_config": return scanConfig(args);
            case "input.bindings": return RuntimeBindingsAdapter.inspect(client, args);
            case "state.diff": return observeDiff(client, args);
            case "registry.items": return catalog.items(args);
            case "registry.item": return catalog.item(args, client.thePlayer);
            case "registry.variants": return catalog.variants(args, client.thePlayer);
            case "client.window": return ClientDisplayState.inspect(client);
            case "client.window_action":
                if (!sessionId.equals(JsonData.string(args, "expectedSessionId"))) throw new BridgeException("stale_session", "Window action requires the current client session.");
                return ClientWindowControls.perform(client, args);
            case "gui.inspect": return gui(client);
            case "gui.pointer":
                GuiScreen pointerScreen = requireGui(client, args);
                return controlResult(client, args, LogicalGuiPointer.perform(client, pointerScreen, args, sessionId, () -> background.active(client)));
            case "gui.tooltip":
                requirePlayer(client);
                GuiScreen tooltipScreen = requireGui(client, args);
                return GuiDataAdapter.tooltip(client, tooltipScreen, args,
                        nativeButtons(tooltipScreen));
            case "nei.inspect":
                requirePlayer(client);
                return NeiDataAdapter.inspect(client, requireGui(client, args), args);
            case "nei.view":
                requirePlayer(client);
                return NeiViewAdapter.inspect(client, requireGui(client, args), args, sessionId);
            case "nei.bookmarks":
                requirePlayer(client);
                return neiBookmarks.inspect(requireGui(client, args), client.theWorld, args);
            case "nei.bookmark_action":
                requirePlayer(client);
                GuiScreen bookmarkScreen = requireGui(client, args);
                requireCurrentContainer(client, bookmarkScreen);
                return controlResult(client, args, neiBookmarks.perform(bookmarkScreen, client.theWorld, args));
            case "research.read":
                requirePlayer(client);
                return ResearchDataAdapter.inspect(client, args);
            case "nei.preview_control":
                requirePlayer(client);
                GuiScreen previewScreen = requireGui(client, args);
                NeiViewAdapter.validateContext(previewScreen, args, sessionId);
                if ("preview_render_capture".equals(JsonData.string(args, "action"))) {
                    JsonObject capture = NeiNativeRenderProvider.capture(previewScreen, args);
                    capture.addProperty("action", "preview_render_capture");
                    capture.addProperty("contextToken", NeiViewAdapter.refreshCaptureContext(previewScreen, args, sessionId));
                    capture.addProperty("contextRefreshed", true); capture.addProperty("requiresViewRefresh", false);
                    capture.addProperty("currentScreenChanged", client.currentScreen != previewScreen);
                    return controlResult(client, args, capture);
                }
                JsonObject previewControl = NeiPreviewProvider.control(previewScreen, args);
                previewControl.addProperty("currentScreenChanged", client.currentScreen != previewScreen);
                finishNeiPreviewControl(previewControl);
                return controlResult(client, args, previewControl);
            case "nei.open":
                requirePlayer(client);
                if (!identifyScreen(client.currentScreen).equals(JsonData.string(args, "screenId")))
                    throw new BridgeException("stale_screen", "screenId does not match the current screen, including an empty ID when no GUI is open.");
                return controlResult(client, args, NeiControlAdapter.open(client, client.currentScreen, args));
            case "nei.navigate":
                requirePlayer(client);
                GuiScreen neiNavigationScreen = requireGui(client, args);
                NeiControlAdapter.validateNavigation(args);
                if ("pause".equals(JsonData.string(args, "action"))) {
                    if (!background.active(client)) throw new BridgeException("background_required", "NEI Shift hold requires full background takeover.");
                    TooltipModifiers.probe();
                    if (!TooltipModifiers.diagnostics().get("hookObserved").getAsBoolean()) throw new BridgeException("unsupported", "Actual Keyboard.isKeyDown hook is unavailable; no Shift hold installed.");
                    int recipe = JsonData.integer(args, "index", 0, 100000);
                    NeiOverlayAdapter.findWidget(neiNavigationScreen, recipe);
                    Object world = client.theWorld, handler = ExpandedGuiApi.call(neiNavigationScreen, "getHandler");
                    String view = LogicalGuiPointer.viewMarker(neiNavigationScreen);
                    return controlResult(client, args, NeiModifierHold.arm(recipe, args.has("ttl_ms") ? JsonData.integer(args, "ttl_ms", 100, 60000) : 15000,
                            () -> client.currentScreen == neiNavigationScreen && client.theWorld == world && client.thePlayer != null && !client.thePlayer.isDead && background.active(client)
                                    && handler == ExpandedGuiApi.call(neiNavigationScreen, "getHandler") && view.equals(LogicalGuiPointer.viewMarker(neiNavigationScreen))));
                }
                if ("bookmark".equals(JsonData.string(args, "action")) && args.has("taskId"))
                    return controlResult(client, args, neiBookmarks.addRecipe(neiNavigationScreen, client.theWorld, args));
                return controlResult(client, args, NeiControlAdapter.navigate(client, neiNavigationScreen, args));
            case "nei.item_action":
                requirePlayer(client);
                GuiScreen neiItemScreen = requireGui(client, args);
                requireCurrentContainer(client, neiItemScreen);
                return controlResult(client, args, NeiItemControlAdapter.perform(client, neiItemScreen, args, "developer".equals(profile)));
            case "waila.inspect": return WailaDataAdapter.inspect(client, args);
            case "ae.inspect":
                requirePlayer(client);
                return AeDataAdapter.inspect(client, requireGui(client, args), args);
            case "ae.action":
                requirePlayer(client);
                GuiScreen aeScreen = requireGui(client, args);
                requireCurrentContainer(client, aeScreen);
                return controlResult(client, args, AeControlAdapter.perform(client, aeScreen, args));
            case "gui.widgets":
                GuiScreen widgetScreen = requireGui(client, args);
                return ScreenSemantics.describe(GuiControlAdapter.inspect(client, widgetScreen, args,
                        nativeButtons(widgetScreen)), client.currentScreen);
            case "gui.widget_action":
                GuiScreen actionScreen = requireGui(client, args);
                requireCurrentContainer(client, actionScreen);
                return controlResult(client, args, GuiControlAdapter.perform(client, actionScreen, args,
                        nativeButtons(actionScreen), sessionId, () -> background.active(client)));
            case "inventory.inspect":
                requirePlayer(client);
                JsonObject inventory = JsonData.inventory(client.thePlayer.inventory);
                inventory.addProperty("playerId", client.thePlayer.getUniqueID().toString());
                inventory.addProperty("selectedHotbarSlot", client.thePlayer.inventory.currentItem);
                inventory.add("cursorItem", JsonData.item(client.thePlayer.inventory.getItemStack()));
                if (GuiDataAdapter.booleanArg(args, "includeNbt", false)) {
                    for (JsonElement row : inventory.getAsJsonArray("slots")) {
                        JsonObject slot = row.getAsJsonObject();
                        ItemStack stack = client.thePlayer.inventory.getStackInSlot(slot.get("index").getAsInt());
                        if (stack != null) slot.add("item", AeDataAdapter.actualItem(stack));
                    }
                    ItemStack cursor = client.thePlayer.inventory.getItemStack();
                    if (cursor != null) inventory.add("cursorItem", AeDataAdapter.actualItem(cursor));
                }
                return inventory;
            case "baubles.inspect":
                requirePlayer(client);
                JsonObject accessories = BaublesInventoryAdapter.inspect(client.thePlayer, args);
                accessories.addProperty("sessionId", sessionId);
                return accessories;
            case "world.inspect": case "world.scan": case "world.inspect_many": case "world.entities": case "world.spatial":
                return worldOperation(operation, args, false, control);
            case "player.look":
                requirePlayer(client);
                float yaw = (float) JsonData.number(args, "yaw", -360000, 360000);
                float pitch = (float) JsonData.number(args, "pitch", -90, 90);
                client.thePlayer.rotationYaw = yaw;
                client.thePlayer.rotationPitch = pitch;
                client.thePlayer.prevRotationYaw = client.thePlayer.rotationYaw;
                client.thePlayer.prevRotationPitch = client.thePlayer.rotationPitch;
                return snapshot(client);
            case "player.hotbar":
                requirePlayer(client);
                client.thePlayer.inventory.currentItem = JsonData.integer(args, "slot", 0, 8);
                return status("selectedHotbarSlot", client.thePlayer.inventory.currentItem);
            case "player.input": return input(client, args);
            case "player.task": return inputTask(args);
            case "player.follow": return follow(args);
            case "player.flight": return flight(client, args);
            case "player.interact": return interact(client, args);
            case "player.place_block": return placeBlock(client, args);
            case "gui.slot_click": return slotClick(client, args);
            case "gui.open_inventory": {
                requireWorldControls(client);
                releaseInput();
                client.displayGuiScreen(new GuiInventory(client.thePlayer));
                GuiScreen inventoryScreen = client.currentScreen;
                CreativeGuiAdapter.selectPlayerInventory(inventoryScreen, client.thePlayer.inventory,
                        () -> client.currentScreen == inventoryScreen);
                return gui(client);
            }
            case "gui.open_pause_menu":
                ExpandedGuiApi.keys(args);
                requireWorldControls(client);
                if (client.thePlayer.inventory.getItemStack() != null)
                    throw new BridgeException("invalid_state", "Pause menu requires an empty inventory cursor.");
                releaseInput();
                client.displayInGameMenu();
                return gui(client);
            case "gui.open_baubles":
                requireWorldControls(client);
                releaseInput();
                return BaublesInventoryAdapter.open(client.thePlayer);
            case "gui.click": return clickGui(client, args);
            case "gui.key": return keyGui(client, args);
            case "gui.close":
                StartupScreenRecovery.requireNativeChoice(client.currentScreen);
                releaseInput();
                if (client.thePlayer != null) client.thePlayer.closeScreen();
                else client.displayGuiScreen(null);
                return ScreenSemantics.describe(status("closed", true), client.currentScreen);
            case "chat.send":
                requirePlayer(client);
                if (args.has("expectedSessionId") && !sessionId.equals(JsonData.string(args, "expectedSessionId")))
                    throw new BridgeException("stale_session", "Chat client runtime changed; no message sent.");
                if (args.has("expectedConnectionId")) RemoteReadClient.INSTANCE.requireCurrent(JsonData.string(args, "expectedConnectionId"));
                String message = JsonData.string(args, "message");
                if (message.trim().isEmpty() || message.length() > 100 || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0 || message.indexOf('\u0000') >= 0)
                    throw new BridgeException("invalid_args", "message must contain 1 through 100 characters without line breaks or NUL.");
                activity.command(message, "mcb_chat");
                if (ClientControlCommand.ownsMessage(message)) {
                    ClientControlCommand.executeLocal(client.thePlayer, message);
                    JsonObject local = status("sent", false);
                    local.addProperty("localCommand", true);
                    local.add("background", background.inspect(client));
                    return local;
                }
                // Same public Forge route as GuiChat; the game/server decides actual command permission.
                client.ingameGUI.getChatGUI().addToSentMessages(message);
                boolean handled = net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(client.thePlayer, message) != 0;
                if (!handled) client.thePlayer.sendChatMessage(message);
                JsonObject submitted = status("sent", !handled);
                submitted.addProperty("localCommand", handled);
                submitted.addProperty("source", handled ? "forge_client_command_handler" : "native_player_chat_packet");
                submitted.addProperty("executionConfirmed", false);
                submitted.addProperty("acceptance", "Inspect native chat feedback and actual state; submission does not prove execution or permission.");
                return submitted;
            default: throw new BridgeException("unsupported", "Operation is not implemented: " + operation);
        }
    }

    private JsonObject scheduleLifecycle(Minecraft client, String operation, JsonObject args, GameQueue.Control control) {
        String expectedSession = CatalogData.text(args, "expectedSessionId", 128, null);
        if (!sessionId.equals(expectedSession)) throw new BridgeException("stale_session", "expectedSessionId does not match this client process.");
        observeStartup(client);
        if ("client.load_world".equals(operation) || "client.connect".equals(operation)) startupRecovery.requireFreshClient();
        background.requireLifecycle(client);
        final World initialWorld = client.theWorld;
        final IntegratedServer initialServer = client.getIntegratedServer();
        final GuiScreen initialScreen = client.currentScreen;
        final Object initialHandler = client.getNetHandler();
        final EntityPlayer initialPlayer = client.thePlayer;
        if ("client.connect".equals(operation)) {
            final String host=WorldLifecycle.host(args); final int port=JsonData.integer(args,"port",1,65535);
            if(initialWorld!=null || initialServer!=null || !WorldLifecycle.mainMenu(initialScreen))
                throw new BridgeException("invalid_state","Connecting requires the normal main menu with no loaded world or integrated server.");
            final net.minecraft.client.multiplayer.ServerData target=new net.minecraft.client.multiplayer.ServerData("Agent QA",host+":"+port);
            JsonObject result=lifecycle.schedule(operation,sessionId,control,
                    () -> client.theWorld==null && client.getIntegratedServer()==null && client.currentScreen==initialScreen,
                    () -> {
                        releaseInput(); background.requireLifecycle(client);
                        // Forge initializes the login/registration latch; direct GuiConnecting skips that setup.
                        cpw.mods.fml.client.FMLClientHandler.instance().setupServerList();
                        cpw.mods.fml.client.FMLClientHandler.instance().connectToServer(initialScreen,target);
                    },
                    () -> {
                        if(client.currentScreen instanceof net.minecraft.client.gui.GuiDisconnected)
                            throw new BridgeException("connection_failed","Native server login was rejected or disconnected. Inspect client/server diagnostics before retrying.");
                        if(client.isSingleplayer() || client.getIntegratedServer()!=null) throw new BridgeException("stale_world","A local world replaced the requested server connection.");
                        net.minecraft.client.multiplayer.ServerData current=client.getCurrentServerData();
                        if(current!=null && !target.serverIP.equals(current.serverIP)) throw new BridgeException("stale_world","A different server replaced the requested connection.");
                        return client.theWorld!=null && client.thePlayer!=null && current!=null && target.serverIP.equals(current.serverIP);
                    });
            result.addProperty("host",host); result.addProperty("port",port); return result;
        }
        if ("client.load_world".equals(operation)) {
            final String folder = WorldLifecycle.folder(args);
            WorldLifecycle.loadName(client, folder);
            final IntegratedServer[] launched = new IntegratedServer[1];
            JsonObject result = lifecycle.schedule(operation, sessionId, control,
                    () -> client.theWorld == initialWorld && client.getIntegratedServer() == initialServer && client.currentScreen == initialScreen,
                    () -> {
                        String name = WorldLifecycle.loadName(client, folder);
                        releaseInput();
                        background.requireLifecycle(client);
                        // null settings prohibit the create-world branch if the existing save vanishes.
                        client.launchIntegratedServer(folder, name, null);
                        launched[0] = client.getIntegratedServer();
                        if (launched[0] == null || !folder.equals(launched[0].getFolderName()))
                            throw new BridgeException("lifecycle_failed", "World startup returned without the requested integrated server; inspect the client before retrying.");
                    },
                    () -> {
                        if (client.getIntegratedServer() != launched[0])
                            throw new BridgeException("stale_world", "The integrated server changed while the requested world was joining.");
                        return client.theWorld != null && client.thePlayer != null && WorldLifecycle.integrated(client);
                    });
            result.addProperty("folder", folder);
            return result;
        }
        final boolean recoveryExit = "client.exit".equals(operation) && startupRecovery.restartRequired()
                && initialWorld == null && initialPlayer == null;
        if (initialWorld != null) WorldLifecycle.requireCurrent(client, args);
        else if (!recoveryExit && (initialServer != null || !WorldLifecycle.mainMenu(initialScreen)))
            throw new BridgeException("invalid_state", "An unloaded client must be at the normal main menu with no integrated server before exit.");
        if ("client.leave_world".equals(operation) && initialWorld == null)
            throw new BridgeException("invalid_state", "There is no joined world to leave.");
        return lifecycle.schedule(operation, sessionId, control,
                () -> client.theWorld == initialWorld && client.getIntegratedServer() == initialServer && client.currentScreen == initialScreen
                        && client.getNetHandler() == initialHandler && client.thePlayer == initialPlayer
                        && (initialWorld != null || recoveryExit || WorldLifecycle.mainMenu(client.currentScreen)),
                () -> {
                    if (initialWorld != null) WorldLifecycle.requireCurrent(client, args);
                    releaseInput();
                    background.requireLifecycle(client);
                    if (initialWorld != null) WorldLifecycle.leave(client);
                    if ("client.exit".equals(operation)) {
                        if (client.theWorld != null || client.thePlayer != null || (!recoveryExit && client.getIntegratedServer() != null))
                            throw new BridgeException("lifecycle_failed", "Normal world unload did not complete; client shutdown was not requested.");
                        background.restoreForExit(client, this::releaseInput);
                        client.shutdown();
                    }
                },
                () -> client.theWorld == null && client.getIntegratedServer() == null && WorldLifecycle.mainMenu(client.currentScreen));
    }

    /** Current client owning-thread observation only; no server ticket, save scan or future readiness promise. */
    private JsonObject readiness(Minecraft client) {
        observeStartup(client);
        boolean worldLoaded = client.theWorld != null;
        boolean playerPresent = client.thePlayer != null;
        boolean paused = client.isGamePaused();
        IntegratedServer server = client.getIntegratedServer();
        boolean local = WorldLifecycle.integrated(client);
        boolean healthy = playerPresent && !client.thePlayer.isDead && client.thePlayer.getHealth() > 0;
        boolean consistent = worldLoaded && playerPresent && client.thePlayer.worldObj == client.theWorld
                && client.theWorld.provider != null && client.thePlayer.dimension == client.theWorld.provider.dimensionId;
        ServerContext context = serverContext;
        boolean matches = consistent && local && context != null && context.server == server
                && context.dimension == client.thePlayer.dimension && context.playerId.equals(client.thePlayer.getUniqueID())
                && context.paused == paused && context.screenIdentity == client.currentScreen;
        JsonObject result = ClientReadiness.healthReadiness(profile, worldLoaded, playerPresent, healthy, consistent, server != null, local, matches,
                matches && context.developer, paused, lifecycle.active(), WorldLifecycle.mainMenu(client.currentScreen), background.active(client));
        if (!local && server == null) ClientReadiness.remoteReadiness(result, client.getNetHandler() != null,
                RemoteReadClient.INSTANCE.operatorReady(), "developer".equals(profile));
        result.addProperty("sessionId", sessionId);
        result.addProperty("profile", profile);
        result.addProperty("integratedServerPublic", local && server.getPublic());
        result.addProperty("screenKind", ScreenSemantics.kind(client.currentScreen));
        ClientReadiness.menuReadiness(result, client.currentScreen != null);
        result.addProperty("source", "current_client_owning_thread; context and identity from existing tick observations");
        if (playerPresent) result.addProperty("playerId", client.thePlayer.getUniqueID().toString());
        JsonObject world = new JsonObject();
        if (worldLoaded && client.theWorld.provider != null) world.addProperty("dimension", client.theWorld.provider.dimensionId);
        ServerIdentity identity = serverIdentity;
        if (matches && identity != null && identity.server == server) {
            world.addProperty("worldName", identity.name); world.addProperty("worldFolder", identity.folder);
            world.addProperty("identitySource", "integrated_server_tick_observation");
        } else if (worldLoaded && client.theWorld.getWorldInfo() != null) {
            world.addProperty("worldName", JsonData.bounded(client.theWorld.getWorldInfo().getWorldName(), 512));
            world.addProperty("identitySource", "current_client_world");
        }
        result.add("world", world);
        startupRecovery.readiness(result, lifecycle.active(), StartupScreenRecovery.startupScreen(client.currentScreen));
        if ("game_over".equals(ScreenSemantics.kind(client.currentScreen)) && !lifecycle.active()) {
            result.addProperty("stage", "game_over"); result.addProperty("nextTool", "mcb_gui");
            result.addProperty("nextToolCondition", "Inspect the current native game-over screen and its enabled Respawn control; do not retry world actions while the player is dead.");
        }
        return result;
    }

    /** Native client readiness does not require a server-side MCB handshake or command privileges. */
    private JsonObject health(Minecraft client) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", 1);
        result.addProperty("modVersion", Tags.VERSION);
        result.add("operationPolicy", policyStatus(client));
        result.addProperty("gameVersion", "1.7.10");
        result.addProperty("sessionId", sessionId);
        result.addProperty("instancePath", ClientBootstrap.instancePath());
        result.add("executingBridge", LoadedModEvidence.bridge());
        result.addProperty("profile", profile);
        result.addProperty("clientTicks", clientTicks);
        result.addProperty("developerAvailable", serverContext != null || RemoteReadClient.INSTANCE.operatorReady());
        result.add("serverBridge",RemoteReadClient.INSTANCE.status());
        JsonObject readiness=readiness(client);
        if(!client.isSingleplayer() && RemoteReadClient.INSTANCE.operatorReady()) {
            readiness.addProperty("qaExecutionSupported",RemoteReadClient.INSTANCE.qaReady());
        }
        result.add("readiness", readiness);
        result.add("lifecycle", lifecycle.inspect());
        result.add("lifecycleTarget", WorldLifecycle.target(client));
        result.add("background", background.inspect(client));
        BridgeCapabilities.append(result, activity.logStatus(), scanSettings, keyBindings(client).keySet(),
                client.theWorld != null && client.thePlayer != null,
                "developer".equals(profile) && serverContext != null && policies.permits("machineRead"),
                !WorldLifecycle.integrated(client) && "developer".equals(profile) && RemoteReadClient.INSTANCE.operatorReady() && policies.permits("machineRead"));
        return result;
    }

    private JsonObject diagnostics(Minecraft client) {
        JsonObject result = new JsonObject();
        result.add("readiness", readiness(client));
        result.add("clientSettings", VanillaSettingsControls.settings(client));
        result.add("displayState", ClientDisplayState.inspect(client));
        result.addProperty("sessionId", sessionId);
        result.addProperty("clientTicks", clientTicks);
        result.addProperty("focused", client.inGameHasFocus);
        result.addProperty("paused", client.isGamePaused());
        result.addProperty("worldLoaded", client.theWorld != null);
        result.add("lifecycle", lifecycle.inspect());
        result.add("lifecycleTarget", WorldLifecycle.target(client));
        result.add("background", background.inspect(client));
        result.addProperty("screen", client.currentScreen == null ? "" : client.currentScreen.getClass().getName());
        JsonObject runtime = new JsonObject();
        runtime.addProperty("javaVersion", JsonData.bounded(System.getProperty("java.version"), 128));
        runtime.addProperty("javaVendor", JsonData.bounded(System.getProperty("java.vendor"), 128));
        runtime.addProperty("osName", JsonData.bounded(System.getProperty("os.name"), 128));
        runtime.addProperty("osArch", JsonData.bounded(System.getProperty("os.arch"), 128));
        runtime.addProperty("osVersion", JsonData.bounded(System.getProperty("os.version"), 128));
        result.add("runtime", runtime);
        Runtime vm = Runtime.getRuntime();
        JsonObject heap = new JsonObject();
        heap.addProperty("usedBytes", vm.totalMemory() - vm.freeMemory());
        heap.addProperty("committedBytes", vm.totalMemory());
        heap.addProperty("maxBytes", vm.maxMemory());
        heap.add("gc", JvmMemoryDiagnostics.inspect());
        result.add("heap", heap);
        if (lastFrameSample != 0) {
            result.addProperty("fps", measuredFps);
            result.addProperty("fpsSampleAgeMillis", Math.max(0, System.nanoTime() - lastFrameSample) / 1000000L);
        } else {
            result.add("fps", JsonNull.INSTANCE);
            result.add("fpsSampleAgeMillis", JsonNull.INSTANCE);
        }
        result.addProperty("fpsSource", "RenderTick END events per elapsed wall second; sampled approximately once per second.");
        result.addProperty("agentRenderPumpEligible", renderClientReady(client));
        result.add("guiPointer", LogicalGuiPointer.inspect());
        result.addProperty("agentRenderPumpPolicy", "One queued client request per RenderTick END while a stable local developer world has full background protection, no lifecycle ticket and no held synthetic input; original client/server tick drains and scan budgets remain unchanged.");
        result.addProperty("loadedModCount", Loader.instance().getActiveModList().size());
        ServerContext context = serverContext;
        result.add("serverTicks", serverSamples.inspect(context == null ? null : context.server, client.isGamePaused(), System.nanoTime()));
        result.addProperty("playerCoordinates", "snapshot.player.y is legacy client posY; feetY is boundingBox.minY, eyeY is getPosition(1F).yCoord. dev.teleport uses authoritative server feet coordinates.");
        return result;
    }

    private JsonObject snapshot(Minecraft client) {
        observeStartup(client);
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", sessionId);
        result.addProperty("profile", profile);
        result.addProperty("clientTicks", clientTicks);
        result.addProperty("focused", client.inGameHasFocus);
        result.addProperty("paused", client.isGamePaused());
        result.addProperty("screen", client.currentScreen == null ? "" : client.currentScreen.getClass().getName());
        result.addProperty("screenId", identifyScreen(client.currentScreen));
        ScreenSemantics.describe(result, client.currentScreen);
        result.addProperty("worldLoaded", client.theWorld != null);
        result.add("lifecycle", lifecycle.inspect());
        result.add("clientRecovery", startupRecovery.inspect());
        result.add("lifecycleTarget", WorldLifecycle.target(client));
        result.add("background", background.inspect(client));
        if (client.theWorld != null) {
            JsonObject world = new JsonObject();
            world.addProperty("dimension", client.theWorld.provider.dimensionId);
            world.addProperty("time", client.theWorld.getWorldTime());
            world.addProperty("singleplayer", client.isSingleplayer());
            ServerIdentity identity = serverIdentity;
            ServerContext context = serverContext;
            if (context != null && identity != null && identity.server == context.server) {
                world.addProperty("worldName", identity.name);
                world.addProperty("worldFolder", identity.folder);
                world.addProperty("identitySource", "integrated_server");
            } else {
                world.addProperty("worldName", JsonData.bounded(client.theWorld.getWorldInfo().getWorldName(), 512));
                world.addProperty("identitySource", "client");
                JsonObject remote=RemoteReadClient.INSTANCE.worldIdentity();
                if(!client.isSingleplayer() && remote!=null) {
                    world.add("worldName",remote.get("worldName")); world.add("worldFolder",remote.get("worldFolder"));
                    world.addProperty("identitySource","remote_server_handshake");
                }
            }
            result.add("world", world);
        }
        if (client.thePlayer != null) {
            EntityPlayer player = client.thePlayer;
            JsonObject state = new JsonObject();
            state.addProperty("name", player.getCommandSenderName());
            state.addProperty("x", player.posX);
            state.addProperty("y", player.posY);
            state.addProperty("feetY", player.boundingBox.minY);
            state.addProperty("motionX", player.motionX); state.addProperty("motionY", player.motionY); state.addProperty("motionZ", player.motionZ);
            state.addProperty("nativeMoveForward", player.moveForward); state.addProperty("nativeMoveStrafing", player.moveStrafing);
            state.addProperty("eyeY", player.getPosition(1F).yCoord);
            state.addProperty("z", player.posZ);
            state.addProperty("yaw", player.rotationYaw);
            state.addProperty("pitch", player.rotationPitch);
            state.addProperty("health", player.getHealth());
            state.addProperty("food", player.getFoodStats().getFoodLevel());
            state.addProperty("onGround", player.onGround);
            state.addProperty("creative", player.capabilities.isCreativeMode);
            state.addProperty("sneaking", player.isSneaking());
            state.addProperty("flying", player.capabilities.isFlying);
            state.addProperty("allowFlying", player.capabilities.allowFlying);
            state.addProperty("flySpeed", player.capabilities.getFlySpeed());
            state.add("serverFlight",client.isSingleplayer() ? flightObservation(client) : RemoteReadClient.INSTANCE.poseObservation());
            state.addProperty("selectedHotbarSlot", player.inventory.currentItem);
            result.add("player", state);
        }
        result.add("serverControl", serverControlObservation(client));
        result.add("serverBridge",RemoteReadClient.INSTANCE.status());
        result.add("flightCapability", flightCapability.observe(client.thePlayer, sessionId, result));
        MovingObjectPosition target = client.objectMouseOver;
        if (target != null) {
            JsonObject hit = new JsonObject();
            hit.addProperty("type", target.typeOfHit.name().toLowerCase(java.util.Locale.ROOT));
            if (target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                hit.addProperty("x", target.blockX);
                hit.addProperty("y", target.blockY);
                hit.addProperty("z", target.blockZ);
                hit.addProperty("side", target.sideHit);
            } else if (target.entityHit != null) {
                hit.addProperty("entityId", target.entityHit.getEntityId());
                hit.addProperty("entityClass", target.entityHit.getClass().getName());
            }
            result.add("target", hit);
        }
        result.addProperty("inputTicksRemaining", remainingInputTicks);
        JsonObject taskQuery = new JsonObject(); taskQuery.addProperty("expectedSessionId", sessionId);
        result.add("inputTask", inputTask(taskQuery));
        JsonArray inputKeys = new JsonArray();
        for (String key : held.keySet()) inputKeys.add(new JsonPrimitive(key));
        result.add("inputKeys", inputKeys);
        result.add("inputDiagnostics", inputDiagnosticObservation(client));
        return result;
    }

    private JsonObject gui(Minecraft client) {
        observeStartup(client);
        JsonObject result = new JsonObject();
        GuiScreen screen = client.currentScreen;
        result.addProperty("screen", screen == null ? "" : screen.getClass().getName());
        result.addProperty("screenId", identifyScreen(screen));
        ScreenSemantics.describe(result, screen);
        result.add("clientRecovery", startupRecovery.inspect());
        if (StartupScreenRecovery.startupScreen(screen)) result.add("startupQuery", StartupScreenRecovery.describe(screen));
        result.addProperty("width", screen == null ? client.displayWidth : screen.width);
        result.addProperty("height", screen == null ? client.displayHeight : screen.height);
        JsonArray buttonData = new JsonArray();
        if (screen != null) {
            for (Object entry : nativeButtons(screen)) {
                if (!(entry instanceof GuiButton) || buttonData.size() >= 128) continue;
                GuiButton button = (GuiButton) entry;
                if (!button.visible) continue;
                JsonObject state = new JsonObject();
                state.addProperty("id", button.id);
                state.addProperty("text", JsonData.bounded(button.displayString, 512));
                state.addProperty("x", button.xPosition);
                state.addProperty("y", button.yPosition);
                state.addProperty("width", button.width);
                state.addProperty("height", button.height);
                state.addProperty("enabled", button.enabled);
                buttonData.add(state);
            }
        }
        result.add("buttons", buttonData);
        result.addProperty("buttonSource", "Current actual GuiScreen buttonList using fixed MCP/SRG field mapping, with Forge-init capture as fallback; unrelated custom widget systems may expose no buttons.");
        if (screen instanceof GuiContainer) {
            Container container = ((GuiContainer) screen).inventorySlots;
            result.addProperty("windowId", container.windowId);
            result.addProperty("containerClass", container.getClass().getName());
            result.addProperty("containerSource", "current_client_gui");
            if (client.thePlayer != null) {
                result.add("cursorItem", JsonData.item(client.thePlayer.inventory.getItemStack()));
                result.addProperty("cursorSource", "current_client_player_inventory");
                result.addProperty("openContainerMatches", client.thePlayer.openContainer == container);
                result.addProperty("playerOpenWindowId", client.thePlayer.openContainer == null ? -1 : client.thePlayer.openContainer.windowId);
            }
            result.addProperty("totalSlots", container.inventorySlots.size());
            result.addProperty("slotCoordinates", "relative to container origin");
            JsonArray slots = new JsonArray();
            MachineSlotBinding.Context machineBindings = MachineSlotBinding.observe(client, container);
            result.add("wholeStackTransfer", machineBindings.wholeStackCapability());
            for (int i = 0; i < Math.min(container.inventorySlots.size(), 512); i++) {
                Slot slot = (Slot) container.inventorySlots.get(i);
                JsonObject state = new JsonObject();
                state.addProperty("slot", slot.slotNumber);
                state.addProperty("nativeContainerIndex", i);
                state.addProperty("slotClass", slot.getClass().getName());
                state.addProperty("inventoryIndex", slot.getSlotIndex());
                BaublesInventoryAdapter.describeSlot(state, slot);
                if (!(screen instanceof GuiContainerCreative)) {
                    PlayerSlotBinding.describe(state, slot, client.thePlayer == null ? null : client.thePlayer.inventory);
                    machineBindings.describe(state, slot);
                }
                state.addProperty("x", slot.xDisplayPosition);
                state.addProperty("y", slot.yDisplayPosition);
                state.add("inventoryClass", slot.inventory == null ? JsonNull.INSTANCE : new JsonPrimitive(slot.inventory.getClass().getName()));
                state.add("item", JsonData.item(slot.getStack()));
                SlotAcceptance.describe(state, slot, client.thePlayer == null ? null : client.thePlayer.inventory.getItemStack());
                if (ExpandedGuiApi.is(slot, "appeng.container.slot.SlotRestrictedInput")) {
                    try {
                        Object restriction = ExpandedGuiApi.call(slot, "getItemType");
                        if (restriction instanceof Enum<?>) state.addProperty("restrictionType", ((Enum<?>) restriction).name());
                    } catch (BridgeException error) { state.addProperty("restrictionUnavailable", error.code); }
                }
                slots.add(state);
            }
            result.add("slots", slots);
            result.addProperty("truncated", container.inventorySlots.size() > 512);
        }
        return GuiDataAdapter.inspect(client, screen, result,
                nativeButtons(screen));
    }

    private JsonObject observeDiff(Minecraft client, JsonObject args) {
        long started = System.nanoTime();
        JsonObject current = new JsonObject();
        for (String section : stateDiff.sections(args)) {
            switch (section) {
                case "player":
                    JsonObject observed = snapshot(client);
                    current.add("player", observed.has("player") ? observed.get("player") : JsonNull.INSTANCE);
                    break;
                case "inventory":
                    if (client.thePlayer == null) current.add("inventory", JsonNull.INSTANCE);
                    else {
                        JsonObject inventory = JsonData.inventory(client.thePlayer.inventory);
                        inventory.addProperty("playerId", client.thePlayer.getUniqueID().toString());
                        inventory.addProperty("selectedHotbarSlot", client.thePlayer.inventory.currentItem);
                        inventory.add("cursorItem", JsonData.item(client.thePlayer.inventory.getItemStack()));
                        current.add("inventory", inventory);
                    }
                    break;
                case "gui": current.add("gui", withoutTimers(gui(client))); break;
                case "messages": current.add("messages", messages.inspect(new JsonObject(), sessionId)); break;
                default: throw new BridgeException("invalid_args", "Unknown state section.");
            }
        }
        long captureNanos = System.nanoTime() - started;
        JsonObject result = stateDiff.observe(args, current, client.theWorld, profile, sessionId, clientTicks);
        result.addProperty("captureNanos", captureNanos);
        result.getAsJsonObject("coverage").addProperty("timers", "Volatile GUI budget/timing fields are omitted from comparisons; captureNanos is reported separately.");
        return result;
    }

    private JsonElement withoutTimers(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!"budget".equals(entry.getKey()) && !"timing".equals(entry.getKey()) && !"elapsedNanos".equals(entry.getKey()))
                    result.add(entry.getKey(), withoutTimers(entry.getValue()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray()) result.add(withoutTimers(entry));
            return result;
        }
        return value;
    }

    private EntityPlayer operationPlayer(boolean developer) {
        if (!developer) {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); return client.thePlayer;
        }
        ServerContext context = requireDeveloperContext();
        for (Object entry : context.server.getConfigurationManager().playerEntityList) {
            if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) return (EntityPlayerMP) entry;
        }
        throw new BridgeException("world_unavailable", "The authoritative player is unavailable.");
    }

    @com.google.common.annotations.VisibleForTesting
    public static void finishNeiPreviewControl(JsonObject result) {
        boolean invalidate = result.get("currentScreenChanged").getAsBoolean()
                || !("set_camera".equals(JsonData.string(result, "action")) || "unchanged".equals(JsonData.string(result, "status")));
        if (invalidate) NeiViewAdapter.invalidateRendered();
        result.addProperty("requiresViewRefresh", invalidate);
        result.addProperty("contextPreserved", !invalidate);
    }

    private JsonObject controlResult(Minecraft client, JsonObject args, JsonObject result) {
        result.addProperty("sessionId", sessionId);
        result.addProperty("previousScreenId", JsonData.string(args, "screenId"));
        result.addProperty("screenId", identifyScreen(client.currentScreen));
        result.addProperty("screen", client.currentScreen == null ? "" : client.currentScreen.getClass().getName());
        return ScreenSemantics.describe(result, client.currentScreen);
    }

    private void requireCurrentContainer(Minecraft client, GuiScreen screen) {
        if (screen instanceof GuiContainer) {
            requirePlayer(client);
            Container container = ((GuiContainer) screen).inventorySlots;
            if (container == null || client.thePlayer.openContainer != container || client.thePlayer.openContainer.windowId != container.windowId)
                throw new BridgeException("stale_container", "The current native container has not synchronized or was replaced.");
        }
    }

    private World operationWorld(JsonObject args, boolean developer) {
        World world;
        if (developer) {
            ServerContext context = requireDeveloperContext();
            int dimension = args.has("dimension") ? JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) : context.dimension;
            world = DimensionManager.getWorld(dimension);
            if (world == null) throw new BridgeException("world_unavailable", "Requested dimension is not loaded.");
        } else {
            Minecraft client = Minecraft.getMinecraft();
            requirePlayer(client);
            world = client.theWorld;
            if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != world.provider.dimensionId)
                throw new BridgeException("permission_denied", "Player profile can inspect only its current client dimension.");
        }
        return world;
    }

    private ServerContext requireDeveloperContext() {
        ServerContext context = serverContext;
        if (!"developer".equals(profile) || context == null || !context.developer || context.server != MinecraftServer.getServer())
            throw new BridgeException("permission_denied", "Developer operations require the current owned integrated world.");
        return context;
    }

    private JsonObject worldOperation(String operation, JsonObject args, boolean developer, GameQueue.Control control) {
        JsonObject selector = args;
        if ("world.scan".equals(operation) && args.has("cursor")) {
            selector = new JsonObject();
            selector.addProperty("dimension", (developer ? serverScans : clientScans).cursorDimension(args));
        }
        World world = operationWorld(selector, developer);
        if ("world.entities".equals(operation) || "world.spatial".equals(operation)) {
            EntityPlayer player = operationPlayer(developer);
            if (player.worldObj != world && ("world.entities".equals(operation) && !args.has("x")
                    || "world.spatial".equals(operation) && "raycast".equals(JsonData.string(args, "mode")) && !args.has("from")))
                throw new BridgeException("invalid_args", "An explicit center or ray is required outside the player's dimension.");
            return "world.entities".equals(operation) ? WorldObservationsAdapter.entities(world, player, args, scanSettings)
                    : WorldObservationsAdapter.spatial(world, player, args);
        }
        if ("world.inspect_many".equals(operation)) return inspectMany(world, args, developer);
        if ("world.scan".equals(operation)) {
            int[] center = null;
            if (!args.has("cursor") && !args.has("x") && !args.has("bounds")) {
                if (developer) {
                    ServerContext context = requireDeveloperContext();
                    EntityPlayerMP player = null;
                    for (Object entry : context.server.getConfigurationManager().playerEntityList) {
                        if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) {
                            player = (EntityPlayerMP) entry;
                            break;
                        }
                    }
                    if (player == null || player.worldObj == null) throw new BridgeException("world_unavailable", "The authoritative server player is unavailable.");
                    if (player.dimension == world.provider.dimensionId)
                        center = new int[] {(int) Math.floor(player.posX), (int) Math.floor(player.boundingBox.minY), (int) Math.floor(player.posZ)};
                } else {
                    EntityPlayer player = Minecraft.getMinecraft().thePlayer;
                    center = new int[] {(int) Math.floor(player.posX), (int) Math.floor(player.boundingBox.minY), (int) Math.floor(player.posZ)};
                }
            }
            return (developer ? serverScans : clientScans).page(new WorldScan.MinecraftAccess(world, operationPlayer(developer)), args, scanSettings, scanEpoch, center, control);
        }
        int x = JsonData.integer(args, "x", -30000000, 30000000);
        int y = JsonData.integer(args, "y", 0, 255);
        int z = JsonData.integer(args, "z", -30000000, 30000000);
        if ("world.inspect".equals(operation)) return aeDetails(world, x, y, z, developer, args, block(world, x, y, z, developer));
        throw new BridgeException("unsupported", "World operation is not implemented.");
    }

    private JsonObject inspectManyPlayerInventory(Minecraft client) {
        return inspectManyPlayerInventory(client, false);
    }

    private JsonObject inspectManyPlayerInventory(Minecraft client, boolean allowPendingZero) {
        requirePlayer(client);
        if (client.thePlayer.worldObj != client.theWorld)
            throw new BridgeException("inventory_observation_unavailable", "The current player and client world identities differ.");
        JsonObject inventory = InspectManyPlayerInventory.read(client.thePlayer.inventory, client.thePlayer.inventory.currentItem, client.thePlayer.inventory.getItemStack(), allowPendingZero);
        inventory.addProperty("source", "client"); inventory.addProperty("inventorySource", "current_player_inventory");
        inventory.addProperty("playerInventoryBindingVerified", true); inventory.addProperty("playerId", client.thePlayer.getUniqueID().toString());
        inventory.addProperty("sessionId", sessionId); inventory.addProperty("clientTicks", clientTicks);
        inventory.addProperty("dimension", client.theWorld.provider.dimensionId); inventory.addProperty("worldTick", client.theWorld.getTotalWorldTime());
        return inventory;
    }

    private JsonObject inspectMany(World world, JsonObject args, boolean developer) {
        return WorldInspectionAdapter.inspectMany(world, args, developer);
    }
    private static boolean clientRecipeQuery(JsonObject args) {
        if (args.has("mode") && !"catalog".equals(CatalogData.text(args, "mode", 32, ""))) return false;
        return args.has("map") || args.has("cursor") && CatalogData.text(args, "cursor", 64, "").startsWith("client:");
    }

    /** Public NEI-visible registry catalog only. It never reaches a server tile or invokes backend matching. */
    private JsonObject clientRecipeCatalog(JsonObject args) {
        Minecraft client = Minecraft.getMinecraft(); requirePlayer(client);
        if (args.has("dimension") && JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE) != client.theWorld.provider.dimensionId)
            throw new BridgeException("stale_query", "Client recipe catalog belongs only to the current client dimension.");
        JsonObject query = new com.google.gson.JsonParser().parse(args.toString()).getAsJsonObject();
        if (query.has("cursor")) {
            String cursor = JsonData.string(query, "cursor");
            if (!cursor.startsWith("client:")) throw new BridgeException("stale_query", "This is not a client-catalog cursor.");
            query.addProperty("cursor", cursor.substring(7));
        }
        JsonObject result = clientRecipes.read(client.theWorld, sessionId, client.theWorld.provider.dimensionId, client.thePlayer.inventory,
                (x, y, z) -> { throw new BridgeException("permission_denied", "The public client catalog does not read machine coordinates or execute a backend match."); }, query, System.nanoTime());
        if (result.has("nextCursor") && !result.get("nextCursor").isJsonNull()) result.addProperty("nextCursor", "client:" + result.get("nextCursor").getAsString());
        result.addProperty("sessionId", sessionId); result.addProperty("source", "client_runtime_public_nei_registry");
        result.addProperty("dimension", client.theWorld.provider.dimensionId); result.addProperty("worldTick", client.theWorld.getTotalWorldTime());
        result.addProperty("authoritativeServerObservation", false); result.addProperty("inventorySource", "current_client_inventory");
        return result;
    }

    private EntityPlayerMP aeOrderPlayer(JsonObject args) {
        ServerContext context = requireDeveloperContext();
        requireExpectedWorld(context, args);
        if (!sessionId.equals(JsonData.string(args, "expectedSessionId"))) throw new BridgeException("stale_session", "Order requires the current exact session.");
        EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
        if (player.isDead || player.dimension != JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE))
            throw new BridgeException("world_unavailable", "Order player or dimension changed.");
        return player;
    }

    private JsonObject developerOperation(String operation, JsonObject args) {
        ServerContext context = requireDeveloperContext();
        if (AeOrderAdapter.OPERATION.equals(operation)) return aeOrders.perform(aeOrderPlayer(args), args);
        if (MachineObservationCapture.OPERATION.equals(operation)) {
            requireExpectedWorld(context, args);
            if (!sessionId.equals(JsonData.string(args, "expectedSessionId"))) throw new BridgeException("stale_session", "Machine capture requires the current exact client session.");
            World world = operationWorld(args, true);
            return machineCaptures.read(world, sessionId, context.server.getFolderName(), args);
        }
        if (BaublesInventoryAdapter.READ.equals(operation)) {
            JsonObject accessories = BaublesInventoryAdapter.inspect((EntityPlayerMP) operationPlayer(true), args);
            accessories.addProperty("sessionId", sessionId);
            accessories.addProperty("worldFolder", context.server.getFolderName());
            return accessories;
        }
        if ("inventory.supply".equals(operation)) {
            requireExpectedWorld(context, args);
            if (!sessionId.equals(JsonData.string(args, "expectedSessionId")))
                throw new BridgeException("stale_session", "Supply requires the current exact session.");
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (player.isDead || player.dimension != context.dimension)
                throw new BridgeException("world_unavailable", "The current authoritative player is unavailable.");
            JsonObject result = NativeSupplyAdapter.supply(args, new NativeSupplyAdapter.Access() {
                public String playerName() { return player.getCommandSenderName(); }
                public boolean creative() { return player.capabilities.isCreativeMode; }
                public boolean inventoryEmpty() {
                    for (int i = 0; i < player.inventory.getSizeInventory(); i++)
                        if (player.inventory.getStackInSlot(i) != null) return false;
                    return true;
                }
                public boolean cursorEmpty() { return player.inventory.getItemStack() == null; }
                public boolean fits(JsonArray items) {
                    ItemStack[] requested = new ItemStack[items.size()];
                    for (int i = 0; i < requested.length; i++) {
                        JsonObject item = items.get(i).getAsJsonObject();
                        requested[i] = new ItemStack((Item) Item.itemRegistry.getObject(JsonData.string(item, "registryName")),
                                item.get("count").getAsInt(), item.get("metadata").getAsInt());
                    }
                    return InventoryCapacity.fits(player.inventory.mainInventory, requested, player.inventory.getInventoryStackLimit());
                }
                public int stackLimit(String registry, int metadata) {
                    Object item = Item.itemRegistry.getObject(registry);
                    return item instanceof Item && registry.equals(Item.itemRegistry.getNameForObject(item))
                            ? new ItemStack((Item) item, 1, metadata).getMaxStackSize() : 0;
                }
                public int execute(String command) { return context.server.getCommandManager().executeCommand(player, command); }
            });
            result.addProperty("sessionId", sessionId); result.addProperty("playerName", player.getCommandSenderName());
            result.addProperty("dimension", player.dimension); return result;
        }
        if (operation.startsWith("qa.")) {
            if ("qa.run".equals(operation) || "qa.command".equals(operation) || "qa.cancel".equals(operation)) requireExpectedWorld(context, args);
            EntityPlayerMP player = null;
            for (Object entry : context.server.getConfigurationManager().playerEntityList) {
                if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) {
                    player = (EntityPlayerMP) entry;
                    break;
                }
            }
            if (player == null || player.isDead) throw new BridgeException("world_unavailable", "The authoritative server player is unavailable.");
            return qa.perform(operation, context.server, player, sessionId, args, QaPolicy.context(policies));
        }
        if ("dev.teleport".equals(operation)) return teleport(context, args);
        if ("machine.set_working".equals(operation)) requireExpectedWorld(context, args);
        World world = operationWorld(args, true);
        if ("recipes.query".equals(operation)) {
            EntityPlayerMP player = null;
            for (Object entry : context.server.getConfigurationManager().playerEntityList) {
                if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) {
                    player = (EntityPlayerMP) entry; break;
                }
            }
            if (player == null || player.isDead) throw new BridgeException("world_unavailable", "The authoritative server player is unavailable.");
            JsonObject result = recipes.read(world, sessionId, world.provider.dimensionId, player.inventory, (x, y, z) -> {
                if (new WorldScan.MinecraftAccess(world).section(x >> 4, y >> 4, z >> 4) == WorldScan.UNKNOWN)
                    throw new BridgeException("chunk_unloaded", "Recipe machine query requires an existing loaded chunk.");
                Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
                return chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
            }, args, System.nanoTime());
            result.addProperty("sessionId", sessionId); result.addProperty("source", "integrated_server");
            result.addProperty("dimension", world.provider.dimensionId); result.addProperty("worldTick", world.getTotalWorldTime());
            return result;
        }
        if ("world.tiles".equals(operation)) return DeveloperMachineTools.tiles(world, args);
        if ("machine.inspect".equals(operation)) {
            int x = JsonData.integer(args, "x", -30000000, 30000000);
            int y = JsonData.integer(args, "y", 0, 255);
            int z = JsonData.integer(args, "z", -30000000, 30000000);
            if (new WorldScan.MinecraftAccess(world).section(x >> 4, y >> 4, z >> 4) == WorldScan.UNKNOWN)
                throw new BridgeException("chunk_unloaded", "Machine query requires an existing loaded chunk.");
            Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            Object tile = chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
            JsonObject machine = MachineDataAdapter.inspect(world, tile instanceof TileEntity ? (TileEntity) tile : null, args);
            machine.addProperty("sessionId", sessionId); machine.addProperty("source", "integrated_server");
            machine.addProperty("worldTick", world.getTotalWorldTime()); machine.addProperty("nonAtomic", true);
            return machine;
        }
        if ("world.structure".equals(operation)) {
            JsonObject structure = structures.inspect(world, args);
            if (GuiDataAdapter.booleanArg(args, "includeSurvivalInventory", false)) {
                JsonObject runtime = structure.has("runtimeObservation") && structure.get("runtimeObservation").isJsonObject()
                        ? structure.getAsJsonObject("runtimeObservation") : new JsonObject();
                if (!args.has("captureId") || !runtime.has("captureId") || !args.get("captureId").equals(runtime.get("captureId"))
                        || !runtime.has("contextMatches") || !runtime.get("contextMatches").getAsBoolean()
                        || !runtime.has("capturedVisitsComplete") || !runtime.get("capturedVisitsComplete").getAsBoolean())
                    throw new BridgeException("stale_capture", "Finite inventory reads require the exact complete current controller capture.");
                EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
                if (player == null || player.isDead || player.worldObj != world || !context.playerId.equals(player.getUniqueID()))
                    throw new BridgeException("stale_world", "The captured controller and finite inventory must belong to the same actual local actor/world.");
                JsonObject inventory = new SurvivalConstructionAdapter.Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack()).json();
                inventory.addProperty("playerId", player.getUniqueID().toString()); inventory.addProperty("sessionId", sessionId); inventory.addProperty("dimension", player.dimension);
                structure.add("serverPlayerInventory", inventory);
            }
            structure.addProperty("sessionId", sessionId);
            structure.addProperty("source", "integrated_server");
            structure.addProperty("dimension", world.provider.dimensionId);
            structure.addProperty("worldTick", world.getTotalWorldTime());
            structure.addProperty("nonAtomic", true);
            return structure;
        }
        if ("machine.set_working".equals(operation)) return DeveloperMachineTools.setWorking(world, args);
        throw new BridgeException("unsupported", "Developer operation is not implemented.");
    }

    private void requireExpectedWorld(ServerContext context, JsonObject args) {
        String expected = CatalogData.text(args, "expectedWorld", 128, null);
        if (expected.isEmpty()) throw new BridgeException("invalid_args", "expectedWorld must not be empty.");
        if (!expected.equals(context.server.getFolderName()) && !expected.equals(context.server.getWorldName()))
            throw new BridgeException("stale_world", "expectedWorld does not match the current local world name or folder.");
    }

    private JsonObject teleport(ServerContext context, JsonObject args) {
        TeleportPolicy.Target target = TeleportPolicy.target(args, context.dimension,
                GuiDataAdapter.booleanArg(args, "changeDimension", false),
                GuiDataAdapter.booleanArg(args, "allowTrackingChunkLoad", false));
        requireExpectedWorld(context, args);
        if (args.has("expectedSessionId") && !sessionId.equals(JsonData.string(args, "expectedSessionId")))
            throw new BridgeException("stale_session", "Teleport expectedSessionId changed before server positioning.");
        EntityPlayerMP player = null;
        for (Object entry : context.server.getConfigurationManager().playerEntityList) {
            if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) {
                player = (EntityPlayerMP) entry;
                break;
            }
        }
        if (player == null || player.isDead || player.playerNetServerHandler == null)
            throw new BridgeException("world_unavailable", "The client player's authoritative server entity is unavailable.");
        if (player.dimension != context.dimension) throw new BridgeException("stale_world", "Server player dimension changed before teleport.");
        World world = DimensionManager.getWorld(context.dimension);
        if (world == null || world != player.worldObj || world.isRemote)
            throw new BridgeException("world_unavailable", "Current authoritative server dimension is unavailable.");
        TeleportPolicy.requireReady(player.canCommandSenderUseCommand(2, "tp"), context.screenOpen, context.paused,
                System.nanoTime() - context.capturedAt <= 1000000000L, player.openContainer != player.inventoryContainer,
                player.ridingEntity != null || player.riddenByEntity != null,
                target.dimension != context.dimension
                        || world.blockExists((int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z)));
        boolean requireFlying = GuiDataAdapter.booleanArg(args, "requireFlying", false);
        if (target.dimension != context.dimension) {
            JsonObject moved = DeveloperDimensionTravel.transfer(context.server, player, target, requireFlying);
            moved.addProperty("playerId", player.getUniqueID().toString()); return moved;
        }
        JsonObject destination = TeleportSafety.inspect((net.minecraft.world.WorldServer) world, player, target);
        JsonObject before = serverPosition(player);
        JsonObject flightSafety = TeleportSafety.prepare(player, destination, requireFlying);
        player.playerNetServerHandler.setPlayerLocation(target.x, target.y, target.z, player.rotationYaw, player.rotationPitch);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", target.dimension);
        result.addProperty("worldName", JsonData.bounded(context.server.getWorldName(), 128));
        result.addProperty("worldFolder", JsonData.bounded(context.server.getFolderName(), 128));
        result.addProperty("coordinateMeaning", "Authoritative server player feet coordinates (server posY); use before for recovery, not snapshot.player.y.");
        result.add("before", before);
        result.add("after", serverPosition(player));
        result.add("flightSafety", flightSafety);
        result.addProperty("playerId", player.getUniqueID().toString());
        result.addProperty("state", "submitted_client_sync_unverified");
        result.addProperty("clientSyncObserved", false);
        result.addProperty("automaticRetryAllowed", false);
        return result;
    }

    /** HTTP-worker wait; only owning-thread callbacks observe or synchronize the single submitted teleport. */
    private JsonObject dispatchTeleport(JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
        long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(8);
        requireUnpausedServer();
        clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
            if (args.has("expectedSessionId") && !sessionId.equals(JsonData.string(args, "expectedSessionId")))
                throw new BridgeException("stale_session", "Teleport expectedSessionId does not match this client process; no teleport sent.");
            requireExpectedWorld(requireDeveloperContext(), args);
            JsonObject before = snapshot(client), player = before.getAsJsonObject("player"), server = before.getAsJsonObject("serverControl");
            if (!client.thePlayer.capabilities.isCreativeMode) {
                if (!PlayerNavigation.flag(server, "available"))
                    throw new BridgeException("invalid_state", "Survival teleport requires a fresh actual client/server flight observation.");
                JsonObject pose = server.getAsJsonObject("pose"), evidence = before.getAsJsonObject("flightCapability");
                boolean clientFlying = PlayerNavigation.flag(player, "flying"), serverFlying = PlayerNavigation.flag(pose, "flying");
                if ((clientFlying || serverFlying) && (!clientFlying || !serverFlying || PlayerNavigation.flag(player, "onGround")
                        || PlayerNavigation.flag(pose, "onGround") || !evidence.get("tested").getAsBoolean()
                        || evidence.get("canFly").isJsonNull() || !evidence.get("canFly").getAsBoolean()))
                    throw new BridgeException("invalid_state", "Survival airborne teleport requires current tested dual native flight; use flight_check probe first.");
            }
            return null;
        }, teleportRemaining(deadline), control), teleportRemaining(deadline));
        JsonObject result = serverQueue.await(serverQueue.submit(() -> developerOperation("dev.teleport", args),
                teleportRemaining(deadline), control), teleportRemaining(deadline));
        if (result.has("outcomeUnknown") && result.get("outcomeUnknown").getAsBoolean()) return result;
        TeleportPolicy.Target target = TeleportPolicy.target(args, result.get("dimension").getAsInt(), true,
                false);
        boolean flight = result.getAsJsonObject("flightSafety").get("flightRequired").getAsBoolean();
        String playerId = result.get("playerId").getAsString();
        long sample = -1;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            // One normal server queue turn between reads prevents a render-only busy poll from supplying stability.
            serverQueue.await(serverQueue.submit(() -> { captureFlight(serverContext); return null; },
                    teleportRemaining(deadline), control), teleportRemaining(deadline));
            JsonObject current = clientQueue.await(clientQueue.submit(() -> {
                Minecraft client = Minecraft.getMinecraft();
                if (client.thePlayer == null || client.thePlayer.isDead || client.theWorld == null || client.isGamePaused()
                        || client.currentScreen != null || !playerId.equals(client.thePlayer.getUniqueID().toString())
                        || !client.isSingleplayer())
                    throw new BridgeException("stale_world", "Teleport actor/world/GUI changed during synchronization; the action is not replayed.");
                if (client.thePlayer != null && client.theWorld != null && client.thePlayer.dimension == target.dimension
                        && Math.abs(client.thePlayer.posX - target.x) <= .02
                        && Math.abs(client.thePlayer.boundingBox.minY - target.y) <= .02
                        && Math.abs(client.thePlayer.posZ - target.z) <= .02 && flight
                        && client.thePlayer.capabilities.isCreativeMode && client.thePlayer.capabilities.allowFlying
                        && !client.thePlayer.capabilities.isFlying) {
                    // Vanilla may clear the pre-location ability packet while the old position was grounded.
                    client.thePlayer.onGround = false;
                    client.thePlayer.capabilities.isFlying = true;
                    client.thePlayer.motionY = 0;
                    client.thePlayer.sendPlayerAbilities();
                }
                return snapshot(client);
            }, teleportRemaining(deadline), control), teleportRemaining(deadline));
            if (TeleportPolicy.arrived(current, target, flight, sample)
                    && playerId.equals(PlayerNavigation.text(current.getAsJsonObject("serverControl"), "playerId"))) {
                sample = PlayerNavigation.sample(current.getAsJsonObject("serverControl"));
                if (++stable >= 2) {
                    result.addProperty("state", "arrived"); result.addProperty("clientSyncObserved", true);
                    result.addProperty("serverArrivalConfirmed", true); result.addProperty("clientSyncRequired", false);
                    result.addProperty("stableServerSamples", stable); result.addProperty("arrivalSampleId", sample);
                    result.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000.0);
                    result.add("arrivalPlayer", current.get("player")); result.add("arrivalServer", current.get("serverControl"));
                    return result;
                }
            } else stable = 0;
        }
        result.addProperty("state", "submitted_client_sync_unverified"); result.addProperty("outcomeUnknown", true);
        result.addProperty("nextAction", "Inspect a fresh snapshot; the single teleport is not replayed.");
        return result;
    }

    /** A real bounded takeoff test; normal double-jump keys and existing native permission, never an ability grant. */
    private JsonObject dispatchFlightCheck(JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
        ExpandedGuiApi.keys(args, "action", "expectedWorld", "expectedSessionId", "dimension", "timeout_ms");
        String action = ExpandedGuiApi.option(args, "action", "inspect", "inspect", "probe");
        JsonObject before = clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client); requireExpectedWorld(requireDeveloperContext(), args);
            if (!sessionId.equals(JsonData.string(args, "expectedSessionId")) || client.thePlayer == null
                    || client.thePlayer.dimension != JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE))
                throw new BridgeException("stale_world", "Flight check requires the exact current session/player/dimension.");
            return snapshot(client);
        }, 1000, control), 1000);
        JsonObject evidence = before.getAsJsonObject("flightCapability"), result = new JsonObject();
        result.add("capability", evidence); result.addProperty("action", action); result.addProperty("movementDispatched", false);
        if ("inspect".equals(action) || evidence.get("tested").getAsBoolean()) return result;
        JsonObject player = before.getAsJsonObject("player"), request = args.deepCopy(); request.remove("action");
        request.add("x", player.get("x")); request.add("z", player.get("z"));
        request.addProperty("y", player.get("feetY").getAsDouble() + 2);
        request.addProperty("strategy", "ability_flight"); request.addProperty("finishFlying", true);
        JsonObject expected = new JsonObject(); expected.addProperty("version", 1); expected.addProperty("expectedSessionId", sessionId);
        expected.addProperty("expectedProfile", "developer"); expected.add("expectedWorld", args.get("expectedWorld")); expected.addProperty("requireBackground", true);
        GuardedContext.Result moved = dispatchNavigation(new PlayerNavigation.Request(request), control, GuardedContext.parse("player.navigate", expected));
        JsonObject after = clientQueue.await(clientQueue.submit(() -> snapshot(Minecraft.getMinecraft()), 1000, control), 1000);
        result.add("capability", after.get("flightCapability")); result.add("nativeNavigation", moved.data);
        result.addProperty("movementDispatched", true); result.addProperty("posture", "normal_flight_retained_after_probe"); return result;
    }

    private static long teleportRemaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new BridgeException("timeout", "Teleport synchronization deadline expired; inspect actual state, never replay automatically.");
        return remaining;
    }

    private JsonObject serverPosition(EntityPlayerMP player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.posX);
        result.addProperty("y", player.posY);
        result.addProperty("z", player.posZ);
        result.addProperty("yaw", player.rotationYaw);
        result.addProperty("pitch", player.rotationPitch);
        result.addProperty("flying", player.capabilities.isFlying);
        result.addProperty("allowFlying", player.capabilities.allowFlying);
        result.addProperty("onGround", player.onGround);
        result.addProperty("motionX", player.motionX); result.addProperty("motionY", player.motionY); result.addProperty("motionZ", player.motionZ);
        return result;
    }

    private void captureFlight(ServerContext context) {
        if (context == null || context.server != MinecraftServer.getServer()) {
            serverFlight = null;
            return;
        }
        for (Object entry : context.server.getConfigurationManager().playerEntityList) {
            if (entry instanceof EntityPlayerMP && context.playerId.equals(((EntityPlayerMP) entry).getUniqueID())) {
                EntityPlayerMP player = (EntityPlayerMP) entry;
                if (!player.isDead && player.worldObj != null && player.dimension == context.dimension) {
                    serverFlight = new FlightObservation(context.server, context.playerId, player.dimension,
                            player.worldObj.getTotalWorldTime(), player.capabilities.isFlying, player.capabilities.allowFlying,
                            player.capabilities.isCreativeMode, player.onGround, System.nanoTime(), serverControlSample(context, player), player.canCommandSenderUseCommand(2, "tp"));
                    return;
                }
            }
        }
        serverFlight = null;
    }

    private JsonObject flightObservation(Minecraft client) {
        return flightObservation(client, serverFlight);
    }

    private JsonObject flightObservation(Minecraft client, FlightObservation observed) {
        ServerContext context = serverContext;
        JsonObject result = new JsonObject();
        if (observed == null || context == null || !context.developer || observed.server != context.server
                || !observed.playerId.equals(context.playerId) || client.thePlayer == null
                || !observed.playerId.equals(client.thePlayer.getUniqueID()) || observed.dimension != client.thePlayer.dimension
                || System.nanoTime() - observed.capturedAt > 1000000000L) {
            result.addProperty("available", false);
            result.addProperty("reason", "No fresh current local developer server-player flight sample; inspect again after an unpaused server tick.");
            return result;
        }
        result.addProperty("available", true);
        result.addProperty("flying", observed.flying);
        result.addProperty("allowFlying", observed.allowFlying);
        result.addProperty("creative", observed.creative);
        result.addProperty("onGround", observed.onGround);
        result.addProperty("dimension", observed.dimension);
        result.addProperty("worldTick", observed.worldTick);
        result.addProperty("ageMillis", Math.max(0, System.nanoTime() - observed.capturedAt) / 1000000.0);
        result.addProperty("source", "integrated_server_tick");
        return result;
    }

    /** Server-thread sample of public container/selection/pose state, at the same epoch as the existing flight sample. */
    private JsonObject serverControlSample(ServerContext context, EntityPlayerMP player) {
        if (guiWaits.observing() && player.openContainer != null) {
            try {
                GuiObservationWait.ContainerEvidence resend = guiResyncRequest;
                if (resend != null && !guiResyncSubmitted) {
                    guiResyncSubmitted=true; guiResyncRequest=null;
                    ContainerSyncAdapter.resend(player,player.openContainer,resend);
                }
                // Vanilla retains the exact full stacks announced by detectAndSendChanges.
                // Live equipment can mutate later in the same tick; do not compare different epochs or strip NBT.
                guiServerContainer = new GuiObservationWait.ContainerEvidence(player.openContainer, System.nanoTime(), true);
            }
            catch (RuntimeException error) { guiServerContainerFailure = error; }
        }
        JsonObject result = new JsonObject(); result.addProperty("sessionId", sessionId); result.addProperty("playerId", player.getUniqueID().toString());
        result.addProperty("sampleId", ++serverControlSequence);
        result.addProperty("worldName", JsonData.bounded(context.server.getWorldName(), 128)); result.addProperty("worldFolder", JsonData.bounded(context.server.getFolderName(), 128));
        result.addProperty("selectedHotbarSlot", player.inventory.currentItem);
        JsonObject container = new JsonObject(); container.addProperty("className", player.openContainer == null ? "" : player.openContainer.getClass().getName());
        container.addProperty("windowId", player.openContainer == null ? -1 : player.openContainer.windowId);
        container.addProperty("isInventoryContainer", player.openContainer != null && player.openContainer == player.inventoryContainer); result.add("openContainer", container);
        JsonObject pose = serverPosition(player); pose.addProperty("feetY", player.boundingBox.minY); result.add("pose", pose);
        return result;
    }

    /** Pure fresh tick DTO. Client and server captures are non-atomic; no server container is dereferenced here. */
    private JsonObject serverControlObservation(Minecraft client) {
        FlightObservation observed = serverFlight;
        // Validate and serialize one published immutable sample. A newer server tick
        // does not invalidate this still-fresh sample or mix its flight/control epoch.
        JsonObject result = flightObservation(client, observed);
        if (!result.get("available").getAsBoolean() || observed == null || observed.control == null) return result;
        for (Map.Entry<String, JsonElement> entry : observed.control.entrySet()) result.add(entry.getKey(), entry.getValue());
        Container current = client.thePlayer.openContainer; JsonObject container = new JsonObject();
        container.addProperty("windowId", current == null ? -1 : current.windowId);
        container.addProperty("isInventoryContainer", current != null && current == client.thePlayer.inventoryContainer); result.add("clientContainer", container);
        result.addProperty("selectionSynchronized", observed.control.get("selectedHotbarSlot").getAsInt() == client.thePlayer.inventory.currentItem);
        result.addProperty("nonAtomic", true); return result;
    }

    private JsonObject flight(Minecraft client, JsonObject args) {
        requirePlayer(client);
        String action = ExpandedGuiApi.option(args, "action", "inspect", "inspect", "set");
        if (action.equals("inspect")) {
            ExpandedGuiApi.keys(args);
            JsonObject result = new JsonObject(); result.add("player", snapshot(client).get("player")); return result;
        }
        ExpandedGuiApi.keys(args, "flying", "expectedWorld");
        if (!args.has("flying")) throw new BridgeException("invalid_args", "flying is required for set.");
        boolean requested = GuiDataAdapter.booleanArg(args, "flying", false);
        requireWorldControls(client);
        refreshServerContext(client);
        ServerContext context = requireDeveloperContext();
        requireExpectedWorld(context, args);
        FlightPolicy.requireChange(client.thePlayer.capabilities.isCreativeMode, client.thePlayer.capabilities.allowFlying,
                client.thePlayer.onGround, client.thePlayer.ridingEntity != null || client.thePlayer.riddenByEntity != null, requested);
        releaseInput();
        boolean before = client.thePlayer.capabilities.isFlying;
        client.thePlayer.capabilities.isFlying = requested;
        client.thePlayer.sendPlayerAbilities();
        JsonObject result = new JsonObject();
        result.addProperty("submitted", true); result.addProperty("requestedFlying", requested); result.addProperty("beforeFlying", before);
        result.addProperty("clientFlying", client.thePlayer.capabilities.isFlying);
        result.add("serverFlight", flightObservation(client));
        result.addProperty("completion", "Normal sendPlayerAbilities packet submitted. Confirm a later snapshot.player.serverFlight sample and client flying state before airborne controls or teleport.");
        return result;
    }

    private JsonObject block(World world, int x, int y, int z, boolean developer) {
        return WorldInspectionAdapter.block(world, x, y, z, developer);
    }
    private JsonObject block(World world, int x, int y, int z, boolean developer, PlacementAfterObservation.Coverage coverage) {
        return WorldInspectionAdapter.block(world, x, y, z, developer, coverage);
    }
    private JsonObject aeDetails(World world, int x, int y, int z, boolean developer, JsonObject args, JsonObject data) {
        return WorldInspectionAdapter.aeDetails(world, x, y, z, developer, args, data);
    }


    private JsonObject input(Minecraft client, JsonObject args) {
        if (navigation != null) throw new BridgeException("busy", "A native navigation task owns its input; wait for its completion or release agent control.");
        requireWorldControls(client);
        int ticks = JsonData.integer(args, "ticks", 1, 200);
        JsonArray requested;
        try { requested = args.getAsJsonArray("keys"); } catch (RuntimeException e) { requested = null; }
        if (requested == null || requested.size() < 1 || requested.size() > 9)
            throw new BridgeException("invalid_args", "keys must contain 1 through 9 supported binding names.");
        Map<String, KeyBinding> available = keyBindings(client);
        Map<String, KeyBinding> next = new LinkedHashMap<String, KeyBinding>();
        for (int i = 0; i < requested.size(); i++) {
            if (!requested.get(i).isJsonPrimitive() || !requested.get(i).getAsJsonPrimitive().isString())
                throw new BridgeException("invalid_args", "Each key must be a binding name string.");
            String name = requested.get(i).getAsString();
            if (!available.containsKey(name)) throw new BridgeException("unsupported", "Unsupported input binding: " + name);
            next.put(name, available.get(name));
        }
        releaseInput();
        held.putAll(next);
        for (Map.Entry<String, KeyBinding> entry : next.entrySet()) heldKeyCodes.put(entry.getKey(), entry.getValue().getKeyCode());
        inputDiagnostics.beginNative("player.input", client.theWorld, client.thePlayer, sessionId, clientTicks, client.gameSettings.keyBindings);
        remainingInputTicks = ticks;
        inputTaskId=UUID.randomUUID().toString();
        inputDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        beginOwnedKeys("player.input", inputDeadline, client, next, () -> !held.isEmpty() && remainingInputTicks > 0 && navigation == null);
        for (Map.Entry<String, KeyBinding> entry : held.entrySet()) {
            KeyBinding binding = entry.getValue(); int code = heldKeyCodes.get(entry.getKey());
            OwnedKeyState.hold("player.input", binding, code);
            KeyBinding.setKeyBindState(code, true);
            inputDiagnostics.writeNative("player.input", entry.getKey(), binding, code, true, clientTicks);
            if (binding == client.gameSettings.keyBindAttack || binding == client.gameSettings.keyBindUseItem)
                KeyBinding.onTick(code);
        }
        JsonObject result = status("ticksScheduled", ticks);
        result.addProperty("taskId",inputTaskId);
        result.addProperty("completion", "scheduled: bindings release after client ticks, GUI/world changes, pause or 15 seconds");
        result.add("inputDiagnostics", inputDiagnosticObservation(client));
        return result;
    }

    private JsonObject actualMovementInput(Minecraft client) {
        JsonObject result = new JsonObject(); boolean available = client.thePlayer != null && client.thePlayer.movementInput != null;
        result.addProperty("available", available);
        if (available) {
            result.addProperty("sneak", client.thePlayer.movementInput.sneak); result.addProperty("jump", client.thePlayer.movementInput.jump);
            result.addProperty("forward", client.thePlayer.movementInput.moveForward); result.addProperty("strafe", client.thePlayer.movementInput.moveStrafe);
            result.addProperty("sprinting", client.thePlayer.isSprinting()); result.addProperty("motionX", client.thePlayer.motionX);
            result.addProperty("motionY", client.thePlayer.motionY); result.addProperty("motionZ", client.thePlayer.motionZ);
        }
        return result;
    }

    private JsonObject inputDiagnosticObservation(Minecraft client) {
        try { JsonObject result = inputDiagnostics.inspect(client.theWorld, client.thePlayer, sessionId, clientTicks, actualMovementInput(client));
            result.addProperty("logicalOwnedKeyCount", OwnedKeyState.heldCount()); result.addProperty("logicalGetterHookCalls", OwnedKeyState.hookCalls()); return result; }
        catch (RuntimeException | LinkageError error) {
            JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("status", "unavailable");
            result.addProperty("failure", error.getClass().getSimpleName()); return result;
        }
    }

    private Map<String, KeyBinding> keyBindings(Minecraft client) {
        Map<String, KeyBinding> map = new LinkedHashMap<String, KeyBinding>();
        map.put("forward", client.gameSettings.keyBindForward);
        map.put("back", client.gameSettings.keyBindBack);
        map.put("left", client.gameSettings.keyBindLeft);
        map.put("right", client.gameSettings.keyBindRight);
        map.put("jump", client.gameSettings.keyBindJump);
        map.put("sneak", client.gameSettings.keyBindSneak);
        map.put("sprint", client.gameSettings.keyBindSprint);
        map.put("use", client.gameSettings.keyBindUseItem);
        map.put("attack", client.gameSettings.keyBindAttack);
        return map;
    }

    private KeyBinding keyBinding(Minecraft client, String name) {
        switch (name) {
            case "forward": return client.gameSettings.keyBindForward;
            case "back": return client.gameSettings.keyBindBack;
            case "left": return client.gameSettings.keyBindLeft;
            case "right": return client.gameSettings.keyBindRight;
            case "jump": return client.gameSettings.keyBindJump;
            case "sneak": return client.gameSettings.keyBindSneak;
            case "sprint": return client.gameSettings.keyBindSprint;
            case "use": return client.gameSettings.keyBindUseItem;
            case "attack": return client.gameSettings.keyBindAttack;
            default: return null;
        }
    }

    private volatile FollowTarget following;

    /** Client command and RPC share this entry point. Tracking is transient, never resumed on world load. */
    public JsonObject follow(JsonObject args) {
        ExpandedGuiApi.keys(args,"action","player","expectedSessionId","expectedWorld","taskId");
        if(args.has("expectedSessionId") && !sessionId.equals(args.get("expectedSessionId").getAsString()))
            throw new BridgeException("stale_world","Follow session changed.");
        String action=ExpandedGuiApi.option(args,"action","status","start","status","stop");
        if("stop".equals(action)) {
            if(args.has("taskId") && (following==null || !following.active || !following.taskId.equals(args.get("taskId").getAsString())))
                throw new BridgeException("stale_task","Follow task no longer owns this tracker.");
            stopFollow("cancelled");
        } else if("start".equals(action)) {
            Minecraft client=Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
            if(remoteConnection) throw new BridgeException("unsupported","Following reuses native navigation, which currently requires an integrated world.");
            ServerContext context=requireDeveloperContext();
            if(args.has("expectedWorld")) requireExpectedWorld(context,args);
            if(following!=null && (following.active || following.workerBusy) || navigation!=null || !held.isEmpty())
                throw new BridgeException("busy","Stop the current movement task before starting follow.");
            if(!background.active(client)) throw new BridgeException("invalid_state","Enable /mcb agent on before following.");
            OperationPolicyRules.check("player.navigate",new JsonObject(),policies,true,client.thePlayer.capabilities.isCreativeMode,canTeleport(client));
            String selector=CatalogData.text(args,"player",128,null); EntityPlayer target=null;
            for(Object entry:client.theWorld.playerEntities) if(entry instanceof EntityPlayer) {
                EntityPlayer candidate=(EntityPlayer)entry;
                if(selector.equalsIgnoreCase(candidate.getCommandSenderName()) || selector.equals(candidate.getUniqueID().toString())) {
                    if(target!=null) throw new BridgeException("ambiguous_target","More than one loaded player matched.");
                    target=candidate;
                }
            }
            if(target==null || target.isDead) throw new BridgeException("target_unavailable","Player must be loaded, alive and in the same dimension.");
            if(target==client.thePlayer) throw new BridgeException("invalid_args","Cannot follow the controlled player itself.");
            following=new FollowTarget(target.getUniqueID().toString(),target.getCommandSenderName(),context.server.getFolderName(),sessionId,
                    client.thePlayer.dimension,policies.revision(),client.theWorld,client.thePlayer,target);
            following.nextObservation=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        }
        JsonObject result=following==null ? new JsonObject() : following.inspect();
        result.addProperty("operation","player.follow"); result.addProperty("sessionId",sessionId);
        if(following==null) { result.addProperty("active",false); result.addProperty("phase","idle"); }
        result.addProperty("remoteExecutionSupported",false); return result;
    }

    private void cancelFollowLeg(FollowTarget task) {
        task.generation++;
        PlayerNavigation.State active=navigation;
        if(active!=null && task.taskId.equals(active.request.followTaskId)) {
            active.abort=true; releaseNavigationKeys(active); if(navigation==active) navigation=null;
        }
    }
    private void stopFollow(String reason) {
        FollowTarget task=following;
        if(task==null || !task.active) return;
        task.active=false; task.phase="stopped"; task.reason=reason; cancelFollowLeg(task);
    }
    private void maintainFollow(Minecraft client) {
        FollowTarget task=following;
        if(task==null || !task.active) return;
        if(task.worldIdentity!=client.theWorld || task.actorIdentity!=client.thePlayer || client.thePlayer==null
                || client.thePlayer.isDead || task.revision!=policies.revision() || client.thePlayer.dimension!=task.dimension
                || !background.active(client) || client.isGamePaused()) { stopFollow("context_changed"); return; }
        long now=System.nanoTime();
        if(now<task.nextObservation) return;
        task.nextObservation=now+TimeUnit.SECONDS.toNanos(1);
        if(client.currentScreen!=null || client.thePlayer.inventory.getItemStack()!=null || client.thePlayer.ridingEntity!=null
                || client.thePlayer.riddenByEntity!=null) { stopFollow("controls_unavailable"); return; }
        if(navigation!=null && !task.taskId.equals(navigation.request.followTaskId) || !held.isEmpty()
                || !task.workerBusy && !navigationMovementKeysIdle(client)) { stopFollow("input_taken_over"); return; }
        EntityPlayer target=(EntityPlayer)task.targetIdentity;
        if(target.isDead || target.worldObj!=client.theWorld || !client.theWorld.playerEntities.contains(target)
                || !task.playerId.equals(target.getUniqueID().toString())) { stopFollow("target_unavailable"); return; }
        PlayerNavigation.Point actor=new PlayerNavigation.Point(client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ);
        task.target=new PlayerNavigation.Point(target.posX,target.boundingBox.minY,target.posZ); task.observations++;
        if(actor.distance(task.target)>28) { stopFollow("target_out_of_navigation_range"); return; }
        boolean visible=false;
        if(actor.distance(task.target)<=4) {
            WalkingPathPlanner.Check check=NearbyNavigationGoal.visibility(client.theWorld,client.thePlayer,actor,task.target,target.height,
                    now+WalkingPathPlanner.WORK_NANOS);
            if(check==WalkingPathPlanner.Check.PARTIAL) return;
            if(check==WalkingPathPlanner.Check.UNKNOWN) { stopFollow("visibility_unknown"); return; }
            visible=check==WalkingPathPlanner.Check.CLEAR;
        }
        FollowTarget.Decision decision=FollowTarget.decide(actor,task.target,visible,task.holding,task.workerBusy,task.legTarget);
        if(decision==FollowTarget.Decision.HOLD) {
            if(!task.holding) cancelFollowLeg(task);
            task.holding=true; task.phase="holding"; return;
        }
        task.holding=false;
        if(decision==FollowTarget.Decision.REPLAN) { task.replans++; cancelFollowLeg(task); task.phase="retargeting"; return; }
        if(decision==FollowTarget.Decision.KEEP) return;
        JsonObject args=new JsonObject(),goal=new JsonObject(); goal.addProperty("type","nearby");
        goal.add("center",NearbyNavigationGoal.xyz(task.target.x,task.target.y,task.target.z)); goal.addProperty("targetHeight",target.height);
        args.add("goal",goal); args.addProperty("expectedWorld",task.world); args.addProperty("expectedSessionId",task.session);
        args.addProperty("dimension",task.dimension);
        String execution=OperationPolicyRules.execution(policies.selected().get("movement").getAsString(),client.thePlayer.capabilities.allowFlying && client.thePlayer.capabilities.isFlying,canTeleport(client));
        boolean tp="developer_teleport".equals(execution);
        args.addProperty("segmented",!tp); args.addProperty("timeout_ms",tp ? 6000 : 15000);
        if("ability_flight".equals(execution)) args.addProperty("finishFlying",true);
        task.legTarget=task.target; task.workerBusy=true; task.phase="moving"; task.segments++;
        final int generation=task.generation;
        Thread worker=new Thread(()->runFollowLeg(task,generation,args),"MCB-follow-"+task.taskId.substring(0,8));
        worker.setDaemon(true); worker.start();
    }
    private void runFollowLeg(FollowTarget task,int generation,JsonObject args) {
        try {
            GameQueue.Control control=new GameQueue.Control();
            JsonObject prepared=prepareOperation(PlayerNavigation.OPERATION,args,control);
            control.guardExecution(()->{
                if(task!=following || !task.active || task.generation!=generation || task.revision!=policies.revision())
                    throw new BridgeException("cancelled","Follow target or policy changed; old leg cannot acquire input.");
            });
            PlayerNavigation.Request request=new PlayerNavigation.Request(prepared); request.followTaskId=task.taskId;
            JsonObject expected=new JsonObject(); expected.addProperty("version",1); expected.addProperty("expectedSessionId",task.session);
            expected.addProperty("expectedProfile","developer"); expected.addProperty("expectedWorld",task.world); expected.addProperty("requireBackground",true);
            dispatchNavigation(request,control,GuardedContext.parse(PlayerNavigation.OPERATION,expected));
            if(task.active && task.generation==generation) task.phase="observing";
        } catch(Exception error) {
            Throwable cause=error instanceof ExecutionException ? error.getCause() : error;
            if(task.active && task.generation==generation) {
                task.reason=cause instanceof BridgeException ? ((BridgeException)cause).code : "navigation_failed";
                task.phase="stopped"; task.active=false;
            }
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { task.workerBusy=false; }
    }
    private boolean nearbyNavigationProof(PlayerNavigation.State state,World world,EntityPlayer player) {
        if(!(state.request.goal instanceof NearbyNavigationGoal) || !state.goalResolved) return true;
        NearbyNavigationGoal goal=(NearbyNavigationGoal)state.request.goal;
        PlayerNavigation.Point actual=new PlayerNavigation.Point(player.posX,player.boundingBox.minY,player.posZ);
        // Check the chosen endpoint before moving, and the real pose again at arrival. Route geometry remains unchanged.
        if(state.mutation && !actual.near(state.request.destination)) return true;
        PlayerNavigation.Point point=state.mutation ? actual : state.request.destination;
        WalkingPathPlanner.Check check=goal.visible(world,player,point,System.nanoTime()+WalkingPathPlanner.WORK_NANOS);
        if(check==WalkingPathPlanner.Check.PARTIAL) return false;
        if(check!=WalkingPathPlanner.Check.CLEAR || !goal.contains(point))
            throw new BridgeException("target_unverified","Nearby endpoint is no longer within range with sufficient visible target area.");
        return true;
    }

    private void releaseInput() {
        stopFollow("input_released");
        PlayerNavigation.State active = navigation;
        if (active != null) { active.abort = true; releaseNavigationKeys(active); }
        OwnedKeyState.clear("player.input");
        for (Map.Entry<String, KeyBinding> entry : held.entrySet()) {
            KeyBinding binding = entry.getValue();
            int code = heldKeyCodes.get(entry.getKey());
            boolean physical = code >= 0 ? Keyboard.isCreated() && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code)
                    : Mouse.isCreated() && code + 100 >= 0 && code + 100 < Mouse.getButtonCount() && Mouse.isButtonDown(code + 100);
            KeyBinding.setKeyBindState(code, physical);
            inputDiagnostics.writeNative("player.input", entry.getKey(), binding, code, physical, clientTicks);
            if (!physical) while (binding.isPressed()) { /* Consume pending synthetic clicks after release. */ }
        }
        held.clear();
        heldKeyCodes.clear();
        remainingInputTicks = 0;
        inputTaskId=null;
        inputDiagnostics.release("player.input");
    }
    /** Exact task cancellation on the client queue; leaves agent mode and physical input unchanged. */
    private JsonObject inputTask(JsonObject args) {
        ExpandedGuiApi.keys(args,"action","expectedSessionId","taskId");
        if(!sessionId.equals(CatalogData.text(args,"expectedSessionId",128,""))) throw new BridgeException("stale_world","Input task session changed.");
        String action=ExpandedGuiApi.option(args,"action","status","status","cancel");
        PlayerNavigation.State active=navigation;
        FollowTarget tracker=following!=null && following.active ? following : null;
        String current=tracker!=null ? tracker.taskId : active!=null && !active.abort ? active.taskId : inputTaskId;
        JsonObject result=new JsonObject(); result.addProperty("operation","player.task"); result.addProperty("sessionId",sessionId);
        if("cancel".equals(action)) {
            String expected=CatalogData.text(args,"taskId",128,"");
            if(current==null || !current.equals(expected)) throw new BridgeException("stale_task","Task no longer owns input; no newer task was cancelled.");
            releaseInput(); if(navigation==active) navigation=null;
            result.addProperty("status","cancelled"); result.addProperty("taskId",expected);
            result.addProperty("ownedKeysReleased",OwnedKeyState.heldCount()==0 && held.isEmpty());
        } else {
            result.addProperty("status",current==null ? "idle" : "active");
            if(current!=null) result.addProperty("taskId",current);
            result.addProperty("owner",tracker!=null ? "player.follow" : active!=null && !active.abort ? PlayerNavigation.OPERATION : inputTaskId!=null ? "player.input" : "none");
            if(tracker!=null) result.add("follow",tracker.inspect());
            result.addProperty("ownedKeyCount",OwnedKeyState.heldCount());
            if(active!=null && !active.abort) { result.addProperty("phase",active.routeReady() ? "executing" : "planning"); result.addProperty("elapsedMillis",(System.nanoTime()-active.started)/1000000.0); }
        }
        result.addProperty("agentModePreserved",true); return result;
    }

    private void beginOwnedKeys(String owner, long deadline, Minecraft client, Map<String, KeyBinding> bindings, java.util.function.BooleanSupplier active) {
        World world = client.theWorld; EntityPlayer player = client.thePlayer; String session = sessionId, ownedProfile = profile;
        Map<String, KeyBinding> expected = new LinkedHashMap<String, KeyBinding>(bindings);
        Map<String, Integer> expectedCodes = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, KeyBinding> entry : expected.entrySet()) expectedCodes.put(entry.getKey(), entry.getValue().getKeyCode());
        OwnedKeyState.begin(owner, deadline, () -> {
            if (!active.getAsBoolean() || Minecraft.getMinecraft() != client || client.theWorld != world || client.thePlayer != player
                    || player == null || player.isDead || !sessionId.equals(session) || !ownedProfile.equals(profile) || !background.active(client)
                    || client.currentScreen != null || client.isGamePaused()) return false;
            for (Map.Entry<String, KeyBinding> entry : expected.entrySet()) if (keyBinding(client, entry.getKey()) != entry.getValue()
                    || entry.getValue().getKeyCode() != expectedCodes.get(entry.getKey())) return false;
            return true;
        });
    }

    private JsonObject settleNavigationStart(PlayerNavigation.Request request, GameQueue.Control control,
            GuardedContext guard, long deadline) throws ExecutionException, InterruptedException {
        GuiObservationWait.Job job=clientQueue.await(clientQueue.submit(() -> {
            Minecraft client=Minecraft.getMinecraft(); requireWorldControls(client); refreshServerContext(client);
            if(navigation!=null || !held.isEmpty() || !navigationMovementKeysIdle(client))
                throw new BridgeException("busy","Walking start observation requires released native movement keys.");
            JsonObject initial=snapshot(client); guard.check(initial);
            final Object world=client.theWorld, player=client.thePlayer;
            final boolean creative=client.thePlayer.capabilities.isCreativeMode;
            final NavigationStartSettle settle=new NavigationStartSettle(initial);
            JsonObject definition=new JsonObject(); definition.addProperty("timeoutMillis",Math.max(1,Math.min(2000,ApproachPlanner.remaining(deadline)-250)));
            return navigationStartWaits.register(new GuiObservationWait.Request(definition),new GuiObservationWait.Access() {
                public void check() {
                    requireWorldControls(client); guard.check(snapshot(client));
                    if(client.theWorld!=world || client.thePlayer!=player || client.thePlayer.isDead
                            || client.thePlayer.dimension!=request.dimension || client.thePlayer.capabilities.isCreativeMode!=creative
                            || !request.session.equals(sessionId) || navigation!=null || !held.isEmpty()
                            || !navigationMovementKeysIdle(client) || client.thePlayer.inventory.getItemStack()!=null)
                        throw new BridgeException("navigation_start_changed","Walking start context/input changed during read-only settling.");
                }
                public JsonObject read() { return settle.observe(snapshot(client),clientTicks); }
            });
        },ApproachPlanner.remaining(deadline),control),ApproachPlanner.remaining(deadline));
        Runnable cancellation=job::cancel; control.onReadCancellation(cancellation);
        try {
            while(true) {
                long began=System.nanoTime(); final JsonObject observed;
                try { observed=job.await(ApproachPlanner.remaining(deadline)); }
                finally { control.recordNativeAwait(System.nanoTime()-began); }
                JsonObject result=clientQueue.await(clientQueue.submit(() -> job.verifyFinal(observed),
                        ApproachPlanner.remaining(deadline),control),ApproachPlanner.remaining(deadline));
                if(result==null) continue;
                JsonObject wait=result.getAsJsonObject("guiWait");
                if(!wait.get("matched").getAsBoolean())
                    throw new BridgeException("navigation_start_unsettled","Native walking motion did not settle inside its bounded read-only window; no navigation input dispatched.");
                result.remove("guiWait"); result.addProperty("elapsedMillis",wait.get("elapsedMillis").getAsDouble());
                return result;
            }
        } finally {job.cancel();control.clearReadCancellation(cancellation);}
    }

    /** HTTP worker only; owning queues never block each other or advance game ticks. */
    private GuardedContext.Result dispatchNavigation(PlayerNavigation.Request request, GameQueue.Control control, GuardedContext guard) throws ExecutionException, InterruptedException {
        if(request.followTaskId==null && following!=null && following.active)
            throw new BridgeException("busy","Stop the active follow task before independent navigation.");
        final long started = System.nanoTime();
        final long deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeout);
        final JsonObject startSettling="walking".equals(request.strategy) || "walking_step".equals(request.strategy)
                ? settleNavigationStart(request,control,guard,deadline) : null;
        final ApproachPlanner.Stages interactionRead = request.goal instanceof InteractionNavigationGoal
                ? approachStages(((InteractionNavigationGoal) request.goal).approachArgs(request.world, request.dimension, false), guard) : null;
        if (interactionRead != null) {
            ApproachPlanner.Frame initial = clientQueue.await(clientQueue.submit(() -> {
                if (navigation != null || !held.isEmpty()) throw new BridgeException("busy", "Navigation requires unowned synthetic input.");
                if (!request.session.equals(sessionId)) throw new BridgeException("stale_world", "Navigation session changed.");
                return interactionRead.capture();
            }, Math.min(1000, ApproachPlanner.remaining(deadline)), control), Math.min(1000, ApproachPlanner.remaining(deadline)));
            // Reuse the bounded native END-tick observation before taking movement ownership.
            // No sleeping game thread, pose writes or second navigation dispatch.
            interactionRead.ready(initial, deadline, control);
        }
        PlayerNavigation.State state = clientQueue.await(clientQueue.submit(() -> {
            Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client); refreshServerContext(client);
            if (navigation != null || !held.isEmpty()) throw new BridgeException("busy", "Navigation requires unowned synthetic input.");
            if (!request.session.equals(sessionId)) throw new BridgeException("stale_world", "Navigation session changed.");
            ServerContext context = requireDeveloperContext(); JsonObject args = new JsonObject(); args.addProperty("expectedWorld", request.world); requireExpectedWorld(context, args);
            if (client.thePlayer.inventory.getItemStack() != null || client.thePlayer.ridingEntity != null || client.thePlayer.riddenByEntity != null)
                throw new BridgeException("invalid_state", "Navigation requires an unmounted actor and empty real cursor.");
            JsonObject before = snapshot(client); guard.check(before);
            if(startSettling!=null && (!NavigationStartSettle.quiet(before.getAsJsonObject("player"))
                    || !navigationMovementKeysIdle(client)))
                throw new BridgeException("navigation_start_changed","Walking motion changed after settling and before route capture.");
            if (request.goal instanceof InteractionNavigationGoal) {
                InteractionNavigationGoal goal = (InteractionNavigationGoal) request.goal;
                goal.capture(captureApproach(goal.approachArgs(request.world, request.dimension, false)), approachAccess(client.theWorld, client.thePlayer));
            }
            if (request.crosshair != null) request.crosshair.capture(approachAccess(client.theWorld,client.thePlayer));
            if (request.goal instanceof NearbyNavigationGoal) {
                NearbyNavigationGoal goal=(NearbyNavigationGoal)request.goal;
                World world=client.theWorld; EntityPlayer actor=client.thePlayer;
                goal.visibility=(point,workDeadline)->goal.visible(world,actor,point,workDeadline);
            }
            PlayerNavigation.State created = new PlayerNavigation.State(request, before, client.theWorld, client.thePlayer, client.thePlayer.getUniqueID().toString(), started);
            created.startSettling=startSettling;
            if (interactionRead != null) created.eyeSettling = interactionRead.eyeObservation();
            if ("walking_step".equals(request.strategy)) created.step = new GroundedStep(created.start, request.destination, client.thePlayer.width, client.thePlayer.height);
            Map<String, KeyBinding> bindings = keyBindings(client);
            for (String name : PlayerNavigation.MOVEMENT_KEYS) created.keyCodes.put(name, bindings.get(name).getKeyCode());
            if (new java.util.HashSet<Integer>(created.keyCodes.values()).size() != created.keyCodes.size())
                throw new BridgeException("unsupported", "Navigation requires distinct native movement key bindings.");
            navigation = created;
            Map<String, KeyBinding> owned = new LinkedHashMap<String, KeyBinding>();
            for (String name : created.keyCodes.keySet()) owned.put(name, bindings.get(name));
            beginOwnedKeys(PlayerNavigation.OPERATION, created.deadline, client, owned, () -> navigation == created && !created.abort);
            inputDiagnostics.beginNative(PlayerNavigation.OPERATION, client.theWorld, client.thePlayer, sessionId, clientTicks, client.gameSettings.keyBindings);
            return created;
        }, Math.min(1000, ApproachPlanner.remaining(deadline)), control), Math.min(1000, ApproachPlanner.remaining(deadline)));
        try {
            while (!state.abort) {
                long remaining = navigationRemaining(state);
                // Planning is pure client work. Bind the server once, then validate the complete
                // published route before input rather than queueing a redundant server read per slice.
                boolean ready = state.authoritativeActor == null || state.routeReady()
                        ? serverQueue.await(serverQueue.submit(() -> navigationServer(state), remaining, control), remaining) : false;
                remaining = navigationRemaining(state);
                GuardedContext.Result result = clientQueue.await(clientQueue.submit(() -> navigationClient(state, ready, guard), remaining, control), remaining);
                if (result != null) return result;
            }
            if (state.failure != null) throw state.failure;
            throw new BridgeException("cancelled", "Navigation input ownership was released; no action is replayed.");
        } catch (ExecutionException error) {
            releaseNavigationAfterFailure(state);
            if (error.getCause() instanceof BridgeException) throw state.diagnostic((BridgeException) error.getCause());
            throw error;
        } catch (BridgeException error) {
            releaseNavigationAfterFailure(state);
            throw state.diagnostic(error);
        } finally {
            state.abort = true;
            // Release on the next actual client tick even when HTTP cancellation prevents another queued callback.
        }
    }

    private void releaseNavigationAfterFailure(PlayerNavigation.State state) {
        state.abort = true;
        try {
            // Cleanup has its own bounded ticket: cancellation/deadline never replays navigation.
            clientQueue.await(clientQueue.submit(() -> {
                if (navigation == state || navigation == null && inputTaskId==null) {
                    releaseNavigationKeys(state);
                    if (navigation == state) navigation = null;
                }
                return null;
            }, 250), 250);
        } catch (ExecutionException | InterruptedException | BridgeException cleanup) {
            if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
            // The next native tick retains the abort cleanup; the receipt reports lack of confirmation.
        }
    }

    private static long navigationRemaining(PlayerNavigation.State state) {
        if (state.failure != null) throw state.failure;
        long value = TimeUnit.NANOSECONDS.toMillis(state.deadline - System.nanoTime());
        if (state.abort || value <= 0) throw new BridgeException("timeout", "The native navigation deadline expired; task keys are released, outcome is not replayed.");
        return value;
    }

    private boolean navigationServer(PlayerNavigation.State state) {
        navigationRemaining(state); ServerContext context = requireDeveloperContext();
        if (context.dimension != state.request.dimension || !context.playerId.toString().equals(state.playerId)
                || context.paused || context.screenOpen || System.nanoTime() - context.capturedAt > 1000000000L)
            throw new BridgeException("stale_world", "The authoritative navigation world/player context changed.");
        JsonObject args = new JsonObject(); args.addProperty("expectedWorld", state.request.world); requireExpectedWorld(context, args);
        EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
        if (state.authoritativeActor == null) { state.authoritativeActor = player; state.authoritativeWorld = player.worldObj; state.authoritativeServer = context.server; }
        if (state.authoritativeActor != player || state.authoritativeWorld != player.worldObj || state.authoritativeServer != context.server)
            throw new BridgeException("stale_world", "The authoritative navigation actor/world/server entity changed, including respawn.");
        if (player.isDead || player.ridingEntity != null || player.riddenByEntity != null || player.worldObj == null || player.dimension != state.request.dimension
                || player.capabilities.isCreativeMode != state.creative || player.openContainer != player.inventoryContainer || player.inventory.getItemStack() != null)
            throw new BridgeException("invalid_state", "The actual native navigation actor/mode/container changed.");
        if ("ability_flight".equals(state.request.strategy) && !player.capabilities.allowFlying) throw new BridgeException("permission_denied", "Actual server flight permission was removed.");
        // Publish this owning-server observation before the queued callback completes.
        // END-tick capture after queue drain may otherwise be overtaken by the client stage.
        if ("ability_flight".equals(state.request.strategy)) captureFlight(context);
        if (!state.routeReady()) return false;
        if (!nearbyNavigationProof(state,player.worldObj,player)) return false;
        navigationCrosshairServer(state, player);
        if (state.step != null) {
            long deadline = System.nanoTime() + WalkingPathPlanner.WORK_NANOS;
            boolean ready = navigationStepSpace(player.worldObj, player, state, deadline);
            // Height transitions retain their native landing controller, but final interaction
            // arrival still needs the same fresh authoritative actual-face proof as a flat route.
            if (ready && state.request.goal instanceof InteractionNavigationGoal && state.waypoint == state.route.size()-1
                    && player.onGround && state.step.arrival(state.latest)) {
                PlayerNavigation.Point actual = new PlayerNavigation.Point(player.posX,player.boundingBox.minY,player.posZ);
                WalkingPathPlanner.Check check = ((InteractionNavigationGoal)state.request.goal).proveActual(approachAccess(player.worldObj,player),actual,
                        player.theItemInWorldManager.getBlockReachDistance(),player.worldObj.getTotalWorldTime(),deadline,false);
                if (check == WalkingPathPlanner.Check.PARTIAL) return false;
                if (check != WalkingPathPlanner.Check.CLEAR)
                    throw new BridgeException("target_unverified", "The actual final height landing no longer proves authoritative interaction targets and allowed faces.");
            }
            if (ready) state.serverSafetyAt = System.nanoTime();
            return ready;
        }
        if ("ability_flight".equals(state.request.strategy)) return navigationFlightServer(state, player);
        if ("walking".equals(state.request.strategy)) {
            long deadline = System.nanoTime()+WalkingPathPlanner.WORK_NANOS;
            PlayerNavigation.Point actual = new PlayerNavigation.Point(player.posX,player.boundingBox.minY,player.posZ);
            if (state.request.goal instanceof InteractionNavigationGoal) {
                InteractionNavigationGoal goal = (InteractionNavigationGoal) state.request.goal;
                boolean actualCandidate = (state.mutation || actual.near(state.request.destination))
                        && state.waypoint == state.route.size()-1 && player.onGround && goal.reachDistance(actual) <= .00001;
                WalkingPathPlanner.Check check = actualCandidate ? goal.proveActual(approachAccess(player.worldObj,player),actual,
                        player.theItemInWorldManager.getBlockReachDistance(),player.worldObj.getTotalWorldTime(),deadline,false)
                        : state.mutation ? WalkingPathPlanner.Check.CLEAR : goal.prove(approachAccess(player.worldObj, player),state.request.destination,
                          player.theItemInWorldManager.getBlockReachDistance(), player.worldObj.getTotalWorldTime(), deadline, false);
                if (check == WalkingPathPlanner.Check.PARTIAL) return false;
                if (check != WalkingPathPlanner.Check.CLEAR && (!state.mutation || actual.near(state.request.destination, state.walkingTolerance())))
                    throw new BridgeException("target_unverified", "The selected walking endpoint no longer proves all authoritative interaction targets, identities and allowed faces.");
            }
            if (!navigationWalkingProof(player.worldObj, player, state, deadline, false)) return false;
            boolean safe = !state.mutation || navigationSweep(player.worldObj, player, state,
                    new PlayerNavigation.Point(player.posX, player.boundingBox.minY, player.posZ), deadline);
            if (safe) state.serverSafetyAt = System.nanoTime();
            return safe;
        }
        List<PlayerNavigation.Point> probes = PlayerNavigation.probes(state.start, state.route, "developer_teleport".equals(state.request.strategy));
        long deadline = System.nanoTime() + 4000000L;
        while (state.serverProbe < probes.size() && System.nanoTime() < deadline) {
            PlayerNavigation.Point point = probes.get(state.serverProbe);
            boolean supported = "walking".equals(state.request.strategy) || state.serverProbe == probes.size() - 1 && state.requiresLanding();
            if (!navigationTeleportSpace(player.worldObj, player, point, supported, deadline)) return false;
            state.serverProbe++;
        }
        if (state.serverProbe < probes.size()) return false;
        if (!state.mutation) return true;
        PlayerNavigation.Point point = new PlayerNavigation.Point(player.posX, player.boundingBox.minY, player.posZ);
        return navigationSweep(player.worldObj, player, state, point, deadline);
    }

    private boolean navigationFlightServer(PlayerNavigation.State state, EntityPlayerMP player) {
        if (player.width != state.flightWidth || player.height != state.flightHeight)
            throw new BridgeException("invalid_state", "Actual server/client flight body dimensions do not match.");
        if (!state.mutation && state.start.distance(new PlayerNavigation.Point(player.posX, player.boundingBox.minY, player.posZ)) > .0001)
            throw new BridgeException("invalid_state", "The actual server start moved during flight planning/proof.");
        long deadline = System.nanoTime() + FlightPathPlanner.WORK_NANOS;
        List<FlightPathPlanner.Sweep> sweeps = state.flightSweeps(player.width, player.height);
        while (state.serverProbe < sweeps.size() && System.nanoTime() < deadline) {
            if (!navigationFlightSpace(player.worldObj, player, sweeps.get(state.serverProbe), deadline, state, "route", state.flightKeys)) return false;
            state.serverProbe++;
        }
        if (state.serverProbe < sweeps.size()) return false;
        if (!state.request.finishFlying && !navigationFlightLandingSpace(player.worldObj, player, state, deadline)) return false;
        if (state.serverFlightBrakePending()) return false;
        java.util.Set<String> requestedKeys = state.flightKeys;
        try {
            if (!navigationFlightSweep(player.worldObj, player, state, requestedKeys, deadline)) return false;
        } catch (BridgeException error) {
            JsonObject rejection = state.geometryRejection;
            if ("unsupported".equals(error.code) && player.capabilities.isFlying && !player.onGround
                    && state.requestServerFlightBrake(requestedKeys, rejection, () -> navigationFlightSweep(
                            player.worldObj,player,state,java.util.Collections.<String>emptySet(),deadline))) return false;
            throw error;
        }
        state.serverSafetyAt = System.nanoTime();
        return true;
    }

    private static AxisAlignedBB navigationFlightBody(EntityPlayer player, FlightPathPlanner.Sweep sweep) {
        return PlayerNavigation.translatedFlightBody(player.boundingBox,
                new PlayerNavigation.Point(player.posX, player.boundingBox == null ? Double.NaN : player.boundingBox.minY, player.posZ),
                sweep, player.width, player.height);
    }

    private boolean navigationFlightSpace(World world, EntityPlayer player, FlightPathPlanner.Sweep sweep, long deadline,
            PlayerNavigation.State state, String stage, java.util.Set<String> nextKeys) {
        if(sweep.pieces!=null) {
            for(FlightPathPlanner.Sweep piece:sweep.pieces) {
                if(System.nanoTime()>=deadline || !navigationFlightSpace(world,player,piece,deadline,state,stage,nextKeys)) return false;
            }
            return true;
        }
        AxisAlignedBB body = navigationFlightBody(player, sweep);
        JsonObject observation = WorldObservationsAdapter.spaceAt(world, player, body, deadline, false);
        FlightPathPlanner.Check check = PlayerNavigation.flightCheck(observation);
        if (check != FlightPathPlanner.Check.CLEAR)
            recordNavigationFlightRejection(world, player, state, stage, body, nextKeys, observation);
        else state.clearFlightBodyRejection();
        if (check == FlightPathPlanner.Check.PARTIAL) return false;
        if (check != FlightPathPlanner.Check.CLEAR)
            throw new BridgeException("unsupported", "Actual swept flight body is " + check.name().toLowerCase(java.util.Locale.ROOT) + "; no movement fallback.");
        return true;
    }

    private static void recordNavigationFlightRejection(World world, EntityPlayer player, PlayerNavigation.State state,
            String stage, AxisAlignedBB body, java.util.Set<String> nextKeys, JsonObject observation) {
        JsonObject actor = new JsonObject(); actor.addProperty("x", player.posX); actor.addProperty("feetY", player.boundingBox.minY); actor.addProperty("z", player.posZ);
        actor.addProperty("motionX", player.motionX); actor.addProperty("motionY", player.motionY); actor.addProperty("motionZ", player.motionZ);
        actor.addProperty("yaw", player.rotationYaw); actor.addProperty("steeringYaw", state.steeringYaw);
        actor.addProperty("flySpeed", player.capabilities.getFlySpeed()); actor.addProperty("width", player.width); actor.addProperty("height", player.height);
        state.rejectedFlightBody(world.isRemote ? "client" : "server", stage, actor, player.boundingBox, body, nextKeys, observation);
    }

    private boolean navigationFlightLandingSpace(World world, EntityPlayer player, PlayerNavigation.State state, long deadline) {
        FlightPathPlanner.Point target = PlayerNavigation.flightPoint(state.request.destination);
        AxisAlignedBB body = navigationFlightBody(player, new FlightPathPlanner.Sweep(target, target, player.width, player.height));
        JsonObject observation = WorldObservationsAdapter.spaceAt(world, player, body, deadline, true);
        WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(observation);
        if (check != WalkingPathPlanner.Check.CLEAR)
            recordNavigationFlightRejection(world, player, state, "landing_support", body, state.flightKeys, observation);
        if (check == WalkingPathPlanner.Check.PARTIAL) return false;
        if (check != WalkingPathPlanner.Check.CLEAR)
            throw new BridgeException("unsupported", "The full native landing body/support is " + check.name().toLowerCase(java.util.Locale.ROOT) + "; no movement fallback.");
        return true;
    }

    private boolean navigationFlightSweep(World world, EntityPlayer player, PlayerNavigation.State state, java.util.Set<String> keys, long deadline) {
        return navigationFlightSweep(world,player,state,keys,deadline,false);
    }

    private boolean navigationFlightSweep(World world, EntityPlayer player, PlayerNavigation.State state, java.util.Set<String> keys, long deadline, boolean allowBrake) {
        PlayerNavigation.Point current = new PlayerNavigation.Point(player.posX, player.boundingBox.minY, player.posZ);
        boolean landing = !state.request.finishFlying && state.waypoint == state.route.size() - 1 && current.horizontal(state.request.destination) <= PlayerNavigation.TOLERANCE;
        if (landing && !navigationFlightLandingSpace(world, player, state, deadline)) return false;
        boolean grounded = player.onGround && !player.capabilities.isFlying && player.motionY <= 0;
        if (world.isRemote) state.clientGroundFloorProof = false; else state.serverGroundFloorProof = false;
        if (grounded) {
            JsonObject support = WorldObservationsAdapter.spaceAt(world, player, player.boundingBox, deadline, true);
            WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(support);
            if (check != WalkingPathPlanner.Check.CLEAR)
                recordNavigationFlightRejection(world, player, state, "ground_support", player.boundingBox, keys, support);
            if (check == WalkingPathPlanner.Check.PARTIAL) return false;
            if (!PlayerNavigation.groundedFloorProven(player.onGround, player.capabilities.isFlying, player.motionY, support)) {
                throw new BridgeException("unsupported", "Actual grounded flight start lacks complete safe full-footprint support.");
            }
            if (world.isRemote) state.clientGroundFloorProof = true; else state.serverGroundFloorProof = true;
        }
        // Only freshly proven physical support constrains negative coast. A low flying
        // descent can reach that same floor without disabling the actual flight ability.
        double supportedFloor = grounded ? current.y : landing ? state.request.destination.y : Double.NaN;
        if (!Double.isFinite(supportedFloor) && PlayerNavigation.descentFloorCandidate(
                player.capabilities.isFlying, current.y, state.destination().y, player.motionY)) {
            double floor = state.destination().y;
            AxisAlignedBB floorBody = player.boundingBox.getOffsetBoundingBox(0, floor - current.y, 0);
            JsonObject support = WorldObservationsAdapter.spaceAt(world, player, floorBody, deadline, true);
            WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(support);
            if (check == WalkingPathPlanner.Check.PARTIAL) return false;
            if (check == WalkingPathPlanner.Check.CLEAR) supportedFloor = floor;
        }
        List<FlightPathPlanner.Sweep> sweeps = PlayerNavigation.inertiaSweeps(current, player.motionX, player.motionY, player.motionZ,
                keys, state.steeringYaw, player.capabilities.getFlySpeed(), player.capabilities.isFlying, supportedFloor,
                player.width, player.height,state.coastDrag(),state.coastScale());
        for (int index = 0; index < sweeps.size(); index++) {
            FlightPathPlanner.Sweep sweep = sweeps.get(index);
            try {
                if (!navigationFlightSpace(world, player, sweep, deadline, state, index == 0 ? "coast" : "controlled", keys)) return false;
            } catch (BridgeException error) {
                // Never relax geometry or choose another strategy. Only a client dispatch may
                // replace a blocked powered input with fully checked no-input coasting.
                if (allowBrake && index == 1 && "unsupported".equals(error.code) && !keys.isEmpty()
                        && state.flightObserved && player.capabilities.isFlying && !player.onGround) {
                    state.lastBrakedFlightBody = state.geometryRejection == null ? null : state.geometryRejection.deepCopy();
                    java.util.Set<String> coastKeys=new java.util.LinkedHashSet<String>();
                    if(keys.contains("jump") && keys.contains("sneak")) { coastKeys.add("jump"); coastKeys.add("sneak"); }
                    return state.brakeRejectedFlightInput(keys, () -> navigationFlightSweep(world,player,state,
                            coastKeys,deadline), clientTicks);
                }
                throw error;
            }
            if (Double.isFinite(supportedFloor) && sweep.feetY <= supportedFloor + .00001) {
                AxisAlignedBB body = navigationFlightBody(player, sweep);
                AxisAlignedBB supportedBody = AxisAlignedBB.getBoundingBox(body.minX, supportedFloor, body.minZ,
                        body.maxX, supportedFloor + (player.boundingBox.maxY - player.boundingBox.minY), body.maxZ);
                JsonObject support = WorldObservationsAdapter.spaceAt(world, player, supportedBody, deadline, true);
                WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(support);
                if (check != WalkingPathPlanner.Check.CLEAR)
                    recordNavigationFlightRejection(world, player, state, index == 0 ? "coast_support" : "controlled_support", supportedBody, keys, support);
                if (check == WalkingPathPlanner.Check.PARTIAL) return false;
                if (check != WalkingPathPlanner.Check.CLEAR) {
                    throw new BridgeException("unsupported", "Inertial grounded/landing footprint lacks complete safe support.");
                }
            }
        }
        return true;
    }

    private boolean navigationStepObservation(PlayerNavigation.State state, JsonObject value, boolean clear,
            String stage, boolean client, AxisAlignedBB body) {
        if (!clear) state.step.rejectGeometry(stage, client ? "client" : "server", body, value);
        if ("partial".equals(PlayerNavigation.text(value, "status"))) {
            state.step.partial = true; state.step.partialReads++;
            if (state.mutation) throw new BridgeException("partial_geometry", "A native walking_step geometry read became partial after dispatch; inputs release and the step is not replayed.");
            return false;
        }
        if (!clear) throw new BridgeException("unsupported", "The loaded native walking_step body/support/corridor is blocked or unknown; no movement fallback.");
        return true;
    }

    private boolean navigationStepSpace(World world, EntityPlayer player, PlayerNavigation.State state, long deadline) {
        GroundedStep step = state.step; boolean client = world.isRemote;
        if (client) { step.clientEnvelope = false; step.clientLanding = false; }
        else { step.serverEnvelope = false; step.serverLanding = false; }
        step.requirePosture(client ? "client" : "server", player.capabilities.isCreativeMode, player.capabilities.isFlying,
                player.isSneaking(), player.isSprinting(), player.isPotionActive(net.minecraft.potion.Potion.jump),
                player.width, player.height, player.boundingBox, "walking".equals(state.request.strategy));
        PlayerNavigation.Point actual = new PlayerNavigation.Point(player.posX, player.boundingBox.minY, player.posZ);
        AxisAlignedBB start = step.at(player.boundingBox, actual, step.start), landing = step.at(player.boundingBox, actual, step.target);
        JsonObject first = walkingPointSpace(world, player, step.start, player.width, player.height, deadline);
        if (!navigationStepObservation(state, first, PlayerNavigation.walkingCheck(first) == WalkingPathPlanner.Check.CLEAR, "start_support", client, start)) return false;
        JsonObject last = walkingPointSpace(world, player, step.target, player.width, player.height, deadline);
        if (!navigationStepObservation(state, last, PlayerNavigation.walkingCheck(last) == WalkingPathPlanner.Check.CLEAR, "landing_support", client, landing)) return false;
        if (!step.dispatched) {
            if (!actual.near(step.start) || !player.onGround || Math.hypot(player.motionX, player.motionZ) > .02)
                throw new BridgeException("invalid_state", "The real client/server takeoff moved before walking_step proof.");
            if (client) step.clientStart = true; else step.serverStart = true;
        }
        JsonObject corridor = WorldObservationsAdapter.spaceAt(world, player, step.envelope, deadline, false);
        if (!navigationStepObservation(state, corridor, step.envelopeClear(corridor), "corridor", client, step.envelope)) return false;
        if (!GroundedStep.contained(player.boundingBox, step.envelope))
            throw new BridgeException("unsupported", "The full actual native step body left its bounded corridor.");
        JsonObject current = WorldObservationsAdapter.spaceAt(world, player, player.boundingBox, deadline, false);
        if (!navigationStepObservation(state, current, PlayerNavigation.flightCheck(current) == FlightPathPlanner.Check.CLEAR, "current_body", client, player.boundingBox)) return false;
        AxisAlignedBB previous = client ? step.clientPrevious : step.serverPrevious;
        if (previous != null) {
            AxisAlignedBB swept = AxisAlignedBB.getBoundingBox(Math.min(previous.minX, player.boundingBox.minX),
                    Math.min(previous.minY, player.boundingBox.minY) + .00001, Math.min(previous.minZ, player.boundingBox.minZ),
                    Math.max(previous.maxX, player.boundingBox.maxX), Math.max(previous.maxY, player.boundingBox.maxY),
                    Math.max(previous.maxZ, player.boundingBox.maxZ));
            JsonObject observed = WorldObservationsAdapter.spaceAt(world, player, swept, deadline, false);
            if (!navigationStepObservation(state, observed, step.envelopeClear(observed), "swept_body", client, swept)) return false;
        }
        if (actual.near(step.target) && player.onGround) {
            JsonObject grounded = walkingPointSpace(world, player, actual, player.width, player.height, deadline);
            if (!navigationStepObservation(state, grounded, PlayerNavigation.walkingCheck(grounded) == WalkingPathPlanner.Check.CLEAR, "landed_support", client, player.boundingBox)) return false;
            if (client) step.clientLanding = true; else step.serverLanding = true;
        }
        if (client) { step.clientEnvelope = true; step.clientPrevious = player.boundingBox.copy(); }
        else { step.serverEnvelope = true; step.serverPrevious = player.boundingBox.copy(); }
        step.geometrySamples++;
        return true;
    }

    private boolean navigationSweep(World world, EntityPlayer player, PlayerNavigation.State state, PlayerNavigation.Point point, long deadline) {
        boolean support = "walking".equals(state.request.strategy);
        if (support) {
            WalkingPathPlanner.Point at = new WalkingPathPlanner.Point(point.x,point.y,point.z);
            if (!navigationWalkingSpace(world,player,state,new WalkingPathPlanner.Sweep(at,at,player.width,player.height),deadline,"current_body")) return false;
            double friction = navigationWalkingFriction(world, player);
            if (world.isRemote) {
                state.walkingFriction = friction;
                state.walkingAcceleration = player.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.movementSpeed).getAttributeValue()
                        * .98 * .16277136 / (friction*friction*friction);
            }
            PlayerNavigation.Point stop = PlayerNavigation.walkingStop(point,player.motionX,player.motionZ,friction);
            if (!navigationWalkingCorridor(world,player,state,point,stop,deadline,"coast")) return false;
            if (state.crosshairStopping || state.walkingCoasting || point.near(state.destination())
                    || state.request.goal instanceof InteractionNavigationGoal
                        && ((InteractionNavigationGoal)state.request.goal).actualContains(point,world.isRemote)) return true;
        } else if (!navigationSpace(world, player, point, false, deadline)) return false;
        if ("developer_teleport".equals(state.request.strategy) || point.near(state.request.destination)) return true;
        PlayerNavigation.Point target = state.destination(); double length = point.distance(target);
        if (support && Math.abs(point.y-target.y)>.0001) {
            PlayerNavigation.Point takeoff = state.waypoint == 0 ? state.start : state.route.get(state.waypoint-1);
            if (point.near(takeoff) && player.onGround) return true; // Held inputs are released until the height executor is proved.
            throw new BridgeException("unsupported", "A grounded height transition has no prepared native executor.");
        }
        // Recheck only the next half block from the real pose, so dynamic changes cannot use the old whole-route proof.
        for (int step = 1; step <= 2 && length > .000001; step++) {
            double fraction = Math.min(1, PlayerNavigation.STEP * step / length);
            PlayerNavigation.Point upcoming = new PlayerNavigation.Point(point.x + (target.x - point.x) * fraction,
                    point.y + (target.y - point.y) * fraction, point.z + (target.z - point.z) * fraction);
            if (support ? !navigationWalkingCorridor(world,player,state,point,upcoming,deadline,"next_segment")
                    : !navigationSpace(world, player, upcoming, false, deadline)) return false;
            if (fraction == 1) break;
        }
        return true;
    }

    private static double navigationWalkingFriction(World world, EntityPlayer player) {
        return world.getBlock(MathHelper.floor_double(player.posX),MathHelper.floor_double(player.boundingBox.minY)-1,
                MathHelper.floor_double(player.posZ)).slipperiness * .91;
    }

    private boolean navigationWalkingCorridor(World world, EntityPlayer player, PlayerNavigation.State state,
            PlayerNavigation.Point from, PlayerNavigation.Point to, long deadline, String stage) {
        int count = Math.max(1,(int)Math.ceil(from.distance(to)/WalkingPathPlanner.SWEEP_STEP));
        WalkingPathPlanner.Point previous = new WalkingPathPlanner.Point(from.x,from.y,from.z);
        for (int i=1;i<=count;i++) {
            if (System.nanoTime()>=deadline) return false;
            double f=i/(double)count;
            WalkingPathPlanner.Point next = new WalkingPathPlanner.Point(from.x+(to.x-from.x)*f,from.y,from.z+(to.z-from.z)*f);
            if (!navigationWalkingSpace(world,player,state,new WalkingPathPlanner.Sweep(previous,next,player.width,player.height),deadline,stage)) return false;
            previous=next;
        }
        return true;
    }

    private boolean navigationWalkingSpace(World world, EntityPlayer player, PlayerNavigation.State state,
            WalkingPathPlanner.Sweep sweep, long deadline, String stage) {
        WalkingPathPlanner.Check check=walkingSweepCheck(world,player,sweep,deadline,state,stage);
        if (check==WalkingPathPlanner.Check.PARTIAL) return false;
        if (check!=WalkingPathPlanner.Check.CLEAR) {
            throw new BridgeException("unsupported","Actual walking "+stage+" is "+check.name().toLowerCase(java.util.Locale.ROOT)+"; no movement fallback.");
        }
        return true;
    }

    private WalkingPathPlanner.Check walkingSweepCheck(World world, EntityPlayer player, WalkingPathPlanner.Sweep sweep,
            long deadline, PlayerNavigation.State state, String stage) {
        final JsonObject[] last = new JsonObject[1];
        final WalkingPathPlanner.Sweep[] lastSweep = new WalkingPathPlanner.Sweep[1];
        WalkingPathPlanner.Check check=WalkingSweepProof.inspect(sweep,(piece,limit) -> {
            lastSweep[0]=piece;
            last[0]=WorldObservationsAdapter.walkingSpaceAt(world,player,piece,limit,false);
            return PlayerNavigation.walkingCheck(last[0]);
        },deadline);
        if(state!=null && last[0]!=null && check!=WalkingPathPlanner.Check.CLEAR && check!=WalkingPathPlanner.Check.PARTIAL)
            recordNavigationFlightRejection(world,player,state,stage,lastSweep[0].body(),state.held,last[0]);
        return check;
    }

    private WalkingPathPlanner.Check navigationGroundedEdge(World world, EntityPlayer player,
            WalkingPathPlanner.Point from, WalkingPathPlanner.Point to, double width, double height, long deadline) {
        GroundedStep edge;
        try { edge = new GroundedStep(new PlayerNavigation.Point(from.x,from.y,from.z), new PlayerNavigation.Point(to.x,to.y,to.z),width,height); }
        catch (BridgeException unsupported) { return WalkingPathPlanner.Check.BLOCKED; }
        for (PlayerNavigation.Point point : new PlayerNavigation.Point[] {edge.start, edge.target}) {
            if (System.nanoTime()>=deadline) return WalkingPathPlanner.Check.PARTIAL;
            WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(walkingPointSpace(world,player,point,width,height,deadline));
            if (check != WalkingPathPlanner.Check.CLEAR) return check;
        }
        if (System.nanoTime()>=deadline) return WalkingPathPlanner.Check.PARTIAL;
        JsonObject corridor = WorldObservationsAdapter.spaceAt(world,player,edge.envelope,deadline,false);
        if ("partial".equals(PlayerNavigation.text(corridor,"status"))) return WalkingPathPlanner.Check.PARTIAL;
        return edge.envelopeClear(corridor) ? WalkingPathPlanner.Check.CLEAR : WalkingPathPlanner.Check.BLOCKED;
    }

    private JsonObject walkingPointSpace(World world, EntityPlayer player, PlayerNavigation.Point point,
            double width, double height, long deadline) {
        WalkingPathPlanner.Point feet = new WalkingPathPlanner.Point(point.x,point.y,point.z);
        return WorldObservationsAdapter.walkingSpaceAt(world,player,new WalkingPathPlanner.Sweep(feet,feet,width,height),deadline,false);
    }

    private List<WalkingPathPlanner.Sweep> navigationWalkingSweeps(PlayerNavigation.State state, double width, double height) {
        if(width!=state.walkingWidth || height!=state.walkingHeight) throw new BridgeException("invalid_state","Actual walking body differs from the planned body.");
        if(state.walkingProofSweeps!=null) return state.walkingProofSweeps;
        List<WalkingPathPlanner.Sweep> result = new java.util.ArrayList<WalkingPathPlanner.Sweep>();
        WalkingPathPlanner.Point previous = new WalkingPathPlanner.Point(state.start.x,state.start.y,state.start.z);
        result.add(new WalkingPathPlanner.Sweep(previous,previous,width,height));
        for (PlayerNavigation.Point value : state.route) {
            WalkingPathPlanner.Point target = new WalkingPathPlanner.Point(value.x,value.y,value.z);
            int count = Math.abs(previous.y-target.y)>.0001 ? 1 : Math.max(1,(int)Math.ceil(previous.distance(target)/WalkingPathPlanner.SWEEP_STEP));
            WalkingPathPlanner.Point last = previous;
            for (int i=1;i<=count;i++) {
                double f = i/(double)count;
                WalkingPathPlanner.Point next = new WalkingPathPlanner.Point(previous.x+(target.x-previous.x)*f,
                        previous.y+(target.y-previous.y)*f,previous.z+(target.z-previous.z)*f);
                result.add(new WalkingPathPlanner.Sweep(last,next,width,height)); last = next;
            }
            previous = target;
        }
        if (result.size()>(state.request.segmented ? 2048 : PlayerNavigation.MAX_PROBES)) throw new BridgeException("unsupported","Grounded route exceeds its finite proof budget.");
        state.walkingProofSweeps=java.util.Collections.unmodifiableList(result); return state.walkingProofSweeps;
    }

    private boolean navigationWalkingProof(World world, EntityPlayer player, PlayerNavigation.State state, long deadline, boolean client) {
        List<WalkingPathPlanner.Sweep> sweeps = navigationWalkingSweeps(state,player.width,player.height);
        int index = client ? state.clientProbe : state.serverProbe;
        while (index<sweeps.size() && System.nanoTime()<deadline) {
            WalkingPathPlanner.Sweep sweep = sweeps.get(index);
            WalkingPathPlanner.Check check = Math.abs(sweep.from.y-sweep.to.y)>.0001
                    ? navigationGroundedEdge(world,player,sweep.from,sweep.to,player.width,player.height,deadline)
                    : walkingSweepCheck(world,player,sweep,deadline,null,null);
            if (check == WalkingPathPlanner.Check.PARTIAL) return false;
            if (check != WalkingPathPlanner.Check.CLEAR) throw new BridgeException("unsupported","Grounded native route changed or is unsafe: "+check);
            index++; if (client) state.clientProbe = index; else state.serverProbe = index;
        }
        return index == sweeps.size();
    }

    private boolean navigationSpace(World world, EntityPlayer player, PlayerNavigation.Point point, boolean support, long deadline) {
        JsonObject body = WorldObservationsAdapter.spaceAt(world, player, point.x, point.y, point.z, player.width, player.height, deadline, support);
        if ("partial".equals(JsonData.string(body, "status"))) return false;
        if (!ApproachPlanner.safe(body, !support)) throw new BridgeException("unsupported", "The declared native corridor has unknown/unloaded/colliding/liquid/hazard/unsupported space; no movement fallback.");
        return true;
    }

    private boolean navigationTeleportSpace(World world, EntityPlayer player, PlayerNavigation.Point point, boolean support, long deadline) {
        JsonObject body = WorldObservationsAdapter.spaceAt(world, player, point.x, point.y, point.z, player.width, player.height, deadline, support);
        if ("partial".equals(PlayerNavigation.text(body,"status"))) return false;
        FlightPathPlanner.Check clearance = PlayerNavigation.flightCheck(body);
        if (clearance != FlightPathPlanner.Check.CLEAR)
            throw new BridgeException("unsupported", "Command teleport destination body is " + clearance.name().toLowerCase(java.util.Locale.ROOT) + "; no command was replayed.");
        if (support && !PlayerNavigation.teleportSupportProven(body))
            throw new BridgeException("unsupported", "Command teleport destination lacks complete safe support. Grounded command navigation cannot acquire flight; select native ability_flight or explicit protected mcb_teleport.");
        return true;
    }

    private GuardedContext.Result navigationClient(PlayerNavigation.State state, boolean serverReady, GuardedContext guard) {
        navigationRemaining(state); Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client);
        if (navigation != state || state.worldIdentity != client.theWorld || state.playerIdentity != client.thePlayer
                || !state.playerId.equals(client.thePlayer.getUniqueID().toString()) || client.thePlayer.inventory.getItemStack() != null
                || client.thePlayer.ridingEntity != null || client.thePlayer.riddenByEntity != null)
            throw new BridgeException("stale_world", "The actual client navigation actor/world/cursor changed.");
        JsonObject current = snapshot(client); guard.check(current); state.requireContext(current);
        if (!state.positionReady()) state.arrivalPoseReport.leaveArrival("actual_final_arrival_pending");
        state.observeNativeJump(client.thePlayer.movementInput.jump, clientTicks);
        if (!navigationBindingsCurrent(state, client)) throw new BridgeException("invalid_state", "Native navigation key bindings changed; only originally owned key codes are released.");
        long deadline = System.nanoTime() + WalkingPathPlanner.WORK_NANOS;
        if (state.request.goal instanceof NearbyNavigationGoal && "developer_teleport".equals(state.request.strategy) && !state.goalResolved) {
            releaseNavigationKeys(state);
            state.resolveGoal((point,workDeadline)->PlayerNavigation.walkingCheck(WorldObservationsAdapter.spaceAt(client.theWorld,
                    client.thePlayer,point.x,point.y,point.z,client.thePlayer.width,client.thePlayer.height,workDeadline,true)),deadline);
            return null;
        }
        if (!state.walkingRouteReady) {
            releaseNavigationKeys(state);
            state.prepareWalking(new WalkingPathPlanner.Probe() {
                public WalkingPathPlanner.Check endpoint(WalkingPathPlanner.Point point, double width, double height, long workDeadline) {
                    if (state.request.goal instanceof InteractionNavigationGoal)
                        return ((InteractionNavigationGoal) state.request.goal).prove(approachAccess(client.theWorld, client.thePlayer),
                                new PlayerNavigation.Point(point.x,point.y,point.z), client.playerController.getBlockReachDistance(), client.theWorld.getTotalWorldTime(), workDeadline, true);
                    return WalkingPathPlanner.Probe.super.endpoint(point,width,height,workDeadline);
                }
                public WalkingPathPlanner.Check inspect(WalkingPathPlanner.Sweep sweep, long workDeadline) {
                    // Keep planner sample accounting unchanged. Refinement is only
                    // for rechecking its conservative route from shifted live poses.
                    JsonObject observation = WorldObservationsAdapter.walkingSpaceAt(client.theWorld, client.thePlayer,
                            sweep,workDeadline,true);
                    WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(observation);
                    if (check == WalkingPathPlanner.Check.BLOCKED && sweep.from.x == state.start.x && sweep.from.y == state.start.y
                            && sweep.from.z == state.start.z && sweep.to.distance(sweep.from) == 0)
                        recordNavigationFlightRejection(client.theWorld,client.thePlayer,state,"walking_start",sweep.body(),state.held,observation);
                    return check;
                }
                public WalkingPathPlanner.Check step(WalkingPathPlanner.Point from, WalkingPathPlanner.Point to, double width, double height, long workDeadline) {
                    return navigationGroundedEdge(client.theWorld,client.thePlayer,from,to,width,height,workDeadline);
                }
            },
                    client.thePlayer.width, client.thePlayer.height, deadline);
            // Even on FOUND, wait for the next server callback to validate the complete published route.
            return null;
        }
        if (!state.flightRouteReady) {
            releaseNavigationKeys(state);
            state.prepareFlight((sweep, workDeadline) -> PlayerNavigation.flightCheck(
                    WorldObservationsAdapter.planningSpaceAt(client.theWorld, client.thePlayer, navigationFlightBody(client.thePlayer, sweep), workDeadline)),
                    client.thePlayer.width, client.thePlayer.height, deadline);
            return null;
        }
        if (!nearbyNavigationProof(state,client.theWorld,client.thePlayer)) { releaseNavigationKeys(state); return null; }
        if (applyServerFlightBrake(state,client,deadline)) return null;
        if (state.reanchorWalkingCorner(navigationMovementKeysIdle(client))) {
            releaseNavigationKeys(state);
            // Do not reuse serverReady from the old route: the next server callback proves the new geometry.
            return null;
        }
        if (!serverReady) { state.arrivalPoseReport.leaveArrival("original_server_proof_pending"); releaseNavigationKeys(state); return null; }
        if (!state.prepareWalkingStep(client.thePlayer.width,client.thePlayer.height,navigationMovementKeysIdle(client))) {
            releaseNavigationKeys(state); return null;
        }
        if (state.step != null) {
            if (!navigationStepSpace(client.theWorld, client.thePlayer, state, deadline)) { releaseNavigationKeys(state); return null; }
        } else if ("ability_flight".equals(state.request.strategy)) {
            if (!state.mutation && state.start.distance(state.current()) > .00001)
                throw new BridgeException("invalid_state", "The actual client start moved during flight planning/proof.");
            if (client.thePlayer.width != state.flightWidth || client.thePlayer.height != state.flightHeight)
                throw new BridgeException("invalid_state", "Actual flight body changed after bounded planning.");
            List<FlightPathPlanner.Sweep> sweeps = state.flightSweeps(client.thePlayer.width, client.thePlayer.height);
            while (state.clientProbe < sweeps.size() && System.nanoTime() < deadline) {
                if (!navigationFlightSpace(client.theWorld, client.thePlayer, sweeps.get(state.clientProbe), deadline, state, "route", state.flightKeys)) return null;
                state.clientProbe++;
            }
            if (state.clientProbe < sweeps.size()) return null;
            if (!state.request.finishFlying && !navigationFlightLandingSpace(client.theWorld, client.thePlayer, state, deadline)) return null;
        } else if ("walking".equals(state.request.strategy)) {
            if (!navigationWalkingProof(client.theWorld,client.thePlayer,state,deadline,true)) return null;
        } else {
            List<PlayerNavigation.Point> probes = PlayerNavigation.probes(state.start, state.route, "developer_teleport".equals(state.request.strategy));
            while (state.clientProbe < probes.size() && System.nanoTime() < deadline) {
                PlayerNavigation.Point point = probes.get(state.clientProbe);
                boolean supported = "walking".equals(state.request.strategy) || state.clientProbe == probes.size() - 1 && state.requiresLanding();
                if (!navigationTeleportSpace(client.theWorld, client.thePlayer, point, supported, deadline)) return null;
                state.clientProbe++;
            }
            if (state.clientProbe < probes.size()) return null;
        }
        if (state.request.goal instanceof InteractionNavigationGoal && state.waypoint==state.route.size()-1 && client.thePlayer.onGround
                && ((InteractionNavigationGoal)state.request.goal).reachDistance(state.current())<=.00001) {
            InteractionNavigationGoal goal = (InteractionNavigationGoal)state.request.goal;
            WalkingPathPlanner.Check check = goal.proveActual(approachAccess(client.theWorld,client.thePlayer),state.current(),
                    client.playerController.getBlockReachDistance(),client.theWorld.getTotalWorldTime(),deadline,true);
            if (check == WalkingPathPlanner.Check.PARTIAL) { releaseNavigationKeys(state); return null; }
            if (check != WalkingPathPlanner.Check.CLEAR && state.current().near(state.request.destination, state.walkingTolerance()))
                throw new BridgeException("target_unverified","The actual client landing does not prove the target's normal reach and visible face.");
            if (check==WalkingPathPlanner.Check.CLEAR) state.request.lookAt = goal.aim(state.current());
        }
        boolean stopping = navigationCrosshairStop(state, client, deadline, () -> {
            if (!state.mutation) { guard.navigationMutationStarting(current); state.mutation = true; state.mutationSample = PlayerNavigation.sample(PlayerNavigation.object(current, "serverControl")); }
        });
        if (stopping && !state.crosshairStopping) return null;
        if (navigationInputArrivalReady(state, client, deadline)) {
            releaseNavigationKeys(state);
            if (state.request.lookAt != null && state.request.crosshair == null) {
                double[] aim = PlayerNavigation.aim(state.current(), client.thePlayer.getPosition(1F).yCoord - client.thePlayer.boundingBox.minY, state.request.lookAt);
                if (!state.mutation && (PlayerNavigation.angleDifference(client.thePlayer.rotationYaw, aim[0]) > .02 || Math.abs(client.thePlayer.rotationPitch - aim[1]) > .02)) {
                    guard.navigationMutationStarting(current); state.mutation = true; state.mutationSample = PlayerNavigation.sample(PlayerNavigation.object(current, "serverControl"));
                }
                client.thePlayer.rotationYaw = (float) aim[0]; client.thePlayer.rotationPitch = (float) aim[1]; state.aiming = true;
            }
            PlayerNavigation.ActualArrivalPose actual = navigationArrivalPose(client);
            state.arrivalPoseReport.observe(clientTicks, actual, navigationMovementKeysIdle(client));
            if (state.acknowledged()) {
                FlightObservation epoch = navigationArrivalEpoch(state, client);
                if (!state.arrivalPoseReport.completion(epoch != null, epoch == null ? -1 : epoch.capturedAt,
                        epoch == null ? -1 : PlayerNavigation.sample(epoch.control), epoch == null ? -1 : epoch.worldTick)) return null;
                OwnedKeyState.clear(PlayerNavigation.OPERATION); state.ownerClearConfirmed = true; navigation = null; inputDiagnostics.release(PlayerNavigation.OPERATION); return guard.result(state.completed(clientTicks), state.before);
            }
            navigationReportActualArrival(state, client, actual);
            return null;
        }
        if (state.step == null && !"ability_flight".equals(state.request.strategy) && !navigationSweep(client.theWorld, client.thePlayer, state, state.current(), deadline)) { releaseNavigationKeys(state); return null; }
        java.util.Set<String> next = state.next(clientTicks, client.thePlayer.capabilities.getFlySpeed());
        if (state.step != null) next = state.step.forwardSafeKeys(next, clientTicks);
        if ("ability_flight".equals(state.request.strategy) && !navigationFlightSweep(client.theWorld, client.thePlayer, state, next, deadline, true)) {
            releaseNavigationKeys(state); return null;
        }
        if (!next.isEmpty() || "developer_teleport".equals(state.request.strategy) && !state.teleportSent) {
            if (!state.mutation) { guard.navigationMutationStarting(current); state.mutation = true; state.mutationSample = PlayerNavigation.sample(PlayerNavigation.object(current, "serverControl")); }
        }
        if ("developer_teleport".equals(state.request.strategy)) {
            if (!state.teleportSent) { state.teleportSent = true; PlayerNavigation.Point point = state.request.destination;
                JsonObject destination = WorldObservationsAdapter.spaceAt(client.theWorld, client.thePlayer,
                        point.x, point.y, point.z, client.thePlayer.width, client.thePlayer.height, deadline, true);
                if (!"observed".equals(PlayerNavigation.text(destination, "status")))
                    throw new BridgeException("invalid_state", "Teleport destination support is not fully observed.");
                if (!PlayerNavigation.flag(destination, "fullSupportCoverage")
                        && (!client.thePlayer.capabilities.isFlying || !PlayerNavigation.flag(PlayerNavigation.object(PlayerNavigation.object(current, "serverControl"), "pose"), "flying")))
                    throw new BridgeException("invalid_state", "Airborne command teleport requires already confirmed client/server flight; use creative mcb_teleport for automatic flight protection.");
                client.thePlayer.sendChatMessage("/tp " + Double.toString(point.x) + " " + Double.toString(point.y) + " " + Double.toString(point.z)); }
        } else {
            PlayerNavigation.Point target = state.destination();
            if (state.step != null) { client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(state.step.dz, state.step.dx)) - 90); client.thePlayer.rotationPitch = 0; }
            else if (("ability_flight".equals(state.request.strategy) || "walking".equals(state.request.strategy)) && (next.contains("forward") || next.contains("back"))) client.thePlayer.rotationYaw = (float) state.steeringYaw;
            else if (next.contains("forward")) client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(target.z - client.thePlayer.posZ, target.x - client.thePlayer.posX)) - 90);
            if (state.step == null && (next.contains("forward") || next.contains("back") || next.contains("jump") || next.contains("sneak")))
                client.thePlayer.rotationPitch = (float) PlayerNavigation.travelPitch(state.current(), target, "walking".equals(state.request.strategy));
            setNavigationKeys(state, next, client);
        }
        return null;
    }

    private boolean navigationInputArrivalReady(PlayerNavigation.State state, Minecraft client, long deadline) {
        if (!state.inputArrivalReady(client.thePlayer.capabilities.getFlySpeed())) return false;
        return !"ability_flight".equals(state.request.strategy)
                || navigationFlightSweep(client.theWorld, client.thePlayer, state, java.util.Collections.<String>emptySet(), deadline);
    }

    private boolean navigationCrosshairPhase(PlayerNavigation.State state, EntityPlayer player) {
        if (state.request.crosshair == null || !state.routeReady() || !state.crosshairRouteEligible()
                || state.step != null && !state.step.arrival(state.latest)) return false;
        return "ability_flight".equals(state.request.strategy)
                ? state.flightObserved && (state.request.finishFlying ? player.capabilities.isFlying && !player.onGround : player.onGround && !player.capabilities.isFlying)
                : player.onGround && !player.capabilities.isFlying;
    }

    private void navigationCrosshairServer(PlayerNavigation.State state, EntityPlayerMP player) {
        CrosshairArrival arrival = state.request.crosshair;
        if (arrival == null) return;
        arrival.clear(false);
        if (!navigationCrosshairPhase(state, player)) return;
        arrival.observe(crosshairAccess(player.worldObj, player), SurvivalConstructionAdapter.serverEye(player.posX,player.posY,player.posZ,player.getEyeHeight()), player.getLook(1F),
                player.theItemInWorldManager.getBlockReachDistance(), new PlayerNavigation.Point(player.posX,player.boundingBox.minY,player.posZ),
                player.rotationYaw, player.rotationPitch, player.worldObj.getTotalWorldTime(),
                System.nanoTime()+WalkingPathPlanner.WORK_NANOS, false, null);
    }

    /** Owning-client final segment only. Normal movement yaw is untouched until its keys are released. */
    private boolean navigationCrosshairStop(PlayerNavigation.State state, Minecraft client, long deadline, Runnable mutationStarting) {
        CrosshairArrival arrival = state.request.crosshair;
        if (arrival == null) return false;
        arrival.clear(true);
        if (!navigationCrosshairPhase(state, client.thePlayer) || client.renderViewEntity != client.thePlayer) {
            if (state.crosshairStopping) arrival.lost(); state.crosshairStopping = false; return false;
        }
        CrosshairArrival.Access access = crosshairAccess(client.theWorld, client.thePlayer);
        Vec3 eye = client.thePlayer.getPosition(1F); double reach = client.playerController.getBlockReachDistance();
        if (!arrival.withinReach(eye, reach)) {
            if (state.crosshairStopping) arrival.lost(); state.crosshairStopping = false; return false;
        }
        boolean safe = "ability_flight".equals(state.request.strategy)
                ? navigationFlightSweep(client.theWorld,client.thePlayer,state,java.util.Collections.<String>emptySet(),deadline)
                : navigationSweep(client.theWorld,client.thePlayer,state,state.current(),deadline);
        if (!safe) { releaseNavigationKeys(state); return true; }
        float beforeYaw = client.thePlayer.rotationYaw, beforePitch = client.thePlayer.rotationPitch;
        client.entityRenderer.getMouseOver(1F);
        boolean hit = arrival.observe(access,eye,client.thePlayer.getLook(1F),reach,state.current(),beforeYaw,beforePitch,
                clientTicks,deadline,true,client.objectMouseOver);
        if (!hit) {
            PlayerNavigation.Point aimPoint = arrival.aim(access,eye,reach,deadline);
            if (aimPoint != null) {
                double[] aim = PlayerNavigation.aim(state.current(),eye.yCoord-client.thePlayer.boundingBox.minY,aimPoint);
                mutationStarting.run(); releaseNavigationKeys(state);
                client.thePlayer.rotationYaw = (float)aim[0]; client.thePlayer.rotationPitch = (float)aim[1];
                client.entityRenderer.getMouseOver(1F);
                hit = arrival.observe(access,eye,client.thePlayer.getLook(1F),reach,state.current(),client.thePlayer.rotationYaw,client.thePlayer.rotationPitch,
                        clientTicks,deadline,true,client.objectMouseOver);
                if (!hit) {
                    client.thePlayer.rotationYaw = beforeYaw; client.thePlayer.rotationPitch = beforePitch;
                    client.entityRenderer.getMouseOver(1F);
                }
            }
        }
        if (hit) {
            releaseNavigationKeys(state); state.crosshairStopping = true; state.aiming = true;
            // Refresh the pose after an actual rotation; do not bind the hit to the preceding snapshot.
            state.requireContext(snapshot(client)); return true;
        }
        if (state.crosshairStopping) arrival.lost(); state.crosshairStopping = false; return false;
    }

    private static CrosshairArrival.Access crosshairAccess(World world, EntityPlayer player) {
        ApproachPlanner.Access blocks = approachAccess(world,player);
        return new CrosshairArrival.Access() {
            public boolean loaded(int x,int y,int z) { return blocks.loaded(x,y,z); }
            public NativePlacementAdapter.State state(int x,int y,int z) { return blocks.state(x,y,z); }
            public MovingObjectPosition trace(Vec3 eye,Vec3 end) { return blocks.trace(eye,end); }
            public AxisAlignedBB selectedBounds(int x,int y,int z) { return blocks.selectedBounds(x,y,z); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return blocks.selectedPart(hit); }
            public AxisAlignedBB partBounds(int x,int y,int z,JsonObject part) { return blocks.partBounds(x,y,z,part); }
            public java.util.List<AxisAlignedBB> partBoxes(int x,int y,int z,JsonObject part) { return blocks.partBoxes(x,y,z,part); }
            public JsonObject space(int x,int y,int z,double width,double height,long deadline) { return blocks.space(x,y,z,width,height,deadline); }
            public boolean entityOccluded(Vec3 eye,Vec3 end,double distance) {
                AxisAlignedBB ray = AxisAlignedBB.getBoundingBox(Math.min(eye.xCoord,end.xCoord),Math.min(eye.yCoord,end.yCoord),Math.min(eye.zCoord,end.zCoord),
                        Math.max(eye.xCoord,end.xCoord),Math.max(eye.yCoord,end.yCoord),Math.max(eye.zCoord,end.zCoord)).expand(1,1,1);
                java.util.List<?> entities = world.getEntitiesWithinAABBExcludingEntity(player,ray);
                if (entities.size()>256) return true;
                for (Object value : entities) {
                    net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity)value;
                    if (!entity.canBeCollidedWith()) continue;
                    if (entity.boundingBox == null) return true;
                    float border = entity.getCollisionBorderSize(); AxisAlignedBB body = entity.boundingBox.expand(border,border,border);
                    if (body.isVecInside(eye)) return true;
                    MovingObjectPosition intercept = body.calculateIntercept(eye,end);
                    if (intercept != null && eye.distanceTo(intercept.hitVec) <= distance+.000001) return true;
                }
                return false;
            }
        };
    }

    private PlayerNavigation.ActualArrivalPose navigationArrivalPose(Minecraft client) {
        return new PlayerNavigation.ActualArrivalPose(client.thePlayer.posX, client.thePlayer.boundingBox.minY,
                client.thePlayer.posY, client.thePlayer.posZ, client.thePlayer.rotationYaw, client.thePlayer.rotationPitch,
                client.thePlayer.onGround, client.thePlayer.capabilities.isFlying, client.thePlayer.motionX, client.thePlayer.motionY, client.thePlayer.motionZ,
                client.thePlayer.movementInput != null, client.thePlayer.movementInput == null ? Double.NaN : client.thePlayer.movementInput.moveForward,
                client.thePlayer.movementInput == null ? Double.NaN : client.thePlayer.movementInput.moveStrafe,
                client.thePlayer.movementInput != null && client.thePlayer.movementInput.jump,
                client.thePlayer.movementInput != null && client.thePlayer.movementInput.sneak, client.thePlayer.isSprinting());
    }

    private boolean navigationMovementKeysIdle(Minecraft client) {
        for (String name : new String[] {"forward", "back", "left", "right", "jump", "sneak", "sprint"})
            if (keyBinding(client, name).getIsKeyPressed()) return false;
        return true;
    }

    /** Bind one immutable published server capture to the existing ACK snapshot. No server entity read. */
    private FlightObservation navigationArrivalEpoch(PlayerNavigation.State state, Minecraft client) {
        FlightObservation observed = serverFlight; ServerContext context = serverContext;
        if (observed == null || context == null || observed.control == null || !context.developer
                || observed.server != context.server || observed.server != state.authoritativeServer
                || !observed.playerId.equals(context.playerId) || !observed.playerId.equals(client.thePlayer.getUniqueID())
                || !observed.playerId.toString().equals(state.playerId) || observed.dimension != state.request.dimension
                || observed.dimension != client.thePlayer.dimension || System.nanoTime() - observed.capturedAt > 1000000000L
                || !state.request.session.equals(PlayerNavigation.text(observed.control, "sessionId"))
                || PlayerNavigation.sample(observed.control) != PlayerNavigation.sample(PlayerNavigation.object(state.latest, "serverControl"))) return null;
        return serverFlight == observed ? observed : null;
    }

    private void navigationReportActualArrival(PlayerNavigation.State state, Minecraft client, PlayerNavigation.ActualArrivalPose actual) {
        PlayerNavigation.ArrivalPoseReport report = state.arrivalPoseReport;
        if (!report.eligible(state, actual, true)) return;
        if (client.thePlayer.isDead || client.thePlayer.isPlayerSleeping() || client.thePlayer.sendQueue == null) { report.reason = "actual_actor_or_send_queue_unavailable"; return; }
        long deadline = Math.min(state.deadline, System.nanoTime() + FlightPathPlanner.WORK_NANOS);
        report.bodyProof = false;
        if ("ability_flight".equals(state.request.strategy)) {
            if (!navigationFlightSweep(client.theWorld, client.thePlayer, state, state.flightKeys, deadline)) { report.reason = "current_flight_body_partial"; return; }
        } else {
            WalkingPathPlanner.Point point = new WalkingPathPlanner.Point(client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ);
            JsonObject body = WorldObservationsAdapter.walkingSpaceAt(client.theWorld, client.thePlayer,
                    new WalkingPathPlanner.Sweep(point,point,client.thePlayer.width,client.thePlayer.height),deadline,false);
            WalkingPathPlanner.Check check = PlayerNavigation.walkingCheck(body);
            if (check != WalkingPathPlanner.Check.CLEAR) { report.reason = "current_walking_body_" + check.name().toLowerCase(java.util.Locale.ROOT); return; }
        }
        report.bodyProof = true; navigationRemaining(state);
        actual = navigationArrivalPose(client);
        if (!navigationMovementKeysIdle(client)) { report.leaveArrival("actual_native_key_pending"); return; }
        if (!report.eligible(state, actual, true)) return;
        FlightObservation epoch = navigationArrivalEpoch(state, client);
        if (epoch == null) { report.reason = "matching_actual_epoch_pending"; return; }
        navigationRemaining(state);
        report.sendCaptureAt = epoch.capturedAt;
        report.gapBeforeSend = state.current().distance(PlayerNavigation.pose(PlayerNavigation.object(PlayerNavigation.object(state.latest, "serverControl"), "pose")));
        report.dispatch(actual, clientTicks, PlayerNavigation.sample(epoch.control), epoch.worldTick,
                packet -> client.thePlayer.sendQueue.addToSendQueue(packet), System::nanoTime);
    }

    private void setNavigationKeys(PlayerNavigation.State state, java.util.Set<String> next, Minecraft client) {
        state.requireBoundKeys(next);
        state.dispatched(next, clientTicks);
        Map<String, KeyBinding> keys = keyBindings(client);
        for (String name : new java.util.LinkedHashSet<String>(state.held)) if (!next.contains(name)) {
            OwnedKeyState.release(PlayerNavigation.OPERATION, keys.get(name));
            boolean physical = restoreNavigationKey(state.keyCodes.get(name));
            inputDiagnostics.writeNative(PlayerNavigation.OPERATION, name, keys.get(name), state.keyCodes.get(name), physical, clientTicks); state.held.remove(name);
        }
        for (String name : next) {
            OwnedKeyState.hold(PlayerNavigation.OPERATION, keys.get(name), state.keyCodes.get(name));
            KeyBinding.setKeyBindState(state.keyCodes.get(name), true); state.held.add(name);
            inputDiagnostics.writeNative(PlayerNavigation.OPERATION, name, keys.get(name), state.keyCodes.get(name), true, clientTicks);
        }
        state.keyReleaseConfirmed = next.isEmpty();
    }

    private boolean navigationBindingsCurrent(PlayerNavigation.State state, Minecraft client) {
        Map<String, KeyBinding> keys = keyBindings(client);
        for (Map.Entry<String, Integer> binding : state.keyCodes.entrySet()) if (keys.get(binding.getKey()).getKeyCode() != binding.getValue()) return false;
        return true;
    }

    private boolean restoreNavigationKey(int code) {
        boolean physical = code >= 0 ? Keyboard.isCreated() && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code)
                : Mouse.isCreated() && code + 100 >= 0 && code + 100 < Mouse.getButtonCount() && Mouse.isButtonDown(code + 100);
        KeyBinding.setKeyBindState(code, physical);
        return physical;
    }

    private void releaseNavigationKeys(PlayerNavigation.State state) {
        OwnedKeyState.releaseAll(PlayerNavigation.OPERATION);
        if (state.abort) OwnedKeyState.clear(PlayerNavigation.OPERATION);
        Map<String, KeyBinding> keys = keyBindings(Minecraft.getMinecraft());
        for (String name : state.held) {
            boolean physical = restoreNavigationKey(state.keyCodes.get(name));
            inputDiagnostics.writeNative(PlayerNavigation.OPERATION, name, keys.get(name), state.keyCodes.get(name), physical, clientTicks);
        }
        state.held.clear(); state.flightKeys = java.util.Collections.emptySet();
        state.keyReleaseConfirmed = OwnedKeyState.heldCount() == 0;
        if (state.abort) state.ownerClearConfirmed = !OwnedKeyState.active(PlayerNavigation.OPERATION);
        if (state.latest != null) state.trace(clientTicks, "keys_released", state.held);
        if (state.abort) inputDiagnostics.release(PlayerNavigation.OPERATION);
    }

    private boolean applyServerFlightBrake(PlayerNavigation.State state, Minecraft client, long deadline) {
        if (!state.serverFlightBrakePending()) return false;
        releaseNavigationKeys(state);
        state.applyServerFlightBrake(() -> navigationFlightSweep(client.theWorld,client.thePlayer,state,
                java.util.Collections.<String>emptySet(),deadline),clientTicks);
        return true;
    }

    private void maintainNavigation(Minecraft client) {
        PlayerNavigation.State state = navigation; if (state == null) return;
        if (state.abort || System.nanoTime() >= state.deadline || !"developer".equals(profile) || !background.active(client) || !OwnedKeyState.active(PlayerNavigation.OPERATION)
                || client.theWorld != state.worldIdentity || client.thePlayer != state.playerIdentity || client.currentScreen != null || client.isGamePaused()
                || !navigationBindingsCurrent(state, client)) {
            state.abort = true; releaseNavigationKeys(state); navigation = null;
        } else if (state.step != null && state.mutation) {
            try {
                JsonObject current = snapshot(client); state.requireContext(current);
                state.observeNativeJump(client.thePlayer.movementInput.jump, clientTicks);
                if (System.nanoTime() - state.serverSafetyAt > 100000000L) { releaseNavigationKeys(state); return; }
                if (!navigationStepSpace(client.theWorld, client.thePlayer, state, System.nanoTime() + WalkingPathPlanner.WORK_NANOS)) {
                    releaseNavigationKeys(state); return;
                }
                if (state.positionReady() || state.step.arrival(current)) { releaseNavigationKeys(state); return; }
                java.util.Set<String> next = state.next(clientTicks);
                next = state.step.forwardSafeKeys(next, clientTicks);
                client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(state.step.dz, state.step.dx)) - 90);
                client.thePlayer.rotationPitch = 0;
                setNavigationKeys(state, next, client);
            } catch (BridgeException error) {
                state.failure = error; state.abort = true; releaseNavigationKeys(state); navigation = null;
            }
        } else if ("ability_flight".equals(state.request.strategy) && state.mutation && state.routeReady()
                && state.clientProbe == state.flightSweeps(state.flightWidth, state.flightHeight).size()) {
            try {
                // A key lease is never permission to coast through subsequent unchecked native ticks.
                JsonObject current = snapshot(client); state.requireContext(current);
                state.observeNativeJump(client.thePlayer.movementInput.jump, clientTicks);
                if (applyServerFlightBrake(state,client,System.nanoTime()+FlightPathPlanner.WORK_NANOS)) return;
                if (System.nanoTime() - state.serverSafetyAt > 100000000L) { releaseNavigationKeys(state); return; }
                long deadline = System.nanoTime() + FlightPathPlanner.WORK_NANOS;
                if (navigationCrosshairStop(state,client,deadline,() -> {})) return;
                if (navigationInputArrivalReady(state, client, deadline)) { releaseNavigationKeys(state); return; }
                java.util.Set<String> next = state.next(clientTicks, client.thePlayer.capabilities.getFlySpeed());
                if (!navigationFlightSweep(client.theWorld, client.thePlayer, state, next, deadline, true)) {
                    releaseNavigationKeys(state); return;
                }
                if (next.contains("forward") || next.contains("back")) client.thePlayer.rotationYaw = (float) state.steeringYaw;
                if (!next.isEmpty()) client.thePlayer.rotationPitch = (float) PlayerNavigation.travelPitch(state.current(), state.destination(), false);
                setNavigationKeys(state, next, client);
            } catch (BridgeException error) {
                state.failure = error; state.abort = true; releaseNavigationKeys(state); navigation = null;
            }
        } else if ("walking".equals(state.request.strategy) && state.mutation && state.routeReady()) {
            try {
                JsonObject current = snapshot(client); state.requireContext(current);
                if (System.nanoTime()-state.serverSafetyAt>100000000L) { releaseNavigationKeys(state); return; }
                if (!state.prepareWalkingStep(client.thePlayer.width,client.thePlayer.height,navigationMovementKeysIdle(client)) || state.step != null) {
                    releaseNavigationKeys(state); return;
                }
                if (navigationCrosshairStop(state,client,System.nanoTime()+WalkingPathPlanner.WORK_NANOS,() -> {})) return;
                if (navigationInputArrivalReady(state,client,System.nanoTime()+WalkingPathPlanner.WORK_NANOS)) { releaseNavigationKeys(state); return; }
                if (!navigationSweep(client.theWorld,client.thePlayer,state,state.current(),System.nanoTime()+WalkingPathPlanner.WORK_NANOS)) {
                    releaseNavigationKeys(state); return;
                }
                java.util.Set<String> next = state.next(clientTicks);
                PlayerNavigation.Point target = state.destination();
                if (next.contains("forward") || next.contains("back")) client.thePlayer.rotationYaw = (float)state.steeringYaw;
                client.thePlayer.rotationPitch = 0; setNavigationKeys(state,next,client);
            } catch (BridgeException error) {
                state.failure = error; state.abort = true; releaseNavigationKeys(state); navigation = null;
            }
        } else {
            Map<String, KeyBinding> keys = keyBindings(client);
            for (String name : state.held) {
                OwnedKeyState.hold(PlayerNavigation.OPERATION, keys.get(name), state.keyCodes.get(name));
                KeyBinding.setKeyBindState(state.keyCodes.get(name), true);
                inputDiagnostics.writeNative(PlayerNavigation.OPERATION, name, keys.get(name), state.keyCodes.get(name), true, clientTicks);
            }
        }
    }

    private JsonObject placeBlock(Minecraft client, JsonObject args) {
        return placeBlock(client, args, null);
    }

    private JsonObject placeBlock(Minecraft client, JsonObject args, Runnable nativeStarting) {
        if (args.has("attachmentBinding")) throw new BridgeException("invalid_request", "Exact attachment placement requires the guarded dual-side native route.");
        requireWorldControls(client);
        refreshServerContext(client);
        ServerContext context = requireDeveloperContext();
        requireExpectedWorld(context, args);
        NativePlacementAdapter.Plan plan = NativePlacementAdapter.plan(client, args, context.dimension);
        releaseInput();
        JsonObject result = NativePlacementAdapter.invoke(plan, client.thePlayer.inventory, use -> interact(client, use, nativeStarting));
        result.addProperty("sessionId", sessionId);
        return result;
    }

    /** Called by an HTTP worker only. No game thread waits on the other owning queue. */
    private GuardedContext.Result approach(final JsonObject args, GameQueue.Control control, final GuardedContext guard)
            throws ExecutionException, InterruptedException {
        return ApproachPlanner.dispatch(clientQueue, serverQueue, control, approachStages(args, guard));
    }

    private void maintainApproachEyeReads() {
        java.util.Iterator<ApproachPlanner.EyeRead> pending = approachEyeReads.iterator();
        while (pending.hasNext()) {
            ApproachPlanner.EyeRead read = pending.next(); read.tick(clientTicks);
            if (read.future.isDone()) pending.remove();
        }
    }

    private ApproachPlanner.Stages approachStages(final JsonObject args, final GuardedContext guard) {
        return new ApproachPlanner.Stages() {
            private boolean capturedCreative;
            private JsonObject eyeSettlingReceipt = ApproachPlanner.EyeRead.immediate();
            private long captureClientTick = -1;
            public JsonObject eyeObservation() { return eyeSettlingReceipt; }
            public ApproachPlanner.Frame capture() {
                ApproachPlanner.Frame frame = captureApproach(args);
                if (captureClientTick < 0) captureClientTick = clientTicks;
                capturedCreative = Minecraft.getMinecraft().thePlayer.capabilities.isCreativeMode;
                if (guard != null) guard.check(snapshot(Minecraft.getMinecraft()));
                return frame;
            }
            public ApproachPlanner.Frame ready(ApproachPlanner.Frame frame, long deadline, GameQueue.Control control)
                    throws ExecutionException, InterruptedException {
                if (!frame.needsEyeSettle()) return frame;
                final java.util.concurrent.atomic.AtomicReference<ApproachPlanner.EyeRead> owned = new java.util.concurrent.atomic.AtomicReference<ApproachPlanner.EyeRead>();
                Runnable cancellation = () -> { ApproachPlanner.EyeRead read = owned.get(); if (read != null) read.close(); };
                control.onReadCancellation(cancellation);
                try {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new BridgeException("timeout", "Approach eye observation reached its original read deadline.");
                    ApproachPlanner.EyeRead read = clientQueue.await(clientQueue.submit(() -> {
                        approachEyeReads.removeIf(value -> value.future.isDone());
                        if (approachEyeReads.size() >= ApproachPlanner.EyeRead.MAX_ACTIVE) throw new BridgeException("busy", "The bounded pure approach eye observation queue is full.");
                        ApproachPlanner.EyeRead value = new ApproachPlanner.EyeRead(frame, () -> {
                            ApproachPlanner.Frame current = captureApproach(args);
                            Minecraft client = Minecraft.getMinecraft();
                            if (client.thePlayer.capabilities.isCreativeMode != capturedCreative) throw new BridgeException("permission_denied", "Approach player game mode changed during eye observation.");
                            if (guard != null) guard.check(snapshot(client));
                            return current;
                        }, clientTicks, deadline, System::nanoTime);
                        owned.set(value); if (control.isCancelled()) value.close();
                        approachEyeReads.add(value); return value;
                    }, remaining, control), remaining);
                    ApproachPlanner.Frame current;
                    long nativeAwaitStarted = System.nanoTime();
                    try { current = read.await(); }
                    finally { control.recordNativeAwait(System.nanoTime() - nativeAwaitStarted); }
                    eyeSettlingReceipt = read.receipt(); return current;
                } finally {
                    control.clearReadCancellation(cancellation); ApproachPlanner.EyeRead read = owned.get(); if (read != null) read.close();
                }
            }
            public ApproachPlanner.Search search(ApproachPlanner.Frame frame, ApproachPlanner.Search previous) {
                ServerContext context = requireDeveloperContext();
                if (context.server != frame.serverIdentity || context.dimension != frame.request.dimension
                        || !context.playerId.toString().equals(frame.playerId) || context.paused || context.screenOpen != frame.screenOpen
                        || context.screenIdentity != frame.screenIdentity
                        || !context.screenId.equals(frame.screenId)
                        || System.nanoTime() - context.capturedAt > 1000000000L)
                    throw new BridgeException("stale_world", "Approach server context changed or is not fresh and unpaused.");
                requireExpectedWorld(context, args);
                EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
                if (player.isDead || player.worldObj == null || player.worldObj.isRemote || player.dimension != frame.request.dimension
                        || !frame.playerId.equals(player.getUniqueID().toString()))
                    throw new BridgeException("world_unavailable", "The authoritative approach player is unavailable.");
                World world = player.worldObj;
                if (player.capabilities.isCreativeMode != capturedCreative) throw new BridgeException("permission_denied", "Approach client and authoritative player game modes disagree.");
                if (player.ridingEntity != null || player.riddenByEntity != null) throw new BridgeException("invalid_state", "Approach planning requires the current unmounted server player.");
                if (!NativePlacementAdapter.access(world, player.dimension).loaded(frame.request.x, frame.request.y, frame.request.z))
                    throw new BridgeException("chunk_unloaded", "Approach target must actually be loaded on the authoritative server.");
                TeleportPolicy.requireFlight(frame.request.requireFlying, player.capabilities.allowFlying, player.capabilities.isFlying);
                requireApproachShape(player);
                if (Math.abs(player.width - frame.width) > .0001 || Math.abs(player.height - frame.height) > .0001)
                    throw new BridgeException("unsupported", "Client and server player body dimensions differ.");
                if (frame.request.slot >= 0) {
                    ItemStack item = player.inventory.getStackInSlot(frame.request.slot); NativePlacementAdapter.requireItem(item);
                    if (!JsonData.item(item).toString().equals(frame.sourceItem) || !approachTag(item).equals(frame.sourceTag))
                        throw new BridgeException("target_unverified", "The actual server hotbar material differs from the client sample.");
                }
                if (player.theItemInWorldManager == null || player.theItemInWorldManager.thisPlayerMP != player
                        || player.theItemInWorldManager.theWorld != world)
                    throw new BridgeException("unsupported", "The same authoritative approach actor's public normal reach manager is unavailable.");
                double normalReach = player.theItemInWorldManager.getBlockReachDistance();
                if (!Double.isFinite(normalReach) || normalReach <= 0 || normalReach > 32)
                    throw new BridgeException("unsupported", "The owning-thread actual server normal reach is not finite and bounded.");
                JsonObject flight = serverPosition(player); flight.addProperty("available", true); flight.addProperty("creative", player.capabilities.isCreativeMode);
                flight.addProperty("dimension", player.dimension); flight.addProperty("worldTick", world.getTotalWorldTime());
                flight.addProperty("source", "integrated_server"); flight.addProperty("playerId", player.getUniqueID().toString());
                flight.addProperty("normalReach", normalReach);
                ApproachPlanner.Search search = ApproachPlanner.search(approachAccess(world, player), frame, flight, previous, System::nanoTime);
                search.target.addProperty("worldTick", world.getTotalWorldTime());
                return search;
            }
            public GuardedContext.Result finish(ApproachPlanner.Frame frame, ApproachPlanner.Search search) {
                ApproachPlanner.Frame after = captureApproach(args); frame.check(after);
                long recheckClientTick = clientTicks;
                Minecraft client = Minecraft.getMinecraft();
                if (client.thePlayer.capabilities.isCreativeMode != capturedCreative) throw new BridgeException("permission_denied", "Approach player game mode changed during planning.");
                JsonObject data = ApproachPlanner.finish(approachAccess(client.theWorld, client.thePlayer), after, search,
                        client.theWorld.getTotalWorldTime(), System::nanoTime);
                data.getAsJsonObject("current").addProperty("captureWorldTick", frame.captureWorldTick);
                data.add("eyeSettling", eyeSettlingReceipt);
                JsonObject context = snapshot(client);
                ApproachPlanner.clientPhaseClock(data, captureClientTick, recheckClientTick, context.get("clientTicks").getAsLong());
                if (guard != null) { guard.check(context); return guard.result(data, context); }
                return new GuardedContext.Result(data, null);
            }
        };
    }

    /** Client/server identities are copied on their owning queues; server stages never dereference client objects. */
    private class SurvivalContext {
        final JsonObject args;
        final GuardedContext guard;
        final int dimension;
        final boolean requireSurvivalFlight;
        Object serverIdentity;
        String playerId;
        JsonObject current;
        double reach;
        boolean actorCreative;
        boolean acceptsCreative() { return false; }
        SurvivalContext(JsonObject args, GuardedContext guard, int dimension) { this.args = args; this.guard = guard; this.dimension = dimension; this.requireSurvivalFlight = GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false); }
        Minecraft client(SurvivalConstructionAdapter.State state) {
            state.check(); Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client);
            refreshServerContext(client); ServerContext context = requireDeveloperContext(); requireExpectedWorld(context, args);
            if (context.dimension != dimension || client.thePlayer.dimension != dimension || client.theWorld.provider.dimensionId != dimension)
                throw new BridgeException("DIMENSION_CHANGED", "Survival operation client/server dimension changed.");
            NativePlacementAdapter.requireMatchingMode(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode());
            if (client.thePlayer.capabilities.isCreativeMode && !acceptsCreative() || client.thePlayer.isDead || client.thePlayer.ridingEntity != null || client.thePlayer.riddenByEntity != null)
                throw new BridgeException("invalid_state", "Survival operation requires its actual live, unmounted survival player.");
            if (client.thePlayer.inventory.getItemStack() != null) throw new BridgeException("inventory_changed", "Survival operation requires an empty cursor.");
            if (args.has("attachmentBinding") && (!Float.isFinite(client.thePlayer.getHealth()) || client.thePlayer.getHealth() <= 0 || client.thePlayer.inventory.getSizeInventory() != 41))
                throw new BridgeException("invalid_state", "Exact attachment placement requires a living actor and all 41 actual player slots.");
            if (serverIdentity == null) { serverIdentity = context.server; playerId = client.thePlayer.getUniqueID().toString(); actorCreative = client.thePlayer.capabilities.isCreativeMode; }
            if (client.thePlayer.capabilities.isCreativeMode != actorCreative) throw new BridgeException("invalid_state", "Native placement actor mode changed.");
            if (serverIdentity != context.server || !playerId.equals(client.thePlayer.getUniqueID().toString()))
                throw new BridgeException("stale_world", "Survival operation server/player identity changed.");
            current = snapshot(client); guard.check(current); reach = client.playerController.getBlockReachDistance();
            if (requireSurvivalFlight) {
                SurvivalFlightPosture.actor(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode(), client.thePlayer.capabilities.allowFlying, client.thePlayer.capabilities.isFlying);
                SurvivalFlightPosture.snapshot(current, sessionId, playerId, JsonData.string(args, "expectedWorld"), dimension);
                flightBody(state, client.thePlayer, "client");
            }
            if (state.before == null) state.before = current;
            return client;
        }
        EntityPlayerMP server(SurvivalConstructionAdapter.State state) {
            state.check(); ServerContext context = requireDeveloperContext(); requireExpectedWorld(context, args);
            if (context.server != serverIdentity || context.dimension != dimension || !context.playerId.toString().equals(playerId)
                    || context.paused || context.screenOpen || System.nanoTime() - context.capturedAt > 1000000000L)
                throw new BridgeException("stale_world", "Survival authoritative context changed or is stale/paused.");
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (player.worldObj == null || player.worldObj.isRemote || player.dimension != dimension || player.isDead
                    || player.capabilities.isCreativeMode != actorCreative || player.capabilities.isCreativeMode && !acceptsCreative() || player.ridingEntity != null || player.riddenByEntity != null
                    || player.openContainer != player.inventoryContainer || player.inventory.getItemStack() != null)
                throw new BridgeException("invalid_state", "The authoritative survival actor/container is unavailable.");
            if (args.has("attachmentBinding") && (!Float.isFinite(player.getHealth()) || player.getHealth() <= 0 || player.inventory.getSizeInventory() != 41))
                throw new BridgeException("invalid_state", "Exact authoritative attachment placement requires a living actor and all 41 player slots.");
            if (requireSurvivalFlight) {
                SurvivalFlightPosture.actor(player.capabilities.isCreativeMode, player.capabilities.isCreativeMode, player.capabilities.allowFlying, player.capabilities.isFlying);
                flightBody(state, player, "server");
            }
            JsonObject pose = current.getAsJsonObject("player");
            if (Math.abs(player.posX - pose.get("x").getAsDouble()) > .0001
                    || Math.abs(player.boundingBox.minY - pose.get("feetY").getAsDouble()) > .0001
                    || Math.abs(player.posZ - pose.get("z").getAsDouble()) > .0001
                    || Math.abs(player.rotationYaw - pose.get("yaw").getAsFloat()) > .01
                    || Math.abs(player.rotationPitch - pose.get("pitch").getAsFloat()) > .01
                    || player.inventory.currentItem != pose.get("selectedHotbarSlot").getAsInt()) {
                state.receipt.addProperty("lastCheckpoint", "server_pose_pending");
                JsonObject observed = actor(player); observed.addProperty("clientFeetY", pose.get("feetY").getAsDouble());
                observed.addProperty("clientYaw", pose.get("yaw").getAsFloat()); observed.addProperty("clientPitch", pose.get("pitch").getAsFloat());
                observed.addProperty("clientSelectedHotbarSlot", pose.get("selectedHotbarSlot").getAsInt()); state.receipt.add("pendingServerPose", observed); return null;
            }
            return player;
        }
        void flightBody(SurvivalConstructionAdapter.State state, EntityPlayer player, String side) {
            JsonObject proof = WorldObservationsAdapter.spaceAt(player.worldObj, player, NativePlacementAdapter.actorBody(player.boundingBox), state.deadline, false);
            proof.addProperty("worldSource", side); proof.addProperty("worldTick", player.worldObj.getTotalWorldTime());
            state.receipt.add(side + "SurvivalFlightBodyProof", proof); state.receipt.addProperty("survivalFlightRequired", true);
            SurvivalFlightPosture.space(proof);
        }
        void aim(Minecraft client, MovingObjectPosition hit, int slot) {
            Vec3 eye = client.thePlayer.getPosition(1F);
            double dx = hit.hitVec.xCoord - eye.xCoord, dy = hit.hitVec.yCoord - eye.yCoord, dz = hit.hitVec.zCoord - eye.zCoord;
            client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            client.thePlayer.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            client.thePlayer.prevRotationYaw = client.thePlayer.rotationYaw; client.thePlayer.prevRotationPitch = client.thePlayer.rotationPitch;
            client.objectMouseOver = hit; client.thePlayer.inventory.currentItem = slot;
            client.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
            client.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(client.thePlayer.rotationYaw, client.thePlayer.rotationPitch, client.thePlayer.onGround));
            current = snapshot(client); guard.check(current);
        }
        MovingObjectPosition aimed(SurvivalConstructionAdapter.State state, EntityPlayerMP player, int x, int y, int z, int side) {
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(player.worldObj, dimension);
            Vec3 eye = SurvivalConstructionAdapter.serverEye(player.posX, player.posY, player.posZ, player.getEyeHeight()), look = player.getLook(1F);
            JsonObject ray = new JsonObject(); ray.addProperty("source", "integrated_server"); ray.addProperty("eyeMeaning", "actual_server_posY_plus_public_getEyeHeight");
            ray.addProperty("eyeX", eye.xCoord); ray.addProperty("eyeY", eye.yCoord); ray.addProperty("eyeZ", eye.zCoord);
            ray.addProperty("targetX", x); ray.addProperty("targetY", y); ray.addProperty("targetZ", z); ray.addProperty("targetSide", side);
            state.receipt.add("serverAim", ray);
            if (!access.loaded(x, y, z) || NativePlacementAdapter.distanceToBlock(eye, x, y, z) > reach)
                throw new BridgeException("out_of_reach", "Survival target must be loaded and within the actual native player reach.");
            Vec3 end = Vec3.createVectorHelper(eye.xCoord + look.xCoord * reach, eye.yCoord + look.yCoord * reach, eye.zCoord + look.zCoord * reach);
            if (!NativePlacementAdapter.loadedRay(access, eye, end, y)) throw new BridgeException("chunk_unloaded", "The actual server aim ray crosses an unloaded neighbour halo.");
            MovingObjectPosition hit = access.trace(Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), end);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                ray.addProperty("hitX", hit.blockX); ray.addProperty("hitY", hit.blockY); ray.addProperty("hitZ", hit.blockZ); ray.addProperty("hitSide", hit.sideHit);
            }
            return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.blockX == x && hit.blockY == y && hit.blockZ == z && hit.sideHit == side ? hit : null;
        }
        JsonObject actor(EntityPlayerMP player) {
            JsonObject result = serverPosition(player); result.addProperty("source", "integrated_server");
            result.addProperty("feetY", player.boundingBox.minY);
            result.addProperty("playerId", player.getUniqueID().toString()); result.addProperty("dimension", player.dimension);
            result.addProperty("creative", player.capabilities.isCreativeMode); result.addProperty("actualSurvival", !player.capabilities.isCreativeMode);
            result.addProperty("flying", player.capabilities.isFlying); result.addProperty("allowFlying", player.capabilities.allowFlying);
            result.addProperty("selectedHotbarSlot", player.inventory.currentItem); return result;
        }
    }

    private final class SurvivalBuildStages extends SurvivalContext implements SurvivalConstructionAdapter.BuildStages {
        final SurvivalConstructionAdapter.Request request;
        ItemStack trigger;
        String controllerIdentity;
        AeSurvivalSource materialSource;
        SurvivalBuildStages(SurvivalConstructionAdapter.Request request, GuardedContext guard) {
            super(request.contextArgs(), guard, request.dimension); this.request = request;
        }
        public void prepare(SurvivalConstructionAdapter.State state) {
            if (!sessionId.equals(request.session)) throw new BridgeException("stale_session", "Survival construction session changed.");
            Minecraft client = client(state); releaseInput();
            trigger = client.thePlayer.inventory.getStackInSlot(request.slot);
            SurvivalConstructionAdapter.requireBlueprint(trigger); trigger = trigger.copy();
            controllerIdentity = controller(client.theWorld).toString(); refresh(state);
            state.receipt.addProperty("version", 1); state.receipt.addProperty("operation", SurvivalConstructionAdapter.OPERATION);
            state.receipt.addProperty("sessionId", sessionId); state.receipt.addProperty("expectedWorld", request.world);
            state.receipt.addProperty("dimension", dimension); state.receipt.addProperty("generationPerformed", false);
            state.receipt.addProperty("actualSurvival", true);
            state.receipt.addProperty("materialSourceType", request.materialSource == null ? "actual_player_fromPlayer" : "ae_cells");
            state.receipt.addProperty("inventoryCleared", false); state.receipt.add("blueprint", SurvivalConstructionAdapter.item(trigger));
        }
        JsonObject controller(World world) {
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(world, dimension);
            if (!access.loaded(request.x, request.y, request.z)) throw new BridgeException("chunk_unloaded", "Survival controller is not actually loaded.");
            JsonObject result = access.state(request.x, request.y, request.z).description;
            result.remove("source"); TileEntity tile = world.getTileEntity(request.x, request.y, request.z);
            if (tile == null) throw new BridgeException("unsupported", "The actual target has no controller tile.");
            result.addProperty("tileClass", tile.getClass().getName()); JsonObject gt = GregTechAdapter.identity(tile);
            if (gt != null) result.add("gregtech", gt); return result;
        }
        void outsideBuildBody(SurvivalConstructionAdapter.State state, EntityPlayer player, String side) {
            if (request.excludedBuildEnvelope == null) return;
            AxisAlignedBB body = player.boundingBox;
            PlayerNavigation.translatedFlightBody(body, new PlayerNavigation.Point(player.posX, body.minY, player.posZ),
                    new FlightPathPlanner.Sweep(new FlightPathPlanner.Point(player.posX, body.minY, player.posZ),
                        new FlightPathPlanner.Point(player.posX, body.minY, player.posZ), player.width, player.height), player.width, player.height);
            JsonObject proof = new JsonObject(); proof.addProperty("version", 1); proof.addProperty("source", side);
            proof.addProperty("playerId", player.getUniqueID().toString()); proof.addProperty("dimension", player.dimension);
            proof.add("actualNativeBody", PlayerNavigation.bounds(body));
            proof.add("excludedBuildEnvelope", PlayerNavigation.bounds(request.excludedBuildEnvelope));
            proof.addProperty("outside", !body.intersectsWith(request.excludedBuildEnvelope)); proof.addProperty("formationProven", false);
            state.receipt.add(side + "BuildBodyProof", proof);
            if (body.intersectsWith(request.excludedBuildEnvelope)) {
                state.receipt.addProperty("lastCheckpoint", side + "_future_body_overlap");
                throw new BridgeException("future_build_body_overlap", "The actual full native survival actor body intersects the declared captured build envelope before a shell callback.");
            }
        }
        public void refresh(SurvivalConstructionAdapter.State state) {
            Minecraft client = client(state); outsideBuildBody(state, client.thePlayer, "client");
            if (!ItemStack.areItemStacksEqual(trigger, client.thePlayer.inventory.getStackInSlot(request.slot)))
                throw new BridgeException("inventory_changed", "Actual blueprint Stack/NBT/channel data changed during survival construction.");
            if (!controllerIdentity.equals(controller(client.theWorld).toString())) throw new BridgeException("target_unverified", "Actual client controller identity changed.");
            MovingObjectPosition hit = NativePlacementAdapter.visibleFace(NativePlacementAdapter.access(client.theWorld, dimension), client.thePlayer.getPosition(1F),
                    reach, request.x, request.y, request.z, request.side);
            if (hit == null) throw new BridgeException("face_not_visible", "Survival controller needs a real visible native interaction face.");
            aim(client, hit, request.slot);
        }
        public JsonObject batch(SurvivalConstructionAdapter.State state) {
            EntityPlayerMP player = server(state); if (player == null) return null;
            outsideBuildBody(state, player, "server");
            if (!ItemStack.areItemStacksEqual(trigger, player.inventory.getStackInSlot(request.slot))) return null;
            if (!controllerIdentity.equals(controller(player.worldObj).toString())) throw new BridgeException("target_unverified", "Actual server controller differs from the client binding.");
            if (aimed(state, player, request.x, request.y, request.z, request.side) == null) { state.receipt.addProperty("lastCheckpoint", "server_aim_pending"); return null; }
            if (survivalCooldownServer != serverIdentity || !playerId.equals(survivalCooldownPlayer)) {
                survivalCooldownServer = serverIdentity; survivalCooldownPlayer = playerId; survivalCallbackCompleted = 0;
            }
            long now = state.clock.getAsLong();
            if (survivalCallbackCompleted > 0 && now - survivalCallbackCompleted < TimeUnit.MILLISECONDS.toNanos(SurvivalConstructionAdapter.cooldown())) return null;
            state.receipt.add("controller", controller(player.worldObj)); state.receipt.add("actor", actor(player));
            state.receipt.addProperty("cooldownScope", "bridge_callbacks_same_actual_server_player");
            int previousCalls = state.callsStarted;
            try {
                if (request.materialSource != null) {
                    if (materialSource == null) materialSource = AeSurvivalSource.bind(player, request.materialSource);
                    else materialSource.requireActor(player);
                }
                return SurvivalConstructionAdapter.invoke(player, player.worldObj.getTileEntity(request.x, request.y, request.z), player.getHeldItem(), request, state,
                        () -> guard.survivalMutationStarting(current), materialSource);
            } catch (RuntimeException | LinkageError error) {
                if (materialSource != null) state.receipt.add("materialSourceAtFailure", materialSource.receipt());
                throw error;
            } finally { if (state.callsStarted > previousCalls) survivalCallbackCompleted = state.clock.getAsLong(); }
        }
        public GuardedContext.Result finish(SurvivalConstructionAdapter.State state) {
            client(state); state.receipt.addProperty("source", "integrated_server_public_survival_construct");
            return guard.result(state.receipt, state.before);
        }
    }

    private class SurvivalPlacementStages extends SurvivalContext implements SurvivalConstructionAdapter.PlacementStages {
        NativePlacementAdapter.Plan plan;
        SurvivalConstructionAdapter.Inventory before;
        OwnedBlockReplacement.InventorySnapshot prepared;
        @Override boolean acceptsCreative() { return args.has("attachmentBinding") || args.has("expectedPlacementFacing"); }
        SurvivalPlacementStages(JsonObject args, GuardedContext guard) {
            super(args, guard, args.has("dimension") ? JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE)
                    : serverContext == null ? Integer.MIN_VALUE : serverContext.dimension);
        }
        public GuardedContext.Result prepare(SurvivalConstructionAdapter.State state) throws Exception {
            Minecraft initial = Minecraft.getMinecraft(); requireWorldControls(initial);
            if (requireSurvivalFlight) SurvivalFlightPosture.actor(initial.thePlayer.capabilities.isCreativeMode, initial.playerController.isInCreativeMode(), initial.thePlayer.capabilities.allowFlying, initial.thePlayer.capabilities.isFlying);
            if (initial.thePlayer.capabilities.isCreativeMode && !acceptsCreative())
                return SurvivalConstructionAdapter.localPlacement(state, guard, () -> snapshot(initial),
                        starting -> placeBlock(initial, args, starting));
            guard.requireSurvivalOperation(); Minecraft client = client(state); releaseInput();
            JsonObject bodyProof = NativePlacementAdapter.placementBodyProof(client.thePlayer.boundingBox,
                    JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), JsonData.integer(args, "y", 0, 255),
                    JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX));
            state.receipt.add("clientPlacementBodyProof", bodyProof); state.receipt.addProperty("lastCheckpoint", "client_placement_body_checked");
            NativePlacementAdapter.requirePlacementBody(bodyProof);
            plan = NativePlacementAdapter.plan(client, args, dimension);
            if (plan.binding != null) state.receipt.add("attachmentBinding", new com.google.gson.JsonParser().parse(plan.binding.declaration.toString()));
            state.receipt.addProperty("lastCheckpoint", "client_placement_prepared");
            state.receipt.addProperty("beforeBarrierComplete", false); state.receipt.addProperty("afterBarrierComplete", false);
            prepared = new OwnedBlockReplacement.InventorySnapshot(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            aim(client, plan.attachment, plan.slot);
            return null;
        }
        public boolean before(SurvivalConstructionAdapter.State state) {
            EntityPlayerMP player = server(state); if (player == null) return false;
            JsonObject bodyProof = NativePlacementAdapter.placementBodyProof(player.boundingBox, plan.x, plan.y, plan.z);
            bodyProof.addProperty("worldSource", "integrated_server"); bodyProof.addProperty("worldTick", player.worldObj.getTotalWorldTime());
            state.receipt.add("serverPlacementBodyProof", bodyProof); state.receipt.addProperty("lastCheckpoint", "server_placement_body_checked");
            NativePlacementAdapter.requirePlacementBody(bodyProof);
            try { prepared.require(player.inventory, player.inventory.getItemStack()); }
            catch (BridgeException pending) {
                if ("inventory_changed".equals(pending.code)) { state.receipt.addProperty("lastCheckpoint", "server_inventory_pending"); return false; }
                throw pending;
            }
            if (plan.binding != null) state.receipt.add("serverAttachmentIdentity", plan.binding.verify(NativePlacementAdapter.access(player.worldObj, dimension)));
            if (aimed(state, player, plan.attachment.blockX, plan.attachment.blockY, plan.attachment.blockZ, plan.attachment.sideHit) == null) {
                state.receipt.addProperty("lastCheckpoint", "server_aim_pending"); return false;
            }
            PlacementFacing.require(PlacementFacing.requested(args),player.rotationYaw,player.rotationPitch);
            if (PlacementFacing.requested(args) != null) state.receipt.addProperty("serverPlacementFacingVerified",true);
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(player.worldObj, dimension);
            if (!access.loaded(plan.x, plan.y, plan.z)) throw new BridgeException("chunk_unloaded", "Survival placement target is not actually loaded on the server.");
            NativePlacementAdapter.State target = access.state(plan.x, plan.y, plan.z);
            if (!target.description.get("registryName").equals(plan.before.get("registryName")) || !target.description.get("metadata").equals(plan.before.get("metadata")))
                throw new BridgeException("target_unverified", "Survival target changed before its native placement.");
            if (plan.part != null) { NativePartPlacement.vacant(target.description, plan.part); NativePartPlacement.route(player.worldObj, plan); }
            before = new SurvivalConstructionAdapter.Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack());
            state.receipt.add("serverBefore", before.json()); state.receipt.add("actor", actor(player));
            if (plan.binding != null) state.receipt.addProperty("serverAttachmentBindingVerified", true);
            state.receipt.addProperty("lastCheckpoint", "server_before_place_confirmed"); state.receipt.addProperty("beforeBarrierComplete", true); return true;
        }
        public void place(SurvivalConstructionAdapter.State state) {
            Minecraft client = client(state); prepared.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            JsonObject bodyProof = NativePlacementAdapter.placementBodyProof(client.thePlayer.boundingBox, plan.x, plan.y, plan.z);
            state.receipt.add("clientPlacementBodyProof", bodyProof); state.receipt.addProperty("lastCheckpoint", "client_before_native_body_checked");
            NativePlacementAdapter.requirePlacementBody(bodyProof);
            NativePlacementAdapter.Plan currentPlan = NativePlacementAdapter.plan(client, args, dimension);
            if (currentPlan.attachment.blockX != plan.attachment.blockX || currentPlan.attachment.blockY != plan.attachment.blockY
                    || currentPlan.attachment.blockZ != plan.attachment.blockZ || currentPlan.attachment.sideHit != plan.attachment.sideHit)
                throw new BridgeException("stale_world", "The actual native survival attachment changed before placement.");
            Runnable starting = () -> {
                PlacementFacing.require(PlacementFacing.requested(args),client.thePlayer.rotationYaw,client.thePlayer.rotationPitch);
                state.starting(() -> guard.survivalMutationStarting(current)); state.receipt.addProperty("lastCheckpoint", "native_place_submitted");
                if (PlacementFacing.requested(args) != null) {
                    state.receipt.addProperty("expectedPlacementFacing",PlacementFacing.requested(args));
                    state.receipt.addProperty("clientPlacementFacingVerified",true);
                }
            };
            JsonObject nativeResult = NativePlacementAdapter.invoke(currentPlan, client.thePlayer.inventory, use ->
                    currentPlan.part == null ? interact(client, use, starting) : NativePartPlacement.use(client, currentPlan, starting));
            if (currentPlan.part != null) {
                nativeResult.add("expectedPart", currentPlan.part);
                nativeResult.addProperty("nativePath", "Minecraft.rightClickMouse_with_scoped_sneak");
                nativeResult.addProperty("coverage", "Native AE part placement; server part identity and all41 inventory conservation are verified after callback.");
                nativeResult.addProperty("airUseFallback", true);
                nativeResult.addProperty("nativeMouseCallbackSemantics", true);
            }
            state.completed();
            state.receipt.addProperty("lastCheckpoint", "client_native_place_returned");
            for (Map.Entry<String, JsonElement> entry : nativeResult.entrySet()) state.receipt.add(entry.getKey(), entry.getValue());
            state.receipt.addProperty("sessionId", sessionId); state.receipt.addProperty("actualSurvival", !plan.creative);
        }
        public boolean after(SurvivalConstructionAdapter.State state) {
            EntityPlayerMP player = server(state); if (player == null) return false;
            SurvivalConstructionAdapter.Inventory after = new SurvivalConstructionAdapter.Inventory(player.inventory, player.inventory.currentItem, player.inventory.getItemStack());
            state.receipt.add("serverAfter", after.json());
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(player.worldObj, dimension);
            if (!access.loaded(plan.x, plan.y, plan.z)) throw new BridgeException("chunk_unloaded", "Survival placed target unloaded before confirmation.");
            NativePlacementAdapter.State target = access.state(plan.x, plan.y, plan.z);
            if (target.air) { state.receipt.addProperty("lastCheckpoint", "server_target_pending"); return false; }
            Block sourceBlock = Block.getBlockFromItem(plan.item.getItem());
            if (plan.part != null && !NativePartPlacement.present(target.description, plan.part)) {
                state.receipt.addProperty("lastCheckpoint", "server_part_pending"); return false;
            }
            if (plan.part == null && (sourceBlock == null || player.worldObj.getBlock(plan.x, plan.y, plan.z) != sourceBlock))
                throw new BridgeException("target_unverified", "Actual placed block does not belong to the source ItemBlock.");
            if (plan.creative) prepared.require(player.inventory, player.inventory.getItemStack());
            if (!plan.creative && !SurvivalConstructionAdapter.placementConsumed(before, after, plan.slot))
                throw new BridgeException("inventory_changed", "Actual survival placement did not deduct exactly one source while preserving every other Stack/NBT.");
            JsonObject gt = GregTechAdapter.identity(player.worldObj.getTileEntity(plan.x, plan.y, plan.z));
            if (gt != null) target.description.add("gregtech", gt); state.receipt.add("serverTarget", target.description);
            state.receipt.addProperty("serverConfirmed", true); state.receipt.addProperty("serverConsumptionConfirmed", true);
            state.receipt.addProperty("serverSourceConsumption", plan.creative ? 0 : 1); state.receipt.addProperty("outcomeUnknown", false);
            if (plan.binding != null) {
                state.receipt.addProperty("attachmentBindingVerified", true); state.receipt.addProperty("allPlayerSlots", 41);
                state.receipt.addProperty("fullNbtConservation", true);
            }
            state.receipt.addProperty("lastCheckpoint", "server_after_place_confirmed"); state.receipt.addProperty("afterBarrierComplete", true);
            state.receipt.addProperty("completion", plan.creative
                    ? "One exact-bound native creative placement is server-confirmed with all 41 player Stack/NBT preserved. Machine identity and structure formation remain separate checks."
                    : "One actual native survival placement is confirmed by the loaded server ItemBlock identity and exact source decrement of one; all other server Stack/NBT are preserved. Machine-specific identity and structure formation remain separate checks.");
            World world = player.worldObj;
            state.receipt.add("serverPlacementObservation", PlacementAfterObservation.read(sessionId, playerId,
                    ((MinecraftServer) serverIdentity).getWorldName(), dimension, plan.x, plan.y, plan.z, target.description,
                    new PlacementAfterObservation.Access() {
                        Block observedBlock; TileEntity observedTile; int observedMetadata;
                        public boolean loaded() { return world.blockExists(plan.x, plan.y, plan.z); }
                        public long tick() { return world.getTotalWorldTime(); }
                        public JsonObject data(PlacementAfterObservation.Coverage coverage) {
                            observedBlock = world.getBlock(plan.x, plan.y, plan.z); observedMetadata = world.getBlockMetadata(plan.x, plan.y, plan.z);
                            observedTile = world.getTileEntity(plan.x, plan.y, plan.z);
                            return block(world, plan.x, plan.y, plan.z, true, coverage);
                        }
                        public boolean unchanged() {
                            return "developer".equals(profile) && serverIdentity == MinecraftServer.getServer()
                                    && DimensionManager.getWorld(dimension) == world && player.worldObj == world
                                    && player.dimension == dimension && playerId.equals(player.getUniqueID().toString())
                                    && world.getBlock(plan.x, plan.y, plan.z) == observedBlock
                                    && world.getBlockMetadata(plan.x, plan.y, plan.z) == observedMetadata
                                    && world.getTileEntity(plan.x, plan.y, plan.z) == observedTile
                                    && (observedTile == null || !observedTile.isInvalid() && observedTile.getWorld() == world
                                        && observedTile.xCoord == plan.x && observedTile.yCoord == plan.y && observedTile.zCoord == plan.z);
                        }
                    }, state.deadline, System::nanoTime));
            return true;
        }
        public void refresh(SurvivalConstructionAdapter.State state) { client(state); }
        public GuardedContext.Result finish(SurvivalConstructionAdapter.State state) { client(state); return guard.result(state.receipt, state.before); }
    }

    /** Reuses normal held keys and the existing authoritative placement inventory/target accounting. */
    private final class JumpPlacementStages extends SurvivalPlacementStages {
        String ownedInput;
        boolean inputComplete, clientAir, serverAir, jumpStarted, serverSneakObserved;
        double startX, startZ;
        JumpPlacementStages(JsonObject input, GuardedContext guard) { super(JumpPlacement.arguments(input), guard); }
        @Override boolean acceptsCreative() { return false; }
        private void geometry(EntityPlayer player, SurvivalConstructionAdapter.State state, String side) {
            JumpPlacement.pose(player.boundingBox, plan.x, plan.y, plan.z, player.onGround, player.capabilities.isFlying, player.motionX, player.motionZ);
            if (player.isPotionActive(net.minecraft.potion.Potion.jump))
                throw new BridgeException("unsupported", "Underfoot input currently requires ordinary jump height, without Jump Boost.");
            JsonObject standing = WorldObservationsAdapter.spaceAt(player.worldObj, player, NativePlacementAdapter.actorBody(player.boundingBox), state.deadline, true);
            JsonObject envelope = WorldObservationsAdapter.spaceAt(player.worldObj, player, JumpPlacement.envelope(player.boundingBox), state.deadline, false);
            state.receipt.add(side+"JumpStart", standing); state.receipt.add(side+"JumpEnvelope", envelope);
            if (PlayerNavigation.walkingCheck(standing) != WalkingPathPlanner.Check.CLEAR
                    || PlayerNavigation.flightCheck(envelope) != FlightPathPlanner.Check.CLEAR)
                throw new BridgeException("unsupported", "Underfoot jump needs complete native support and clear loaded headroom without entities, liquid or hazards.");
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(player.worldObj, dimension);
            if (!access.loaded(plan.x,plan.y,plan.z) || !access.state(plan.x,plan.y,plan.z).air)
                throw new BridgeException("target_unverified", "The underfoot target must remain loaded and empty before the one input action.");
            if (plan.binding != null) plan.binding.verify(access);
        }
        @Override public GuardedContext.Result prepare(SurvivalConstructionAdapter.State state) {
            guard.requireSurvivalOperation(); Minecraft client = client(state);
            if (navigation != null || !held.isEmpty() || inputTaskId != null)
                throw new BridgeException("busy", "Underfoot jump requires unowned native input.");
            int x=JsonData.integer(args,"x",WorldScan.WORLD_MIN,WorldScan.WORLD_MAX), y=JsonData.integer(args,"y",1,252);
            int z=JsonData.integer(args,"z",WorldScan.WORLD_MIN,WorldScan.WORLD_MAX), slot=JsonData.integer(args,"hotbarSlot",0,8);
            ItemStack source=client.thePlayer.inventory.getStackInSlot(slot); NativePlacementAdapter.requireItem(source);
            Block block=Block.getBlockFromItem(source.getItem());
            if (block == null || block.hasTileEntity(source.getItem().getMetadata(source.getMetadata())))
                throw new BridgeException("unsupported", "Underfoot construction accepts ordinary non-tile structural ItemBlocks only.");
            NativePlacementAdapter.Access access=NativePlacementAdapter.access(client.theWorld,dimension);
            Vec3 eye=client.thePlayer.getPosition(1F);
            MovingObjectPosition hit=NativePlacementAdapter.visibleFace(access,eye,reach,x,y-1,z,1);
            if (hit==null) throw new BridgeException("face_not_visible", "The actual supporting top face is not visible within normal reach.");
            // A downward ray through the actual player's X/Z is used; no predicted airborne hit or fake target.
            hit=access.trace(Vec3.createVectorHelper(eye.xCoord,eye.yCoord,eye.zCoord),Vec3.createVectorHelper(eye.xCoord,eye.yCoord-reach,eye.zCoord));
            if(hit==null || hit.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK || hit.blockX!=x || hit.blockY!=y-1 || hit.blockZ!=z || hit.sideHit!=1)
                throw new BridgeException("face_not_visible", "The actual downward native ray must hit the declared supporting top face.");
            plan=new NativePlacementAdapter.Plan(x,y,z,slot,dimension,false,source.copy(),access.state(x,y,z).description,
                    NativePlacementAdapter.placementBodyProof(client.thePlayer.boundingBox,x,y,z),hit);
            plan.creative=false;
            if(args.has("attachmentBinding")) {
                plan.binding=new PlacementAttachment(args,x,y,z,dimension);
                if(plan.binding.x!=x || plan.binding.y!=y-1 || plan.binding.z!=z || plan.binding.side!=1)
                    throw new BridgeException("invalid_args", "Underfoot placement binds only the actual lower top face.");
            }
            geometry(client.thePlayer,state,"client");
            prepared=new OwnedBlockReplacement.InventorySnapshot(client.thePlayer.inventory,client.thePlayer.inventory.getItemStack());
            startX=client.thePlayer.posX; startZ=client.thePlayer.posZ; aim(client,hit,slot);
            state.receipt.addProperty("jumpPlacementVersion",JumpPlacement.VERSION);
            state.receipt.addProperty("nativePath","normal_held_jump_sneak_use");
            state.receipt.addProperty("nativeCallbackCountKnown",false);
            return null;
        }
        @Override public boolean before(SurvivalConstructionAdapter.State state) {
            EntityPlayerMP player=server(state); if(player==null) return false;
            geometry(player,state,"server"); prepared.require(player.inventory,player.inventory.getItemStack());
            if(aimed(state,player,plan.x,plan.y-1,plan.z,1)==null) return false;
            before=new SurvivalConstructionAdapter.Inventory(player.inventory,player.inventory.currentItem,player.inventory.getItemStack());
            state.receipt.add("serverBefore",before.json()); state.receipt.addProperty("beforeBarrierComplete",true);
            return true;
        }
        @Override public void place(SurvivalConstructionAdapter.State state) {
            Minecraft client=client(state); geometry(client.thePlayer,state,"client");
            prepared.require(client.thePlayer.inventory,client.thePlayer.inventory.getItemStack());
            guard.survivalMutationStarting(current); state.starting();
            JsonObject keys=new JsonObject(); JsonArray names=new JsonArray();
            // The first native use is processed before movementInput's next
            // update. On a GUI-bearing support, simultaneous sneak/use may
            // open its GUI. Establish real sneak on both owners first.
            names.add(new JsonPrimitive("sneak"));
            keys.add("keys",names); keys.addProperty("ticks",JumpPlacement.SNEAK_PREPARE_TICKS);
            JsonObject scheduled=input(client,keys); ownedInput=scheduled.get("taskId").getAsString();
            state.receipt.add("jumpSneakInput",scheduled); state.completed();
            state.receipt.addProperty("lastCheckpoint","jump_sneak_submitted");
        }
        @Override public void refresh(SurvivalConstructionAdapter.State state) {
            Minecraft client=client(state);
            if(ownedInput==null) return;
            if(Math.abs(client.thePlayer.posX-startX)>.02 || Math.abs(client.thePlayer.posZ-startZ)>.02
                    || Math.abs(client.thePlayer.rotationPitch-90)>.01 || client.thePlayer.capabilities.isFlying
                    || client.thePlayer.inventory.currentItem!=plan.slot)
                throw new BridgeException("invalid_state","Underfoot jump actor/view/source changed; never replay the input.");
            if(inputTaskId!=null && !inputTaskId.equals(ownedInput)) throw new BridgeException("busy","Another native input task replaced the jump owner.");
            if(!jumpStarted) {
                if(inputTaskId==null || held.isEmpty())
                    throw new BridgeException("timeout","Underfoot sneak preparation expired without dual actual sneak; no jump/use was submitted.");
                if(serverSneakObserved && client.thePlayer.isSneaking() && client.thePlayer.movementInput.sneak) {
                    geometry(client.thePlayer,state,"client");
                    prepared.require(client.thePlayer.inventory,client.thePlayer.inventory.getItemStack());
                    guard.survivalMutationStarting(current); state.starting();
                    JsonObject keys=new JsonObject(); JsonArray names=new JsonArray();
                    for(String key:new String[]{"jump","sneak","use"}) names.add(new JsonPrimitive(key));
                    keys.add("keys",names); keys.addProperty("ticks",JumpPlacement.INPUT_TICKS);
                    JsonObject scheduled=input(client,keys); ownedInput=scheduled.get("taskId").getAsString();
                    state.completed(); jumpStarted=true;
                    state.receipt.addProperty("clientSneakBeforeJump",true);
                    state.receipt.add("jumpInput",scheduled);
                    state.receipt.addProperty("lastCheckpoint","jump_input_submitted");
                }
                return;
            }
            clientAir |= !client.thePlayer.onGround;
            JsonObject control=current.getAsJsonObject("serverControl");
            if(control!=null && PlayerNavigation.flag(control,"available") && !PlayerNavigation.flag(control,"onGround")) serverAir=true;
            inputComplete=inputTaskId==null && held.isEmpty();
        }
        @Override public boolean after(SurvivalConstructionAdapter.State state) {
            if(!jumpStarted) {
                EntityPlayerMP player=server(state); if(player==null) return false;
                geometry(player,state,"server");
                prepared.require(player.inventory,player.inventory.getItemStack());
                serverSneakObserved=player.isSneaking();
                state.receipt.addProperty("serverSneakBeforeJump",serverSneakObserved);
                state.receipt.addProperty("serverSneakObservationTick",player.worldObj.getTotalWorldTime());
                return false;
            }
            if(!inputComplete) return false;
            EntityPlayerMP player=server(state); if(player==null) return false;
            if(!JumpPlacement.landed(player.boundingBox,plan.x,plan.y,plan.z,player.onGround,player.capabilities.isFlying)) return false;
            if(!clientAir || !serverAir) throw new BridgeException("target_unverified","Native jump needs observed client/server airborne phases before grounded success.");
            JsonObject support=WorldObservationsAdapter.spaceAt(player.worldObj,player,NativePlacementAdapter.actorBody(player.boundingBox),state.deadline,true);
            if(PlayerNavigation.walkingCheck(support)!=WalkingPathPlanner.Check.CLEAR) throw new BridgeException("unsupported","Actual jump landing lacks complete native support.");
            if(plan.binding!=null) plan.binding.verify(NativePlacementAdapter.access(player.worldObj,dimension));
            if(!super.after(state)) return false;
            state.receipt.add("jumpServerLanding",support); state.receipt.add("jumpServerPose",actor(player));
            state.receipt.addProperty("nativeInvoked",true); state.receipt.addProperty("actualSurvival",true);
            JsonObject attachment=new JsonObject(); attachment.addProperty("x",plan.x); attachment.addProperty("y",plan.y-1);
            attachment.addProperty("z",plan.z); attachment.addProperty("side",1); state.receipt.add("attachment",attachment);
            return true;
        }
        @Override public GuardedContext.Result finish(SurvivalConstructionAdapter.State state) {
            Minecraft client=client(state); release();
            if(!JumpPlacement.landed(client.thePlayer.boundingBox,plan.x,plan.y,plan.z,client.thePlayer.onGround,client.thePlayer.capabilities.isFlying))
                throw new BridgeException("invalid_state","The actual client has not landed on the new block.");
            JsonObject support=WorldObservationsAdapter.spaceAt(client.theWorld,client.thePlayer,NativePlacementAdapter.actorBody(client.thePlayer.boundingBox),state.deadline,true);
            if(PlayerNavigation.walkingCheck(support)!=WalkingPathPlanner.Check.CLEAR) throw new BridgeException("unsupported","Client jump landing support changed.");
            state.receipt.add("jumpClientLanding",support); state.receipt.addProperty("jumpPlacementConfirmed",true);
            state.receipt.addProperty("inputKeysReleased",held.isEmpty() && inputTaskId==null);
            state.receipt.addProperty("clientAirObserved",clientAir); state.receipt.addProperty("serverAirObserved",serverAir);
            state.receipt.addProperty("automaticReplayAllowed",false);
            return guard.result(state.receipt,state.before);
        }
        void release() { if(ownedInput!=null && ownedInput.equals(inputTaskId)) releaseInput(); }
    }

    private final class ReplacementStages implements OwnedBlockReplacement.Stages {
        private final OwnedBlockReplacement.Request request;
        private final GuardedContext guard, observationGuard;
        private final ApproachPlanner.Stages attackStages, placeStages;
        private ApproachPlanner.Frame attackFrame, placeFrame;
        private ApproachPlanner.Search attackSearch, placeSearch;
        private JsonObject beforeOwner, airOwner, placedOwner, serverPlaced, pendingMove;
        private OwnedBlockReplacement.InventorySnapshot clientInventory, serverInventory;
        private JsonObject initialClientInventory;

        ReplacementStages(OwnedBlockReplacement.Request request, GuardedContext guard) {
            this.request = request; this.guard = guard; observationGuard = guard.replacementRead("world.inspect_many");
            attackStages = approachStages(request.approachArgs(false), guard.replacementRead("player.plan_approach"));
            placeStages = approachStages(request.approachArgs(true), guard.replacementRead("player.plan_approach"));
        }

        public void prepare(OwnedBlockReplacement.State state) {
            Minecraft client = client(state);
            if (client.thePlayer.inventory.getStackInSlot(request.emptySlot) != null)
                throw new BridgeException("invalid_state", "Native replacement's empty hotbar slot is not actually empty.");
            request.requireSource(client.thePlayer.inventory.getStackInSlot(request.slot));
            clientInventory = new OwnedBlockReplacement.InventorySnapshot(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            initialClientInventory = inspectManyPlayerInventory(client);
            OwnedBlockReplacement.requireFullInventory(initialClientInventory);
            state.before = snapshot(client);
            synchronized (state.control) {
                state.checkRunning(); guard.replacementMutationStarting(state.before); state.markMutation();
                // Public normal held-item packets precede every native action. No private controller cache is accessed.
                client.thePlayer.inventory.currentItem = request.emptySlot;
                client.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(request.emptySlot));
            }
            attackFrame = attackStages.capture();
        }

        private Minecraft client(OwnedBlockReplacement.State state) {
            state.checkRunning(); Minecraft client = Minecraft.getMinecraft(); requireWorldControls(client);
            refreshServerContext(client); ServerContext context = requireDeveloperContext();
            requireExpectedWorld(context, request.placeArgs());
            if (context.dimension != request.dimension || client.thePlayer.dimension != request.dimension || client.theWorld.provider.dimensionId != request.dimension)
                throw new BridgeException("DIMENSION_CHANGED", "Native replacement's client/server dimension changed.");
            NativePlacementAdapter.requireCreative(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode());
            TeleportPolicy.requireFlight(request.flying, client.thePlayer.capabilities.allowFlying, client.thePlayer.capabilities.isFlying);
            if (client.thePlayer.isDead || client.thePlayer.ridingEntity != null || client.thePlayer.riddenByEntity != null)
                throw new BridgeException("invalid_state", "Native replacement needs its current unmounted live player.");
            guard.check(snapshot(client));
            if (clientInventory != null) clientInventory.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            request.requireSource(client.thePlayer.inventory.getStackInSlot(request.slot));
            if (client.thePlayer.inventory.getStackInSlot(request.emptySlot) != null)
                throw new BridgeException("inventory_changed", "Native replacement's actual empty-hand slot changed.");
            return client;
        }

        private EntityPlayerMP server(OwnedBlockReplacement.State state, ApproachPlanner.Frame frame) {
            state.checkRunning(); ServerContext context = requireDeveloperContext(); requireExpectedWorld(context, request.placeArgs());
            if (frame == null || context.server != frame.serverIdentity || context.dimension != request.dimension
                    || !context.playerId.toString().equals(frame.playerId) || context.paused || context.screenOpen
                    || context.screenIdentity != frame.screenIdentity || !context.screenId.equals(frame.screenId)
                    || System.nanoTime() - context.capturedAt > 1000000000L)
                throw new BridgeException("stale_world", "Native replacement's authoritative owner context changed or became stale.");
            EntityPlayerMP player = (EntityPlayerMP) operationPlayer(true);
            if (player.isDead || player.worldObj == null || player.worldObj.isRemote || player.dimension != request.dimension
                    || !player.capabilities.isCreativeMode || player.ridingEntity != null || player.riddenByEntity != null
                    || player.openContainer != player.inventoryContainer)
                throw new BridgeException("invalid_state", "Native replacement's authoritative creative player is unavailable.");
            TeleportPolicy.requireFlight(request.flying, player.capabilities.allowFlying, player.capabilities.isFlying);
            if (!player.worldObj.blockExists(request.x, request.y, request.z))
                throw new BridgeException("chunk_unloaded", "Native replacement's authoritative target unloaded.");
            if (serverInventory != null) serverInventory.require(player.inventory, player.inventory.getItemStack());
            request.requireSource(player.inventory.getStackInSlot(request.slot));
            if (player.inventory.getStackInSlot(request.emptySlot) != null)
                throw new BridgeException("inventory_changed", "Native replacement's server empty-hand slot changed.");
            return player;
        }

        private JsonObject serverPlayer(EntityPlayerMP player, JsonObject owner) {
            World world = player.worldObj;
            if (world == null || world.isRemote)
                throw new BridgeException("world_unavailable", "Replacement player sampling requires the authoritative server world.");
            long tick = world.getTotalWorldTime();
            JsonObject point = ownerPoint(owner);
            if (!"integrated_server".equals(JsonData.string(owner, "source"))
                    || !"integrated_server".equals(JsonData.string(point, "source"))
                    || !point.has("worldTick") || point.get("worldTick").getAsLong() != tick)
                throw new BridgeException("stale_world", "Replacement owner and player epochs differ.");
            JsonObject result = new JsonObject(); result.addProperty("source", "integrated_server"); result.addProperty("dimension", player.dimension);
            result.addProperty("worldTick", tick); result.addProperty("playerId", player.getUniqueID().toString());
            JsonObject inventory = InspectManyPlayerInventory.read(player.inventory, player.inventory.currentItem, player.inventory.getItemStack());
            OwnedBlockReplacement.requireFullInventory(inventory); result.add("inventory", inventory);
            result.addProperty("selectedHotbarSlot", player.inventory.currentItem); result.addProperty("creative", player.capabilities.isCreativeMode);
            result.addProperty("flying", player.capabilities.isFlying); result.addProperty("allowFlying", player.capabilities.allowFlying);
            JsonObject feet = new JsonObject(); feet.addProperty("x", player.posX); feet.addProperty("y", player.posY); feet.addProperty("z", player.posZ); result.add("feet", feet);
            if (player.worldObj != world || world.getTotalWorldTime() != tick)
                throw new BridgeException("stale_world", "Replacement player sampling crossed a server epoch.");
            return result;
        }

        private JsonObject owner(EntityPlayerMP player) {
            World world = player.worldObj;
            if (world == null || world.isRemote)
                throw new BridgeException("world_unavailable", "Replacement owner sampling requires the authoritative server world.");
            long tick = world.getTotalWorldTime();
            JsonObject result = inspectMany(world, request.pointArgs(), true);
            JsonObject point = ownerPoint(result);
            if (!"integrated_server".equals(JsonData.string(result, "source"))
                    || !"integrated_server".equals(JsonData.string(point, "source")))
                throw new BridgeException("world_unavailable", "Replacement owner evidence must come from the authoritative server world.");
            if (player.worldObj != world || world.getTotalWorldTime() != tick)
                throw new BridgeException("stale_world", "Replacement owner sampling crossed a server epoch.");
            point.addProperty("worldTick", tick);
            return result;
        }
        private JsonObject ownerPoint(JsonObject owner) {
            JsonObject result = owner.getAsJsonArray("results").get(0).getAsJsonObject();
            if (!result.get("ok").getAsBoolean() || !result.has("data") || !result.get("data").isJsonObject())
                throw new BridgeException("world_unavailable", "A complete native replacement owner point is unavailable.");
            return result.getAsJsonObject("data");
        }
        private JsonObject clientPoint(Minecraft client) { return approachAccess(client.theWorld, client.thePlayer).state(request.x, request.y, request.z).description; }
        private void requireNoTile(JsonObject point) {
            if (point.has("tileClass")) throw new BridgeException("unsupported", "Native owned replacement is limited to an actual old block with no tile entity.");
        }
        private boolean agrees(JsonObject actual, JsonObject expected) {
            try { ApproachPlanner.sameIdentity(expected, actual); return true; }
            catch (BridgeException failure) {
                if (!"target_unverified".equals(failure.code)) throw failure;
                String registry = JsonData.string(actual, "registryName");
                if (!"minecraft:air".equals(registry) && !request.old.registry.equals(registry) && !request.placed.registry.equals(registry)) throw failure;
                return false;
            }
        }

        public OwnedBlockReplacement.Decision beforeDig(OwnedBlockReplacement.State state) {
            EntityPlayerMP player = server(state, attackFrame);
            beforeOwner = owner(player); JsonObject point = ownerPoint(beforeOwner); request.old.require(point); requireNoTile(point);
            if (player.inventory.currentItem != request.emptySlot) return OwnedBlockReplacement.Decision.PENDING;
            if (serverInventory == null) serverInventory = new OwnedBlockReplacement.InventorySnapshot(player.inventory, player.inventory.getItemStack());
            JsonObject observed = serverPlayer(player, beforeOwner);
            OwnedBlockReplacement.requireConserved(initialClientInventory, observed.getAsJsonObject("inventory"));
            state.serverPlayers.add("beforeDig", observed);
            state.stage = "attack_plan";
            attackSearch = attackStages.search(attackFrame, continuing(attackSearch));
            return planningReady(attackSearch);
        }

        private ApproachPlanner.Search continuing(ApproachPlanner.Search previous) {
            return previous != null && previous.partial && previous.selected == null && previous.pages < ApproachPlanner.MAX_SERVER_PAGES && previous.rays < ApproachPlanner.MAX_RAYS ? previous : null;
        }
        private OwnedBlockReplacement.Decision planningReady(ApproachPlanner.Search search) {
            if (search.partial && search.selected == null && search.pages < ApproachPlanner.MAX_SERVER_PAGES && search.rays < ApproachPlanner.MAX_RAYS)
                return OwnedBlockReplacement.Decision.PENDING;
            return OwnedBlockReplacement.Decision.READY;
        }
        private JsonObject requireReady(GuardedContext.Result plan) {
            if (!"ready".equals(JsonData.string(plan.data, "status")) || !plan.data.has("selected") || !plan.data.get("selected").isJsonObject())
                throw new BridgeException("target_unverified", "The complete fresh native replacement approach is not ready.");
            return plan.data.getAsJsonObject("selected");
        }

        private JsonObject observe(Minecraft client, JsonObject data) {
            GuardedContext.Result observed = InspectManyPlayerInventory.after(request.inventory, data, () -> inspectManyPlayerInventory(client), () -> {
                JsonObject current = snapshot(client); current.getAsJsonObject("player").addProperty("playerId", client.thePlayer.getUniqueID().toString()); return current;
            }, observationGuard);
            clientInventory.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            OwnedBlockReplacement.requireConserved(initialClientInventory, observed.data.getAsJsonObject("playerInventory"));
            return OwnedBlockReplacement.pair(observed);
        }

        public OwnedBlockReplacement.Decision dig(OwnedBlockReplacement.State state) {
            Minecraft client = client(state); JsonObject actual = clientPoint(client);
            if (!agrees(actual, attackSearch.target)) return OwnedBlockReplacement.Decision.PENDING;
            request.old.require(actual); if (client.theWorld.getTileEntity(request.x, request.y, request.z) != null) requireNoTile(block(client.theWorld, request.x, request.y, request.z, false));
            GuardedContext.Result plan = attackStages.finish(attackFrame, attackSearch); JsonObject selected = requireReady(plan);
            state.attackPlan = OwnedBlockReplacement.pair(plan);
            if (selected.get("moveRequired").getAsBoolean()) return OwnedBlockReplacement.Decision.MOVE;
            if (client.thePlayer.inventory.currentItem != request.emptySlot || client.thePlayer.getHeldItem() != null)
                throw new BridgeException("inventory_changed", "The native digging hand is not the confirmed empty slot.");
            state.observations.add("beforeDig", observe(client, beforeOwner));
            OwnedBlockReplacement.requireEpochBinding(state.serverPlayers.getAsJsonObject("beforeDig"), state.observations.getAsJsonObject("beforeDig"));
            OwnedBlockReplacement.requireConserved(state.serverPlayers.getAsJsonObject("beforeDig").getAsJsonObject("inventory"), initialClientInventory);
            JsonObject attachment = selected.getAsJsonObject("attachment"); JsonObject args = new JsonObject(); args.addProperty("action", "attack");
            args.addProperty("x", request.x); args.addProperty("y", request.y); args.addProperty("z", request.z); args.add("side", attachment.get("side"));
            state.stage = "native_dig";
            synchronized (state.control) {
                state.checkRunning(); state.nativeDig = interact(client, args, state::markDig);
            }
            if (!state.nativeDig.get("accepted").getAsBoolean() || !state.nativeDigSubmitted)
                throw new BridgeException("native_refused", "The native creative digging invocation was refused; it is not replayed.");
            clientInventory.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            placeFrame = placeStages.capture(); placeSearch = null;
            return OwnedBlockReplacement.Decision.READY;
        }

        public void refresh(OwnedBlockReplacement.State state, boolean place) {
            client(state);
            if ("after_place".equals(state.stage)) return;
            ApproachPlanner.Frame previous = place ? placeFrame : attackFrame;
            ApproachPlanner.Frame fresh = (place ? placeStages : attackStages).capture();
            if (previous != null) previous.check(fresh);
            if (place) placeFrame = fresh; else attackFrame = fresh;
        }

        public OwnedBlockReplacement.Decision afterDig(OwnedBlockReplacement.State state) {
            EntityPlayerMP player = server(state, placeFrame); airOwner = owner(player); JsonObject point = ownerPoint(airOwner);
            if (!"minecraft:air".equals(JsonData.string(point, "registryName"))) {
                request.old.require(point); requireNoTile(point); return OwnedBlockReplacement.Decision.PENDING;
            }
            if (point.get("metadata").getAsInt() != 0 || player.worldObj.getTileEntity(request.x, request.y, request.z) != null)
                throw new BridgeException("target_unverified", "The fresh server digging result is not an exact empty native air point.");
            state.digConfirmed = true; state.serverPlayers.add("afterDig", serverPlayer(player, airOwner));
            if (player.inventory.currentItem != request.emptySlot)
                throw new BridgeException("inventory_changed", "The server's confirmed empty digging hand changed before placement planning.");
            state.stage = "place_plan"; placeSearch = placeStages.search(placeFrame, continuing(placeSearch));
            return planningReady(placeSearch);
        }

        public OwnedBlockReplacement.Decision place(OwnedBlockReplacement.State state) {
            Minecraft client = client(state); JsonObject actual = clientPoint(client);
            if (!agrees(actual, placeSearch.target)) return OwnedBlockReplacement.Decision.PENDING;
            if (client.theWorld.getTileEntity(request.x, request.y, request.z) != null)
                throw new BridgeException("target_unverified", "The client native air point still has a tile entity.");
            GuardedContext.Result plan = placeStages.finish(placeFrame, placeSearch); JsonObject selected = requireReady(plan);
            state.placePlan = OwnedBlockReplacement.pair(plan);
            if (selected.get("moveRequired").getAsBoolean()) return OwnedBlockReplacement.Decision.MOVE;
            state.observations.add("afterDig", observe(client, airOwner));
            OwnedBlockReplacement.requireEpochBinding(state.serverPlayers.getAsJsonObject("afterDig"), state.observations.getAsJsonObject("afterDig"));
            OwnedBlockReplacement.requireConserved(state.serverPlayers.getAsJsonObject("afterDig").getAsJsonObject("inventory"), initialClientInventory);
            state.checkRunning(); NativePlacementAdapter.Plan nativePlan = NativePlacementAdapter.plan(client, request.placeArgs(), request.dimension);
            state.stage = "native_place";
            synchronized (state.control) {
                state.checkRunning();
                state.nativePlace = NativePlacementAdapter.invoke(nativePlan, client.thePlayer.inventory, use -> {
                    state.checkRunning(); state.markMutation();
                    client.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(request.slot));
                    return interact(client, use, state::markPlace);
                });
            }
            if (!state.nativePlace.get("accepted").getAsBoolean() || !state.nativePlaceSubmitted)
                throw new BridgeException("native_refused", "The native placement invocation was refused; no second placement is submitted.");
            clientInventory.require(client.thePlayer.inventory, client.thePlayer.inventory.getItemStack());
            return OwnedBlockReplacement.Decision.READY;
        }

        public void move(OwnedBlockReplacement.State state, boolean place) {
            if (!request.allowMove) throw new BridgeException("movement_required", "A verified approach relocation is required but allowMove is false.");
            ApproachPlanner.Frame frame = place ? placeFrame : attackFrame; server(state, frame);
            JsonObject proof = place ? state.placePlan : state.attackPlan; JsonObject selected = proof.getAsJsonObject("data").getAsJsonObject("selected");
            JsonObject feet = selected.getAsJsonObject("feet"); JsonObject args = new JsonObject(); args.add("x", feet.get("x")); args.add("y", feet.get("y")); args.add("z", feet.get("z"));
            args.addProperty("dimension", request.dimension); args.addProperty("expectedWorld", request.world); args.addProperty("requireFlying", request.flying);
            JsonObject moved;
            synchronized (state.control) { state.markMutation(); moved = teleport(requireDeveloperContext(), args); }
            pendingMove = new JsonObject(); pendingMove.addProperty("phase", place ? "before_place" : "before_dig");
            pendingMove.add("nativeTeleport", moved); pendingMove.add("selectedFeet", feet); pendingMove.add("plan", proof);
        }

        public boolean awaitMove(OwnedBlockReplacement.State state, boolean place) {
            Minecraft client = client(state); JsonObject feet = pendingMove.getAsJsonObject("selectedFeet");
            if (Math.abs(client.thePlayer.posX - feet.get("x").getAsDouble()) > .0001
                    || Math.abs(client.thePlayer.boundingBox.minY - feet.get("y").getAsDouble()) > .0001
                    || Math.abs(client.thePlayer.posZ - feet.get("z").getAsDouble()) > .0001) return false;
            pendingMove.add("afterSnapshot", snapshot(client)); pendingMove.addProperty("confirmed", true); state.moves.add(pendingMove); pendingMove = null;
            if (place) { placeFrame = placeStages.capture(); placeSearch = null; }
            else { attackFrame = attackStages.capture(); attackSearch = null; }
            return true;
        }

        public OwnedBlockReplacement.Decision afterPlace(OwnedBlockReplacement.State state) {
            EntityPlayerMP player = server(state, placeFrame); placedOwner = owner(player); JsonObject point = ownerPoint(placedOwner);
            if ("minecraft:air".equals(JsonData.string(point, "registryName"))) return OwnedBlockReplacement.Decision.PENDING;
            request.placed.require(point); state.placeConfirmed = true; serverPlaced = point;
            if (player.inventory.currentItem != request.slot)
                throw new BridgeException("inventory_changed", "The server placement source slot is not the actual selected source.");
            state.serverPlayers.add("afterPlace", serverPlayer(player, placedOwner));
            return OwnedBlockReplacement.Decision.READY;
        }

        public GuardedContext.Result finish(OwnedBlockReplacement.State state) {
            Minecraft client = client(state); JsonObject actual = clientPoint(client);
            if (!agrees(actual, serverPlaced)) return null;
            request.placed.require(actual);
            if (client.thePlayer.inventory.currentItem != request.slot)
                throw new BridgeException("inventory_changed", "The actual client placement source selection changed before final confirmation.");
            state.observations.add("afterPlace", observe(client, placedOwner));
            OwnedBlockReplacement.requireEpochBinding(state.serverPlayers.getAsJsonObject("afterPlace"), state.observations.getAsJsonObject("afterPlace"));
            JsonObject finalInventory = state.observations.getAsJsonObject("afterPlace").getAsJsonObject("data").getAsJsonObject("playerInventory");
            for (String phase : new String[] {"beforeDig", "afterDig", "afterPlace"}) {
                OwnedBlockReplacement.requireConserved(initialClientInventory, state.observations.getAsJsonObject(phase).getAsJsonObject("data").getAsJsonObject("playerInventory"));
                OwnedBlockReplacement.requireConserved(finalInventory, state.serverPlayers.getAsJsonObject(phase).getAsJsonObject("inventory"));
            }
            JsonObject conserved = new JsonObject(); conserved.addProperty("scope", "all_actual_player_slots");
            conserved.addProperty("actualClientSlots", clientInventory.slots.length); conserved.addProperty("actualServerSlots", serverInventory.slots.length);
            conserved.addProperty("clientItemsPreserved", true); conserved.addProperty("serverItemsPreserved", true); conserved.addProperty("nbtPreserved", true);
            conserved.addProperty("cursorEmpty", true); conserved.addProperty("creativeSourceCountUnchanged", true); state.conservation = conserved;
            JsonObject receipt = state.receipt(); GuardedContext.Result result = guard.result(receipt, state.before);
            JsonObject pair = OwnedBlockReplacement.pair(result);
            if (pair.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new BridgeException("result_too_large", "Complete native replacement receipt exceeds the ordinary HTTP response budget; it is never truncated.");
            return result;
        }
    }

    private ApproachPlanner.Frame captureApproach(JsonObject args) {
        Minecraft client = Minecraft.getMinecraft(); requirePlayer(client);
        if (client.isGamePaused()) throw new BridgeException("world_paused", "Approach planning requires a current unpaused client world.");
        refreshServerContext(client);
        ServerContext context = requireDeveloperContext(); requireExpectedWorld(context, args);
        EntityPlayer player = client.thePlayer;
        NativePlacementAdapter.requireMatchingMode(player.capabilities.isCreativeMode, client.playerController.isInCreativeMode());
        if (player.dimension != context.dimension || client.theWorld.provider.dimensionId != context.dimension
                || player.ridingEntity != null || player.riddenByEntity != null || player.isDead)
            throw new BridgeException("invalid_state", "Approach planning requires the current unmounted live player and matching dimensions.");
        ApproachPlanner.Request request = new ApproachPlanner.Request(args, context.dimension);
        if (request.operationPosture != null && (player.capabilities.isCreativeMode || player.onGround))
            throw new BridgeException("permission_denied", "Explicit hover operation posture requires the real airborne survival actor.");
        if (request.requireFlying && (!player.capabilities.allowFlying || !player.capabilities.isFlying))
            throw new BridgeException("invalid_state", "Airborne approach planning requires current client flight and authoritative server flight.");
        requireApproachShape(player);
        Vec3 eye = player.getPosition(1F);
        if (Math.abs(eye.xCoord - player.posX) > .0001 || Math.abs(eye.zCoord - player.posZ) > .0001)
            throw new BridgeException("unsupported", "A displaced mod camera cannot certify a normal player approach eye.");
        ItemStack item = player.inventory.getStackInSlot(request.slot >= 0 ? request.slot : player.inventory.currentItem);
        ItemStack heldItem = player.inventory.getStackInSlot(player.inventory.currentItem);
        if (request.slot >= 0) NativePlacementAdapter.requireItem(item);
        return new ApproachPlanner.Frame(request, client.theWorld, context.server, sessionId, player.getUniqueID().toString(),
                JsonData.item(heldItem).toString(), approachTag(heldItem), JsonData.item(item).toString(), approachTag(item), player.posX, player.boundingBox.minY, player.posZ,
                eye.yCoord - player.boundingBox.minY, player.width, player.height, client.playerController.getBlockReachDistance(), player.boundingBox,
                player.rotationYaw, player.rotationPitch, client.theWorld.getTotalWorldTime(), player.inventory.currentItem, player.capabilities.isFlying,
                player.isSneaking(), player.inventory.getItemStack() == null, client.currentScreen, identifyScreen(client.currentScreen),
                player.yOffset2, client.thePlayer.movementInput.sneak);
    }

    public static String approachTag(ItemStack item) {
        String tag = item == null || item.stackTagCompound == null ? "" : item.stackTagCompound.toString();
        if (tag.length() > 65536) throw new BridgeException("unsupported", "Approach material tag exceeds the bounded identity sample.");
        return tag;
    }

    private static void requireApproachShape(EntityPlayer player) {
        if (player.boundingBox == null || Math.abs(player.boundingBox.maxY - player.boundingBox.minY - player.height) > .0001
                || Math.abs(player.boundingBox.maxX - player.boundingBox.minX - player.width) > .0001
                || Math.abs(player.boundingBox.maxZ - player.boundingBox.minZ - player.width) > .0001)
            throw new BridgeException("unsupported", "The actual player bounding box does not match the supported normal body dimensions.");
    }

    private static ApproachPlanner.Access approachAccess(final World world, final EntityPlayer player) {
        final NativePlacementAdapter.Access blocks = NativePlacementAdapter.access(world, world.provider.dimensionId);
        return new ApproachPlanner.Access() {
            public boolean loaded(int x, int y, int z) { return blocks.loaded(x, y, z); }
            public NativePlacementAdapter.State state(int x, int y, int z) {
                NativePlacementAdapter.State state = blocks.state(x, y, z);
                Object tile = world.getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
                if (tile instanceof TileEntity) {
                    JsonObject identity = GregTechAdapter.identity((TileEntity) tile);
                    if (identity != null) state.description.add("gregtech", identity);
                }
                return state;
            }
            public MovingObjectPosition trace(Vec3 eye, Vec3 point) { return blocks.trace(eye, point); }
            public AxisAlignedBB selectedBounds(int x, int y, int z) { return blocks.selectedBounds(x, y, z); }
            public JsonObject selectedPart(MovingObjectPosition hit) { return blocks.selectedPart(hit); }
            public AxisAlignedBB partBounds(int x, int y, int z, JsonObject part) { return blocks.partBounds(x, y, z, part); }
            public java.util.List<AxisAlignedBB> partBoxes(int x, int y, int z, JsonObject part) { return blocks.partBoxes(x, y, z, part); }
            public JsonObject space(int x, int y, int z, double width, double height, long deadline) {
                return WorldObservationsAdapter.space(world, player, x, y, z, width, height, deadline);
            }
            public JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline) {
                return WorldObservationsAdapter.spaceAt(world, player, x, y, z, width, height, deadline, false);
            }
            public JsonObject spaceAt(double x, double y, double z, double width, double height, long deadline, boolean fullSupport) {
                if (fullSupport) {
                    WalkingPathPlanner.Point point = new WalkingPathPlanner.Point(x,y,z);
                    return WorldObservationsAdapter.walkingSpaceAt(world,player,new WalkingPathPlanner.Sweep(point,point,width,height),deadline,false);
                }
                return WorldObservationsAdapter.spaceAt(world, player, x, y, z, width, height, deadline, fullSupport);
            }
            public AxisAlignedBB placementBody(double x, double y, double z, boolean currentOnly) {
                AxisAlignedBB actual = NativePlacementAdapter.actorBody(player.boundingBox);
                return currentOnly ? actual : actual.getOffsetBoundingBox(x - player.posX, y - actual.minY, z - player.posZ);
            }
        };
    }

    private JsonObject interact(Minecraft client, JsonObject args) {
        return interact(client, args, null);
    }

    private JsonObject interact(Minecraft client, JsonObject args, Runnable nativeStarting) {
        requireWorldControls(client);
        if (args.has("expectedHeld")) {
            JsonElement value = args.get("expectedHeld");
            if (!value.isJsonObject()) throw new BridgeException("invalid_args", "expectedHeld requires slot, registryName and metadata.");
            JsonObject expected = value.getAsJsonObject(); ExpandedGuiApi.keys(expected, "slot", "registryName", "metadata");
            ItemStack actual = client.thePlayer.getHeldItem();
            if (client.thePlayer.inventory.currentItem != JsonData.integer(expected, "slot", 0, 8) || actual == null
                    || !JsonData.string(expected, "registryName").equals(Item.itemRegistry.getNameForObject(actual.getItem()))
                    || actual.getMetadata() != JsonData.integer(expected, "metadata", 0, 32767))
                throw new BridgeException("held_item_changed", "Selected held item identity changed before interaction; no use submitted. NBT/count identity is not asserted by this binding.");
        }
        String beforeScreenId = identifyScreen(client.currentScreen);
        String action = JsonData.string(args, "action");
        if (!"use".equals(action) && !"attack".equals(action)) throw new BridgeException("invalid_args", "action must be use or attack.");
        boolean requireSurvivalFlight = GuiDataAdapter.booleanArg(args, "requireSurvivalFlight", false);
        if (requireSurvivalFlight) {
            if (!"use".equals(action) || !args.has("x") || !args.has("y") || !args.has("z"))
                throw new BridgeException("invalid_args", "Airborne survival interaction requires one explicit normal use target.");
            SurvivalFlightPosture.actor(client.thePlayer.capabilities.isCreativeMode, client.playerController.isInCreativeMode(), client.thePlayer.capabilities.allowFlying, client.thePlayer.capabilities.isFlying);
            ServerContext context = requireDeveloperContext();
            SurvivalFlightPosture.snapshot(snapshot(client), sessionId, client.thePlayer.getUniqueID().toString(), context.server.getWorldName(), client.thePlayer.dimension);
        }
        boolean restoreView = interactionRestoreView(args, action, "developer".equals(profile));
        if (restoreView) requireDeveloperContext();
        if (args.has("sneaking") && (!args.get("sneaking").isJsonPrimitive()
                || !args.get("sneaking").getAsJsonPrimitive().isBoolean() || !"use".equals(action)))
            throw new BridgeException("invalid_args", "sneaking must be a boolean on a use action.");
        MovingObjectPosition target = client.objectMouseOver;
        boolean explicit = args.has("x") || args.has("y") || args.has("z");
        boolean wrenchFacing = GuiDataAdapter.booleanArg(args, "wrenchFacing", false);
        JsonObject hitPoint = InteractionHitPoint.requested(args);
        if (hitPoint != null && (!explicit || !args.has("side") || wrenchFacing))
            throw new BridgeException("invalid_args", "hitPoint requires an explicit XYZ/side and excludes wrenchFacing.");
        if (wrenchFacing) {
            if (!explicit || !"use".equals(action) || !args.has("side") || !args.has("expectedHeld")
                    || args.has("targetPart") || GuiDataAdapter.booleanArg(args, "sneaking", false))
                throw new BridgeException("invalid_args", "wrenchFacing requires one explicit nonsneaking GT use face and expectedHeld.");
            NativeGtWrench.source(client.thePlayer.getHeldItem());
        }
        if (explicit) {
            int x = JsonData.integer(args, "x", -30000000, 30000000);
            int y = JsonData.integer(args, "y", 0, 255);
            int z = JsonData.integer(args, "z", -30000000, 30000000);
            int side = args.has("side") ? JsonData.integer(args, "side", 0, 5) : 1;
            NativePlacementAdapter.Access blocks = NativePlacementAdapter.access(client.theWorld, client.thePlayer.dimension);
            if (!blocks.loaded(x, y, z))
                throw new BridgeException("chunk_unloaded", "Interaction target is not actually loaded.");
            if (args.has("targetPart")) {
                if (!args.get("targetPart").isJsonObject()) throw new BridgeException("invalid_args", "targetPart requires an exact mounted part object.");
                JsonObject part = AePartAdapter.declaration(args.getAsJsonObject("targetPart"));
                AePartAdapter.requirePresent(part, blocks.state(x, y, z).description);
                blocks = NativePlacementAdapter.forPart(blocks, part);
            }
            Vec3 point = NativePlacementAdapter.facePoint(x, y, z, side);
            Vec3 eye = client.thePlayer.getPosition(1.0F);
            double reach = client.playerController.getBlockReachDistance();
            if (NativePlacementAdapter.distanceToBlock(eye, x, y, z) > reach)
                throw new BridgeException("out_of_reach", "Interaction target exceeds normal player reach.");
            if (!NativePlacementAdapter.loadedRay(blocks, eye, point, y))
                throw new BridgeException("chunk_unloaded", "The interaction ray crosses a client chunk that is not actually loaded.");
            MovingObjectPosition line = hitPoint != null ? InteractionHitPoint.probe(blocks, eye, reach, x, y, z, side, hitPoint)
                    : wrenchFacing ? NativeGtWrench.target(blocks, eye, reach, x, y, z, side)
                    : NativePlacementAdapter.visibleFace(blocks, eye, reach, x, y, z, side, client.objectMouseOver);
            if (line == null) {
                MovingObjectPosition primary = blocks.trace(Vec3.createVectorHelper(eye.xCoord, eye.yCoord, eye.zCoord), point);
                if (primary != null && primary.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                        && (primary.blockX != x || primary.blockY != y || primary.blockZ != z))
                    throw new BridgeException("blocked", "Another block obstructs the interaction target.");
                throw new BridgeException("face_not_visible", "Requested block face is not reachable from the player's current view position.");
            }
            target = line;
        }
        if (!explicit && args.has("targetPart"))
            throw new BridgeException("invalid_args", "targetPart requires an explicit loaded block target; no use was submitted.");
        if (args.has("sneaking") && (target == null || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK))
            throw new BridgeException("unsupported", "Scoped sneaking requires a reachable block target.");
        boolean accepted = false;
        if (target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            boolean beforeSneak = client.thePlayer.movementInput.sneak;
            boolean scopedSneak = args.has("sneaking");
            float beforeYaw = client.thePlayer.rotationYaw;
            float beforePitch = client.thePlayer.rotationPitch;
            MovingObjectPosition beforeTarget = client.objectMouseOver;
            try {
                if (explicit) {
                    Vec3 eye = client.thePlayer.getPosition(1.0F);
                    double dx = target.hitVec.xCoord - eye.xCoord;
                    double dy = target.hitVec.yCoord - eye.yCoord;
                    double dz = target.hitVec.zCoord - eye.zCoord;
                    client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    client.thePlayer.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    if (!restoreView) {
                        client.thePlayer.prevRotationYaw = client.thePlayer.rotationYaw;
                        client.thePlayer.prevRotationPitch = client.thePlayer.rotationPitch;
                    }
                    client.objectMouseOver = target;
                }
                if (explicit || scopedSneak) {
                    // Synchronize the aimed view before either the block-use or block-attack packet.
                    client.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                            client.thePlayer.rotationYaw, client.thePlayer.rotationPitch, client.thePlayer.onGround));
                }
                if (scopedSneak) {
                    boolean requested = args.get("sneaking").getAsBoolean();
                    client.thePlayer.movementInput.sneak = requested;
                    // Preserve normal packet order: aimed look/sneak state before the block-use packet.
                    client.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(client.thePlayer, requested ? 1 : 2));
                }
                if ("use".equals(action)) {
                    final MovingObjectPosition nativeTarget = target;
                    accepted = structures.blueprints().runNative(client, sessionId, "developer".equals(profile), nativeTarget,
                            () -> {
                                if (nativeStarting != null) nativeStarting.run();
                                return client.playerController.onPlayerRightClick(client.thePlayer, client.theWorld, client.thePlayer.getHeldItem(),
                                        nativeTarget.blockX, nativeTarget.blockY, nativeTarget.blockZ, nativeTarget.sideHit, nativeTarget.hitVec);
                            });
                    if (!accepted && !explicit) accepted = client.playerController.sendUseItem(client.thePlayer, client.theWorld, client.thePlayer.getHeldItem());
                } else {
                    if (nativeStarting != null) nativeStarting.run();
                    // Unlike onPlayerDamageBlock/right-use, vanilla clickBlock does
                    // not sync the selected tool. A just-selected instant tool must
                    // reach the server before its one start-dig packet.
                    client.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(client.thePlayer.inventory.currentItem));
                    client.playerController.clickBlock(target.blockX, target.blockY, target.blockZ, target.sideHit);
                    accepted = true;
                }
            } finally {
                if (scopedSneak) {
                    client.thePlayer.movementInput.sneak = beforeSneak;
                }
                if (explicit && restoreView) {
                    client.thePlayer.rotationYaw = beforeYaw;
                    client.thePlayer.rotationPitch = beforePitch;
                    client.objectMouseOver = beforeTarget;
                }
                try {
                    if (scopedSneak)
                        client.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(client.thePlayer, beforeSneak ? 1 : 2));
                } finally {
                    if (explicit && restoreView)
                        client.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                                beforeYaw, beforePitch, client.thePlayer.onGround));
                }
            }
        } else if (target != null && target.entityHit != null) {
            if ("attack".equals(action)) { client.playerController.attackEntity(client.thePlayer, target.entityHit); accepted = true; }
            else accepted = client.playerController.interactWithEntitySendPacket(client.thePlayer, target.entityHit);
        } else if ("use".equals(action)) {
            accepted = client.playerController.sendUseItem(client.thePlayer, client.theWorld, client.thePlayer.getHeldItem());
        }
        if (accepted || "attack".equals(action)) client.thePlayer.swingItem();
        JsonObject result = status("accepted", accepted);
        String afterScreenId = identifyScreen(client.currentScreen);
        result.addProperty("beforeScreenId", beforeScreenId);
        result.addProperty("screenId", afterScreenId);
        result.addProperty("guiOpen", client.currentScreen != null);
        result.addProperty("guiChanged", !afterScreenId.equals(beforeScreenId));
        ScreenSemantics.describe(result, client.currentScreen);
        JsonObject endingView = new JsonObject(); endingView.addProperty("yaw", client.thePlayer.rotationYaw); endingView.addProperty("pitch", client.thePlayer.rotationPitch);
        result.add("endingView", endingView);
        result.addProperty("completion", "accepted reports the local interaction return value; GUI opening and server effects require a later gui.inspect/client.snapshot confirmation.");
        if (explicit && target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            JsonObject blockTarget = new JsonObject();
            blockTarget.addProperty("x", target.blockX);
            blockTarget.addProperty("y", target.blockY);
            blockTarget.addProperty("z", target.blockZ);
            blockTarget.addProperty("side", target.sideHit);
            blockTarget.addProperty("hitX", target.hitVec.xCoord);
            blockTarget.addProperty("hitY", target.hitVec.yCoord);
            blockTarget.addProperty("hitZ", target.hitVec.zCoord);
            result.add("blockTarget", blockTarget);
            result.addProperty("airUseFallback", false);
            result.addProperty("viewRestored", restoreView);
            if (wrenchFacing) result.addProperty("nativeGtWrenchFacingVersion", 1);
            if (hitPoint != null) { result.addProperty("interactionHitPointVersion", 1); result.add("hitPoint", hitPoint); }
        }
        return result;
    }

    static boolean interactionRestoreView(JsonObject args, String action, boolean developer) {
        boolean restore = GuiDataAdapter.booleanArg(args, "restoreView", false);
        if (args.has("restoreView") && ((!"use".equals(action) && !"attack".equals(action)) || !args.has("x") || !args.has("y") || !args.has("z")))
            throw new BridgeException("invalid_args", "restoreView requires action=use or attack and a complete explicit x/y/z block target.");
        if (restore && !developer)
            throw new BridgeException("permission_denied", "restoreView=true is reserved for the local developer profile; normal explicit block interactions retain the target-facing view.");
        return restore;
    }

    private WholeStackTransfer.Access wholeStackAccess(final WholeStackTransfer.Request request, final GuardedContext guard) {
        return new WholeStackTransfer.Access() {
            Object boundServer, boundWorld, boundScreen, boundPlayer;
            String boundPlayerId;
            long boundEpoch;
            double normalReach;
            private Minecraft currentClient() {
                Minecraft client = Minecraft.getMinecraft(); requirePlayer(client); refreshServerContext(client);
                guard.check(GameBridge.this.snapshot(client)); ServerContext context = requireDeveloperContext(); JsonObject expected = new JsonObject(); expected.addProperty("expectedWorld", request.world); requireExpectedWorld(context, expected);
                if (!sessionId.equals(request.session) || client.isGamePaused() || context.dimension != request.dimension
                        || !request.screen.equals(identifyScreen(client.currentScreen)) || !(client.currentScreen instanceof GuiContainer)
                        || client.currentScreen instanceof GuiContainerCreative || !held.isEmpty() || navigation != null)
                    throw new BridgeException("whole_stack_context_changed", "The exact unpaused, protected native transfer screen/player is unavailable.");
                if (boundServer == null) {
                    boundServer = context.server; boundWorld = client.theWorld; boundScreen = client.currentScreen; boundPlayer = client.thePlayer;
                    boundPlayerId = client.thePlayer.getUniqueID().toString(); boundEpoch = scanEpoch; normalReach = client.playerController.getBlockReachDistance();
                }
                if (boundServer != context.server || boundWorld != client.theWorld || boundScreen != client.currentScreen
                        || boundPlayer != client.thePlayer || !boundPlayerId.equals(context.playerId.toString()) || scanEpoch != boundEpoch)
                    throw new BridgeException("whole_stack_context_changed", "The transfer session, world, player, screen or resource epoch changed.");
                requireCurrentContainer(client, client.currentScreen); return client;
            }
            public WholeStackTransfer.Side client() {
                Minecraft client = currentClient(); Container container = ((GuiContainer) client.currentScreen).inventorySlots;
                return new WholeStackTransfer.Side(request, client.theWorld, client.thePlayer, container, MachineSlotBinding.observe(client, container), Math.min(normalReach, client.playerController.getBlockReachDistance()), false);
            }
            public WholeStackTransfer.Side server() {
                ServerContext context = requireDeveloperContext(); JsonObject expected = new JsonObject(); expected.addProperty("expectedWorld", request.world); requireExpectedWorld(context, expected);
                if (context.server != boundServer || context.dimension != request.dimension || !boundPlayerId.equals(context.playerId.toString())
                        || context.paused || context.screenIdentity != boundScreen || !request.screen.equals(context.screenId) || scanEpoch != boundEpoch
                        || System.nanoTime() - context.capturedAt > 1000000000L)
                    throw new BridgeException("whole_stack_context_changed", "The authoritative transfer context changed or became stale.");
                EntityPlayerMP actor = (EntityPlayerMP) operationPlayer(true); Container container = actor.openContainer;
                return new WholeStackTransfer.Side(request, actor.worldObj, actor, container, MachineSlotBinding.observe(actor.worldObj, actor, container, true), Math.min(normalReach, actor.theItemInWorldManager.getBlockReachDistance()), true);
            }
            public JsonObject snapshot() { return GameBridge.this.snapshot(currentClient()); }
            public void click(int slot) {
                Minecraft client = currentClient(); Container container = ((GuiContainer) client.currentScreen).inventorySlots;
                client.playerController.windowClick(container.windowId, slot, 0, 0, client.thePlayer);
            }
        };
    }

    private JsonObject slotClick(Minecraft client, JsonObject args) {
        requirePlayer(client);
        if (!(client.currentScreen instanceof GuiContainer)) throw new BridgeException("invalid_state", "No container screen is open.");
        if (client.currentScreen instanceof GuiContainerCreative)
            throw new BridgeException("unsupported", "Creative inventory uses virtual slots and creative packets; use gui.click with its current screenId instead.");
        requireCurrentContainer(client, client.currentScreen);
        Container container = ((GuiContainer) client.currentScreen).inventorySlots;
        int window = JsonData.integer(args, "windowId", 0, 32767);
        if (window != container.windowId || client.thePlayer.openContainer.windowId != window)
            throw new BridgeException("stale_window", "windowId no longer matches the player's open container.");
        int slot = JsonData.integer(args, "slot", -999, Math.max(0, container.inventorySlots.size() - 1));
        if (slot < 0 && slot != -999) throw new BridgeException("invalid_args", "Only slot -999 denotes outside the container.");
        int mode = args.has("mode") ? JsonData.integer(args, "mode", 0, 6) : 0;
        if (mode == 5) throw new BridgeException("unsupported", "Drag mode requires a multi-packet gesture and is not supported by slot_click.");
        int button = args.has("button") ? JsonData.integer(args, "button", 0, mode == 2 ? 8 : mode == 3 ? 2 : 1) : mode == 3 ? 2 : 0;
        if (mode == 3 && (button != 2 || !client.thePlayer.capabilities.isCreativeMode))
            throw new BridgeException("invalid_args", "Clone mode requires creative mode and button 2.");
        if (slot == -999 && mode != 0 && mode != 4) throw new BridgeException("invalid_args", "Outside clicks support only pickup or drop mode.");
        GuiContainer screen = (GuiContainer) client.currentScreen;
        Slot backing = slot < 0 ? null : (Slot) container.inventorySlots.get(slot);
        if (backing != null && backing.slotNumber != slot)
            throw new BridgeException("unsupported", "Native slot number differs from its container index; no click was submitted.");
        SlotAcceptance.preflight(backing, client.thePlayer.inventory.getItemStack(), mode);
        JsonObject preparation = BogoCreativeInput.prepareSlotClick(screen, backing, client.thePlayer);
        if (client.currentScreen != screen || screen.inventorySlots != container)
            throw new BridgeException("stale_container", "The native GUI changed during input preparation; no click was submitted.");
        requireCurrentContainer(client, screen);
        if (container.windowId != window || (backing != null && (container.inventorySlots.size() <= slot
                || container.inventorySlots.get(slot) != backing || backing.slotNumber != slot)))
            throw new BridgeException("stale_window", "The native window or target slot changed during input preparation; no click was submitted.");
        ItemStack slotBefore = backing == null || backing.getStack() == null ? null : backing.getStack().copy();
        ItemStack cursorBefore = client.thePlayer.inventory.getItemStack() == null ? null : client.thePlayer.inventory.getItemStack().copy();
        ItemStack hotbarBefore = mode != 2 || client.thePlayer.inventory.getStackInSlot(button) == null ? null : client.thePlayer.inventory.getStackInSlot(button).copy();
        boolean canTake = backing != null && backing.canTakeStack(client.thePlayer);
        String swapStrategy = args.has("swapStrategy") ? ExpandedGuiApi.option(args, "swapStrategy", null, "auto", "native", "pickup") : "auto";
        if (args.has("swapStrategy") && mode != 2) throw new BridgeException("invalid_args", "swapStrategy is only valid for hotbar mode 2.");
        boolean pickupSwap = mode == 2 && ("pickup".equals(swapStrategy) || "auto".equals(swapStrategy)
                && ExpandedGuiApi.is(container, "com.cleanroommc.modularui.screen.ModularContainer")
                && NativeHotbarSwap.wrappedPlayerSlot(backing, client.thePlayer.inventory));
        JsonObject swapReceipt = null; ItemStack nativeReturn = null;
        if (pickupSwap) {
            swapReceipt = NativeHotbarSwap.perform(container, backing, button, client.thePlayer.inventory, new NativeHotbarSwap.Input() {
                public boolean current() { return inputContextMatches(client, screen, container, window); }
                public boolean canTake(Slot candidate) { return candidate.canTakeStack(client.thePlayer); }
                public void pickup(int nativeSlot) { client.playerController.windowClick(window, nativeSlot, 0, 0, client.thePlayer); }
            });
        } else nativeReturn = client.playerController.windowClick(window, slot, button, mode, client.thePlayer);
        JsonObject result = ScreenSemantics.describe(status("submitted", true), client.currentScreen);
        result.addProperty("windowId", window); result.addProperty("slot", slot); result.addProperty("button", button); result.addProperty("mode", mode);
        result.addProperty("source", "client_native_container"); result.addProperty("containerClass", container.getClass().getName());
        result.addProperty("inputContextCurrent", client.currentScreen == screen && client.thePlayer.openContainer == container && container.windowId == window);
        result.addProperty("serverConfirmed", false); result.add("inputPreparation", preparation);
        if (swapReceipt != null) { result.add("hotbarSwap", swapReceipt); result.addProperty("submitted", swapReceipt.get("submitted").getAsBoolean()); }
        result.add("slotBefore", JsonData.item(slotBefore)); result.add("slotAfter", JsonData.item(backing == null ? null : backing.getStack()));
        result.add("cursorBefore", JsonData.item(cursorBefore)); result.add("cursorAfter", JsonData.item(client.thePlayer.inventory.getItemStack()));
        result.add("nativeReturnItem", JsonData.item(nativeReturn)); result.addProperty("targetSlotPresent", backing != null);
        if (backing != null) { result.addProperty("slotClass", backing.getClass().getName()); result.addProperty("canTakeStackAtDispatch", canTake); }
        boolean changed = !ItemStack.areItemStacksEqual(slotBefore, backing == null ? null : backing.getStack())
                || !ItemStack.areItemStacksEqual(cursorBefore, client.thePlayer.inventory.getItemStack());
        if (mode == 2) {
            ItemStack hotbarAfter = client.thePlayer.inventory.getStackInSlot(button);
            result.addProperty("hotbarSlot", button); result.add("hotbarBefore", JsonData.item(hotbarBefore)); result.add("hotbarAfter", JsonData.item(hotbarAfter));
            changed |= !ItemStack.areItemStacksEqual(hotbarBefore, hotbarAfter);
        }
        result.addProperty("localEffectObserved", changed);
        result.addProperty("completion", swapReceipt == null ? "One native windowClick was submitted. Returned stack and immediate changes are client prediction; later container/inventory state is required to confirm server acceptance."
                : swapReceipt.get("completion").getAsString());
        return result;
    }

    private GuiScreen requireGui(Minecraft client, JsonObject args) {
        GuiScreen screen = client.currentScreen;
        if (screen == null) throw new BridgeException("invalid_state", "No game screen is open.");
        if (!JsonData.string(args, "screenId").equals(identifyScreen(screen)))
            throw new BridgeException("stale_screen", "screenId no longer matches the current game screen.");
        if (screen instanceof GuiCommandBlock && (!"developer".equals(profile) || serverContext == null))
            throw new BridgeException("permission_denied", "Command-block GUI controls require developer profile in an owned integrated world.");
        return screen;
    }

    private JsonObject clickGui(Minecraft client, JsonObject args) {
        GuiScreen screen = requireGui(client, args);
        int x = JsonData.integer(args, "x", 0, Math.max(0, screen.width - 1));
        int y = JsonData.integer(args, "y", 0, Math.max(0, screen.height - 1));
        int button = args.has("button") ? JsonData.integer(args, "button", 0, 2) : 0;
        String inputScreenId = identifyScreen(screen);
        List<?> inputButtons = nativeButtons(screen);
        Container inputContainer = client.thePlayer == null ? null : client.thePlayer.openContainer;
        int inputWindow = inputContainer == null ? -1 : inputContainer.windowId;
        JsonObject beforeInput = GuiControlAdapter.observeInputState(client, screen, inputScreenId, inputButtons);
        if (!inputContextMatches(client, screen, inputContainer, inputWindow))
            throw new BridgeException("stale_window", "The native input context changed before click dispatch.");
        JsonObject pointer = LogicalGuiPointer.prepareClick(client, screen, x, y, sessionId, () -> background.active(client));
        JsonObject coordinates;
        try { coordinates = GuiCallbacks.click(screen, x, y, button, () -> inputContextMatches(client, screen, inputContainer, inputWindow)); }
        catch (RuntimeException | LinkageError error) {
            if (pointer != null) LogicalGuiPointer.clear("click_callback_failed");
            throw error;
        }
        boolean inputContextCurrent = inputContextMatches(client, screen, inputContainer, inputWindow);
        JsonObject afterInput = inputContextCurrent ? GuiControlAdapter.observeInputState(client, screen, inputScreenId, inputButtons) : null;
        JsonObject result = gui(client);
        result.add("inputReceipt", GuiCallbacks.inputReceipt(beforeInput, afterInput, client.currentScreen == screen));
        result.getAsJsonObject("inputReceipt").addProperty("inputContextCurrent", inputContextCurrent);
        result.getAsJsonObject("inputReceipt").add("coordinateDispatch", coordinates);
        if (pointer != null) result.getAsJsonObject("inputReceipt").add("naturalFramePointer", pointer);
        return result;
    }

    private JsonObject keyGui(Minecraft client, JsonObject args) {
        GuiScreen screen = requireGui(client, args);
        int key = JsonData.integer(args, "keyCode", 0, 255);
        if (key == 1) StartupScreenRecovery.requireNativeChoice(screen);
        String character = args.has("character") ? JsonData.string(args, "character") : "";
        String inputScreenId = identifyScreen(screen);
        List<?> inputButtons = nativeButtons(screen);
        Container inputContainer = client.thePlayer == null ? null : client.thePlayer.openContainer;
        int inputWindow = inputContainer == null ? -1 : inputContainer.windowId;
        JsonObject beforeInput = GuiControlAdapter.observeInputState(client, screen, inputScreenId, inputButtons);
        if (!inputContextMatches(client, screen, inputContainer, inputWindow))
            throw new BridgeException("stale_window", "The native input context changed before key dispatch.");
        GuiCallbacks.key(screen, key, character);
        boolean inputContextCurrent = inputContextMatches(client, screen, inputContainer, inputWindow);
        JsonObject afterInput = inputContextCurrent ? GuiControlAdapter.observeInputState(client, screen, inputScreenId, inputButtons) : null;
        JsonObject result = gui(client);
        result.add("inputReceipt", GuiCallbacks.inputReceipt(beforeInput, afterInput, client.currentScreen == screen));
        result.getAsJsonObject("inputReceipt").addProperty("inputContextCurrent", inputContextCurrent);
        return result;
    }

    private static boolean inputContextMatches(Minecraft client, GuiScreen screen, Container container, int window) {
        Container actual = client.thePlayer == null ? null : client.thePlayer.openContainer;
        return client.currentScreen == screen && actual == container && (actual == null || actual.windowId == window);
    }

    private void requirePlayer(Minecraft client) {
        if (client.thePlayer == null || client.theWorld == null || client.playerController == null)
            throw new BridgeException("world_unavailable", "No playable client world is loaded.");
    }

    private void requireWorldControls(Minecraft client) {
        requirePlayer(client);
        if (client.currentScreen != null || client.isGamePaused()) throw new BridgeException("invalid_state", "Close the game screen before using world controls.");
    }

    private JsonObject status(String key, Object value) {
        JsonObject result = new JsonObject();
        if (value instanceof Boolean) result.addProperty(key, (Boolean) value);
        else if (value instanceof Number) result.addProperty(key, (Number) value);
        else result.addProperty(key, String.valueOf(value));
        return result;
    }

    private static class ServerContext {
        final IntegratedServer server;
        final int dimension;
        final boolean developer;
        final UUID playerId;
        final boolean screenOpen;
        final Object screenIdentity;
        final String screenId;
        final boolean paused;
        final long capturedAt;
        ServerContext(IntegratedServer server, int dimension, boolean developer, UUID playerId, boolean screenOpen, boolean paused, long capturedAt) {
            this(server, dimension, developer, playerId, screenOpen, paused, capturedAt, null, "");
        }
        ServerContext(IntegratedServer server, int dimension, boolean developer, UUID playerId, boolean screenOpen, boolean paused, long capturedAt, Object screenIdentity, String screenId) {
            this.server = server;
            this.dimension = dimension;
            this.developer = developer;
            this.playerId = playerId;
            this.screenOpen = screenOpen;
            this.screenIdentity = screenIdentity;
            this.screenId = screenId;
            this.paused = paused;
            this.capturedAt = capturedAt;
        }
    }

    private static class FlightObservation {
        final IntegratedServer server;
        final UUID playerId;
        final int dimension;
        final long worldTick, capturedAt;
        final boolean flying, allowFlying, creative, onGround, canTeleport;
        final JsonObject control;
        FlightObservation(IntegratedServer server, UUID playerId, int dimension, long worldTick, boolean flying,
                boolean allowFlying, boolean creative, boolean onGround, long capturedAt, JsonObject control, boolean canTeleport) {
            this.canTeleport = canTeleport;
            this.server = server; this.playerId = playerId; this.dimension = dimension; this.worldTick = worldTick;
            this.flying = flying; this.allowFlying = allowFlying; this.creative = creative; this.onGround = onGround; this.capturedAt = capturedAt;
            this.control = control;
        }
    }

    private static class ServerIdentity {
        final IntegratedServer server;
        final String name;
        final String folder;
        ServerIdentity(IntegratedServer server, String name, String folder) {
            this.server = server;
            this.name = JsonData.bounded(name, 512);
            this.folder = JsonData.bounded(folder, 512);
        }
    }
}

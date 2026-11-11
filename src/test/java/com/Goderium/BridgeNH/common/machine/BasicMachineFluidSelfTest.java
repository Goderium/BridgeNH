package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.client.gui.FluidGuiAdapter;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.client.gui.WidgetFixtures;
import com.Goderium.BridgeNH.client.nei.ExpandedGtSelfTest;
import com.Goderium.BridgeNH.util.JsonData;

import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.widgets.slot.FluidSlot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.fluids.IFluidTank;

/** Mocked actual tank identity, complete named snapshots and read-only failure behavior. */
public final class BasicMachineFluidSelfTest {
    private static int checks;
    private static final String NAME = "bridge_named_tank_fixture.fluid";
    public static int run() {
        checks = 0;
        if (FluidRegistry.getFluid(NAME) == null) FluidRegistry.registerFluid(new Fluid(NAME));
        loadedOwnerGateway(); snapshots(); failures(); bindings(); gui(); hooks();
        return checks;
    }

    private static void loadedOwnerGateway() {
        // Expected chunk/local coordinates are fixed independently of the adapter's bitmask expression.
        int[][] cases = {
            {-260, 119, -120, -17, -8, 12, 8}, {16, 64, 31, 1, 1, 0, 15},
            {-1, 70, -16, -1, -1, 15, 0}, {-17, 71, 32, -2, 2, 15, 0},
            {0, 72, 15, 0, 0, 0, 15}, {15, 255, -17, 0, -2, 15, 15}
        };
        for (int[] value : cases) {
            LoadedWorld world = allocate(LoadedWorld.class);
            world.expectedChunkX = value[3]; world.expectedChunkZ = value[4]; world.loaded = true;
            world.chunk = new LoadedChunk(world, value[3], value[4]);
            world.publicProvider = (IChunkProvider) Proxy.newProxyInstance(IChunkProvider.class.getClassLoader(),
                    new Class<?>[] {IChunkProvider.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("chunkExists")) throw new AssertionError("Gateway must not load or generate chunks");
                        check((Integer) args[0] == world.expectedChunkX && (Integer) args[1] == world.expectedChunkZ,
                                "Native provider receives the actual positive or negative chunk coordinate");
                        return world.loaded;
                    });
            TileEntity tile = new TileEntity(); tile.xCoord = value[0]; tile.yCoord = value[1]; tile.zCoord = value[2];
            // Inherited native Chunk.addTileEntity computes local coordinates using chunk-origin subtraction.
            world.chunk.addTileEntity(tile);
            ChunkPosition localKey = new ChunkPosition(value[5], value[1], value[6]);
            check(world.chunk.chunkTileEntityMap.get(localKey) == tile,
                    "Native Chunk.addTileEntity populated the independent expected local key");
            check(present(world, tile) && world.chunkReads == 1,
                    "The actual owner gateway finds the same loaded tile across chunk boundaries");
            world.chunk.chunkTileEntityMap.put(localKey, new TileEntity());
            check(!present(world, tile), "A different tile object at the same local key cannot prove owner identity");
            world.chunk.chunkTileEntityMap.clear();
            ChunkPosition worldKey = new ChunkPosition(value[0], value[1], value[2]);
            if (!worldKey.equals(localKey)) world.chunk.chunkTileEntityMap.put(worldKey, tile);
            check(!present(world, tile), "A missing local key or stale world-coordinate decoy is rejected without tile creation");
            world.chunk.chunkTileEntityMap.put(localKey, tile);
            world.loaded = false; int reads = world.chunkReads;
            check(!present(world, tile) && world.chunkReads == reads,
                    "An unloaded chunk is rejected before any chunk retrieval even with a fixture map entry");
        }
    }

    private static boolean present(World world, TileEntity tile) {
        try {
            Method method = BasicMachineFluidAdapter.class.getDeclaredMethod("present", World.class, TileEntity.class);
            method.setAccessible(true); return (Boolean) method.invoke(null, world, tile);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Actual loaded owner gateway invocation failed", error); }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
        } catch (ReflectiveOperationException error) { throw new AssertionError("Headless loaded-world fixture initialization failed", error); }
    }

    private static final class LoadedWorld extends World {
        IChunkProvider publicProvider; LoadedChunk chunk; int expectedChunkX, expectedChunkZ, chunkReads; boolean loaded;
        private LoadedWorld() { super(null, "named_tank_fixture", (WorldSettings) null, null, null); }
        @Override public IChunkProvider getChunkProvider() { return publicProvider; }
        @Override public Chunk getChunkFromChunkCoords(int x, int z) {
            check(loaded && x == expectedChunkX && z == expectedChunkZ,
                    "Only the already-loaded actual chunk may be retrieved"); chunkReads++; return chunk;
        }
        @Override protected IChunkProvider createChunkProvider() { throw new AssertionError("Fixture cannot create a chunk provider"); }
        @Override protected int getRenderDistanceChunks() { throw new AssertionError("Gateway cannot request rendering"); }
        @Override public Entity getEntityByID(int id) { throw new AssertionError("Gateway cannot inspect entities"); }
    }

    private static final class LoadedChunk extends Chunk {
        LoadedChunk(World world, int x, int z) { super(world, x, z); }
        @Override public void setBlockTileEntityInChunk(int x, int y, int z, TileEntity tile) {
            chunkTileEntityMap.put(new ChunkPosition(x, y, z), tile);
        }
    }

    private static void snapshots() {
        MTEBasicMachine machine = machine(); JsonObject owner = owner(machine);
        JsonObject data = BasicMachineFluidAdapter.snapshot(machine, owner); JsonArray tanks = data.getAsJsonArray("tanks");
        check(data.get("available").getAsBoolean() && data.get("schemaVersion").getAsInt() == 1
                && data.get("source").getAsString().equals("integrated_server") && data.get("binding").getAsString().equals(BasicMachineFluidAdapter.BINDING),
                "Actual server projection has an explicit version, source and fixed public identity binding");
        check(tanks.size() == 2 && data.get("totalTanks").getAsInt() == 2 && !data.get("truncated").getAsBoolean(),
                "A successful named snapshot contains both complete roles without truncation");
        check(tanks.get(0).getAsJsonObject().get("role").getAsString().equals("input")
                && tanks.get(0).getAsJsonObject().get("publicGetter").getAsString().equals("getFluidTank")
                && tanks.get(1).getAsJsonObject().get("role").getAsString().equals("output")
                && tanks.get(1).getAsJsonObject().get("publicGetter").getAsString().equals("getFluidOutputTank"),
                "Roles come from the actual named getters, with no guessed tank indices");
        check(!tanks.get(0).getAsJsonObject().has("index") && tanks.get(0).getAsJsonObject().get("capacity").getAsInt() == 32000
                && tanks.get(1).getAsJsonObject().get("capacity").getAsInt() == 64000
                && tanks.get(0).getAsJsonObject().get("empty").getAsBoolean() && tanks.get(0).getAsJsonObject().get("fluid").isJsonNull(),
                "Empty tanks retain exact distinct capacities and explicit null fluid");
        JsonObject actualOwner = data.getAsJsonObject("owner");
        check(actualOwner.get("registryName").getAsString().equals("gregtech:gt.blockmachines")
                && actualOwner.get("x").getAsInt() == -260 && actualOwner.get("y").getAsInt() == 119
                && actualOwner.get("z").getAsInt() == -120 && actualOwner.get("dimension").getAsInt() == 180
                && actualOwner.get("metaTileId").getAsInt() == 810 && actualOwner.get("metaClass").getAsString().equals(machine.getClass().getName()),
                "Full observed owner identity survives the bounded projection");
        owner.addProperty("x", 999);
        check(actualOwner.get("x").getAsInt() == -260, "Published owner is an independent JSON snapshot");
        GuardedTank input = (GuardedTank) machine.inputTank, output = (GuardedTank) machine.outputTank;
        input.fluid = fluid(3000); input.fluid.tag = new NBTTagCompound(); output.fluid = fluid(144);
        data = BasicMachineFluidAdapter.snapshot(machine, owner(machine)); tanks = data.getAsJsonArray("tanks");
        check(!tanks.get(0).getAsJsonObject().get("empty").getAsBoolean()
                && tanks.get(0).getAsJsonObject().getAsJsonObject("fluid").get("fluid").getAsString().equals(NAME)
                && tanks.get(0).getAsJsonObject().getAsJsonObject("fluid").get("amount").getAsInt() == 3000
                && tanks.get(0).getAsJsonObject().getAsJsonObject("fluid").get("hasTag").getAsBoolean(),
                "Fluid runtime identity, exact amount and fluid tag flag are observed separately");
        check(tanks.get(1).getAsJsonObject().getAsJsonObject("fluid").get("amount").getAsInt() == 144
                && !tanks.get(1).getAsJsonObject().getAsJsonObject("fluid").get("hasTag").getAsBoolean(),
                "A partial output amount remains exact without inventing container or tank capacity");
        input.fluid.amount = 1; output.fluid = null;
        check(tanks.get(0).getAsJsonObject().getAsJsonObject("fluid").get("amount").getAsInt() == 3000
                && tanks.get(1).getAsJsonObject().getAsJsonObject("fluid").get("amount").getAsInt() == 144,
                "Returned scalar snapshots do not alias later live fluid changes");
        input.fluid = fluid(Integer.MAX_VALUE); input.capacity = Integer.MAX_VALUE;
        check(BasicMachineFluidAdapter.snapshot(machine, owner(machine)).getAsJsonArray("tanks").get(0).getAsJsonObject()
                .getAsJsonObject("fluid").get("amount").getAsInt() == Integer.MAX_VALUE, "Public integer capacity/amount boundaries remain exact");
    }

    private static void failures() {
        MTEBasicMachine machine = machine();
        negative(BasicMachineFluidAdapter.snapshot(machine, null), "public_basic_machine_owner_unverified");
        negative(BasicMachineFluidAdapter.snapshot(new Lookalike(), owner(machine)), "public_basic_machine_api_unavailable");
        machine.outputTank = machine.inputTank;
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tanks_aliased");
        machine = machine(); machine.outputTank = null;
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_api_unavailable");
        machine = new ChangingMachine();
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_identity_changed");
        machine = new ThrowingMachine();
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_basic_machine_named_tank_read_failed");
        machine = machine(); GuardedTank input = (GuardedTank) machine.inputTank;
        input.capacity = -1;
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_capacity_invalid");
        input.capacity = 0;
        check(BasicMachineFluidAdapter.snapshot(machine, owner(machine)).get("available").getAsBoolean(), "A zero-capacity empty public tank is faithfully represented");
        input.capacity = 32000; input.fluid = fluid(0);
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_amount_invalid");
        input.fluid = fluid(-1);
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_amount_invalid");
        input.fluid = fluid(32001);
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_amount_invalid");
        input.fluid = ExpandedGtSelfTest.fixtureFluid(new Fluid("bridge_named_tank_fixture.unregistered"), 1000);
        negative(BasicMachineFluidAdapter.snapshot(machine, owner(machine)), "public_named_tank_fluid_identity_invalid");
    }

    private static void bindings() {
        MTEBasicMachine machine = machine(); JsonObject owner = owner(machine);
        JsonObject data = BasicMachineFluidAdapter.bind(machine, owner, machine.inputTank);
        check(data.get("available").getAsBoolean() && data.get("role").getAsString().equals("input")
                && data.get("publicGetter").getAsString().equals("getFluidTank") && data.get("source").getAsString().equals("client_display")
                && !data.get("serverAcceptance").getAsBoolean() && data.getAsJsonObject("owner").equals(owner),
                "GUI binding records actual input tank identity and separates client display from server acceptance");
        data = BasicMachineFluidAdapter.bind(machine, owner, machine.outputTank);
        check(data.get("available").getAsBoolean() && data.get("role").getAsString().equals("output")
                && data.get("publicGetter").getAsString().equals("getFluidOutputTank") && data.get("totalNamedTanks").getAsInt() == 2
                && !data.get("truncated").getAsBoolean(), "Actual output getter identity binds independently without a tank index");
        negative(BasicMachineFluidAdapter.bind(machine, owner, new GuardedTank(32000)), "actual_named_machine_tank_identity_mismatch");
        negative(BasicMachineFluidAdapter.bind(machine, null, machine.inputTank), "public_basic_machine_owner_unverified");
        negative(BasicMachineFluidAdapter.bind(machine, owner, new Object()), "public_widget_tank_unavailable");
        machine.outputTank = machine.inputTank;
        negative(BasicMachineFluidAdapter.bind(machine, owner, machine.inputTank), "public_named_tanks_aliased");
        ChangingMachine changing = new ChangingMachine();
        negative(BasicMachineFluidAdapter.bind(changing, owner(changing), changing.first), "public_named_tank_identity_changed");
    }

    private static void gui() {
        MTEBasicMachine machine = machine(); MachineSlotBinding.Context owner = ownerContext(machine);
        FluidSlot slot = new FluidSlot(); slot.handler.tank = machine.inputTank;
        JsonObject data = FluidGuiAdapter.read(slot, owner);
        check(data.getAsJsonObject("machineTankBinding").get("available").getAsBoolean()
                && data.getAsJsonObject("machineTankBinding").get("role").getAsString().equals("input"),
                "The public FluidSlot actual tank is bound through the already-proved native GUI owner");
        slot.handler.tank = machine.outputTank; slot.handler.fill = false;
        data = FluidGuiAdapter.read(slot, owner);
        check(data.getAsJsonObject("machineTankBinding").get("role").getAsString().equals("output")
                && !data.getAsJsonObject("canFill").get("value").getAsBoolean() && data.getAsJsonObject("canDrain").get("value").getAsBoolean(),
                "Named output binding leaves native public fill/drain permissions intact");
        slot.handler.tank = new GuardedTank(64000);
        negative(FluidGuiAdapter.read(slot, owner).getAsJsonObject("machineTankBinding"), "actual_named_machine_tank_identity_mismatch");
        ChangingWidget changingWidget = new ChangingWidget(machine.inputTank);
        negative(FluidGuiAdapter.read(changingWidget, owner).getAsJsonObject("machineTankBinding"), "public_widget_tank_identity_changed");
        negative(FluidGuiAdapter.read(slot).getAsJsonObject("machineTankBinding"), "public_machine_owner_api_unavailable");
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen(); slot.handler.tank = machine.outputTank; screen.root.children.add(slot);
        JsonObject args = new JsonObject(); args.addProperty("screenId", "named_tank_fixture"); args.addProperty("limit", 128);
        JsonArray widgets = GuiControlAdapter.read(screen, args, Collections.emptyList(), System.nanoTime(), null, owner).getAsJsonArray("widgets");
        JsonObject widget = widgets.get(1).getAsJsonObject();
        check(widget.get("kind").getAsString().equals("fluid_slot")
                && widget.getAsJsonObject("fluidSlot").getAsJsonObject("machineTankBinding").get("role").getAsString().equals("output")
                && widget.getAsJsonArray("supportedActions").size() == 1 && slot.presses == 0 && slot.releases == 0,
                "Native widget inspection publishes the actual named binding without sending clicks or fluid mutations");
        machine.outputTank = null;
        negative(FluidGuiAdapter.read(slot, owner).getAsJsonObject("machineTankBinding"), "public_named_tank_api_unavailable");
        GuiControlAdapter.invalidate();
    }

    private static void hooks() {
        LegacyHandler handler = new LegacyHandler(); JsonObject data = JsonData.fluids(handler);
        check(data.getAsJsonArray("tanks").size() == 1 && data.getAsJsonArray("tanks").get(0).getAsJsonObject().get("index").getAsInt() == 0
                && data.getAsJsonArray("tanks").get(0).getAsJsonObject().get("capacity").getAsInt() == 7000 && handler.reads == 1,
                "Existing generic getTankInfo projection remains available without extra reads or mutations");
        negative(data.getAsJsonObject("namedTanks"), "public_basic_machine_owner_api_unavailable");
        MTEBasicMachine machine = machine(); ((GuardedTank) machine.outputTank).fluid = fluid(144);
        FluidSlot slot = new FluidSlot(); slot.handler.tank = machine.outputTank; slot.handler.fluid = ((GuardedTank) machine.outputTank).fluid; slot.handler.fill = false;
        JsonObject fixture = new JsonObject(); fixture.addProperty("evidenceType", "mock_public_api_fixture_not_live_evidence");
        fixture.add("namedTanks", BasicMachineFluidAdapter.snapshot(machine, owner(machine)));
        fixture.add("fluidSlot", FluidGuiAdapter.read(slot, ownerContext(machine)));
        System.out.println("NAMED_FLUID_FIXTURE_JSON " + fixture);
    }

    private static MTEBasicMachine machine() {
        MTEBasicMachine machine = new MTEBasicMachine(8, 10); machine.inputTank = new GuardedTank(32000); machine.outputTank = new GuardedTank(64000); return machine;
    }
    private static FluidStack fluid(int amount) { return new FluidStack(FluidRegistry.getFluid(NAME), amount); }
    private static JsonObject owner(Object meta) {
        JsonObject owner = new JsonObject(); owner.addProperty("x", -260); owner.addProperty("y", 119); owner.addProperty("z", -120);
        owner.addProperty("dimension", 180); owner.addProperty("registryName", "gregtech:gt.blockmachines"); owner.addProperty("metaTileId", 810);
        owner.addProperty("metaClass", meta.getClass().getName()); return owner;
    }
    private static MachineSlotBinding.Context ownerContext(MTEBasicMachine machine) {
        return new MachineSlotBinding.Context(new ModularContainer(), machine, new Object(), owner(machine), 20);
    }
    private static void negative(JsonObject data, String reason) {
        check(!data.get("available").getAsBoolean() && data.get("reason").getAsString().equals(reason)
                && !data.has("owner") && !data.has("tanks") && !data.has("role"), "Unavailable " + reason + " cannot publish partial owner/role/tank guesses");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static class GuardedTank implements IFluidTank {
        public int capacity; public FluidStack fluid;
        public GuardedTank(int capacity) { this.capacity = capacity; }
        public FluidStack getFluid() { return fluid; }
        public int getFluidAmount() { throw new AssertionError("Use the actual public stack amount"); }
        public int getCapacity() { return capacity; }
        public FluidTankInfo getInfo() { throw new AssertionError("Named roles cannot use positional info projections"); }
        public int fill(FluidStack value, boolean execute) { throw new AssertionError("Tank fill forbidden in observation"); }
        public FluidStack drain(int amount, boolean execute) { throw new AssertionError("Tank drain forbidden in observation"); }
    }
    public static class Lookalike {
        public IFluidTank getFluidTank() { throw new AssertionError("Unknown classes must not be probed by lookalike method names"); }
        public IFluidTank getFluidOutputTank() { throw new AssertionError("Unknown classes must not be probed by lookalike method names"); }
    }
    public static class ChangingMachine extends MTEBasicMachine {
        final IFluidTank first = new GuardedTank(32000), second = new GuardedTank(32000); int reads;
        public ChangingMachine() { super(8, 10); outputTank = new GuardedTank(64000); }
        @Override public IFluidTank getFluidTank() { return reads++ == 0 ? first : second; }
    }
    public static class ThrowingMachine extends MTEBasicMachine {
        public ThrowingMachine() { super(8, 10); }
        @Override public IFluidTank getFluidOutputTank() { throw new IllegalStateException("Missing public output API"); }
    }
    public static class ChangingWidget extends FluidSlot {
        final IFluidTank first, second = new GuardedTank(32000); int reads;
        public ChangingWidget(IFluidTank first) { this.first = first; }
        @Override public IFluidTank getFluidTank() { return reads++ == 0 ? first : second; }
    }
    private static class LegacyHandler implements IFluidHandler {
        int reads;
        public FluidTankInfo[] getTankInfo(ForgeDirection side) { reads++; return new FluidTankInfo[] {new FluidTankInfo(null, 7000)}; }
        public int fill(ForgeDirection side, FluidStack value, boolean execute) { throw new AssertionError("Fill forbidden"); }
        public FluidStack drain(ForgeDirection side, FluidStack value, boolean execute) { throw new AssertionError("Drain forbidden"); }
        public FluidStack drain(ForgeDirection side, int amount, boolean execute) { throw new AssertionError("Drain forbidden"); }
        public boolean canFill(ForgeDirection side, Fluid fluid) { throw new AssertionError("Gate probing unnecessary"); }
        public boolean canDrain(ForgeDirection side, Fluid fluid) { throw new AssertionError("Gate probing unnecessary"); }
    }
}

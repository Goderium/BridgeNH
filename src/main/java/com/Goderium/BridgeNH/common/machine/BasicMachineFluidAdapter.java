package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.fluids.IFluidTank;

/** Bounded read-only named public BasicMachine tanks; no positional tank-role inference or tank mutation. */
public final class BasicMachineFluidAdapter {
    public static final String BINDING = "public_basic_machine_named_tank_identity";
    private static final String BASE = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String BASIC = "gregtech.api.metatileentity.implementations.MTEBasicMachine";
    private static final String INPUT = "getFluidTank", OUTPUT = "getFluidOutputTank";

    public static JsonObject read(IFluidHandler handler) {
        if (!(handler instanceof TileEntity) || !ExpandedGuiApi.is(handler, BASE))
            return unavailable("integrated_server", "public_basic_machine_owner_api_unavailable");
        TileEntity tile = (TileEntity) handler;
        try {
            World world = tile.getWorld();
            if (world == null || world.isRemote || world.provider == null || tile.isInvalid() || !present(world, tile))
                return unavailable("integrated_server", "integrated_server_owner_unverified");
            Object meta = AdapterAccess.call(tile, "getMetaTileEntity");
            if (meta == null || AdapterAccess.call(meta, "getBaseMetaTileEntity") != tile)
                return unavailable("integrated_server", "public_basic_machine_owner_meta_unverified");
            Object rawId = AdapterAccess.call(tile, "getMetaTileID");
            if (!(rawId instanceof Integer) || (Integer) rawId < 0 || (Integer) rawId > 32767)
                return unavailable("integrated_server", "public_basic_machine_owner_id_unavailable");
            Block block = world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord);
            Object registry = Block.blockRegistry.getNameForObject(block);
            if (!(registry instanceof String) || ((String) registry).isEmpty() || ((String) registry).length() > 256)
                return unavailable("integrated_server", "public_basic_machine_owner_registry_unavailable");
            JsonObject owner = new JsonObject();
            owner.addProperty("x", tile.xCoord); owner.addProperty("y", tile.yCoord); owner.addProperty("z", tile.zCoord);
            owner.addProperty("dimension", world.provider.dimensionId); owner.addProperty("registryName", (String) registry);
            owner.addProperty("metaTileId", (Integer) rawId); owner.addProperty("metaClass", meta.getClass().getName());
            JsonObject result = snapshot(meta, owner);
            if (!result.get("available").getAsBoolean()) return result;
            if (tile.getWorld() != world || tile.isInvalid() || !present(world, tile)
                    || tile.xCoord != owner.get("x").getAsInt() || tile.yCoord != owner.get("y").getAsInt()
                    || tile.zCoord != owner.get("z").getAsInt() || world.provider.dimensionId != owner.get("dimension").getAsInt()
                    || world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord) != block
                    || AdapterAccess.call(tile, "getMetaTileEntity") != meta
                    || AdapterAccess.call(meta, "getBaseMetaTileEntity") != tile
                    || !rawId.equals(AdapterAccess.call(tile, "getMetaTileID")))
                return unavailable("integrated_server", "public_basic_machine_owner_changed");
            return result;
        } catch (RuntimeException | LinkageError error) {
            return unavailable("integrated_server", "public_basic_machine_server_read_failed");
        }
    }

    private static boolean present(World world, TileEntity tile) {
        return world.getChunkProvider().chunkExists(tile.xCoord >> 4, tile.zCoord >> 4)
                && world.getChunkFromChunkCoords(tile.xCoord >> 4, tile.zCoord >> 4).chunkTileEntityMap
                    .get(new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15)) == tile;
    }

    /** Internal call after actual server owner proof; tests supply a pre-proved owner fixture. */
    static JsonObject snapshot(Object meta, JsonObject owner) {
        String source = "integrated_server";
        if (owner == null) return unavailable(source, "public_basic_machine_owner_unverified");
        try {
            Pair pair = Pair.read(meta);
            JsonArray tanks = new JsonArray();
            tanks.add(tank(pair.input, "input", INPUT)); tanks.add(tank(pair.output, "output", OUTPUT));
            pair.recheck(meta);
            JsonObject result = base(source); result.addProperty("available", true);
            result.addProperty("scope", "complete_actual_owner_named_tanks_public_api_read");
            result.addProperty("binding", BINDING); result.add("owner", copy(owner)); result.add("tanks", tanks);
            result.addProperty("totalTanks", 2); result.addProperty("truncated", false);
            result.addProperty("publicApi", BASIC + ".getFluidTank/getFluidOutputTank");
            return result;
        } catch (TankFailure error) { return unavailable(source, error.reason); }
        catch (RuntimeException | LinkageError error) { return unavailable(source, "public_basic_machine_named_tank_read_failed"); }
    }

    /** Client display object identity only; server tank state remains a separate read. */
    static JsonObject bind(Object meta, JsonObject owner, Object actualTank) {
        String source = "client_display";
        if (meta == null || owner == null) return unavailable(source, "public_basic_machine_owner_unverified");
        if (!(actualTank instanceof IFluidTank)) return unavailable(source, "public_widget_tank_unavailable");
        try {
            Pair pair = Pair.read(meta);
            String role, getter;
            if (actualTank == pair.input) { role = "input"; getter = INPUT; }
            else if (actualTank == pair.output) { role = "output"; getter = OUTPUT; }
            else return unavailable(source, "actual_named_machine_tank_identity_mismatch");
            pair.recheck(meta);
            JsonObject result = base(source); result.addProperty("available", true);
            result.addProperty("scope", "current_native_widget_actual_named_machine_tank_identity");
            result.addProperty("binding", BINDING); result.add("owner", copy(owner)); result.addProperty("role", role);
            result.addProperty("publicGetter", getter); result.addProperty("widgetPublicGetter", "getFluidTank");
            result.addProperty("totalNamedTanks", 2); result.addProperty("truncated", false);
            result.addProperty("serverAcceptance", false);
            return result;
        } catch (TankFailure error) { return unavailable(source, error.reason); }
        catch (RuntimeException | LinkageError error) { return unavailable(source, "public_basic_machine_named_tank_binding_failed"); }
    }

    private static JsonObject tank(IFluidTank tank, String role, String getter) {
        int capacity = tank.getCapacity();
        if (capacity < 0) throw new TankFailure("public_named_tank_capacity_invalid");
        FluidStack stack = tank.getFluid();
        JsonObject result = new JsonObject(); result.addProperty("role", role); result.addProperty("publicGetter", getter);
        result.addProperty("capacity", capacity); result.addProperty("empty", stack == null);
        if (stack == null) result.add("fluid", JsonNull.INSTANCE);
        else {
            Fluid fluid = stack.getFluid(); String name = fluid == null ? null : FluidRegistry.getFluidName(fluid);
            if (name == null || name.isEmpty() || name.length() > 128 || FluidRegistry.getFluid(name) != fluid)
                throw new TankFailure("public_named_tank_fluid_identity_invalid");
            if (stack.amount <= 0 || stack.amount > capacity) throw new TankFailure("public_named_tank_amount_invalid");
            JsonObject state = new JsonObject(); state.addProperty("fluid", name); state.addProperty("amount", stack.amount);
            state.addProperty("hasTag", stack.tag != null); result.add("fluid", state);
        }
        return result;
    }

    private static JsonObject base(String source) {
        JsonObject result = new JsonObject(); result.addProperty("schemaVersion", 1); result.addProperty("source", source); return result;
    }
    public static JsonObject unavailable(String source, String reason) {
        JsonObject result = base(source); result.addProperty("available", false); result.addProperty("reason", reason); return result;
    }
    private static JsonObject copy(JsonObject object) { return new JsonParser().parse(object.toString()).getAsJsonObject(); }

    private static final class Pair {
        final IFluidTank input, output;
        Pair(IFluidTank input, IFluidTank output) { this.input = input; this.output = output; }
        static Pair read(Object meta) {
            if (!ExpandedGuiApi.is(meta, BASIC)) throw new TankFailure("public_basic_machine_api_unavailable");
            Object input = AdapterAccess.call(meta, INPUT), output = AdapterAccess.call(meta, OUTPUT);
            if (!(input instanceof IFluidTank) || !(output instanceof IFluidTank))
                throw new TankFailure("public_named_tank_api_unavailable");
            if (input == output) throw new TankFailure("public_named_tanks_aliased");
            return new Pair((IFluidTank) input, (IFluidTank) output);
        }
        void recheck(Object meta) {
            if (AdapterAccess.call(meta, INPUT) != input || AdapterAccess.call(meta, OUTPUT) != output)
                throw new TankFailure("public_named_tank_identity_changed");
        }
    }
    private static final class TankFailure extends RuntimeException {
        final String reason;
        TankFailure(String reason) { this.reason = reason; }
    }
}

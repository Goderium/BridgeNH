package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.util.BoundedNbt;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.function.Function;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/** On-demand public fluid getters on independent bounded copies of one observed stack. */
public final class FluidContainerDataAdapter {
    private static final String ITEM_API = "public_container_item_api", REGISTRY_API = "public_fluid_container_registry";

    public static JsonObject read(ItemStack stack) {
        return read(stack, null);
    }

    /** Requested capacity is a separate registration query, never the observed container contents. */
    public static JsonObject read(ItemStack stack, String containerFluid) {
        if (containerFluid != null && (containerFluid.isEmpty() || containerFluid.length() > 128))
            throw new BridgeException("invalid_args", "containerFluid must contain 1 to 128 characters.");
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("source", "public_fluid_container_api");
        result.addProperty("scope", "observed_stack_copy_public_api_read");
        result.addProperty("itemHasTag", stack != null && stack.hasTagCompound());
        result.addProperty("probeMutatedCopy", false);
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return unavailable(result, "item_stack_unavailable");
        try {
            Snapshot snapshot = new Snapshot(stack, result);
            if (snapshot.tagTruncated) return unavailable(result, "container_nbt_snapshot_truncated");
            if (snapshot.item instanceof IFluidContainerItem) {
                result.addProperty("source", ITEM_API);
                IFluidContainerItem api = (IFluidContainerItem) snapshot.item;
                FluidStack fluid = snapshot.probe(api::getFluid);
                int capacity = snapshot.probe(api::getCapacity);
                if (capacity < 0) return unavailable(result, "container_capacity_negative");
                String error = fluidError(fluid);
                if (error != null) return unavailable(result, error);
                if (capacity > 0) return available(result, fluid, capacity);
                // GT MetaBaseItem implements the interface even for registered fixed cells and noncontainers.
                return registry(result, snapshot, fluid, containerFluid);
            }
            return registry(result, snapshot, null, containerFluid);
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("errorType", JsonData.bounded(error.getClass().getName(), 256));
            return unavailable(result, "public_fluid_container_read_failed");
        }
    }

    private static JsonObject registry(JsonObject result, Snapshot snapshot, FluidStack interfaceFluid, String containerFluid) {
        result.addProperty("source", REGISTRY_API);
        FluidStack fluid = snapshot.probe(FluidContainerRegistry::getFluidForFilledItem);
        boolean empty = snapshot.probe(FluidContainerRegistry::isEmptyContainer);
        if (fluid != null && empty) return unavailable(result, "registered_container_state_ambiguous");
        String error = fluidError(fluid);
        if (error != null) return unavailable(result, error);
        if (interfaceFluid != null && (fluid == null || interfaceFluid.getFluid() != fluid.getFluid()
                || interfaceFluid.amount != fluid.amount || !sameTag(interfaceFluid.tag, fluid.tag)))
            return unavailable(result, "container_interface_registry_disagree");
        if (fluid != null) {
            int capacity = snapshot.probe(FluidContainerRegistry::getContainerCapacity);
            if (capacity <= 0) return unavailable(result, "registered_filled_capacity_unavailable");
            return available(result, fluid, capacity);
        }
        if (!empty) return unavailable(result, "not_a_public_fluid_container");
        result.addProperty("available", true);
        result.addProperty("empty", true);
        result.add("fluid", JsonNull.INSTANCE);
        result.add("capacity", containerFluid == null ? missingCapacity("registered_empty_capacity_requires_fluid_identity")
                : requestedCapacity(snapshot, containerFluid));
        return result;
    }

    private static JsonObject requestedCapacity(Snapshot snapshot, String name) {
        JsonObject result = missingCapacity("requested_container_fluid_unknown");
        try {
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid == null || !name.equals(FluidRegistry.getFluidName(fluid))) return result;
            result.addProperty("source", "public_fluid_container_registry_getContainerCapacity");
            result.addProperty("fluidName", name);
            int capacity = snapshot.probe(copy -> FluidContainerRegistry.getContainerCapacity(new FluidStack(fluid, 1), copy));
            if (capacity <= 0) {
                result.addProperty("reason", "registered_empty_capacity_unavailable_for_fluid");
                return result;
            }
            result.remove("reason");
            result.addProperty("available", true);
            result.addProperty("value", capacity);
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("reason", "registered_empty_capacity_read_failed");
            result.addProperty("errorType", JsonData.bounded(error.getClass().getName(), 256));
        }
        return result;
    }

    private static JsonObject available(JsonObject result, FluidStack fluid, int capacity) {
        if (fluid != null && fluid.amount > capacity) return unavailable(result, "contained_amount_exceeds_capacity");
        result.addProperty("available", true);
        result.addProperty("empty", fluid == null);
        JsonObject capacityData = new JsonObject();
        capacityData.addProperty("available", true);
        capacityData.addProperty("value", capacity);
        result.add("capacity", capacityData);
        if (fluid == null) result.add("fluid", JsonNull.INSTANCE);
        else {
            JsonObject fluidData = new JsonObject();
            fluidData.addProperty("fluidName", FluidRegistry.getFluidName(fluid.getFluid()));
            fluidData.addProperty("amount", fluid.amount);
            fluidData.addProperty("hasTag", fluid.tag != null);
            result.add("fluid", fluidData);
        }
        return result;
    }

    private static String fluidError(FluidStack stack) {
        if (stack == null) return null;
        Fluid fluid = stack.getFluid();
        String name = fluid == null ? null : FluidRegistry.getFluidName(fluid);
        if (name == null || name.isEmpty() || name.length() > 128 || FluidRegistry.getFluid(name) != fluid)
            return "contained_fluid_identity_unavailable";
        if (stack.amount <= 0) return "contained_fluid_amount_not_positive";
        if (stack.tag != null && BoundedNbt.snapshot(stack.tag).truncated) return "contained_fluid_nbt_snapshot_truncated";
        return null;
    }

    private static boolean sameTag(NBTTagCompound a, NBTTagCompound b) {
        return a == null ? b == null : b != null && BoundedNbt.snapshot(a).tag.equals(BoundedNbt.snapshot(b).tag);
    }

    private static JsonObject unavailable(JsonObject result, String reason) {
        result.remove("empty");
        result.remove("fluid");
        result.addProperty("available", false);
        result.addProperty("reason", reason);
        result.add("capacity", missingCapacity(reason));
        return result;
    }

    private static JsonObject missingCapacity(String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("available", false);
        result.addProperty("reason", reason);
        return result;
    }

    private static final class Snapshot {
        final Item item;
        final int metadata, count;
        final NBTTagCompound tag;
        final boolean tagTruncated;
        final JsonObject result;
        Snapshot(ItemStack original, JsonObject result) {
            item = original.getItem(); metadata = original.getMetadata(); count = original.stackSize;
            BoundedNbt.Snapshot copied = BoundedNbt.snapshot(original.stackTagCompound);
            tag = original.hasTagCompound() ? copied.tag : null;
            tagTruncated = copied.truncated;
            this.result = result;
        }
        <T> T probe(Function<ItemStack, T> read) {
            ItemStack copy = new ItemStack(item, count, metadata);
            if (tag != null) copy.setTagCompound((NBTTagCompound) tag.copy());
            try { return read.apply(copy); }
            finally {
                if (changed(copy)) result.addProperty("probeMutatedCopy", true);
            }
        }
        boolean changed(ItemStack copy) {
            if (copy.getItem() != item || copy.stackSize != count || copy.getMetadata() != metadata
                    || copy.hasTagCompound() != (tag != null)) return true;
            if (tag == null) return false;
            BoundedNbt.Snapshot after = BoundedNbt.snapshot(copy.stackTagCompound);
            return after.truncated || !tag.equals(after.tag);
        }
    }
}

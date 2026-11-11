package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.machine.MachineDescriptionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Existing registered GT prototype only; reuses the bounded compiled-piece reader without creating a tile. */
final class PrototypeStructureAdapter {
    private static final String ITEM_MACHINES = "gregtech.common.blocks.ItemMachines";
    private static final String META = "gregtech.api.interfaces.metatileentity.IMetaTileEntity";

    static void parameters(JsonObject args) {
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : args.entrySet())
            if (!Arrays.asList("registryName", "metadata", "dimension", "piece", "variant", "offset", "limit", "query", "boundsOnly").contains(entry.getKey()))
                throw new BridgeException("invalid_args", "Registered prototypes have no placed coordinates, controller offsets or runtime captures: " + entry.getKey());
        String registry = CatalogData.text(args, "registryName", 256, null);
        if (!registry.matches("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+"))
            throw new BridgeException("invalid_args", "registryName must be one exact registered item identity without separators or whitespace.");
        JsonData.integer(args, "metadata", 0, 32766);
    }

    static JsonObject inspect(StructureAdapter structures, JsonObject args, long started) {
        parameters(args);
        String registry = JsonData.string(args, "registryName");
        Object registered = Item.itemRegistry.getObject(registry);
        if (!(registered instanceof Item) || !registry.equals(String.valueOf(Item.itemRegistry.getNameForObject(registered))))
            throw new BridgeException("not_found", "The exact registered controller item is unavailable.");
        Item item = (Item) registered;
        Class<?> api = null;
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass())
            if (ITEM_MACHINES.equals(type.getName())) { api = type; break; }
        if (api == null) throw new BridgeException("unsupported", "This registered item exposes no public GT prototype resolver.");
        ItemStack stack = new ItemStack(item, 1, JsonData.integer(args, "metadata", 0, 32766));
        Object prototype = invoke(api, null, "getMetaTileEntity", new Class<?>[] {ItemStack.class}, stack.copy());
        if (prototype == null) throw new BridgeException("not_found", "The actual stack has no existing registered GT prototype.");
        final Class<?> metaApi;
        try { metaApi = Class.forName(META, false, prototype.getClass().getClassLoader()); }
        catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "The public GT prototype identity API is unavailable."); }
        if (!metaApi.isInterface() || !Modifier.isPublic(metaApi.getModifiers()) || !metaApi.isInstance(prototype))
            throw new BridgeException("unsupported", "The resolver did not expose a public IMetaTileEntity.");
        Object form = invoke(metaApi, prototype, "getStackForm", new Class<?>[] {long.class}, 1L);
        if (!(form instanceof ItemStack) || ((ItemStack) form).getItem() != item
                || ((ItemStack) form).getMetadata() != stack.getMetadata() || ((ItemStack) form).hasTagCompound())
            throw new BridgeException("unsupported", "The public prototype stack does not confirm the exact untagged controller identity.");
        return read(structures, prototype, JsonData.item(stack).getAsJsonObject(), args, started);
    }

    static JsonObject read(StructureAdapter structures, Object prototype, JsonObject identity, JsonObject args, long started) {
        JsonObject result;
        try {
            Object definition = invoke(prototype.getClass(), prototype, "getStructureDefinition", new Class<?>[0]);
            result = structures.readPlan(null, null, prototype, definition, null, 0, 0, 0, args, started);
        } catch (BridgeException error) {
            if (!"unsupported".equals(error.code)) throw error;
            result = new JsonObject(); result.addProperty("mode", "prototype_structure_unavailable");
            result.addProperty("available", false); result.addProperty("reason", JsonData.bounded(error.getMessage(), 512));
        }
        result.add("controller", JsonNull.INSTANCE);
        result.add("registeredPrototype", identity);
        result.addProperty("prototypeScope", "registered_prototype_public_default_state; individual compiled pieces only, never active runtime pieces, their repetition or complete machine footprint");
        result.addProperty("coordinateSpace", "piece_origin"); result.addProperty("worldPositionsAvailable", false);
        result.addProperty("structureFormationVerified", false); result.addProperty("fullMachineBoundsAvailable", false);
        result.addProperty("prototypeSource", "public ItemMachines.getMetaTileEntity(actual untagged stack), confirmed by IMetaTileEntity.getStackForm(1L)");
        result.addProperty("runtimeClass", JsonData.bounded(prototype.getClass().getName(), 256));
        result.addProperty("nonAtomic", true);
        JsonObject descriptions = new JsonObject();
        if (!GuiDataAdapter.booleanArg(args, "boundsOnly", false)) MachineDescriptionAdapter.add(descriptions, prototype);
        else result.addProperty("descriptionCoverage", "not_requested_bounds_only");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : descriptions.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > StructureAdapter.BYTE_LIMIT - 4096) {
                JsonObject unavailable = new JsonObject(); unavailable.addProperty("available", false);
                unavailable.addProperty("reason", "result_byte_budget"); unavailable.addProperty("truncated", true);
                result.add(entry.getKey(), unavailable);
            }
        }
        result.add("budget", StructureAdapter.budget(started));
        return result;
    }

    private static Object invoke(Class<?> api, Object target, String method, Class<?>[] signature, Object... args) {
        try {
            Method getter = api.getMethod(method, signature);
            if (!Modifier.isPublic(getter.getDeclaringClass().getModifiers())) throw new NoSuchMethodException(method);
            return getter.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            throw new BridgeException("adapter_error", "Public prototype getter failed: " + cause.getClass().getSimpleName());
        } catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Public prototype API unavailable: " + method); }
    }
}

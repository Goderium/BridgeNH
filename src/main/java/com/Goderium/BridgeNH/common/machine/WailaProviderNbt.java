package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import cpw.mods.fml.relauncher.ReflectionHelper;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Complete isolated cached Waila payload. Rejects oversized data instead of giving providers a damaged preview. */
public final class WailaProviderNbt {
    static final int NODE_LIMIT = 16384, DEPTH_LIMIT = 32, BYTE_LIMIT = 1024 * 1024;
    private NBTTagCompound tag;
    private int nodes;
    private long bytes;
    private String reason = "matching_cached_nbt_unavailable";

    public static WailaProviderNbt snapshot(NBTTagCompound source) {
        WailaProviderNbt result = new WailaProviderNbt();
        if (source != null) {
            try {
                result.validate(source, 0);
                result.tag = (NBTTagCompound) source.copy();
                result.reason = "complete_isolated_cached_payload";
            } catch (Limit failure) { result.reason = failure.reason; }
            catch (ReflectiveOperationException | RuntimeException failure) { result.reason = "native_nbt_traversal_unavailable"; }
        }
        return result;
    }

    public NBTTagCompound copy() {
        if (tag == null) throw new BridgeException(reason.startsWith("provider_nbt_") ? "waila_nbt_limit" : "waila_nbt_unavailable", reason);
        return (NBTTagCompound) tag.copy();
    }

    public JsonObject describe() {
        JsonObject result = new JsonObject();
        result.addProperty("available", tag != null); result.addProperty("complete", tag != null);
        result.addProperty("reason", reason); result.addProperty("nodesInspected", nodes); result.addProperty("estimatedBytes", bytes);
        result.addProperty("nodeLimit", NODE_LIMIT); result.addProperty("depthLimit", DEPTH_LIMIT); result.addProperty("estimatedByteLimit", BYTE_LIMIT);
        result.addProperty("budgetScope", "complete provider input, independent of external NBT preview; not network packet size");
        return result;
    }

    private void account(long amount) {
        bytes += amount;
        if (bytes > BYTE_LIMIT) throw new Limit("provider_nbt_byte_limit");
    }

    private void validate(NBTBase value, int depth) throws ReflectiveOperationException {
        if (depth > DEPTH_LIMIT) throw new Limit("provider_nbt_depth_limit");
        if (++nodes > NODE_LIMIT) throw new Limit("provider_nbt_node_limit");
        if (value == null) throw new Limit("invalid_native_nbt");
        account(32);
        if (value instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) value;
            if (compound.getKeySet().size() > NODE_LIMIT - nodes) throw new Limit("provider_nbt_node_limit");
            for (Object name : compound.getKeySet()) {
                String key = (String) name; account(16L + 2L * key.length());
                validate(compound.getTag(key), depth + 1);
            }
        } else if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            if (list.tagCount() > NODE_LIMIT - nodes) throw new Limit("provider_nbt_node_limit");
            // 1.7.10 has no generic public list getter. Read this one fixed vanilla field,
            // including nested lists and byte arrays, without copying or serializing unchecked NBT.
            Field field = ReflectionHelper.findField(NBTTagList.class, "tagList", "field_74747_a");
            for (Object child : (List<?>) field.get(list)) validate((NBTBase) child, depth + 1);
        } else if (value instanceof NBTTagString) account(2L * ((NBTTagString) value).getString().length());
        else if (value instanceof NBTTagByteArray) account(((NBTTagByteArray) value).getByteArray().length);
        else if (value instanceof NBTTagIntArray) account(4L * ((NBTTagIntArray) value).getIntArray().length);
        else if (value.getId() < 1 || value.getId() > 6) throw new Limit("unsupported_native_nbt_type");
    }

    private static final class Limit extends RuntimeException {
        final String reason;
        Limit(String reason) { this.reason = reason; }
    }
}

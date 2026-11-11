package com.Goderium.BridgeNH.util;

import com.google.gson.JsonObject;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

/** Small external NBT preview only. Provider input has an independent complete-copy budget. */
public final class BoundedNbt {
    private BoundedNbt() { }
    static final int NODE_LIMIT = 256, ARRAY_LIMIT = 32, KEY_LIMIT = 64, DEPTH_LIMIT = 6, STRING_LIMIT = 512;
    public static final class Snapshot { public NBTTagCompound tag; public boolean truncated; }
    public static Snapshot snapshot(NBTTagCompound source) {
        Snapshot result = new Snapshot(); int[] nodes = {NODE_LIMIT};
        NBTBase tag = copy(source, 0, nodes, result); result.tag = tag instanceof NBTTagCompound ? (NBTTagCompound) tag : new NBTTagCompound(); return result;
    }
    public static JsonObject describe(NBTTagCompound tag) {
        Snapshot snapshot = snapshot(tag); JsonObject result = new JsonObject();
        String text = snapshot.tag.toString();
        result.addProperty("snbt", JsonData.bounded(text, 4096)); result.addProperty("truncated", snapshot.truncated || text.length() > 4096);
        result.addProperty("snbtCharacterLimit", 4096);
        result.addProperty("nodeLimit", NODE_LIMIT); result.addProperty("arrayLimit", ARRAY_LIMIT);
        result.addProperty("depthLimit", DEPTH_LIMIT); result.addProperty("stringLimit", STRING_LIMIT);
        return result;
    }
    private static NBTBase copy(NBTBase source, int depth, int[] nodes, Snapshot result) {
        if (source == null) return null;
        if (--nodes[0] < 0 || depth > DEPTH_LIMIT) { result.truncated = true; return null; }
        if (source instanceof NBTTagCompound) {
            NBTTagCompound original = (NBTTagCompound) source, copied = new NBTTagCompound(); int count = 0;
            for (Object value : original.getKeySet()) {
                if (count++ >= KEY_LIMIT || nodes[0] <= 0) { result.truncated = true; break; }
                String key = (String) value;
                if (key.length() > STRING_LIMIT) { result.truncated = true; continue; }
                NBTBase child = copy(original.getTag(key), depth + 1, nodes, result); if (child != null) copied.setTag(key, child);
            }
            return copied;
        }
        if (source instanceof NBTTagList) {
            NBTTagList original = (NBTTagList) source, copied = new NBTTagList(); int size = original.tagCount();
            if (size > ARRAY_LIMIT) result.truncated = true;
            for (int i = 0; i < Math.min(size, ARRAY_LIMIT) && nodes[0] > 0; i++) {
                NBTBase child = listValue(original, i, result);
                child = copy(child, depth + 1, nodes, result); if (child != null) copied.appendTag(child);
            }
            if (nodes[0] <= 0 && size > copied.tagCount()) result.truncated = true;
            return copied;
        }
        if (source instanceof NBTTagString) {
            String text = ((NBTTagString) source).getString(); if (text.length() > STRING_LIMIT) result.truncated = true;
            return new NBTTagString(JsonData.bounded(text, STRING_LIMIT));
        }
        if (source instanceof NBTTagByteArray) {
            byte[] array = ((NBTTagByteArray) source).getByteArray(); if (array.length > ARRAY_LIMIT) result.truncated = true;
            return new NBTTagByteArray(java.util.Arrays.copyOf(array, Math.min(array.length, ARRAY_LIMIT)));
        }
        if (source instanceof NBTTagIntArray) {
            int[] array = ((NBTTagIntArray) source).getIntArray(); if (array.length > ARRAY_LIMIT) result.truncated = true;
            return new NBTTagIntArray(java.util.Arrays.copyOf(array, Math.min(array.length, ARRAY_LIMIT)));
        }
        return source.copy();
    }
    private static NBTBase listValue(NBTTagList list, int index, Snapshot result) {
        switch (list.getTagType()) {
            case 10: return list.getCompoundTagAt(index);
            case 8: return new NBTTagString(list.getStringTagAt(index));
            case 11: return new NBTTagIntArray(list.getIntArrayAt(index));
            case 5: return new NBTTagFloat(list.getFloatAt(index));
            case 6: return new NBTTagDouble(list.getDoubleAt(index));
            case 1: case 2: case 3: case 4:
                String value = list.getStringTagAt(index).replaceAll("[bBsSlL]$", "");
                try {
                    long number = Long.parseLong(value);
                    if (list.getTagType() == 1) return new NBTTagByte((byte) number);
                    if (list.getTagType() == 2) return new NBTTagShort((short) number);
                    if (list.getTagType() == 3) return new NBTTagInt((int) number);
                    return new NBTTagLong(number);
                } catch (NumberFormatException error) { result.truncated = true; return null; }
            default: result.truncated = true; return null;
        }
    }
}

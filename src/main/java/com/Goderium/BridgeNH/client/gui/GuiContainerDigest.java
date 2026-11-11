package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.FluidStack;

/** Bounded canonical full-content digest, independent of compound insertion order and runtime item IDs. */
final class GuiContainerDigest {
    private final MessageDigest digest;
    private int bytes, nodes;
    GuiContainerDigest() {
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    void integer(int value) { raw(new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value}); }
    void text(String value) { byte[] encoded=value.getBytes(StandardCharsets.UTF_8); integer(encoded.length); raw(encoded); }
    private void raw(byte[] value) {
        bytes=Math.addExact(bytes,value.length);
        if (bytes>262144) throw new BridgeException("result_too_large","Complete GUI content digest exceeds 256 KiB; no partial proof returned.");
        digest.update(value);
    }
    void item(ItemStack value) {
        integer(value==null ? 0 : 1);
        if (value!=null) {
            Object name=Item.itemRegistry.getNameForObject(value.getItem());
            if (name==null) throw new BridgeException("unsupported","Unregistered item cannot certify remote container identity.");
            text(name.toString());
            integer(value.getMetadata()); integer(value.stackSize); nbt(value.getTagCompound(),0);
        }
    }
    void fluid(FluidStack value) {
        integer(value==null ? 0 : 1);
        if (value!=null) { text(value.getFluid().getName()); integer(value.amount); nbt(value.tag,0); }
    }
    private void nbt(NBTBase value,int depth) {
        if (++nodes>65536 || depth>64) throw new BridgeException("result_too_large","Complete GUI NBT exceeds the bounded tree policy.");
        integer(value==null ? -1 : value.getId());
        if (value==null) return;
        if (value instanceof NBTTagCompound) {
            NBTTagCompound compound=(NBTTagCompound)value;
            List<String> keys=new ArrayList<String>(); for (Object key:compound.getKeySet()) keys.add((String)key);
            Collections.sort(keys); integer(keys.size());
            for (String key:keys) { text(key); nbt(compound.getTag(key),depth+1); }
        } else if (value instanceof NBTTagList) {
            // Vanilla 1.7.10 has no generic list getter. Traverse an isolated copy with its public remove API.
            NBTTagList list=(NBTTagList)value.copy(); integer(list.getTagType()); integer(list.tagCount());
            for (int i=list.tagCount()-1;i>=0;i--) nbt(list.removeTag(i),depth+1);
        } else if (value instanceof NBTTagByteArray) {
            byte[] array=((NBTTagByteArray)value).getByteArray(); integer(array.length); raw(array);
        } else if (value instanceof NBTTagIntArray) {
            int[] array=((NBTTagIntArray)value).getIntArray(); integer(array.length); for (int child:array) integer(child);
        } else if (value instanceof NBTTagString) text(((NBTTagString)value).getString());
        else if (value.getId()>=1 && value.getId()<=6) text(value.toString());
        else throw new BridgeException("unsupported","Unknown native NBT type cannot certify complete synchronization.");
    }
    String finish() { StringBuilder value=new StringBuilder(); for (byte b:digest.digest()) value.append(String.format("%02x",b&255)); return value.toString(); }
}

package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Real vanilla Container pickup logic with public wrapped player slots, no network/client runtime. */
public final class NativeHotbarSwapSelfTest {
    static int checks;
    public static int run() {
        checks=0;
        Fixture f=new Fixture(); f.fill();
        check(NativeHotbarSwap.wrappedPlayerSlot(f.target,f.inventory), "public isHere recognizes a wrapper despite its dummy inventory");
        JsonObject sent=f.run();
        check(sent.get("localSwapObserved").getAsBoolean() && f.clicks==3 && f.inventory.getItemStack()==null,
                "full-inventory wrapped slots swap with exactly three real native pickups");
        check(f.inventory.mainInventory[14].getMetadata()==2 && f.inventory.mainInventory[5].getMetadata()==1,
                "actual player stacks exchange rather than only reporting native submission");
        f=new Fixture(); f.inventory.mainInventory[5]=f.item(2); sent=f.run();
        check(f.clicks==2 && sent.get("localSwapObserved").getAsBoolean() && f.inventory.mainInventory[5]==null,
                "empty target uses two pickups and clears the actual hotbar");
        f=new Fixture(); f.inventory.mainInventory[14]=f.item(1); sent=f.run();
        check(f.clicks==2 && sent.get("localSwapObserved").getAsBoolean() && f.inventory.mainInventory[14]==null,
                "empty hotbar uses two pickups");
        final Fixture same=new Fixture(); same.inventory.mainInventory[14]=same.item(1); same.inventory.mainInventory[5]=same.item(1);
        same.inventory.mainInventory[5].stackSize=2;
        expect("unsupported", () -> same.run()); check(same.clicks==0,"merge-prone same-type stacks are refused before dispatch");
        final Fixture cursor=new Fixture(); cursor.fill(); cursor.inventory.setItemStack(cursor.item(3));
        expect("invalid_state", () -> cursor.run()); check(cursor.clicks==0,"real held item is never discarded or overwritten");
        final Fixture blocked=new Fixture(); blocked.fill(); blocked.takeable=false;
        expect("invalid_state", () -> blocked.run()); check(blocked.clicks==0,"native extraction permissions are checked on both sides");
        f=new Fixture(); f.fill(); f.correctAfterFirst=true; sent=f.run();
        check(f.clicks==1 && !sent.get("localSwapObserved").getAsBoolean() && sent.get("stoppedReason").getAsString().equals("native_prediction_differed"),
                "a corrected native prediction stops the gesture without replay or rollback");
        f=new Fixture(); f.fill(); f.closeAfterFirst=true; sent=f.run();
        check(f.clicks==1 && !sent.get("localSwapObserved").getAsBoolean() && f.inventory.getItemStack()!=null,
                "changed context stops with the actual carried stack retained for recovery");
        return checks;
    }
    private static class Fixture implements NativeHotbarSwap.Input {
        final InventoryPlayer inventory=new InventoryPlayer(null);
        final net.minecraft.item.Item type=new StructuredInfoSelfTest.FakeItem();
        final EntityClientPlayerMP player=allocate();
        final NativeContainer container=new NativeContainer();
        final Slot target=wrapped(14), hotbar=wrapped(5);
        int clicks; boolean takeable=true, correctAfterFirst, closeAfterFirst;
        Fixture() { player.inventory=inventory; container.add(target); container.add(hotbar); }
        ItemStack item(int meta) { return new ItemStack(type,1,meta); }
        void fill() { for(int i=0;i<36;i++) inventory.mainInventory[i]=item(9); inventory.mainInventory[14]=item(1);inventory.mainInventory[5]=item(2); }
        Slot wrapped(int index) { return new Slot(new InventoryBasic("shadow",false,36),index,0,0) {
            @Override public boolean isHere(IInventory inv,int i) { return inv==Fixture.this.inventory && i==index; }
            @Override public ItemStack getStack() { return Fixture.this.inventory.getStackInSlot(index); }
            @Override public void putStack(ItemStack stack) { Fixture.this.inventory.setInventorySlotContents(index,stack); }
            @Override public ItemStack decrStackSize(int count) { return Fixture.this.inventory.decrStackSize(index,count); }
        }; }
        JsonObject run() { return NativeHotbarSwap.perform(container,target,5,inventory,this); }
        public boolean current() { return !(closeAfterFirst && clicks>0); }
        public boolean canTake(Slot slot) { return takeable; }
        public void pickup(int slot) { clicks++; container.slotClick(slot,0,0,player); if(correctAfterFirst && clicks==1) { inventory.setItemStack(null);inventory.mainInventory[14]=item(1); } }
    }
    private static class NativeContainer extends Container {
        void add(Slot slot) { addSlotToContainer(slot); }
        @Override public boolean canInteractWith(EntityPlayer player) { return true; }
    }
    private static EntityClientPlayerMP allocate() {
        try { Class<?> unsafe=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
            return (EntityClientPlayerMP)unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),EntityClientPlayerMP.class);
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void expect(String code,Runnable action) { try {action.run();throw new AssertionError(code);}catch(BridgeException error){check(error.code.equals(code),"refusal "+code);} }
    private static void check(boolean condition,String text) { checks++;if(!condition)throw new AssertionError(text); }
}

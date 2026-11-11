package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.Collections;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class AePhantomSlotsSelfTest {
    public static final class Slot extends appeng.client.gui.slots.VirtualMEPhantomSlot {
        int clicks; ItemStack received;
        Slot() { super(new CellWorkbenchFixtures.Inventory(1),0); }
        public void handleMouseClicked(ItemStack item,boolean extra,int button) {
            clicks++; received=item==null?null:item.copy();
            if(item!=null) { item.stackSize=99; if(item.hasTagCompound()) item.getTagCompound().setString("native","changed copy"); }
        }
    }
    public static int run() {
        int checks=0; appeng.client.gui.AEBaseGui screen=new appeng.client.gui.AEBaseGui(); Slot slot=new Slot(); screen.virtualSlots.add(slot);
        InventoryPlayer inv=new InventoryPlayer(null); Item item=new Item(); Item.itemRegistry.addObject(32461,"bridge_ae_phantom:item",item);
        ItemStack source=new ItemStack(item,7,3); NBTTagCompound tag=new NBTTagCompound(); tag.setString("identity","kept"); source.setTagCompound(tag);
        inv.mainInventory[4]=source; inv.setItemStack(source);
        JsonObject read=new JsonObject();read.addProperty("screenId","ae-phantom-test");read.addProperty("actionableOnly",true);
        JsonObject entry=GuiControlAdapter.read(screen,read,Collections.emptyList(),System.nanoTime()+60000000000L).getAsJsonArray("widgets").get(0).getAsJsonObject();
        check(entry.get("kind").getAsString().equals("ae_phantom_slot") && entry.getAsJsonArray("supportedActions").size()==2,"Current AE phantom exposes ordinary widget actions");checks++;
        JsonObject args=new JsonObject();args.addProperty("screenId","ae-phantom-test");args.addProperty("widgetId",entry.get("widgetId").getAsString());args.addProperty("action","ghost_drag");
        JsonObject selector=new JsonObject();selector.addProperty("playerSlot",4);args.add("source",selector);
        JsonObject receipt=GuiControlAdapter.perform(screen,args,Collections.emptyList(),inv,()->true);
        check(slot.clicks==1 && slot.received.stackSize==1 && slot.received.getTagCompound().equals(tag),"One native callback receives a full-NBT unit copy");checks++;
        check(source.stackSize==7 && source.getTagCompound().equals(tag) && !source.getTagCompound().hasKey("native") && inv.getItemStack()==source,"Native callback cannot mutate the original source or cursor through its template");checks++;
        check(receipt.get("submitted").getAsBoolean() && !receipt.get("serverConfirmed").getAsBoolean(),"Submission never claims server acceptance");checks++;
        slot.hidden=true;expect(()->AePhantomSlots.invoke(slot,screen,args,inv,()->true,true));checks++;
        slot.hidden=false;screen.virtualSlots.clear();expect(()->AePhantomSlots.invoke(slot,screen,args,inv,()->true,true));checks++;
        screen.virtualSlots.add(slot);expect(()->AePhantomSlots.invoke(slot,screen,args,inv,()->false,true));checks++;
        check(slot.clicks==1,"Hidden, removed and stale controls never dispatch");checks++;
        inv.setItemStack(null); JsonObject click=new JsonObject();click.addProperty("button",0);
        AePhantomSlots.invoke(slot,screen,click,inv,()->true,false);
        check(slot.clicks==2 && slot.received==null,"Empty real cursor uses native clear semantics");checks++;
        GuiControlAdapter.invalidate();return checks;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void expect(Runnable action){try{action.run();throw new AssertionError("Expected preflight refusal");}catch(BridgeException expected){}}
}

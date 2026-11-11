package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.client.nei.NeiGhostDragAdapter;
import com.Goderium.BridgeNH.common.inventory.GhostItemSource;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/** Current native AE phantom widgets, using the same callback as a normal click. */
public final class AePhantomSlots {
    private static final String BASE = "appeng.client.gui.AEBaseGui";
    private static final String SLOT = "appeng.client.gui.slots.VirtualMEPhantomSlot";
    public static boolean supports(Object value) { return ExpandedGuiApi.is(value,SLOT); }
    public static List<Object> discover(Object screen) {
        List<Object> result = new ArrayList<Object>();
        if (!ExpandedGuiApi.is(screen,BASE)) return result;
        Object raw = ExpandedGuiApi.field(screen,BASE,"virtualSlots");
        if (!(raw instanceof List<?>) || ((List<?>)raw).size()>128)
            throw new BridgeException("unsupported","Current AE virtual slots exceed the bounded public widget surface.");
        for (Object slot : (List<?>)raw) if (supports(slot)) result.add(slot);
        return result;
    }
    public static boolean available(Object slot,Object screen) {
        if (!supports(slot) || Boolean.TRUE.equals(ExpandedGuiApi.call(slot,"isHidden"))) return false;
        int matches=0; for (Object current : discover(screen)) if (current==slot) matches++;
        return matches==1;
    }
    public static JsonObject describe(Object slot,Object screen) {
        JsonObject r=new JsonObject(); boolean enabled=available(slot,screen);
        r.addProperty("kind","ae_phantom_slot"); r.addProperty("enabled",enabled); r.addProperty("valid",enabled);
        r.addProperty("configurationOnly",true); r.addProperty("nativeContainerSlot",false);
        r.addProperty("slotIndex",((Number)ExpandedGuiApi.call(slot,"getSlotIndex")).intValue());
        r.addProperty("storage",String.valueOf(ExpandedGuiApi.call(slot,"getStorageName")));
        Object stack=ExpandedGuiApi.call(slot,"getAEStack"); r.add("resource",stack==null?JsonNull.INSTANCE:AeDataAdapter.stack(stack));
        r.addProperty("verification","Synchronized client display; close/reopen or inspect the owning server configuration before claiming persistence. No stock is acquired or consumed.");
        return r;
    }
    public static JsonObject invoke(Object slot,Object screen,JsonObject args,InventoryPlayer inventory,BooleanSupplier current,boolean ghost) {
        if (!available(slot,screen)) throw new BridgeException("stale_widget","The native AE phantom slot is hidden or no longer bound to this screen.");
        if (inventory==null) throw new BridgeException("invalid_state","Actual player inventory is unavailable.");
        ItemStack before=inventory.getItemStack()==null?null:inventory.getItemStack().copy();
        ItemStack template=ghost?GhostItemSource.resolve(screen,args.getAsJsonObject("source"),inventory.mainInventory):before;
        int button=ghost?0:JsonData.integer(args,"button",0,1);
        GuiControlAdapter.neutralInput();
        if (!current.getAsBoolean() || !available(slot,screen)) throw new BridgeException("stale_screen","AE phantom screen changed before its native callback.");
        ExpandedGuiApi.call(slot,"handleMouseClicked",template==null?null:template.copy(),false,button);
        boolean flushed=false;
        if (current.getAsBoolean() && screen instanceof GuiContainer) {
            Object manager=ExpandedGuiApi.call(((GuiContainer)screen).inventorySlots,"getSyncManager");
            if (manager!=null) { ExpandedGuiApi.call(manager,"flushSync"); flushed=true; }
        }
        JsonObject r=new JsonObject(); r.addProperty("accepted",true); r.addProperty("submitted",true);
        r.addProperty("nativeAction","ae_virtual_phantom_native_click"); r.addProperty("serverConfirmed",false);
        r.addProperty("configurationOnly",true); r.addProperty("inventoryConsumed",false); r.addProperty("syncFlushInvoked",flushed);
        r.addProperty("realCursorPreserved",ItemStack.areItemStacksEqual(before,inventory.getItemStack()));
        r.addProperty("acceptanceScope","Native callback returned; native type predicates apply. Read configuration after synchronization; never replay to verify.");
        if (ghost && current.getAsBoolean()) r.add("ghostRelease",NeiGhostDragAdapter.afterMark(screen));
        return r;
    }
}

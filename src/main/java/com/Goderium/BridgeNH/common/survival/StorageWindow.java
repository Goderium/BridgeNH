package com.Goderium.BridgeNH.common.survival;

import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.call;
import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.enumeration;
import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.field;
import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.is;
import static com.Goderium.BridgeNH.client.gui.ExpandedGuiApi.load;
import static com.Goderium.BridgeNH.common.survival.SurvivalStorage.need;
import static com.Goderium.BridgeNH.common.survival.SurvivalStorage.same;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraftforge.common.util.ForgeDirection;

/** Exact endpoint identity and the native inventory actions used by its ordinary player UI. */
final class StorageWindow {
    private static final String DRAWERS="com.jaquadro.minecraft.storagedrawers.";
    private static final String STORAGE=DRAWERS+"inventory.InventoryStorage";
    private static final String ME="appeng.container.implementations.ContainerMEMonitorable";
    private final EntityPlayerMP player;
    private final TileEntity tile;
    private final Container window;
    private final String kind;
    private final boolean deposit;
    private final ItemStack type;
    private final IInventory chest;
    private final int drawerIndex;
    private Object drawer, aeType, grid;
    final String key;

    static boolean target(TileEntity tile,String kind,JsonObject ep) {
        if (tile==null) return false;
        if ("chest".equals(kind)) return tile.getClass()==TileEntityChest.class;
        if ("drawer".equals(kind)) return tile.getClass().getName().equals(DRAWERS+"block.tile.TileEntityDrawersStandard");
        if (!"me".equals(kind)||!is(tile,"appeng.api.parts.IPartHost")) return false;
        Object part=call(tile,"getPart", ForgeDirection.getOrientation(JsonData.integer(ep,"side",0,5)));
        return is(part,"appeng.parts.reporting.PartTerminal");
    }

    static boolean bound(EntityPlayerMP p,TileEntity tile,String kind,JsonObject ep) {
        Container c=p.openContainer;
        if(c==null || c==p.inventoryContainer) return false;
        if("chest".equals(kind)) {
            if(c.getClass()!=ContainerChest.class) return false;
            IInventory inv=((ContainerChest)c).getLowerChestInventory();
            if(inv==tile) return true;
            return inv instanceof InventoryLargeChest && ((InventoryLargeChest)inv).isPartOfLargeChest((IInventory)tile);
        }
        if("drawer".equals(kind)) {
            if(!is(c,DRAWERS+"inventory.ContainerDrawers")) return false;
            for(Object row:c.inventorySlots) {
                IInventory inv=((Slot)row).inventory;
                if(is(inv,STORAGE)) return field(inv,STORAGE,"tile")==tile;
            }
            return false;
        }
        return is(c,ME) && call(c,"getTarget")==call(tile,"getPart",ForgeDirection.getOrientation(JsonData.integer(ep,"side",0,5)));
    }

    static void drawerAccess(EntityPlayerMP p,TileEntity tile) {
        need(Boolean.TRUE.equals(call(load(tile,DRAWERS+"security.SecurityManager"),"hasAccess",p.getGameProfile(),tile)),
            "endpoint_denied","The drawer's security provider denied access.");
        need(Boolean.FALSE.equals(call(tile,"isSealed")) && Boolean.FALSE.equals(call(tile,"isVending")) && Boolean.FALSE.equals(call(tile,"isVoid")),
            "endpoint_unavailable","Select an unsealed finite drawer without a void upgrade.");
    }

    StorageWindow(EntityPlayerMP p,TileEntity tile,String kind,JsonObject ep,boolean deposit,ItemStack type) {
        this.player=p; this.tile=tile; this.kind=kind; this.deposit=deposit; this.type=type; window=p.openContainer;
        chest="chest".equals(kind)?((ContainerChest)window).getLowerChestInventory():null;
        drawerIndex="drawer".equals(kind)?JsonData.integer(ep,"drawer",0,3):-1;
        String location=tile.xCoord+","+tile.yCoord+","+tile.zCoord;
        if(chest!=null) {
            if(chest instanceof InventoryLargeChest) {
                List<String> halves=new ArrayList<String>();
                halves.add(location);
                for(ForgeDirection d:new ForgeDirection[]{ForgeDirection.NORTH,ForgeDirection.SOUTH,ForgeDirection.WEST,ForgeDirection.EAST}) {
                    int x=tile.xCoord+d.offsetX,z=tile.zCoord+d.offsetZ;
                    if(!p.worldObj.blockExists(x,tile.yCoord,z)) continue;
                    TileEntity other=p.worldObj.getTileEntity(x,tile.yCoord,z);
                    if(other instanceof TileEntityChest && ((InventoryLargeChest)chest).isPartOfLargeChest((IInventory)other))
                        halves.add(x+","+tile.yCoord+","+z);
                }
                need(halves.size()==2,"endpoint_unavailable","Both physical chest halves must be loaded.");
                Collections.sort(halves); location=halves.get(0)+"/"+halves.get(1);
            }
            key="chest:"+p.dimension+":"+location;
        } else if(drawerIndex>=0) {
            drawerAccess(player,tile);
            need(drawerIndex<((Number)call(tile,"getDrawerCount")).intValue() && Boolean.TRUE.equals(call(tile,"isDrawerEnabled",drawerIndex)),
                "endpoint_unavailable","Requested drawer index is not present.");
            drawer=call(tile,"getDrawer",drawerIndex);
            need(drawer!=null && Boolean.FALSE.equals(call(drawer,"isVendingUnlimited")),"endpoint_unavailable","The selected drawer must be finite.");
            key="drawer:"+p.dimension+":"+location+":"+drawerIndex;
        } else {
            Object node=call(window,"getNetworkNode");
            need(node!=null&&Boolean.TRUE.equals(call(node,"isActive")),"endpoint_unavailable","Terminal needs an active ME channel.");
            grid=call(node,"getGrid");
            Object security=call(grid,"getCache",load(tile,"appeng.api.networking.security.ISecurityGrid"));
            need(Boolean.TRUE.equals(call(security,"hasPermission",p,enumeration(tile,"appeng.api.config.SecurityPermissions",deposit?"INJECT":"EXTRACT"))),
                "endpoint_denied","ME security denied this player action.");
            aeType=call(load(tile,"appeng.util.item.AEItemStack"),"create",type.copy());
            key="me:"+call(grid,"getId");
        }
    }

    long count() {
        if(chest!=null) {
            long sum=0;
            for(int i=0;i<chest.getSizeInventory();i++) { ItemStack s=chest.getStackInSlot(i); if(same(s,type)) sum+=s.stackSize; }
            return sum;
        }
        if(drawer!=null) return same((ItemStack)call(drawer,"getStoredItemPrototype"),type)?((Number)call(drawer,"getStoredItemCount")).longValue():0;
        return finiteGridCount();
    }

    // The terminal performs the real player operation. Account against unique finite physical cells;
    // creative, void, external and unknown backing cannot establish conservation.
    private long finiteGridCount() {
        Object storage=call(grid,"getCache",load(tile,"appeng.api.networking.storage.IStorageGrid"));
        Collection<?> providers=(Collection<?>)call(storage,"getActiveCellProviders");
        need(providers.size()<=64,"endpoint_unavailable","ME provider observation budget exceeded.");
        Object channel=enumeration(tile,"appeng.api.storage.StorageChannel","ITEMS");
        Set<Object> cells=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        long total=0;
        for(Object provider:providers) {
            Collection<?> handlers=(Collection<?>)call(provider,"getCellArray",channel);
            for(Object handler:handlers) {
                Object cell=handler;
                for(int i=0;i<8&&cell!=null&&!is(cell,"appeng.api.storage.ICellInventory");i++) {
                    need(is(cell,"appeng.api.storage.IMEInventoryHandler") && call(cell,"getExternalNetworkInventory")==null,
                        "endpoint_unavailable","ME item provider does not identify finite local cells.");
                    cell=is(cell,"appeng.api.storage.ICellInventoryHandler")?call(cell,"getCellInv"):call(cell,"getInternal");
                }
                need(cell!=null&&cell.getClass().getName().equals("appeng.me.storage.ItemCellInventory")&&cells.add(cell)&&cells.size()<=128,
                    "endpoint_unavailable","ME backing is not a unique finite item cell within budget.");
                Object found=call(cell,"getAvailableItem",aeType);
                if(found!=null) {
                    long n=((Number)call(found,"getStackSize")).longValue();
                    need(same(type,(ItemStack)call(found,"getItemStack"))&&n>=0&&total<=Long.MAX_VALUE-n,"endpoint_unavailable","Unverifiable ME cell identity or total.");
                    total+=n;
                }
            }
        }
        return total;
    }

    private Slot slot(boolean playerSlot,boolean source) {
        Slot empty=null;
        for(Object v:window.inventorySlots) {
            Slot s=(Slot)v;
            if(playerSlot ? s.inventory!=player.inventory || s.getSlotIndex()<0 || s.getSlotIndex()>=36 : s.inventory!=chest) continue;
            ItemStack stack=s.getStack();
            if(source) { if(same(stack,type)&&stack.stackSize>0&&s.canTakeStack(player)) return s; }
            else if(s.isItemValid(type)) {
                if(stack==null) { if(empty==null) empty=s; }
                else if(same(stack,type)&&room(s)>0) return s;
            }
        }
        return empty;
    }

    private int room(Slot s) { return Math.max(0,Math.min(type.getMaxStackSize(),s.getSlotStackLimit())-(s.getHasStack()?s.getStack().stackSize:0)); }
    private void click(Slot s,int button) { window.slotClick(s.slotNumber,button,0,player); }
    private int cursor() {
        ItemStack hand=player.inventory.getItemStack();
        need(hand==null||same(hand,type),"identity_changed","Unexpected cursor item; inspect before continuing.");
        return hand==null?0:hand.stackSize;
    }

    String move(int limit) {
        Slot physical=slot(true,deposit);
        if(physical==null) return deposit?"inventory_empty":"inventory_full";
        int n=Math.min(limit,deposit?physical.getStack().stackSize:room(physical));
        if(chest!=null) {
            Slot other=slot(false,!deposit);
            if(other==null) return deposit?"destination_full":"source_empty";
            Slot source=deposit?physical:other, destination=deposit?other:physical;
            n=Math.min(n,Math.min(source.getStack().stackSize,room(destination)));
            if(n==0) return deposit?"destination_full":"inventory_full";
            int initial=source.getStack().stackSize;
            click(source,0);
            need(cursor()==initial,"reconcile_required","Source pickup was not exact.");
            if(n==initial) click(destination,0);
            else for(int i=1;i<=n;i++) { click(destination,1); need(cursor()==initial-i,"reconcile_required","Partial native transfer stopped."); }
            if(cursor()>0) click(source,0);
        } else if(drawer!=null) {
            ItemStack prototype=(ItemStack)call(drawer,"getStoredItemPrototype");
            if(prototype!=null&&!same(prototype,type)) return deposit?"destination_full":"source_empty";
            if(deposit) {
                if(!Boolean.TRUE.equals(call(drawer,"canItemBeStored",type))) return "destination_full";
                int free=prototype==null?((Number)call(drawer,"getMaxCapacity",type)).intValue():((Number)call(drawer,"getRemainingCapacity")).intValue();
                n=Math.min(n,free);
                if(n<=0) return "destination_full";
                ItemStack real=physical.getStack();
                call(tile,"putItemsIntoSlot",drawerIndex,real,n); if(real.stackSize<=0) physical.putStack(null); else physical.onSlotChanged();
            } else {
                n=(int)Math.min(n,count());
                if(n==0) return "source_empty";
                ItemStack extracted=(ItemStack)call(tile,"takeItemsFromSlot",drawerIndex,n,player,false);
                need(same(extracted,type)&&extracted.stackSize==n,"reconcile_required","Drawer returned an unexpected physical stack.");
                if(physical.getHasStack()) { physical.getStack().stackSize+=n; physical.onSlotChanged(); } else physical.putStack(extracted);
            }
            tile.markDirty(); player.worldObj.markBlockForUpdate(tile.xCoord,tile.yCoord,tile.zCoord);
        } else {
            Object single=enumeration(window,"appeng.helpers.MonitorableAction",deposit?"SPLIT_OR_PLACE_SINGLE":"PICKUP_SINGLE");
            if(!deposit) n=(int)Math.min(n,count());
            if(n<=0) return deposit?"destination_full":"source_empty";
            int initial=deposit?physical.getStack().stackSize:0;
            if(deposit) { click(physical,0); need(cursor()==initial,"reconcile_required","ME deposit could not bind source stack."); }
            call(window,"setTargetStack",aeType);
            for(int i=0;i<n;i++) {
                int before=cursor(); call(window,"doMonitorableAction",single,-1,player);
                int after=cursor();
                if(after==before) break;
                need(after==before+(deposit?-1:1),"reconcile_required","ME did not transfer exactly one item.");
            }
            if(cursor()>0) click(physical,0);
        }
        need(cursor()==0,"reconcile_required","A real cursor stack needs reconciliation.");
        return deposit?"destination_full":"source_empty";
    }
}

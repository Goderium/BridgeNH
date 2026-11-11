package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.nei.NeiViewAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.cleanroommc.modularui.widgets.slot.PhantomItemSlot;
import com.cleanroommc.modularui.widgets.slot.FluidSlot;
import com.google.gson.JsonObject;
import java.util.Collections;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

public class PhantomGuiSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        gregtech.common.modularui2.widget.GhostCircuitSlotWidget.Screen screen = new gregtech.common.modularui2.widget.GhostCircuitSlotWidget.Screen();
        PhantomItemSlot item = new PhantomItemSlot(); screen.root.children.add(item);
        item.handler.manager = screen.modular.sync.panel; item.handler.manager.registeredHandler = item.handler;
        InventoryPlayer inventory = new InventoryPlayer(null);
        Item type = new Item() { @Override public String getItemStackDisplayName(ItemStack stack) { return "ghost input fixture"; } };
        Item.itemRegistry.addObject(32330, "bridge_phantom_fixture:item", type);
        ItemStack source = new ItemStack(type, 7, 3); NBTTagCompound tag = new NBTTagCompound(); tag.setString("test", "exact NBT"); source.setTagCompound(tag);
        inventory.mainInventory[5] = source; inventory.setItemStack(source);
        JsonObject entry = entry(screen); String id = entry.get("widgetId").getAsString();
        check(entry.get("kind").getAsString().equals("phantom_item_slot") && entry.getAsJsonArray("supportedActions").size() == 3
                && entry.getAsJsonObject("ghostSlot").get("item").isJsonNull(), "noncontainer phantom slot is discoverable with actual empty configuration and native actions");
        JsonObject drag = action(id, "ghost_drag"), selector = new JsonObject(); selector.addProperty("playerSlot", 5); drag.add("source", selector);
        JsonObject result = GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true);
        check(item.drags == 1 && item.presses == 0 && item.dragged.getTagCompound().equals(tag) && item.dragged.stackSize == 1,
                "actual player template keeps full NBT on an independent unit copy and invokes one native drag");
        check(source.stackSize == 7 && inventory.getItemStack() == source && item.displayed == null && item.handler.packets == 1,
                "no owned stack, cursor or ghost inventory is directly mutated");
        check(result.get("submitted").getAsBoolean() && !result.get("serverConfirmed").getAsBoolean()
                && result.getAsJsonObject("after").getAsJsonObject("ghostSlot").get("item").isJsonNull(), "queued native submission does not invent synced configuration");
        item.displayed = item.dragged.copy(); entry = entry(screen);
        check(entry.getAsJsonObject("ghostSlot").getAsJsonObject("item").get("metadata").getAsInt() == 3 && item.drags == 1, "later native sync can be read without a second drag");
        selector = new JsonObject(); selector.addProperty("registryName", "bridge_phantom_fixture:item"); selector.addProperty("metadata", 4); drag.add("source", selector);
        GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true);
        check(item.drags == 2 && item.dragged.getMetadata() == 4 && !item.dragged.hasTagCompound(), "NEI registry fake template needs no give or real cursor mutation");
        codechicken.nei.ItemPanel previousPanel = codechicken.nei.ItemPanels.itemPanel;
        try {
            codechicken.nei.ItemPanels.itemPanel = new codechicken.nei.ItemPanel(); codechicken.nei.ItemPanels.itemPanel.grid.items.add(source);
            JsonObject query = new JsonObject(); query.addProperty("screenId", "phantom-screen"); query.addProperty("section", "panel"); query.addProperty("scope", "all"); query.addProperty("limit", 1);
            JsonObject page = NeiViewAdapter.read(screen, null, query, "phantom-session", System.nanoTime() + 60000000000L);
            JsonObject observedDrag = action(id, "ghost_drag"); observedDrag.add("source", page.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("ghostSource"));
            int beforeDrags = item.drags;
            codechicken.nei.ItemPanels.itemPanel.draggedStack = source;
            JsonObject marked = GuiControlAdapter.perform(screen, observedDrag, Collections.emptyList(), inventory, () -> true);
            check(item.drags == beforeDrags + 1 && item.dragged.getTagCompound().equals(tag) && inventory.mainInventory[5] == source && source.stackSize == 7,
                    "observed NEI full-NBT reference feeds the same native MUI drag without real-item generation or consumption");
            check(marked.getAsJsonObject("ghostRelease").get("released").getAsBoolean() && codechicken.nei.ItemPanels.itemPanel.draggedStack == null
                    && inventory.getItemStack() == source && source.stackSize == 7,
                    "accepted MUI ghost marking auto-releases only the NEI fake and preserves the real cursor");
            codechicken.nei.ItemPanels.itemPanel.draggedStack = source; item.accept = false;
            marked = GuiControlAdapter.perform(screen, observedDrag, Collections.emptyList(), inventory, () -> true);
            check(!marked.has("ghostRelease") && codechicken.nei.ItemPanels.itemPanel.draggedStack == source,
                    "native filter rejection never discards the user's source ghost"); item.accept = true;
            item.drags = beforeDrags;
        } finally { codechicken.nei.ItemPanels.itemPanel = previousPanel; NeiViewAdapter.invalidateRendered(); }
        item.accept = false; result = GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true);
        check(!result.get("submitted").getAsBoolean() && !result.getAsJsonObject("phantomAction").get("accepted").getAsBoolean() && item.drags == 2,
                "native filter rejection reports no submitted packet"); item.accept = true;
        JsonObject click = action(id, "phantom_click"); click.addProperty("button", 1);
        GuiControlAdapter.perform(screen, click, Collections.emptyList(), inventory, () -> true);
        check(item.presses == 1 && item.releases == 1 && item.button == 1 && inventory.getItemStack() == source, "manual native click uses the real cursor and preserves right-click semantics");
        click.addProperty("button", 2); expect("invalid_args", () -> GuiControlAdapter.perform(screen, click, Collections.emptyList(), inventory, () -> true));
        JsonObject invalid = action(id, "ghost_drag"); invalid.add("source", new JsonObject());
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, invalid, Collections.emptyList(), inventory, () -> true));
        invalid.getAsJsonObject("source").addProperty("playerSlot", 5); invalid.getAsJsonObject("source").addProperty("registryName", "bridge_phantom_fixture:item");
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, invalid, Collections.emptyList(), inventory, () -> true));
        invalid.getAsJsonObject("source").remove("playerSlot"); invalid.getAsJsonObject("source").addProperty("nbt", "caller arbitrary NBT");
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, invalid, Collections.emptyList(), inventory, () -> true));
        screen.root.enabled = false; expect("disabled", () -> GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true)); screen.root.enabled = true;
        expect("stale_screen", () -> GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> false));
        item.handler.manager.registered = false; check(!PhantomGuiAdapter.available(item, screen), "unregistered handler cannot advertise native drag");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true)); item.handler.manager.registered = true;
        item.handler.manager.client = false; check(!PhantomGuiAdapter.available(item, screen), "server manager does not masquerade as current client handler"); item.handler.manager.client = true;
        item.handler.manager.initialized = false; check(!PhantomGuiAdapter.available(item, screen), "uninitialized handler is unavailable"); item.handler.manager.initialized = true;
        screen.root.children.clear(); expect("stale_widget", () -> GuiControlAdapter.perform(screen, drag, Collections.emptyList(), inventory, () -> true));
        FluidSlot fluid = new FluidSlot(); fluid.handler.phantom = true; screen.root.children.add(fluid);
        fluid.handler.manager = screen.modular.sync.panel; fluid.handler.manager.registeredHandler = fluid.handler;
        Fluid water = new Fluid("bridge_phantom_fixture:water"); FluidRegistry.registerFluid(water);
        entry = entry(screen); String fluidId = entry.get("widgetId").getAsString();
        check(entry.getAsJsonObject("ghostSlot").get("available").getAsBoolean() && !entry.getAsJsonObject("ghostSlot").get("controlsAmount").getAsBoolean(), "debug type-only fluid input is exposed alongside ordinary fluid state");
        JsonObject fluidDrag = action(fluidId, "ghost_drag"), fluidSource = new JsonObject(); fluidSource.addProperty("fluidName", water.getName()); fluidSource.addProperty("amount", 32000); fluidDrag.add("source", fluidSource);
        result = GuiControlAdapter.perform(screen, fluidDrag, Collections.emptyList(), inventory, () -> true);
        check(fluid.drags == 1 && fluid.handler.fluid == null && fluid.presses == 0 && inventory.getItemStack() == source,
                "fluid display template queues a single NEI packet without directly filling tank or moving cursor");
        check(result.get("nativeAction").getAsString().equals("mui2_nei_ghost_drag") && result.getAsJsonObject("phantomAction").get("fluidAmount").getAsInt() == 32000,
                "GT display amount NBT must be retained; zero-amount display cannot configure fluids");
        fluid.handler.fluid = new FluidStack(water, 1);
        check(entry(screen).getAsJsonObject("fluidSlot").getAsJsonObject("fluid").getAsJsonObject("value").get("amount").getAsInt() == 1,
                "debug tank's later one-unit type configuration is not reported as 32000 real mB");
        ItemStack cell = new ItemStack(type, 4, 21);
        FluidContainerRegistry.registerFluidContainer(new FluidStack(water, 16000), new ItemStack(type, 1, 21), new ItemStack(type, 1, 22));
        inventory.mainInventory[6] = cell; JsonObject cellSource = new JsonObject(); cellSource.addProperty("playerSlot", 6); fluidDrag.add("source", cellSource);
        result = GuiControlAdapter.perform(screen, fluidDrag, Collections.emptyList(), inventory, () -> true);
        check(fluid.drags == 2 && result.getAsJsonObject("phantomAction").get("fluidAmount").getAsInt() == 16000 && cell.stackSize == 4,
                "a real filled cell's native fluid identity/amount configures the phantom without consuming the cell");
        cellSource.addProperty("playerSlot", 5); expect("invalid_args", () -> GuiControlAdapter.perform(screen, fluidDrag, Collections.emptyList(), inventory, () -> true)); cellSource.addProperty("playerSlot", 6);
        JsonObject fluidClick = action(fluidId, "phantom_click"); fluidClick.addProperty("button", 2);
        GuiControlAdapter.perform(screen, fluidClick, Collections.emptyList(), inventory, () -> true);
        check(fluid.lastButton == 2 && fluid.presses == 1 && fluid.releases == 1, "native middle click can clear a type-only fluid slot without changing real cursor");
        fluid.handler.fill = false; expect("unsupported", () -> GuiControlAdapter.perform(screen, fluidDrag, Collections.emptyList(), inventory, () -> true)); fluid.handler.fill = true;
        fluid.handler.phantom = false; expect("unsupported", () -> GuiControlAdapter.perform(screen, fluidDrag, Collections.emptyList(), inventory, () -> true));
        check(fluid.drags == 2, "ordinary real fluid slots cannot accept virtual supply");
        GuiControlAdapter.invalidate(); return checks;
    }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "phantom-screen"); args.addProperty("actionableOnly", true); return args; }
    private static JsonObject entry(Object screen) { return GuiControlAdapter.read(screen, args(), Collections.emptyList(), System.nanoTime() + 60000000000L).getAsJsonArray("widgets").get(0).getAsJsonObject(); }
    private static JsonObject action(String id, String action) { JsonObject args = new JsonObject(); args.addProperty("screenId", "phantom-screen"); args.addProperty("widgetId", id); args.addProperty("action", action); return args; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void expect(String code, Runnable task) { try { task.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "Expected " + code + " got " + error.code); } }
}

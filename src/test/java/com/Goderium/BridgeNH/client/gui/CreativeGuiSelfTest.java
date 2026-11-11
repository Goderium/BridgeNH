package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.common.structure.BogoCreativeInputSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Actual registry objects and native Container.slotClick, with headless client/player bindings and no input device. */
final class CreativeGuiSelfTest {
    private static int checks;
    static int run() {
        checks = 0; CreativeTabs[] original = CreativeTabs.creativeTabArray.clone();
        GuiContainerCreative cleanup = new GuiContainerCreative();
        try { tabs(); inventoryDefault(); geometryGetters(); slots(); nativeDiagnostics(); checks += BogoCreativeInputSelfTest.run(); }
        finally { CreativeTabs.creativeTabArray = original; cleanup.page(0); GuiControlAdapter.invalidate(); }
        return checks;
    }

    private static void tabs() {
        CreativeTabs[] original = CreativeTabs.creativeTabArray;
        GuiContainerCreative screen = new GuiContainerCreative(); screen.page(0); InventoryPlayer inventory = new InventoryPlayer(null);
        JsonObject page = read(screen, inventory); JsonArray widgets = page.getAsJsonArray("widgets"); JsonObject tab = named(widgets, "inventory");
        String inventoryId = tab.get("widgetId").getAsString(), blockId = named(widgets, "buildingBlocks").get("widgetId").getAsString();
        check(widgets.size() == original.length && "creative_tab".equals(tab.get("kind").getAsString()), "tabs exist without Forge GuiButtons or icon/catalog construction");
        check(tab.get("playerInventory").getAsBoolean() && tab.get("enabled").getAsBoolean() && !tab.get("selected").getAsBoolean(), "actual inventory tab advertises activation independently of the selected item tab");
        check(tab.get("x").getAsInt() == 285 && tab.get("y").getAsInt() == 188 && "screen".equals(tab.get("coordinateSpace").getAsString()), "native tab bounds use the current protected container origin and size");
        check(inventoryId.equals(page.getAsJsonObject("creativeInventory").get("inventoryTabWidgetId").getAsString()), "the inventory tab is directly addressable without searching extra pages");
        check(inventoryId.equals(named(read(screen, inventory).getAsJsonArray("widgets"), "inventory").get("widgetId").getAsString()), "the same actual tab retains its screen-bound widget identity");
        screen.page(1);
        check(!named(read(screen, inventory).getAsJsonArray("widgets"), "buildingBlocks").get("visible").getAsBoolean(), "non-current-page tabs are described as hidden");
        expect("disabled", () -> perform(screen, inventory, blockId));
        JsonObject sent = perform(screen, inventory, inventoryId);
        check(screen.presses == 1 && screen.releases == 1 && screen.lastX == 299 && screen.lastY == 204 && screen.lastButton == 0, "tab activation dispatches exactly the existing native press/release callbacks at its current center");
        check(sent.get("submitted").getAsBoolean() && sent.getAsJsonObject("creativeInventory").get("selectedTabIndex").getAsInt() == CreativeTabs.tabInventory.getTabIndex() && !sent.has("completed"), "tab result reports native submission and observed selection without a server completion claim");
        screen.moveOrigin(150, 40); perform(screen, inventory, inventoryId);
        check(screen.lastX == 333 && screen.lastY == 192, "a previously issued tab ID uses current geometry after the native GUI moves");
        screen.moveOrigin(116, 52);
        CreativeTabs modTab = new Tab(12, "fixture_mod");
        page = read(screen, inventory); String modId = named(page.getAsJsonArray("widgets"), "fixture_mod").get("widgetId").getAsString();
        check(named(page.getAsJsonArray("widgets"), "fixture_mod").get("enabled").getAsBoolean(), "Forge mod tabs use their public page/row/column layout");
        screen.releaseTab = modTab; perform(screen, inventory, modId);
        check(screen.lastX == 130 && screen.lastY == 36 && screen.func_147056_g() == 12, "a named mod tab routes to its own native location");
        CreativeTabs overlap = new Tab(13, "overlap") { @Override public int getTabColumn() { return 5; } @Override public boolean isTabInFirstRow() { return false; } };
        page = read(screen, inventory);
        check(!named(page.getAsJsonArray("widgets"), "overlap").get("enabled").getAsBoolean(), "ambiguous overlapping native tab positions never advertise activation");
        String overlapId = named(page.getAsJsonArray("widgets"), "overlap").get("widgetId").getAsString();
        expect("disabled", () -> perform(screen, inventory, overlapId));
        CreativeTabs.creativeTabArray[overlap.getTabIndex()] = null;
        expect("stale_widget", () -> perform(screen, inventory, overlapId));
        CreativeTabs malformed = new Tab(14, "misregistered") { @Override public int getTabColumn() { return 5; } @Override public boolean isTabInFirstRow() { return false; } };
        CreativeTabs.creativeTabArray[14] = null; CreativeTabs.creativeTabArray[0] = malformed;
        expect("disabled", () -> perform(screen, inventory, inventoryId));
        CreativeTabs.creativeTabArray = original; screen.page(0);
        new Tab(CreativeTabs.tabBlock.getTabIndex(), "replacement");
        expect("stale_widget", () -> perform(screen, inventory, blockId));
        CreativeTabs.creativeTabArray = original; original[CreativeTabs.tabBlock.getTabIndex()] = CreativeTabs.tabBlock;
        JsonObject invalid = action(inventoryId); invalid.addProperty("button", 1);
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, invalid, Collections.emptyList(), inventory, () -> true));
        int calls = screen.presses;
        expect("stale_screen", () -> GuiControlAdapter.perform(screen, action(inventoryId), Collections.emptyList(), inventory, () -> false));
        check(screen.presses == calls, "a changed current screen cannot submit native callbacks");
        JsonObject otherId = action(inventoryId); otherId.addProperty("screenId", "replacement screen ID");
        expect("stale_widget", () -> GuiControlAdapter.perform(screen, otherId, Collections.emptyList(), inventory, () -> true));
    }

    private static void inventoryDefault() {
        GuiContainerCreative screen = new GuiContainerCreative(); screen.page(0);
        InventoryPlayer inventory = new InventoryPlayer(null);
        CreativeGuiAdapter.selectPlayerInventory(screen, inventory, () -> true);
        check(screen.func_147056_g() == CreativeTabs.tabInventory.getTabIndex()
                && screen.presses == 1 && screen.releases == 1, "opening creative inventory selects the native player tab with one real callback pair");
        CreativeGuiAdapter.selectPlayerInventory(screen, inventory, () -> true);
        check(screen.presses == 1 && screen.releases == 1, "an already selected player tab is not clicked again");
        screen.select(CreativeTabs.tabBlock);
        expect("stale_screen", () -> CreativeGuiAdapter.selectPlayerInventory(screen, inventory, () -> false));
        check(screen.presses == 1, "a stale screen cannot receive the inventory-tab selection");
        screen.releaseTab = CreativeTabs.tabBlock;
        expect("invalid_state", () -> CreativeGuiAdapter.selectPlayerInventory(screen, inventory, () -> true));
        check(screen.presses == 2 && screen.releases == 2, "an unchanged native selection is reported once without a click retry");
    }

    private static void geometryGetters() {
        PublicGeometry screen = new PublicGeometry(); screen.page(0);
        CreativeGuiAdapter.State state = CreativeGuiAdapter.capture(screen, null);
        check(state.page == 3 && state.left == 17 && state.top == 19 && state.width == 201 && state.height == 141, "present public native getters take precedence over fixed fields");
        expect("adapter_error", () -> CreativeGuiAdapter.capture(new BrokenGeometry(), null));
        check(!CreativeGuiAdapter.describe(CreativeTabs.tabInventory, state).get("enabled").getAsBoolean(), "custom creative GUI subclasses are described but do not acquire unverified native actions");
    }

    private static void slots() {
        GuiContainerCreative screen = new GuiContainerCreative(); screen.page(0); screen.select(CreativeTabs.tabInventory);
        InventoryPlayer inventory = new InventoryPlayer(null), other = new InventoryPlayer(null);
        Item item = new FixtureItem();
        inventory.mainInventory[25] = new ItemStack(item, 4, 7);
        inventory.mainInventory[1] = new ItemStack(item, 1, 19056);
        NativeContainer container = new NativeContainer(inventory);
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class); player.inventory = inventory; player.inventoryContainer = container;
        Minecraft client = new Minecraft(); client.thePlayer = player; screen.mc = client;
        SyncFixture sync = new SyncFixture(); container.onCraftGuiOpened(sync); sync.changes = 0;
        GuiContainerCreative.CreativeSlot main = new GuiContainerCreative.CreativeSlot(container.getSlot(25), 9, 9, 54);
        GuiContainerCreative.CreativeSlot hotbar = new GuiContainerCreative.CreativeSlot(container.getSlot(37), 36, 9, 112);
        screen.addSlot(main, 9); screen.addSlot(hotbar, 36);
        screen.addSlot(new GuiContainerCreative.CreativeSlot(inventory, 0, -2000, -2000), 0);
        screen.addSlot(new Slot(new InventoryBasic("trash", false, 1), 0, 173, 112), 45);
        screen.addSlot(new Slot(new InventoryBasic("catalog", false, 1), 0, 9, 18), 46);
        screen.addSlot(new GuiContainerCreative.CreativeSlot(other, 10, 45, 54), 10);
        screen.addSlot(new Slot(inventory, 11, 63, 54), 11);
        JsonObject page = read(screen, inventory); JsonObject slot = named(page.getAsJsonArray("widgets"), "player_slot:9");
        String id = slot.get("widgetId").getAsString();
        check(page.getAsJsonObject("creativeInventory").get("playerSlots").getAsInt() == 2, "only current-player native visible inventory-tab wrappers become actionable slots");
        check(slot.getAsJsonObject("item").get("count").getAsInt() == 4 && slot.getAsJsonObject("item").get("metadata").getAsInt() == 7 && slot.get("nativeWrapperIndex").getAsInt() == 9, "player slot data reads the actual stack and states native wrapper index semantics");
        check(slot.get("playerInventoryIndex").getAsInt() == 25 && slot.get("nativeContainerSlotId").getAsInt() == 25,
                "main inventory mapping comes from the actual public backing rather than a mismatched creative wrapper index");
        JsonObject mappedHotbar = named(page.getAsJsonArray("widgets"), "player_slot:36");
        check(mappedHotbar.get("playerInventoryIndex").getAsInt() == 1 && mappedHotbar.get("nativeContainerSlotId").getAsInt() == 37,
                "hotbar inventory/container mapping is public and requires no caller-side index arithmetic");
        check(slot.get("x").getAsInt() == 125 && slot.get("y").getAsInt() == 106 && slot.get("nativeClickType").getAsInt() == 0, "player slot bounds and supported fixed pickup mode are explicit");
        JsonObject sent = perform(screen, inventory, id);
        check(inventory.mainInventory[25] == null && inventory.getItemStack().getMetadata() == 7 && inventory.getItemStack().stackSize == 4,
                "real native Container.slotClick changes backing inventory and carried stack despite different wrapper/container indices");
        check(sent.get("nativeCallback").getAsString().equals("net.minecraft.inventory.Container.slotClick") && sent.get("nativeContainerSlotId").getAsInt() == 25
                && sent.get("localEffect").getAsBoolean() && sent.get("beforeCursorItem").isJsonNull(), "resolved native container slot and observed local effect are returned");
        JsonObject nativeObservation = sent.getAsJsonObject("nativeDiagnostic");
        check(nativeObservation.get("actualDeclaredContainerClass").getAsString().equals(Container.class.getName())
                && nativeObservation.get("actualPublicMethodName").getAsString().equals("slotClick")
                && nativeObservation.get("nativeCallBinding").getAsString().equals("typed_compiled"), "public method declaration metadata comes from the actual container class independently of typed invocation");
        check(nativeObservation.getAsJsonObject("afterNative").get("slotItem").isJsonNull()
                && nativeObservation.getAsJsonObject("afterNative").getAsJsonObject("carriedItem").get("metadata").getAsInt() == 7
                && nativeObservation.get("nativeStateChanged").getAsBoolean() && !nativeObservation.get("synchronizationChangedState").getAsBoolean(), "actual native carried state is captured before and after synchronization");
        String hotbarId = named(page.getAsJsonArray("widgets"), "player_slot:36").get("widgetId").getAsString();
        JsonObject hotbarSent = perform(screen, inventory, hotbarId);
        check(hotbarSent.get("nativeContainerSlotId").getAsInt() == 37 && inventory.mainInventory[1].getMetadata() == 7
                && inventory.mainInventory[1].stackSize == 4 && inventory.getItemStack().getMetadata() == 19056, "public wrapper isHere resolves the actual hotbar inventory index rather than its wrapper index");
        perform(screen, inventory, id);
        check(inventory.mainInventory[25].getMetadata() == 19056 && inventory.mainInventory[1].getMetadata() == 7 && inventory.getItemStack() == null,
                "three real native pickups swap main and hotbar slots and leave the cursor empty");
        perform(screen, inventory, id); perform(screen, inventory, hotbarId); perform(screen, inventory, id);
        check(inventory.mainInventory[25].getMetadata() == 7 && inventory.mainInventory[25].stackSize == 4 && inventory.mainInventory[1].getMetadata() == 19056
                && inventory.mainInventory[1].stackSize == 1 && inventory.getItemStack() == null, "the same three pickups restore both exact item stacks");
        check(sync.changes == 6 && screen.presses == 0, "native detectAndSendChanges reports every changed actual slot to its existing synchronization listener");
        check(sent.get("submitted").getAsBoolean() && "creative_inventory_left_pickup".equals(sent.get("nativeAction").getAsString()) && sent.has("after") && sent.getAsJsonObject("creativeInventory").has("cursorItem") && sent.get("completion").getAsString().contains("later inspection"), "slot action returns current local observations and explicitly leaves server synchronization unacknowledged");
        JsonObject invalidMode = action(id); invalidMode.addProperty("mode", 2);
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, invalidMode, Collections.emptyList(), inventory, () -> true));
        JsonObject wrongAction = action(id); wrongAction.addProperty("action", "set_selected"); wrongAction.addProperty("selected", 1);
        expect("unsupported", () -> GuiControlAdapter.perform(screen, wrongAction, Collections.emptyList(), inventory, () -> true));
        expect("stale_widget", () -> perform(screen, other, id));
        screen.select(CreativeTabs.tabBlock);
        expect("stale_widget", () -> perform(screen, inventory, id));
        screen.select(CreativeTabs.tabInventory); screen.inventorySlots.inventorySlots.remove(main);
        GuiContainerCreative.CreativeSlot replacement = new GuiContainerCreative.CreativeSlot(container.getSlot(25), 9, 9, 54); screen.addSlot(replacement, 9);
        expect("stale_widget", () -> perform(screen, inventory, id));
        page = read(screen, inventory); String replacementId = named(page.getAsJsonArray("widgets"), "player_slot:9").get("widgetId").getAsString();
        check(!id.equals(replacementId), "a replacement player slot never inherits an old object's widget ID");
        screen.addSlot(new Slot(inventory, 12, 9, 54), 12);
        expect("disabled", () -> perform(screen, inventory, replacementId));
        check(sync.changes == 6 && inventory.getItemStack() == null && inventory.mainInventory[25].stackSize == 4, "invalid modes, player bindings, tabs, removed slots and ambiguous positions cause no inventory mutation");
        screen.inventorySlots.inventorySlots.remove(screen.inventorySlots.inventorySlots.size() - 1);
        int number = container.getSlot(25).slotNumber; container.getSlot(25).slotNumber = 0;
        expect("target_unverified", () -> perform(screen, inventory, replacementId)); container.getSlot(25).slotNumber = number;
        container.add(new Slot(inventory, 25, 0, 0)); expect("target_unverified", () -> perform(screen, inventory, replacementId));
        check(sync.changes == 6 && inventory.mainInventory[25].stackSize == 4 && inventory.getItemStack() == null, "inconsistent container numbering and duplicate backing bindings never send a pickup");
    }

    private static void nativeDiagnostics() {
        InventoryPlayer inventory = new InventoryPlayer(null);
        ItemStack original = new ItemStack(new FixtureItem(), 4, 7); inventory.mainInventory[25] = original;
        NativeContainer container = new NativeContainer(inventory);
        int[] canTakeCalls = {0};
        Slot blocked = new Slot(inventory, 25, 0, 0) {
            @Override public boolean canTakeStack(EntityPlayer player) { canTakeCalls[0]++; return false; }
        };
        blocked.slotNumber = 25; container.inventorySlots.set(25, blocked);
        GuiContainerCreative screen = new GuiContainerCreative();
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class); player.inventory = inventory; player.inventoryContainer = container;
        Minecraft client = new Minecraft(); client.thePlayer = player; screen.mc = client;
        JsonObject denied = GuiCallbacks.creativePickup(screen, new GuiContainerCreative.CreativeSlot(blocked, 9, 0, 0), inventory);
        check(!denied.get("canTakeStack").getAsBoolean() && !denied.get("nativeStateChanged").getAsBoolean()
                && denied.get("actualSlotClass").getAsString().equals(blocked.getClass().getName()) && inventory.mainInventory[25] == original
                && inventory.getItemStack() == null && canTakeCalls[0] == 3, "typed refusal diagnostics identify the actual denying slot without retrying or changing inventory");
        check(denied.getAsJsonObject("returnedStack").get("metadata").getAsInt() == 7,
                "a non-null real native return is not mistaken for a successful pickup when canTakeStack denies it");

        NativeContainer synchronizedContainer = new NativeContainer(inventory); player.inventoryContainer = synchronizedContainer;
        int[] syncCalls = {0};
        synchronizedContainer.onCraftGuiOpened(new ICrafting() {
            public void updateCraftingInventory(Container ignored, List stacks) { }
            public void sendSlotContents(Container ignored, int index, ItemStack stack) {
                if (index == 25 && stack == null) {
                    syncCalls[0]++;
                    // Test-only listener models synchronous correction; production never writes inventory itself.
                    inventory.setInventorySlotContents(25, original); inventory.setItemStack(null);
                }
            }
            public void sendProgressBarUpdate(Container ignored, int index, int value) { }
        });
        JsonObject corrected = GuiCallbacks.creativePickup(screen,
                new GuiContainerCreative.CreativeSlot(synchronizedContainer.getSlot(25), 9, 0, 0), inventory);
        check(corrected.getAsJsonObject("afterNative").get("slotItem").isJsonNull()
                && corrected.getAsJsonObject("afterNative").getAsJsonObject("carriedItem").get("metadata").getAsInt() == 7
                && corrected.getAsJsonObject("afterSync").getAsJsonObject("slotItem").get("count").getAsInt() == 4
                && corrected.getAsJsonObject("afterSync").get("carriedItem").isJsonNull()
                && corrected.get("nativeStateChanged").getAsBoolean() && corrected.get("synchronizationChangedState").getAsBoolean()
                && syncCalls[0] == 1, "real native mutation and a synchronization listener's correction remain separately observable after exactly one pickup");
        check(corrected.toString().length() < 8192 && !corrected.toString().contains("displayName")
                && !corrected.toString().contains("nbt"), "fixed primitive diagnostics remain bounded and do not enumerate display text or NBT");
    }

    private static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
        } catch (ReflectiveOperationException error) { throw new AssertionError("Headless native player binding could not be initialized", error); }
    }
    private static class NativeContainer extends Container {
        NativeContainer(InventoryPlayer inventory) {
            InventoryBasic hidden = new InventoryBasic("hidden", false, 9);
            for (int i = 0; i < 45; i++) add(new Slot(i < 9 ? hidden : inventory, i < 36 ? i : i - 36, 0, 0));
        }
        void add(Slot slot) { addSlotToContainer(slot); }
        @Override public boolean canInteractWith(EntityPlayer player) { return true; }
    }
    private static class SyncFixture implements ICrafting {
        int changes;
        public void updateCraftingInventory(Container container, List stacks) { }
        public void sendSlotContents(Container container, int index, ItemStack stack) { changes++; }
        public void sendProgressBarUpdate(Container container, int index, int value) { }
    }

    private static JsonObject read(GuiContainerCreative screen, InventoryPlayer inventory) { return GuiControlAdapter.read(screen, args(), Collections.emptyList(), System.nanoTime() + 1000000000L, inventory); }
    private static JsonObject perform(GuiContainerCreative screen, InventoryPlayer inventory, String id) { return GuiControlAdapter.perform(screen, action(id), Collections.emptyList(), inventory, () -> true); }
    private static JsonObject args() { JsonObject result = new JsonObject(); result.addProperty("screenId", "creative fixture"); result.addProperty("limit", 128); return result; }
    private static JsonObject action(String id) { JsonObject result = new JsonObject(); result.addProperty("screenId", "creative fixture"); result.addProperty("widgetId", id); result.addProperty("action", "activate"); return result; }
    private static JsonObject named(JsonArray entries, String name) { for (com.google.gson.JsonElement value : entries) if (name.equals(value.getAsJsonObject().get("name").getAsString())) return value.getAsJsonObject(); throw new AssertionError("Missing " + name); }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
    private static class Tab extends CreativeTabs { Tab(int index, String name) { super(index, name); } @Override public Item getTabIconItem() { throw new AssertionError("Tab icon/item enumeration is unnecessary"); } }
    public static class PublicGeometry extends GuiContainerCreative { public int getTabPage() { return 3; } public int getGuiLeft() { return 17; } public int getGuiTop() { return 19; } public int getXSize() { return 201; } public int getYSize() { return 141; } }
    public static class BrokenGeometry extends GuiContainerCreative { public int getGuiLeft() { throw new IllegalStateException("Public getter failure must remain visible"); } }
    private static class FixtureItem extends Item { @Override public String getItemStackDisplayName(ItemStack stack) { return "creative fixture item"; } }
}

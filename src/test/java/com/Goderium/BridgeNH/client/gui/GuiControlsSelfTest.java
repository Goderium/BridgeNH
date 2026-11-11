package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.nei.ExpandedGtSelfTest;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.cleanroommc.modularui.widgets.AbstractCycleButtonWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.menu.DropdownWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.gui.GuiButton;

/** Stable targets, parent state and native semantic setters without a Minecraft window. */
public class GuiControlsSelfTest {
    private static int checks;
    public static int run() { checks = 0; controls(); buttonVariants(); identityAndBounds(); gtPublicSemantics(); ghostCircuit(); fluidSlot(); semanticPages(); actionablePages(); checks += CreativeGuiSelfTest.run(); checks += NamedStructureStateSelfTest.run(); GuiControlAdapter.invalidate(); return checks; }
    private static void buttonVariants() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen(); ButtonWidget button = new ButtonWidget(); button.name="variant"; screen.root.children.add(button);
        String id=named(GuiControlAdapter.read(screen,args(),Collections.emptyList(),generousStart()).getAsJsonArray("widgets"),"variant");
        for (int mouse=1; mouse<=2; mouse++) {
            JsonObject args=action(id,"activate"); args.addProperty("button",mouse); button.order="";
            JsonObject sent=GuiControlAdapter.perform(screen,args,Collections.emptyList());
            check(button.lastButton==mouse && button.order.equals("PTR") && sent.get("button").getAsInt()==mouse,
                    "right/middle semantic activation follows native press, accepted tap, release with exact button");
        }
        button.pressResult="STOP"; button.order="";
        JsonObject sent=GuiControlAdapter.perform(screen,action(id,"activate"),Collections.emptyList());
        check(button.order.equals("PR"), "a rejecting native press is released but never tapped");
        button.pressResult="IGNORE"; button.order=""; GuiControlAdapter.perform(screen,action(id,"activate"),Collections.emptyList());
        check(button.order.equals("PR"), "ignored native press cannot cause a phantom tap");
        hoverGatedButton();
    }
    private static void hoverGatedButton() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        final boolean[] alive = {true};
        final int[] changed = {0};
        ButtonWidget button = new ButtonWidget() {
            public Object onMousePressed(int mouse) { Object result = super.onMousePressed(mouse); if (mouse == 2) alive[0] = false; return result; }
            public boolean onMouseRelease(int mouse) {
                super.onMouseRelease(mouse);
                if (!isHovering()) return false;
                changed[0] = mouse == 1 ? 0 : 1 - changed[0]; return true;
            }
        };
        screen.root.children.add(button);
        // Root and button share a label; select the actual child identity explicitly.
        String id = GuiControlAdapter.read(screen,args(),Collections.emptyList(),generousStart()).getAsJsonArray("widgets").get(1).getAsJsonObject().get("widgetId").getAsString();
        JsonObject click = action(id, "activate");
        screen.screen.context.updateState(3,4,.25F);
        JsonObject first = GuiControlAdapter.perform(screen,click,Collections.emptyList(),null,()->alive[0]);
        check(changed[0] == 1 && button.order.equals("PTR"), "Native hovered press/tap/release reaches a custom release-gated button once.");
        check(!first.get("serverConfirmed").getAsBoolean(), "Native input receipt is not server state confirmation.");
        GuiControlAdapter.perform(screen,click,Collections.emptyList(),null,()->alive[0]);
        check(changed[0] == 0, "The same generic lifecycle supports the reverse toggle.");
        changed[0]=1; click.addProperty("button",1);
        GuiControlAdapter.perform(screen,click,Collections.emptyList(),null,()->alive[0]);
        check(changed[0]==0 && screen.screen.context.x==3 && screen.screen.context.y==4 && !button.hovering,
                "Right release and coordinate restoration use no OS input or persistent hover override.");
        screen.screen.context.occluded=true; int presses=button.presses;
        expect("target_unverified",()->GuiControlAdapter.perform(screen,click,Collections.emptyList()));
        check(button.presses==presses, "An occluded requested button cannot invoke a different control or bypass hit testing.");
        screen.screen.context.occluded=false; screen.screen.context.dragging=true;
        expect("input_busy",()->GuiControlAdapter.perform(screen,click,Collections.emptyList()));
        screen.screen.context.dragging=false; click.addProperty("button",2); int releases=button.releases;
        JsonObject closed=GuiControlAdapter.perform(screen,click,Collections.emptyList(),null,()->alive[0]);
        check(button.releases==releases && closed.getAsJsonObject("nativeCallbackReturns").has("remainingCallbacksSkipped"),
                "A screen-changing press does not tap or release into a stale/replacement page.");
    }
    private static void fluidSlot() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        com.cleanroommc.modularui.widgets.slot.FluidSlot slot = new com.cleanroommc.modularui.widgets.slot.FluidSlot(); screen.root.children.add(slot);
        JsonObject filter = args(); filter.addProperty("actionableOnly", true);
        JsonObject entry = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").get(0).getAsJsonObject();
        String id = entry.get("widgetId").getAsString(); JsonObject state = entry.getAsJsonObject("fluidSlot");
        check(entry.get("kind").getAsString().equals("fluid_slot") && entry.getAsJsonArray("supportedActions").size() == 1
                && state.get("available").getAsBoolean() && state.getAsJsonObject("fluid").get("empty").getAsBoolean()
                && state.getAsJsonObject("capacity").get("value").getAsInt() == 256000 && !state.getAsJsonObject("phantom").get("value").getAsBoolean(), "shared fluid slot advertises native activation and exact public empty/capacity/phantom state");
        net.minecraft.entity.player.InventoryPlayer inventory = new net.minecraft.entity.player.InventoryPlayer(null);
        net.minecraft.item.ItemStack cursor = new net.minecraft.item.ItemStack(new net.minecraft.item.Item() {
            @Override public String getItemStackDisplayName(net.minecraft.item.ItemStack stack) { return "held fluid container"; }
        }, 1, 0); inventory.setItemStack(cursor);
        JsonObject sent = GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList(), inventory, () -> true);
        check(slot.presses == 1 && slot.releases == 1 && slot.handler.fluid == null && inventory.getItemStack() == cursor,
            "one normal press/no-op release neither direct-fills the tank nor changes the real cursor in Bridge code");
        check(sent.get("submitted").getAsBoolean() && !sent.get("serverConfirmed").getAsBoolean() && sent.get("cursorObserved").getAsBoolean()
                && sent.getAsJsonObject("beforeCursorItem").get("count").getAsInt() == 1 && sent.getAsJsonObject("afterCursorItem").get("count").getAsInt() == 1
                && sent.getAsJsonObject("afterFluidSlot").getAsJsonObject("fluid").get("empty").getAsBoolean(), "native submission reports actual cursor and current cache without inventing server fill acceptance");
        slot.handler.fluid = ExpandedGtSelfTest.fixtureFluid(new net.minecraftforge.fluids.Fluid("slot_sync_fixture"), 1000);
        state = FluidGuiAdapter.read(slot);
        check(state.getAsJsonObject("fluid").getAsJsonObject("value").get("fluid").getAsString().equals("slot_sync_fixture")
                && state.getAsJsonObject("fluid").getAsJsonObject("value").get("amount").getAsInt() == 1000 && slot.presses == 1, "later synchronized public fluid data can be read without clicking or filling again");
        screen.root.enabled = false; expect("disabled", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
        check(slot.presses == 1, "disabled ancestors still block fluid native activation"); screen.root.enabled = true;
        slot.handler.fill = false; slot.handler.drain = false;
        check(GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").size() == 0, "native read-only fluid displays cannot advertise a fill/drain click");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
        slot.handler.fill = true; slot.handler.tank = null; state = FluidGuiAdapter.read(slot);
        check(!state.get("available").getAsBoolean() && !state.getAsJsonObject("capacity").get("available").getAsBoolean()
                && state.get("nativeClickAvailable").getAsBoolean(), "missing public capacity stays unavailable independently of known native click permissions");
        slot.handler = null;
        check(!FluidGuiAdapter.read(slot).get("nativeClickAvailable").getAsBoolean()
                && GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").size() == 0, "uninitialized sync handlers neither look empty nor advertise an action");
    }
    private static void ghostCircuit() {
        gregtech.common.modularui2.widget.GhostCircuitSlotWidget.Screen screen = new gregtech.common.modularui2.widget.GhostCircuitSlotWidget.Screen();
        gregtech.common.modularui2.widget.GhostCircuitSlotWidget circuit = new gregtech.common.modularui2.widget.GhostCircuitSlotWidget(); circuit.name = "live ghost circuit";
        circuit.handler.manager = screen.modular.sync.panel; circuit.handler.manager.registeredHandler = circuit.handler;
        screen.root.children.add(circuit);
        JsonObject filter = args(); filter.addProperty("actionableOnly", true);
        JsonObject entry = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").get(0).getAsJsonObject();
        String id = entry.get("widgetId").getAsString();
        check(entry.get("kind").getAsString().equals("ghost_circuit") && entry.getAsJsonArray("supportedActions").size() == 2 && entry.has("setValueApi")
                && entry.getAsJsonObject("circuitConfig").get("value").getAsInt() == -1, "shared ghost circuit advertises native activation and a ready public selector submission");
        JsonObject sent = GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList());
        check(circuit.presses == 1 && circuit.releases == 1 && circuit.handler.circuit.config == -1, "one normal ghost click queues sync without directly setting its inventory/configuration");
        check(sent.get("submitted").getAsBoolean() && !sent.get("serverConfirmed").getAsBoolean()
                && sent.getAsJsonObject("afterCircuitConfig").get("value").getAsInt() == -1, "pending native click does not invent server acceptance or a changed client configuration");
        circuit.handler.circuit.config = 7;
        entry = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").get(0).getAsJsonObject();
        check(entry.getAsJsonObject("circuitConfig").get("value").getAsInt() == 7 && circuit.presses == 1, "later synchronization reads the current public configuration without clicking again");
        net.minecraft.entity.player.InventoryPlayer inventory = new net.minecraft.entity.player.InventoryPlayer(null);
        JsonObject set = action(id, "set_value"); set.addProperty("value", 24); circuit.mouseBlocked = true;
        sent = GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true);
        check(circuit.handler.packets == 1 && circuit.handler.packetId == gregtech.common.modularui2.sync.GhostCircuitSyncHandler.SYNC_CIRCUIT_CONFIG
                && circuit.handler.payload == 24 && circuit.presses == 1 && circuit.releases == 1 && circuit.handler.circuit.config == 7,
                "named setter reads the public packet constant and writes one short without mouse callbacks or local config writes");
        check(sent.get("submitted").getAsBoolean() && !sent.get("serverConfirmed").getAsBoolean()
                && "gt_ghost_circuit_set_value".equals(sent.get("nativeAction").getAsString()) && sent.get("requestedCircuitConfig").getAsInt() == 24
                && sent.getAsJsonObject("afterCircuitConfig").get("value").getAsInt() == 7 && inventory.getItemStack() == null,
                "selector receipt preserves observed client configuration and empty cursor while waiting for server confirmation");
        set.addProperty("value", -1); GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true);
        check(circuit.handler.packets == 2 && circuit.handler.payload == -1 && circuit.handler.circuit.config == 7,
                "native deselection writes the signed short without optimistic clearing");
        for (com.google.gson.JsonElement invalid : Arrays.asList(new com.google.gson.JsonPrimitive(0), new com.google.gson.JsonPrimitive(-2),
                new com.google.gson.JsonPrimitive(25), new com.google.gson.JsonPrimitive(1.5), new com.google.gson.JsonPrimitive(true),
                new com.google.gson.JsonPrimitive("24"), com.google.gson.JsonNull.INSTANCE)) {
            set.add("value", invalid); expect("invalid_args", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true));
        }
        set.remove("value"); expect("invalid_args", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true));
        check(circuit.handler.packets == 2, "invalid numeric ranges, fractions, booleans, strings, null and missing values never send a packet");
        set.addProperty("value", 1);
        expect("target_unverified", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList()));
        inventory.setItemStack(new net.minecraft.item.ItemStack(new net.minecraft.item.Item() {}, 1, 0));
        expect("cursor_not_empty", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true)); inventory.setItemStack(null);
        expect("stale_screen", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> false));
        final int[] currentChecks = {0};
        expect("stale_widget", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> ++currentChecks[0] == 1));
        check(circuit.handler.packets == 2, "unknown/nonempty actual cursor and changed live context prevent submission");
        circuit.handler.allowC2S = false;
        check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "C2S-disabled handlers cannot advertise set_value");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true)); circuit.handler.allowC2S = true;
        circuit.handler.valid = false; check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "invalid handler prevents setter advertisement"); circuit.handler.valid = true;
        circuit.handler.manager.initialized = false; check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "uninitialized manager prevents setter advertisement"); circuit.handler.manager.initialized = true;
        circuit.handler.manager.client = false; check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "server manager cannot advertise a client submission"); circuit.handler.manager.client = true;
        circuit.handler.manager.registered = false;
        entry = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").get(0).getAsJsonObject();
        check(entry.getAsJsonArray("supportedActions").size() == 1 && !entry.has("setValueApi"), "unregistered handler leaves only the existing native activation advertised");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true)); circuit.handler.manager.registered = true;
        circuit.handler.manager.registeredHandler = new Object(); check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "exact handler membership is required"); circuit.handler.manager.registeredHandler = circuit.handler;
        screen.modular.container = new Object();
        expect("unsupported", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true)); screen.modular.container = screen.modular.sync.container;
        circuit.handler.manager = new com.cleanroommc.modularui.value.sync.ModularSyncManager().panel; circuit.handler.manager.registeredHandler = circuit.handler;
        check(!GhostCircuitGuiAdapter.setterAvailable(circuit, screen), "a handler from another native screen manager is unavailable"); circuit.handler.manager = screen.modular.sync.panel;
        check(circuit.handler.packets == 2, "native readiness and ownership failures never send a packet");
        screen.root.enabled = false; expect("disabled", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
        expect("disabled", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true));
        check(circuit.presses == 1, "disabled ghost ancestors prevent native activation"); screen.root.enabled = true;
        screen.root.children.remove(circuit); expect("stale_widget", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true)); screen.root.children.add(circuit);
        circuit.handler.throwAfterSend = true; expect("adapter_error", () -> GuiControlAdapter.perform(screen, set, Collections.emptyList(), inventory, () -> true));
        check(circuit.handler.packets == 3 && circuit.handler.payload == 1 && circuit.handler.circuit.config == 7 && circuit.presses == 1,
                "a failure after native submission propagates without fallback, replay, mouse dispatch or a direct setter");
        circuit.handler = null;
        check(GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").size() == 0, "uninitialized public ghost sync handler cannot advertise an action");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(id, "activate"), Collections.emptyList()));
    }
    private static void controls() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        ButtonWidget button = new ButtonWidget(); button.name = "activate";
        TextFieldWidget text = new TextFieldWidget(); text.name = "editable";
        AbstractCycleButtonWidget choice = new AbstractCycleButtonWidget(); choice.name = "choice";
        SliderWidget slider = new SliderWidget(); slider.name = "slider";
        DropdownWidget dropdown = new DropdownWidget(); dropdown.name = "dropdown";
        screen.root.children.addAll(Arrays.asList(button, text, choice, slider, dropdown));
        JsonObject first = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()); JsonArray entries = first.getAsJsonArray("widgets");
        check(entries.size() == 6, "public widget tree contains root and supported controls");
        String parentId = entries.get(0).getAsJsonObject().get("widgetId").getAsString();
        String buttonId = named(entries, "activate"), textId = named(entries, "editable"), choiceId = named(entries, "choice"), sliderId = named(entries, "slider"), dropdownId = named(entries, "dropdown");
        check(entries.get(0).getAsJsonObject().getAsJsonArray("childIds").size() == 5 && entries.get(1).getAsJsonObject().get("parentId").getAsString().equals(parentId), "parent/child relations use stable widget identities");
        JsonObject second = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart());
        check(named(second.getAsJsonArray("widgets"), "activate").equals(buttonId), "same actual widget keeps ID across inspections");
        GuiControlAdapter.perform(screen, action(buttonId, "activate"), Collections.emptyList());
        check(button.presses == 1 && button.releases == 1 && button.taps == 1, "one activation invokes one complete native button lifecycle without coordinates");
        final JsonObject edit = action(textId, "set_text"); edit.addProperty("text", "  machine  ");
        GuiControlAdapter.perform(screen, edit, Collections.emptyList());
        check(text.value.value.equals("machine") && text.commits == 1 && !text.focused, "committed text uses native focus-removal validator and synchronized value");
        edit.addProperty("text", "bad!"); expect("invalid_args", () -> GuiControlAdapter.perform(screen, edit, Collections.emptyList()));
        check(text.commits == 1 && text.text.equals("  machine  "), "native text pattern rejects before changing field or sync source");
        edit.addProperty("text", "draft"); edit.addProperty("commit", false); GuiControlAdapter.perform(screen, edit, Collections.emptyList());
        check(text.focused && text.text.equals("draft") && text.value.value.equals("machine"), "uncommitted text remains an explicitly focused draft");
        JsonObject focus = action(textId, "focus"); focus.addProperty("focused", false); GuiControlAdapter.perform(screen, focus, Collections.emptyList());
        check(text.value.value.equals("draft") && text.commits == 2, "native unfocus commits the existing draft once");
        final JsonObject select = action(choiceId, "set_selected"); select.addProperty("selected", 2); GuiControlAdapter.perform(screen, select, Collections.emptyList());
        check(choice.selected == 2 && choice.changes == 1, "selected option updates actual cycle sync source");
        select.addProperty("selected", 3); expect("invalid_args", () -> GuiControlAdapter.perform(screen, select, Collections.emptyList()));
        check(choice.changes == 1, "out-of-range selection does not call native setter");
        final JsonObject slide = action(sliderId, "set_value"); slide.addProperty("value", 4.5); GuiControlAdapter.perform(screen, slide, Collections.emptyList());
        check(slider.value == 4.5 && slider.changes == 1, "slider uses native bounded setter with source sync");
        slide.addProperty("value", 8); expect("invalid_args", () -> GuiControlAdapter.perform(screen, slide, Collections.emptyList()));
        check(slider.changes == 1, "out-of-range slider never invokes setter");
        JsonObject drop = action(dropdownId, "set_selected"); drop.addProperty("selected", 1); GuiControlAdapter.perform(screen, drop, Collections.emptyList());
        check(dropdown.value.value.equals("south") && dropdown.changes == 1, "dropdown selected index maps the actual typed option and native setter");
        screen.root.enabled = false;
        expect("disabled", () -> GuiControlAdapter.perform(screen, action(buttonId, "activate"), Collections.emptyList()));
        check(button.presses == 1, "disabled parent prevents a child action even when the child's own enabled bit is true");
        screen.root.enabled = true; screen.root.children.remove(button);
        expect("stale_widget", () -> GuiControlAdapter.perform(screen, action(buttonId, "activate"), Collections.emptyList()));
        check(button.presses == 1, "removed widget identity cannot activate a replacement/old object");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(parentId, "activate"), Collections.emptyList()));
        JsonObject extra = action(choiceId, "set_selected"); extra.addProperty("selected", 1); extra.addProperty("methodName", "arbitraryMethod");
        expect("invalid_args", () -> GuiControlAdapter.perform(screen, extra, Collections.emptyList()));
        check(choice.changes == 1, "caller-selected reflection and unexpected args have no effect");
    }
    private static void identityAndBounds() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        GuiButton a = new GuiButton(0, 1, 2, 10, 10, "same native id A"), b = new GuiButton(0, 3, 4, 10, 10, "same native id B");
        JsonObject data = GuiControlAdapter.read(screen, args(), Arrays.asList(a, b), generousStart());
        String first = data.getAsJsonArray("widgets").get(0).getAsJsonObject().get("widgetId").getAsString();
        String second = data.getAsJsonArray("widgets").get(1).getAsJsonObject().get("widgetId").getAsString();
        check(!first.equals(second), "AE-style duplicate native button IDs never collide");
        check(data.getAsJsonArray("widgets").get(0).getAsJsonObject().get("nativeButtonId").getAsInt() == 0, "original native ID remains informational");
        for (int i = 0; i < 300; i++) { ButtonWidget button = new ButtonWidget(); button.name = "bounded " + i; screen.root.children.add(button); }
        JsonObject page = args(); page.addProperty("offset", 128); page.addProperty("limit", 128);
        data = GuiControlAdapter.read(screen, page, Arrays.asList(a, b), generousStart());
        check(data.getAsJsonArray("widgets").size() <= 128 && data.get("hasMore").getAsBoolean(), "large tree pagination is bounded with continuation");
        check(data.toString().getBytes(StandardCharsets.UTF_8).length <= 96 * 1024, "control DTO remains within byte budget");
        JsonObject root = GuiControlAdapter.read(screen, args(), Arrays.asList(a, b), generousStart()).getAsJsonArray("widgets").get(2).getAsJsonObject();
        check(root.get("childCount").getAsInt() == 300 && root.getAsJsonArray("childIds").size() == 128 && root.get("childIdsTruncated").getAsBoolean(), "child edge list is bounded while total remains explicit");
        WidgetFixtures.Screen replacement = new WidgetFixtures.Screen();
        expect("stale_widget", () -> GuiControlAdapter.perform(replacement, action(first, "activate"), Collections.emptyList()));
    }
    private static void gtPublicSemantics() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        AbstractCycleButtonWidget recheck = new AbstractCycleButtonWidget(); recheck.name = "arbitrary unnamed machine button"; recheck.count = 2;
        recheck.overlay = new UITexture("gregtech:textures/gui/overlay_button/structure_update.png");
        recheck.tooltip = new RichTooltip();
        recheck.tooltip.builder = () -> recheck.tooltip.text.lines.add("GT5U.gui.button.structure_update");
        AbstractCycleButtonWidget power = new AbstractCycleButtonWidget(); power.name = "another arbitrary button"; power.count = 2;
        power.overlay = new DynamicDrawable(() -> new UITexture("gregtech:textures/gui/overlay_button/power_switch_off.png"));
        screen.root.children.addAll(Arrays.asList(recheck, power));
        JsonArray entries = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets");
        JsonObject described = entries.get(1).getAsJsonObject(); String recheckId = described.get("widgetId").getAsString();
        check(described.get("semantic").getAsString().equals("structure_recheck") && described.getAsJsonArray("tooltip").get(0).getAsString().equals("GT5U.gui.button.structure_update"), "shared GT resource and public dirty tooltip identify recheck without machine class, ID or coordinates");
        check(recheck.tooltip.builds == 1 && described.getAsJsonObject("overlay").has("u0"), "native dirty tooltip refresh and public atlas UVs are exposed");
        check(entries.get(2).getAsJsonObject().get("semantic").getAsString().equals("power_switch") && entries.get(2).getAsJsonObject().getAsJsonObject("overlay").getAsJsonObject("current").get("location").getAsString().endsWith("power_switch_off.png"), "current dynamic power texture resolves through public Supplier");
        JsonObject sent = GuiControlAdapter.perform(screen, action(recheckId, "recheck_structure"), Collections.emptyList());
        check(sent.get("submitted").getAsBoolean() && recheck.selected == 1 && recheck.changes == 1, "semantic recheck runs the normal two-state button click exactly once");
        JsonObject pending = GuiControlAdapter.perform(screen, action(recheckId, "recheck_structure"), Collections.emptyList());
        check(!pending.get("submitted").getAsBoolean() && pending.get("pending").getAsBoolean() && recheck.selected == 1 && recheck.changes == 1, "pending structure update does not toggle back or submit a second request");
        check(recheck.tooltip.builds == 1, "clean tooltips are not rebuilt on each inspection/action");
        recheck.tooltip.autoUpdate = true;
        recheck.tooltip.builder = () -> recheck.tooltip.text.lines.add("updated public label");
        entries = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets");
        check(recheck.tooltip.builds == 2 && entries.get(1).getAsJsonObject().getAsJsonArray("tooltip").get(0).getAsString().equals("updated public label"), "native auto-update tooltips refresh on data inspection without drawing or hovering");
        recheck.tooltip = null; recheck.overlay = new UITexture("othermod:textures/gui/overlay_button/structure_update.png"); recheck.selected = 0;
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(recheckId, "recheck_structure"), Collections.emptyList()));
        check(recheck.changes == 1, "a same-named foreign texture cannot reuse GT structure semantics and the live target is revalidated");
        recheck.overlay = new UITexture("gregtech:textures/gui/overlay_button/structure_update.png"); recheck.count = 3;
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(recheckId, "recheck_structure"), Collections.emptyList()));
        check(recheck.changes == 1, "structure texture on a non-boolean control does not advertise a recheck action");
        ButtonWidget labelOnly = new ButtonWidget(); labelOnly.name = "structure_update"; screen.root.children.add(labelOnly);
        String labelId = named(GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets"), "structure_update");
        expect("unsupported", () -> GuiControlAdapter.perform(screen, action(labelId, "recheck_structure"), Collections.emptyList()));
        check(labelOnly.presses == 0, "unverified label names never authorize structure mutation");
        JsonObject tooltipOnly = new JsonObject(); GuiDataAdapter.text(tooltipOnly, Arrays.asList("\u00a7aGT5U.gui.button.structure_update"));
        check("structure_recheck".equals(GuiPublicSemantics.semantic(tooltipOnly)), "public GT tooltip key supports a semantic label when texture data is unavailable");
        DynamicDrawable[] cycle = new DynamicDrawable[1]; cycle[0] = new DynamicDrawable(() -> cycle[0]); power.overlay = cycle[0];
        entries = GuiControlAdapter.read(screen, args(), Collections.emptyList(), generousStart()).getAsJsonArray("widgets");
        check(!entries.get(2).getAsJsonObject().has("semantic"), "cyclic dynamic display data remains bounded and cannot invent a semantic target");
    }
    private static void semanticPages() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        RichTooltip skipped = new RichTooltip(); skipped.builder = () -> { throw new AssertionError("ordinary widgets must not build tooltips during semantic filtering"); };
        for (int i = 0; i < 400; i++) {
            com.cleanroommc.modularui.widget.Widget slot = new com.cleanroommc.modularui.widget.Widget(); slot.tooltip = skipped;
            screen.root.children.add(slot);
        }
        AbstractCycleButtonWidget first = new AbstractCycleButtonWidget(); first.name = "first recheck"; first.count = 2;
        first.overlay = new UITexture("gregtech:textures/gui/overlay_button/structure_update.png");
        AbstractCycleButtonWidget power = new AbstractCycleButtonWidget(); power.name = "power"; power.count = 2;
        power.overlay = new UITexture("gregtech:textures/gui/overlay_button/power_switch_on.png");
        AbstractCycleButtonWidget second = new AbstractCycleButtonWidget(); second.name = "second recheck"; second.count = 2;
        second.overlay = first.overlay;
        screen.root.children.addAll(Arrays.asList(first, power, second));
        JsonObject filter = args(); filter.addProperty("semantic", "structure_recheck"); filter.addProperty("limit", 1);
        JsonObject page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart());
        check(page.getAsJsonArray("widgets").size() == 1 && page.getAsJsonArray("widgets").get(0).getAsJsonObject().get("name").getAsString().equals("first recheck"), "explicit semantic filter reaches GT buttons after hundreds of unrelated slots in one bounded page");
        check(page.get("nextOffset").getAsInt() == 402 && page.get("rawScanned").getAsInt() == 402 && page.get("hasMore").getAsBoolean(), "filtered continuation uses original tree positions and advances across skipped nodes");
        check(skipped.builds == 0 && page.get("semanticCandidates").getAsInt() == 1, "unrelated widget tooltip builders are never inspected by the filter");
        filter.addProperty("offset", page.get("nextOffset").getAsInt());
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart());
        check(page.getAsJsonArray("widgets").size() == 1 && page.getAsJsonArray("widgets").get(0).getAsJsonObject().get("name").getAsString().equals("second recheck") && page.get("nextOffset").getAsInt() == 404 && !page.get("hasMore").getAsBoolean(), "filtered next page skips other semantics without repeating the first match");
        filter.addProperty("offset", 0); filter.addProperty("semantic", "power_switch");
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart());
        check(page.getAsJsonArray("widgets").size() == 1 && page.getAsJsonArray("widgets").get(0).getAsJsonObject().get("name").getAsString().equals("power"), "power filter returns the verified power control only");
        filter.addProperty("offset", 403);
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart());
        check(page.getAsJsonArray("widgets").size() == 0 && page.get("nextOffset").getAsInt() == 404 && !page.get("partial").getAsBoolean() && !page.get("hasMore").getAsBoolean(), "exhausted no-match page advances its raw cursor and reports complete coverage");
        filter.addProperty("offset", 0);
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS);
        check(page.getAsJsonArray("widgets").size() == 0 && page.get("nextOffset").getAsInt() == 1 && page.get("partial").getAsBoolean() && page.get("scanTruncated").getAsBoolean(), "budget-limited empty filter advances at least the visited raw node and reports partial coverage");
        filter.addProperty("semantic", "arbitrary_method");
        expect("invalid_args", () -> GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()));
    }
    private static void actionablePages() {
        WidgetFixtures.Screen screen = new WidgetFixtures.Screen();
        RichTooltip skipped = new RichTooltip(); skipped.builder = () -> { throw new AssertionError("Non-actionable widgets, disabled controls and context labels must not build tooltips"); };
        com.cleanroommc.modularui.widgets.TextWidget label = new com.cleanroommc.modularui.widgets.TextWidget(); label.name = "machine context"; label.text = "Generator settings"; label.tooltip = skipped;
        screen.root.children.add(label);
        for (int i = 0; i < 400; i++) { com.cleanroommc.modularui.widget.Widget slot = new com.cleanroommc.modularui.widget.Widget(); slot.name = "player item slot"; slot.tooltip = skipped; screen.root.children.add(slot); }
        ButtonWidget disabled = new ButtonWidget(); disabled.enabled = false; disabled.tooltip = skipped;
        ButtonWidget invalid = new ButtonWidget(); invalid.valid = false; invalid.tooltip = skipped;
        ButtonWidget first = new ButtonWidget(); first.name = "first action"; first.tooltip = new RichTooltip(); first.tooltip.builder = () -> first.tooltip.text.lines.add("Start work");
        ButtonWidget second = new ButtonWidget(); second.name = "second action";
        AbstractCycleButtonWidget power = new AbstractCycleButtonWidget(); power.name = "power action"; power.count = 2; power.overlay = new UITexture("gregtech:textures/gui/overlay_button/power_switch_on.png");
        screen.root.children.addAll(Arrays.asList(disabled, invalid, first, second, power));
        JsonObject filter = args(); filter.addProperty("actionableOnly", true); filter.addProperty("limit", 2);
        JsonObject page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()); JsonArray entries = page.getAsJsonArray("widgets");
        check(entries.size() == 2 && entries.get(0).getAsJsonObject().get("contextOnly").getAsBoolean() && "Generator settings".equals(entries.get(0).getAsJsonObject().get("text").getAsString()), "actionable pages retain required public sibling text as explicit context-only labels");
        check(entries.get(0).getAsJsonObject().getAsJsonArray("supportedActions").size() == 0 && entries.get(1).getAsJsonObject().getAsJsonArray("supportedActions").size() > 0 && "first action".equals(entries.get(1).getAsJsonObject().get("name").getAsString()), "filtered controls advertise actual native actions while context labels remain read-only");
        check(page.get("nextOffset").getAsInt() == 405 && page.get("rawScanned").getAsInt() == 405 && page.get("hasMore").getAsBoolean(), "filter continuation counts original tree positions across skipped item slots and disabled controls");
        check(skipped.builds == 0 && first.tooltip.builds == 1, "unrelated and label tooltips are skipped before construction while the real action tooltip is retained");
        filter.addProperty("offset", page.get("nextOffset").getAsInt());
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()); entries = page.getAsJsonArray("widgets");
        check(entries.size() == 2 && "second action".equals(entries.get(0).getAsJsonObject().get("name").getAsString()) && "power action".equals(entries.get(1).getAsJsonObject().get("name").getAsString()) && page.get("nextOffset").getAsInt() == 407 && !page.get("hasMore").getAsBoolean(), "next filtered page returns remaining controls exactly once without dropping the final node");
        filter.addProperty("offset", 0); filter.addProperty("semantic", "power_switch");
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart());
        check(page.getAsJsonArray("widgets").size() == 1 && "power action".equals(page.getAsJsonArray("widgets").get(0).getAsJsonObject().get("name").getAsString()) && skipped.builds == 0, "actionable-only composes with the existing semantic filter without probing other tooltip builders");
        filter.remove("semantic"); filter.addProperty("offset", 405);
        page = GuiControlAdapter.read(screen, filter, Collections.emptyList(), System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(page.getAsJsonArray("widgets").size() == 0 && page.get("nextOffset").getAsInt() == 405 && page.get("rawScanned").getAsInt() == 0 && page.get("partial").getAsBoolean() && page.get("hasMore").getAsBoolean(), "a newly truncated tree prefix cannot rewind or skip an issued filtered cursor");
        filter.addProperty("actionableOnly", "true");
        expect("invalid_args", () -> GuiControlAdapter.read(screen, filter, Collections.emptyList(), generousStart()));
        WidgetFixtures.Screen ordinary = new WidgetFixtures.Screen(); ordinary.root.children.add(new ButtonWidget()); ordinary.root.children.add(new com.cleanroommc.modularui.widgets.TextWidget());
        JsonObject all = GuiControlAdapter.read(ordinary, args(), Collections.emptyList(), generousStart());
        check(all.getAsJsonArray("widgets").size() == 3 && !all.getAsJsonArray("widgets").get(2).getAsJsonObject().has("contextOnly"), "omitting actionableOnly preserves the original full widget tree and label description");
        JsonObject noFilter = args(); noFilter.addProperty("actionableOnly", false);
        check(GuiControlAdapter.read(ordinary, noFilter, Collections.emptyList(), generousStart()).getAsJsonArray("widgets").size() == 3, "explicit false also retains the existing full-tree behavior");
    }
    private static String named(JsonArray entries, String name) { for (com.google.gson.JsonElement element : entries) { JsonObject entry = element.getAsJsonObject(); if (entry.get("name").getAsString().equals(name)) return entry.get("widgetId").getAsString(); } throw new AssertionError("Missing widget " + name); }
    private static JsonObject args() { JsonObject args = new JsonObject(); args.addProperty("screenId", "widget fixture"); return args; }
    private static JsonObject action(String widget, String action) { JsonObject args = args(); args.addProperty("widgetId", widget); args.addProperty("action", action); return args; }
    private static long generousStart() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
}

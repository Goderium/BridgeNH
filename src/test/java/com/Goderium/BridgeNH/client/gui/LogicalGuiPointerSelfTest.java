package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.NEIRecipeWidget;
import codechicken.nei.recipe.RecipeHandlerRef;
import com.google.gson.JsonObject;
import cpw.mods.fml.client.config.GuiSlider;
import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;

/** Native-layout selectors, frame leases and callback coordinates; no live client, OS cursor or redraw. */
public final class LogicalGuiPointerSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0; AtomicLong clock = new AtomicLong(); LogicalGuiPointer.fixtureClock(clock::get);
        try { leases(clock); soleDraw(clock); selectors(); strictArgs(); callbackCoordinates(clock); } finally { LogicalGuiPointer.fixtureClock(null); }
        return checks;
    }
    private static void callbackCoordinates(AtomicLong clock) throws Exception {
        LogicalGuiPointer.clear("input_fixture"); clock.set(0);
        SliderScreen screen = new SliderScreen();
        JsonObject receipt = GuiCallbacks.click(screen, 505, 365, 0, () -> screen.current);
        check(screen.presses == 1 && screen.releases == 1 && screen.slider.getValueInt() == 8 && !screen.slider.dragging,
                "real Forge slider receives the NEI-style converted callback point and reaches maximum without drawing");
        check(receipt.get("logicalMouseReads").getAsInt() == 2 && !receipt.get("osCursorModified").getAsBoolean()
                && !receipt.get("inputReplayed").getAsBoolean(), "coordinate receipt counts actual input reads without claiming native acceptance");
        check(LogicalGuiPointer.guiDrawPosition() == null, "callback coordinates do not survive the dispatch");
        GuiCallbacks.click(screen, 356, 365, 0, () -> screen.current);
        check(screen.slider.getValueInt() == 1 && screen.presses == 2 && screen.releases == 2, "native slider minimum uses normal press/release");
        screen.slider.enabled = false; GuiCallbacks.click(screen, 505, 365, 0, () -> screen.current);
        check(screen.slider.getValueInt() == 1, "disabled native slider remains unchanged despite completed callbacks"); screen.slider.enabled = true;

        Fixture access = new Fixture(); access.screen = screen; access.width = screen.width; access.height = screen.height;
        arm(access, new GuiPointerLocator.Target(505, 365, "click_frame", () -> true));
        LogicalGuiPointer.beforePre(screen, 0, 0); screen.deferred = true;
        GuiCallbacks.click(screen, 505, 365, 0, () -> screen.current);
        check(screen.slider.dragging && screen.selected == 1, "deferred native handler retains a pending selection until its ordinary update");
        check(LogicalGuiPointer.guiDrawPosition().equals(new Point(505, 365)), "intervening frame keeps click coordinates through the existing lease");
        screen.normalUpdate(); check(screen.selected == 8 && !screen.slider.dragging, "ordinary native update commits the Forge slider value once");
        int frameReads = LogicalGuiPointer.frameData().get("logicalReads").getAsInt();
        LogicalGuiPointer.input(screen, 410, 370, () -> true, () -> {
            Point point = LogicalGuiPointer.guiDrawPosition(); point.x = -100;
            check(LogicalGuiPointer.guiDrawPosition().equals(new Point(410, 370)), "callback point is copied and overrides an older frame position");
            LogicalGuiPointer.input(screen, 420, 380, () -> true,
                    () -> check(LogicalGuiPointer.guiDrawPosition().equals(new Point(420, 380)), "nested callback has its own exact point"));
            check(LogicalGuiPointer.guiDrawPosition().equals(new Point(410, 370)), "nested callback restores the outer scope");
            AtomicInteger escaped = new AtomicInteger(); Thread other = new Thread(() -> { if (LogicalGuiPointer.guiDrawPosition() != null) escaped.incrementAndGet(); });
            other.start(); try { other.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
            check(escaped.get() == 0, "callback point never leaks to another thread");
        });
        check(LogicalGuiPointer.frameData().get("logicalReads").getAsInt() == frameReads, "input reads do not inflate natural-frame diagnostics");
        check(LogicalGuiPointer.guiDrawPosition().equals(new Point(505, 365)), "callback completion preserves the natural-frame lease");
        LogicalGuiPointer.renderEnd(); clock.set(2000000000L); LogicalGuiPointer.renderStart();
        check(!LogicalGuiPointer.inspect().get("enabled").getAsBoolean() && LogicalGuiPointer.guiDrawPosition() == null, "click frame lease expires normally");

        SliderScreen stale = new SliderScreen(); stale.current = false;
        expect("stale_screen", () -> GuiCallbacks.click(stale, 505, 365, 0, () -> stale.current));
        check(stale.presses == 0 && stale.releases == 0, "stale context refuses input before native dispatch");
        stale.current = true; stale.replace = true;
        GuiCallbacks.click(stale, 505, 365, 0, () -> stale.current);
        check(stale.presses == 1 && stale.releases == 0 && stale.afterReplacement == null, "screen replacement suppresses logical reads and release into the replacement");
        SliderScreen resized = new SliderScreen(); resized.resize = true;
        GuiCallbacks.click(resized, 505, 365, 0, () -> resized.current);
        check(resized.releases == 0 && resized.afterReplacement == null, "resized GUI cannot reuse the old scaled point");
        SliderScreen failing = new SliderScreen(); failing.fail = true;
        expect("gui_error", () -> GuiCallbacks.click(failing, 505, 365, 0, () -> failing.current));
        check(failing.presses == 1 && failing.releases == 0 && LogicalGuiPointer.guiDrawPosition() == null, "callback failure clears coordinates without replay");
    }
    private static final class SliderScreen extends GuiScreen {
        final GuiSlider slider = new GuiSlider(0, 7, 8, 156, 20, "", "", 1, 8, 1, false, false);
        int presses, releases, selected = 1; boolean current = true, replace, resize, fail, deferred; Point afterReplacement;
        SliderScreen() { width = 854; height = 457; }
        protected void mouseClicked(int x, int y, int button) {
            presses++;
            Point mouse = LogicalGuiPointer.guiDrawPosition();
            if (mouse != null && button == 0) slider.mousePressed(null, mouse.x - 345, mouse.y - 350);
            if (replace) current = false;
            if (resize) width++;
            if (replace || resize) afterReplacement = LogicalGuiPointer.guiDrawPosition();
            if (fail) throw new IllegalStateException("native click failure");
        }
        @Override protected void mouseReleased(int x, int y, int button) {
            releases++; Point mouse = LogicalGuiPointer.guiDrawPosition();
            if (!deferred && mouse != null) slider.mouseReleased(mouse.x - 345, mouse.y - 350);
        }
        void normalUpdate() { slider.mouseReleased(0, 0); selected = slider.getValueInt(); }
    }
    private static void soleDraw(AtomicLong clock) throws Exception {
        clock.set(0); CountingScreen screen = new CountingScreen(); Fixture access = new Fixture(); access.screen = screen;
        arm(access, new GuiPointerLocator.Target(52, 63, "fixture", () -> true)); LogicalGuiPointer.renderStart(); LogicalGuiPointer.beforePre(screen, 1, 2);
        com.Goderium.BridgeNH.mixin.GuiPointerEntityRendererMixin hook = new com.Goderium.BridgeNH.mixin.GuiPointerEntityRendererMixin() { };
        java.lang.reflect.Method draw = com.Goderium.BridgeNH.mixin.GuiPointerEntityRendererMixin.class.getDeclaredMethod("codexbridge$drawOnce", GuiScreen.class, int.class, int.class, float.class); draw.setAccessible(true);
        draw.invoke(hook, screen, 1, 2, 0F);
        check(screen.draws == 1 && screen.point.equals(new Point(52, 63)) && screen.scoped.equals(screen.point), "actual draw wrapper delegates original GUI exactly once at the bound point");
        check(LogicalGuiPointer.forPost(screen).equals(screen.point), "original draw completion preserves scoped Post point");
        LogicalGuiPointer.renderEnd(); screen.fail = true; arm(access, new GuiPointerLocator.Target(52, 63, "fixture", () -> true)); LogicalGuiPointer.beforePre(screen, 1, 2);
        try { draw.invoke(hook, screen, 1, 2, 0F); throw new AssertionError("Native exception must propagate"); }
        catch (java.lang.reflect.InvocationTargetException error) { check(error.getCause() instanceof IllegalStateException && screen.draws == 2 && LogicalGuiPointer.guiDrawPosition() == null && !LogicalGuiPointer.inspect().get("enabled").getAsBoolean(), "native draw exception propagates once and clears cursor/capture scope"); }
    }
    private static void leases(AtomicLong clock) throws Exception {
        Fixture access = new Fixture(); final Fixture initialAccess = access; AtomicInteger targetReads = new AtomicInteger();
        GuiPointerLocator.Target target = new GuiPointerLocator.Target(52, 63, "fixture_native_rectangle", () -> { targetReads.incrementAndGet(); return initialAccess.target; });
        arm(access, target); long generation = LogicalGuiPointer.inspect().get("generation").getAsLong();
        LogicalGuiPointer.renderStart(); Point pre = LogicalGuiPointer.beforePre(access.screen, 7, 8);
        check(LogicalGuiPointer.frameData().get("logicalReads").getAsInt() == 0 && !LogicalGuiPointer.frameData().has("logicalReadX"), "an armed frame has no invented GuiDraw reads or returned coordinates");
        check(pre.equals(new Point(52, 63)) && LogicalGuiPointer.guiDrawPosition().equals(pre), "Pre callback gets bound native point including GuiDraw reads");
        int reads = targetReads.get(), views = access.viewReads;
        for (int i = 0; i < 1000; i++) check(LogicalGuiPointer.guiDrawPosition().equals(pre), "scoped GuiDraw remains coherent");
        check(targetReads.get() == reads && access.viewReads == views, "render hot path does not repeatedly query native layout/reflection");
        check(!LogicalGuiPointer.frameData().get("naturalDrawInvoked").getAsBoolean(), "Pre alone is not accepted draw");
        check(LogicalGuiPointer.forDraw(access.screen).equals(pre) && LogicalGuiPointer.forPost(access.screen).equals(pre), "same point at unique draw and Post");
        JsonObject frame = LogicalGuiPointer.frameData();
        check(frame.get("logicalReads").getAsInt() == 1001 && frame.get("guiDrawHookCalls").getAsInt() == 1001 && frame.get("logicalReadX").getAsInt() == 52 && frame.get("logicalReadY").getAsInt() == 63, "per-frame GuiDraw diagnostic counts actual logical returns at their exact point");
        check(frame.get("physicalMouseX").getAsInt() == 7 && frame.get("physicalMouseY").getAsInt() == 8, "original physical scaled position is observed separately and never rewritten");
        check(frame.get("pointerGeneration").getAsLong() == generation && frame.get("naturalDrawInvoked").getAsBoolean() && !frame.get("osCursorModified").getAsBoolean(), "completed draw metadata is distinct from armed receipt and OS remains untouched");
        final AtomicInteger escaped = new AtomicInteger(); Thread other = new Thread(() -> { if (LogicalGuiPointer.guiDrawPosition() != null || LogicalGuiPointer.frameData().get("cursorSource").getAsString().equals("agent_logical_gui")) escaped.incrementAndGet(); }); other.start(); other.join();
        check(escaped.get() == 0, "logical cursor never leaks into another thread");
        check(LogicalGuiPointer.frameData().get("logicalReads").getAsInt() == 1001, "other-thread read attempts never inflate current natural frame logical reads");
        clock.set(2000000000L); check(LogicalGuiPointer.forPost(access.screen).equals(pre), "TTL expiry cannot change pinned coordinates inside the accepted normal frame");
        LogicalGuiPointer.renderEnd(); check(!LogicalGuiPointer.inspect().get("enabled").getAsBoolean() && LogicalGuiPointer.guiDrawPosition() == null, "TTL clears immediately at frame end");
        clock.set(0); arm(access, target); LogicalGuiPointer.beforePre(access.screen, 1, 2); LogicalGuiPointer.renderFailed(); check(LogicalGuiPointer.guiDrawPosition() == null && !LogicalGuiPointer.inspect().get("enabled").getAsBoolean(), "render/event exceptions release frame and lease");
        for (String change : new String[] {"screen", "world", "width", "height", "protection", "page", "target"}) {
            access = new Fixture(); Fixture current = access; GuiPointerLocator.Target bound = new GuiPointerLocator.Target(52, 63, "fixture", () -> current.target); arm(access, bound);
            if (change.equals("screen")) access.screen = new Object(); else if (change.equals("world")) access.world = new Object(); else if (change.equals("width")) access.width++; else if (change.equals("height")) access.height++; else if (change.equals("protection")) access.protectedInput = false; else if (change.equals("page")) access.view = "next_page"; else access.target = false;
            check(LogicalGuiPointer.beforePre(access.screen, 0, 0) == null && !LogicalGuiPointer.inspect().get("enabled").getAsBoolean(), "lease invalidates on " + change);
            LogicalGuiPointer.renderEnd();
        }
        access = new Fixture(); arm(access, new GuiPointerLocator.Target(52, 63, "fixture", () -> true)); LogicalGuiPointer.beforePre(access.screen, 1, 2); LogicalGuiPointer.clear("release");
        check(LogicalGuiPointer.guiDrawPosition() == null && LogicalGuiPointer.forDraw(access.screen) == null, "explicit release restores physical path without native mouse events");
        LogicalGuiPointer.renderStart(); check(LogicalGuiPointer.beforePre(access.screen, 9, 10) == null && LogicalGuiPointer.forDraw(access.screen) == null && LogicalGuiPointer.forPost(access.screen) == null && LogicalGuiPointer.frameData().get("cursorSource").getAsString().equals("physical"), "unarmed Pre draw Post coordinates remain entirely physical");
        LogicalGuiPointer.guiDrawPosition(); check(LogicalGuiPointer.frameData().get("guiDrawHookCalls").getAsInt() == 1 && LogicalGuiPointer.frameData().get("logicalReads").getAsInt() == 0, "physical hook invocation is counted separately from logical returns and counters reset per frame"); LogicalGuiPointer.renderEnd();
    }
    private static void selectors() {
        PositionedStack output = new PositionedStack(new net.minecraft.item.ItemStack[0], 119, 24);
        IRecipeHandler handler = new IRecipeHandler() {
            public String getRecipeName() { return "pointer native fixture"; } public int numRecipes() { return 20; }
            public List<PositionedStack> getIngredientStacks(int recipe) { return Collections.emptyList(); } public PositionedStack getResultStack(int recipe) { return output; } public List<PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
        };
        GuiRecipe screen = new GuiRecipe(handler, Collections.singletonList(7)); NEIRecipeWidget widget = new NEIRecipeWidget(new RecipeHandlerRef(handler, 7)); widget.x = 150; widget.y = 180; widget.info.yShift = 11; screen.fixtureContainer().widgets.add(widget);
        JsonObject args = set("recipe"); args.addProperty("recipeIndex", 7); args.addProperty("positionKind", "outputs");
        GuiPointerLocator.Target target = GuiPointerLocator.recipe(screen, args);
        check(target.point.equals(new Point(277, 223)) && target.data.getAsJsonObject("observedRectangle").get("y").getAsInt() == 215 && target.valid.getAsBoolean(), "semantic original recipe output resolves absolute widget origin plus native YShift and actual hit-test");
        widget.y++; check(!target.valid.getAsBoolean(), "moved native recipe rectangle is rejected"); widget.y--;
        args.addProperty("recipeIndex", 8); expect("not_found", () -> GuiPointerLocator.recipe(screen, args)); args.addProperty("recipeIndex", 7); args.addProperty("positionIndex", 1); expect("not_found", () -> GuiPointerLocator.recipe(screen, args)); args.remove("positionIndex");
        screen.fixtureContainer().widgets.add(new NEIRecipeWidget(new RecipeHandlerRef(handler, 7))); expect("target_unverified", () -> GuiPointerLocator.recipe(screen, args)); screen.fixtureContainer().widgets.remove(1);
        NativeContainer gui = new NativeContainer(); gui.width = 800; gui.height = 600; gui.setOrigin(100, 120); Slot slot = new Slot(new InventoryBasic("pointer", false, 1), 0, 30, 40); slot.slotNumber = 23; gui.inventorySlots.inventorySlots.add(slot);
        JsonObject slotArgs = set("slot"); slotArgs.addProperty("slot", 23); GuiPointerLocator.Target bound = GuiPointerLocator.locate(gui, slotArgs);
        check(bound.point.equals(new Point(138, 168)) && bound.valid.getAsBoolean(), "ordinary actual slotNumber resolves fixed native gui origin"); slot.xDisplayPosition++; check(!bound.valid.getAsBoolean(), "slot move compares to immutable armed rectangle");
        JsonObject coordinates = set("coordinates"); coordinates.addProperty("x", 799); coordinates.addProperty("y", 599); check(GuiPointerLocator.locate(gui, coordinates).point.x == 799, "coordinate selector uses actual screen edge"); coordinates.addProperty("x", 800); expect("invalid_args", () -> GuiPointerLocator.locate(gui, coordinates));
    }
    private static void strictArgs() {
        JsonObject clear = new JsonObject(); clear.addProperty("action", "clear"); clear.addProperty("screenId", "current"); LogicalGuiPointer.validate(clear); checks++;
        for (String key : new String[] {"source", "ttl_ms", "x", "slot", "recipeIndex"}) { JsonObject invalid = clear.deepCopy(); invalid.addProperty(key, 1); expect("invalid_args", () -> LogicalGuiPointer.validate(invalid)); }
        JsonObject slot = set("slot"); slot.addProperty("slot", -999); expect("invalid_args", () -> LogicalGuiPointer.validate(slot)); slot.addProperty("slot", 4095); LogicalGuiPointer.validate(slot); checks++;
        for (int ttl : new int[] {99, 5001}) { slot.addProperty("ttl_ms", ttl); expect("invalid_args", () -> LogicalGuiPointer.validate(slot)); }
        slot.remove("ttl_ms"); slot.addProperty("x", 1); expect("invalid_args", () -> LogicalGuiPointer.validate(slot));
    }
    private static JsonObject set(String source) { JsonObject args = new JsonObject(); args.addProperty("action", "set"); args.addProperty("screenId", "current"); args.addProperty("source", source); return args; }
    private static void arm(Fixture fixture, GuiPointerLocator.Target target) { LogicalGuiPointer.arm(fixture, fixture.screen, "exact-session", target, 2000); }
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
    private static final class Fixture implements LogicalGuiPointer.Access {
        Object screen = new Object(), world = new Object(); int width = 800, height = 600, viewReads; boolean protectedInput = true, target = true; String view = "current_page";
        public Object screen() { return screen; } public Object world() { return world; } public int width() { return width; } public int height() { return height; } public boolean protectedInput() { return protectedInput; } public String view() { viewReads++; return view; }
    }
    private static final class NativeContainer extends GuiContainer {
        NativeContainer() { super(new Container() { public boolean canInteractWith(EntityPlayer player) { return true; } }); }
        void setOrigin(int x, int y) { guiLeft = x; guiTop = y; }
        protected void drawGuiContainerBackgroundLayer(float partial, int x, int y) { }
    }
    private static final class CountingScreen extends GuiScreen {
        int draws; boolean fail; Point point, scoped;
        public void drawScreen(int x, int y, float partial) { draws++; point = new Point(x, y); scoped = LogicalGuiPointer.guiDrawPosition(); if (fail) throw new IllegalStateException("native fixture failure"); }
    }
}

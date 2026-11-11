package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.nei.NeiPreviewResources;
import com.Goderium.BridgeNH.client.nei.NeiUiAccess;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.awt.Point;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** Screen-bound natural-frame leases and synchronous native callback coordinates. No OS input. */
public final class LogicalGuiPointer {
    private static State state; private static Frame frame; private static long generation; private static String clearReason = "not_armed";
    private static final ThreadLocal<InputPoint> inputPoint = new ThreadLocal<InputPoint>();
    private static boolean entityHookObserved, drawHookObserved, guiDrawHookObserved;
    private static LongSupplier clock = System::nanoTime;
    public static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args, String session, BooleanSupplier protection) {
        return perform(client, screen, args, session, protection, true);
    }
    /** Native custom widgets consume GuiScreen draw coordinates, without calling CodeChicken GuiDraw.getMousePosition. */
    static JsonObject nativeWidget(Minecraft client, GuiScreen screen, JsonObject args, String session, BooleanSupplier protection) {
        return perform(client, screen, args, session, protection, false);
    }
    /** Some NEI handlers finalize a native slider on a later tick; keep intervening normal frames at the click. */
    public static JsonObject prepareClick(Minecraft client, GuiScreen screen, int x, int y, String session, BooleanSupplier protection) {
        if (!NeiUiAccess.is(screen, "codechicken.nei.recipe.GuiRecipe")) return null;
        GuiControlAdapter.neutralInput();
        JsonObject args = new JsonObject(); args.addProperty("action", "set"); args.addProperty("screenId", "native_click_context");
        args.addProperty("x", x); args.addProperty("y", y); args.addProperty("ttl_ms", 2000);
        JsonObject result = perform(client, screen, args, session, protection, true);
        result.addProperty("acceptance", "Short natural-frame lease only; native handler ticks and a later recipe observation determine the effect. No draw or update callback is forced.");
        return result;
    }
    /** GuiDraw readers inside input handlers must see the same scaled point as their callback arguments. */
    static JsonObject input(GuiScreen screen, int x, int y, BooleanSupplier current, Runnable callback) {
        InputPoint previous = inputPoint.get(), point = new InputPoint(screen, x, y, current);
        if (!point.valid()) throw new BridgeException("stale_screen", "Native coordinate input context changed before dispatch.");
        inputPoint.set(point);
        try {
            callback.run();
            JsonObject result = new JsonObject(); result.addProperty("source", "scoped_native_callback_coordinates");
            result.addProperty("x", x); result.addProperty("y", y); result.addProperty("logicalMouseReads", point.reads);
            result.addProperty("osCursorModified", false); result.addProperty("inputReplayed", false);
            result.addProperty("scope", "Synchronous native press/release only; reads are distinct from natural-frame diagnostics and do not prove control acceptance.");
            return result;
        } finally { if (previous == null) inputPoint.remove(); else inputPoint.set(previous); }
    }
    private static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args, String session, BooleanSupplier protection, boolean needsGuiDraw) {
        validate(args);
        String action = JsonData.string(args, "action");
        if ("clear".equals(action)) { clear("explicit_clear"); JsonObject out = inspect(); out.addProperty("status", "cleared"); return out; }
        if (!protection.getAsBoolean()) throw new BridgeException("background_required", "Logical GUI pointer requires current full background takeover.");
        if (!entityHookObserved || !drawHookObserved || (needsGuiDraw && !guiDrawHookObserved)) throw new BridgeException("unsupported", "Required native GUI draw hooks have not been observed; wait for one normal GUI frame and inspect pointer diagnostics.");
        GuiPointerLocator.Target target = GuiPointerLocator.locate(screen, args);
        Access access = new Access() {
            public Object screen() { return client.currentScreen; } public Object world() { return client.theWorld; }
            public int width() { return screen.width; } public int height() { return screen.height; }
            public boolean protectedInput() { return protection.getAsBoolean(); } public String view() { return viewMarker(screen); }
        };
        arm(access, screen, session, target, args.has("ttl_ms") ? JsonData.integer(args, "ttl_ms", 100, 5000) : 2000);
        JsonObject out = inspect(); out.addProperty("status", "armed"); out.add("target", target.data.deepCopy()); out.addProperty("acceptance", "Receipt only; require a later completed natural frame with this generation and nonempty native Tooltip."); return out;
    }
    static void validate(JsonObject args) {
        String action = JsonData.string(args, "action"), source = args.has("source") ? JsonData.string(args, "source") : "coordinates";
        if (!Arrays.asList("set", "clear").contains(action)) throw new BridgeException("invalid_args", "action must be set or clear.");
        List<String> accepted = new java.util.ArrayList<String>(Arrays.asList("action", "screenId"));
        if ("set".equals(action)) {
            accepted.add("source"); accepted.add("ttl_ms");
            if ("coordinates".equals(source)) { accepted.add("x"); accepted.add("y"); JsonData.integer(args, "x", 0, 32767); JsonData.integer(args, "y", 0, 32767); }
            else if ("recipe".equals(source)) { accepted.add("recipeIndex"); accepted.add("positionKind"); accepted.add("positionIndex"); JsonData.integer(args, "recipeIndex", 0, Integer.MAX_VALUE); if (args.has("positionKind") && !Arrays.asList("inputs", "outputs", "other").contains(JsonData.string(args, "positionKind"))) throw new BridgeException("invalid_args", "Unknown positionKind."); if (args.has("positionIndex")) JsonData.integer(args, "positionIndex", 0, Integer.MAX_VALUE); }
            else if ("slot".equals(source)) { accepted.add("slot"); JsonData.integer(args, "slot", 0, 4095); }
            else throw new BridgeException("invalid_args", "Unknown pointer source.");
            if (args.has("ttl_ms")) JsonData.integer(args, "ttl_ms", 100, 5000);
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.entrySet()) if (!accepted.contains(entry.getKey())) throw new BridgeException("invalid_args", entry.getKey() + " is not valid for this pointer action/source.");
        if (JsonData.string(args, "screenId").isEmpty()) throw new BridgeException("invalid_args", "screenId is required.");
    }
    static void arm(Access access, Object screen, String session, GuiPointerLocator.Target target, int ttl) {
        if (access.screen() != screen || !access.protectedInput() || target.point.x < 0 || target.point.y < 0 || target.point.x >= access.width() || target.point.y >= access.height()) throw new BridgeException("stale_screen", "Current pointer screen/rectangle is unavailable.");
        clear("rearmed"); state = new State(access, screen, session, target, ttl, ++generation); clearReason = "armed"; NeiViewAdapter.invalidateRendered();
    }
    public static void renderStart() { frame = null; entityHookObserved = true; tickFast(); }
    public static Point beforePre(Object screen, int x, int y) {
        tick(); State current = state; frame = new Frame(current != null && current.screen == screen ? current : null, screen, x, y);
        return frame.owner == null ? null : new Point(frame.owner.target.point);
    }
    public static Point forDraw(Object screen) { drawHookObserved = true; Frame current = validFrame(screen); if (current == null) return null; current.drawInvoked = true; return current.owner == null ? null : new Point(current.owner.target.point); }
    public static Point forPost(Object screen) { Frame current = validFrame(screen); return current == null || current.owner == null ? null : new Point(current.owner.target.point); }
    public static Point guiDrawPosition() {
        guiDrawHookObserved = true;
        InputPoint input = inputPoint.get();
        if (input != null) {
            if (!input.valid()) return null;
            input.reads++; return new Point(input.x, input.y);
        }
        Frame current = frame;
        if (current == null || current.thread != Thread.currentThread()) return null;
        current.guiDrawHookCalls++;
        if (current.owner == null || validFrame(current.screen) == null) return null;
        Point point = new Point(current.owner.target.point); current.logicalReads++; current.logicalX = point.x; current.logicalY = point.y; return point;
    }
    public static void renderEnd() { frame = null; tickFast(); }
    public static void renderFailed() { clear("render_exception"); NeiViewAdapter.invalidateRendered(); }
    private static Frame validFrame(Object screen) { Frame current = frame; if (current == null || current.screen != screen || current.thread != Thread.currentThread()) return null; if (current.owner != null && !current.owner.fastValid(false)) { clear("frame_context_changed"); return null; } return current; }
    private static void tickFast() { if (state != null && state.thread == Thread.currentThread() && !state.fastValid(true)) clear("expired_or_context_changed"); }
    public static void tick() { if (state != null && state.thread == Thread.currentThread() && !state.valid(frame == null)) clear("expired_or_context_changed"); }
    public static void clear(String reason) { if (state != null || frame != null) { generation++; state = null; frame = null; clearReason = reason; NeiViewAdapter.invalidateRendered(); } }
    public static String context() { return "gui_pointer_generation=" + generation; }
    public static JsonObject frameData() {
        JsonObject out = new JsonObject(); Frame current = frame;
        if (current != null && current.thread != Thread.currentThread()) current = null;
        out.addProperty("cursorSource", current == null || current.owner == null ? "physical" : "agent_logical_gui"); out.addProperty("pointerGeneration", current == null ? generation : current.generation);
        if (current != null) { out.addProperty("physicalMouseX", current.physicalX); out.addProperty("physicalMouseY", current.physicalY); out.addProperty("physicalCoordinateSource", "Original EntityRenderer scaled coordinates before logical Pre substitution; read only."); }
        out.addProperty("guiDrawHookCalls", current == null ? 0 : current.guiDrawHookCalls); out.addProperty("logicalReads", current == null ? 0 : current.logicalReads);
        if (current != null && current.logicalReads > 0) { out.addProperty("logicalReadX", current.logicalX); out.addProperty("logicalReadY", current.logicalY); }
        out.addProperty("logicalReadProvenance", "Per current natural frame calls through the existing GuiDraw.getMousePosition hook, counting only actual logical Point returns; arming or frame cursorSource alone is not a read.");
        out.addProperty("naturalDrawInvoked", current != null && current.drawInvoked); out.addProperty("osCursorModified", false); return out;
    }
    public static JsonObject inspect() {
        tick(); JsonObject out = new JsonObject(); out.addProperty("enabled", state != null); out.addProperty("generation", state == null ? generation : state.generation); out.addProperty("clearReason", clearReason);
        out.addProperty("entityHookObserved", entityHookObserved); out.addProperty("drawHookObserved", drawHookObserved); out.addProperty("guiDrawHookObserved", guiDrawHookObserved); out.addProperty("osCursorModified", false);
        if (state != null) { out.addProperty("source", "agent_logical_gui"); out.addProperty("sessionId", state.session); out.addProperty("x", state.target.point.x); out.addProperty("y", state.target.point.y); out.addProperty("ttlRemainingMillis", Math.max(0, state.deadline - clock.getAsLong()) / 1000000D); out.addProperty("currentScreenBound", true); }
        return out;
    }
    public static String viewMarker(Object screen) {
        StringBuilder out = new StringBuilder(NeiPreviewResources.context());
        if (NeiUiAccess.is(screen, "codechicken.nei.recipe.GuiRecipe")) {
            ClassLoader loader = screen.getClass().getClassLoader(); String owner = "codechicken.nei.recipe.GuiRecipe";
            out.append('/').append(NeiUiAccess.publicField(loader, owner, screen, "recipetype")).append('/').append(NeiUiAccess.publicField(loader, owner, screen, "page"));
            Object refs = NeiUiAccess.publicCall(loader, owner, screen, "getRecipeIndices", new Class<?>[0]);
            if (!(refs instanceof List<?>) || ((List<?>) refs).size() > 64) throw new BridgeException("unsupported", "Pointer view requires bounded native visible recipes.");
            out.append('/').append(refs); out.append('/').append(System.identityHashCode(NeiUiAccess.publicCall(loader, owner, screen, "getHandler", new Class<?>[0])));
        }
        return out.toString();
    }
    interface Access { Object screen(); Object world(); int width(); int height(); boolean protectedInput(); String view(); }
    private static final class InputPoint {
        final GuiScreen screen; final int x, y, width, height; final BooleanSupplier current; int reads;
        InputPoint(GuiScreen screen, int x, int y, BooleanSupplier current) { this.screen = screen; this.x = x; this.y = y; width = screen.width; height = screen.height; this.current = current; }
        boolean valid() { return screen.width == width && screen.height == height && x >= 0 && y >= 0 && x < width && y < height && current.getAsBoolean(); }
    }
    private static final class State {
        final Access access; final Object screen, world; final String session, view; final GuiPointerLocator.Target target; final int width, height; final long deadline, generation; final Thread thread = Thread.currentThread();
        State(Access access, Object screen, String session, GuiPointerLocator.Target target, int ttl, long generation) { this.access = access; this.screen = screen; this.session = session; this.target = target; world = access.world(); width = access.width(); height = access.height(); view = access.view(); deadline = clock.getAsLong() + ttl * 1000000L; this.generation = generation; }
        boolean fastValid(boolean expiry) { try { return (!expiry || clock.getAsLong() < deadline) && access.screen() == screen && access.world() == world && access.width() == width && access.height() == height && access.protectedInput(); } catch (RuntimeException | LinkageError error) { return false; } }
        boolean valid(boolean expiry) { try { return fastValid(expiry) && view.equals(access.view()) && target.valid.getAsBoolean(); } catch (RuntimeException | LinkageError error) { return false; } }
    }
    private static final class Frame { final State owner; final Object screen; final long generation; final int physicalX, physicalY; final Thread thread = Thread.currentThread(); boolean drawInvoked; int guiDrawHookCalls, logicalReads, logicalX, logicalY; Frame(State owner, Object screen, int x, int y) { this.owner = owner; this.screen = screen; physicalX = x; physicalY = y; generation = owner == null ? LogicalGuiPointer.generation : owner.generation; } }
    static void fixtureClock(LongSupplier value) { clear("fixture_reset"); clock = value == null ? System::nanoTime : value; }
}

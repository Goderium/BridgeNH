package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.common.inventory.FluidContainerDataAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Keyboard;

/** Read-only client GUI data through fixed public APIs; never updates physical hover or draws. */
public class GuiDataAdapter {
    public static final int WIDGET_LIMIT = 128, LINE_LIMIT = 32, TEXT_LIMIT = 512;
    private static final int BYTE_LIMIT = 64 * 1024;
    private static final String MUI = "com.cleanroommc.modularui.";

    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject result) {
        return inspect(client, screen, result, Collections.emptyList());
    }

    /** Buttons must be the Forge InitGuiEvent list for this exact screen, not a prior screen. */
    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject result, List<?> buttons) {
        long started = System.nanoTime();
        if (buttons == null) buttons = Collections.emptyList();
        JsonObject coverage = coverage(screen);
        JsonArray widgets = new JsonArray();
        int bytes = 0, visited = 0;
        boolean truncated = false;
        for (Object object : buttons) {
            if (!(object instanceof GuiButton)) continue;
            if (visited >= WIDGET_LIMIT || (visited > 0 && overBudget(started))) { truncated = true; break; }
            visited++;
            GuiButton button = (GuiButton) object;
            if (!button.visible) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("identity", "button:" + button.id);
            entry.addProperty("class", button.getClass().getName());
            entry.addProperty("text", JsonData.bounded(button.displayString, TEXT_LIMIT));
            entry.addProperty("enabled", button.enabled);
            bounds(entry, button.xPosition, button.yPosition, button.width, button.height);
            addAeTooltip(entry, button);
            int size = utf8(entry);
            if (bytes + size > BYTE_LIMIT) { truncated = true; break; }
            widgets.add(entry); bytes += size;
        }
        MuiWalk walk = new MuiWalk(screen, started);
        while (walk.hasNext() && visited < WIDGET_LIMIT && (visited == 0 || !overBudget(started))) {
            Object widget = walk.next(); visited++;
            JsonObject entry;
            try { entry = muiEntry(widget, walk.widgetApi, walk.tooltipApi, screen); }
            catch (RuntimeException | LinkageError error) { coverage.addProperty("mui2Error", error.getClass().getSimpleName()); continue; }
            int size = utf8(entry);
            if (bytes + size > BYTE_LIMIT) { truncated = true; break; }
            widgets.add(entry); bytes += size;
        }
        if (walk.hasNext() || walk.truncated) truncated = true;
        if (walk.error != null) coverage.addProperty("mui2Error", walk.error);
        result.add("widgets", widgets);
        result.add("coverage", coverage);
        result.addProperty("widgetVisited", visited);
        result.addProperty("widgetTruncated", truncated);
        result.addProperty("widgetLimit", WIDGET_LIMIT);
        result.add("budget", StructureAdapter.budget(started));
        if (result.has("screenId")) {
            JsonObject controlArgs = new JsonObject();
            controlArgs.addProperty("screenId", result.get("screenId").getAsString());
            JsonObject controls = GuiControlAdapter.inspect(client, screen, controlArgs, buttons);
            result.add("controls", controls);
            result.add("visibleText", GuiControlAdapter.visibleText(client, screen, result.get("screenId").getAsString(), buttons));
            if (controls.has("creativeInventory")) result.add("creativeInventory", controls.get("creativeInventory"));
        }
        return result;
    }

    public static JsonObject tooltip(Minecraft client, GuiScreen screen, JsonObject args) {
        return tooltip(client, screen, args, Collections.emptyList());
    }

    /** Caller requires a loaded player and checks screenId against the current screen before invoking. */
    public static JsonObject tooltip(Minecraft client, GuiScreen screen, JsonObject args, List<?> buttons) {
        long started = System.nanoTime();
        if (buttons == null) buttons = Collections.emptyList();
        if (screen == null) throw new BridgeException("gui_unavailable", "No GUI is open.");
        boolean slotTarget = args.has("slot"), xy = args.has("x") || args.has("y");
        if (slotTarget == xy || (xy && (!args.has("x") || !args.has("y"))))
            throw new BridgeException("invalid_args", "Choose slot or both x/y, not both target forms.");
        boolean advanced = booleanArg(args, "advanced", false);
        String containerFluid = args.has("containerFluid") ? JsonData.string(args, "containerFluid") : null;
        if (containerFluid != null && (containerFluid.isEmpty() || containerFluid.length() > 128))
            throw new BridgeException("invalid_args", "containerFluid must contain 1 to 128 characters.");
        JsonObject result = new JsonObject();
        result.addProperty("screenId", JsonData.string(args, "screenId"));
        result.addProperty("advanced", advanced);
        result.add("modifiers", modifiers());
        result.add("coverage", coverage(screen));
        text(result, Collections.emptyList());
        if (slotTarget) {
            int number = JsonData.integer(args, "slot", 0, Integer.MAX_VALUE);
            Slot slot = findSlot(screen, number);
            if (slot == null) throw new BridgeException("not_found", "No ordinary container slot has that slotNumber.");
            result.addProperty("slot", number);
            itemTooltip(result, slot.getStack(), client.thePlayer, advanced, containerFluid);
        } else {
            int x = JsonData.integer(args, "x", 0, Math.max(0, screen.width - 1));
            int y = JsonData.integer(args, "y", 0, Math.max(0, screen.height - 1));
            result.addProperty("x", x); result.addProperty("y", y);
            boolean found = false;
            int[] origin = containerOrigin(screen);
            if (screen instanceof GuiContainer && origin != null) {
                int count = 0;
                for (Object object : ((GuiContainer) screen).inventorySlots.inventorySlots) {
                    if (++count > 512 || (count > 1 && overBudget(started))) break;
                    Slot slot = (Slot) object;
                    if (new Rectangle(origin[0] + slot.xDisplayPosition, origin[1] + slot.yDisplayPosition, 16, 16).contains(x, y)) {
                        result.addProperty("slot", slot.slotNumber);
                        itemTooltip(result, slot.getStack(), client.thePlayer, advanced, containerFluid); found = true; break;
                    }
                }
            }
            if (!found) {
                int count = 0;
                for (Object object : buttons) {
                    if (++count > WIDGET_LIMIT || (count > 1 && overBudget(started))) break;
                    if (!(object instanceof GuiButton)) continue;
                    GuiButton button = (GuiButton) object;
                    if (button.visible && new Rectangle(button.xPosition, button.yPosition, button.width, button.height).contains(x, y)
                            && addAeTooltip(result, button)) { result.addProperty("source", "AE public ITooltip.getMessage"); found = true; break; }
                }
            }
            if (!found) {
                MuiWalk walk = new MuiWalk(screen, started);
                int count = 0;
                while (walk.hasNext() && count < WIDGET_LIMIT && (count == 0 || !overBudget(started))) {
                    Object widget = walk.next(); count++;
                    try {
                        Object area = call(walk.widgetApi, widget, "getArea");
                        if (area instanceof Rectangle && ((Rectangle) area).contains(x, y)) {
                            JsonObject entry = muiEntry(widget, walk.widgetApi, walk.tooltipApi, screen);
                            // Return a bounded geometric match without claiming cached hover/topmost/clip equivalence.
                            if (entry.has("tooltip")) {
                                result.add("widget", entry);
                                result.add("tooltip", entry.get("tooltip"));
                                result.add("tooltipTotalLines", entry.get("tooltipTotalLines"));
                                result.add("tooltipTruncated", entry.get("tooltipTruncated"));
                                result.addProperty("source", "MUI2 public current RichTooltip text at geometric widget bounds; native dirty builder refreshed");
                                found = true;
                            } else if (!found) result.add("widget", entry);
                        }
                    } catch (RuntimeException | LinkageError error) { result.addProperty("widgetError", error.getClass().getSimpleName()); }
                }
                if (walk.hasNext() || walk.truncated) result.addProperty("searchTruncated", true);
                if (walk.error != null) result.addProperty("widgetError", walk.error);
            }
            if (!found) {
                result.addProperty("source", "unavailable");
                result.add("tooltip", new JsonArray());
                result.addProperty("reason", "No bounded public tooltip matched. Ordinary slots can be queried by slotNumber; complex widgets/AE virtual slots require dedicated public data APIs.");
            }
        }
        result.add("budget", StructureAdapter.budget(started));
        return result;
    }

    @com.google.common.annotations.VisibleForTesting
    public static void itemTooltip(JsonObject result, ItemStack stack, EntityPlayer player, boolean advanced) {
        itemTooltip(result, stack, player, advanced, null);
    }

    static void itemTooltip(JsonObject result, ItemStack stack, EntityPlayer player, boolean advanced, String containerFluid) {
        result.add("fluidContainer", FluidContainerDataAdapter.read(stack, containerFluid));
        try { result.add("item", JsonData.item(stack)); }
        catch (RuntimeException | LinkageError error) { result.addProperty("itemError", error.getClass().getSimpleName()); }
        result.addProperty("source", stack == null ? "empty ordinary container slot" : "current ItemStack.getTooltip(player, advanced); original NBT preserved");
        if (stack == null) { text(result, Collections.emptyList()); return; }
        try { text(result, stack.copy().getTooltip(player, advanced)); }
        catch (RuntimeException | LinkageError error) {
            result.add("tooltip", new JsonArray()); result.addProperty("tooltipError", error.getClass().getSimpleName());
        }
    }

    private static Slot findSlot(GuiScreen screen, int number) {
        if (!(screen instanceof GuiContainer)) throw new BridgeException("unsupported", "This screen exposes no ordinary GuiContainer slots.");
        int visited = 0;
        for (Object value : ((GuiContainer) screen).inventorySlots.inventorySlots) {
            if (++visited > 512) break;
            Slot slot = (Slot) value;
            if (slot.slotNumber == number) return slot;
        }
        return null;
    }

    private static JsonObject muiEntry(Object widget, Class<?> widgetApi, Class<?> tooltipApi, Object screen) {
        JsonObject entry = new JsonObject();
        entry.addProperty("class", JsonData.bounded(widget.getClass().getName(), 256));
        entry.addProperty("name", JsonData.bounded(String.valueOf(call(widgetApi, widget, "getName")), 256));
        entry.addProperty("identity", "mui:" + Integer.toHexString(System.identityHashCode(widget)));
        entry.addProperty("enabled", (Boolean) call(widgetApi, widget, "isEnabled"));
        entry.addProperty("valid", (Boolean) call(widgetApi, widget, "isValid"));
        Object area = call(widgetApi, widget, "getArea");
        if (area instanceof Rectangle) {
            Rectangle rectangle = (Rectangle) area;
            bounds(entry, rectangle.x, rectangle.y, rectangle.width, rectangle.height);
        }
        Class<?> textApi = ancestor(widget.getClass(), MUI + "widgets.TextWidget");
        if (textApi != null) {
            Object key = call(textApi, widget, "getKey");
            Class<?> keyApi = namedApi(key.getClass(), MUI + "api.drawable.IKey");
            if (keyApi != null) entry.addProperty("text", JsonData.bounded(String.valueOf(call(keyApi, key, "getFormatted")), TEXT_LIMIT));
        }
        GuiPublicSemantics.describe(widget, entry, screen);
        return entry;
    }

    private static boolean addAeTooltip(JsonObject entry, GuiButton button) {
        Class<?> api = namedApi(button.getClass(), "appeng.client.gui.widgets.ITooltip");
        if (api == null) return false;
        try {
            String message = String.valueOf(call(api, button, "getMessage"));
            text(entry, java.util.Arrays.asList(message.split("\\n", LINE_LIMIT + 1)));
            return true;
        } catch (RuntimeException | LinkageError error) { entry.addProperty("tooltipError", error.getClass().getSimpleName()); return false; }
    }

    public static void text(JsonObject result, List<?> lines) {
        JsonArray text = new JsonArray();
        int count = lines == null ? 0 : lines.size();
        boolean shortened = count > LINE_LIMIT;
        for (int i = 0; i < Math.min(count, LINE_LIMIT); i++) {
            String line = String.valueOf(lines.get(i));
            if (line.length() > TEXT_LIMIT) shortened = true;
            text.add(new JsonPrimitive(JsonData.bounded(line, TEXT_LIMIT)));
        }
        result.add("tooltip", text); result.addProperty("tooltipTotalLines", count); result.addProperty("tooltipTruncated", shortened);
    }

    private static JsonObject coverage(GuiScreen screen) {
        JsonObject coverage = new JsonObject();
        coverage.addProperty("vanilla", "Forge-captured buttons and ordinary container slots; per-slot tooltip queried on demand");
        coverage.addProperty("mui2", namedApi(screen == null ? null : screen.getClass(), MUI + "api.IMuiScreen") == null ? "not_applicable" : "bounded public open panels/widget tree, identities, bounds, enabled state");
        coverage.addProperty("mui1", LegacyModularGuiAdapter.screen(screen) ? "public native open windows/children and text fields available through controls/gui.widgets" : "not_applicable");
        coverage.addProperty("text", "visibleText scans supported native button/label/editable text and both ModularUI widget trees independently of inventory-slot ordering; cached RichText contents and public text drawables are included. Arbitrary direct drawString labels have no supported getter.");
        coverage.addProperty("richTooltips", "MUI2 public RichTooltip.isEmpty refreshes native dirty builders, then getRichText/getAsStrings; rich icons/clipping/topmost hit state not reconstructed");
        coverage.addProperty("textures", "MUI2 public Widget.getOverlay/getBackground, DynamicDrawable.getSupplier and UITexture resource/UV data; no drawing or physical hover changes");
        coverage.addProperty("itemSlots", "Public ItemSlot.getSlot/getVanillaSlot and exact native container lookup bind current widget display to a container slot ID; machine/player ownership needs its separate verified binding. Access flags and filter presence are not candidate acceptance.");
        coverage.addProperty("aeVirtualSlots", "unavailable; ordinary container slots and public AE button messages only, cached physical hover is never changed");
        coverage.addProperty("creativeInventory", "Actual public tabs and inventory-tab player slots are exposed in controls/gui.widgets with screen-bound widget IDs. activate uses native tab callbacks or one fixed left pickup callback; synchronization requires a later inspection.");
        coverage.addProperty("modifierPolicy", "Current physical Shift/Ctrl/Alt state only; advanced=true is not Shift expansion. No simulated keys, hover, drawing, or screenshot fallback.");
        return coverage;
    }

    private static int[] containerOrigin(GuiScreen screen) {
        try {
            return new int[] {((Number) call(screen.getClass(), screen, "getGuiLeft")).intValue(),
                    ((Number) call(screen.getClass(), screen, "getGuiTop")).intValue()};
        } catch (RuntimeException | LinkageError error) { return null; }
    }

    private static JsonObject modifiers() {
        JsonObject modifiers = new JsonObject();
        if (Keyboard.isCreated()) {
            modifiers.addProperty("shift", Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
            modifiers.addProperty("ctrl", Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
            modifiers.addProperty("alt", Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
            modifiers.addProperty("source", "current LWJGL physical keyboard state, read only");
        } else modifiers.addProperty("source", "keyboard state unavailable");
        return modifiers;
    }

    public static boolean booleanArg(JsonObject args, String key, boolean fallback) {
        if (!args.has(key)) return fallback;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean())
            throw new BridgeException("invalid_args", key + " must be a boolean.");
        return args.get(key).getAsBoolean();
    }

    private static void bounds(JsonObject entry, int x, int y, int width, int height) {
        entry.addProperty("x", x); entry.addProperty("y", y); entry.addProperty("width", width); entry.addProperty("height", height);
        entry.addProperty("coordinates", "screen pixels; geometric bounds only");
    }
    private static boolean overBudget(long started) { return System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS; }
    private static int utf8(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private static Object call(Class<?> api, Object target, String method) {
        try { return api.getMethod(method).invoke(target); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Public GUI getter " + method + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public GUI getter unavailable: " + method); }
    }

    private static Class<?> ancestor(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) if (name.equals(current.getName())) return current;
        return null;
    }
    private static Class<?> namedApi(Class<?> type, String name) {
        if (type == null) return null;
        if (name.equals(type.getName())) return type;
        for (Class<?> api : type.getInterfaces()) { Class<?> found = namedApi(api, name); if (found != null) return found; }
        return namedApi(type.getSuperclass(), name);
    }

    private static final class MuiWalk {
        final ArrayDeque<Object> pending = new ArrayDeque<Object>();
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Class<?> widgetApi, tooltipApi;
        String error; boolean truncated;
        MuiWalk(GuiScreen screen, long started) {
            Class<?> api = namedApi(screen == null ? null : screen.getClass(), MUI + "api.IMuiScreen");
            if (api == null) return;
            try {
                widgetApi = Class.forName(MUI + "api.widget.IWidget", false, screen.getClass().getClassLoader());
                tooltipApi = Class.forName(MUI + "api.widget.ITooltip", false, screen.getClass().getClassLoader());
                Object modular = call(api, screen, "getScreen");
                Object manager = call(modular.getClass(), modular, "getPanelManager");
                add(call(manager.getClass(), manager, "getOpenPanels"));
            } catch (ClassNotFoundException | RuntimeException | LinkageError failure) { error = failure.getClass().getSimpleName(); }
        }
        boolean hasNext() { return !pending.isEmpty(); }
        Object next() {
            Object widget = pending.removeFirst();
            try { add(call(widgetApi, widget, "getChildren")); }
            catch (RuntimeException | LinkageError failure) { error = failure.getClass().getSimpleName(); }
            return widget;
        }
        void add(Object children) {
            if (!(children instanceof Iterable<?>)) return;
            int considered = 0;
            for (Object child : (Iterable<?>) children) {
                if (++considered > WIDGET_LIMIT || seen.size() >= WIDGET_LIMIT) { truncated = true; break; }
                if (child != null && seen.add(child)) pending.addLast(child);
            }
        }
    }
}

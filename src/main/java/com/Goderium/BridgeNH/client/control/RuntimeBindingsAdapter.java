package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.StatCollector;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Read current registered bindings without consuming queued presses or changing keyboard state. */
public class RuntimeBindingsAdapter {
    public static JsonObject inspect(Minecraft client, JsonObject args) {
        long started = System.nanoTime();
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, 100000) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 64;
        String query = CatalogData.text(args, "query", 128, "");
        KeyBinding[] bindings = client.gameSettings.keyBindings;
        JsonArray entries = new JsonArray();
        int next = Math.min(offset, bindings.length), visited = 0;
        while (next < bindings.length && visited < 512 && entries.size() < limit) {
            if (visited > 0 && System.nanoTime() - started >= 4000000L) break;
            int index = next++;
            visited++;
            KeyBinding binding = bindings[index];
            if (binding == null) continue;
            String description = binding.getKeyDescription(), category = binding.getKeyCategory();
            String display = StatCollector.translateToLocal(description);
            if (!CatalogData.matches(query, description, category, display)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("index", index); item.addProperty("description", JsonData.bounded(description, 256));
            item.addProperty("displayName", JsonData.bounded(display, 512));
            item.addProperty("category", JsonData.bounded(category, 256));
            int code = binding.getKeyCode();
            item.addProperty("keyCode", code);
            item.addProperty("keyName", JsonData.bounded(GameSettings.getKeyDisplayString(code), 128));
            item.addProperty("held", binding.getIsKeyPressed());
            item.addProperty("bound", code != 0);
            Boolean physical = physical(code);
            item.add("physicalDown", physical == null ? JsonNull.INSTANCE : new JsonPrimitive(physical));
            entries.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("bindings", entries); result.addProperty("offset", offset);
        result.addProperty("totalRegistered", bindings.length); result.addProperty("visited", visited);
        result.addProperty("hasMore", next < bindings.length);
        result.add("nextOffset", next < bindings.length ? new JsonPrimitive(next) : JsonNull.INSTANCE);
        result.add("modifiers", modifiers());
        JsonObject coverage = new JsonObject();
        coverage.addProperty("source", "Current GameSettings.keyBindings, including Forge-registered mod bindings; current rebinding is read directly.");
        coverage.addProperty("excluded", "Private mod-specific shortcut registries and GUI focus/shortcut gates are not represented as Forge key bindings.");
        coverage.addProperty("held", "Minecraft binding state; physicalDown is read-only native state. No isPressed/onTick calls, input injection or settings writes.");
        coverage.addProperty("offset", "Index in the registered binding array, not matched-result count.");
        result.add("coverage", coverage);
        JsonObject budget = new JsonObject(); budget.addProperty("softMillis", 4);
        budget.addProperty("elapsedNanos", System.nanoTime() - started); budget.addProperty("cooperative", true);
        result.add("budget", budget); result.addProperty("nonAtomic", true);
        return result;
    }

    static Boolean physical(int code) {
        try {
            if (code == 0) return false;
            if (code > 0) return Keyboard.isCreated() && code < Keyboard.KEYBOARD_SIZE ? Keyboard.isKeyDown(code) : null;
            int button = code + 100;
            return Mouse.isCreated() && button >= 0 && button < Mouse.getButtonCount() ? Mouse.isButtonDown(button) : null;
        } catch (RuntimeException | LinkageError error) { return null; }
    }

    public static JsonObject modifiers() {
        JsonObject result = new JsonObject();
        addModifier(result, "shift", Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT);
        addModifier(result, "ctrl", Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL);
        addModifier(result, "alt", Keyboard.KEY_LMENU, Keyboard.KEY_RMENU);
        result.addProperty("source", "Read-only physical key state; no simulated modifier presses.");
        return result;
    }

    private static void addModifier(JsonObject result, String name, int left, int right) {
        Boolean a = physical(left), b = physical(right);
        result.add(name, a == null || b == null ? JsonNull.INSTANCE : new JsonPrimitive(a || b));
    }
}

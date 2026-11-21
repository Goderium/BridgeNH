package com.Goderium.BridgeNH.packtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.BufferedWriter;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invoked once on the client tick; uses the same actual item getter and scoped modifier hook as NEI panel detail. */
final class SelectedTooltips {
    private final Map<String, List<Object>> stacks = new LinkedHashMap<>();
    private final Map<Object, Map<Integer, String>> wanted = new HashMap<>();
    private final JsonArray missing = new JsonArray();

    void capture(Object minecraft, File out, String selections, String modes) throws Exception {
        Class<?> stackType = Class.forName("net.minecraft.item.ItemStack");
        Method getItem = stackType.getMethod("func_77973_b"), getMeta = stackType.getMethod("func_77960_j");
        Class<?> itemType = Class.forName("net.minecraft.item.Item");
        Object registry = itemType.getField("field_150901_e").get(null);
        Method get = registry.getClass().getMethod("func_82594_a", String.class);
        for (String key : selections.split("\n")) {
            int at = key.lastIndexOf('@');
            Object item = get.invoke(registry, key.substring(0, at));
            stacks.put(key, new ArrayList<>());
            if (item != null) wanted.computeIfAbsent(item, ignored -> new HashMap<>())
                .put(Integer.parseInt(key.substring(at + 1)), key);
        }
        for (Object stack : (Collection<?>)Class.forName("codechicken.nei.ItemList").getField("items").get(null)) {
            if (stack == null) continue;
            Map<Integer, String> matches = wanted.get(getItem.invoke(stack));
            if (matches == null) continue;
            String id = matches.get((Integer)getMeta.invoke(stack));
            if (id != null) stacks.get(id).add(stack);
        }
        Object manager = minecraft.getClass().getMethod("func_135016_M").invoke(minecraft);
        Object language = manager.getClass().getMethod("func_135041_c").invoke(manager);
        String lang = (String)language.getClass().getMethod("func_135034_a").invoke(language);
        Object player = minecraft.getClass().getField("field_71439_g").get(minecraft);
        Method getter = stackType.getMethod("func_82840_a", Class.forName("net.minecraft.entity.player.EntityPlayer"), boolean.class);
        Method enter = null;
        JsonObject diagnostics = new JsonObject();
        JsonObject sources = new JsonObject();
        try {
            Class<?> keys = Class.forName("com.Goderium.BridgeNH.loader.rfb.TooltipKeyScope");
            // This method is called from ClientTickEvent.END, as is NEI panel-detail inspection.
            keys.getMethod("bindClientThread").invoke(null);
            Class<?> mods = Class.forName("com.Goderium.BridgeNH.client.nei.TooltipModifiers");
            mods.getMethod("probe").invoke(null);
            diagnostics = (JsonObject)mods.getMethod("diagnostics").invoke(null);
            if (diagnostics.get("clientThread").getAsBoolean() && diagnostics.get("hookObserved").getAsBoolean())
                enter = keys.getMethod("enter", Boolean.class, Boolean.class, Boolean.class);
        } catch (ReflectiveOperationException | LinkageError unavailable) { diagnostics.addProperty("reason", unavailable.toString()); }
        int count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(new File(out, "tooltip-samples.jsonl").toPath(), StandardCharsets.UTF_8)) {
            for (Map.Entry<String,List<Object>> pair : stacks.entrySet()) for (String mode : modes.split(",")) {
                String key = pair.getKey(), reason = null;
                if (pair.getValue().size() != 1) reason = pair.getValue().isEmpty() ? "not_in_nei_item_list" : "ambiguous_nei_variants";
                if (reason == null) {
                    try (TooltipKeys keys = TooltipKeys.sample(mode, enter)) {
                        List<?> actual = (List<?>)getter.invoke(pair.getValue().get(0), player, false);
                        if (actual.size() > 512) throw new IllegalStateException("too_many_lines");
                        JsonArray lines = new JsonArray();
                        int size = 0;
                        for (Object line : actual) {
                            if (!(line instanceof String) || (size += ((String)line).length()) > 65536)
                                throw new IllegalStateException("oversized_or_nontext_tooltip");
                            lines.add(new JsonPrimitive((String)line));
                        }
                        int at = key.lastIndexOf('@');
                        JsonObject row = new JsonObject();
                        row.addProperty("id", key.substring(0, at)); row.addProperty("meta", Integer.parseInt(key.substring(at+1)));
                        row.addProperty("mode", mode); row.addProperty("language", lang); row.add("lines", lines);
                        writer.write(row.toString()); writer.newLine(); count++;
                        sources.addProperty(mode,keys.source);
                    } catch (Exception | LinkageError failure) { reason = "capture_failed:" + failure.toString(); }
                }
                if (reason != null) {
                    JsonObject absence = new JsonObject();
                    absence.addProperty("item", key); absence.addProperty("mode", mode); absence.addProperty("reason", reason);
                    missing.add(absence);
                }
            }
        }
        JsonObject coverage = new JsonObject();
        coverage.addProperty("lang", lang); coverage.addProperty("samples", count); coverage.add("missing", missing);
        coverage.add("modifierDiagnostics", diagnostics);
        coverage.add("modifierSources", sources);
        coverage.addProperty("source", "NEI ItemList actual stack, ItemStack.getTooltip(player,false); observed Keyboard.isKeyDown readback");
        Files.write(new File(out, "tooltip-sample-report.json").toPath(), coverage.toString().getBytes(StandardCharsets.UTF_8));
    }
}

package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.item.ItemStack;

/** Native public inventory purposes, independent of consumed-input and candidate acceptance contracts. */
final class MachineSlotPurpose {
    private static final String BASIC = "gregtech.api.metatileentity.implementations.MTEBasicMachine";
    private static final String META = "gregtech.api.metatileentity.MetaTileEntity";
    private static final String CIRCUIT = "gregtech.api.interfaces.IConfigurationCircuitSupport";

    static void unavailable(JsonObject entry, String reason) {
        entry.addProperty("machineInventoryPurpose", "unavailable");
        entry.addProperty("machineInventoryPurposeVerified", false);
        entry.addProperty("machineInventoryPurposeCoverageComplete", false);
        entry.addProperty("machineInventoryPurposeReason", reason);
    }

    static void describe(JsonObject entry, Object meta, int index, int size) {
        unavailable(entry, "public_slot_purpose_api_unavailable");
        if (index < 0 || index >= size || size < 1 || size > 256) {
            entry.addProperty("machineInventoryPurposeReason", "actual_inventory_index_out_of_range"); return;
        }
        Evidence evidence = new Evidence(index, size);
        if (ExpandedGuiApi.is(meta, BASIC)) {
            evidence.supported = true;
            Integer input = evidence.integer(meta, "getInputSlot");
            Integer output = evidence.integer(meta, "getOutputSlot");
            JsonObject inputRange = evidence.range("input", "MTEBasicMachine.getInputSlot/getOutputSlot/isIOSlot", input,
                    input == null || output == null ? null : (long) output - input);
            if (inputRange.get("available").getAsBoolean() && inputRange.get("matches").getAsBoolean()) {
                try {
                    Object io = ExpandedGuiApi.call(meta, "isIOSlot", index);
                    if (Boolean.TRUE.equals(io)) evidence.claim("input", inputRange);
                    else evidence.fail(inputRange, io instanceof Boolean ? "native_io_role_conflicts_with_input_range" : "public_io_role_not_boolean");
                } catch (RuntimeException | LinkageError error) { evidence.fail(inputRange, "public_io_role_api_unavailable"); }
            }
            Integer outputs = null;
            try {
                // GT's final array is allocated from aOutputSlotCount in each constructor; only its fixed length is read.
                Object items = meta.getClass().getField("mOutputItems").get(meta);
                if (items instanceof ItemStack[]) outputs = ((ItemStack[]) items).length;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { /* Explicit unavailable range below. */ }
            evidence.claimRange("output", "MTEBasicMachine.getOutputSlot/public mOutputItems.length", output,
                    outputs == null ? null : outputs.longValue());
            evidence.claimRange("special", "MTEBasicMachine.getSpecialSlotIndex", evidence.integer(meta, "getSpecialSlotIndex"), 1L);
        }
        if (ExpandedGuiApi.is(meta, CIRCUIT)) {
            evidence.supported = true;
            evidence.claimRange("configuration_circuit", "IConfigurationCircuitSupport.getCircuitSlot", evidence.integer(meta, "getCircuitSlot"), 1L);
        }
        if (ExpandedGuiApi.is(meta, META) || ExpandedGuiApi.is(meta, BASIC)) {
            evidence.supported = true;
            JsonObject charging = evidence.claimRange("battery", "MetaTileEntity.rechargerSlotStartIndex/rechargerSlotCount",
                    evidence.integer(meta, "rechargerSlotStartIndex"), evidence.count(meta, "rechargerSlotCount"));
            JsonObject discharging = evidence.claimRange("battery", "MetaTileEntity.dechargerSlotStartIndex/dechargerSlotCount",
                    evidence.integer(meta, "dechargerSlotStartIndex"), evidence.count(meta, "dechargerSlotCount"));
            if (evidence.claims.containsKey("battery")) {
                entry.addProperty("machineInventoryBatteryCharging", charging.get("available").getAsBoolean() && charging.get("matches").getAsBoolean());
                entry.addProperty("machineInventoryBatteryDischarging", discharging.get("available").getAsBoolean() && discharging.get("matches").getAsBoolean());
            }
        }
        entry.add("machineInventoryPurposeEvidence", evidence.ranges);
        entry.addProperty("machineInventoryPurposeCoverageComplete", evidence.supported && evidence.complete);
        JsonArray candidates = new JsonArray();
        for (String role : evidence.claims.keySet()) candidates.add(new JsonPrimitive(role));
        entry.add("machineInventoryPurposeCandidates", candidates);
        if (evidence.claims.size() == 1) {
            Map.Entry<String, JsonArray> claim = evidence.claims.entrySet().iterator().next();
            entry.addProperty("machineInventoryPurpose", claim.getKey());
            entry.addProperty("machineInventoryPurposeVerified", true);
            entry.add("machineInventoryPurposeSources", claim.getValue());
            entry.remove("machineInventoryPurposeReason");
        } else {
            entry.addProperty("machineInventoryPurposeReason", evidence.claims.size() > 1 ? "overlapping_public_slot_purposes"
                    : evidence.supported ? "no_verified_public_slot_purpose_for_index" : "public_slot_purpose_api_unavailable");
        }
    }

    private static final class Evidence {
        final int index, size;
        final JsonArray ranges = new JsonArray();
        final Map<String, JsonArray> claims = new LinkedHashMap<String, JsonArray>();
        boolean supported, complete = true;

        Evidence(int index, int size) { this.index = index; this.size = size; }

        Integer integer(Object meta, String api) {
            try {
                Object value = ExpandedGuiApi.call(meta, api);
                return value instanceof Integer || value instanceof Short || value instanceof Byte ? ((Number) value).intValue() : null;
            } catch (RuntimeException | LinkageError error) { return null; }
        }
        Long count(Object meta, String api) { Integer value = integer(meta, api); return value == null ? null : value.longValue(); }

        JsonObject claimRange(String role, String api, Integer start, Long count) {
            JsonObject range = range(role, api, start, count);
            if (range.get("available").getAsBoolean() && range.get("matches").getAsBoolean()) claim(role, range);
            return range;
        }

        JsonObject range(String role, String api, Integer start, Long count) {
            JsonObject range = new JsonObject(); range.addProperty("purpose", role); range.addProperty("api", api);
            range.addProperty("available", false); range.addProperty("matches", false);
            if (start != null) range.addProperty("start", start);
            if (count != null) range.addProperty("count", count);
            ranges.add(range);
            if (start == null || count == null) fail(range, "public_boundary_api_unavailable");
            else if (start < 0) fail(range, "negative_public_range_start");
            else if (count < 0) fail(range, "negative_public_range_count");
            else if ((long) start + count > size) fail(range, "public_range_outside_actual_inventory");
            else {
                range.addProperty("available", true);
                range.addProperty("matches", index >= start && index < (long) start + count);
            }
            return range;
        }

        void claim(String role, JsonObject range) {
            JsonArray sources = claims.get(role);
            if (sources == null) { sources = new JsonArray(); claims.put(role, sources); }
            sources.add(range.get("api"));
        }

        void fail(JsonObject range, String reason) {
            complete = false; range.addProperty("available", false); range.addProperty("matches", false); range.addProperty("reason", reason);
        }
    }
}

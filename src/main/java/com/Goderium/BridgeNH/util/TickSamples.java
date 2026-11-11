package com.Goderium.BridgeNH.util;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Bounded server execution-duration samples, independent of Minecraft internals. */
public class TickSamples {
    private final long[] durations = new long[128];
    private Object source;
    private int size;
    private int cursor;
    private long lastSample;

    public synchronized void record(Object source, long duration, long now) {
        if (this.source != source) { this.source = source; size = 0; cursor = 0; }
        durations[cursor] = Math.max(0, duration);
        cursor = (cursor + 1) % durations.length;
        size = Math.min(size + 1, durations.length);
        lastSample = now;
    }

    public synchronized JsonObject inspect(Object source, boolean paused, long now) {
        boolean available = source != null && this.source == source && size > 0;
        JsonObject result = new JsonObject();
        result.addProperty("available", available);
        result.addProperty("paused", paused);
        result.addProperty("sampleCount", available ? size : 0);
        result.addProperty("windowCapacity", durations.length);
        if (available) {
            double total = 0;
            long maximum = 0;
            for (int i = 0; i < size; i++) { total += durations[i]; maximum = Math.max(maximum, durations[i]); }
            result.addProperty("meanMillis", total / size / 1000000D);
            result.addProperty("maxMillis", maximum / 1000000D);
            result.addProperty("lastSampleAgeMillis", Math.max(0, now - lastSample) / 1000000L);
        } else {
            result.add("meanMillis", JsonNull.INSTANCE);
            result.add("maxMillis", JsonNull.INSTANCE);
            result.add("lastSampleAgeMillis", JsonNull.INSTANCE);
        }
        result.addProperty("meaning", "Recent local server START-to-END tick execution durations before bridge queue work; excludes tick sleep, is not TPS. Paused servers may retain older samples or have no samples.");
        return result;
    }
}

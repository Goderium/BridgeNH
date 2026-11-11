package com.Goderium.BridgeNH.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/** Passive VM counters only; never forces collection or retains game/world objects. */
public final class JvmMemoryDiagnostics {
    public static JsonObject inspect() {
        JsonObject result = new JsonObject();
        JsonArray collectors = new JsonArray(), pools = new JsonArray();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", JsonData.bounded(collector.getName(), 128));
            value.addProperty("collectionCount", collector.getCollectionCount());
            value.addProperty("collectionTimeMillis", collector.getCollectionTime());
            collectors.add(value);
        }
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            JsonObject value = new JsonObject();
            value.addProperty("name", JsonData.bounded(pool.getName(), 128));
            MemoryUsage usage = pool.getCollectionUsage();
            value.addProperty("collectionUsageAvailable", usage != null);
            if (usage == null) value.add("afterLastCollection", JsonNull.INSTANCE);
            else {
                JsonObject after = new JsonObject();
                after.addProperty("usedBytes", usage.getUsed());
                after.addProperty("committedBytes", usage.getCommitted());
                after.addProperty("maxBytes", usage.getMax());
                value.add("afterLastCollection", after);
            }
            pools.add(value);
        }
        result.add("collectors", collectors); result.add("heapPools", pools);
        result.addProperty("nonAtomic", true);
        result.addProperty("forcedCollection", false);
        result.addProperty("scope", "Public JVM MXBean counters since process start and per-pool last observed collection usage; pool samples may belong to different collections. A heap rise alone is not proof of a leak.");
        return result;
    }
}

package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BoundedNbt;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.common.machine.WailaProviderNbt;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;

/** Waila's current cached target only. Uses isolated accessor/NBT snapshots and registered public providers. */
public class WailaDataAdapter {
    private static final String PREFIX = "mcp.mobius.waila.";
    public static final int PROVIDER_LIMIT = 64, LINE_LIMIT = 32;

    public static JsonObject inspect(Minecraft client, JsonObject args) {
        long started = System.nanoTime(); boolean include = flag(args, "includeNbt"), advanced = flag(args, "advanced");
        try {
            ClassLoader loader = WailaDataAdapter.class.getClassLoader();
            Class<?> common = Class.forName(PREFIX + "api.impl.DataAccessorCommon", false, loader);
            Object cached = common.getField("instance").get(null);
            if (cached == null || common.getField("world").get(cached) != client.theWorld || common.getField("player").get(cached) != client.thePlayer)
                throw new BridgeException("not_found", "Waila has no cached target for the current world/player.");
            Class<?> rays = Class.forName(PREFIX + "overlay.RayTracing", false, loader);
            Object ray = AdapterAccess.invoke(rays, null, "instance", new Class<?>[0], false);
            Object target = AdapterAccess.call(ray, "getTarget");
            Object position = common.getField("mop").get(cached);
            if (!(position instanceof MovingObjectPosition) || !(target instanceof MovingObjectPosition)
                    || !same((MovingObjectPosition) position, (MovingObjectPosition) target))
                throw new BridgeException("not_found", "Waila cached accessor does not match its current visible target.");
            MovingObjectPosition mop = (MovingObjectPosition) position;
            JsonObject result = new JsonObject(); JsonObject identity = new JsonObject();
            identity.addProperty("type", mop.typeOfHit.name());
            if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                identity.addProperty("x", mop.blockX); identity.addProperty("y", mop.blockY); identity.addProperty("z", mop.blockZ);
                identity.addProperty("side", mop.sideHit); identity.addProperty("dimension", client.theWorld.provider.dimensionId);
                identity.addProperty("registryName", String.valueOf(common.getField("blockResource").get(cached)));
                identity.addProperty("metadata", ((Number) common.getField("metadata").get(cached)).intValue());
            } else if (mop.entityHit != null) { identity.addProperty("entityId", mop.entityHit.getEntityId()); identity.addProperty("entityClass", mop.entityHit.getClass().getName()); }
            else throw new BridgeException("not_found", "Waila cache has no block or entity target.");
            result.add("cacheTarget", identity); result.addProperty("matchesVanillaCrosshair", same(mop, client.objectMouseOver));
            long timer = ((Number) common.getField("timeLastUpdate").get(cached)).longValue();
            result.addProperty("cacheTimerMillis", timer); result.addProperty("cacheTimerAgeMillis", Math.max(0, System.currentTimeMillis() - timer));
            result.addProperty("cacheTimerMeaning", "Waila request throttle timer, not payload-received time; exact NBT age is unavailable");
            NBTTagCompound remote = (NBTTagCompound) common.getField("remoteNbt").get(cached);
            boolean matchingNbt = matchesNbt(remote, mop);
            BoundedNbt.Snapshot snapshot = BoundedNbt.snapshot(matchingNbt ? remote : null);
            result.addProperty("nbtCoordinatesMatch", matchingNbt); result.addProperty("nbtSnapshotTruncated", snapshot.truncated);
            result.addProperty("nbtPayloadReceivedAt", "unavailable");
            if (include && matchingNbt) result.add("nbt", BoundedNbt.describe(remote));
            // The external preview is deliberately small. Providers need the complete, unmodified payload.
            final WailaProviderNbt providerNbt = WailaProviderNbt.snapshot(matchingNbt ? remote : null);
            result.add("providerNbt", providerNbt.describe());
            Class<?> blockAccessor = Class.forName(PREFIX + "api.IWailaDataAccessor", false, loader);
            Class<?> entityAccessor = Class.forName(PREFIX + "api.IWailaEntityAccessor", false, loader);
            Object accessor = Proxy.newProxyInstance(loader, new Class<?>[] {blockAccessor, entityAccessor}, (proxy, method, values) -> {
                if ("getNBTData".equals(method.getName())) {
                    return providerNbt.copy();
                }
                if ("toString".equals(method.getName())) return "Codex bounded Waila cached accessor";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == values[0];
                // Only the fixed public accessor getter interface reaches this branch, never set()/writeToNBT().
                Object value = common.getMethod(method.getName(), method.getParameterTypes()).invoke(cached, values);
                return value instanceof ItemStack ? ((ItemStack) value).copy() : value;
            });
            Class<?> configApi = Class.forName(PREFIX + "api.IWailaConfigHandler", false, loader);
            Class<?> configClass = Class.forName(PREFIX + "api.impl.ConfigHandler", false, loader);
            Object config = AdapterAccess.invoke(configClass, null, "instance", new Class<?>[0], false);
            Class<?> registrarClass = Class.forName(PREFIX + "api.impl.ModuleRegistrar", false, loader);
            Object registrar = AdapterAccess.invoke(registrarClass, null, "instance", new Class<?>[0], false);
            boolean entity = mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
            Object block = common.getField("block").get(cached), tile = common.getField("tileEntity").get(cached);
            ItemStack stack = (ItemStack) common.getField("stack").get(cached);
            Object identifier = entity ? AdapterAccess.call(ray, "getTargetEntity") : AdapterAccess.call(ray, "getTargetStack");
            if (identifier == null) identifier = entity ? mop.entityHit : stack;
            if (!entity && identifier instanceof ItemStack) stack = (ItemStack) identifier;
            result.add("item", GTRecipeAdapter.value(stack, 0));
            JsonObject sections = new JsonObject(); JsonArray providers = new JsonArray(); int[] visited = {0};
            for (String[] section : new String[][] {{"body", "Body"}, {"head", "Head"}, {"tail", "Tail"}}) {
                List<String> lines = new ArrayList<>(); boolean partial = false;
                Class<?> direct = Class.forName(PREFIX + "api.IWailaBlock", false, loader);
                if (!entity && direct.isInstance(block)) {
                    lines = invokeProvider(block, direct, "getWaila" + section[1], stack, lines, accessor, config, blockAccessor, configApi, providers, visited);
                } else {
                    String suffix = entity ? "EntityProviders" : "Providers";
                    Map<Integer, Object> collected = new TreeMap<>();
                    collectProviders(collected, AdapterAccess.call(registrar, "get" + section[1] + suffix, new Class<?>[] {Object.class}, entity ? mop.entityHit : block));
                    if (!entity && tile != null) collectProviders(collected, AdapterAccess.call(registrar, "get" + section[1] + suffix, new Class<?>[] {Object.class}, tile));
                    Class<?> provider = Class.forName(PREFIX + "api." + (entity ? "IWailaEntityProvider" : "IWailaDataProvider"), false, loader);
                    for (Object value : collected.values()) {
                        if (!(value instanceof List<?>)) continue;
                        for (Object object : (List<?>) value) {
                            if (visited[0] >= PROVIDER_LIMIT || (visited[0] > 0 && GTRecipeAdapter.expired(started))) { partial = true; break; }
                            lines = invokeProvider(object, provider, "getWaila" + section[1], entity ? identifier : stack, lines, accessor, config,
                                    entity ? entityAccessor : blockAccessor, configApi, providers, visited);
                            if (advanced && !entity && "body".equals(section[0]) && visited[0] < PROVIDER_LIMIT) {
                                try {
                                    boolean has = Boolean.TRUE.equals(provider.getMethod("hasWailaAdvancedBody",
                                            ItemStack.class, blockAccessor, configApi).invoke(object, stack == null ? null : stack.copy(), accessor, config));
                                    if (has) lines = invokeProvider(object, provider, "getWailaAdvancedBody", stack, lines, accessor, config, blockAccessor, configApi, providers, visited);
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                                    partial = true; JsonObject failure = new JsonObject();
                                    failure.addProperty("class", object.getClass().getName()); failure.addProperty("callback", "hasWailaAdvancedBody");
                                    providerFailure(failure, error); providers.add(failure);
                                }
                            }
                        }
                        if (partial) break;
                    }
                }
                JsonObject text = new JsonObject(); GuiDataAdapter.text(text, lines); text.addProperty("providerBudgetPartial", partial);
                sections.add(section[0], text);
            }
            result.add("sections", sections); result.add("providers", providers); result.addProperty("advancedRequested", advanced);
            summarizeCompleteness(result);
            result.addProperty("source", "Current Waila cached target; registered public tooltip providers with matching isolated NBT snapshot, no refresh request or tile serialization");
            result.addProperty("coverage", "Block/entity providers and legacy IWailaBlock; FMP-specific renderer/layout and physical advanced-key presentation are not reconstructed");
            result.add("budget", StructureAdapter.budget(started)); result.addProperty("providerLimit", PROVIDER_LIMIT);
            result.addProperty("nonAtomic", true); GTRecipeAdapter.bound(result, 128 * 1024); return result;
        } catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Waila cached public API is unavailable: " + error.getClass().getSimpleName()); }
        catch (LinkageError error) { throw new BridgeException("unsupported", "Waila is unavailable."); }
    }
    @com.google.common.annotations.VisibleForTesting
    public static List<String> invokeProvider(Object target, Class<?> api, String method, Object item, List<String> lines, Object accessor,
            Object config, Class<?> accessorApi, Class<?> configApi, JsonArray providers, int[] visited) {
        JsonObject report = new JsonObject(); report.addProperty("class", target.getClass().getName()); report.addProperty("callback", method); visited[0]++;
        try {
            List<String> working = copyTooltip(lines);
            boolean entity = item instanceof Entity;
            Object value = api.getMethod(method, entity ? Entity.class : ItemStack.class, List.class, accessorApi, configApi).invoke(target,
                    item instanceof ItemStack ? ((ItemStack) item).copy() : item, working, accessor, config);
            List<?> returned = value instanceof List<?> ? (List<?>) value : working;
            List<String> bounded = copyTooltip(returned);
            boolean truncated = returned.size() > LINE_LIMIT;
            for (int i = 0; i < Math.min(returned.size(), LINE_LIMIT); i++) truncated |= String.valueOf(returned.get(i)).length() > 512;
            report.addProperty("available", true); report.addProperty("returnedLines", returned.size()); report.addProperty("truncated", truncated);
            providers.add(report); return bounded;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { providerFailure(report, error); providers.add(report); return lines; }
    }
    private static void providerFailure(JsonObject report, Throwable failure) {
        report.addProperty("available", false);
        Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null ? failure.getCause() : failure;
        report.addProperty("error", "Waila callback failed: " + cause.getClass().getName());
        JsonArray causes = new JsonArray();
        for (int depth = 0; cause != null && depth < 4; depth++) {
            JsonObject detail = new JsonObject(); detail.addProperty("type", cause.getClass().getName());
            if (cause instanceof BridgeException) detail.addProperty("code", JsonData.bounded(((BridgeException) cause).code, 64));
            JsonArray frames = new JsonArray();
            StackTraceElement[] stack = cause.getStackTrace();
            for (int i = 0; i < Math.min(4, stack.length); i++) frames.add(new JsonPrimitive(JsonData.bounded(stack[i].toString(), 240)));
            detail.add("frames", frames); causes.add(detail);
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        report.add("errorDetails", causes); // No provider messages or cached NBT in error diagnostics.
    }
    @com.google.common.annotations.VisibleForTesting
    public static void summarizeCompleteness(JsonObject result) {
        int failed = 0, truncated = 0; boolean budgetPartial = false;
        for (JsonElement value : result.getAsJsonArray("providers")) {
            JsonObject provider = value.getAsJsonObject();
            if (!provider.get("available").getAsBoolean()) failed++;
            if (provider.has("truncated") && provider.get("truncated").getAsBoolean()) truncated++;
        }
        for (Map.Entry<String, JsonElement> entry : result.getAsJsonObject("sections").entrySet()) {
            JsonObject section = entry.getValue().getAsJsonObject();
            budgetPartial |= section.get("providerBudgetPartial").getAsBoolean();
        }
        boolean complete = failed == 0 && truncated == 0 && !budgetPartial;
        result.addProperty("complete", complete); result.addProperty("partial", !complete);
        result.addProperty("status", complete ? "complete" : "partial");
        JsonObject summary = new JsonObject(); summary.addProperty("failedCallbacks", failed);
        summary.addProperty("truncatedCallbacks", truncated); summary.addProperty("providerBudgetPartial", budgetPartial);
        summary.addProperty("scope", "registered provider text only; not pixel layout, payload freshness or current authoritative machine state");
        result.add("completeness", summary);
    }
    @SuppressWarnings("unchecked")
    private static List<String> copyTooltip(List<?> source) {
        try {
            ClassLoader loader = WailaDataAdapter.class.getClassLoader();
            Class<?> taggedApi = Class.forName(PREFIX + "api.ITaggedList", false, loader);
            Class<?> implementation = Class.forName(PREFIX + "api.impl.TipList", false, loader);
            List<String> copied = (List<String>) implementation.getConstructor().newInstance();
            for (int i = 0; i < Math.min(source.size(), LINE_LIMIT); i++) {
                String line = JsonData.bounded(String.valueOf(source.get(i)), 512);
                Object tags = taggedApi.isInstance(source)
                        ? AdapterAccess.invoke(taggedApi, source, "getTags", new Class<?>[] {int.class}, false, i) : null;
                if (tags instanceof Collection<?> && !((Collection<?>) tags).isEmpty()) {
                    // Keep provider tag semantics while isolating each callback's mutations.
                    List<Object> isolatedTags = new ArrayList<>();
                    for (Object tag : (Collection<?>) tags) {
                        if (isolatedTags.size() >= LINE_LIMIT) break;
                        isolatedTags.add(tag);
                    }
                    AdapterAccess.invoke(taggedApi, copied, "add", new Class<?>[] {Object.class, Collection.class}, false, line, isolatedTags);
                } else copied.add(line);
            }
            return copied;
        } catch (ReflectiveOperationException error) {
            throw new BridgeException("unsupported", "Waila public tagged tooltip API is unavailable: " + error.getClass().getSimpleName());
        }
    }
    private static void collectProviders(Map<Integer, Object> target, Object value) {
        if (!(value instanceof Map<?, ?>)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) if (entry.getKey() instanceof Integer) target.put((Integer) entry.getKey(), entry.getValue());
    }
    static boolean same(MovingObjectPosition first, MovingObjectPosition second) {
        if (first == null || second == null || first.typeOfHit != second.typeOfHit) return false;
        if (first.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return first.blockX == second.blockX && first.blockY == second.blockY && first.blockZ == second.blockZ;
        return first.entityHit != null && first.entityHit == second.entityHit;
    }
    @com.google.common.annotations.VisibleForTesting
    public static boolean matchesNbt(NBTTagCompound tag, MovingObjectPosition mop) {
        if (tag == null || mop == null) return false;
        if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return tag.hasKey("WailaX") && tag.getInteger("WailaX") == mop.blockX
                && tag.getInteger("WailaY") == mop.blockY && tag.getInteger("WailaZ") == mop.blockZ;
        return mop.entityHit != null && tag.hasKey("WailaEntityID") && tag.getInteger("WailaEntityID") == mop.entityHit.getEntityId();
    }
    private static boolean flag(JsonObject args, String key) {
        if (!args.has(key)) return false;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args", key + " must be boolean.");
        return args.get(key).getAsBoolean();
    }
}

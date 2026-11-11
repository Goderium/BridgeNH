package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.common.machine.PhantomDisplayEvidence;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** One bounded client END-tick observer. No input, frame capture, or game-thread waiting. */
public final class GuiObservationWait {
    public interface Access { void check(); JsonObject read(); default JsonObject diagnostic() { return new JsonObject(); } default void close() {} }
    private final LongSupplier clock;
    private volatile Job active;
    public GuiObservationWait() { this(System::nanoTime); }
    GuiObservationWait(LongSupplier clock) { this.clock = clock; }

    public static final class Request {
        final int millis;
        final List<JsonObject> conditions = new ArrayList<JsonObject>();
        public Request(JsonObject value) {
            ExpandedGuiApi.keys(value, "timeoutMillis", "conditions");
            millis = value.has("timeoutMillis") ? JsonData.integer(value, "timeoutMillis", 1, 6500) : 2000;
            if (value.has("conditions")) {
                if (!value.get("conditions").isJsonArray() || value.getAsJsonArray("conditions").size() > 8)
                    throw new BridgeException("invalid_args", "GUI waiting accepts at most eight scalar predicates.");
                for (JsonElement entry : value.getAsJsonArray("conditions")) {
                    if (!entry.isJsonObject()) throw new BridgeException("invalid_args", "A GUI predicate object is required.");
                    JsonObject condition = entry.getAsJsonObject(); ExpandedGuiApi.keys(condition, "path", "operator", "value");
                    String path = JsonData.string(condition, "path"), operator = JsonData.string(condition, "operator");
                    if (path.length() > 128 || !path.matches("(?:slots|visibleText|widgets|containerClass|totalSlots)(?:\\.[A-Za-z0-9_-]+)*")
                            || !("equals".equals(operator) || "contains".equals(operator)) || !condition.has("value")
                            || !condition.get("value").isJsonPrimitive() || condition.get("value").toString().length() > 1024
                            || "contains".equals(operator) && !condition.get("value").getAsJsonPrimitive().isString())
                        throw new BridgeException("invalid_args", "GUI predicates must use bounded observed content paths and scalar equals/contains.");
                    conditions.add(new com.google.gson.JsonParser().parse(condition.toString()).getAsJsonObject());
                }
            }
        }
        boolean matches(JsonObject observation) {
            for (JsonObject condition : conditions) {
                JsonElement value = path(observation, JsonData.string(condition, "path"));
                if (value == null || !value.isJsonPrimitive()) return false;
                JsonElement expected = condition.get("value");
                if ("equals".equals(JsonData.string(condition, "operator"))) {
                    // Gson numeric equality must never equate JSON boolean true with number 1.
                    if (value.getAsJsonPrimitive().isBoolean() != expected.getAsJsonPrimitive().isBoolean()
                            || value.getAsJsonPrimitive().isString() != expected.getAsJsonPrimitive().isString() || !value.equals(expected)) return false;
                } else if (!value.getAsJsonPrimitive().isString() || !value.getAsString().contains(expected.getAsString())) return false;
            }
            return true;
        }
        JsonArray unmet(JsonObject observation) {
            JsonArray result=new JsonArray();
            for (int i=0;i<conditions.size();i++) {
                JsonObject condition=conditions.get(i);
                JsonElement actual=path(observation,JsonData.string(condition,"path")), expected=condition.get("value");
                boolean equal=actual!=null && actual.isJsonPrimitive() && ("contains".equals(JsonData.string(condition,"operator"))
                        ? actual.getAsJsonPrimitive().isString() && actual.getAsString().contains(expected.getAsString())
                        : actual.getAsJsonPrimitive().isBoolean()==expected.getAsJsonPrimitive().isBoolean()
                            && actual.getAsJsonPrimitive().isString()==expected.getAsJsonPrimitive().isString() && actual.equals(expected));
                if (equal) continue;
                JsonObject detail=condition.deepCopy(); detail.addProperty("index",i);
                detail.addProperty("observed",actual!=null);
                if (actual!=null && actual.isJsonPrimitive()) {
                    if (actual.getAsJsonPrimitive().isString()) detail.addProperty("actual",JsonData.bounded(actual.getAsString(),1024));
                    else detail.add("actual",actual.deepCopy());
                }
                result.add(detail);
            }
            return result;
        }
    }
    private static JsonElement path(JsonElement value, String path) {
        for (String part : path.split("\\.")) {
            if (value == null) return null;
            if (value.isJsonObject()) value = value.getAsJsonObject().get(part);
            else if (value.isJsonArray() && part.matches("[0-9]{1,3}")) {
                int index = Integer.parseInt(part); value = index < value.getAsJsonArray().size() ? value.getAsJsonArray().get(index) : null;
            } else return null;
        }
        return value;
    }
    public synchronized Job register(Request request, Access access) {
        if (active != null) throw new BridgeException("busy", "A native GUI observation is already active.");
        Job job = new Job(request, access, clock.getAsLong()); active = job; return job;
    }
    public boolean observing() { return active != null; }
    public void tick() {
        Job job = active; if (job == null) return;
        if (job.future.isDone()) return; // Retain the owner until the final AFTER read consumes it.
        if (clock.getAsLong() >= job.deadline) { job.finish(false); return; }
        try {
            job.access.check(); JsonObject observation = job.access.read(); job.attempts++;
            job.access.check();
            if (observation != null) job.last = observation;
            if (clock.getAsLong() >= job.deadline) job.finish(false);
            else if (observation != null && job.request.matches(observation)) job.finish(true);
        } catch (RuntimeException | LinkageError error) { job.fail(error); }
    }
    public final class Job {
        final Request request; final Access access; final long started, deadline;
        volatile CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        boolean terminal;
        int attempts; JsonObject last;
        Job(Request request, Access access, long started) {
            this.request = request; this.access = access; this.started = started;
            deadline = started + TimeUnit.MILLISECONDS.toNanos(request.millis);
        }
        void finish(boolean matched) {
            JsonObject result = matched && last != null ? last : new JsonObject();
            JsonObject receipt = new JsonObject(); receipt.addProperty("version", 1); receipt.addProperty("matched", matched);
            receipt.addProperty("timedOut", !matched); receipt.addProperty("attempts", attempts);
            receipt.addProperty("elapsedMillis", (clock.getAsLong() - started) / 1000000D);
            receipt.addProperty("source", "normal_client_end_ticks"); receipt.addProperty("replayAllowed", false);
            receipt.add("diagnostic", access.diagnostic());
            receipt.addProperty("observationReady",last!=null); receipt.add("unmetConditions",request.unmet(last));
            receipt.addProperty("scope", "Fresh matching native container/window/full slot contents plus declared predicates; other custom GUI state is not inferred.");
            result.add("guiWait", receipt); future.complete(result);
        }
        /** Client owner thread only. A transient final mismatch resumes the same read-only deadline. */
        public JsonObject verifyFinal(JsonObject candidate) {
            synchronized (GuiObservationWait.this) {
                if (terminal || active != this)
                    throw new BridgeException("cancelled", "GUI observation ended before the final receipt; never replay use.");
                access.check(); JsonObject fresh = access.read(); attempts++; access.check();
                boolean previouslyMatched = candidate.getAsJsonObject("guiWait").get("matched").getAsBoolean();
                boolean matched = previouslyMatched && fresh != null && request.matches(fresh) && clock.getAsLong() < deadline;
                last = fresh;
                if (!matched && previouslyMatched && clock.getAsLong() < deadline) {
                    future = new CompletableFuture<JsonObject>();
                    return null;
                }
                // Build the receipt from this read, never retain obsolete synchronized slots on failure.
                JsonObject result = fresh == null ? new JsonObject() : fresh;
                JsonObject receipt = new JsonObject(); receipt.addProperty("version", 1);
                receipt.addProperty("matched", matched); receipt.addProperty("timedOut", !matched);
                receipt.addProperty("attempts", attempts); receipt.addProperty("finalReadFresh", fresh != null);
                receipt.addProperty("elapsedMillis", (clock.getAsLong() - started) / 1000000D);
                receipt.addProperty("source", "normal_client_end_ticks"); receipt.addProperty("replayAllowed", false);
                receipt.add("diagnostic", access.diagnostic());
                receipt.addProperty("observationReady",fresh!=null); receipt.add("unmetConditions",request.unmet(fresh));
                receipt.addProperty("scope", "Fresh matching native container/window/full slot contents plus declared predicates; other custom GUI state is not inferred.");
                result.add("guiWait", receipt); terminal = true; active = null; access.close(); return result;
            }
        }
        void fail(Throwable error) {
            synchronized (GuiObservationWait.this) {
                terminal = true; if (active == this) active = null; access.close();
                future.completeExceptionally(error);
            }
        }
        public void cancel() { fail(new BridgeException("cancelled", "GUI observation cancelled; never replay the preceding use.")); }
        public JsonObject await(long millis) throws ExecutionException, InterruptedException {
            try { return future.get(millis, TimeUnit.MILLISECONDS); }
            catch (TimeoutException error) { cancel(); throw new BridgeException("timeout", "GUI observation expired; never replay the preceding use."); }
        }
    }

    /** Immutable full copies made only by the container's owner thread, retained only while waiting. */
    public static final class ContainerEvidence {
        public final String className; public final int window; public final ItemStack[] slots, ghosts; public final String[] ghostKeys, fluidKeys; final FluidStack[] fluids; public final long captured;
        final Container owner; final boolean nativeAnnounced;
        public ContainerEvidence(Container container, long captured) { this(container, captured, false); }
        public ContainerEvidence(Container container, long captured, boolean nativeAnnounced) {
            owner = container; this.nativeAnnounced = nativeAnnounced;
            className = container.getClass().getName(); window = container.windowId; this.captured = captured;
            int count = container.inventorySlots.size(), chars = 0;
            if (count > 512) throw new BridgeException("result_too_large", "GUI synchronization is bounded to 512 complete native slots.");
            if (nativeAnnounced && container.inventoryItemStacks.size() != count)
                throw new BridgeException("unsupported", "Native announced slot snapshot does not match the current container layout.");
            slots = new ItemStack[count];
            for (int i = 0; i < count; i++) {
                ItemStack item = nativeAnnounced ? (ItemStack) container.inventoryItemStacks.get(i) : ((Slot) container.inventorySlots.get(i)).getStack(); slots[i] = item == null ? null : item.copy();
                if (item != null && item.hasTagCompound()) chars = Math.addExact(chars, item.getTagCompound().toString().length());
                if (chars > 262144) throw new BridgeException("result_too_large", "GUI full native NBT exceeds the bounded copy policy.");
            }
            List<Slot> phantom = ContainerSyncAdapter.phantomSlots(container);
            ghosts = new ItemStack[phantom.size()]; ghostKeys = new String[ghosts.length];
            for (int i=0;i<ghosts.length;i++) {
                Slot slot=phantom.get(i); ghostKeys[i]=ContainerSyncAdapter.phantomKey(slot);
                ItemStack item=slot.getStack(); ghosts[i]=item==null ? null : item.copy();
                if(item!=null && item.hasTagCompound()) chars=Math.addExact(chars,item.getTagCompound().toString().length());
                if(chars>262144) throw new BridgeException("result_too_large","GUI complete phantom NBT exceeds the bounded copy policy.");
            }
            java.util.Map<String,Object> fluidHandlers=ContainerSyncAdapter.phantomFluids(container);
            fluidKeys=fluidHandlers.keySet().toArray(new String[0]); fluids=new FluidStack[fluidKeys.length];
            for(int i=0;i<fluidKeys.length;i++) {
                fluids[i]=ContainerSyncAdapter.fluidValue(fluidHandlers.get(fluidKeys[i]));
                if(fluids[i]!=null && fluids[i].tag!=null) chars=Math.addExact(chars,fluids[i].tag.toString().length());
                if(chars>262144) throw new BridgeException("result_too_large","GUI complete phantom fluid NBT exceeds the bounded copy policy.");
            }
        }
        public JsonObject summary() {
            JsonObject result=new JsonObject(); result.addProperty("windowId",window); result.addProperty("containerClass",className);
            result.addProperty("slotCount",slots.length); result.addProperty("phantomItemCount",ghosts.length); result.addProperty("phantomFluidCount",fluids.length);
            GuiContainerDigest digest=new GuiContainerDigest(); digest.text(className); digest.integer(window); digest.integer(slots.length);
            for (ItemStack slot:slots) digest.item(slot);
            digest.integer(ghosts.length); for (int i=0;i<ghosts.length;i++) { digest.text(ghostKeys[i]); digest.item(ghosts[i]); }
            digest.integer(fluids.length); for (int i=0;i<fluids.length;i++) { digest.text(fluidKeys[i]); digest.fluid(fluids[i]); }
            result.addProperty("contentDigest",digest.finish());
            // Server emits raw section/entry digests only. Display queries are client-only.
            GuiContainerDigest nativeSlots = new GuiContainerDigest(), fluidSlots = new GuiContainerDigest();
            for (ItemStack slot : slots) nativeSlots.item(slot);
            for (int i=0;i<fluids.length;i++) { fluidSlots.text(fluidKeys[i]); fluidSlots.fluid(fluids[i]); }
            result.addProperty("sectionDigestVersion",1);
            result.addProperty("nativeSlotsDigest",nativeSlots.finish()); result.addProperty("phantomFluidsDigest",fluidSlots.finish());
            JsonArray items = new JsonArray();
            for (int i=0;i<ghosts.length;i++) {
                JsonObject entry=new JsonObject(); entry.addProperty("key",ghostKeys[i]); entry.addProperty("digest",itemDigest(ghosts[i])); items.add(entry);
            }
            result.add("phantomItems",items); return result;
        }
        public static boolean sameSummary(JsonObject expected,JsonObject actual) {
            for (String key:new String[]{"windowId","containerClass","slotCount","phantomItemCount","phantomFluidCount","contentDigest"})
                if (!expected.has(key) || !expected.get(key).equals(actual.get(key))) return false;
            return true;
        }
        JsonObject synchronization(String source,long age,boolean resynced) {
            return synchronization(source,age,resynced,0);
        }
        public JsonObject synchronization(String source,long age,boolean resynced,int displayInitializations) {
            if (displayInitializations<0) throw new BridgeException("invalid_state","Unmatched phantom contents cannot produce synchronization evidence.");
            JsonObject sync=new JsonObject(); sync.addProperty("fullNativeSlotsEqual",true); sync.addProperty("fullNbtCompared",true);
            sync.addProperty("serverSlotSnapshot","native_last_announced_full_nbt"); sync.addProperty("liveInventoryEqualityInferred",false);
            sync.addProperty("source",source); sync.addProperty("nonAtomic",true); sync.addProperty("serverSampleAgeMillis",age/1000000D);
            sync.addProperty("fullPhantomSlotsEqual",displayInitializations==0); sync.addProperty("phantomSlotCount",ghosts.length+fluids.length);
            sync.addProperty("phantomConfigurationConfirmed",true);
            if (displayInitializations>0) {
                sync.addProperty("phantomDisplayInitializationVersion",1);
                sync.addProperty("phantomDisplayInitializationVerified",true);
                sync.addProperty("phantomDisplayInitializedCount",displayInitializations);
                sync.addProperty("phantomComparison","exact_raw_NBT_or_native_durability_initialization_from_absent_or_empty_NBT_on_copies");
            }
            sync.addProperty("phantomItemSlotCount",ghosts.length); sync.addProperty("fullPhantomFluidSlotsEqual",true); sync.addProperty("phantomFluidSlotCount",fluids.length);
            sync.addProperty("phantomCoverage","complete_registered_mui2_item_slots_and_phantom_fluid_value_handlers"); sync.addProperty("phantomRegistryComplete",true);
            sync.addProperty("nativeResyncSubmitted",resynced); sync.addProperty("inputReplayed",false); return sync;
        }
        boolean matches(ContainerEvidence other) {
            if (other == null || window != other.window || !className.equals(other.className) || slots.length != other.slots.length) return false;
            for (int i = 0; i < slots.length; i++) if (!ItemStack.areItemStacksEqual(slots[i], other.slots[i])) return false;
            if (!java.util.Arrays.equals(ghostKeys,other.ghostKeys)) return false;
            for(int i=0;i<ghosts.length;i++) if(!ItemStack.areItemStacksEqual(ghosts[i],other.ghosts[i])) return false;
            if(!java.util.Arrays.equals(fluidKeys,other.fluidKeys)) return false;
            for(int i=0;i<fluids.length;i++) if(!fluidEquals(fluids[i],other.fluids[i])) return false;
            return true;
        }
        /** Client side only. Return -1 on any unproved content/layout difference. */
        public int displayInitializationCount(ContainerEvidence other) {
            if (other==null || window!=other.window || !className.equals(other.className) || slots.length!=other.slots.length
                    || !java.util.Arrays.equals(ghostKeys,other.ghostKeys) || !java.util.Arrays.equals(fluidKeys,other.fluidKeys)) return -1;
            for (int i=0;i<slots.length;i++) if (!ItemStack.areItemStacksEqual(slots[i],other.slots[i])) return -1;
            for (int i=0;i<fluids.length;i++) if (!fluidEquals(fluids[i],other.fluids[i])) return -1;
            int count=0;
            for (int i=0;i<ghosts.length;i++) if (!ItemStack.areItemStacksEqual(ghosts[i],other.ghosts[i])) {
                boolean verified=false;
                for (ItemStack source:PhantomDisplayEvidence.sources(ghosts[i])) if (ItemStack.areItemStacksEqual(source,other.ghosts[i])) { verified=true; break; }
                if (!verified) return -1;
                count++;
            }
            return count;
        }
        /** Same proof for remote servers, without invoking client display methods on the server. */
        public int displayInitializationCount(JsonObject local,JsonObject server) {
            if (server==null) return -1;
            for (String key:new String[]{"windowId","containerClass","slotCount","phantomItemCount","phantomFluidCount",
                    "sectionDigestVersion","nativeSlotsDigest","phantomFluidsDigest"})
                if (!local.has(key) || !local.get(key).equals(server.get(key))) return -1;
            if (!server.has("phantomItems") || !server.get("phantomItems").isJsonArray()
                    || server.getAsJsonArray("phantomItems").size()!=ghosts.length) return -1;
            int count=0;
            for (int i=0;i<ghosts.length;i++) {
                JsonElement raw=server.getAsJsonArray("phantomItems").get(i);
                if (!raw.isJsonObject()) return -1;
                JsonObject actual=raw.getAsJsonObject(), expected=local.getAsJsonArray("phantomItems").get(i).getAsJsonObject();
                if (!expected.get("key").equals(actual.get("key"))) return -1;
                if (expected.get("digest").equals(actual.get("digest"))) continue;
                boolean verified=false;
                for (ItemStack source:PhantomDisplayEvidence.sources(ghosts[i]))
                    if (new com.google.gson.JsonPrimitive(itemDigest(source)).equals(actual.get("digest"))) { verified=true; break; }
                if (!verified) return -1;
                count++;
            }
            return count;
        }
        private static String itemDigest(ItemStack value) { GuiContainerDigest digest=new GuiContainerDigest(); digest.item(value); return digest.finish(); }
        private static boolean fluidEquals(FluidStack a, FluidStack b) {
            return a==b || a!=null && b!=null && a.amount==b.amount && a.isFluidEqual(b);
        }
        public JsonObject diagnostic(ContainerEvidence other) {
            JsonObject result = new JsonObject(); result.addProperty("clientWindow", window); result.addProperty("clientContainerClass", className);
            result.addProperty("clientSlots", slots.length); result.addProperty("serverSampleAvailable", other != null);
            result.addProperty("clientPhantomSlots",ghosts.length+fluids.length); result.addProperty("clientPhantomItemSlots",ghosts.length);
            result.addProperty("phantomStockSemantics","configuration_only_not_consumable_inventory");
            result.addProperty("clientPhantomFluidSlots",fluids.length); result.addProperty("phantomFluidKeysComplete",true);
            result.addProperty("replayAllowed",false);
            if (other == null) { result.addProperty("reason", "server_sample_pending"); return result; }
            result.addProperty("serverSlotSnapshot", other.nativeAnnounced ? "native_last_announced_full_nbt" : "live_owner_thread_full_nbt");
            result.addProperty("serverWindow", other.window); result.addProperty("serverContainerClass", other.className); result.addProperty("serverSlots", other.slots.length);
            if (window != other.window) result.addProperty("reason", "window_mismatch");
            else if (!className.equals(other.className)) result.addProperty("reason", "container_class_mismatch");
            else if (slots.length != other.slots.length) result.addProperty("reason", "slot_layout_mismatch");
            else if (!java.util.Arrays.equals(ghostKeys,other.ghostKeys) || !java.util.Arrays.equals(fluidKeys,other.fluidKeys)) result.addProperty("reason","phantom_layout_mismatch");
            else {
                result.addProperty("reason", "synchronized");
                for (int i = 0; i < slots.length; i++) if (!ItemStack.areItemStacksEqual(slots[i], other.slots[i])) {
                    result.addProperty("reason", "slot_contents_pending"); result.addProperty("firstDifferentSlotIndex", i);
                    result.add("clientItem",JsonData.item(slots[i])); result.add("serverItem",JsonData.item(other.slots[i]));
                    result.addProperty("clientNbt", slots[i] == null || !slots[i].hasTagCompound() ? "" : JsonData.bounded(slots[i].getTagCompound().toString(), 2048));
                    result.addProperty("serverNbt", other.slots[i] == null || !other.slots[i].hasTagCompound() ? "" : JsonData.bounded(other.slots[i].getTagCompound().toString(), 2048));
                    result.addProperty("meaning","Client display differs from authoritative server contents; this is not proof that the preceding action failed."); return result;
                }
                for(int i=0;i<ghosts.length;i++) if(!ItemStack.areItemStacksEqual(ghosts[i],other.ghosts[i])) {
                    result.addProperty("reason","phantom_contents_pending"); result.addProperty("phantomKey",ghostKeys[i]);
                    result.add("clientItem",JsonData.item(ghosts[i])); result.add("serverItem",JsonData.item(other.ghosts[i]));
                    result.addProperty("clientNbt",ghosts[i]==null || !ghosts[i].hasTagCompound() ? "" : JsonData.bounded(ghosts[i].getTagCompound().toString(),2048));
                    result.addProperty("serverNbt",other.ghosts[i]==null || !other.ghosts[i].hasTagCompound() ? "" : JsonData.bounded(other.ghosts[i].getTagCompound().toString(),2048));
                    result.addProperty("meaning","Client phantom display differs from raw server configuration; neither this mismatch nor a callback receipt proves action failure."); break;
                }
                for(int i=0;i<fluids.length;i++) if(!fluidEquals(fluids[i],other.fluids[i])) {
                    result.addProperty("reason","phantom_fluid_contents_pending"); result.addProperty("phantomFluidKey",fluidKeys[i]);
                    result.add("clientFluid",fluid(fluids[i])); result.add("serverFluid",fluid(other.fluids[i])); break;
                }
            }
            return result;
        }
        private static JsonElement fluid(FluidStack value) {
            if(value==null || value.getFluid()==null) return com.google.gson.JsonNull.INSTANCE;
            JsonObject result=new JsonObject(); result.addProperty("fluidName",value.getFluid().getName()); result.addProperty("amount",value.amount);
            result.addProperty("hasTag",value.tag!=null); if(value.tag!=null) result.addProperty("nbt",JsonData.bounded(value.tag.toString(),2048)); return result;
        }
    }
}

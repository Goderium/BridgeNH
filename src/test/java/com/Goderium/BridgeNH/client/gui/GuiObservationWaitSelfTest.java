package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public class GuiObservationWaitSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0; remoteDigests(); AtomicLong clock = new AtomicLong(); GuiObservationWait waits = new GuiObservationWait(clock::get);
        JsonObject definition = json("{\"timeoutMillis\":100,\"conditions\":[{\"path\":\"slots.0.item.count\",\"operator\":\"equals\",\"value\":2}]}");
        final JsonObject[] current = {null}; final boolean[] changed = {false};
        GuiObservationWait.Access access = new GuiObservationWait.Access() {
            public void check() { if (changed[0]) throw new BridgeException("changed", "Changed actor."); }
            public JsonObject read() { return current[0]; }
        };
        GuiObservationWait.Job job = waits.register(new GuiObservationWait.Request(definition), access);
        waits.tick(); check(!job.future.isDone() && waits.observing(), "Opening delay is read-only waiting.");
        current[0] = json("{\"slots\":[{\"item\":{\"count\":1}}]}"); waits.tick(); check(!job.future.isDone(), "Wrong content does not complete.");
        current[0] = json("{\"slots\":[{\"item\":{\"count\":2}}]}"); waits.tick();
        check(job.future.isDone() && waits.observing(), "Tick acceptance retains the owner through the final read.");
        check(job.verifyFinal(job.await(1)).getAsJsonObject("guiWait").get("matched").getAsBoolean() && !waits.observing(), "Fresh final acceptance releases registration.");
        GuiObservationWait.Request request = new GuiObservationWait.Request(definition);
        check(!request.matches(json("{\"slots\":[{\"item\":{\"count\":true}}]}")), "Boolean is not numeric equality.");
        check(!request.matches(json("{\"slots\":[]}")), "Missing data remains unknown.");
        current[0] = null; job = waits.register(request, access); clock.set(100000001); waits.tick();
        check(job.verifyFinal(job.await(1)).getAsJsonObject("guiWait").get("timedOut").getAsBoolean() && !waits.observing(), "Deadline releases observer without replay.");
        job = waits.register(request, access); changed[0] = true; waits.tick();
        check(job.future.isCompletedExceptionally() && !waits.observing(), "Context change cancels acceptance."); changed[0] = false;
        job = waits.register(request, access); job.cancel(); check(!waits.observing() && job.future.isCompletedExceptionally(), "Cancellation releases all registration state.");
        for (String invalid : new String[] {"{\"conditions\":[{\"path\":\"screenOpen\",\"operator\":\"equals\",\"value\":true}]}",
                "{\"timeoutMillis\":6501}", "{\"conditions\":[{\"path\":\"slots\",\"operator\":\"contains\",\"value\":{}}]}"}) {
            try { new GuiObservationWait.Request(json(invalid)); throw new AssertionError("Invalid predicate accepted."); }
            catch (BridgeException expected) { checks++; }
        }
        transientFinalMismatch(); fullNativeSlots(); nativeAnnouncedSlots(); phantomEvidence(); phantomDisplayInitialization(); fluidEvidence(); unmetPredicates(); return checks;
    }
    private static void unmetPredicates() throws Exception {
        AtomicLong clock=new AtomicLong();GuiObservationWait waits=new GuiObservationWait(clock::get);
        JsonObject definition=json("{\"timeoutMillis\":100,\"conditions\":[{\"path\":\"slots.0.item.count\",\"operator\":\"equals\",\"value\":2}]}");
        GuiObservationWait.Job job=waits.register(new GuiObservationWait.Request(definition),new GuiObservationWait.Access(){
            public void check(){}
            public JsonObject read(){return json("{\"slots\":[{\"item\":{\"count\":1}}]}");}
        });
        waits.tick();clock.set(100000000);waits.tick();
        JsonObject receipt=job.verifyFinal(job.await(1)).getAsJsonObject("guiWait");
        check(receipt.get("observationReady").getAsBoolean() && !receipt.get("matched").getAsBoolean(),
                "Native content can be ready while a requested predicate is still unmet.");
        JsonObject unmet=receipt.getAsJsonArray("unmetConditions").get(0).getAsJsonObject();
        check(unmet.get("path").getAsString().equals("slots.0.item.count") && unmet.get("actual").getAsInt()==1 && unmet.get("value").getAsInt()==2,
                "Timeout preserves the exact unmet predicate and bounded observed scalar.");
    }
    private static void phantomDisplayInitialization() {
        Item item=new Item(){
            public boolean showDurabilityBar(ItemStack stack){
                if(!stack.hasTagCompound() || stack.getTagCompound().hasNoTags()) {
                    NBTTagCompound root=new NBTTagCompound(),wear=new NBTTagCompound();
                    wear.setLong("Damage",0);wear.setLong("MaxDamage",50);root.setTag("nativeDisplay",wear);stack.setTagCompound(root);
                }
                return false;
            }
        };
        Item.itemRegistry.addObject(31277,"mcb:phantom_display_fixture",item);
        InventoryBasic clientInventory=new InventoryBasic("display",true,1),serverInventory=new InventoryBasic("server",true,1);
        com.cleanroommc.modularui.screen.ModularContainer client=new com.cleanroommc.modularui.screen.ModularContainer(),server=new com.cleanroommc.modularui.screen.ModularContainer();
        client.addPhantom(new GhostSlot(clientInventory));server.addPhantom(new GhostSlot(serverInventory));client.windowId=server.windowId=9;
        ItemStack original=new ItemStack(item,1,7);serverInventory.setInventorySlotContents(0,original);
        GuiObservationWait.ContainerEvidence authority=new GuiObservationWait.ContainerEvidence(server,0);
        check(new GuiObservationWait.ContainerEvidence(client,0).displayInitializationCount(authority)<0,
                "A first-frame empty phantom list still waits for the real nonempty template.");
        ItemStack displayed=original.copy();item.showDurabilityBar(displayed);clientInventory.setInventorySlotContents(0,displayed);
        GuiObservationWait.ContainerEvidence local=new GuiObservationWait.ContainerEvidence(client,1);
        check(!local.matches(authority),"Render-initialized phantom NBT is still reported as different raw NBT.");
        check(local.displayInitializationCount(authority)==1,"Native copy-only default initialization proves phantom configuration without stripping arbitrary NBT.");
        check(local.displayInitializationCount(local.summary(),authority.summary())==1,
                "Remote raw entry digests validate the same native display initialization as integrated-server copies.");
        check(!original.hasTagCompound() && ItemStack.areItemStacksEqual(displayed,local.ghosts[0]),
                "The proof never initializes server stock or changes the displayed/captured item.");
        JsonObject sync=local.synchronization("fixture",0,false,1);
        check(!sync.get("fullPhantomSlotsEqual").getAsBoolean() && sync.get("phantomConfigurationConfirmed").getAsBoolean()
                && sync.get("phantomDisplayInitializedCount").getAsInt()==1,"Receipt distinguishes verified display initialization from raw phantom NBT equality.");
        JsonObject diagnostic=local.diagnostic(authority);
        check(diagnostic.get("clientNbt").getAsString().contains("nativeDisplay") && diagnostic.get("serverNbt").getAsString().isEmpty(),
                "Phantom mismatch diagnostics retain both bounded NBT values.");
        JsonObject oldServer=authority.summary();oldServer.remove("sectionDigestVersion");
        check(local.displayInitializationCount(local.summary(),oldServer)<0,"Older remote servers cannot accidentally authorize the new comparison path.");
        JsonObject wrongWindow=authority.summary();wrongWindow.addProperty("windowId",10);
        check(local.displayInitializationCount(local.summary(),wrongWindow)<0,"A display equivalent cannot cross native window identity.");
        original.setTagCompound(new NBTTagCompound());authority=new GuiObservationWait.ContainerEvidence(server,2);
        check(local.displayInitializationCount(authority)==1,"An explicitly empty server compound is separately reproduced and compared exactly.");
        displayed.getTagCompound().getCompoundTag("nativeDisplay").setLong("Damage",1);
        GuiObservationWait.ContainerEvidence worn=new GuiObservationWait.ContainerEvidence(client,3);
        check(worn.displayInitializationCount(authority)<0 && worn.displayInitializationCount(worn.summary(),authority.summary())<0,
                "Actual wear changes are not default initialization, locally or remotely.");
        displayed.getTagCompound().getCompoundTag("nativeDisplay").setLong("Damage",0);
        displayed.getTagCompound().setString("culture","unique-business-template");
        check(new GuiObservationWait.ContainerEvidence(client,4).displayInitializationCount(authority)<0,
                "A culture/custom NBT template cannot lose its meaningful tag under display compatibility.");
        original.setTagCompound((NBTTagCompound)displayed.getTagCompound().copy());authority=new GuiObservationWait.ContainerEvidence(server,5);
        check(new GuiObservationWait.ContainerEvidence(client,5).displayInitializationCount(authority)==0,
                "Exact full business NBT synchronization still succeeds without any normalization.");
        original.setTagCompound(null);displayed.getTagCompound().removeTag("culture");
        client.add(new Slot(clientInventory,0,0,0));server.add(new Slot(serverInventory,0,0,0));
        local=new GuiObservationWait.ContainerEvidence(client,6);authority=new GuiObservationWait.ContainerEvidence(server,6);
        check(local.displayInitializationCount(authority)<0 && local.displayInitializationCount(local.summary(),authority.summary())<0,
                "Real owned inventory slots never use phantom display equivalence even for the same item.");
    }
    private static void transientFinalMismatch() throws Exception {
        AtomicLong clock = new AtomicLong(); GuiObservationWait waits = new GuiObservationWait(clock::get);
        final JsonObject[] current = {json("{\"containerSynchronization\":{\"fullNativeSlotsEqual\":true},\"slots\":[]}")};
        GuiObservationWait.Access access = new GuiObservationWait.Access() {
            public void check() { }
            public JsonObject read() { return current[0]; }
            public JsonObject diagnostic() { return json(current[0] == null ? "{\"reason\":\"slot_contents_pending\"}" : "{\"reason\":\"synchronized\"}"); }
        };
        GuiObservationWait.Request request = new GuiObservationWait.Request(json("{\"timeoutMillis\":100}"));
        GuiObservationWait.Job job = waits.register(request, access); waits.tick(); JsonObject first = job.await(1);
        current[0] = null; clock.set(58000000);
        check(job.verifyFinal(first) == null && !job.future.isDone() && waits.observing(), "A 58ms transient final slot mismatch continues within the original 100ms deadline.");
        try { waits.register(request, access); throw new AssertionError("Competing observer accepted"); }
        catch (BridgeException expected) { check("busy".equals(expected.code), "Final-read retry keeps exclusive observer ownership."); }
        current[0] = json("{\"slots\":[]}"); clock.set(80000000); waits.tick();
        JsonObject accepted = job.verifyFinal(job.await(1));
        check(accepted.getAsJsonObject("guiWait").get("matched").getAsBoolean() && job.attempts == 4, "Later fresh synchronization completes without another use and accumulates all reads.");
        check(job.started == 0 && job.deadline == 100000000L && !waits.observing(), "The resumed observer never extends its original deadline.");
        clock.set(0); job = waits.register(request, access); waits.tick(); first = job.await(1);
        current[0] = null; clock.set(58000000); check(job.verifyFinal(first) == null, "Permanent mismatch initially stays read-only pending.");
        clock.set(100000000); waits.tick(); JsonObject expired = job.verifyFinal(job.await(1));
        check(expired.getAsJsonObject("guiWait").get("timedOut").getAsBoolean() && !expired.has("containerSynchronization") && !expired.has("slots"), "Only the real deadline times out, with no stale synchronized GUI payload.");
        check("slot_contents_pending".equals(expired.getAsJsonObject("guiWait").getAsJsonObject("diagnostic").get("reason").getAsString()), "Timeout diagnostic belongs to the final read.");
        clock.set(0); current[0] = json("{\"slots\":[]}"); job = waits.register(request, access); waits.tick(); first = job.await(1);
        current[0] = null; check(job.verifyFinal(first) == null, "Cancellation fixture rearms a pending final read.");
        job.cancel(); check(job.future.isCompletedExceptionally() && !waits.observing(), "Cancellation also terminates a rearmed observer.");
    }
    private static void fullNativeSlots() {
        final InventoryBasic inventory = new InventoryBasic("fixture", true, 1);
        Container container = new Container() {
            { addSlotToContainer(new Slot(inventory, 0, 0, 0)); }
            public boolean canInteractWith(EntityPlayer player) { return true; }
        };
        container.windowId = 7;
        ItemStack item = new ItemStack(new Item(), 2, 0); NBTTagCompound tag = new NBTTagCompound(); tag.setString("full", "before"); item.setTagCompound(tag);
        inventory.setInventorySlotContents(0, item);
        GuiObservationWait.ContainerEvidence before = new GuiObservationWait.ContainerEvidence(container, 0);
        check(before.matches(new GuiObservationWait.ContainerEvidence(container, 1)), "Independent full native copies match.");
        check("synchronized".equals(before.diagnostic(new GuiObservationWait.ContainerEvidence(container, 1)).get("reason").getAsString()), "Diagnostic synchronization needs full native equality.");
        item.getTagCompound().setString("full", "after");
        check(!before.matches(new GuiObservationWait.ContainerEvidence(container, 2)), "NBT change is detected and cannot mutate the retained copy.");
        JsonObject changed = before.diagnostic(new GuiObservationWait.ContainerEvidence(container, 2));
        check("slot_contents_pending".equals(changed.get("reason").getAsString()) && changed.get("firstDifferentSlotIndex").getAsInt() == 0, "Full NBT mismatch reports the actual differing slot without declaring success.");
        item.setTagCompound(tag); container.windowId = 8;
        check(!before.matches(new GuiObservationWait.ContainerEvidence(container, 3)), "Changed actual window is never synchronized evidence.");
        check("window_mismatch".equals(before.diagnostic(new GuiObservationWait.ContainerEvidence(container, 3)).get("reason").getAsString()), "Window mismatch retains a specific read-only diagnostic.");
        check(!before.matches(null), "Missing server evidence remains unknown.");
        check("server_sample_pending".equals(before.diagnostic(null).get("reason").getAsString()), "No sample never means an empty or synchronized server container.");
    }
    private static void nativeAnnouncedSlots() {
        InventoryBasic inventory = new InventoryBasic("native-snapshot", true, 1);
        Container container = new Container() {
            { addSlotToContainer(new Slot(inventory, 0, 0, 0)); }
            public boolean canInteractWith(EntityPlayer player) { return true; }
        };
        ItemStack item = new ItemStack(new Item(), 1, 0);
        NBTTagCompound tag = new NBTTagCompound(); tag.setString("dynamic", "announced"); item.setTagCompound(tag);
        inventory.setInventorySlotContents(0, item); container.detectAndSendChanges();
        GuiObservationWait.ContainerEvidence received = new GuiObservationWait.ContainerEvidence(container, 0);
        item.getTagCompound().setString("dynamic", "next-owner-tick");
        GuiObservationWait.ContainerEvidence sent = new GuiObservationWait.ContainerEvidence(container, 1, true);
        check(received.matches(sent), "Independent client evidence matches the native transmitted full NBT despite later owner mutation.");
        check(!received.matches(new GuiObservationWait.ContainerEvidence(container, 1)), "Live full NBT remains independently unequal; no volatile keys are stripped.");
        container.detectAndSendChanges();
        check(!received.matches(new GuiObservationWait.ContainerEvidence(container, 2, true)), "A new actual native announcement invalidates the prior received snapshot.");
        check(sent.owner == container && sent.nativeAnnounced, "Resynchronization is bound to the original actual server container identity.");
    }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    private static String digest(ItemStack value) { GuiContainerDigest digest=new GuiContainerDigest(); digest.item(value); return digest.finish(); }
    private static void remoteDigests() {
        Item item=new Item(); Item.itemRegistry.addObject(31111,"mcb:gui_digest_test",item);
        ItemStack first=new ItemStack(item,64,7), second=first.copy();
        NBTTagCompound a=new NBTTagCompound(), b=new NBTTagCompound();
        a.setString("Aa","one"); a.setString("BB","two"); a.setByteArray("bytes",new byte[]{1,2,3}); a.setIntArray("ints",new int[]{1,2,3});
        b.setIntArray("ints",new int[]{1,2,3}); b.setByteArray("bytes",new byte[]{1,2,3}); b.setString("BB","two"); b.setString("Aa","one");
        first.setTagCompound(a); second.setTagCompound(b);
        check(digest(first).equals(digest(second)),"Canonical full NBT ignores compound insertion order, including colliding map keys.");
        b.setByteArray("bytes",new byte[]{1,9,3}); check(!digest(first).equals(digest(second)),"Equal-length byte arrays compare actual bytes, not abbreviated NBT strings.");
        b.setByteArray("bytes",new byte[]{1,2,3}); b.setIntArray("ints",new int[]{1,9,3});
        check(!digest(first).equals(digest(second)),"Integer array contents are fully covered.");
        second=first.copy(); second.stackSize=63; check(!digest(first).equals(digest(second)),"Stack amount is part of remote synchronization.");
        a.setByteArray("oversized",new byte[262145]);
        try { digest(first); throw new AssertionError("Oversized GUI NBT accepted"); }
        catch(BridgeException expected) { check("result_too_large".equals(expected.code),"Oversized content never becomes partial proof."); }
    }
    private static void phantomEvidence() {
        com.cleanroommc.modularui.screen.ModularContainer container=new com.cleanroommc.modularui.screen.ModularContainer();
        InventoryBasic inventory=new InventoryBasic("ghost",true,1); GhostSlot slot=new GhostSlot(inventory); container.addPhantom(slot);
        container.windowId=8; GuiObservationWait.ContainerEvidence empty=new GuiObservationWait.ContainerEvidence(container,0);
        check(empty.slots.length==0 && empty.ghosts.length==1,"Registered non-container ghost slots are captured separately from real stock.");
        ItemStack item=new ItemStack(new Item(),1,0); inventory.setInventorySlotContents(0,item);
        GuiObservationWait.ContainerEvidence displayed=new GuiObservationWait.ContainerEvidence(container,1);
        check(!displayed.matches(empty) && "phantom_contents_pending".equals(displayed.diagnostic(empty).get("reason").getAsString()),
                "All normal stock slots can agree while a stale phantom icon remains unconfirmed.");
        check(displayed.diagnostic(empty).get("serverItem").isJsonNull() && displayed.diagnostic(empty).get("clientItem").isJsonObject(),
                "A stale client icon and authoritative empty configuration are reported independently, without item loss claims.");
        NBTTagCompound nbt=new NBTTagCompound(); nbt.setString("ghost","full-NBT"); item.setTagCompound(nbt);
        check(!displayed.matches(new GuiObservationWait.ContainerEvidence(container,2)),"Phantom equality retains full independent NBT copies.");
        slot.handler.key="rebound";
        check("phantom_layout_mismatch".equals(empty.diagnostic(new GuiObservationWait.ContainerEvidence(container,3)).get("reason").getAsString()),
                "A rebound phantom sync key cannot reuse earlier slot evidence.");
    }
    public static final class GhostSlot extends Slot {
        final GhostHandler handler=new GhostHandler();
        GhostSlot(InventoryBasic inventory) { super(inventory,0,0,0); }
        public boolean isPhantom() { return true; }
        public GhostHandler getSyncHandler() { return handler; }
    }
    private static void fluidEvidence() {
        com.cleanroommc.modularui.screen.ModularContainer container=new com.cleanroommc.modularui.screen.ModularContainer();
        com.cleanroommc.modularui.value.sync.PanelSyncManager manager=container.getSyncManager().panel;
        net.minecraftforge.fluids.Fluid water=new net.minecraftforge.fluids.Fluid("mcb_phantom_sync_fixture");
        net.minecraftforge.fluids.FluidRegistry.registerFluid(water);
        com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler[] handlers=new com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler[5];
        for(int i=0;i<5;i++) {
            handlers[i]=new com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler(manager,"fluid:"+i);
            manager.syncHandlers.put(handlers[i].key,handlers[i]);
        }
        GuiObservationWait.ContainerEvidence empty=new GuiObservationWait.ContainerEvidence(container,0);
        check(empty.ghosts.length==0 && empty.fluids.length==5,"Five fluid value handlers are captured even when vanilla and item phantom lists are empty.");
        handlers[2].value=new net.minecraftforge.fluids.FluidStack(water,1);
        GuiObservationWait.ContainerEvidence nonempty=new GuiObservationWait.ContainerEvidence(container,1);
        check(!empty.matches(nonempty) && "phantom_fluid_contents_pending".equals(empty.diagnostic(nonempty).get("reason").getAsString()),
                "Fluid cache mismatch cannot be declared full phantom synchronization.");
        handlers[2].value.amount=2;
        check(!nonempty.matches(new GuiObservationWait.ContainerEvidence(container,2)),"Type-only phantom amount remains part of exact cache evidence.");
        handlers[2].value.tag=new NBTTagCompound(); handlers[2].value.tag.setString("full","before");
        GuiObservationWait.ContainerEvidence tagged=new GuiObservationWait.ContainerEvidence(container,3);
        handlers[2].value.tag.setString("full","after");
        check(!tagged.matches(new GuiObservationWait.ContainerEvidence(container,4)) && "before".equals(tagged.fluids[2].tag.getString("full")),
                "Complete fluid NBT uses independent copies and detects later changes.");
        handlers[4].phantom=false;
        check("phantom_layout_mismatch".equals(empty.diagnostic(new GuiObservationWait.ContainerEvidence(container,5)).get("reason").getAsString()),
                "Changed phantom handler membership invalidates prior layout proof; ordinary fluid handlers are not phantom evidence.");
        container.getSyncManager().container=new Object();
        try { new GuiObservationWait.ContainerEvidence(container,6); throw new AssertionError("Foreign native container accepted"); }
        catch(BridgeException expected) {check("unsupported".equals(expected.code),"Foreign panel binding fails before a completeness claim.");}
    }
    public static final class GhostHandler {
        String key="ghost:16"; final GhostManager manager=new GhostManager();
        public String getKey() { return key; }
        public GhostManager getSyncManager() { return manager; }
        public boolean isRegistered(GhostManager expected) { return expected==manager; }
    }
    public static final class GhostManager { public String getPanelName() { return "main"; } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

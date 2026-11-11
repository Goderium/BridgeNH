package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.common.structure.StructureAdapter;

import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic.IModularizedMachine;
import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic.ModularHatchTypes;
import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.ExecutionCores.IExecutionCore;
import com.Nxer.TwistSpaceTechnology.common.modularizedMachine.modularHatches.IModularHatch;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

/** Public module discovery and state fixtures only; all world-changing methods trip immediately. */
public class ModularMachineSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        modules();
        missing();
        outputStorage();
        componentBudget();
        return checks;
    }

    private static void componentBudget() {
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); Controller controller = new Controller(); tile.meta = controller;
        JsonObject args = new JsonObject();
        long expired = System.nanoTime() - 2 * StructureAdapter.SOFT_BUDGET_NANOS;
        JsonObject page = MachineDataAdapter.read(null, tile, args, expired).getAsJsonObject("components");
        check(!page.get("softBudgetPartial").getAsBoolean() && !page.get("hasMore").getAsBoolean(), "empty complete directory is not partial when earlier getters exhaust the budget");
        Core first = new Core(controller, true), second = new Core(controller, false);
        controller.modules.add(first);
        page = MachineDataAdapter.read(null, tile, args, expired).getAsJsonObject("components");
        check(page.getAsJsonArray("entries").size() == 1 && !page.get("softBudgetPartial").getAsBoolean(), "last component completes even when its getter exceeds the budget");
        controller.modules.add(second);
        page = MachineDataAdapter.read(null, tile, args, expired).getAsJsonObject("components");
        check(page.get("softBudgetPartial").getAsBoolean() && page.get("nextOffset").getAsInt() == 1 && page.get("hasMore").getAsBoolean(), "exhausted budget still advances exactly one component and declares remaining coverage");
        String fingerprint = page.get("directoryFingerprint").getAsString();
        args.addProperty("componentOffset", 1);
        page = MachineDataAdapter.read(null, tile, args, expired).getAsJsonObject("components");
        check(!page.get("hasMore").getAsBoolean() && !page.get("softBudgetPartial").getAsBoolean()
                && fingerprint.equals(page.get("directoryFingerprint").getAsString()), "final continuation shares the same directory and completes despite elapsed budget");
        controller.modules = new LinkedHashSet<IModularHatch>(); controller.modules.add(first); controller.modules.add(second);
        page = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(fingerprint.equals(page.get("directoryFingerprint").getAsString()), "equivalent public collection replacement preserves encounter-order fingerprint");
        controller.modules.clear(); controller.modules.add(second); controller.modules.add(first);
        page = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(!fingerprint.equals(page.get("directoryFingerprint").getAsString()), "same-size reordering changes directory fingerprint");
        controller.modules.clear(); controller.modules.add(first); controller.modules.add(new Core(controller, false));
        page = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(!fingerprint.equals(page.get("directoryFingerprint").getAsString()), "same-size registration replacement changes directory fingerprint");
        args.addProperty("componentOffset", 0); args.addProperty("componentLimit", 1);
        page = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(page.get("hasMore").getAsBoolean() && !page.get("softBudgetPartial").getAsBoolean(), "requested page limit is distinct from elapsed-budget truncation");
    }

    private static void outputStorage() {
        JsonObject data = MachineDataAdapter.outputStorage(new Output());
        check(data.get("available").getAsBoolean() && Long.toString(Long.MAX_VALUE).equals(data.get("cachedAmount").getAsString()), "public provider totals retain decimal long precision without inventory enumeration");
        check(data.get("scope").getAsString().contains("excludes inserted cell") && !data.has("items"), "aggregate quantity never pretends to be item identity or entire network inventory");
        check(!MachineDataAdapter.outputStorage(new Object()).get("available").getAsBoolean(), "missing provider interface is explicit unavailable");
        data = MachineDataAdapter.outputStorage(new Output() { @Override public Object getProvider() { throw new IllegalStateException("fixture"); } });
        check(!data.get("available").getAsBoolean() && "public_output_provider_failed".equals(data.get("reason").getAsString()), "public provider failure never invents zero output");
    }
    public static class Output implements gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase.Environment {
        @Override public Object getProvider() { return new Provider(); }
    }
    public static class Provider {
        public long getCachedAmount() { return Long.MAX_VALUE; }
        public long getCacheCapacity() { return Long.MAX_VALUE; }
        public void getCacheList() { throw new AssertionError("No cache enumeration"); }
        public void flushCachedStack() { throw new AssertionError("No cache flushing"); }
    }
    public static void main(String[] args) { System.out.println("Modular machine fixtures passed: " + run()); }

    private static void modules() {
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); Controller controller = new Controller(); tile.meta = controller;
        Core active = new Core(controller, true), idle = new Core(controller, false);
        controller.mInputHatches.add(new OrdinaryHatch());
        controller.modules.add(active); controller.modules.add(null); controller.modules.add(idle);
        JsonObject args = new JsonObject(); args.addProperty("componentOffset", 1); args.addProperty("componentLimit", 1);
        JsonObject result = MachineDataAdapter.read(null, tile, args, start());
        JsonObject components = result.getAsJsonObject("components");
        check(components.get("total").getAsInt() == 4, "generic registered modules extend ordinary GT components");
        check(components.getAsJsonObject("modularHatches").get("available").getAsBoolean()
                && components.getAsJsonObject("modularHatches").get("registeredCount").getAsInt() == 3, "public collection count includes null and idle entries");
        JsonObject entry = components.getAsJsonArray("entries").get(0).getAsJsonObject();
        check("modular_hatch".equals(entry.get("role").getAsString()) && "EXECUTION_CORE".equals(entry.get("moduleType").getAsString()), "module role and declared enum type are separate");
        check(!entry.get("valid").getAsBoolean() && "base_tile_unavailable".equals(entry.get("reason").getAsString()), "missing world/base remains explicit invalidity while public module data survives");
        JsonObject core = entry.getAsJsonObject("executionCore");
        check(core.get("working").getAsBoolean() && !core.get("idle").getAsBoolean(), "public core working and idle states are retained");
        check(core.get("eut").getAsLong() == Long.MAX_VALUE && core.get("needProgressingTimeTicks").getAsInt() == 17, "core getter values retain long precision and named API semantics");
        check(core.getAsJsonObject("mainMachine").get("controllerIdentityMatches").getAsBoolean(), "registered module link compares actual controller identity");
        check(!core.has("signedCalculatedEUt") && core.get("eutMeaning").getAsString().contains("IExecutionCore.getEut"), "core EU/t does not acquire controller or recipe meaning");
        args.addProperty("componentOffset", components.get("nextOffset").getAsInt());
        components = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check("null_registered_entry".equals(components.getAsJsonArray("entries").get(0).getAsJsonObject().get("reason").getAsString()), "null modular registration is preserved across pagination");
        args.addProperty("componentOffset", components.get("nextOffset").getAsInt());
        components = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(!components.get("hasMore").getAsBoolean() && components.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("executionCore").get("idle").getAsBoolean(), "complete module directory includes idle cores without working filters");
        controller.modules = new LinkedHashSet<IModularHatch>(); controller.modules.add(active); controller.modules.add(idle);
        args.addProperty("componentOffset", 2);
        components = MachineDataAdapter.read(null, tile, args, start()).getAsJsonObject("components");
        check(components.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("executionCore").get("idle").getAsBoolean(), "Collection contract supports non-indexed registered module sets");
        tile.meta = active;
        result = MachineDataAdapter.read(null, tile, new JsonObject(), start());
        check(result.getAsJsonObject("executionCore").get("working").getAsBoolean(), "standalone core inspection exposes the same public getters");
        check(!result.getAsJsonObject("executionCore").getAsJsonObject("mainMachine").has("controllerIdentityMatches"), "standalone core is not compared to itself as a supposed controller");
        check(controller.directoryCalls > 0 && controller.filteredCalls == 0, "discovery invokes only the complete registered module getter");
    }

    private static void missing() {
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = new MTEMultiBlockBase();
        JsonObject components = MachineDataAdapter.read(null, tile, new JsonObject(), start()).getAsJsonObject("components");
        check(!components.getAsJsonObject("modularHatches").get("available").getAsBoolean(), "machine without a public modular contract reports unavailable");
        Controller broken = new Controller() { @Override public Collection<IModularHatch> getAllModularHatches() { throw new IllegalStateException("fixture getter failure"); } };
        tile.meta = broken;
        components = MachineDataAdapter.read(null, tile, new JsonObject(), start()).getAsJsonObject("components");
        check(!components.getAsJsonObject("modularHatches").get("available").getAsBoolean()
                && "public_getter_failed".equals(components.getAsJsonObject("modularHatches").get("reason").getAsString()), "failed getter is not treated as an empty known module collection");
    }

    public static class Controller extends MTEMultiBlockBase implements IModularizedMachine.ISupportExecutionCore {
        Collection<IModularHatch> modules = new ArrayList<IModularHatch>(); int directoryCalls, filteredCalls;
        public Object getBaseMetaTileEntity() { return null; }
        @Override public Collection<IModularHatch> getAllModularHatches() { directoryCalls++; return modules; }
        public Collection<IExecutionCore> getAllWorkingExecutionCoresToBoost() { filteredCalls++; throw new AssertionError("Filtered boost getter is not a directory"); }
        public void checkMachine() { throw new AssertionError("Structure checking is forbidden"); }
    }
    public static class Core implements IExecutionCore, IModularHatch {
        final Controller controller; final boolean working;
        Core(Controller controller, boolean working) { this.controller = controller; this.working = working; }
        public Object getBaseMetaTileEntity() { return null; }
        @Override public ModularHatchTypes getType() { return ModularHatchTypes.EXECUTION_CORE; }
        @Override public boolean isIdle() { return !working; }
        @Override public boolean isWorking() { return working; }
        @Override public boolean useMainMachinePower() { return false; }
        @Override public long getEut() { return Long.MAX_VALUE; }
        @Override public int getNeedProgressingTime() { return 17; }
        @Override public IModularizedMachine.ISupportExecutionCore getMainMachine() { return controller; }
        public void setup() { throw new AssertionError("Core setup is forbidden"); }
        public void boostTick() { throw new AssertionError("Core boosting is forbidden"); }
        public void shutDown() { throw new AssertionError("Core shutdown is forbidden"); }
    }
    public static class OrdinaryHatch { public Object getBaseMetaTileEntity() { return null; } }
    private static long start() { return System.nanoTime() + 1000000000L; }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
}

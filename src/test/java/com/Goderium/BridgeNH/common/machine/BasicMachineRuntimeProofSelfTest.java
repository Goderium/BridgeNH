package com.Goderium.BridgeNH.common.machine;

import com.google.gson.JsonObject;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/** Public common-base membership and observation-bound owner identity; no machine-specific aliases. */
public final class BasicMachineRuntimeProofSelfTest {
    public static int run() {
        int checks = 0;
        Object[] members = {new MTEBasicMachine(8, 10), new DirectBasic(), new RecipeBasic(), new NestedBasic()};
        for (int i = 0; i < members.length; i++) {
            JsonObject observation = observation(members[i], 405 + i);
            JsonObject proof = MachineDataAdapter.singleBlockRecipeMachine(members[i], observation);
            check(proof.get("runtimeMember").isJsonPrimitive() && proof.get("runtimeMember").getAsJsonPrimitive().isBoolean()
                    && proof.get("runtimeMember").getAsBoolean(), "Common base, sibling and nested subclasses use actual Class.isInstance membership"); checks++;
            check(proof.get("schemaVersion").getAsInt() == 1
                    && proof.get("apiClass").getAsString().equals("gregtech.api.metatileentity.implementations.MTEBasicMachine")
                    && proof.get("source").getAsString().equals("public_runtime_api"), "Versioned proof names only the supported public common API"); checks++;
            JsonObject owner = proof.getAsJsonObject("owner");
            check(owner.entrySet().size() == 6 && owner.get("metaClass").getAsString().equals(members[i].getClass().getName())
                    && owner.get("metaTileId").getAsInt() == 405 + i && owner.get("x").getAsInt() == -260
                    && owner.get("y").getAsInt() == 119 && owner.get("z").getAsInt() == -120
                    && owner.get("dimension").getAsInt() == 180, "Owner binds the current full observation and preserves actual subclass"); checks++;
            JsonObject circuit = proof.getAsJsonObject("configurationCircuit");
            check(circuit.get("available").getAsBoolean() && circuit.get("allowSelectCircuit").getAsBoolean()
                    && circuit.get("inventoryIndex").getAsInt() == ((MTEBasicMachine) members[i]).getCircuitSlot()
                    && circuit.get("apiClass").getAsString().equals("gregtech.api.interfaces.IConfigurationCircuitSupport")
                    && circuit.get("source").getAsString().equals("public_runtime_api")
                    && circuit.getAsJsonObject("owner").equals(owner),
                    "The actual public circuit backing index is bound to the same owner and accepts subclass overrides"); checks++;
            observation.addProperty("x", 999); observation.addProperty("metaClass", "changed_after_capture");
            check(owner.get("x").getAsInt() == -260 && owner.get("metaClass").getAsString().equals(members[i].getClass().getName()),
                    "Later replacement of observation fields cannot rewrite the captured proof owner"); checks++;
        }
        SimilarMethods unknown = new SimilarMethods();
        JsonObject negative = MachineDataAdapter.singleBlockRecipeMachine(unknown, observation(unknown, 999));
        check(!negative.get("runtimeMember").getAsBoolean(), "Lookalike public getters do not establish public BasicMachine membership"); checks++;
        check(!negative.getAsJsonObject("configurationCircuit").get("available").getAsBoolean()
                && !negative.getAsJsonObject("configurationCircuit").has("inventoryIndex"),
                "Lookalike circuit getters cannot publish a supported backing index"); checks++;
        check(!MachineDataAdapter.singleBlockRecipeMachine(null, new JsonObject()).get("runtimeMember").getAsBoolean(),
                "Absent runtime meta object cannot publish affirmative membership"); checks++;
        JsonObject disabled = MachineDataAdapter.singleBlockRecipeMachine(new DisabledCircuit(), observation(new DisabledCircuit(), 410))
                .getAsJsonObject("configurationCircuit");
        check(disabled.get("available").getAsBoolean() && !disabled.get("allowSelectCircuit").getAsBoolean(),
                "A disabled public configuration API remains an explicit negative selection permission"); checks++;
        for (MTEBasicMachine invalid : new MTEBasicMachine[] {new InvalidCircuit(), new FailedCircuit()}) {
            JsonObject unavailable = MachineDataAdapter.singleBlockRecipeMachine(invalid, observation(invalid, 411))
                    .getAsJsonObject("configurationCircuit");
            check(!unavailable.get("available").getAsBoolean() && !unavailable.has("inventoryIndex"),
                    "Failed or invalid public getters never publish an actionable backing index"); checks++;
        }

        BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = new DirectBasic();
        JsonObject current = MachineDataAdapter.read(null, tile, new JsonObject(), System.nanoTime() + 60000000000L);
        JsonObject proof = current.getAsJsonObject("singleBlockRecipeMachine");
        check(proof.get("runtimeMember").getAsBoolean() && current.get("metaClass").getAsString().equals(DirectBasic.class.getName())
                && proof.getAsJsonObject("owner").get("metaClass").equals(current.get("metaClass"))
                && proof.getAsJsonObject("owner").get("metaTileId").equals(current.get("metaTileId")),
                "Fresh machine diagnostics publish a top-level proof against the same actual meta object and ID"); checks++;
        check(!proof.getAsJsonObject("owner").has("x") && !proof.getAsJsonObject("owner").has("dimension"),
                "Missing physical test-fixture coordinates remain absent instead of being inferred from request arguments"); checks++;
        tile.meta = unknown;
        current = MachineDataAdapter.read(null, tile, new JsonObject(), System.nanoTime() + 60000000000L);
        check(!current.getAsJsonObject("singleBlockRecipeMachine").get("runtimeMember").getAsBoolean()
                && current.get("metaClass").getAsString().equals(SimilarMethods.class.getName()),
                "An unrelated fresh machine observation carries an explicit negative proof without class normalization"); checks++;
        return checks;
    }

    private static JsonObject observation(Object meta, int id) {
        JsonObject result = new JsonObject(); result.addProperty("metaTileId", id); result.addProperty("metaClass", meta.getClass().getName());
        result.addProperty("x", -260); result.addProperty("y", 119); result.addProperty("z", -120); result.addProperty("dimension", 180);
        return result;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static class DirectBasic extends MTEBasicMachine {
        DirectBasic() { super(8, 10); }
        public int getCircuitSlot() { return 3; }
    }
    static class RecipeBasic extends MTEBasicMachine { RecipeBasic() { super(8, 10); } }
    static class NestedBasic extends RecipeBasic { }
    static class DisabledCircuit extends DirectBasic { public boolean allowSelectCircuit() { return false; } }
    static class InvalidCircuit extends DirectBasic { public int getCircuitSlot() { return -1; } }
    static class FailedCircuit extends DirectBasic { public int getCircuitSlot() { throw new IllegalStateException("getter failure"); } }
    public static class SimilarMethods {
        public int getInputSlot() { return 8; }
        public int getOutputSlot() { return 10; }
        public boolean allowSelectCircuit() { return true; }
        public int getCircuitSlot() { return 3; }
        public Object getRecipeMap() { throw new AssertionError("Runtime proof must not invoke a lookalike recipe getter"); }
    }
}

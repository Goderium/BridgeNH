package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NativeSupplySelfTest {
    public static int run() {
        int checks = 0;
        Fixture ok = new Fixture();
        JsonObject supplied = NativeSupplyAdapter.supply(items(1, 1), ok);
        require(supplied.get("complete").getAsBoolean() && ok.commands.size() == 1 && ok.commands.get(0).equals("give TestPlayer minecraft:stone 1 1")); checks++;
        Fixture uppercase = new Fixture(); JsonObject uppercaseDeclaration = new JsonObject(); JsonArray uppercaseItems = new JsonArray();
        for (String registry : new String[] {"IC2:itemFluidCell", "IC2:itemCellEmpty", "Railcraft:fluid.creosote.cell"}) {
            uppercase.registered.add(registry);
            JsonObject item = items(0, 1).getAsJsonArray("items").get(0).getAsJsonObject(); item.addProperty("registryName", registry); uppercaseItems.add(item);
        }
        uppercaseDeclaration.add("items", uppercaseItems); supplied = NativeSupplyAdapter.supply(uppercaseDeclaration, uppercase);
        require(supplied.get("complete").getAsBoolean() && supplied.get("commandsAccepted").getAsInt() == 3
                && uppercase.commands.get(0).equals("give TestPlayer IC2:itemFluidCell 1 0")
                && uppercase.commands.get(1).equals("give TestPlayer IC2:itemCellEmpty 1 0")
                && uppercase.commands.get(2).equals("give TestPlayer Railcraft:fluid.creosote.cell 1 0")); checks++;
        Fixture wrongCase = new Fixture(); wrongCase.registered.add("IC2:itemFluidCell");
        JsonObject changedCase = items(0, 1); changedCase.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("registryName", "ic2:itemFluidCell");
        refused(changedCase, wrongCase); require(wrongCase.commands.isEmpty()); checks++;
        for (String registry : new String[] {"IC2:itemFluidCell ", " IC2:itemFluidCell", "IC2:itemFluid Cell", "IC2:itemFluidCell\n", "IC2:itemFluidCell\r\ngive TestPlayer minecraft:stone", "IC2:itemFluidCell\t", "IC2:itemFluidCell;give", "IC2:itemFluidCell/ give", "IC2:itemFluidCell\u0000"}) {
            Fixture unsafe = new Fixture(); unsafe.registered.add(registry);
            JsonObject rejected = items(0, 1); rejected.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("registryName", registry);
            refused(rejected, unsafe); require(unsafe.commands.isEmpty()); checks++;
        }
        StringBuilder longRegistry = new StringBuilder("IC2:"); for (int i = 0; i < 130; i++) longRegistry.append('a');
        for (String registry : new String[] {longRegistry.toString(), longRegistry.substring(0, 90)}) {
            Fixture bounded = new Fixture(); bounded.registered.add(registry);
            JsonObject rejected = items(0, 1); rejected.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("registryName", registry);
            refused(rejected, bounded); require(bounded.commands.isEmpty()); checks++;
        }
        for (String fault : new String[] {"inventory", "cursor", "creative", "player", "registry", "stack"}) {
            Fixture fixture = new Fixture(); fixture.fault = fault;
            refused(items(1, 1), fixture); require(fixture.commands.isEmpty()); checks++;
        }
        Fixture invalidLast = new Fixture();
        JsonObject declaration = items(1, 1); declaration.getAsJsonArray("items").add(new JsonParser().parse("{\"registryName\":\"minecraft:stone\",\"metadata\":2,\"count\":65}"));
        refused(declaration, invalidLast); require(invalidLast.commands.isEmpty()); checks++;
        Fixture duplicate = new Fixture(); declaration = items(1, 1); declaration.getAsJsonArray("items").add(declaration.getAsJsonArray("items").get(0));
        refused(declaration, duplicate); require(duplicate.commands.isEmpty()); checks++;
        Fixture preserve = new Fixture(); preserve.fault = "inventory";
        declaration = items(0, 1); declaration.addProperty("preserveInventory", true);
        require(NativeSupplyAdapter.supply(declaration, preserve).get("complete").getAsBoolean()); checks++;
        Fixture full = new Fixture(); full.fault = "capacity";
        refused(declaration, full); require(full.commands.isEmpty()); checks++;
        for (String fault : new String[] {"cursor", "creative"}) {
            Fixture denied = new Fixture(); denied.fault = fault;
            refused(declaration, denied); require(denied.commands.isEmpty()); checks++;
        }
        Fixture extra = new Fixture(); declaration = items(1, 1); declaration.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("nbt", "{}");
        refused(declaration, extra); require(extra.commands.isEmpty()); checks++;
        for (boolean unknown : new boolean[] {false, true}) {
            Fixture failed = new Fixture(); failed.failAt = 1; failed.throwsAfterDispatch = unknown;
            declaration = items(1, 1); declaration.getAsJsonArray("items").add(items(2, 1).getAsJsonArray("items").get(0)); declaration.getAsJsonArray("items").add(items(3, 1).getAsJsonArray("items").get(0));
            JsonObject result = NativeSupplyAdapter.supply(declaration, failed);
            require(!result.get("complete").getAsBoolean() && result.get("commandsAttempted").getAsInt() == 2
                && result.get("commandsAccepted").getAsInt() == 1 && failed.commands.size() == 2
                && result.get("outcomeUnknown").getAsBoolean() == unknown); checks++;
        }
        Fixture inject = new Fixture(); declaration = items(1, 1); declaration.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("registryName", "minecraft:stone give");
        refused(declaration, inject); require(inject.commands.isEmpty()); checks++;
        return checks;
    }
    private static JsonObject items(int metadata, int count) {
        return new JsonParser().parse("{\"items\":[{\"registryName\":\"minecraft:stone\",\"metadata\":" + metadata + ",\"count\":" + count + "}]}").getAsJsonObject();
    }
    private static void refused(JsonObject args, Fixture fixture) {
        try { NativeSupplyAdapter.supply(args, fixture); throw new AssertionError("Expected refusal"); }
        catch (BridgeException expected) { }
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Native supply proof failed"); }
    private static final class Fixture implements NativeSupplyAdapter.Access {
        String fault = ""; int failAt = -1; boolean throwsAfterDispatch;
        final List<String> commands = new ArrayList<String>();
        final Set<String> registered = new HashSet<String>();
        Fixture() { registered.add("minecraft:stone"); }
        public String playerName() { return "player".equals(fault) ? "bad name" : "TestPlayer"; }
        public boolean creative() { return !"creative".equals(fault); }
        public boolean inventoryEmpty() { return !"inventory".equals(fault); }
        public boolean cursorEmpty() { return !"cursor".equals(fault); }
        public boolean fits(JsonArray items) { return !"capacity".equals(fault); }
        public int stackLimit(String registry, int metadata) { return "registry".equals(fault) || "stack".equals(fault) || !registered.contains(registry) ? 0 : 64; }
        public int execute(String command) {
            int index = commands.size(); commands.add(command);
            if (index == failAt) { if (throwsAfterDispatch) throw new IllegalStateException(); return 0; }
            return 1;
        }
    }
}

package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic public API fixtures; no running client, world, checks, hints or placement. */
public class StructureDiscoverySelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        defaults();
        runtimeParameters();
        discovery();
        geometry();
        prototype();
        return checks;
    }
    public static void main(String[] args) { System.out.println("Structure discovery fixtures passed: " + run()); }

    private static void runtimeParameters() {
        JsonObject args = new JsonObject(); args.addProperty("offset", 123);
        StructureAdapter.RuntimeParameters initial = StructureAdapter.runtimeParameters(args);
        check(initial.offset == 0 && initial.captureId == null && initial.floorBlockY == null,
                "runtime coordinate offset and floor defaults stay independent of definition pagination");
        args.addProperty("runtimeOffset", 1);
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("captureId", "current-capture"); args.addProperty("floorBlockY", 254);
        StructureAdapter.RuntimeParameters current = StructureAdapter.runtimeParameters(args);
        check(current.offset == 1 && "current-capture".equals(current.captureId) && current.floorBlockY == 254,
                "continuation carries an exact capture identity and literal floor block height");
        args.addProperty("runtimeOffset", 2049);
        check(StructureAdapter.runtimeParameters(args).offset == 2049, "runtime offset above the legacy capture limit remains valid");
        args.addProperty("runtimeOffset", BlueprintObservationAdapter.POSITION_LIMIT);
        check(StructureAdapter.runtimeParameters(args).offset == 16384, "terminal raw offset permits the expanded capture position upper bound");
        args.addProperty("runtimeOffset", BlueprintObservationAdapter.POSITION_LIMIT + 1);
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("runtimeOffset", -1);
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("runtimeOffset", 0); args.addProperty("captureId", "");
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("captureId", String.join("", Collections.nCopies(129, "x")));
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("captureId", "current-capture"); args.addProperty("floorBlockY", 255);
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("floorBlockY", -1);
        expect("invalid_args", () -> StructureAdapter.runtimeParameters(args));
        args.addProperty("floorBlockY", 0);
        check(StructureAdapter.runtimeParameters(args).floorBlockY == 0, "floor height zero remains a valid block coordinate");
    }

    private static void defaults() {
        JsonObject args = new JsonObject();
        StructureAdapter.Piece piece = StructureAdapter.piece("any.controller", args);
        check(piece.name.isEmpty() && piece.offsets == null, "missing piece selects discovery, never a machine default");
        args.add("piece", JsonNull.INSTANCE);
        check(StructureAdapter.piece("another.controller", args).name.isEmpty(), "null optional piece selects discovery");
        args.addProperty("piece", "fixture");
        check(StructureAdapter.piece("any.controller", args).offsets == null, "piece without offsets retains unknown anchor");
        args.addProperty("offsetA", 0);
        expect("invalid_args", () -> StructureAdapter.piece("any.controller", args));
        args.addProperty("offsetB", 1); args.addProperty("offsetC", 2);
        check(Arrays.equals(StructureAdapter.piece("any.controller", args).offsets, new int[] {0, 1, 2}), "only complete caller ABC locates the piece");
        args.addProperty("piece", "");
        expect("invalid_args", () -> StructureAdapter.piece("any.controller", args));
        args.addProperty("piece", String.join("", Collections.nCopies(129, "x")));
        expect("invalid_args", () -> StructureAdapter.piece("any.controller", args));
        JsonObject unavailable = new StructureAdapter().readPlan(null, new Object(), new Object(),
                new Definition(new IStructureElement[0]), null, 1, 2, 3, new JsonObject(), System.nanoTime());
        check("definition_discovery".equals(unavailable.get("mode").getAsString()), "default entry returns definition discovery without requiring facing");
        check(!unavailable.getAsJsonObject("definition").get("pieceDiscoveryAvailable").getAsBoolean(), "interface without enumeration reports unavailable");
        check(unavailable.getAsJsonObject("definition").get("pieceDiscoveryReason").getAsString().contains("no piece enumeration"), "unavailable enumeration has an actionable reason");
        check(unavailable.get("piecesTotal").isJsonNull() && unavailable.getAsJsonArray("pieces").size() == 0, "unknown enumeration is not an empty known definition");
    }

    private static void discovery() {
        Map<String, Object> structures = new LinkedHashMap<String, Object>();
        structures.put("alpha", new IStructureElement[] {new Element()});
        structures.put("beta", new IStructureElement[0]);
        structures.put("oversized", new IStructureElement[StructureAdapter.COMPILED_LIMIT + 1]);
        StructureAdapter.DefinitionCatalog catalog = new StructureAdapter.DefinitionCatalog(null, new Object(), new Object(), new Object(), 1, 2, 3, "", structures);
        List<String> names = new ArrayList<String>();
        JsonObject page; int cursor = 0, loops = 0;
        do {
            page = StructureAdapter.definitionPage(catalog, cursor, 1, System.nanoTime());
            for (com.google.gson.JsonElement value : page.getAsJsonArray("pieces")) {
                JsonObject piece = value.getAsJsonObject(); names.add(piece.get("name").getAsString());
                check(piece.get("offsets").isJsonNull() && !piece.get("worldPositionsAvailable").getAsBoolean(), "catalog never invents controller offsets");
            }
            check(page.toString().getBytes(StandardCharsets.UTF_8).length < StructureAdapter.BYTE_LIMIT, "discovery result is byte bounded");
            cursor = page.get("nextOffset").getAsInt();
            if (++loops > 10) throw new AssertionError("Definition discovery did not terminate");
        } while (page.get("hasMore").getAsBoolean());
        check(names.equals(Arrays.asList("alpha", "beta", "oversized")), "discovery pages preserve all piece names");
        check(!page.getAsJsonArray("pieces").get(0).getAsJsonObject().get("geometryAvailable").getAsBoolean(), "oversized compiled geometry is explicitly unavailable");
        expect("stale_cursor", () -> StructureAdapter.definitionPage(catalog, 4, 1, System.nanoTime()));
        StructureAdapter.DefinitionCatalog filtered = new StructureAdapter.DefinitionCatalog(null, new Object(), new Object(), new Object(), 1, 2, 3, "beta", structures);
        page = StructureAdapter.definitionPage(filtered, 0, 128, System.nanoTime());
        check(page.getAsJsonArray("pieces").size() == 1 && "beta".equals(page.getAsJsonArray("pieces").get(0).getAsJsonObject().get("name").getAsString()), "catalog query filters piece names");
        StructureAdapter.DefinitionCatalog delayed = new StructureAdapter.DefinitionCatalog(null, new Object(), new Object(), new Object(), 1, 2, 3, "", structures);
        page = StructureAdapter.definitionPage(delayed, 0, 128, System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(page.get("visited").getAsInt() == 1 && page.get("hasMore").getAsBoolean(), "discovery deadline permits one entry then yields");
    }

    private static void geometry() {
        Element casing = new Element();
        IStructureElement navigation = new Element() {
            @Override public boolean isNavigating() { return true; }
            @Override public boolean resetA() { return true; }
            @Override public boolean resetB() { return true; }
            @Override public int getStepA() { return 2; }
            @Override public int getStepB() { return 1; }
        };
        Definition definition = new Definition(new IStructureElement[] {casing, navigation, casing});
        JsonObject args = new JsonObject(); args.addProperty("piece", "fixture"); args.addProperty("limit", 1);
        Object tile = new Object(), meta = new Object(); Facing facing = new Facing();
        StructureAdapter adapter = new StructureAdapter();
        JsonObject page = adapter.readPlan(null, tile, meta, definition, facing, 100, 200, 300, args, System.nanoTime());
        JsonObject first = page.getAsJsonArray("positions").get(0).getAsJsonObject();
        check(first.get("a").getAsInt() == 0 && first.get("b").getAsInt() == 0 && first.get("c").getAsInt() == 0, "unknown anchor geometry starts at definition origin");
        check(!first.has("x") && !first.has("y") && !first.has("z") && !first.has("loaded") && !first.has("observed"), "unknown anchor emits no fabricated world observation");
        check("piece_origin".equals(first.get("coordinateSpace").getAsString()) && page.get("offsets").isJsonNull(), "origin coordinate semantics are explicit");
        check("not_queried_unknown_controller_offsets".equals(first.get("candidateCoverage").getAsString()), "unlocated candidate getters are skipped");
        check(facing.transforms == 0 && casing.candidateCalls == 0, "unknown offsets never transform or query candidate world positions");
        args.addProperty("offset", page.get("nextOffset").getAsInt());
        do {
            page = adapter.readPlan(null, tile, meta, definition, facing, 100, 200, 300, args, System.nanoTime());
            args.addProperty("offset", page.get("nextOffset").getAsInt());
        } while (page.get("hasMore").getAsBoolean() && page.getAsJsonArray("positions").size() == 0);
        JsonObject second = page.getAsJsonArray("positions").get(0).getAsJsonObject();
        check(second.get("a").getAsInt() == 2 && second.get("b").getAsInt() == 1, "origin-relative navigation resets and steps remain correct across pages");
        args.addProperty("offset", 0); args.addProperty("offsetA", 1); args.addProperty("offsetB", 2); args.addProperty("offsetC", 3);
        casing.facing = facing;
        page = adapter.readPlan(null, tile, meta, definition, facing, 100, 200, 300, args, System.nanoTime() + 1000000000L);
        first = page.getAsJsonArray("positions").get(0).getAsJsonObject();
        check(page.get("worldPositionsAvailable").getAsBoolean() && first.get("x").getAsInt() == 97 && first.get("y").getAsInt() == 202 && first.get("z").getAsInt() == 301, "explicit ABC uses the public facing transform");
        check(!first.get("loaded").getAsBoolean() && !first.has("observed"), "located unloaded positions remain unknown");
        check(casing.descriptionTransforms == 1, "located position transforms before the optional bounds projection");
        check(facing.transforms == 9 && page.getAsJsonObject("pieceBounds").get("worldBoundsAvailable").getAsBoolean(), "world bounds add exactly eight public corner transforms after the position transform");
        check(casing.candidateCalls == 0 && "not_queried_unloaded".equals(first.get("candidateCoverage").getAsString()), "loaded-only candidate contract remains intact");
        adapter.invalidate(); args.addProperty("offset", 1);
        expect("stale_cursor", () -> adapter.readPlan(null, tile, meta, definition, facing, 100, 200, 300, args, System.nanoTime()));
    }

    public static class Definition implements IStructureDefinition {
        final IStructureElement[] elements;
        Definition(IStructureElement[] elements) { this.elements = elements; }
        @Override public IStructureElement[] getStructureFor(String name) { return elements; }
    }

    private static void prototype() {
        JsonObject args = new JsonObject(); args.addProperty("registryName", "fixture:controller"); args.addProperty("metadata", 37);
        PrototypeStructureAdapter.parameters(args);
        for (String forbidden : Arrays.asList("x", "y", "z", "offsetA", "offsetB", "offsetC", "floorBlockY", "runtimeOffset", "captureId")) {
            args.addProperty(forbidden, 0); expect("invalid_args", () -> PrototypeStructureAdapter.parameters(args)); args.remove(forbidden);
        }
        args.remove("metadata"); expect("invalid_args", () -> PrototypeStructureAdapter.parameters(args));
        args.addProperty("metadata", 32767); expect("invalid_args", () -> PrototypeStructureAdapter.parameters(args));
        args.addProperty("metadata", 37); args.addProperty("registryName", "fixture:controller\n");
        expect("invalid_args", () -> PrototypeStructureAdapter.parameters(args)); args.addProperty("registryName", "fixture:controller");
        args.addProperty("metadata", 37); args.addProperty("piece", "fixture"); args.addProperty("limit", 1);
        JsonObject identity = new JsonObject(); identity.addProperty("registryName", "fixture:controller"); identity.addProperty("metadata", 37);
        Prototype prototype = new Prototype();
        JsonObject result = PrototypeStructureAdapter.read(new StructureAdapter(), prototype, identity, args, System.nanoTime());
        check(result.get("controller").isJsonNull() && result.getAsJsonObject("registeredPrototype") == identity, "prototype identity never fabricates a placed controller");
        check(!result.get("worldPositionsAvailable").getAsBoolean() && "piece_origin".equals(result.get("coordinateSpace").getAsString()), "prototype geometry remains piece-origin only");
        check(!result.get("fullMachineBoundsAvailable").getAsBoolean() && !result.get("structureFormationVerified").getAsBoolean(), "piece candidates are not active complete machine geometry or formation");
        JsonObject first = result.getAsJsonArray("positions").get(0).getAsJsonObject();
        check(!first.has("x") && !first.has("y") && !first.has("z") && !first.has("observed") && !result.has("runtimeObservation"), "prototype read never observes world blocks or synthetic runtime captures");
        check(!result.getAsJsonObject("pieceBounds").get("worldBoundsAvailable").getAsBoolean() && prototype.element.candidateCalls == 0, "prototype piece bounds do not invoke world/candidate getters");
        check(result.getAsJsonObject("structureTooltip").has("reason") && !result.getAsJsonObject("structureTooltip").get("available").getAsBoolean(), "missing public secondary descriptions stay explicitly unavailable");
        check(result.toString().getBytes(StandardCharsets.UTF_8).length < StructureAdapter.BYTE_LIMIT, "prototype geometry and descriptions preserve the total response byte bound");
    }

    public static class Prototype {
        final Element element = new Element();
        final Definition definition = new Definition(new IStructureElement[] {element});
        public IStructureDefinition getStructureDefinition() { return definition; }
        public void construct(net.minecraft.item.ItemStack stack, boolean hintsOnly) { throw new AssertionError("Prototype construction forbidden"); }
        public void checkMachine() { throw new AssertionError("Prototype structure validation forbidden"); }
    }
    public static class Element implements IStructureElement {
        int candidateCalls, descriptionTransforms;
        Facing facing;
        @Override public List<String> getDescription(Object context) { if (facing != null) descriptionTransforms = facing.transforms; return Collections.singletonList("fixture category"); }
        @Override public BlocksToPlace getBlocksToPlace(Object context, net.minecraft.world.World world, int x, int y, int z,
                net.minecraft.item.ItemStack trigger, com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment environment) {
            candidateCalls++; throw new AssertionError("No loaded fixture world exists");
        }
    }
    public static class Facing {
        int transforms;
        public String getDirection() { return "WEST"; }
        public String getRotation() { return "NORMAL"; }
        public String getFlip() { return "HORIZONTAL"; }
        public void getWorldOffset(int[] abc, int[] xyz) { transforms++; xyz[0] = abc[2]; xyz[1] = -abc[1]; xyz[2] = -abc[0]; }
    }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }
}

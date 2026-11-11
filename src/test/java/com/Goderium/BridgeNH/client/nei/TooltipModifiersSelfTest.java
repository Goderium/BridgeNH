package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import com.Goderium.BridgeNH.loader.rfb.TooltipKeyboardPlugin;
import com.Goderium.BridgeNH.loader.rfb.TooltipKeyScope;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Executes actual transformed Java8 bytecode, scoped overrides and exact continuation contracts. */
public final class TooltipModifiersSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0; TooltipModifiers.bindClientThread();
        JsonObject requested = request(true);
        expect("unsupported", () -> TooltipModifiers.enter(requested));
        expect("unsupported", () -> TooltipModifiers.enter(request(false)));
        JsonObject invalid = request(true); invalid.getAsJsonObject("modifiers").addProperty("meta", true);
        expect("invalid_args", () -> TooltipModifiers.validate(invalid, true));
        expect("invalid_args", () -> TooltipModifiers.validate(requested, false));
        JsonObject empty = new JsonObject(); empty.add("modifiers", new JsonObject()); expect("invalid_args", () -> TooltipModifiers.validate(empty, true));
        RfbClassTransformer transformer = new TooltipKeyboardPlugin().makeTransformers()[0];
        ClassNodeHandle handle = new ClassNodeHandle(original());
        check(!transformer.shouldTransformClass(null, RfbClassTransformer.Context.SYSTEM, null, "org.lwjgl.input.Keyboard", handle), "legacy parent-loader Keyboard is left unchanged");
        transformer.transformClass(null, RfbClassTransformer.Context.LCL_NO_TRANSFORMS, null, "org.lwjglx.input.Keyboard", handle);
        byte[] once = handle.computeBytes(); transformer.transformClass(null, RfbClassTransformer.Context.LCL_NO_TRANSFORMS, null, "org.lwjglx.input.Keyboard", handle);
        check(java.util.Arrays.equals(once, handle.computeBytes()), "RFB compatibility injection is idempotent");
        Class<?> transformed = new ClassLoader(TooltipModifiersSelfTest.class.getClassLoader()) {
            Class<?> define(byte[] bytes) { return defineClass("org.lwjglx.input.Keyboard", bytes, 0, bytes.length); }
        }.define(once);
        Method down = transformed.getMethod("isKeyDown", int.class);
        check(down(down, 7) && !down(down, 42), "transformed unscoped method executes its original native path");
        check(TooltipModifiers.diagnostics().get("hookObserved").getAsBoolean(), "runtime hook observation comes from actual transformed invocation");
        try (TooltipModifiers.Scope scope = TooltipModifiers.enter(requested)) {
            check(down(down, 42) && down(down, 54), "explicit Shift true affects both side keys");
            check(!down(down, 29) && !down(down, 157) && down(down, 7), "omitted Control and ordinary keys keep original path");
            check(scope.data.get("virtualModifiers").getAsBoolean() && scope.data.get("support").getAsString().equals("scoped_keyboard_isKeyDown"), "virtual context is explicitly identified");
            check(scope.data.getAsJsonObject("physicalModifiers").get("source").getAsString().equals("unavailable"), "headless physical state is never invented");
            JsonObject allFalse = request(false); allFalse.getAsJsonObject("modifiers").addProperty("control", false); allFalse.getAsJsonObject("modifiers").addProperty("alt", false);
            try (TooltipModifiers.Scope nested = TooltipModifiers.enter(allFalse)) {
                for (int key : new int[] {42, 54, 29, 157, 56, 184}) check(!down(down, key), "explicit false for modifier key " + key);
            }
            check(down(down, 42), "nested scope restores previous virtual modifiers");
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread other = new Thread(() -> { try { if (down(down, 42)) throw new AssertionError("scope escaped its client thread"); } catch (Throwable error) { failure.set(error); } });
            other.start(); other.join(); check(failure.get() == null, "different thread sees only native state");
        }
        check(!down(down, 42) && TooltipKeyScope.override(42) == null, "normal state is restored after scope close");
        try { try (TooltipModifiers.Scope ignored = TooltipModifiers.enter(requested)) { throw new IllegalStateException("fixture failure"); } } catch (IllegalStateException expected) { }
        check(!down(down, 42), "exception path restores native state");
        JsonObject modifiers = new JsonObject(); modifiers.addProperty("control", true); modifiers.addProperty("alt", true);
        JsonObject all = new JsonObject(); all.add("modifiers", modifiers);
        try (TooltipModifiers.Scope ignored = TooltipModifiers.enter(all)) { for (int key : new int[] {29, 157, 56, 184}) check(down(down, key), "explicit true for modifier key " + key); }
        URL descriptor = TooltipModifiersSelfTest.class.getResource("/META-INF/rfb-plugin/codex-tooltip-keyboard.properties");
        Properties properties = new Properties(); try (InputStream stream = descriptor.openStream()) { properties.load(stream); }
        RfbPluginMetadata metadata = new RfbPluginMetadata(descriptor, descriptor.toURI(), "codex-tooltip-keyboard", properties);
        check(metadata.className().equals(TooltipKeyboardPlugin.class.getName()) && java.util.Arrays.asList(metadata.loadAfter()).contains("lwjgl3ify"), "actual RFB parser accepts packaged descriptor and load order");
        for (java.lang.reflect.Field field : TooltipKeyScope.class.getDeclaredFields()) {
            Class<?> type = field.getType(); check(type == ThreadLocal.class || type == Thread.class || type == long.class || type == java.util.function.IntFunction.class,
                    "early helper fields retain only JDK state, including the owned-input callback: " + field.getName());
        }
        verifyOwnedKeyboardRead(down);
        verifyActualTooltipRead(down);
        return checks;
    }
    private static void verifyOwnedKeyboardRead(Method down) throws Exception {
        Object sneak = new Object(); java.util.concurrent.atomic.AtomicBoolean context = new java.util.concurrent.atomic.AtomicBoolean(true);
        OwnedKeyState.begin("keyboard-fixture", Long.MAX_VALUE, context::get); OwnedKeyState.hold("keyboard-fixture", sneak, 42);
        check(down(down, 42) && !down(down, 54), "actual transformed Keyboard sees only the declared long-held Shift side");
        check(!down(down, 29) && down(down, 7), "unowned modifier and ordinary native reads remain unchanged");
        check(!OwnedKeyState.nativeRead(() -> down(down, 42)), "native key diagnostics suspend both the getter and actual keyboard shim");
        check(down(down, 42), "native diagnostic close restores the continuously held key");
        try (TooltipKeyScope.Scope outer = TooltipKeyScope.suspend(); TooltipKeyScope.Scope inner = TooltipKeyScope.suspendOwnedInput()) {
            check(!down(down, 42), "nested physical scopes exclude owned keyboard input"); inner.close();
            check(!down(down, 42), "inner close does not end outer physical-key suspension");
        }
        check(down(down, 42), "nested physical scopes restore owned input afterward");
        try (TooltipKeyScope.Scope explicit = TooltipKeyScope.enter(false, null, null)) {
            check(!down(down, 42), "explicit tooltip modifier state takes precedence within its own query");
        }
        check(down(down, 42), "tooltip close restores owned world input");
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> { try { if (down(down, 42)) throw new AssertionError("owned keyboard state escaped client thread"); } catch (Throwable error) { failure.set(error); } });
        other.start(); other.join(); check(failure.get() == null, "foreign threads still see physical keyboard only");
        check(((Number) OwnedKeyState.trace(sneak).get("keyboardAccepted")).longValue() > 0, "exact owned target records actual accepted keyboard reads");
        OwnedKeyState.releaseAll("keyboard-fixture"); check(!down(down, 42), "restoration after transient release reads physical state, not the owned shim");
        OwnedKeyState.hold("keyboard-fixture", sneak, 42); check(down(down, 42), "declared navigation hold can resume after transient release");
        context.set(false); check(!down(down, 42) && OwnedKeyState.heldCount() == 0, "GUI/world/profile/deadline context failure stops downstream owned keyboard reads");
        context.set(true); OwnedKeyState.begin("keyboard-fixture", Long.MAX_VALUE, context::get); OwnedKeyState.hold("keyboard-fixture", sneak, 42);
        OwnedKeyState.clear("keyboard-fixture"); check(!down(down, 42), "clear before physical restoration prevents a stuck Shift");
    }
    private static void verifyActualTooltipRead(Method down) {
        AtomicInteger getters = new AtomicInteger();
        codechicken.nei.PositionedStack position = new codechicken.nei.PositionedStack(new net.minecraft.item.ItemStack[0], 0, 0) {
            @Override public java.util.List<String> getTooltip() { getters.incrementAndGet(); return java.util.Collections.singletonList("shift=" + down(down, 42)); }
        };
        codechicken.nei.recipe.IRecipeHandler handler = new codechicken.nei.recipe.IRecipeHandler() {
            public String getRecipeName() { return "scoped getter fixture"; }
            public int numRecipes() { return 1; }
            public java.util.List<codechicken.nei.PositionedStack> getIngredientStacks(int recipe) { return java.util.Collections.singletonList(position); }
            public codechicken.nei.PositionedStack getResultStack(int recipe) { return null; }
            public java.util.List<codechicken.nei.PositionedStack> getOtherStacks(int recipe) { return java.util.Collections.emptyList(); }
        };
        codechicken.nei.recipe.GuiRecipe screen = new codechicken.nei.recipe.GuiRecipe(handler, java.util.Collections.singletonList(0));
        JsonObject args = request(true); args.addProperty("screenId", "scoped-fixture"); args.addProperty("section", "detail"); args.addProperty("target", "tooltip_lines"); args.addProperty("recipeIndex", 0); args.addProperty("tooltipSource", "position");
        JsonObject result = NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L);
        check(result.getAsJsonObject("data").getAsJsonArray("entries").toString().contains("shift=true"), "real detail getter observes requested modifier state");
        check(result.getAsJsonObject("data").getAsJsonObject("modifierContext").getAsJsonObject("effectiveModifiers").get("shift").getAsBoolean() && !down(down, 42), "response is virtual while outside getter native state is restored");
        args.addProperty("contextToken", result.get("contextToken").getAsString()); args.getAsJsonObject("modifiers").addProperty("shift", false);
        expect("stale_context", () -> NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L));
        args.getAsJsonObject("modifiers").addProperty("shift", true); args.addProperty("advanced", true);
        expect("stale_context", () -> NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L));
        args.remove("contextToken"); result = NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L);
        check(result.getAsJsonObject("data").getAsJsonObject("modifierContext").getAsJsonObject("effectiveModifiers").get("shift").getAsBoolean(), "advanced mode is independent from requested Shift");
        verifyCoverageContracts(screen, getters);
    }
    private static void verifyCoverageContracts(Object screen, AtomicInteger getters) {
        JsonObject coverage = null;
        for (String target : new String[] {"tooltip_lines", "tooltip_text", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text"}) {
            JsonObject args = request(true); args.addProperty("screenId", "scoped-fixture"); args.addProperty("section", "detail"); args.addProperty("target", target);
            if (target.startsWith("preview_")) { args.addProperty("groupIndex", 0); args.addProperty("alternativeIndex", 0); }
            else { args.addProperty("recipeIndex", 0); args.addProperty("tooltipSource", "position"); }
            if (target.endsWith("_text")) args.addProperty("lineIndex", 0);
            JsonObject result = NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L);
            coverage = itemTooltipCoverage(result);
            check("partial".equals(coverage.get("status").getAsString()), "explicit actual Tooltip query is requested in response coverage: " + target);
            if (target.startsWith("preview_")) check("not_present".equals(result.get("status").getAsString()), "absent candidate scene does not claim a getter executed: " + target);
        }
        String reason = coverage.get("reason").getAsString(), source = coverage.get("source").getAsString();
        check(source.contains("ItemStack/position/badge getters") && source.contains("selected native widget/recipe-handler callbacks"), "coverage names actual getter and selected callback sources");
        check(reason.contains("query-scoped Shift/Control/Alt") && reason.contains("runtime Keyboard.isKeyDown hook is observed") && reason.contains("absent hook rejects virtual requests"), "coverage describes observed hook requirement and explicit virtual fallback");
        check(reason.contains("advanced and Shift expansion are independent") && reason.contains("Direct key buffers") && reason.contains("third-party cached modifier state"), "coverage preserves independent advanced mode and unsupported modifier paths");
        check(reason.contains("universal renderer-only callback replay remain unsupported") && reason.contains("Passive native hover and rendered text are separately observed data"), "coverage avoids universal renderer parity and passive getter claims");
        for (boolean include : new boolean[] {true, false}) {
            JsonObject args = new JsonObject(); args.addProperty("screenId", "scoped-fixture"); args.addProperty("section", "recipes"); args.addProperty("includeTooltips", include);
            check((include ? "partial" : "not_requested").equals(itemTooltipCoverage(NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L)).get("status").getAsString()), "includeTooltips retains requested coverage: " + include);
        }
        JsonObject coordinate = new JsonObject(); coordinate.addProperty("screenId", "scoped-fixture"); coordinate.addProperty("section", "tooltip"); coordinate.addProperty("x", 0); coordinate.addProperty("y", 0);
        check("partial".equals(itemTooltipCoverage(NeiViewAdapter.read(screen, null, coordinate, "scoped-session", System.nanoTime() + 60000000000L)).get("status").getAsString()), "coordinate Tooltip section retains requested coverage");
        int before = getters.get();
        for (String target : new String[] {"nbt", "item_visuals", "preview_candidate_nbt", "preview_candidate_visuals", "preview_hint_text", "hover_tooltip_lines", "hover_tooltip_text", "hover_hotkeys", "rendered_text"}) {
            JsonObject args = new JsonObject(); args.addProperty("screenId", "scoped-fixture"); args.addProperty("section", "detail"); args.addProperty("target", target);
            if (target.startsWith("preview_candidate_")) { args.addProperty("groupIndex", 0); args.addProperty("alternativeIndex", 0); }
            if ("nbt".equals(target) || "item_visuals".equals(target)) args.addProperty("recipeIndex", 0);
            if ("nbt".equals(target)) args.addProperty("advanced", true);
            if ("hover_tooltip_text".equals(target)) args.addProperty("lineIndex", 0);
            check("not_requested".equals(itemTooltipCoverage(NeiViewAdapter.read(screen, null, args, "scoped-session", System.nanoTime() + 60000000000L)).get("status").getAsString()), "data/passive target does not imply an actual Tooltip getter request: " + target);
        }
        check(getters.get() == before, "NBT, visuals, hints and passive reads do not invoke the actual position Tooltip getter");
    }
    private static JsonObject itemTooltipCoverage(JsonObject result) {
        for (JsonElement entry : result.getAsJsonArray("coverage")) if ("itemTooltips".equals(entry.getAsJsonObject().get("feature").getAsString())) return entry.getAsJsonObject();
        throw new AssertionError("itemTooltips coverage entry is absent");
    }
    private static JsonObject request(boolean shift) { JsonObject result = new JsonObject(), modifiers = new JsonObject(); modifiers.addProperty("shift", shift); result.add("modifiers", modifiers); return result; }
    private static boolean down(Method method, int key) { try { return (Boolean) method.invoke(null, key); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static byte[] original() {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjglx/input/Keyboard", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isKeyDown", "(I)Z", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ILOAD, 0); method.visitIntInsn(Opcodes.BIPUSH, 7);
        org.objectweb.asm.Label no = new org.objectweb.asm.Label(); method.visitJumpInsn(Opcodes.IF_ICMPNE, no); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(no); method.visitFrame(Opcodes.F_SAME, 0, null, 0, null); method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(2, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
}

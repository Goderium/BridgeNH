package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Early configuration and real ASM transformation tests; no native window is opened. */
public final class WindowFocusSelfTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static int run() throws Exception {
        checks = 0;
        Path game = Files.createTempDirectory("bridge-window-policy-");
        Path file = game.resolve("config/codexbridge/window-startup.txt");
        try {
            check(WindowStartupPolicy.read(game).equals("minimized"), "Missing preference defaults to minimized");
            Files.createDirectories(file.getParent());
            for (String[] example : new String[][] {{"background", "background"}, {"minimized", "minimized"},
                {"normal", "normal"}, {"unknown", "minimized"}, {"", "minimized"}}) {
                Files.write(file, (example[0] + "\n").getBytes(StandardCharsets.US_ASCII));
                check(WindowStartupPolicy.read(game).equals(example[1]), "Explicit launch mode and fallback contract");
            }
            Files.write(file, new byte[65]);
            check(WindowStartupPolicy.read(game).equals("minimized"), "Oversize preference remains bounded");
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(game.resolve("config")); Files.deleteIfExists(game);
        }
        ClassNode source = new ClassNode(); source.version = Opcodes.V1_8; source.access = Opcodes.ACC_PUBLIC;
        source.name = "org/lwjglx/opengl/Display"; source.superName = "java/lang/Object";
        String[][] calls = {{"SDL_CreateWindowWithProperties", "(I)J", "create"}, {"SDL_ShowWindow", "(J)Z", "show"},
            {"SDL_MaximizeWindow", "(J)Z", "maximize"}, {"SDL_RestoreWindow", "(J)Z", "restore"},
            {"SDL_SetWindowSize", "(JII)Z", "resize"}, {"SDL_SetWindowFullscreen", "(JZ)Z", "fullscreen"},
            {"SDL_GetWindowFlags", "(J)J", "SDL_GetWindowFlags"}};
        for (int i = 0; i < calls.length; i++) {
            String[] call = calls[i]; MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fixture" + i, call[1], null, null);
            int local = 0;
            for (Type type : Type.getArgumentTypes(call[1])) { method.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local)); local += type.getSize(); }
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVideo", call[0], call[1], false));
            method.instructions.add(new InsnNode(Type.getReturnType(call[1]).getOpcode(Opcodes.IRETURN)));
            source.methods.add(method);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); source.accept(writer);
        ClassNodeHandle handle = new ClassNodeHandle(writer.toByteArray());
        RfbClassTransformer transformer = new WindowFocusPlugin().makeTransformers()[0];
        check(!transformer.shouldTransformClass(null, RfbClassTransformer.Context.SYSTEM, null, "unrelated.Display", handle), "Only the fixed compatibility Display is transformed");
        transformer.transformClass(null, RfbClassTransformer.Context.SYSTEM, null, "org.lwjglx.opengl.Display", handle);
        for (int i = 0; i < calls.length; i++) {
            for (AbstractInsnNode instruction : handle.getNode().methods.get(i).instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                check(call.name.equals(calls[i][2]) && call.desc.equals(calls[i][1]), "Exact ABI preserved");
                check(call.owner.equals(i == 6 ? "org/lwjgl/sdl/SDLVideo" : "com/Goderium/BridgeNH/loader/rfb/WindowNativeHooks"), "Reads unchanged, mutation calls guarded");
            }
        }
        byte[] once = handle.computeBytes();
        transformer.transformClass(null, RfbClassTransformer.Context.SYSTEM, null, "org.lwjglx.opengl.Display", handle);
        check(java.util.Arrays.equals(once, handle.computeBytes()), "Transformation is idempotent");
        testInactiveMouseHandoff();
        for (String helper : new String[] {"WindowNativeHooks", "WindowFocusScope", "InactiveMouseHandoff"}) {
            ClassNode nativeClass = new ClassNode();
            try (java.io.InputStream bytes = WindowFocusSelfTest.class.getResourceAsStream(helper + ".class")) {
                new ClassReader(bytes).accept(nativeClass, ClassReader.SKIP_CODE);
            }
            check(nativeClass.visibleAnnotations != null && nativeClass.visibleAnnotations.stream().anyMatch(
                annotation -> annotation.desc.equals("Lme/eigenraven/lwjgl3ify/api/Lwjgl3Aware;")),
                "Compiled native helper already carries LWJGL3 exclusion before any transformers run");
        }
        return checks;
    }

    private static void testInactiveMouseHandoff() {
        long shown = 8;
        InactiveMouseHandoff.requireInactive(1, 2, 3, shown, 0, 0);
        InactiveMouseHandoff.requireInactive(1, 2, 3, shown, 4, 4);
        for (long[] sample : new long[][] {{1, 2, 2, shown, 0, 0}, {1, 2, 3, shown, 1, 0},
            {1, 2, 3, shown, 0, 1}, {1, 2, 3, org.lwjgl.sdl.SDLVideo.SDL_WINDOW_INPUT_FOCUS, 0, 0},
            {1, 2, 3, org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MOUSE_CAPTURE, 0, 0},
            {0, 2, 3, shown, 0, 0}, {1, 0, 3, shown, 0, 0}, {1, 2, 0, shown, 0, 0}, {1, 2, 3, 0, 0, 0}}) {
            try {
                InactiveMouseHandoff.requireInactive(sample[0], sample[1], sample[2], sample[3], sample[4], sample[5]);
                throw new AssertionError("Active or unverified mouse ownership must be refused");
            } catch (InactiveMouseHandoff.Refusal error) {
                boolean unknown = sample[0] == 0 || sample[1] == 0 || sample[2] == 0 || sample[3] == 0;
                check(error.code.equals(unknown ? "mouse_handoff_unavailable" : "mouse_manual_control"), "Distinct focus refusal");
            }
        }
        ClassNode mouse = new ClassNode();
        mouse.version = Opcodes.V1_8; mouse.access = Opcodes.ACC_PUBLIC;
        mouse.name = "org/lwjglx/input/Mouse"; mouse.superName = "java/lang/Object";
        for (String field : new String[] {"dx", "dy", "dxAccum", "dyAccum", "dxFloat", "dyFloat", "grabbed"}) {
            String type = "grabbed".equals(field) ? "Z" : field.length() == 2 ? "I" : "F";
            mouse.fields.add(new FieldNode(Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE, field, type, null, null));
        }
        String name = "org.lwjglx.input.Mouse";
        RfbClassTransformer transformer = new WindowFocusPlugin().makeTransformers()[1];
        ClassWriter writer = new ClassWriter(0); mouse.accept(writer);
        ClassNodeHandle handle = new ClassNodeHandle(writer.toByteArray());
        transformer.transformClass(null, RfbClassTransformer.Context.SYSTEM, null, name, handle);
        transformer.transformClass(null, RfbClassTransformer.Context.SYSTEM, null, name, handle);
        ClassNode result = new ClassNode(); new ClassReader(handle.computeBytes()).accept(result, 0);
        check(result.methods.size() == 1 && MouseReleaseTransformer.RELEASE.equals(result.methods.get(0).name), "Handoff adds one separate backend entry point");
        java.util.List<String> calls = new java.util.ArrayList<String>();
        int writes = 0;
        for (AbstractInsnNode instruction : result.methods.get(0).instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                calls.add(call.owner + "." + call.name);
            }
            if (instruction.getOpcode() == Opcodes.PUTSTATIC) {
                check(calls.size() == 2, "Mouse state writes follow the checked SDL release");
                writes++;
            }
        }
        check(calls.equals(java.util.Arrays.asList("org/lwjglx/opengl/Display.getWindow", "com/Goderium/BridgeNH/loader/rfb/InactiveMouseHandoff.release"))
            && writes == 7, "Only the owning window's checked release and the compatibility mouse state reset are emitted");
        mouse.fields.remove(0);
        writer = new ClassWriter(0); mouse.accept(writer);
        ClassNodeHandle unknown = new ClassNodeHandle(writer.toByteArray());
        transformer.transformClass(null, RfbClassTransformer.Context.SYSTEM, null, name, unknown);
        check(unknown.getNode().methods.isEmpty(), "An unknown compatibility backend receives no release entry point");
    }
}

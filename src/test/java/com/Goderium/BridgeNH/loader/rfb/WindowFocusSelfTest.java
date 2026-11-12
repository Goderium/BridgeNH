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
        for (String helper : new String[] {"WindowNativeHooks", "WindowFocusScope"}) {
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
}

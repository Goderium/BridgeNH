package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.*;
import java.util.jar.Manifest;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Display is loaded before Forge preInit; wrap only fixed public SDL window calls. */
public final class WindowFocusPlugin implements RfbPlugin {
    @Override public void onConstruction(PluginContext context) {
        Launch.classLoader.addClassLoaderExclusion("com.Goderium.BridgeNH.loader.rfb.");
    }
    @Override public RfbClassTransformer[] makeTransformers() { return new RfbClassTransformer[] {new Transformer()}; }
    public static final class Transformer implements RfbClassTransformer {
        @Override public String id() { return "window-focus"; }
        @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle node) {
            return "org.lwjglx.opengl.Display".equals(name) && node.isPresent();
        }
        @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle node) {
            if (!shouldTransformClass(loader, context, manifest, name, node)) return;
            boolean changed = false;
            for (MethodNode method : node.getNode().methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals("org/lwjgl/sdl/SDLVideo")) continue;
                String target = null;
                if (call.name.equals("SDL_CreateWindowWithProperties") && call.desc.equals("(I)J")) target = "create";
                if (call.name.equals("SDL_ShowWindow") && call.desc.equals("(J)Z")) target = "show";
                if (call.name.equals("SDL_MaximizeWindow") && call.desc.equals("(J)Z")) target = "maximize";
                if (call.name.equals("SDL_RestoreWindow") && call.desc.equals("(J)Z")) target = "restore";
                if (call.name.equals("SDL_SetWindowSize") && call.desc.equals("(JII)Z")) target = "resize";
                if (call.name.equals("SDL_SetWindowFullscreen") && call.desc.equals("(JZ)Z")) target = "fullscreen";
                if (target != null) { call.owner = "com/Goderium/BridgeNH/loader/rfb/WindowNativeHooks"; call.name = target; changed = true; }
            }
            if (changed) node.markDirty();
        }
    }
}

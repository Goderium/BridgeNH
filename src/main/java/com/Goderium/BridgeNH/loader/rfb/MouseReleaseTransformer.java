package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.jar.Manifest;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Adds a separate no-warp release entry point to the known SDL mouse backend. */
public final class MouseReleaseTransformer implements RfbClassTransformer {
    public static final String RELEASE = "bridgeNH$releaseInactiveGrab";
    private static final String MOUSE = "org/lwjglx/input/Mouse";
    private static final String[][] RESET = {
        {"dx", "I"}, {"dy", "I"}, {"dxAccum", "F"}, {"dyAccum", "F"},
        {"dxFloat", "F"}, {"dyFloat", "F"}, {"grabbed", "Z"}
    };

    @Override public String id() { return "inactive-mouse-release"; }
    @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        return "org.lwjglx.input.Mouse".equals(name) && handle.isPresent();
    }
    @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        if (!shouldTransformClass(loader, context, manifest, name, handle)) return;
        ClassNode node = handle.getNode();
        for (MethodNode method : node.methods) if (RELEASE.equals(method.name)) return;
        for (String[] state : RESET) {
            if (node.fields.stream().noneMatch(field -> state[0].equals(field.name) && state[1].equals(field.desc)
                    && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == Opcodes.ACC_STATIC)) return;
        }

        MethodNode release = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, RELEASE, "()V", null, null);
        release.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjglx/opengl/Display", "getWindow", "()J", false);
        release.visitMethodInsn(Opcodes.INVOKESTATIC, "com/Goderium/BridgeNH/loader/rfb/InactiveMouseHandoff", "release", "(J)V", false);
        // Match 3.0.42's successful setGrabbed(false) bookkeeping after SDL confirms release.
        for (String[] state : RESET) {
            release.visitInsn("F".equals(state[1]) ? Opcodes.FCONST_0 : Opcodes.ICONST_0);
            release.visitFieldInsn(Opcodes.PUTSTATIC, MOUSE, state[0], state[1]);
        }
        release.visitInsn(Opcodes.RETURN);
        release.visitMaxs(2, 0);
        node.methods.add(release);
        handle.markDirty();
    }
}

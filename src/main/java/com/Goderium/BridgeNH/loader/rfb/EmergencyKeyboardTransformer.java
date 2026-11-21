package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.jar.Manifest;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Filters the physical queue before either GuiScreen or Minecraft can dispatch the emergency chord. */
public final class EmergencyKeyboardTransformer implements RfbClassTransformer {
    public static final String MARKER = "bridgeNH$emergencyReleaseKey";
    private static final String FILTER = "com/Goderium/BridgeNH/loader/rfb/PhysicalKeyboardEvents";

    @Override public String id() { return "emergency-release-key"; }

    @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        return ("org.lwjglx.input.Keyboard".equals(name) || "org.lwjgl.input.Keyboard".equals(name)) && handle.isPresent();
    }

    @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        if (!shouldTransformClass(loader, context, manifest, name, handle)) return;
        for (MethodNode method : handle.getNode().methods) {
            if (!"next".equals(method.name) || !"()Z".equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            AbstractInsnNode[] instructions = method.instructions.toArray();
            if (instructions.length == 0) return;
            for (AbstractInsnNode op : instructions)
                if (op instanceof MethodInsnNode && FILTER.equals(((MethodInsnNode) op).owner)) return;
            LabelNode nextEvent = new LabelNode();
            method.instructions.insert(nextEvent);
            for (AbstractInsnNode op : instructions) {
                if (op.getOpcode() != Opcodes.IRETURN) continue;
                LabelNode done = new LabelNode();
                InsnList filter = new InsnList();
                filter.add(new InsnNode(Opcodes.DUP));
                filter.add(new JumpInsnNode(Opcodes.IFEQ, done));
                filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILTER, "consume", "()Z", false));
                filter.add(new JumpInsnNode(Opcodes.IFEQ, done));
                filter.add(new InsnNode(Opcodes.POP));
                filter.add(new JumpInsnNode(Opcodes.GOTO, nextEvent));
                filter.add(done);
                method.instructions.insertBefore(op, filter);
            }
            // The extra GUI input pass is mouse-only. The ordinary game pass still receives real key events and the release hotkey.
            LabelNode physicalKeyboard = new LabelNode();
            InsnList mouseOnly = new InsnList();
            mouseOnly.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/Goderium/BridgeNH/loader/rfb/GuiMouseScope", "pumping", "()Z", false));
            mouseOnly.add(new JumpInsnNode(Opcodes.IFEQ, physicalKeyboard));
            mouseOnly.add(new InsnNode(Opcodes.ICONST_0));
            mouseOnly.add(new InsnNode(Opcodes.IRETURN));
            mouseOnly.add(physicalKeyboard);
            method.instructions.insert(mouseOnly);
            MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, MARKER, "()V", null, null);
            marker.instructions.add(new InsnNode(Opcodes.RETURN));
            handle.getNode().methods.add(marker);
            handle.computeFrames();
            handle.markDirty();
            return;
        }
    }
}

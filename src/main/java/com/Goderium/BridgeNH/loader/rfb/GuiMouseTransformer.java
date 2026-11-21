package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Interpose the local GUI device on lwjgl3ify's public mouse reader API. Physical input is unchanged outside takeover. */
public final class GuiMouseTransformer implements RfbClassTransformer {
    private static final String SCOPE = "com/Goderium/BridgeNH/loader/rfb/GuiMouseScope";
    public static final String MARKER = "bridgeNH$virtualGuiMouse";
    private static final String[][] READERS = {
        {"getX","()I","x"}, {"getY","()I","y"}, {"getEventX","()I","x"}, {"getEventY","()I","y"},
        {"getEventDX","()I","eventDX"}, {"getEventDY","()I","eventDY"},
        {"getEventButton","()I","button"}, {"getEventButtonState","()Z","pressed"},
        {"getEventDWheel","()I","wheel"}, {"getEventNanoseconds","()J","nanos"},
        {"isButtonDown","(I)Z","down"}, {"isInsideWindow","()Z","active"},
        {"getDX","()I","consumeDX"}, {"getDY","()I","consumeDY"}, {"getDWheel","()I","consumeWheel"}
    };

    @Override public String id() { return "virtual-gui-mouse"; }
    @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        return "org.lwjglx.input.Mouse".equals(name) && handle.isPresent();
    }
    @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle handle) {
        if (!shouldTransformClass(loader,context,manifest,name,handle)) return;
        ClassNode node = handle.getNode();
        Map<String,MethodNode> methods = new HashMap<>();
        for (MethodNode m : node.methods) {
            if (MARKER.equals(m.name)) return;
            if ((m.access & Opcodes.ACC_STATIC)!=0 && (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE))==0)
                methods.put(m.name+m.desc,m);
        }
        MethodNode next = methods.get("next()Z");
        if (next==null || !methods.containsKey("setCursorPosition(II)V") || !methods.containsKey("setGrabbed(Z)V")) return;
        for (String[] r : READERS) if (!methods.containsKey(r[0]+r[1])) return;
        for (String[] r : READERS) {
            MethodNode m=methods.get(r[0]+r[1]);
            if (r[2].startsWith("consume")) {
                // These getters consume accumulated motion/scroll. Discard their physical value only after the native read.
                for (AbstractInsnNode op : m.instructions.toArray()) if (op.getOpcode()==Opcodes.IRETURN) {
                    LabelNode original=new LabelNode(); InsnList filter=new InsnList();
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"active","()Z",false));
                    filter.add(new JumpInsnNode(Opcodes.IFEQ,original));
                    filter.add(new InsnNode(Opcodes.POP));
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,r[2],"()I",false)); filter.add(original);
                    m.instructions.insertBefore(op,filter);
                }
                // The old GUI callback may still unwind after ownership has changed. Leave newly
                // arrived manual deltas in the real accumulator for the player's next frame.
                LabelNode readNative = new LabelNode();
                InsnList handoff = new InsnList();
                handoff.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"handedBack","()Z",false));
                handoff.add(new JumpInsnNode(Opcodes.IFEQ,readNative));
                handoff.add(new InsnNode(Opcodes.ICONST_0));
                handoff.add(new InsnNode(Opcodes.IRETURN));
                handoff.add(readNative);
                m.instructions.insert(handoff);
                continue;
            }
            InsnList prefix=new InsnList();
            LabelNode original=new LabelNode();
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"active","()Z",false));
            prefix.add(new JumpInsnNode(Opcodes.IFEQ,original));
            if ("(I)Z".equals(m.desc)) prefix.add(new VarInsnNode(Opcodes.ILOAD,0));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,r[2],m.desc,false));
            prefix.add(new InsnNode(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)));
            prefix.add(original); m.instructions.insert(prefix);
        }
        // A GUI callback that closes a window must not warp or grab the real desktop pointer.
        for (String signature : new String[]{"setCursorPosition(II)V","setGrabbed(Z)V"}) {
            InsnList guard=new InsnList();
            LabelNode original=new LabelNode();
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"dispatching","()Z",false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ,original));
            guard.add(new InsnNode(Opcodes.RETURN)); guard.add(original);
            methods.get(signature).instructions.insert(guard);
        }
        // Consume physical events as usual while protected, without delivering them later at handoff.
        LabelNode start=new LabelNode(); next.instructions.insert(start);
        for (AbstractInsnNode op : next.instructions.toArray()) {
            if (op.getOpcode()!=Opcodes.IRETURN) continue;
            LabelNode done=new LabelNode(); InsnList filter=new InsnList();
            filter.add(new InsnNode(Opcodes.DUP)); filter.add(new JumpInsnNode(Opcodes.IFEQ,done));
            filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SCOPE,"active","()Z",false));
            filter.add(new JumpInsnNode(Opcodes.IFEQ,done)); filter.add(new InsnNode(Opcodes.POP));
            filter.add(new JumpInsnNode(Opcodes.GOTO,start)); filter.add(done);
            next.instructions.insertBefore(op,filter);
        }
        // A scoped input pass sees exactly its synthetic event. Leave physical queues to the normal client poll.
        LabelNode nativeQueue = new LabelNode();
        InsnList pump = new InsnList();
        pump.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "pumping", "()Z", false));
        pump.add(new JumpInsnNode(Opcodes.IFEQ, nativeQueue));
        pump.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "nextMouseEvent", "()Z", false));
        pump.add(new InsnNode(Opcodes.IRETURN));
        pump.add(nativeQueue);
        next.instructions.insert(pump);
        MethodNode marker=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_SYNTHETIC,MARKER,"()V",null,null);
        marker.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(marker);
        handle.computeFrames(); handle.markDirty();
    }
}

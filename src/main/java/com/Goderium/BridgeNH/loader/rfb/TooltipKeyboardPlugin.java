package com.Goderium.BridgeNH.loader.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import java.util.jar.Manifest;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** RFB can transform the compatibility class even when it skips legacy LaunchWrapper transformers. */
public final class TooltipKeyboardPlugin implements RfbPlugin {
    @Override public void onConstruction(PluginContext context) {
        // The plugin and its pure-JDK helper must share one parent-loader state with transformed LWJGL.
        Launch.classLoader.addClassLoaderExclusion("com.Goderium.BridgeNH.loader.rfb.");
    }
    @Override public RfbClassTransformer[] makeTransformers() { return new RfbClassTransformer[] {new KeyboardTransformer()}; }

    public static final class KeyboardTransformer implements RfbClassTransformer {
        private static final String OWNER = "com/Goderium/BridgeNH/loader/rfb/TooltipKeyScope";
        @Override public String id() { return "tooltip-keyboard-scope"; }
        @Override public boolean shouldTransformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle node) {
            return "org.lwjglx.input.Keyboard".equals(name) && node.isPresent();
        }
        @Override public void transformClass(ExtensibleClassLoader loader, Context context, Manifest manifest, String name, ClassNodeHandle node) {
            if (!shouldTransformClass(loader, context, manifest, name, node)) return;
            for (MethodNode method : node.getNode().methods) {
                if (!"isKeyDown".equals(method.name) || !"(I)Z".equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode && OWNER.equals(((MethodInsnNode) instruction).owner)) return;
                LabelNode nativePath = new LabelNode(); InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ILOAD, 0));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "override", "(I)Ljava/lang/Boolean;", false));
                guard.add(new InsnNode(Opcodes.DUP)); guard.add(new JumpInsnNode(Opcodes.IFNULL, nativePath));
                guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                guard.add(new InsnNode(Opcodes.IRETURN)); guard.add(nativePath); guard.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(guard); node.computeFrames(); node.markDirty(); return;
            }
        }
    }
}

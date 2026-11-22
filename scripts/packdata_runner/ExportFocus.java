package com.Goderium.BridgeNH.packtools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Client-thread scope for the single temporary world created by this export request. */
final class ExportFocus implements AutoCloseable {
    /** lwjgl3ify rewrites compiled LWJGL2 calls. Reflective calls must resolve its actual implementation. */
    static final class WindowProbe {
        private Method isActive;

        private WindowProbe(Method isActive) { this.isActive = isActive; }

        static WindowProbe find(ClassLoader loader) {
            try {
                Class<?> display;
                try {
                    display = Class.forName("org.lwjglx.opengl.Display", false, loader);
                } catch (ClassNotFoundException vanilla) {
                    display = Class.forName("org.lwjgl.opengl.Display", false, loader);
                }
                Method method = display.getMethod("isActive");
                return new WindowProbe(Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == boolean.class ? method : null);
            } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
                return new WindowProbe(null);
            }
        }

        Boolean foreground() {
            if (isActive == null) return null;
            try {
                return (Boolean)isActive.invoke(null);
            } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
                isActive = null;
                return null;
            }
        }
    }

    private final Object settings;
    private final Field pauseOnLostFocus;
    private final boolean originalPause;
    private final String save;
    private boolean mayResume;

    ExportFocus(Object settings, String save, Boolean foreground) throws ReflectiveOperationException {
        this.settings = settings;
        this.save = save;
        this.pauseOnLostFocus = settings.getClass().getField("field_82881_y");
        this.originalPause = pauseOnLostFocus.getBoolean(settings);
        this.mayResume = Boolean.FALSE.equals(foreground);
        pauseOnLostFocus.setBoolean(settings, false);
    }

    void observeForeground(Boolean foreground) {
        if (!Boolean.FALSE.equals(foreground)) mayResume = false;
    }

    boolean claimInitialPause(String currentSave, Boolean foreground, String screenClass) {
        observeForeground(foreground);
        if (!mayResume || !save.equals(currentSave) || !"net.minecraft.client.gui.GuiIngameMenu".equals(screenClass))
            return false;
        mayResume = false;
        return true;
    }

    @Override public void close() throws IllegalAccessException {
        pauseOnLostFocus.setBoolean(settings, originalPause);
        mayResume = false;
    }
}

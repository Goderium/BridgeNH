package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.JsonData;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.IReloadableResourceManager;

/** Standard resource-reload epoch and language identity; stores no texture bytes or screenshots. */
public final class NeiPreviewResources {
    private static final AtomicLong RELOAD = new AtomicLong();
    private static IResourceManager tracked;
    private static Minecraft client;

    public static synchronized void ensureTracking(Minecraft current) {
        if (current == null) return;
        client = current;
        IResourceManager manager = current.getResourceManager();
        if (manager == tracked) return;
        tracked = manager; RELOAD.incrementAndGet();
        if (manager instanceof IReloadableResourceManager) ((IReloadableResourceManager) manager).registerReloadListener(new IResourceManagerReloadListener() {
            @Override public void onResourceManagerReload(IResourceManager resources) { RELOAD.incrementAndGet(); }
        });
    }

    public static String context() {
        Minecraft current = client;
        String language = current == null || current.gameSettings == null ? "untracked" : current.gameSettings.language;
        return "resources:" + System.identityHashCode(tracked) + ':' + RELOAD.get() + ':' + JsonData.bounded(language, 128);
    }

    static void changedForFixture() { RELOAD.incrementAndGet(); }
}

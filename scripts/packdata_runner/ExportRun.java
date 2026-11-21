package com.Goderium.BridgeNH.packtools;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import alu.aiplayer.AiPlayerMod;

/** Temporary standalone Forge entrypoint in a single explicitly named export client. */
@Mod(modid = "bridgenhpackdatarun", name = "BridgeNH offline pack export", version = "2", acceptableRemoteVersions = "*")
public final class ExportRun {
    private final Properties request = new Properties();
    private Object minecraft;
    private File out;
    private String id, save;
    private int ticks, entered, stable, previousPages = -1;
    private boolean joined, started;
    private volatile boolean done;

    private String value(String key) {
        return new String(Base64.getDecoder().decode(request.getProperty(key)), StandardCharsets.UTF_8);
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Object call(Object receiver, String name) throws Exception {
        return receiver.getClass().getMethod(name).invoke(receiver);
    }
    private static Object field(Object receiver, String name) throws Exception {
        return receiver.getClass().getField(name).get(receiver);
    }
    private void marker(String name, String text) throws Exception {
        Files.write(new File(out, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    @Mod.EventHandler public void init(FMLInitializationEvent event) throws Exception {
        if (!event.getSide().isClient()) return;
        try (InputStream in = getClass().getResourceAsStream("/mcb-export.properties")) { request.load(in); }
        minecraft = type("net.minecraft.client.Minecraft").getMethod("func_71410_x").invoke(null);
        File game = ((File)field(minecraft, "field_71412_D")).getCanonicalFile();
        id = value("id"); save = "BridgeNH-Export-" + id;
        out = new File(value("out")).getCanonicalFile();
        if (!game.equals(new File(value("game")).getCanonicalFile()) || new File(game, "saves/" + save).exists()
                || !out.isDirectory() || !new File(out, "request-" + id).isFile()) return;
        marker("runner.started", id);
        marker("runner.pid", java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END) return;
        if (done) { done = false; call(minecraft, "func_71400_g"); return; }
        if (started || ++ticks % 20 != 0) return;
        try {
            if (field(minecraft, "field_71441_e") == null || field(minecraft, "field_71439_g") == null) {
                entered = ticks;
                Object screen = field(minecraft, "field_71462_r");
                if (!joined && ticks >= 120 && screen != null && (type("net.minecraft.client.gui.GuiMainMenu").isInstance(screen)
                        || screen.getClass().getName().equals("lumien.custommainmenu.gui.GuiCustom"))) {
                    joined = true;
                    File game = (File)field(minecraft, "field_71412_D");
                    if (new File(game, "saves/" + save).exists()) throw new IllegalStateException("Export world already exists");
                    Class<?> settings = type("net.minecraft.world.WorldSettings"), worldType = type("net.minecraft.world.WorldType");
                    Class<?> gameType = type("net.minecraft.world.WorldSettings$GameType");
                    Object world = settings.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType)
                        .newInstance(714L, gameType.getField("CREATIVE").get(null), false, false, worldType.getField("field_77138_c").get(null));
                    minecraft.getClass().getMethod("func_71371_a", String.class, String.class, settings).invoke(minecraft, save, save, world);
                }
                return;
            }
            if (!joined || !Boolean.TRUE.equals(call(minecraft, "func_71387_A"))
                    || !save.equals(call(call(minecraft, "func_71401_C"), "func_71270_I"))) return;
            Class<?> gui = type("codechicken.nei.recipe.GuiCraftingRecipe");
            List<Object> pages = new ArrayList<Object>((Collection<?>)gui.getField("craftinghandlers").get(null));
            pages.addAll((Collection<?>)gui.getField("serialCraftingHandlers").get(null));
            if (previousPages != pages.size()) { previousPages = pages.size(); stable = ticks; }
            if (pages.isEmpty() || ticks - entered < 1200 || ticks - stable < 600
                    || !type("codechicken.nei.ItemList").getField("loadFinished").getBoolean(null)
                    || !Boolean.TRUE.equals(type("codechicken.nei.NEIClientConfig").getMethod("isLoaded").invoke(null))) return;
            started = true;
            if (!value("tooltipItems").isEmpty())
                new SelectedTooltips().capture(minecraft, out, value("tooltipItems"), value("tooltipModes"));
            Thread worker = new Thread(() -> export(pages), "BridgeNH-offline-export");
            worker.setDaemon(true); worker.start();
        } catch (Throwable failure) {
            started = true; fail(failure); done = true;
        }
    }
    private void fail(Throwable error) {
        AiPlayerMod.LOG.error("Offline pack export failed", error);
        try { marker("export.failed", id + "\n" + error.toString()); } catch (Exception ignored) {}
    }
    private void export(List<Object> pages) {
        try {
            if (!value("benchItem").isEmpty()) {
                try { benchmark(pages); }
                catch (Throwable e) { marker("benchmark.failed", e.toString()); AiPlayerMod.LOG.warn("Recipe benchmark failed", e); }
            }
            if ("nei".equals(value("task")))
                type("alu.aiplayer.export.NeiExport").getMethod("run", File.class).invoke(null, out);
            marker("export.ok", id);
        } catch (Throwable error) { fail(error); }
        finally { done = true; }
    }
    private void benchmark(List<Object> pages) throws Exception {
        String itemId = value("benchItem"), handlerId = value("benchHandler");
        int meta = Integer.parseInt(value("benchMeta"));
        Class<?> item = type("net.minecraft.item.Item");
        Object registry = item.getField("field_150901_e").get(null);
        Object selected = registry.getClass().getMethod("func_82594_a", String.class).invoke(registry, itemId);
        if (selected == null) throw new IllegalArgumentException("Unknown item");
        Object stack = type("net.minecraft.item.ItemStack").getConstructor(item, int.class, int.class).newInstance(selected, 1, meta);
        Class<?> crafting = type("codechicken.nei.recipe.ICraftingHandler");
        Class<?> recipe = type("codechicken.nei.recipe.IRecipeHandler");
        Method load = crafting.getMethod("getRecipeHandler", String.class, Object[].class);
        for (Object page : pages) {
            if (!handlerId.equals(recipe.getMethod("getOverlayIdentifier").invoke(page))) continue;
            StringBuilder measurements = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                Object handler = load.invoke(page, "item", new Object[]{stack});
                int count = (Integer) recipe.getMethod("numRecipes").invoke(handler);
                measurements.append(System.nanoTime()-start).append(" ").append(count).append("\n");
            }
            marker("benchmark.txt", measurements.toString());
            return;
        }
        throw new IllegalArgumentException("Handler absent: " + handlerId);
    }
}

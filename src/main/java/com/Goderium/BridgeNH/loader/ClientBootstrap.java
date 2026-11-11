package com.Goderium.BridgeNH.loader;

import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.client.nei.NeiPreviewResources;
import com.Goderium.BridgeNH.client.nei.NeiRenderCapture;
import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.config.LoopbackPortPolicy;
import com.Goderium.BridgeNH.config.NavigationCosts;
import com.Goderium.BridgeNH.config.NavigationSettings;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.network.RemoteReadClient;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

public class ClientBootstrap {
    private static GameBridge activeBridge;

    public static void recordCommand(String text) { if (activeBridge != null) activeBridge.recordActivityCommand(text); }
    public static void recordMouse(net.minecraft.client.gui.GuiScreen screen) { if (activeBridge != null) activeBridge.recordActivityMouse(screen); }
    public static void recordKey(net.minecraft.client.gui.GuiScreen screen) { if (activeBridge != null) activeBridge.recordActivityKey(screen); }
    public static void recordSlot(int window, int slot, int button, int mode) { if (activeBridge != null) activeBridge.recordActivitySlot(window, slot, button, mode); }

    public static void loadingFrame() {
        if (activeBridge != null) activeBridge.loadingFrame();
    }

    public static String instancePath() {
        try { return net.minecraft.client.Minecraft.getMinecraft().mcDataDir.getCanonicalFile().getParentFile().getCanonicalPath(); }
        catch (java.io.IOException failure) { throw new BridgeException("configuration_error", "Cannot resolve the current Minecraft instance directory."); }
    }

    public static void initialize(FMLInitializationEvent event) {
        NeiPreviewResources.ensureTracking(net.minecraft.client.Minecraft.getMinecraft());
        if (activeBridge != null) ClientControlCommand.register(activeBridge);
    }

    public static void start(FMLPreInitializationEvent event) {
        RemoteReadClient.initialize();
        BridgeHttpServer http = null;
        try {
            Configuration config = new Configuration(event.getSuggestedConfigurationFile());
            config.load();
            int port = config.getInt("port", "http", 25671, 1, 65535, "Loopback HTTP port. Restart the client after changing it.");
            int radiusChunks = config.getInt("radiusChunks", "scan", ScanSettings.DEFAULT_RADIUS_CHUNKS, 1, ScanSettings.MAX_CHUNKS,
                    "Default horizontal world observation radius in chunks. Runtime /mcb scan changes are session-only.");
            int verticalRadiusChunks = config.getInt("verticalRadiusChunks", "scan", ScanSettings.DEFAULT_RADIUS_CHUNKS, 1, ScanSettings.MAX_CHUNKS,
                    "Default vertical world observation radius in chunks. Runtime /mcb scan changes are session-only.");
            int cacheRadiusChunks = config.getInt("cacheRadiusChunks", "scan", ScanSettings.DEFAULT_CACHE_RADIUS_CHUNKS, 1, ScanSettings.MAX_CACHE_CHUNKS,
                    "Horizontal local loaded-chunk cache radius. Cache reads never force chunk loading.");
            int cacheVerticalRadiusChunks = config.getInt("cacheVerticalRadiusChunks", "scan", ScanSettings.DEFAULT_CACHE_RADIUS_CHUNKS, 1, ScanSettings.MAX_CACHE_CHUNKS,
                    "Vertical local loaded-chunk cache radius. Cache reads never force chunk loading.");
            int cacheMaxMiB = config.getInt("cacheMaxMiB", "scan", ScanSettings.DEFAULT_CACHE_MAX_MIB, 64, ScanSettings.MAX_CACHE_MIB,
                    "Lazy local cache retention accounting budget in MiB; not preallocated memory or total JVM heap. Runtime /mcb cache memory changes are session-only.");
            NavigationSettings.configure(config.getInt("maxJumpBlocks", "navigation", 1, 0, 1,
                    "Maximum ordinary grounded jump height. 0 disables jumping; native normal jumps support 1 block."),
                    config.getInt("maxDropBlocks", "navigation", 3, 0, 3,
                    "Maximum ordinary grounded walk-off drop with proved landing support. 0 disables dropping."));
            NavigationCosts.walkTicksPerBlock = config.getFloat("walkTicksPerBlock","navigation",4.65F,1F,100F,"Estimated walking ticks per block for route and structure action scoring.");
            NavigationCosts.jumpTicks = config.getFloat("jumpTicks","navigation",8F,1F,100F,"Additional estimated ticks per jumped block.");
            NavigationCosts.dropRecoveryTicks = config.getFloat("dropRecoveryTicks","navigation",3F,0F,100F,"Estimated landing recovery ticks after falling.");
            NavigationCosts.placeTicks = config.getFloat("placeTicks","navigation",4F,1F,100F,"Estimated native placement ticks.");
            NavigationCosts.materialPenaltyTicks = config.getFloat("materialPenaltyTicks","navigation",8F,0F,1000F,"Resource item penalty in the same tick cost unit.");
            if (config.hasChanged()) config.save();
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            GameBridge bridge = new GameBridge(new ScanSettings(radiusChunks, verticalRadiusChunks, cacheRadiusChunks, cacheVerticalRadiusChunks, cacheMaxMiB));
            bridge.initializePolicies(new File(event.getModConfigurationDirectory(), "codexbridge/policies.json").toPath());
            http = new BridgeHttpServer(bridge, token);
            LoopbackPortPolicy.start(port, http::start);
            File directory = new File(event.getModConfigurationDirectory(), "codexbridge");
            Files.createDirectories(directory.toPath());
            File runtime = new File(directory, "runtime.json");
            JsonObject info = new JsonObject();
            info.addProperty("protocolVersion", 1);
            info.addProperty("baseUrl", "http://127.0.0.1:" + http.port());
            info.addProperty("token", token);
            info.addProperty("instancePath", instancePath());
            info.addProperty("sessionId", bridge.runtimeSessionId());
            File temporary = new File(directory, "runtime.json.tmp");
            Files.write(temporary.toPath(), new Gson().toJson(info).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), runtime.toPath(), StandardCopyOption.REPLACE_EXISTING);
            FMLCommonHandler.instance().bus().register(bridge);
            MinecraftForge.EVENT_BUS.register(bridge);
            bridge.registerActivityListeners();
            MinecraftForge.EVENT_BUS.register(new NeiRenderCapture());
            activeBridge = bridge;
            final BridgeHttpServer service = http;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                bridge.shutdownActivity();
                service.stop();
                try { Files.deleteIfExists(runtime.toPath()); } catch (Exception ignored) { }
            }, "Codex Bridge shutdown"));
            event.getModLog().info("Codex Bridge listening on 127.0.0.1:" + http.port() + "; session credentials saved to config/codexbridge/runtime.json.");
        } catch (Exception error) {
            if (http != null) http.stop();
            event.getModLog().error("Codex Bridge could not start: " + error.getClass().getSimpleName() + ". Check the configured port and config directory.");
        }
    }
}

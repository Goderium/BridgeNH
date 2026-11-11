package com.Goderium.BridgeNH;

import com.Goderium.BridgeNH.common.dialogue.AgentDialogueCommand;
import com.Goderium.BridgeNH.common.qa.HorizonQaAdapter;
import com.Goderium.BridgeNH.loader.ClientBootstrap;
import com.Goderium.BridgeNH.network.RemoteReadNetwork;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.LoadedModEvidence;
import com.Goderium.BridgeNH.util.Tags;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import net.minecraft.server.MinecraftServer;

@Mod(modid = "codexbridge", name = "BridgeNH", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class BridgeNH {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LoadedModEvidence.captureBridge();
        try { AgentDialogueCommand.initializePermissions(new java.io.File(event.getModConfigurationDirectory(), "codexbridge/permissions.json").toPath()); }
        catch (BridgeException failure) { org.apache.logging.log4j.LogManager.getLogger("MCB").error(failure.getMessage()); }
        RemoteReadNetwork.initialize();
        HorizonQaAdapter.preInit(event.getAsmData());
        if (event.getSide().isClient()) ClientBootstrap.start(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide().isClient()) ClientBootstrap.initialize(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        try { AgentDialogueCommand.reloadPermissions(); }
        catch (BridgeException failure) { org.apache.logging.log4j.LogManager.getLogger("MCB").error(failure.getMessage()); }
        event.registerServerCommand(new AgentDialogueCommand());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        RemoteReadNetwork.resetServer();
        HorizonQaAdapter.serverStarted(MinecraftServer.getServer());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        RemoteReadNetwork.resetServer();
        HorizonQaAdapter.serverStopped();
    }
}

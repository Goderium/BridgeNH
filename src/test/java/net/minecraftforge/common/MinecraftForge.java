package net.minecraftforge.common;

import cpw.mods.fml.common.eventhandler.EventBus;

/** Headless event bus for the real FluidContainerRegistry public registration fixture. */
public final class MinecraftForge {
    public static final EventBus EVENT_BUS = new EventBus();
}

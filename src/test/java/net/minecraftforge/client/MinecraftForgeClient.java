package net.minecraftforge.client;

import net.minecraft.item.ItemStack;

/** Public inventory renderer query only; no OpenGL initialization or render callback. */
public final class MinecraftForgeClient {
    public static IItemRenderer fixtureRenderer;
    public static int fixtureQueries;
    public static IItemRenderer getItemRenderer(ItemStack stack, IItemRenderer.ItemRenderType type) { fixtureQueries++; return fixtureRenderer != null && fixtureRenderer.handleRenderType(stack, type) ? fixtureRenderer : null; }
}

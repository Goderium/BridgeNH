package com.Goderium.BridgeNH.loader.core;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UniMixins registers this observer before FontRenderer is loaded. No game classes are referenced here. */
@IFMLLoadingPlugin.Name("CodexBridgeNeiReadObserver")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({"com.Goderium.BridgeNH.loader.core."})
public final class NeiObserverLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override public String[] getASMTransformerClass() { return new String[0]; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
    @Override public String getMixinConfig() { return "mixins.codexbridge.nei_observer.json"; }
    @Override public List<String> getMixins(Set<String> loadedCoreMods) {
        if (!FMLLaunchHandler.side().isClient()) return Collections.<String>emptyList();
        List<String> mixins = new ArrayList<String>(Arrays.asList("NeiFontRendererMixin", "GuiPointerEntityRendererMixin", "GuiPointerGuiDrawMixin", "NeiBlockGeometryMixin", "NeiTessellatorGeometryMixin", "NeiTileGeometryMixin", "OwnedLogicalKeyBindingMixin", "OwnedAttackMinecraftMixin", "LoadingScreenQueueMixin", "ActivityChatMixin", "ActivityGuiInputMixin", "ActivitySlotClickMixin"));
        ClassLoader loader = getClass().getClassLoader();
        if (loader.getResource("com/prupe/mcpatcher/ctm/CTMUtils.class") != null) mixins.add("NeiCtmGeometryMixin");
        if (loader.getResource("com/gtnewhorizons/angelica/rendering/tesr/TesrBatchRenderer.class") != null) mixins.add("NeiTesrGeometryMixin");
        if (loader.getResource("com/gtnewhorizons/angelica/lwjgl3/Lwjgl3GLRenderBackend.class") != null) mixins.add("NeiAngelicaGeometryMixin");
        if (loader.getResource("codechicken/nei/guihook/GuiContainerManager.class") != null
                && loader.getResource("com/llamalad7/mixinextras/injector/ModifyExpressionValue.class") != null) mixins.add("NeiTooltipDecisionMixin");
        return mixins;
    }
}

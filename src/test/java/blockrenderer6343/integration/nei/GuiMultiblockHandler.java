package blockrenderer6343.integration.nei;

import blockrenderer6343.client.renderer.WorldSceneRenderer;
import blockrenderer6343.client.utils.ConstructableData;
import com.gtnewhorizon.gtnhlib.blockpos.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.item.ItemStack;
import org.joml.Vector3f;

/** Exact owner/member names only; the fixture never creates or renders a client scene. */
public class GuiMultiblockHandler {
    protected WorldSceneRenderer renderer;
    public static final BlockPos MB_PLACE_POS = new BlockPos(0, 64, 0);
    protected final BlockPos selectedBlock = new BlockPos(-100000, 0, -100000);
    protected static ItemStack DEFAULT_TRIGGER;
    protected Vector3f center = new Vector3f(0, 70, 0);
    protected float rotationYaw = 20, rotationPitch = 50, zoom = 30;
    protected int layerIndex = -1;
    protected ConstructableData constructableData = new ConstructableData();
    protected List<Object> allButtons = new ArrayList<>();
    protected ItemStack trigger;
    protected int scrolled;
    protected Object renderingController, lastRenderingController;
    protected ItemStack stackForm;
    protected ItemStack tooltipBlockStack;
    protected final java.util.Map<Long, Object> structureElementMap = new java.util.LinkedHashMap<>();
    protected String lastSearch = "";
    protected float scaleFactor = 1;
    protected Consumer<List<?>> onCandidateChanged;
    public final ConstructableData fixtureData() { return constructableData; }
    public final List<Object> fixtureButtons() { return allButtons; }
    public final void fixtureInit(WorldSceneRenderer sceneRenderer, ItemStack stack, Consumer<List<?>> callback) {
        renderer = sceneRenderer; trigger = stack; DEFAULT_TRIGGER = stack; onCandidateChanged = callback;
        center.set(0, 70, 0); rotationYaw = 20; rotationPitch = 50; zoom = 30; layerIndex = -1;
        selectedBlock.set(-100000, 0, -100000);
    }
    public final void fixtureController(Object controller) { renderingController = lastRenderingController = controller; }
    public final java.util.Map<Long, Object> fixtureElements() { return structureElementMap; }
    public final ItemStack fixtureTrigger() { return trigger; }
    public final void fixtureLayer(int layer) { layerIndex = layer; }
}

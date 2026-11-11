package blockrenderer6343.client.world;

import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Vector3f;

public class TrackedDummyWorld {
    public final Map<Long, Object> blockMap = new LinkedHashMap<>();
    public final Map<Long, Integer> blockMetaMap = new LinkedHashMap<>();
    public final Map<Long, Object> tileMap = new LinkedHashMap<>();
    protected int visibleYLevel = -1;
    public final Vector3f fixtureMin = new Vector3f(-5, 62, -9);
    public final Vector3f fixtureMax = new Vector3f(5, 81, 1);
    public Vector3f getMinPos() { return fixtureMin; }
    public Vector3f getMaxPos() { return fixtureMax; }
}

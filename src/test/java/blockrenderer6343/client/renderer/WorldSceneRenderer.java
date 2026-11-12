package blockrenderer6343.client.renderer;

import blockrenderer6343.client.world.TrackedDummyWorld;
import java.util.LinkedHashSet;
import java.util.Set;
import org.joml.Vector3f;

public class WorldSceneRenderer {
    public final TrackedDummyWorld world = new TrackedDummyWorld();
    public final Set<Long> renderedBlocks = new LinkedHashSet<>();
    public final Set<Long> renderTranslucentBlocks = new LinkedHashSet<>();
    private final Vector3f eye = new Vector3f(), look = new Vector3f(), up = new Vector3f(0, 1, 0);
    public int fixtureCameraCalls;
    public Vector3f getEyePos() { return eye; }
    public Vector3f getLookAt() { return look; }
    public Vector3f getWorldUp() { return up; }
    public net.minecraft.util.MovingObjectPosition getLastTraceResult() { return null; }
    public void setCameraLookAt(Vector3f center, double radius, double rotationPitch, double rotationYaw) {
        fixtureCameraCalls++; look.set(center.x, center.y, center.z);
        double x = Math.cos(rotationPitch), z = Math.sin(rotationPitch), y = Math.tan(rotationYaw);
        double length = Math.sqrt(x * x + y * y + z * z);
        eye.set((float) (center.x + radius * x / length), (float) (center.y + radius * y / length), (float) (center.z + radius * z / length));
    }
}

package thaumcraft.api;
import java.util.HashSet;
import java.util.Set;
import thaumcraft.api.aspects.Aspect;
public class ThaumcraftApiHelper {
    public static final Set<Aspect> discovered = new HashSet<Aspect>();
    public static boolean hasDiscoveredAspect(String username, Aspect aspect) { return discovered.contains(aspect); }
}

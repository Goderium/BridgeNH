package appeng.api.util;
public class NamedDimensionalCoord extends DimensionalCoord {
    private final String name;
    public NamedDimensionalCoord(int x, int y, int z, int dim, String name) { super(x, y, z, dim); this.name = name; }
    public String getCustomName() { return name; }
}

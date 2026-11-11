package appeng.api.util;
public class DimensionalCoord extends WorldCoord {
    protected final int dimId;
    public DimensionalCoord(int x, int y, int z, int dim) { super(x, y, z); dimId = dim; }
    public int getDimension() { return dimId; }
}

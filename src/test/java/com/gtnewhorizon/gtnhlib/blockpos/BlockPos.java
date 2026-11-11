package com.gtnewhorizon.gtnhlib.blockpos;

import org.joml.Vector3i;

public class BlockPos extends Vector3i {
    public BlockPos(int x, int y, int z) { super(x, y, z); }
    public BlockPos set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
}

package com.Goderium.BridgeNH.mixin;

import com.Goderium.BridgeNH.client.control.WheelEventAccess;
import net.minecraftforge.client.event.MouseEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MouseEvent.class, remap = false)
public abstract class WorldWheelEventMixin implements WheelEventAccess {
    @Shadow @Final @Mutable public int x;
    @Shadow @Final @Mutable public int y;
    @Shadow @Final @Mutable public int dx;
    @Shadow @Final @Mutable public int dy;
    @Shadow @Final @Mutable public int dwheel;
    @Shadow @Final @Mutable public int button;
    @Shadow @Final @Mutable public boolean buttonstate;
    @Shadow @Final @Mutable public long nanoseconds;

    public void mcb$initializeWheel(int centerX, int centerY, int delta) {
        x = centerX;
        y = centerY;
        dx = dy = 0;
        dwheel = delta;
        button = -1;
        buttonstate = false;
        nanoseconds = System.nanoTime();
    }
}

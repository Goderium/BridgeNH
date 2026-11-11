package com.cleanroommc.modularui.api;

import java.io.IOException;
import net.minecraft.network.PacketBuffer;

public interface IPacketWriter {
    void write(PacketBuffer buffer) throws IOException;
}

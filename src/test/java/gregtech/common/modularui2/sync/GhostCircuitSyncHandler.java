package gregtech.common.modularui2.sync;

import com.cleanroommc.modularui.api.IPacketWriter;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import net.minecraft.network.PacketBuffer;

/** Captures only the public native submission; local ghost inventory writes remain forbidden. */
public abstract class GhostCircuitSyncHandler {
    public static final int SYNC_CIRCUIT_CONFIG = 37;
    public PanelSyncManager manager;
    public boolean valid = true, allowC2S = true, throwAfterSend;
    public int packets, packetId, payload;
    public abstract Object getGhostCircuitHandler();
    public boolean isValid() { return valid; }
    public boolean isAllowC2S() { return allowC2S; }
    public PanelSyncManager getSyncManager() { return manager; }
    public boolean isRegistered(PanelSyncManager manager) { return this.manager == manager && manager.registered && manager.registeredHandler == this; }
    public final void syncToServer(int id, IPacketWriter writer) throws IOException {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            writer.write(buffer);
            if (buffer.readableBytes() != 2) throw new AssertionError("Exactly one native short payload is required");
            packetId = id; payload = buffer.readShort(); packets++;
            if (throwAfterSend) throw new IOException("Fixture failure after native submission");
        } finally { buffer.release(); }
    }
}

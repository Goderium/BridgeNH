package com.Goderium.BridgeNH.config;

import java.io.IOException;
import java.net.BindException;

/** A configured loopback port collision may use one OS-allocated port for this session. */
public final class LoopbackPortPolicy {
    public interface Starter {
        void start(int port) throws IOException;
    }

    public static void start(int configuredPort, Starter starter) throws IOException {
        try {
            starter.start(configuredPort);
        } catch (BindException collision) {
            starter.start(0);
        }
    }
}

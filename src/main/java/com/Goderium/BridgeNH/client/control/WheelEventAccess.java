package com.Goderium.BridgeNH.client.control;

/** Initializes a newly allocated Forge event before it is published. */
public interface WheelEventAccess {
    void mcb$initializeWheel(int centerX, int centerY, int delta);
}

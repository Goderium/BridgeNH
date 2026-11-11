package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.util.BridgeException;

/** Creative flight uses the player's existing permission and the vanilla ability packet. */
public class FlightPolicy {
    public static void requireChange(boolean creative, boolean allowFlying, boolean onGround, boolean mounted, boolean flying) {
        if (!creative || !allowFlying)
            throw new BridgeException("permission_denied", "Flight control requires existing creative flight permission; no ability is granted.");
        if (mounted) throw new BridgeException("invalid_state", "Dismount before changing flight.");
        if (flying && onGround)
            throw new BridgeException("invalid_state", "Jump and confirm onGround=false before enabling flight; vanilla clears flight on landing.");
    }
}

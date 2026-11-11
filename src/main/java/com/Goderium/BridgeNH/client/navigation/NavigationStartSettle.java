package com.Goderium.BridgeNH.client.navigation;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;

/** Read-only release/coast barrier before a walking route captures its immutable start. */
public final class NavigationStartSettle {
    private final PlayerNavigation.Point initial;
    private long lastTick = -1;
    private int quietTicks;
    public NavigationStartSettle(JsonObject snapshot) { initial=PlayerNavigation.pose(PlayerNavigation.object(snapshot,"player")); }
    public JsonObject observe(JsonObject snapshot, long tick) {
        JsonObject player=PlayerNavigation.object(snapshot,"player");
        JsonObject server=PlayerNavigation.object(PlayerNavigation.object(snapshot,"serverControl"),"pose");
        PlayerNavigation.Point current=PlayerNavigation.pose(player);
        if (current.distance(initial)>2) throw new BridgeException("navigation_start_changed","Released-input coasting exceeded the bounded two-block start observation.");
        boolean quiet=quiet(player) && quiet(server) && current.distance(PlayerNavigation.pose(server))<=PlayerNavigation.TOLERANCE
                && PlayerNavigation.motion(player,"nativeMoveForward")==0 && PlayerNavigation.motion(player,"nativeMoveStrafing")==0;
        if (!quiet) quietTicks=0;
        else if (tick>lastTick) quietTicks++;
        lastTick=tick;
        if (quietTicks<2) return null;
        JsonObject result=new JsonObject(); result.addProperty("readOnly",true); result.addProperty("inputDispatched",false);
        result.addProperty("quietClientTicks",quietTicks); result.addProperty("coastDistance",current.distance(initial));
        result.add("start",current.json()); return result;
    }
    public static boolean quiet(JsonObject pose) {
        return PlayerNavigation.flag(pose,"onGround") && !PlayerNavigation.flag(pose,"flying")
                && Math.abs(PlayerNavigation.motion(pose,"motionX"))<=1e-7 && Math.abs(PlayerNavigation.motion(pose,"motionZ"))<=1e-7
                && PlayerNavigation.motion(pose,"motionY")<=0;
    }
}

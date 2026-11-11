package com.cleanroommc.modularui.drawable;

/** Shared public resource identity and atlas area fixture. */
public class UITexture {
    public final String location;
    public final float u0 = 0.1F, v0 = 0.2F, u1 = 0.3F, v1 = 0.4F;
    public UITexture(String location) { this.location = location; }
    public String getLocation() { return location; }
}

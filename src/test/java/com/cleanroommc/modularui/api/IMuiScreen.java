package com.cleanroommc.modularui.api;

/** Test-only marker fixture; the production jar has no ModularUI dependency. */
public interface IMuiScreen { default Object getScreen() { return null; } }

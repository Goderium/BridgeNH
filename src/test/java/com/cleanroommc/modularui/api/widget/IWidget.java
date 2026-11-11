package com.cleanroommc.modularui.api.widget;

import java.awt.Rectangle;
import java.util.List;

/** Public methods used by the bounded GUI data walker; no renderer or native input. */
public interface IWidget {
    String getName();
    boolean isEnabled();
    boolean isValid();
    Rectangle getArea();
    List<?> getChildren();
}

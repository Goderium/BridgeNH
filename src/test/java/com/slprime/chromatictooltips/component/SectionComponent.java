package com.slprime.chromatictooltips.component;

import java.util.Collections;
import java.util.List;

/** Public existing-child getter fixture, independent of Chromatic's renderer implementation. */
public class SectionComponent {
    public final List<Object> components;
    public SectionComponent(List<Object> components) { this.components = components; }
    public List<Object> getComponents() { return Collections.unmodifiableList(components); }
    public int getWidth() { return 100; }
    public int getHeight() { return 80; }
    public int getSpacing() { return 1; }
    public void draw() { throw new AssertionError("No section redraw"); }
    public void paginate() { throw new AssertionError("No section pagination"); }
}

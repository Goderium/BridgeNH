package com.slprime.chromatictooltips.component;

import java.util.ArrayList;
import java.util.List;

/** Public pure getter fixture; rendering and pagination must never be invoked. */
public class TextComponent {
    public final List<String> lines;
    public TextComponent(List<String> lines) { this.lines = lines; }
    public List<String> getLines() { return new ArrayList<String>(lines); }
    public int getWidth() { return 25; }
    public int getHeight() { return lines.size() * 9; }
    public int getSpacing() { return 2; }
    public void draw() { throw new AssertionError("No tooltip redraw"); }
    public void paginate() { throw new AssertionError("No tooltip pagination"); }
}

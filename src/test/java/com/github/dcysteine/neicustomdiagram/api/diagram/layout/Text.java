package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import com.github.dcysteine.neicustomdiagram.api.draw.Dimension;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
public class Text {
    private final String text;
    public Text(String text) { this.text = text; }
    public String text() { return text; }
    public Point position() { return new Point(2, 4); }
    public Dimension dimension() { return new Dimension(30, 9); }
    public int colour() { return 0x123456; }
    public boolean small() { return false; }
    public boolean shadow() { return true; }
}

package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
import com.gtnewhorizon.gtnhlib.color.ColorResource;
import java.util.List;
public class Lines {
    public static class Segment { private final Point a, b; public Segment(Point a, Point b) { this.a = a; this.b = b; } public Point a() { return a; } public Point b() { return b; } }
    public final List<Segment> segments;
    public Lines(List<Segment> segments) { this.segments = segments; }
    public List<Segment> segments() { return segments; }
    public List<Object> arrows() { return java.util.Collections.emptyList(); }
    public ColorResource colour() { return new ColorResource(0x234567); }
}

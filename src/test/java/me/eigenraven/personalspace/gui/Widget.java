package me.eigenraven.personalspace.gui;
import java.awt.Rectangle;
import java.util.ArrayList;
public class Widget {
    public Rectangle position = new Rectangle(0, 0, 120, 20);
    public boolean visible = true, enabled = true;
    public ArrayList<Widget> children = new ArrayList<Widget>();
    public int presses, releases;
    public boolean mouseClicked(int x, int y, int button) { presses++; return visible && enabled && position.contains(x, y); }
    public boolean mouseMovedOrUp(int x, int y, int button) { releases++; return true; }
}

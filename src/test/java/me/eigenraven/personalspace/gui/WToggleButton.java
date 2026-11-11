package me.eigenraven.personalspace.gui;
public class WToggleButton extends WButton {
    public boolean value;
    public boolean getValue() { return value; }
    public boolean mouseClicked(int x, int y, int button) { value=!value; return super.mouseClicked(x,y,button); }
}

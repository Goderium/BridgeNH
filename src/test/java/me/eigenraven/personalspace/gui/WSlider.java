package me.eigenraven.personalspace.gui;
public class WSlider extends Widget {
    public double minValue=0, maxValue=255, step=1, rawValue;
    public double getValue() { return Math.round(rawValue/step)*step; }
    public String getText() { return "slider"; }
    public boolean mouseClicked(int x,int y,int button) { rawValue=minValue+(double)(x-position.x-3)/(position.width-6)*(maxValue-minValue); return super.mouseClicked(x,y,button); }
}

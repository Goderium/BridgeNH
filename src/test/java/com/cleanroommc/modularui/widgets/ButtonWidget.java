package com.cleanroommc.modularui.widgets;
import com.cleanroommc.modularui.widget.Widget;
public class ButtonWidget extends Widget {
    public int presses, releases, taps;
    public int lastButton=-1;
    public String pressResult="SUCCESS", order="";
    public boolean hovering;
    public boolean isHovering() { return hovering; }
    public Object onMousePressed(int button) { presses++; lastButton=button; order+="P"; return pressResult; }
    public boolean onMouseRelease(int button) { releases++; lastButton=button; order+="R"; return true; }
    public Object onMouseTapped(int button) { taps++; lastButton=button; order+="T"; return "SUCCESS"; }
}

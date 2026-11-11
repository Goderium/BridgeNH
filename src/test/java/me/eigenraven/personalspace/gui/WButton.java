package me.eigenraven.personalspace.gui;
public class WButton extends Widget {
    public String text = "button", tooltip, itemStackText;
    public Object buttonIcon, itemStack;
    public Runnable onClick;
    public int lastButton;
    public String getText() { return text; }
    public boolean mouseClicked(int x, int y, int button) { if (!super.mouseClicked(x,y,button)) return false; lastButton=button; if(onClick!=null)onClick.run(); return true; }
}

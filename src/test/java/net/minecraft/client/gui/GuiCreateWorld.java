package net.minecraft.client.gui;

import java.util.List;
import net.minecraft.world.WorldType;

/** Test-only vanilla menu transitions, without a renderer, client or save creation. */
public class GuiCreateWorld extends GuiScreen {
    private String field_146342_r = "survival";
    private boolean field_146341_s = true, field_146340_t, field_146339_u, field_146338_v, field_146337_w, field_146344_y;
    private int field_146331_K;
    public String field_146334_a = "";
    private final GuiButton field_146343_z = new GuiButton(2, 0, 0, "Mode");
    private final GuiButton field_146324_A = new GuiButton(3, 0, 0, "Options");
    private final GuiButton field_146325_B = new GuiButton(4, 0, 0, "Features");
    private final GuiButton field_146320_D = new GuiButton(5, 0, 0, "Type");
    private final GuiButton field_146321_E = new GuiButton(6, 0, 0, "Commands");
    private final GuiButton field_146326_C = new GuiButton(7, 0, 0, "Bonus");
    public int callbacks, creates;
    public boolean ignore, fail;
    public Runnable callback;
    public GuiCreateWorld() {
        buttonList.add(new GuiButton(0, 0, 0, "Create"));
        for (GuiButton button : new GuiButton[]{field_146343_z, field_146324_A, field_146325_B, field_146320_D, field_146321_E, field_146326_C}) buttonList.add(button);
        refresh();
    }
    public List<?> buttons() { return buttonList; }
    private void refresh() {
        field_146343_z.visible = !field_146344_y;
        for (GuiButton button : new GuiButton[]{field_146325_B, field_146320_D, field_146321_E, field_146326_C}) button.visible = field_146344_y;
        field_146321_E.enabled = field_146326_C.enabled = !field_146337_w;
        field_146343_z.displayString = "Mode: " + field_146342_r;
        field_146320_D.displayString = "Type: " + WorldType.worldTypes[field_146331_K].getWorldTypeName();
    }
    @Override protected void actionPerformed(GuiButton button) {
        callbacks++; if (fail) throw new IllegalStateException("fixture callback failure");
        if (!ignore) switch (button.id) {
            case 0: creates++; break;
            case 2:
                field_146342_r = field_146342_r.equals("survival") ? "hardcore" : field_146342_r.equals("hardcore") ? "creative" : "survival";
                field_146337_w = field_146342_r.equals("hardcore");
                if (!field_146339_u) field_146340_t = field_146342_r.equals("creative");
                break;
            case 3: field_146344_y = !field_146344_y; break;
            case 4: field_146341_s = !field_146341_s; break;
            case 5:
                do { field_146331_K = (field_146331_K + 1) % WorldType.worldTypes.length; }
                while (WorldType.worldTypes[field_146331_K] == null || !WorldType.worldTypes[field_146331_K].getCanBeCreated());
                field_146334_a = ""; break;
            case 6: field_146339_u = true; field_146340_t = !field_146340_t; break;
            case 7: field_146338_v = !field_146338_v; break;
            default: break;
        }
        refresh(); if (callback != null) callback.run();
    }
}

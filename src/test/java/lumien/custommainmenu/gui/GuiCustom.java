package lumien.custommainmenu.gui;

/** Public identity fixture; omits GuiScreen inheritance to avoid game renderer initialization. */
public class GuiCustom {
    public final lumien.custommainmenu.configuration.GuiConfig guiConfig;

    public GuiCustom(lumien.custommainmenu.configuration.GuiConfig guiConfig) { this.guiConfig = guiConfig; }
}

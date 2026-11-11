package me.eigenraven.personalspace.gui;
public class GuiEditWorld {
    public int guiLeft=30,guiTop=40;
    public Widget rootWidget=new Widget(),page1Container=new Widget(),page2Container=new Widget(),presetEditor=new Widget();
    public WButton save=new WButton(),cancel=new WButton(),hidden=new WButton();
    public WToggleButton enableWeather=new WToggleButton();
    public WTextField presetEntry=new WTextField();
    public WSlider skyRed=new WSlider();
    public WBlockDropdown boundaryBlockADropdown=new WBlockDropdown();
    public GuiEditWorld() {
        rootWidget.children.add(page1Container);rootWidget.children.add(page2Container);
        page2Container.visible=false;page2Container.children.add(hidden);
        page1Container.children.add(enableWeather);page1Container.children.add(presetEntry);page1Container.children.add(skyRed);
        page1Container.children.add(presetEditor);presetEditor.children.add(new WButton());
        rootWidget.children.add(save);rootWidget.children.add(cancel);
        page1Container.children.add(boundaryBlockADropdown);
    }
}

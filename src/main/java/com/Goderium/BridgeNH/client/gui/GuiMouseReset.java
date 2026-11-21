package com.Goderium.BridgeNH.client.gui;

/** Clear an abandoned native gesture without a release callback, which could activate a control. */
public interface GuiMouseReset {
    void mcb$cancelGuiMouse();
    interface Slider { void mcb$cancelSliderDrag(); }
    interface ContainerDrag {
        void mcb$cancelContainerDrag();
        void mcb$forgetVirtualClicks();
    }
}

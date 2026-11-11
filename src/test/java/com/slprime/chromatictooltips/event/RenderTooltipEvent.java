package com.slprime.chromatictooltips.event;

import cpw.mods.fml.common.eventhandler.Event;

/** Public-field event shape fixture, not the installed renderer or live event delivery. */
public class RenderTooltipEvent extends Event {
    public final Object context, page;
    public final int x, y, width, height;
    public RenderTooltipEvent(Object context, Object page, int x, int y, int width, int height) {
        this.context = context; this.page = page; this.x = x; this.y = y; this.width = width; this.height = height;
    }
}

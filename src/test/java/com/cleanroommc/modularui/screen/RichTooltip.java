package com.cleanroommc.modularui.screen;

import java.util.ArrayList;
import java.util.List;

/** A dirty tooltip is empty until its native public read path refreshes the builder. */
public class RichTooltip {
    public final Text text = new Text();
    public Runnable builder;
    public boolean dirty = true;
    public boolean autoUpdate;
    public int builds;
    public boolean isEmpty() {
        if (dirty) {
            dirty = false;
            builds++;
            if (builder != null) { text.lines.clear(); builder.run(); }
        }
        return text.lines.isEmpty();
    }
    public Text getRichText() { return text; }
    public boolean isAutoUpdate() { return autoUpdate; }
    public void markDirty() { dirty = true; }
    public static class Text {
        public final List<String> lines = new ArrayList<String>();
        public List<String> getAsStrings() { return lines; }
    }
}

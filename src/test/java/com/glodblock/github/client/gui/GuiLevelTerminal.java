package com.glodblock.github.client.gui;
public class GuiLevelTerminal {
    public boolean online = true;
    public final appeng.client.gui.widgets.MEGuiTextField searchFieldOutputs = new appeng.client.gui.widgets.MEGuiTextField();
    public final appeng.client.gui.widgets.MEGuiTextField searchFieldNames = new appeng.client.gui.widgets.MEGuiTextField();
    public final LevelTerminalList masterList = new LevelTerminalList();
    public static class LevelTerminalList { public final java.util.Map<Long, LevelTerminalEntry> list = new java.util.LinkedHashMap<Long, LevelTerminalEntry>(); }
    public static class LevelTerminalEntry {
        public String customName = "native maintainer"; public long id; public int x, y, z, dim, side, rows = 1, rowSize = 9;
        public appeng.api.features.LevelItemInfo[] infoList;
    }
}

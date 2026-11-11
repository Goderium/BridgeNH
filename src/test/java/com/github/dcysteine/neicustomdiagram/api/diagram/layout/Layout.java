package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class Layout {
    public static class SlotKey { private final String key; public SlotKey(String key) { this.key = key; } public String key() { return key; } }
    public static class SlotGroupKey { private final String key; public SlotGroupKey(String key) { this.key = key; } public String key() { return key; } }
    public final List<Object> labels = new ArrayList<Object>(), lines = new ArrayList<Object>();
    public final Map<SlotKey, Slot> slots = new LinkedHashMap<SlotKey, Slot>();
    public final Map<SlotGroupKey, SlotGroup> slotGroups = new LinkedHashMap<SlotGroupKey, SlotGroup>();
    public List<Object> labels() { return labels; }
    public List<Object> lines() { return lines; }
    public Map<SlotKey, Slot> slots() { return slots; }
    public Map<SlotGroupKey, SlotGroup> slotGroups() { return slotGroups; }
    public int paddingBottom() { return 3; }
}

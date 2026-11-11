package com.github.dcysteine.neicustomdiagram.api.diagram.layout;
import java.util.List;
public class SlotGroup {
    private final List<Slot> slots;
    public SlotGroup(List<Slot> slots) { this.slots = slots; }
    public int width() { return 6; }
    public int height() { return 4; }
    public List<Slot> slots() { return slots; }
}

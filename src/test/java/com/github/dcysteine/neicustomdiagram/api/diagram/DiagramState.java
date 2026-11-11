package com.github.dcysteine.neicustomdiagram.api.diagram;
public class DiagramState {
    private int ticks;
    public int cycleIndex(int count) { return count == 0 ? 0 : Math.floorMod(ticks / 20, count); }
    public int cycle() { return ticks / 20; }
    public int ticks() { return ticks; }
    public void fixtureTicks(int value) { ticks = value; }
}

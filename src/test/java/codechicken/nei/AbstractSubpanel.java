package codechicken.nei;
public abstract class AbstractSubpanel extends Widget {
    protected ItemsGrid grid;
    protected int mouseDownItemIndex = -1;
    public int releases;
    public Object getSlotMouseOver(int x, int y) { return x >= 0 && y >= 0 ? grid : null; }
    public void mouseUp(int x, int y, int button) {
        if (getSlotMouseOver(x, y) != null) throw new AssertionError("A slot release could cheat an item");
        mouseDownItemIndex = -1; releases++;
    }
    public void fixturePending(int index) { mouseDownItemIndex = index; }
    public int fixturePending() { return mouseDownItemIndex; }
    public ItemsGrid fixtureGrid() { return grid; }
}

package codechicken.nei;
public class ItemPanel extends PanelWidget {
    public ItemHistoryPanel historyPanel;
    public ItemCraftablesPanel craftablesPanel;
    @Override public void mouseUp(int x, int y, int button) { releases++; mouseDownSlot=-1; }
}

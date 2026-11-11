package appeng.client.me;

import java.util.ArrayList;
import java.util.List;

public class ItemRepo {
    public final List<Object> view = new ArrayList<Object>();
    public String search = ""; public int updates;
    public String getSearchString() { return search; }
    public boolean hasPower() { return true; }
    public boolean isPaused() { return false; }
    public void updateView() { updates++; }
}

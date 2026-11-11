package codechicken.nei.bookmark;
public class BookmarkGrid extends codechicken.nei.ItemsGrid {
    public static final int DEFAULT_GROUP_ID=0;
    public final java.util.List<BookmarkItem> saved=new java.util.ArrayList<BookmarkItem>();
    public final java.util.List<BookmarkItem> bookmarkItems=saved;
    public final BookmarkGridGenerator gridGenerator = new BookmarkGridGenerator();
    public final java.util.Map<Integer, BookmarkGroup> groups = new java.util.LinkedHashMap<Integer, BookmarkGroup>();
    public int failRemovalAfter = -1, removalCalls;
    public BookmarkGrid() { groups.put(0, new BookmarkGroup()); }
    public BookmarkGroup getGroup(int group) { return groups.get(group); }
    public void removeGroup(int group) { saved.removeIf(item -> item.groupId == group); if (group != 0) groups.remove(group); }
    public int getRowItemIndex(int row, boolean first) {
        int start=(page-1)*rows*columns+row*columns;
        return start>=saved.size() ? Integer.MIN_VALUE : first ? start : Math.min(saved.size()-1,start+columns-1);
    }
    public int getRowGroupId(int row) { int index=getRowItemIndex(row,true); return index==Integer.MIN_VALUE ? 0 : saved.get(index).groupId; }
    public boolean isCraftingMode(int group) { return getGroup(group).crafting != null; }
    public void setCraftingMode(int group, boolean value) { getGroup(group).crafting = value ? new Object() : null; }
    public void setViewMode(int group, codechicken.nei.BookmarkPanel.BookmarkViewMode mode) { getGroup(group).viewMode = mode; }
    public void toggleCollapsedState(int group) { getGroup(group).collapsed = !getGroup(group).collapsed; }
    public void removeRecipe(int index, boolean wholeRecipe) {
        if (failRemovalAfter >= 0 && removalCalls++ >= failRemovalAfter) throw new IllegalStateException("injected native failure");
        BookmarkItem item = saved.get(index);
        if ("RESULT".equals(item.type) && saved.stream().noneMatch(other -> other != item && "RESULT".equals(other.type)
                && other.groupId == item.groupId && java.util.Objects.equals(other.recipeId,item.recipeId))) wholeRecipe = true;
        if (wholeRecipe && item.recipeId != null && !"ITEM".equals(item.type))
            saved.removeIf(other -> other.groupId == item.groupId && item.recipeId.equals(other.recipeId));
        else saved.remove(index);
    }
    @Override public void fixtureMask(java.util.List<ItemsGridSlot> slots) { gridGenerator.gridMask = slots; }
    @Override public int size() { return saved.size(); }
    public BookmarkItem getBookmarkItem(int index) { return saved.get(index); }
}

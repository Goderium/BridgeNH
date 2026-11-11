package codechicken.nei.bookmark;

public class BookmarksGridSlot extends codechicken.nei.ItemsGrid.ItemsGridSlot {
    private final BookmarkItem bookmark;
    public BookmarksGridSlot(int slot, int index, BookmarkItem bookmark) {
        super(slot, index, bookmark.itemStack.copy()); this.bookmark = bookmark;
    }
    public BookmarkItem getBookmarkItem() { return bookmark; }
}

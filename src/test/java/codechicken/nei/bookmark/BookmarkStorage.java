package codechicken.nei.bookmark;
public class BookmarkStorage {
    public final java.util.List<BookmarkGrid> namespaces = new java.util.ArrayList<BookmarkGrid>();
    public java.io.File bookmarkFile;
    public int activeNamespaceIndex;
    public BookmarkStorage(BookmarkGrid first) { namespaces.add(first); }
    public BookmarkGrid getActiveGrid() { return namespaces.get(activeNamespaceIndex); }
    public int getNamespaceSize() {
        if (namespaces.get(namespaces.size()-1).size()>0) namespaces.add(new BookmarkGrid());
        return namespaces.size();
    }
    public boolean nextNamespace() { activeNamespaceIndex = (activeNamespaceIndex+1)%getNamespaceSize(); return true; }
}

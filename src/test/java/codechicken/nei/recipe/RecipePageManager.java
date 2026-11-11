package codechicken.nei.recipe;

public class RecipePageManager {
    private int currentPage;
    private final int pageCount;
    public RecipePageManager(int count) { pageCount = count; }
    public int getNumPages() { return pageCount; }
    public int getCurrentPageIndex() { return currentPage; }
    public void changePage(int delta) { currentPage = (currentPage + delta + pageCount) % pageCount; }
}

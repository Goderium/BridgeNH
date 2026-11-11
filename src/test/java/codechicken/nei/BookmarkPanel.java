package codechicken.nei;
public class BookmarkPanel extends PanelWidget {
    public enum BookmarkViewMode { DEFAULT, TODO_LIST }
    public codechicken.nei.bookmark.BookmarkGrid bookmarks = new codechicken.nei.bookmark.BookmarkGrid();
    public final codechicken.nei.bookmark.BookmarkStorage storage = new codechicken.nei.bookmark.BookmarkStorage(bookmarks);
    public int saves;
    public boolean editing;
    public boolean inEditingState() { return editing; }
    public void save() { saves++; }
    public void update() { bookmarks = storage.getActiveGrid(); }
    public boolean addItem(net.minecraft.item.ItemStack stack, Object recipe, int group) {
        for (codechicken.nei.bookmark.BookmarkItem old : bookmarks.saved)
            if (old.groupId == group && old.itemStack.getItem() == stack.getItem() && old.itemStack.getMetadata() == stack.getMetadata()) return false;
        codechicken.nei.bookmark.BookmarkItem item = new codechicken.nei.bookmark.BookmarkItem();
        item.itemStack = stack.copy(); item.groupId = group; item.type = "ITEM"; item.amount = stack.stackSize; bookmarks.saved.add(item); return true;
    }
    @Override public ItemsGrid getGrid() { return bookmarks; }
    public boolean addRecipe(codechicken.nei.recipe.Recipe recipe, int multiplier, int group) {
        if(!bookmarks.saved.isEmpty()) return false;
        for(PositionedStack p:recipe.ref.handler.getIngredientStacks(recipe.ref.recipeIndex)) add(p,"INGREDIENT",recipe,group);
        PositionedStack output=recipe.ref.handler.getResultStack(recipe.ref.recipeIndex); if(output!=null)add(output,"RESULT",recipe,group);
        return true;
    }
    private void add(PositionedStack p,String type,codechicken.nei.recipe.Recipe recipe,int group) {
        codechicken.nei.bookmark.BookmarkItem item=new codechicken.nei.bookmark.BookmarkItem(); item.groupId=group;item.recipeId=recipe.getRecipeId();item.type=type;item.itemStack=p.item;item.amount=p.item.stackSize;
        // The inherited display list can stay stale after a native save; storage is authoritative.
        bookmarks.saved.add(item);
    }
}

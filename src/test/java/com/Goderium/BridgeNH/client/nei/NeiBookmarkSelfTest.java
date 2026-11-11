package com.Goderium.BridgeNH.client.nei;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.ItemPanels;
import codechicken.nei.bookmark.BookmarkGrid;
import codechicken.nei.bookmark.BookmarkItem;
import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Native-operation boundary tests: ownership, retention, cascade scope and partial outcomes. */
public final class NeiBookmarkSelfTest {
    private static int checks;
    public static int run() {
        checks = 0; codechicken.nei.PanelWidget previous = ItemPanels.bookmarkPanel;
        try {
            BookmarkPanel panel = new BookmarkPanel(); ItemPanels.bookmarkPanel = panel;
            Object screen = new net.minecraft.client.gui.GuiScreen(), world = new Object(); NeiBookmarkAdapter adapter = new NeiBookmarkAdapter();
            BookmarkItem temporary = add(panel.bookmarks, "ITEM", null), permanent = add(panel.bookmarks, "ITEM", null), unknown = add(panel.bookmarks, "ITEM", null);
            JsonObject first = read(adapter,screen,world);
            check(first.getAsJsonArray("entries").size()==3, "actual stored entries are observed");
            String temporaryId = entry(first,0), permanentId = entry(first,1);
            tag(adapter,screen,world,temporaryId,"temporary"); tag(adapter,screen,world,permanentId,"future");
            JsonObject result = action(adapter,screen,world,"cleanup","taskId","example-task");
            check(result.get("deletedCount").getAsInt()==1 && panel.bookmarks.saved.contains(permanent) && panel.bookmarks.saved.contains(unknown), "cleanup removes owned temporary entries only");
            check(!panel.bookmarks.saved.contains(temporary) && result.get("verifiedInMemory").getAsBoolean(), "native removal is verified without inventory actions");
            JsonObject again = action(adapter,screen,world,"cleanup","taskId","example-task");
            check(again.get("deletedCount").getAsInt()==0, "a reconciled fresh cleanup finds nothing left");

            BookmarkItem ingredient = add(panel.bookmarks,"INGREDIENT","recipe-one"), output = add(panel.bookmarks,"RESULT","recipe-one");
            BookmarkItem associatedItem = add(panel.bookmarks,"ITEM","recipe-one");
            JsonObject recipe = read(adapter,screen,world); String ingredientId=entry(recipe,2), outputId=entry(recipe,3);
            tag(adapter,screen,world,ingredientId,"temporary"); tag(adapter,screen,world,outputId,"future");
            result=action(adapter,screen,world,"cleanup","taskId","example-task");
            check(panel.bookmarks.saved.contains(ingredient) && panel.bookmarks.saved.contains(output) && !result.get("cleanupComplete").getAsBoolean(), "retained recipe output protects its temporary ingredients");
            JsonObject remove = request(adapter,screen,world,"remove"); remove.add("entryIds", ids(outputId));
            refused(() -> adapter.perform(screen,world,remove),"retained_bookmark");
            check(panel.bookmarks.saved.contains(ingredient), "retained recipe is protected before any native call");
            JsonObject whole = request(adapter,screen,world,"remove_recipe"); whole.add("entryIds",ids(outputId));
            refused(() -> adapter.perform(screen,world,whole),"retained_bookmark");
            check(panel.bookmarks.saved.contains(ingredient) && panel.bookmarks.saved.contains(output), "retention preflight prevents partial recipe deletion");
            remove.addProperty("releaseRetained",true); result=adapter.perform(screen,world,remove);
            check(result.get("deletedCount").getAsInt()==3 && !panel.bookmarks.saved.contains(ingredient) && !panel.bookmarks.saved.contains(associatedItem), "deleting a recipe bookmark removes the whole same-group recipe including associated ITEM entries");

            JsonObject stale = request(adapter,screen,world,"cleanup"); stale.addProperty("taskId","example-task");
            unknown.itemStack.stackSize++;
            refused(() -> adapter.perform(screen,world,stale),"stale_view");
            panel.editing=true;
            refused(() -> action(adapter,screen,world,"cleanup","taskId","example-task"),"input_busy"); panel.editing=false;

            result=action(adapter,screen,world,"namespace_new",null,null);
            check(result.get("status").getAsString().equals("completed") && panel.bookmarks != panel.storage.namespaces.get(0), "native new namespace selects an independent empty collection");
            BookmarkItem second = add(panel.bookmarks,"ITEM",null); String secondId=entry(read(adapter,screen,world),0);
            tag(adapter,screen,world,secondId,"temporary");
            String firstNamespace = read(adapter,screen,world).getAsJsonArray("namespaces").get(0).getAsJsonObject().get("namespaceId").getAsString();
            action(adapter,screen,world,"namespace_select","namespaceId",firstNamespace);
            result=action(adapter,screen,world,"cleanup","taskId","example-task");
            check(result.get("deletedCount").getAsInt()==1 && !panel.storage.namespaces.get(1).saved.contains(second), "one cleanup handles owned temporary entries on another page");

            BookmarkItem forgotten=add(panel.bookmarks,"ITEM",null); String forgottenId=entry(read(adapter,screen,world),2);
            tag(adapter,screen,world,forgottenId,"temporary");
            Object otherWorld = new Object(); result=action(adapter,screen,otherWorld,"cleanup","taskId","example-task");
            check(result.get("deletedCount").getAsInt()==0 && panel.bookmarks.saved.contains(forgotten), "world change drops cleanup ownership and preserves unknown entries");

            BookmarkItem one=add(panel.bookmarks,"ITEM",null), two=add(panel.bookmarks,"ITEM",null);
            JsonObject now=read(adapter,screen,otherWorld); tag(adapter,screen,otherWorld,entry(now,3),"temporary"); tag(adapter,screen,otherWorld,entry(now,4),"temporary");
            panel.bookmarks.failRemovalAfter=1;
            result=action(adapter,screen,otherWorld,"cleanup","taskId","example-task");
            check(result.get("status").getAsString().equals("partial") && result.get("outcomeUnknown").getAsBoolean(), "mid-batch native failure is explicit, never reported completed");
            check(panel.bookmarks.saved.contains(one) != panel.bookmarks.saved.contains(two), "partial native effects are retained for reconciliation, not retried or rolled back");
            check(!result.get("automaticReplayAllowed").getAsBoolean(), "partial cleanup never permits automatic action replay");

            BookmarkPanel isolated = new BookmarkPanel(); ItemPanels.bookmarkPanel=isolated;
            NeiBookmarkAdapter scoped = new NeiBookmarkAdapter();
            add(isolated.bookmarks,"INGREDIENT","shared-recipe");
            add(isolated.bookmarks,"RESULT","shared-recipe");
            BookmarkItem otherGroup=add(isolated.bookmarks,"RESULT","shared-recipe"); otherGroup.groupId=7;
            String origin=read(scoped,screen,world).get("activeNamespaceId").getAsString();
            action(scoped,screen,world,"namespace_new",null,null);
            BookmarkItem otherPage=add(isolated.bookmarks,"RESULT","shared-recipe");
            BookmarkGrid otherGrid=isolated.bookmarks;
            action(scoped,screen,world,"namespace_select","namespaceId",origin);
            JsonObject scopedRemoval=request(scoped,screen,world,"remove");
            scopedRemoval.add("entryIds",ids(entry(read(scoped,screen,world),0)));
            result=scoped.perform(screen,world,scopedRemoval);
            check(result.get("deletedCount").getAsInt()==2, "selecting a recipe ingredient removes its complete recipe");
            check(isolated.bookmarks.saved.contains(otherGroup) && otherGrid.saved.contains(otherPage), "recipe cascade preserves independent copies in other groups and namespaces");
            isolated.bookmarks.groups.put(7,new codechicken.nei.bookmark.BookmarkGroup());
            JsonObject groupRemoval=request(scoped,screen,world,"remove_group"); groupRemoval.addProperty("groupId",7);
            result=scoped.perform(screen,world,groupRemoval);
            check(result.get("groupRemoved").getAsBoolean() && isolated.bookmarks.getGroup(7)==null && !isolated.bookmarks.saved.contains(otherGroup), "native group removal clears its metadata and entries together");
            check(otherGrid.saved.contains(otherPage), "native group removal preserves independent namespace entries");
        } finally { ItemPanels.bookmarkPanel=previous; }
        return checks;
    }
    private static BookmarkItem add(BookmarkGrid grid,String type,String recipe) {
        BookmarkItem item=new BookmarkItem(); item.itemStack=new ItemStack(new Item(),1,0); item.amount=1; item.type=type; item.recipeId=recipe; grid.saved.add(item); return item;
    }
    private static JsonObject read(NeiBookmarkAdapter adapter,Object screen,Object world) { return adapter.inspect(screen,world,new JsonObject()); }
    private static String entry(JsonObject view,int index) { return view.getAsJsonArray("entries").get(index).getAsJsonObject().get("entryId").getAsString(); }
    private static JsonArray ids(String id) { JsonArray ids=new JsonArray(); ids.add(id); return ids; }
    private static JsonObject request(NeiBookmarkAdapter adapter,Object screen,Object world,String action) {
        JsonObject args=new JsonObject(); args.addProperty("action",action); args.addProperty("contextToken",read(adapter,screen,world).get("contextToken").getAsString()); return args;
    }
    private static JsonObject action(NeiBookmarkAdapter adapter,Object screen,Object world,String action,String key,String value) {
        JsonObject args=request(adapter,screen,world,action); if(key!=null)args.addProperty(key,value); return adapter.perform(screen,world,args);
    }
    private static void tag(NeiBookmarkAdapter adapter,Object screen,Object world,String id,String mode) {
        JsonObject args=request(adapter,screen,world,"retain"); args.add("entryIds",ids(id));args.addProperty("taskId","example-task");args.addProperty("retention",mode);
        check(adapter.perform(screen,world,args).get("status").getAsString().equals("completed"),"retention changes complete");
    }
    private static void refused(Runnable action,String code) { try {action.run();throw new AssertionError("Expected "+code);} catch(BridgeException e){check(e.code.equals(code),"expected "+code+" got "+e.code);} }
    private static void check(boolean success,String message) { if(!success)throw new AssertionError(message);checks++; }
}

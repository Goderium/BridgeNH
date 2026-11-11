package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.GuiPointerLocator;
import com.google.gson.JsonObject;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.widget.RecipeWidget;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
public final class NeiNativeControlSelfTest {
    private static int checks;
    public static int run() {
        checks=0;
        net.minecraft.item.Item item=new net.minecraft.item.Item();
        PositionedStack input=new PositionedStack(new net.minecraft.item.ItemStack[]{new net.minecraft.item.ItemStack(item,17,0)},10,10);
        PositionedStack output=new PositionedStack(new net.minecraft.item.ItemStack[]{new net.minecraft.item.ItemStack(item,3,0)},100,10);
        IRecipeHandler handler=new IRecipeHandler() {
            public String getRecipeName(){return "native";} public int numRecipes(){return 8;}
            public java.util.List<PositionedStack> getIngredientStacks(int r){return Collections.singletonList(input);}
            public PositionedStack getResultStack(int r){return output;}
            public java.util.List<PositionedStack> getOtherStacks(int r){return Collections.emptyList();}
        };
        codechicken.nei.recipe.GuiRecipe screen=new codechicken.nei.recipe.GuiRecipe(handler,Collections.singletonList(7));
        RecipeWidget widget=new RecipeWidget(new RecipeHandlerRef(handler,7));widget.x=20;widget.y=20;
        widget.buttons.add(new codechicken.nei.recipe.GuiOverlayButton(new RecipeHandlerRef(handler,7)));screen.fixtureContainer().widgets.add(widget);
        codechicken.nei.recipe.GuiOverlayButton overlay=(codechicken.nei.recipe.GuiOverlayButton)widget.buttons.get(0);
        net.minecraft.client.gui.inventory.GuiContainer destination=new net.minecraft.client.gui.inventory.GuiContainer(null) {
            protected void drawGuiContainerBackgroundLayer(float partialTicks,int mouseX,int mouseY) {}
        };
        overlay.fixtureMissingMaterials=true;
        check(!overlay.canFillCraftingGrid() && NeiOverlayAdapter.nativeTransferAvailable(overlay,destination),"native pattern transfer is allowed without physical ingredients");
        overlay.handlerRef.fixtureCanTransfer=false;
        check(!NeiOverlayAdapter.nativeTransferAvailable(overlay,destination),"unsupported native handler remains refused");
        overlay.handlerRef.fixtureCanTransfer=true;
        check(!NeiOverlayAdapter.nativeTransferAvailable(overlay,null),"missing native destination remains refused");
        destination.inventorySlots=new net.minecraft.inventory.Container() { public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer player) { return true; } };
        Object recipeContainer=new Object();
        check(NeiOverlayAdapter.nativeDestinationBound(destination,destination,recipeContainer,recipeContainer),"native saved destination stays bound while NEI owns its empty container");
        check(!NeiOverlayAdapter.nativeDestinationBound(new Object(),destination,recipeContainer,recipeContainer),"foreign saved destination refused");
        check(!NeiOverlayAdapter.nativeDestinationBound(destination,destination,recipeContainer,new Object()),"actor container drift refused");
        destination.inventorySlots=null;
        check(!NeiOverlayAdapter.nativeDestinationBound(destination,destination,recipeContainer,recipeContainer),"missing saved container refused");
        JsonObject args=new JsonObject();args.addProperty("recipeIndex",7);args.addProperty("positionKind","inputs");
        GuiPointerLocator.Target target=GuiPointerLocator.recipe(screen,args);
        check(target.valid.getAsBoolean(),"new widget refs are newly allocated and still bind by handler/index");
        widget.ref=new RecipeHandlerRef(handler,6);check(!target.valid.getAsBoolean(),"same widget cycling to another recipe invalidates target");widget.ref=new RecipeHandlerRef(handler,7);
        codechicken.nei.PanelWidget previous=codechicken.nei.ItemPanels.bookmarkPanel;
        try {
            codechicken.nei.ItemPanels.bookmarkPanel=new codechicken.nei.BookmarkPanel();
            JsonObject saved=NeiOverlayAdapter.bookmark(screen,7,true);
            check(saved.get("added").getAsBoolean() && saved.get("savedItemsComplete").getAsBoolean() && saved.get("savedItemCount").getAsInt()==2,"whole native recipe verifies actual stored ingredients and output");
            check(saved.getAsJsonArray("savedItems").get(0).getAsJsonObject().get("getAmount").getAsLong()==17,"actual saved amount retained");
            check(!NeiOverlayAdapter.bookmark(screen,7,true).get("added").getAsBoolean(),"bookmark repeated call preserves existing recipe instead of toggling off");
        } finally {codechicken.nei.ItemPanels.bookmarkPanel=previous;}
        AtomicLong clock=new AtomicLong(); java.util.concurrent.atomic.AtomicBoolean valid=new java.util.concurrent.atomic.AtomicBoolean(true);
        NeiModifierHold.fixtureClock(clock::get);
        try {
            NeiModifierHold.arm(7,1000,valid::get);
            check(Boolean.TRUE.equals(NeiModifierHold.override(42)) && Boolean.TRUE.equals(NeiModifierHold.override(54)) && NeiModifierHold.override(29)==null,"hold covers both Shift reads only");
            clock.set(1000000000L);check(NeiModifierHold.override(42)==null,"TTL releases hold even without tick callback");
            NeiModifierHold.arm(7,1000,valid::get);valid.set(false);check(NeiModifierHold.override(42)==null,"screen/page/world/takeover guard invalidation releases hold");
        } finally {NeiModifierHold.fixtureClock(null);}
        return checks;
    }
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;}
}

package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;

/** Current NEI recipe page, through fixed version APIs; no recipe registry or GUI actions. */
public class NeiDataAdapter {
    public static final int RECIPE_LIMIT = 16, POSITION_LIMIT = 64, ALTERNATIVE_LIMIT = 32;
    public static final int VISIBLE_REF_LIMIT = 256, BYTE_LIMIT = 128 * 1024;
    private static final String GUI = "codechicken.nei.recipe.GuiRecipe";

    public static boolean supports(GuiScreen screen) { return publicType(screen == null ? null : screen.getClass(), GUI) != null; }

    /** Caller requires player and matches the current screenId; execute on the client thread. */
    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject args) {
        if (!supports(screen)) throw new BridgeException("unsupported", "Open a NEI GuiRecipe page before reading its current public recipe data.");
        return read(screen, client.thePlayer, args, System.nanoTime());
    }

    static JsonObject read(Object screen, EntityPlayer player, JsonObject args, long started) {
        return read(screen, player, args, started, null, null);
    }

    /** View reads may select original handler indices without navigating the GUI. */
    static JsonObject read(Object screen, EntityPlayer player, JsonObject args, long started, Object selected, List<?> selectedRefs) {
        Class<?> guiApi = publicType(screen == null ? null : screen.getClass(), GUI);
        if (guiApi == null) throw new BridgeException("unsupported", "Current screen is not a NEI GuiRecipe.");
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, selected == null ? 100000 : Integer.MAX_VALUE) : 0;
        int limit = args.has("limit") ? JsonData.integer(args, "limit", 1, RECIPE_LIMIT) : 4;
        boolean include = flag(args, "includeTooltips"), advanced = flag(args, "advanced");
        Object handler = selected == null ? call(guiApi, screen, "getHandler", new Class<?>[0]) : selected;
        Object indices = selectedRefs == null ? call(guiApi, screen, "getRecipeIndices", new Class<?>[0]) : selectedRefs;
        if (handler == null || !(indices instanceof List<?>)) throw new BridgeException("unsupported", "Current NEI handler/page is not available.");
        try {
            ClassLoader loader = guiApi.getClassLoader();
            Class<?> handlerApi = Class.forName("codechicken.nei.recipe.IRecipeHandler", false, loader);
            Class<?> positionApi = Class.forName("codechicken.nei.PositionedStack", false, loader);
            if (!handlerApi.isInstance(handler)) throw new BridgeException("unsupported", "NEI handler does not expose IRecipeHandler.");
            int count = ((Number) call(handlerApi, handler, "numRecipes", new Class<?>[0])).intValue();
            if (count < 0) throw new BridgeException("adapter_error", "NEI handler returned a negative recipe count.");
            List<?> refs = (List<?>) indices;
            JsonObject result = new JsonObject();
            result.addProperty("screenId", JsonData.string(args, "screenId"));
            JsonObject identity = new JsonObject();
            identity.addProperty("class", JsonData.bounded(handler.getClass().getName(), 256));
            identity.addProperty("id", JsonData.bounded(String.valueOf(call(handlerApi, handler, "getHandlerId", new Class<?>[0])), 256));
            identity.addProperty("name", JsonData.bounded(String.valueOf(call(handlerApi, handler, "getRecipeName", new Class<?>[0])), 512));
            result.add("handler", identity); result.addProperty("recipeCount", count);
            try { if (selected == null) result.add("navigation", NeiControlAdapter.navigation(screen, offset, limit)); }
            catch (RuntimeException | LinkageError error) {
                JsonObject unavailable = new JsonObject(); unavailable.addProperty("available", false);
                unavailable.addProperty("error", JsonData.bounded(error.getMessage(), 512)); result.add("navigation", unavailable);
            }
            JsonArray visible = new JsonArray();
            for (int i = 0; i < Math.min(refs.size(), VISIBLE_REF_LIMIT); i++) visible.add(new JsonPrimitive(recipeIndex(refs.get(i), count)));
            result.add("visibleRecipeIndices", visible);
            result.addProperty("visibleRecipeCount", refs.size());
            result.addProperty("visibleIndicesTruncated", refs.size() > VISIBLE_REF_LIMIT);
            result.addProperty("includeTooltips", include); result.addProperty("advanced", advanced);
            result.add("modifiers", modifiers());
            JsonArray recipes = new JsonArray();
            Budget budget = new Budget(started);
            int next = Math.min(offset, refs.size());
            while (next < refs.size() && recipes.size() < limit) {
                if (recipes.size() > 0 && budget.expired()) break;
                int ref = recipeIndex(refs.get(next), count);
                JsonObject recipe = new JsonObject();
                recipe.addProperty("pageOffset", next); recipe.addProperty("recipeIndex", ref);
                JsonObject gt = GTRecipeAdapter.inspectHandler(handler, ref, started);
                if (gt != null) {
                    if (budget.take(utf8(gt) + 128)) recipe.add("gregTech", gt);
                    else { budget.full = true; recipe.addProperty("gregTechByteLimited", true); }
                }
                RecipeBudget positions = new RecipeBudget();
                Object input = call(handlerApi, handler, "getIngredientStacks", new Class<?>[] {int.class}, ref);
                recipe.add("inputs", positions(input, positionApi, player, include, advanced, budget, positions));
                if (!budget.full && !budget.expired()) {
                    Object output = call(handlerApi, handler, "getResultStack", new Class<?>[] {int.class}, ref);
                    recipe.add("outputs", positions(output == null ? Collections.emptyList() : Collections.singletonList(output),
                            positionApi, player, include, advanced, budget, positions));
                } else { recipe.add("outputs", new JsonArray()); positions.truncated = true; }
                if (!budget.full && !budget.expired()) {
                    Object other = call(handlerApi, handler, "getOtherStacks", new Class<?>[] {int.class}, ref);
                    recipe.add("other", positions(other, positionApi, player, include, advanced, budget, positions));
                } else { recipe.add("other", new JsonArray()); positions.truncated = true; }
                recipe.addProperty("positionsVisited", positions.visited);
                recipe.addProperty("positionsTruncated", positions.truncated);
                JsonObject researchLinks = NeiRecipeSemantics.researchLinks(handler, ref, player, started);
                if (budget.take(utf8(researchLinks))) recipe.add("researchLinks", researchLinks);
                else recipe.addProperty("researchLinksOmitted", "result_byte_budget; read selected NEI summary or metadata research continuation");
                recipe.addProperty("softBudgetPartial", budget.expired());
                recipes.add(recipe); next++;
                if (budget.full) break;
            }
            result.add("recipes", recipes);
            result.addProperty("offset", offset); result.addProperty("nextOffset", next); result.addProperty("hasMore", next < refs.size());
            result.addProperty("byteLimited", budget.full); result.addProperty("nonAtomic", true);
            JsonObject coverage = new JsonObject();
            coverage.addProperty("scope", "Current GuiRecipe page's original handler indices; no global recipe registry or graph.");
            coverage.addProperty("indexSource", "GuiRecipe.getHandler original and RecipePageManager.getRecipeIndices original RecipeHandlerRef.recipeIndex; no second remapping.");
            coverage.addProperty("stacks", "Public PositionedStack item/items and relx/rely/width/height, chance/getAcceptsLabel/getTooltip; existing permutations only, never advanced or updated.");
            coverage.addProperty("itemTooltips", include ? "Current actual ItemStack.getTooltip(player, advanced), NBT retained; physical modifier state only" : "not_requested");
            coverage.addProperty("extraDrawnText", "GT raw recipe parameters and existing display calculator via fixed GTRecipe adapter; arbitrary custom drawn text remains unavailable");
            coverage.addProperty("purity", "No onUpdate/setPermutationToRender/hover/draw, recipe-open/navigation, screenshot or registry scanning.");
            result.add("coverage", coverage);
            JsonObject limits = new JsonObject();
            limits.addProperty("recipes", RECIPE_LIMIT); limits.addProperty("positionsPerRecipe", POSITION_LIMIT);
            limits.addProperty("alternativesPerPosition", ALTERNATIVE_LIMIT); limits.addProperty("visibleRefs", VISIBLE_REF_LIMIT);
            limits.addProperty("tooltipLines", 32); limits.addProperty("tooltipLineCharacters", 512); limits.addProperty("resultBytes", BYTE_LIMIT);
            result.add("limits", limits); result.add("budget", StructureAdapter.budget(started));
            if (utf8(result) > BYTE_LIMIT) throw new BridgeException("too_large", "NEI result exceeded its bounded output budget.");
            return result;
        } catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "NEI public recipe API is unavailable."); }
    }

    private static JsonArray positions(Object value, Class<?> api, EntityPlayer player, boolean include, boolean advanced,
                                       Budget budget, RecipeBudget positions) {
        JsonArray result = new JsonArray();
        if (value == null) return result;
        if (!(value instanceof List<?>)) throw new BridgeException("adapter_error", "NEI public position getter did not return a list.");
        List<?> list = (List<?>) value;
        for (int i = 0; i < list.size(); i++) {
            if (positions.visited >= POSITION_LIMIT || budget.full || (positions.visited > 0 && budget.expired())) { positions.truncated = true; break; }
            Object object = list.get(i); positions.visited++;
            if (object == null) continue;
            if (!api.isInstance(object)) throw new BridgeException("adapter_error", "Handler returned a non-PositionedStack entry.");
            JsonObject position = new JsonObject();
            for (String field : new String[] {"relx", "rely", "width", "height"}) position.addProperty(field, ((Number) field(api, object, field)).intValue());
            position.addProperty("chance", (Number) call(api, object, "getChance", new Class<?>[0]));
            Object label = call(api, object, "getAcceptsLabel", new Class<?>[0]);
            position.add("acceptsLabel", label == null ? JsonNull.INSTANCE : new JsonPrimitive(JsonData.bounded(String.valueOf(label), 512)));
            Object custom = call(api, object, "getTooltip", new Class<?>[0]);
            GuiDataAdapter.text(position, custom instanceof List<?> ? (List<?>) custom : Collections.emptyList());
            try { position.add("badges", NeiViewAdapter.badges(call(api, object, "getBadges", new Class<?>[0]))); }
            catch (BridgeException unavailable) { position.addProperty("badgesStatus", "unsupported"); }
            Object current = field(api, object, "item");
            position.add("item", current instanceof ItemStack ? stack((ItemStack) current, player, include, advanced) : JsonNull.INSTANCE);
            Object array = field(api, object, "items");
            ItemStack[] items = array instanceof ItemStack[] ? (ItemStack[]) array : new ItemStack[0];
            JsonArray alternatives = new JsonArray();
            position.add("alternatives", alternatives);
            position.addProperty("alternativesTotal", items.length);
            int visited = 0;
            int baseSize = utf8(position) + 512;
            if (!budget.room(baseSize)) { budget.full = true; positions.truncated = true; break; }
            for (int j = 0; j < Math.min(items.length, ALTERNATIVE_LIMIT); j++) {
                if (j > 0 && budget.expired()) break;
                JsonElement item = items[j] == null ? JsonNull.INSTANCE : stack(items[j], player, include, advanced);
                int size = item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (!budget.room(baseSize + size)) { budget.full = true; break; }
                alternatives.add(item); visited++; baseSize += size;
            }
            position.addProperty("alternativesTruncated", visited < items.length);
            position.addProperty("source", "Current public PositionedStack; relx/rely are relative to the recipe drawing origin");
            if (!budget.take(utf8(position) + 512)) { budget.full = true; positions.truncated = true; break; }
            result.add(position);
        }
        return result;
    }

    static JsonElement stack(ItemStack stack, EntityPlayer player, boolean include, boolean advanced) {
        if (stack == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        try { JsonElement identity = JsonData.item(stack); if (identity.isJsonObject()) result = identity.getAsJsonObject(); }
        catch (RuntimeException | LinkageError error) { result.addProperty("identityError", error.getClass().getSimpleName()); }
        if (stack.hasTagCompound()) {
            String nbt = stack.getTagCompound().toString();
            result.addProperty("nbt", JsonData.bounded(nbt, 8192));
            result.addProperty("nbtTruncated", nbt.length() > 8192);
        }
        NeiViewAdapter.fluid(result, stack);
        if (include) {
            try { GuiDataAdapter.text(result, stack.getTooltip(player, advanced)); }
            catch (RuntimeException | LinkageError error) { result.addProperty("tooltipError", error.getClass().getSimpleName()); }
        }
        return result;
    }

    private static int recipeIndex(Object value, int count) {
        if (!(value instanceof Integer) || (Integer) value < 0 || (Integer) value >= count)
            throw new BridgeException("adapter_error", "Current NEI page returned an invalid original handler recipe index.");
        return (Integer) value;
    }
    private static boolean flag(JsonObject args, String key) {
        if (!args.has(key)) return false;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args", key + " must be boolean.");
        return args.get(key).getAsBoolean();
    }
    private static Object field(Class<?> api, Object target, String name) {
        try { return api.getField(name).get(target); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public NEI field unavailable: " + name); }
    }
    private static Object call(Class<?> api, Object target, String name, Class<?>[] types, Object... args) {
        try { return api.getMethod(name, types).invoke(target, args); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "NEI getter " + name + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public NEI API unavailable: " + name); }
    }
    private static Class<?> publicType(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) if (name.equals(current.getName())) return current;
        return null;
    }
    private static int utf8(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    static JsonObject modifiers() {
        JsonObject result = new JsonObject();
        try {
            if (!Keyboard.isCreated()) { result.addProperty("source", "unavailable"); return result; }
            result.addProperty("shift", Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
            result.addProperty("ctrl", Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
            result.addProperty("alt", Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
            result.addProperty("source", "current effective Keyboard.isKeyDown state; physical input plus active scoped/owned GUI modifiers; advanced is independent");
            result.add("neiHold", NeiModifierHold.inspect());
        } catch (RuntimeException | LinkageError error) { result.addProperty("source", "unavailable"); }
        return result;
    }
    private static final class RecipeBudget { int visited; boolean truncated; }
    private static final class Budget {
        final long started; int bytes = 12288; boolean full;
        Budget(long started) { this.started = started; }
        boolean expired() { return System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS; }
        boolean room(int more) { return bytes + more <= BYTE_LIMIT - 4096; }
        boolean take(int more) { if (!room(more)) return false; bytes += more; return true; }
    }
}

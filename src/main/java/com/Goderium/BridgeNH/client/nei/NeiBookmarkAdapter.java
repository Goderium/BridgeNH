package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-thread bookmark operations through fixed NEI APIs; no keyboard injection or NEI file edits. */
public final class NeiBookmarkAdapter {
    private static final String PANEL = "codechicken.nei.BookmarkPanel";
    private static final String GRID = "codechicken.nei.bookmark.BookmarkGrid";
    private static final String STORAGE = "codechicken.nei.bookmark.BookmarkStorage";
    private static final int MAX_ITEMS = 4096, MAX_NAMESPACES = 64, MAX_DELETE = 512;
    private final IdentityHashMap<Object, String> ids = new IdentityHashMap<Object, String>();
    private final IdentityHashMap<Object, Retention> retention = new IdentityHashMap<Object, Retention>();
    private Object ownerWorld, ownerStorage;
    private String ownerFile, nonce = UUID.randomUUID().toString();
    private long serial;

    private static final class Retention {
        final String task, mode;
        Retention(String task, String mode) { this.task = task; this.mode = mode; }
    }
    private static final class Entry {
        Object item, grid, recipe;
        String id, type;
        int group, index;
    }
    private static final class View {
        Object panel, storage, active;
        List<?> grids;
        final List<Entry> entries = new ArrayList<Entry>();
        String token;
    }

    private String id(Object object) {
        String value = ids.get(object);
        if (value == null) { value = "nb-" + nonce + "-" + (++serial); ids.put(object, value); }
        return value;
    }
    private static Object field(Object value, String owner, String name) { return ExpandedGuiApi.field(value, owner, name); }
    private static Object call(Object value, String name, Object... args) { return ExpandedGuiApi.call(value, name, args); }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static String text(JsonObject args, String name) {
        String value = ExpandedGuiApi.string(args, name, 128);
        require(!value.isEmpty(), "invalid_args", name + " must not be empty."); return value;
    }
    private static boolean bool(JsonObject args, String name, boolean fallback) {
        return args.has(name) ? com.Goderium.BridgeNH.client.gui.GuiDataAdapter.booleanArg(args, name, fallback) : fallback;
    }
    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new BridgeException(code, message);
    }
    private static Set<Object> identities() { return Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()); }

    private View view(Object screen, Object world) {
        View v = new View();
        v.panel = NeiUiAccess.publicField(screen.getClass().getClassLoader(), "codechicken.nei.ItemPanels", null, "bookmarkPanel");
        require(v.panel != null, "unsupported", "The NEI bookmark panel is not initialized.");
        v.storage = field(v.panel, PANEL, "storage");
        Object file = field(v.storage, STORAGE, "bookmarkFile");
        String fileKey = String.valueOf(file);
        if (world != ownerWorld || v.storage != ownerStorage || !fileKey.equals(ownerFile)) {
            ids.clear(); retention.clear(); nonce = UUID.randomUUID().toString(); serial = 0;
            ownerWorld = world; ownerStorage = v.storage; ownerFile = fileKey;
        }
        v.grids = new ArrayList<Object>((List<?>) field(v.storage, STORAGE, "namespaces"));
        require(v.grids.size() <= MAX_NAMESPACES, "limit_exceeded", "Too many bookmark namespaces for bounded verification.");
        v.active = call(v.storage, "getActiveGrid");
        require(call(v.panel, "getGrid") == v.active, "not_ready", "Wait for the native bookmark panel to update before editing.");
        Set<Object> present = identities(); present.addAll(v.grids);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hash(digest, nonce + "/" + System.identityHashCode(screen) + "/" + id(v.active));
            int bytes = 0;
            for (Object grid : v.grids) {
                hash(digest, id(grid) + "/" + call(grid, "getPage") + "/" + call(grid, "getRows") + "/" + call(grid, "getColumns"));
                Map<?, ?> groups = (Map<?, ?>) field(grid, GRID, "groups");
                require(groups.size() <= MAX_ITEMS, "limit_exceeded", "Too many bookmark groups.");
                List<Integer> groupIds = new ArrayList<Integer>();
                for (Object key : groups.keySet()) groupIds.add(number(key));
                Collections.sort(groupIds);
                for (int groupId : groupIds) {
                    Object group = groups.get(groupId);
                    hash(digest, groupId + "/" + field(group, "codechicken.nei.bookmark.BookmarkGroup", "viewMode")
                            + "/" + field(group, "codechicken.nei.bookmark.BookmarkGroup", "collapsed")
                            + "/" + call(grid, "isCraftingMode", groupId));
                    List<String> collapsed = new ArrayList<String>();
                    Set<?> collapsedRecipes = (Set<?>) field(group, "codechicken.nei.bookmark.BookmarkGroup", "collapsedRecipes");
                    require(collapsedRecipes.size() <= MAX_ITEMS, "limit_exceeded", "Too many collapsed recipe references.");
                    for (Object recipe : collapsedRecipes) collapsed.add(String.valueOf(recipe));
                    Collections.sort(collapsed); hash(digest, collapsed.toString());
                }
                if (grid == v.active) for (int row = 0; row < Math.min(128, number(call(grid, "getRows"))); row++)
                    hash(digest, row + "/" + call(grid, "getRowGroupId", row) + "/" + call(grid, "getRowItemIndex", row, true) + "/" + call(grid, "getRowItemIndex", row, false));
                List<?> stored = (List<?>) field(grid, GRID, "bookmarkItems");
                int count = stored.size();
                require(count >= 0 && count + v.entries.size() <= MAX_ITEMS, "limit_exceeded", "Too many bookmarks for bounded verification.");
                for (int i = 0; i < count; i++) {
                    Entry e = new Entry(); e.item = stored.get(i); e.grid = grid; e.index = i;
                    e.id = id(e.item); e.group = number(field(e.item, "codechicken.nei.bookmark.BookmarkItem", "groupId"));
                    e.recipe = field(e.item, "codechicken.nei.bookmark.BookmarkItem", "recipeId");
                    e.type = String.valueOf(field(e.item, "codechicken.nei.bookmark.BookmarkItem", "type"));
                    ItemStack stack = (ItemStack) field(e.item, "codechicken.nei.bookmark.BookmarkItem", "itemStack");
                    String nbt = stack.writeToNBT(new NBTTagCompound()).toString(); bytes += nbt.length();
                    require(bytes <= 2 * 1024 * 1024, "limit_exceeded", "Bookmark NBT exceeds the verification budget.");
                    Retention mark = retention.get(e.item);
                    hash(digest, e.id + "/" + e.group + "/" + e.type + "/" + System.identityHashCode(e.recipe)
                            + "/" + nbt + "/" + call(e.item, "getMultiplier") + "/" + call(e.item, "getAmount")
                            + "/" + (mark == null ? "untracked" : mark.task + "/" + mark.mode));
                    present.add(e.item); v.entries.add(e);
                }
            }
            ids.keySet().retainAll(present); retention.keySet().retainAll(present);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            v.token = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return v;
    }
    private static void hash(MessageDigest digest, String text) { digest.update(text.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }

    public JsonObject inspect(Object screen, Object world, JsonObject args) {
        ExpandedGuiApi.keys(args, "contextToken", "offset", "limit");
        View v = view(screen, world);
        int offset = args.has("offset") ? JsonData.integer(args, "offset", 0, MAX_ITEMS) : 0;
        if (offset > 0 || args.has("contextToken")) require(v.token.equals(text(args, "contextToken")), "stale_view", "Read a fresh bookmark view; its state has changed.");
        return describe(v, offset, args.has("limit") ? JsonData.integer(args, "limit", 1, 128) : 32);
    }
    private JsonObject describe(View v, int offset, int limit) {
        JsonObject out = new JsonObject(); out.addProperty("contextToken", v.token);
        out.addProperty("activeNamespaceId", id(v.active));
        out.addProperty("retentionLifetime", "Current client/world and native bookmark objects. After restart/reload unknown entries are preserved, never adopted as temporary.");
        out.addProperty("maxCleanupEntries", MAX_DELETE);
        JsonArray namespaces = new JsonArray();
        for (Object grid : v.grids) {
            JsonObject n = new JsonObject(); n.addProperty("namespaceId", id(grid)); n.addProperty("index", namespaces.size());
            n.addProperty("entries", number(call(grid, "size"))); n.addProperty("active", grid == v.active); namespaces.add(n);
        }
        out.add("namespaces", namespaces);
        JsonArray groups = new JsonArray(); Map<?, ?> nativeGroups = (Map<?, ?>) field(v.active, GRID, "groups");
        List<Integer> keys = new ArrayList<Integer>(); for (Object key : nativeGroups.keySet()) keys.add(number(key)); Collections.sort(keys);
        for (int key : keys) {
            Object group = nativeGroups.get(key); JsonObject g = new JsonObject(); g.addProperty("groupId", key);
            g.addProperty("viewMode", String.valueOf(field(group, "codechicken.nei.bookmark.BookmarkGroup", "viewMode")));
            g.addProperty("collapsed", Boolean.TRUE.equals(field(group, "codechicken.nei.bookmark.BookmarkGroup", "collapsed")));
            g.addProperty("collapsedRecipeCount", ((Set<?>) field(group, "codechicken.nei.bookmark.BookmarkGroup", "collapsedRecipes")).size());
            g.addProperty("crafting", Boolean.TRUE.equals(call(v.active, "isCraftingMode", key))); groups.add(g);
        }
        out.add("groups", groups);
        out.addProperty("page", number(call(v.active, "getPage"))); out.addProperty("rows", number(call(v.active, "getRows")));
        JsonArray visibleRows = new JsonArray();
        for (int row = 0; row < Math.min(128, number(call(v.active, "getRows"))); row++) {
            int first = number(call(v.active, "getRowItemIndex", row, true));
            if (first == Integer.MIN_VALUE) continue;
            JsonObject r = new JsonObject(); r.addProperty("row", row); r.addProperty("groupId", number(call(v.active, "getRowGroupId", row)));
            r.addProperty("firstItemIndex", first); r.addProperty("lastItemIndex", number(call(v.active, "getRowItemIndex", row, false))); visibleRows.add(r);
        }
        out.add("visibleRows", visibleRows);
        JsonArray entries = new JsonArray(); int total = 0;
        for (Entry e : v.entries) if (e.grid == v.active) {
            if (total++ < offset || entries.size() >= limit) continue;
            JsonObject item = new JsonObject(); item.addProperty("entryId", e.id); item.addProperty("index", e.index);
            item.addProperty("groupId", e.group); item.addProperty("type", e.type); item.addProperty("hasRecipe", e.recipe != null);
            item.add("item", JsonData.item((ItemStack) field(e.item, "codechicken.nei.bookmark.BookmarkItem", "itemStack")));
            item.addProperty("amount", String.valueOf(call(e.item, "getAmount")));
            item.addProperty("multiplier", String.valueOf(call(e.item, "getMultiplier")));
            Retention mark = retention.get(e.item); item.addProperty("retention", mark == null ? "untracked" : mark.mode);
            if (mark != null) item.addProperty("taskId", mark.task);
            entries.add(item);
        }
        out.add("entries", entries); out.addProperty("total", total); out.addProperty("offset", offset);
        out.addProperty("nextOffset", Math.min(total, offset + entries.size())); out.addProperty("hasMore", offset + entries.size() < total);
        return out;
    }

    public JsonObject perform(Object screen, Object world, JsonObject args) {
        String action = text(args, "action");
        validateKeys(args, action);
        GuiControlAdapter.neutralInput();
        View before = view(screen, world);
        require(before.token.equals(text(args, "contextToken")), "stale_view", "Read a fresh bookmark view before changing it.");
        require(!Boolean.TRUE.equals(call(before.panel, "inEditingState")), "input_busy", "Finish the current native bookmark drag before editing.");
        JsonObject result = new JsonObject(); result.addProperty("action", action); result.addProperty("automaticReplayAllowed", false);
        List<Entry> removal = null;
        if (action.equals("remove") || action.equals("remove_recipe") || action.equals("remove_group") || action.equals("cleanup"))
            removal = removalPlan(before, args, action, result);
        // All destructive scope/retention checks finish before the first native call.
        try {
            if (removal != null) delete(before, removal, result, action.equals("remove_group") ? group(before, args) : -1);
            else if (action.equals("retain")) {
                List<Entry> entries = selected(before, args); String task = text(args, "taskId");
                String mode = ExpandedGuiApi.option(args, "retention", null, "temporary", "permanent", "future");
                for (Entry e : entries) retention.put(e.item, new Retention(task, mode));
                result.addProperty("tagged", entries.size());
            } else if (action.equals("add_item")) {
                int group = group(before, args); ItemStack stack = NeiViewAdapter.resolvePanelItem(screen, text(args, "neiItemId")).copy();
                stack.stackSize = args.has("count") ? JsonData.integer(args, "count", 0, Integer.MAX_VALUE) : 0;
                String task = text(args, "taskId"); String mode = ExpandedGuiApi.option(args, "retention", "temporary", "temporary", "permanent", "future");
                call(before.panel, "addItem", stack, null, group);
                tagNew(before, view(screen, world), task, mode, result);
            } else if (action.equals("namespace_select") || action.equals("namespace_new")) {
                Object target = null;
                if (action.equals("namespace_select")) {
                    String wanted = text(args, "namespaceId");
                    for (Object grid : before.grids) if (id(grid).equals(wanted)) target = grid;
                    require(target != null, "not_found", "Namespace is absent from the current view.");
                } else {
                    require(before.grids.size() < MAX_NAMESPACES || number(call(before.grids.get(before.grids.size() - 1), "size")) == 0,
                            "limit_exceeded", "Namespace limit reached.");
                    call(before.storage, "getNamespaceSize");
                    List<?> current = (List<?>) field(before.storage, STORAGE, "namespaces");
                    require(current.size() <= MAX_NAMESPACES, "limit_exceeded", "Namespace limit reached.");
                    target = current.get(current.size() - 1);
                    require(number(call(target, "size")) == 0, "not_ready", "No native empty namespace is available.");
                }
                for (int i = 0; call(before.storage, "getActiveGrid") != target && i <= MAX_NAMESPACES; i++) call(before.storage, "nextNamespace");
                call(before.panel, "update");
                require(call(before.storage, "getActiveGrid") == target, "not_ready", "Native namespace selection did not reach the target.");
            } else editGroup(before, args, action);
            call(before.panel, "save");
            result.addProperty("nativeSaveRequested", true);
            View after = view(screen, world);
            result.addProperty("status", "completed"); result.addProperty("changed", !after.token.equals(before.token));
            result.addProperty("contextToken", after.token); result.add("view", describe(after, 0, 16));
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("status", "partial"); result.addProperty("outcomeUnknown", true);
            result.addProperty("error", error instanceof BridgeException ? ((BridgeException) error).code : error.getClass().getSimpleName());
            result.addProperty("recovery", "Inspect current bookmarks and retention before further changes. Do not replay this action.");
        }
        return result;
    }

    /** Used by existing whole-recipe bookmarking; existing entries are never adopted for cleanup. */
    public JsonObject addRecipe(Object screen, Object world, JsonObject args) {
        GuiControlAdapter.neutralInput();
        String task = text(args, "taskId"); String mode = ExpandedGuiApi.option(args, "retention", "temporary", "temporary", "permanent", "future");
        View before = view(screen, world);
        JsonObject result = NeiOverlayAdapter.bookmark(screen, JsonData.integer(args, "index", 0, 100000), bool(args, "saveCount", true));
        tagNew(before, view(screen, world), task, mode, result); call(before.panel, "save"); return result;
    }
    private void tagNew(View before, View after, String task, String mode, JsonObject result) {
        Set<Object> old = identities(); for (Entry e : before.entries) old.add(e.item);
        JsonArray added = new JsonArray();
        for (Entry e : after.entries) if (!old.contains(e.item)) { retention.put(e.item, new Retention(task, mode)); added.add(e.id); }
        result.add("newEntryIds", added); result.addProperty("retention", mode); result.addProperty("taskId", task);
    }

    private List<Entry> selected(View v, JsonObject args) {
        JsonArray values = args.getAsJsonArray("entryIds");
        require(values != null && values.size() > 0 && values.size() <= MAX_DELETE, "invalid_args", "Supply 1..512 unique current entryIds.");
        Set<String> requested = new LinkedHashSet<String>();
        for (JsonElement value : values) require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && requested.add(value.getAsString()), "invalid_args", "Entry IDs must be unique strings.");
        List<Entry> found = new ArrayList<Entry>();
        for (Entry e : v.entries) if (requested.remove(e.id)) found.add(e);
        require(requested.isEmpty(), "not_found", "An entry is absent from the current bookmark state."); return found;
    }
    private List<Entry> removalPlan(View v, JsonObject args, String action, JsonObject result) {
        List<Entry> candidates = new ArrayList<Entry>(); boolean cleanup = action.equals("cleanup");
        if (cleanup) {
            String task = text(args, "taskId");
            for (Entry e : v.entries) { Retention r = retention.get(e.item); if (r != null && r.task.equals(task) && r.mode.equals("temporary")) candidates.add(e); }
        } else if (action.equals("remove_group")) {
            int group = group(v, args); for (Entry e : v.entries) if (e.grid == v.active && e.group == group) candidates.add(e);
        } else {
            candidates.addAll(selected(v, args));
            List<Entry> roots = new ArrayList<Entry>(candidates);
            for (Entry root : roots) {
                if (action.equals("remove_recipe")) require(root.recipe != null, "invalid_args", "The selected bookmark has no recipe identity.");
                // A recipe bookmark is deleted as one unit, including all its associated ITEM
                // entries. Equal items/recipes in another group or namespace are independent.
                if (root.recipe != null && (action.equals("remove_recipe") || !root.type.equals("ITEM")))
                    for (Entry e : v.entries) if (sameRecipe(root, e) && !candidates.contains(e)) candidates.add(e);
            }
        }
        require(candidates.size() <= MAX_DELETE, "limit_exceeded", "Cleanup is limited to 512 entries; split the task or explicitly select a smaller set.");
        Set<Object> allowed = identities(); for (Entry e : candidates) allowed.add(e.item);
        List<Entry> plan = new ArrayList<Entry>(); JsonArray skipped = new JsonArray();
        for (Entry e : candidates) {
            Retention mark = retention.get(e.item);
            require(cleanup || mark == null || mark.mode.equals("temporary") || bool(args, "releaseRetained", false), "retained_bookmark", "This entry is retained for later/permanent use. Explicit releaseRetained is required for intentional removal.");
            boolean safe = true;
            // Automatic cleanup preserves a complete recipe if any part is reserved/untracked.
            // Explicit recipe removal already authorizes this whole same-group recipe unit.
            boolean lastResult = e.type.equals("RESULT");
            if (lastResult) for (Entry related : v.entries)
                if (sameRecipe(e, related) && related.type.equals("RESULT") && !allowed.contains(related.item)) lastResult = false;
            if (e.recipe != null && (cleanup || lastResult)) for (Entry related : v.entries)
                if (sameRecipe(e, related) && !allowed.contains(related.item)) safe = false;
            if (!safe && cleanup) { skipped.add(e.id); continue; }
            require(safe, "invalid_scope", "The native recipe removal plan is incomplete.");
            plan.add(e);
        }
        result.add("skippedEntryIds", skipped); result.addProperty("cleanupComplete", skipped.size() == 0);
        return plan;
    }
    private static boolean sameRecipe(Entry a, Entry b) { return a.grid == b.grid && a.group == b.group && a.recipe != null && a.recipe.equals(b.recipe); }
    private void delete(View v, List<Entry> plan, JsonObject result, int group) {
        Set<Object> intended = identities(); for (Entry e : plan) intended.add(e.item);
        // Verify required native methods before any deletion.
        if (group >= 0) {
            call(v.active, "removeGroup", group);
            require(group == 0 || call(v.active, "getGroup", group) == null,
                    "not_completed", "Native group metadata remains after group removal.");
            result.addProperty("groupRemoved", group != 0);
        } else {
            for (Entry e : plan) try { e.grid.getClass().getMethod("removeRecipe", int.class, boolean.class); }
            catch (NoSuchMethodException ex) { throw new BridgeException("unsupported", "Native bookmark removal API is unavailable."); }
            for (int i = plan.size() - 1; i >= 0; i--) {
                Entry e = plan.get(i); int current = indexOfIdentity(e.grid, e.item);
                if (current >= 0) call(e.grid, "removeRecipe", current, false);
            }
        }
        JsonArray deleted = new JsonArray();
        for (Entry e : v.entries) if (indexOfIdentity(e.grid, e.item) < 0) {
            require(intended.contains(e.item), "unexpected_effect", "Native deletion affected an unselected bookmark; inspect before recovery.");
            deleted.add(e.id); retention.remove(e.item);
        }
        require(deleted.size() == plan.size(), "not_completed", "Some selected bookmarks remain after native removal.");
        result.add("deletedEntryIds", deleted); result.addProperty("deletedCount", deleted.size()); result.addProperty("verifiedInMemory", true);
    }
    private static int indexOfIdentity(Object grid, Object item) {
        List<?> stored = (List<?>) field(grid, GRID, "bookmarkItems");
        require(stored.size() <= MAX_ITEMS, "limit_exceeded", "Bookmark count changed beyond the bound.");
        for (int i = 0; i < stored.size(); i++) if (stored.get(i) == item) return i; return -1;
    }
    private static int group(View v, JsonObject args) {
        int value = args.has("groupId") ? JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE) : 0;
        require(call(v.active, "getGroup", value) != null, "not_found", "Group is absent from the active namespace."); return value;
    }
    private Entry entry(View v, JsonObject args, String name) {
        String value = text(args, name); for (Entry e : v.entries) if (e.id.equals(value) && e.grid == v.active) return e;
        throw new BridgeException("not_found", "Entry is absent from the active namespace.");
    }
    private static int row(View v, JsonObject args, String name) {
        int value = JsonData.integer(args, name, 0, 127);
        require(value < number(call(v.active, "getRows")) && number(call(v.active, "getRowItemIndex", value, true)) != Integer.MIN_VALUE,
                "not_found", "The requested current-page row contains no bookmark."); return value;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void editGroup(View v, JsonObject args, String action) {
        if (action.equals("group_rows")) {
            int start = row(v, args, "startRow"), end = row(v, args, "endRow");
            int group = args.has("groupId") ? group(v, args) : Integer.MIN_VALUE;
            Object selection = ExpandedGuiApi.create(v.panel, "codechicken.nei.bookmark.GroupingItem", v.active, group, start);
            call(selection, "setEndPoint", end); require(Boolean.TRUE.equals(call(selection, "hasEndRow")), "not_ready", "Native group selection is incomplete.");
            call(v.active, "createGroup", selection);
        } else if (action.equals("group_mode")) {
            int group = group(v, args);
            if (args.has("viewMode")) {
                String mode = ExpandedGuiApi.option(args, "viewMode", null, "DEFAULT", "TODO_LIST");
                Class type = ExpandedGuiApi.load(v.panel, PANEL + "$BookmarkViewMode"); call(v.active, "setViewMode", group, Enum.valueOf(type, mode));
            }
            if (args.has("crafting")) call(v.active, "setCraftingMode", group, bool(args, "crafting", false));
            if (args.has("collapsed") && bool(args, "collapsed", false) != Boolean.TRUE.equals(field(call(v.active, "getGroup", group), "codechicken.nei.bookmark.BookmarkGroup", "collapsed"))) call(v.active, "toggleCollapsedState", group);
        } else if (action.equals("move_item")) {
            Entry source = entry(v, args, "entryId"); Object drag = ExpandedGuiApi.create(v.panel, "codechicken.nei.bookmark.SortableItem", v.active, source.item);
            require(!Boolean.TRUE.equals(call(drag, "isEmpty")), "not_ready", "Expand the group and wait for native layout before moving entries.");
            if (args.has("targetEntryId")) call(v.active, "moveItem", drag, entry(v, args, "targetEntryId").index);
            else call(v.active, "moveItem", drag, row(v, args, "targetRow"), group(v, args), bool(args, "after", false));
        } else if (action.equals("move_group")) {
            Object source = v.active;
            if (args.has("sourceNamespaceId")) {
                source = null;
                for (Object grid : v.grids) if (id(grid).equals(text(args, "sourceNamespaceId"))) source = grid;
                require(source != null, "not_found", "Source namespace is absent from the current state.");
            }
            int sourceGroup = JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE);
            require(call(source, "getGroup", sourceGroup) != null, "not_found", "Source group is absent.");
            int targetRow = JsonData.integer(args, "targetRow", 0, 127);
            require(targetRow < number(call(v.active, "getRows")), "not_ready", "Wait for current native bookmark layout.");
            Object drag = ExpandedGuiApi.create(v.panel, "codechicken.nei.bookmark.SortableGroup", source, sourceGroup);
            call(v.active, "moveGroup", drag, targetRow);
        } else if (action.equals("recipe_collapse")) {
            Entry e = entry(v, args, "entryId"); Object nativeGroup = call(v.active, "getGroup", e.group);
            Object crafting = field(nativeGroup, "codechicken.nei.bookmark.BookmarkGroup", "crafting");
            require(crafting != null && e.recipe != null, "invalid_state", "Recipe collapse needs a native crafting group and recipe entry.");
            Object root = e.recipe;
            Map<?, ?> relations = (Map<?, ?>) field(crafting, "codechicken.nei.bookmark.RecipeChainDetails", "recipeRelations");
            require(relations.size() <= MAX_ITEMS, "limit_exceeded", "Recipe chain exceeds the bounded scope.");
            for (Map.Entry<?, ?> relation : relations.entrySet()) if (((Set<?>) relation.getValue()).contains(e.recipe)) { root = relation.getKey(); break; }
            Set<?> collapsed = (Set<?>) field(nativeGroup, "codechicken.nei.bookmark.BookmarkGroup", "collapsedRecipes");
            if (collapsed.contains(root) != bool(args, "collapsed", false)) call(v.active, "toggleCollapsedRecipe", e.group, e.recipe);
        } else if (action.equals("quantity")) {
            long delta = JsonData.integer(args, "delta", -1000000, 1000000);
            String scope = ExpandedGuiApi.option(args, "scope", null, "item", "recipe", "group");
            if (scope.equals("group")) call(v.active, "shiftGroupAmount", group(v, args), delta);
            else {
                Entry e = entry(v, args, "entryId");
                if (scope.equals("item")) call(v.active, "shiftItemAmount", e.index, delta);
                else { require(e.recipe != null, "invalid_args", "The entry has no associated recipe."); call(v.active, "shiftRecipeAmount", e.group, e.recipe, delta); }
            }
        } else throw new BridgeException("invalid_args", "Unknown bookmark action.");
    }
    private static void validateKeys(JsonObject args, String action) {
        String extras;
        switch (action) {
            case "add_item": extras = "neiItemId count groupId taskId retention"; break;
            case "retain": extras = "entryIds taskId retention"; break;
            case "cleanup": extras = "taskId"; break;
            case "remove": case "remove_recipe": extras = "entryIds releaseRetained"; break;
            case "remove_group": extras = "groupId releaseRetained"; break;
            case "namespace_select": extras = "namespaceId"; break;
            case "namespace_new": extras = ""; break;
            case "group_rows": extras = "startRow endRow groupId"; break;
            case "group_mode": extras = "groupId viewMode collapsed crafting"; break;
            case "move_item": extras = "entryId targetEntryId targetRow groupId after"; break;
            case "move_group": extras = "groupId targetRow sourceNamespaceId"; break;
            case "recipe_collapse": extras = "entryId collapsed"; break;
            case "quantity": extras = "scope entryId groupId delta"; break;
            default: throw new BridgeException("invalid_args", "Unknown bookmark action.");
        }
        ExpandedGuiApi.keys(args, ("action contextToken " + extras).trim().split(" +"));
        text(args, "contextToken");
        for (String name : new String[] {"releaseRetained", "collapsed", "crafting", "after"}) if (args.has(name)) bool(args, name, false);
        for (String name : new String[] {"entryId", "targetEntryId", "namespaceId", "sourceNamespaceId", "neiItemId", "taskId"}) if (args.has(name)) text(args, name);
        if (args.has("groupId")) JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE);
        if (args.has("count")) JsonData.integer(args, "count", 0, Integer.MAX_VALUE);
        if (args.has("retention")) ExpandedGuiApi.option(args, "retention", null, "temporary", "permanent", "future");
        if (args.has("viewMode")) ExpandedGuiApi.option(args, "viewMode", null, "DEFAULT", "TODO_LIST");
        switch (action) {
            case "cleanup": text(args, "taskId"); break;
            case "retain": text(args, "taskId"); ExpandedGuiApi.option(args, "retention", null, "temporary", "permanent", "future"); break;
            case "add_item": text(args, "neiItemId"); text(args, "taskId"); break;
            case "remove_group": JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE); break;
            case "namespace_select": text(args, "namespaceId"); break;
            case "group_rows": JsonData.integer(args, "startRow", 0, 127); JsonData.integer(args, "endRow", 0, 127); break;
            case "group_mode":
                JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE);
                require(args.has("viewMode") || args.has("collapsed") || args.has("crafting"), "invalid_args", "Specify the intended group mode."); break;
            case "move_item":
                text(args, "entryId");
                if (args.has("targetEntryId")) require(!args.has("targetRow") && !args.has("groupId") && !args.has("after"), "invalid_args", "Choose one move target.");
                else { JsonData.integer(args, "targetRow", 0, 127); JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE); } break;
            case "move_group": JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE); JsonData.integer(args, "targetRow", 0, 127); break;
            case "recipe_collapse": text(args, "entryId"); require(args.has("collapsed"), "invalid_args", "Specify collapsed state."); break;
            case "quantity":
                JsonData.integer(args, "delta", -1000000, 1000000);
                if (ExpandedGuiApi.option(args, "scope", null, "item", "recipe", "group").equals("group")) {
                    JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE); require(!args.has("entryId"), "invalid_args", "Group quantity does not take entryId.");
                } else { text(args, "entryId"); require(!args.has("groupId"), "invalid_args", "Entry quantity does not take groupId."); } break;
            default: break;
        }
    }
}

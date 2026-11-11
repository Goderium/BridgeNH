# GTNH NEI operation guide

Agent reference: English; [Chinese companion](nei_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

For Minecraft 1.7.10 and GTNH NotEnoughItems. Native bookmark APIs are source-scoped to **2.8.171-GTNH**; the shortcut reference uses **2.8.155-GTNH**, and the other baseline behavior uses **2.8.145-GTNH**. These source baselines do not establish complete live-test coverage. Read current bindings, configuration and tooltips after upgrades or rebinding.

## Choose the bookmark and retrieval scope

- For one final item, a human item-level **A** bookmark is different from **Shift+A** for a whole recipe. Save inputs/outputs together only when that relationship is required. bookmarkItemsWithRecipe may attach recipe identity without adding all ingredients.
- Separate recipe yield, saved bookmark amount and requested retrieval amount. A recipe producing four items still produces four at multiplier one. To retrieve one, use an exact observed entry with `mcb_nei_item_action(action="take",neiItemId=...,count=1)`. Preserve full metadata/NBT and verify final inventory/cursor.
- Use `mcb_nei_bookmark_action(action="add_item")` with an observed exact `neiItemId` for an item-only bookmark. `mcb_nei_navigate(action="bookmark")` adds the whole recipe. Prefer an existing exact entry when no new bookmark is needed; do not reconstruct an NBT/dynamic item as a bare registry template.

Read the [bookmark reference](#bookmark-reference) only as needed. Human gestures are not an instruction to simulate physical modifiers.

## Temporary ghosts and bookmark cleanup

NEI ghosts and bookmarks created only for marking, pattern writing or another temporary step are temporary by default. Once the result is verified, or those temporary entries are no longer needed after an abandoned task, clean them up as part of completion without waiting for another request.

- Verify the target marking/configuration or encoded pattern first. Remove temporary source ghosts and unneeded task-only bookmarks; preserve saved target markings, encoded patterns and real cursor/inventory items. A configured phantom slot is not an unused NEI source ghost.
- Supported semantic ghost marking already releases the NEI fake cursor; inspect its `ghostRelease` result. For residual ghosts from native or manual dragging, use `mcb_nei_item_action(action="release",screenId=...)` with the current screen identity and verify `ghostRelease.available=true` and `ghostPresentAfter=false`. Release preserves configured markers and bookmarks, so it does not complete bookmark cleanup. If cleanup fails, inspect the submitted result and recover cleanup alone; never repeat marking or encoding solely to remove a ghost.
- Record which bookmarks this task added, their page/namespace and why any must remain. Reusing an existing bookmark does not make it disposable. Keep permanent bookmarks and bookmarks explicitly needed by later work until they are explicitly no longer needed; carry their purpose into task notes or handoff.
- For large sets, use separate bookmark pages/namespaces by task, recipe batch or purpose, keeping temporary entries apart from retained ones. When bookmarks become crowded, a manual cleanup pass may remove obsolete or unused entries; retain permanent and explicitly future-needed entries. Inspect the exact deletion scope before deleting a linked recipe or group, and verify that retained entries remain.

In the referenced NEI version, bookmark collections are namespaces: the namespace `<` / `>` controls reach a trailing empty collection for a new page, and NEI creates another empty collection once the last one contains entries. This differs from scrolling visible pages inside one collection and from recipe groups. Use current observed controls and read back the active collection; record its purpose because namespace indices can change when empty collections are removed. `mcb_nei_navigate(action="page")` pages recipes, not bookmark collections.

Use the native bookmark operations below for cleanup. Fake-cursor release is a separate operation and preserves bookmarks. Report skipped retained/linked entries and any partial or unavailable operation as pending cleanup; do not silently substitute Computer Use, OS input, file edits or human assistance.

## Native bookmark management

Read `mcb_nei_bookmarks(screenId=...)` for `contextToken`, active namespace, namespace IDs, groups, visible rows and exact entry IDs. Entries page only within the active namespace; reuse the token for later pages. A changed screen, layout, item/NBT, quantity, group or retention invalidates the token. Refresh observation after manual changes or any mutation.

`mcb_nei_bookmark_action` requires the current `screenId` and `contextToken`:

| Action | Selection and result |
| --- | --- |
| `add_item` | Exact observed `neiItemId`, `taskId`, optional `count` (default zero), `groupId` and `retention`. Native item bookmark with full source NBT; existing entries remain unchanged. |
| `retain` | Explicit `entryIds`, `taskId`, and `retention=temporary/permanent/future`. This explicitly classifies existing entries; mere reuse does not adopt them. |
| `cleanup` | `taskId`; removes only its temporary entries across namespaces, preserving unknown, permanent and future entries. A linked recipe with reserved/untracked entries is skipped as a unit. |
| `remove` / `remove_recipe` / `remove_group` | Exact `entryIds` or `groupId`. Removing a recipe bookmark removes that whole recipe in the same group/namespace, including inputs, outputs and associated item entries. Independent copies elsewhere remain. Plain item bookmarks remove individually; remove_recipe explicitly selects a recipe by an associated entry. Permanent/future entries need `releaseRetained=true` after they are explicitly no longer needed. |
| `namespace_new` / `namespace_select` | Select the native trailing empty collection, or a current `namespaceId`. Filling the empty collection makes the next new page available. |
| `group_rows` | Current `visibleRows` startRow/endRow; omit groupId for a new group, use zero to ungroup, or an existing groupId. Native recipe/chain row grouping applies. |
| `group_mode` / `recipe_collapse` | Set group viewMode (`DEFAULT`/`TODO_LIST`), crafting or collapsed; recipe_collapse uses entryId/collapsed within an actual crafting group. |
| `move_item` / `move_group` | Native ordering with current entry IDs or visible row. A move_group may use sourceNamespaceId to move a group into the active namespace, including a new empty page. Native view/chain constraints still apply. |
| `quantity` | Signed bounded multiplier `delta`, with scope=item/recipe/group and its exact entryId/groupId. This changes bookmark demand, never real stock. |

For whole recipes, give `mcb_nei_navigate(action="bookmark",...,taskId=...,retention="temporary")`; only newly created entries join the task. Retention defaults to temporary when a task is supplied. Mark reusable entries future/permanent before cleanup. Retention follows actual native bookmark objects during this client/world lifetime; after a client/world/native reload, lost ownership becomes untracked and is preserved. Reconcile from task notes and explicitly classify current entries rather than guessing by item name or page number.

Read back native `amount` and `multiplier`: `add_item.count` is the source stack count passed to NEI, and native fluid/NBT conversion determines saved demand. Ordinary group quantity changes apply to positive-demand entries; zero-demand templates remain unchanged. `remove_group` also removes the native group's settings; group zero is NEI's permanent default group.

One cleanup call handles at most 512 selected entries within 64 namespaces/4096 observed bookmarks. Split larger work into bounded tasks. Inspect deletedEntryIds/deletedCount, skippedEntryIds, cleanupComplete and the returned view. `changed=false` is not proof that a requested move succeeded. Native save is requested; in-memory verification alone does not prove disk durability. A partial result or unknown effect requires inspection and reconciliation, never automatic replay of deletion, grouping or encoding.

## Observe focus, identity and bindings

Start with current GUI/widgets/inventory/tooltip and, for a recipe screen, `mcb_nei`. A real ItemStack tooltip preserves state/NBT better than a registry+metadata template. Use exact-version source if structured data is insufficient, and native screenshots only for a necessary visual check. Tooltip queries do not move the physical mouse. Do not normally save screenshots; the bridge cannot clear host media history.

`mcb_nei(screenId=...)` reads the current public handler, original recipe references and PositionedStack inputs/outputs/other alternatives. Offset defaults zero, limit four/max sixteen; continue explicitly using returned pagination. includeTooltips defaults false and advanced does not emulate modifiers. Virtual recipe positions are not container slot numbers. Supported GT data includes raw items/fluids/EUt/duration/chance/conditions and displayOverclock, which is not actual machine processing.

Offsets refer to visible original recipe references, not the handler's entire recipeCount. Each recipe visits at most 64 positions and 32 alternatives per position. Inspect positionsVisited/positionsTruncated/softBudgetPartial/alternativesTruncated and budget: exhaustion before outputs/other does not mean those groups are empty. Chance uses 0.01% units, so 10000 means 100%. Missing custom rendering stays unknown. Queries do not enumerate a global recipe graph or execute R/U/A.

For human shortcuts, focus and hover matter. A focused search box can consume R/U/A as text; Enter/Esc or clicking outside removes focus, and the first Esc may only blur the box. Current registered bindings come from `mcb_bindings` or Controls. Modern versions use vanilla KeyBinding/options.txt entries `key_nei.options.keys.*`; old client.cfg keys.gui/hash values are not reliable runtime bindings. Many Shift/Ctrl/Alt combinations are handler semantics, not separately registered keys.

`mcb_gui_key` supports fixed navigation and bounded text. A character callback cannot emulate Keyboard.isKeyDown, physical hover or modifiers. Use semantic APIs and report missing coverage without switching to OS input.

## Semantic opening and navigation

`mcb_nei_open` requires current screenId and mode crafting/usage. Use an empty screenId only when no GUI is actually open. Source-specific arguments:

| Source | Identity |
| --- | --- |
| registry | registryName and optional metadata; a template without live NBT. |
| inventory | Actual inventory slot 0..255. |
| container | Current real slotNumber. |
| recipe | Current handler's original recipeIndex, inputs/outputs/other group, position 0..63, alternative 0..31. |

Prefer actual stack sources to reconstructed templates. Do not mix source selectors. `mcb_nei_navigate` needs current nonempty screenId; handler/page requires a valid current index, back accepts no index. Native APIs need no physical R/U/arrows or hover.

Bookmark action uses original recipeIndex and saveCount (default true), adding the **whole recipe** while preserving existing entries. False only omits quantity; it does not become item-only or implement toggle deletion. Re-read real handler/recipe references after submission; do not repeat an asynchronous action because the screen has not changed. For advertised widgets use current IDs/actions, not old coordinates.

## Batch retrieval and creative inventory sync

Preflight existing stock, ordinary 36-slot capacity, stack limits and total additions. Compose search -> wait for exact panel identity -> bind current neiItemId -> take in a bounded sequence. Search is asynchronous; ordinary waiting is about one second. Reconcile exact NBT/count increases and cursor once per batch. Submission is not pickup; after failure/unknown inspect the original batch instead of requesting items again.

Minecraft 1.7.10 creative category tabs ignore some ordinary-inventory S2FPacketSetSlot updates; hotbar sync is not full inventory sync. With inventoryPlayerTabVersion>=1, open_inventory selects the native player inventory tab and returns its GUI state; reuse it. Older adapters expose inventoryTabWidgetId. Take requires this tab and refuses category pages before supply. For already divergent server/client state, compare both sides and use a normal container opening to trigger full-slot sync; closing a category tab alone may not repair lost updates. Never take again to repair a display.

## Core query and navigation gestures

Defaults below are reference gestures; runtime bindings take precedence.

| Input | Context | Effect |
| --- | --- | --- |
| R / U | Hovered item, search unfocused | Recipes / uses, including supported catalyst uses. |
| Ctrl+U | Same | Ordinary uses excluding catalyst query. |
| Shift+R / Shift+U | Same | Still R/U queries; no separate base NEI recipe-tree action. |
| Backspace | Recipe, text unfocused | Previous recipe query. |
| Esc / E | Recipe | Return toward the original container; text focus handles first. |
| Up/Down | Recipe, text unfocused | Change handler/category. |
| Left/Right | Same | Recipe page. |
| PgUp/PgDn | NEI panel | Panel page. |
| O / B | Container, appropriate focus | Toggle NEI / bookmark panel. |

Panel/recipe icons normally query recipes with left click and uses with right click, but enabled cheat mode/permission may give items. Shift-click may retain querying. Real inventory slots still move real items. Per-world NEI mode can override global configuration; do not infer it from static examples.

## Bookmark reference

The following tables describe 2.8.155-GTNH source behavior. Inspect registered bookmark.add/remove_recipe/favorite/favorite_item/pull_items/chat_link/open_crafting_tree/hide and gui.prev/next bindings. Adding and group deletion are separate bindings even if both default to A.

**Read** means `mcb_nei_view(section="panel",panel="bookmarks",scope="visible" or "all")` in the current namespace, preserving contextToken, pagination and neiItemId. All does not mean every namespace. **Gap** means no matching BridgeNH semantic action; record it if needed for the task. Human availability does not establish native agent coverage. After a supported logical `mcb_gui_pointer` hover and a completed natural frame, `mcb_nei_view(section="detail",target="hover_hotkeys")` can read localized hints; missing completed-frame evidence is unknown. Tooltip read alone does not change hover.

### Add, delete and recipe trees

| Default input / target | Native scope | BridgeNH path or gap |
| --- | --- | --- |
| A / non-bookmarked item | Toggle one item; normally omit count; existing recipe/config can affect association/count. | add_item adds explicitly; remove deletes explicitly. |
| Ctrl+A / same | Item bookmark preserving source amount; panel quantity control may apply. | add_item with explicit observed count. |
| Shift+A / recipe item or overlay button | Toggle ingredients and selected output; fallback to item when no recipe. Count follows bookmarkRecipeWithCount. | Whole-recipe bookmark action adds without toggle deletion. |
| Ctrl+Shift+A / same | Whole recipe with counts. | Whole-recipe action with saveCount=true. |
| A / expanded bookmark | Delete one entry; linked chains/collapsed groups can refuse individual removal. | remove uses exact entryIds; recipe bookmarks remove the whole same-group recipe as one operation. |
| Shift+A / recipe bookmark | Delete associated recipe, possibly chain/whole collapsed crafting group. | remove_recipe uses explicitly selected recipes; remove_group separately selects a whole group. |
| Shift+A / group sidebar or default-group page toolbar | Delete that entire group. | remove_group with current groupId and retention checks. |
| Left click / favorite star | Toggle preferred recipe. | Current native GUI click and readback where supported. |
| Wheel / favorite star | Select associated output in a multi-output recipe. | Gap; not item retrieval. |
| Shift+A / favorite star | New bookmark group for the favorite recipe tree. | Gap; whole-recipe bookmark is not equivalent. |
| Shift+F / item/history panel or alternative ingredient | Save favorite tree / choose preferred ingredient, depending on handler. | Gap; not ordinary F search. |
| T / crafting-group sidebar; Shift+T / favorite star | Open tree / save then open tree. | Gap for shortcut; an open tree is readable with section=tree. |
| Ctrl+L / item/group; Ctrl+Shift+L / recipe | Send chat links for the item/recipe/group. | Gap; actual external communication requires user intent. |

Native bookmarks toggle; a repeated A can delete. A zero-count bookmark is a full-identity template, not inventory or a request for zero items. Retrieval applies a positive count to a copy and must not rewrite the template. Native Shift+A fallback is not a dedicated item-bookmark API.

### Quantity and wheel

Wheel up/down normally increases/decreases, but item quantity, recipe multiplier and group demand are different. Large steps use native alignment rather than guaranteed arithmetic addition to every displayed value.

| Input / target | Quantity scope | BridgeNH path or gap |
| --- | --- | --- |
| Ctrl+wheel / expanded ITEM bookmark | Item multiplier +/-1 down to zero; recipeId alone does not make it recipe-wide. | quantity scope=item with current entryId and delta. |
| Ctrl+wheel / RESULT or INGREDIENT | Linked recipe multiplier +/-1; chain intermediates may have minimum one. | quantity scope=recipe with current entryId and delta. |
| Ctrl+wheel / sidebar, default toolbar or collapsed group | Group scale/chain-output multiplier; ordinary groups change positive-count entries. | quantity scope=group with groupId and delta. |
| Ctrl+Alt+wheel / item | Visible nonzero quantity-control step, otherwise 144 for fluid display or native max stack; retains item/recipe/group scope. | quantity with the intended scope and explicitly observed step as delta. |
| Ctrl+Alt+wheel / group toolbar | Quantity-control step or default 64 across the group. | quantity scope=group with explicit delta. |
| Wheel / Shift+wheel / Ctrl+wheel on quantity field | +/-1 / +/-10 / +/-64, minimum zero; Shift takes precedence when both modifiers are held. | Use an advertised text/value widget if available; no general modified-wheel action. |
| Up/Down / focused quantity field | +/-1, minimum zero. | Navigation key with actual focus, then readback. |
| Shift+wheel / supported bookmark alternatives | Change alternative, not count. | Gap; detail bookmark_permutations can read candidates. |
| Plain wheel / bookmark body | Panel page, not quantity. | Read pagination does not change UI page. |

Zero bookmark count can fall back to quantity-control/default stack size; ordinary left click need not retrieve one.

### Retrieval, transfer and crafting

| Input / context | Native effect | BridgeNH path or gap |
| --- | --- | --- |
| Left/right click / container bookmark with cheat permission, no Shift, not GuiRecipe | Prepared quantity / one generated item. | take with exact neiItemId and count 1..64 (default one), within native stack limit. |
| Left/right click / recipe, Shift or no cheat permission | Query recipe/uses. | nei_open crafting/usage. |
| Middle click/drag / panel entry | NEI fake drag/template, not inventory arrival. | Prefer ghostSource; ghost_pick only supports advertised item-panel hits. |
| V / Shift+V / supported IBookmarkContainerHandler | Withdraw actual container quantity / only inventory shortfall. | AE resource_take when applicable; no universal V tool. |
| V / Shift+V on sidebar; P-button click | Whole-group requirements / shortfall. P labels a UI button, not the keyboard key. | Gap; one take is not whole-group withdrawal. |
| S / Shift+S / Ctrl+Shift+S on supported recipe/bookmark | Ghost layout / fill as much as possible (up to 64 batches) / bookmark multiplier (up to 64, non-bookmark one). | Current recipe overlay action with shift; not every S multiplier gesture. |
| Shift+C / Ctrl+Shift+C on supported autocrafting recipe/group | Real crafting / craft only shortage; may run a chain. | Gap; AE ordering is separate from GT processing. |

Ctrl+left click can enter an infinite-item branch depending on server NEI/handler; do not use it for finite retrieval. Take preserves full NBT and requires creative/developer/native authority, empty cursor and capacity in the player inventory tab. A receipt is not arrival. Use ghostSource for configuration, not generated items in survival.

### Groups, ordering, collapse and pages

| Input / context | Effect | BridgeNH path or gap |
| --- | --- | --- |
| Left/right drag across group sidebar rows | Create/include group / return rows to default group. | group_rows using current visible rows and intended group. |
| Shift+left drag on item/sidebar | Reorder entry/group; RESULT may move its whole recipe. | move_item/move_group retain native ordering rules. |
| Left/right click on group toolbar | Cycle display / crafting-chain mode. | group_mode sets the intended native mode. |
| Alt+left click on toolbar/collapsed icon | Collapse/expand group or linked recipe. | group_mode or recipe_collapse with explicit collapsed state. |
| PgUp/PgDn or page-arrow click | Bookmark page. | Navigation requires correct logical hover; supported arrows can be clicked. Read offset is not UI page. |
| Wheel or namespace-arrow click | Previous/next namespace, separate from group/page. | namespace_select/namespace_new, or an observed native button. |
| B / container with search unfocused | Show/hide bookmarks. | Gap; text character b is not physical B. |

Bookmark item/recipe/group deletion, quantity and ordering now use the operations above. Favorite recipe-tree creation and other gestures explicitly marked Gap remain separate coverage limits. `nei_navigate(page)` only pages recipes. Ordinary native clicks still need fresh screen/coordinates/target evidence and readback, not an old screenshot.

## Search

F focuses the visible NEI global field in the source baseline, not legacy Ctrl+F. Right click clears; double left click toggles inventory match highlighting; focused Up/Down walks search history. Autofocus is configurable.

When Extended patternMode=1 and spaceMode=0 apply, spaces are AND, `|` is OR, `-` excludes, quotes group phrases, `*`/`?` are wildcards and `r/.../` is explicit regex. Mod-name filtering such as `@GregTech` uses display names, not arbitrary abbreviations/mod IDs. `%` is subsets; chemical `=` support depends on an installed provider.

Filter configuration is 0=ALWAYS, 1=PREFIX, 2=NEVER. With tooltip/identifier/oreDict ALWAYS, ordinary terms already include them; `#`, `&` and `$` are not guaranteed registered syntax. Inspect current settings rather than copying a prior user's filters.

The recipe magnifier opens a separate recipe filter, not global item search. Source syntax includes `<text` inputs, `>text` outputs and `!text` exclusion; current UI hints take precedence.

## Recipe wheel, overlay and favorites

Holding Shift pauses alternative cycling. Shift+wheel over an ingredient/catalyst with alternatives changes that alternative; elsewhere it may change handler. Plain wheel pages recipes/panels unless a widget/handler consumes it. Confirm the target before changing counts.

S can show a supported ghost overlay; S after leaving the item can clear it. Ghosts do not prove placed material. Shift+S attempts actual filling (default multiplier zero means up to 64 batches); Ctrl+Shift+S uses bookmark multiplier or one for a non-bookmark. Third-party handlers may expose only maxTransfer behavior.

The recipe overlay button needs compatible container/handler support. With shiftOverlayRecipe=true, plain click commonly shows ghosts and Shift+click fills; AE/NEE compatibility may differ. Missing/disabled buttons do not prove missing recipes. Shift+C crafting requires real materials and a supported autocrafting handler, never a bypass for GT voltage/structure/recipe rules.

The favorite star selects a recipe/output; Shift+A there saves a favorite tree, distinct from bookmarking one output/recipe. Favorite depth is configurable. Shift+F depends on panel/ingredient context. T for a crafting bookmark tree differs from BogoSorter's AE hover search; see [ae.md](ae.md).

Ctrl+C copies an escaped search name, Ctrl+D ore dictionary entries and Ctrl+X the registry identifier (possibly metadata). Chat-link shortcuts actually send and are not default debugging steps. F7/F9 world overlays differ from recipe S. Item zoom/next-tooltip key conflicts need current verification.

## Sources and verification limits

Bookmark reference: [NEI 2.8.155-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.155-GTNH/NotEnoughItems-2.8.155-GTNH-sources.jar), SHA256 `fd7efd4ad77a7d250b2d2e9854cba7d643222a941cc28a4c808937505c0b2973`.

- Defaults/modifiers: NEIClientConfig.setDefaultKeyBindings, KeyManager.isKeyDown/isHashDown.
- Item/recipe toggles, count preservation, V and deletion: api/ShortcutInputHandler; favorites: recipe/GuiFavoriteButton and recipe/widget/RecipeWidget.
- Grouping, ordering, wheel and retrieval preparation: BookmarkPanel, bookmark/BookmarkGrid and SortableItem.
- Quantity/fallback: ItemQuantityField, PanelWidget, NEIClientUtils.cheatItem/getScrollMultiplier.
- BridgeNH coverage: NeiControlAdapter, NeiOverlayAdapter.bookmark, NeiItemControlAdapter and catalog/nei.py / catalog/gui.py.

Earlier baseline: [NEI 2.8.145-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.145-GTNH/NotEnoughItems-2.8.145-GTNH-sources.jar), SHA256 `d4ede32d2bd510e4d5312f79a2012da79ea7fec7f5eab23c52064e4377af3c5e`. Relevant classes also include RecipeItemInputHandler, GuiUsageRecipe, GuiRecipe, NEIRecipeWidget, SearchField/TextField/SearchTokenParser/ModNameFilter, GuiOverlayButton and DefaultOverlayHandler.

Source review is not live shortcut acceptance. Runtime rebinding, world-mode overrides, third-party handlers, chemical search providers and key conflicts require current evidence. A guide does not imply complete native support or universal recipe filling.

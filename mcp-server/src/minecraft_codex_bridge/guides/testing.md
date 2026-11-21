# BridgeNH layered validation and optional QA

Applies to Minecraft 1.7.10, the existing BridgeNH regression suites, optional Horizon-QA/TST-QA adapters and native client operation. Read offline with `mcb_help({"topic":"testing"})`.

## Choose evidence for the change

State the behavior, expected result and environment first. Run the smallest relevant existing checks; expand only for new changes, failures or unresolved coverage. Routine movement/orders do not require development tests. An installed-package user does not need a source build.

| Change/goal | Regression evidence | Optional QA | Native acceptance |
| --- | --- | --- | --- |
| Validation, arithmetic, receipts, waiting | Existing Python/Java unit and protocol tests | Only if game execution matters | Affected tool path when required |
| Recipe consumption, ME buffers and delivery | Identity/NBT/long counts and failure boundaries | Existing finite recipes, ME output or declarations | Real logistics, order and collection |
| Orientation, ports and layout | Transform/binding regressions | Existing cases or generic declarations | Actual placement and structure checks |
| Flight, approach, rays, mining and GUI | Control/synchronization/failure regressions | Server logic covers only part | Actual agent-facing native client path |
| Cross-dimension or sustained production | Boundary/protocol checks | Bounded local portions | Normal ticks, loading, IO and required duration |
| Docs, installation and configuration | Offline reads, parsing, links, bounds and affected config checks | Unneeded | Reconnection/read-only interfaces only when relevant |

Mocked tests, in-game QA, an integrated server and a dedicated server are distinct evidence. QA does not replace GUI/player acceptance; singleplayer results do not establish remote-server support. Source developers reuse `mcp-server/tests` and the root Gradle `bridgeSelfTest` task; compile Java changes as required by the project. Ordinary distribution users need no development dependencies.

If any step requires desktop Computer Use, [report the error immediately](workspace.md#native-control-gap-and-feedback-template). Record the task outcome separately and keep native acceptance for the assisted step open until it succeeds end to end through the intended interface.

For Windows window controls, verify launch modes and repeated restore/resize/maximize/minimize sequences on the selected client. Check native state, framebuffer dimensions and taskbar presence. Record foreground and keyboard-focus changes throughout each operation; equal before/after handles do not exclude a brief input interruption. Passive OS observations are measurement, not client control. Assess Prism's prelaunch dialogs separately from the game window; a client-window pass does not establish uninterrupted typing during launcher startup.

## QA remains optional

Horizon-QA provides a test framework; TST-QA provides related cases and public private-dimension integration. Neither is required for ordinary BridgeNH use, server dialogue, permissions or reads. Do not install/enable QA, elevate policy or switch client/world without task authorization. Client prerequisites remain UniMixins, StructureLib and lwjgl3ify.

Discover relevant QA through current health and `mcb_qa_catalog(section="capabilities")`; query cases with bounded filters. Tool names alone do not prove that QA is installed, executable or already run. Missing mods, disabled policy, unsupported APIs, insufficient permission or absent cases are unexecuted layers with reasons. Continue independent authorized checks, but never mark skipped validation as passed or lower required acceptance silently.

A submitted QA failure/timeout/unknown result must still be tracked. Optional availability is not permission to ignore a failed run or submit it again.

## Policy and ownership

`mcb_policy` and local `/mcb policy qa auto|off|observe|test|manage` use `config/codexbridge/policies.json`, scoped by save/server. With default groups, `auto` means survival=off, survival_efficient=observe, creative=test, full=manage. Explicit QA settings only narrow the mode ceiling; lowering the main mode immediately narrows effective access.

| QA level | Scope |
| --- | --- |
| `off` | Capability discovery remains available; no new observation of unrelated runs or tests. |
| `observe` | Catalog, status and results; no test-area creation/cleanup. |
| `test` | Exact reviewed finite cases or supported declarations in an authorized test area. The private runner advertises `tstqa-controlled-policy-v1`; arbitrary Java callbacks are not a sandbox. No undeclared/global runall/diagnostic management. |
| `manage` | Supported management operations with real game/server authority and ownership/scope checks. QA teleport also requires `movement=tp` and actual command permission. |

Policy grants no OP. Dialogue roles grant no OP. `readOnly` blocks new tests and fixture cleanup. After downgrade/read-only, explicit owned-run reads, supported normal stop and release of finished records remain possible; they do not authorize other players' runs or global cancellation. The current private runner's normal stop drains supported running debug-source cases; it does not force-interrupt arbitrary finite callbacks.

`mcb_qa_command(command="clear",args=[runId])` clears only a finished owned area whose blocks still match the expected state. `forget` releases the finished record/lease and leaves blocks. Confirm runner support. Missing QA or disabled policy returns capability unavailability; distinguish pre-dispatch refusal from post-submission uncertainty.

## Reuse the existing execution flow

1. Bind the actual client, world/server, session, dimension and authority. Remote execution requires advertised server QA support. Use an authorized dedicated test area; preparation may place/remove blocks. Do not run on production machinery or create/reset worlds merely because a guide mentions testing.
2. Find installed cases first. Reuse finite recipe, ME output and numerical-boundary cases. Within supported semantics, express a new case with immutable `machineFlows`; do not add a per-machine BridgeNH tool or hard-coded complete production chain. Record unexpressible mechanisms as gaps rather than substituting an unrelated recipe.
3. Prefer `mcb_qa_test_once` for a known case. It owns fresh catalog/fixture planning, space preflight, one run, waits, result pages and post-checks. Do not duplicate these checks outside it. Selection queries do not replace fresh execution guards; time/request/space budgets still apply.
4. Require `requireFullFlow=true` for a finite machine flow. Use false only when native assertions/legacy scope are the intended result and explain that limit. Generic declarations retain their 1..10 limit and exact identity/recipe/fixture evidence.
5. Continue existing runs with `mcb_qa_run/status/results` and their actual runId. Use native `waitMillis` when advertised; otherwise ordinary agent polling is about one second. Do not add a faster outer poller or replace existing waiters.
6. Infinite diagnostics are not finite passes. Where authorized, preserve separate observation-ready, quantified output, normal-stop and recovery evidence. Stop only the owned run, never globally cancel.

QA can fix inputs/expectations while operating tools stay general. Native placement, GUI input and AE ordering still use reusable tools. Reuse existing workflow evidence instead of constructing the same machine for every layer.

## Truthful results, timing and cleanup

- `submitted` proves submission only. `testPassed` differs from `fullFlowVerified`; a full flow needs actual preparation, consumption, production, collection, stopping and required stability evidence.
- Result pages must belong to one runId. Partial/truncated/old/unowned/missing data stays unknown. Global runner state is not an owned-run result.
- Preserve complete item/NBT identity and decimal long quantities. Distinguish local ME provider buffers, storage cells, CPU receipt and player collection. Creative supply or injected boundary data does not prove survival cost, real manufacture or total capacity.
- GT-specific fast-forward advances only supported GT blocks/power tasks in the test area, not AE or global world time. AE delivery, cross-dimension logistics and timing acceptance require normal ticks; do not bypass a workflow's timing guards.
- Measure preparation-to-acceptance elapsed time, agent calls, internal requests and recovery cost. Separate accelerated/simulated time from real-world duration.
- Clean only verified task-owned test products. `forget` does not remove blocks. Preserve evidence still needed for diagnosis; do not expand cleanup to unrelated structures or a world rollback.

## Record the result

Reuse `tasks/<task-id>/state.md` from [workspace.md](workspace.md):

```text
Goal/change and acceptance:
Environment: versions, client/server, world/dimension, actual permissions/policy
Regression: passed | failed | skipped | unavailable; command, evidence, limits
In-game QA: passed | failed | skipped | unavailable; reason/case selection
QA correlation: actual selector/runId, testPassed/fullFlowVerified, pages, timing
Native client / dedicated server: separate actions, evidence and results
External assistance required or used; remaining native-control gaps:
Uncertain effects, recovery and cleanup:
Total elapsed time and call cost:
```

Add regressions nearest the root cause. Keep task evidence in task notes and reusable findings in the knowledge base. A server without QA can maintain the same records and use normal BridgeNH features.

## Set create-world options by target value

Read `mcb_gui_widgets` with the current `screenId` and `actionableOnly=false` to include choices on the other page. Native create-world choices expose `setting` (`game_mode`, `world_type`, `more_world_options`, `map_features`, `allow_commands`, `bonus_chest`), current `selected`/`value` and `options[].index/value`. Match the desired value to its observed index; do not infer it from translated button text or filtered array position.

Use `mcb_gui_widget_action(action="set_selected",widgetId=...,selected=...)` once. It performs the required native transitions and returns `localSelectionMatched`, `after` and `nativeSelection.afterState` in that same call. Already-matching values invoke no callback. A confirmed local menu value does not need another click or a separate read merely to establish the same selection; synchronized game effects still require their own acceptance.

For several choices, use `action="set_selected_many"` and 1..8 unique `selections:[{widgetId,selected}]`, without a top-level widgetId. Use current widget identities and runtime option indices. Order the choices so each changed control is visible when reached: select creative mode, select the advanced page, then select flat world type. Read with `actionableOnly=false` before composing this batch; hidden-page controls retain identities but cannot be changed while hidden. Already-matching hidden choices require no page change.

Accept the whole batch only when `localSelectionMatched=true` and the final `afterState` matches the request. On `status=partial`, inspect per-selection receipts and current values before continuing; do not replay the entire batch. Creation/cancel buttons cannot be included. Normal mode/cheat defaults and world-type preset clearing remain native behavior. The result configures pending menu values; it does not create or save a world. For a longer menu chain, use the guarded `mcb_sequence` menu mode below.

## Cross-dimension logistics and sustained runs

Versioned reference mechanisms must be revalidated against the installed client:

- ServerUtilities 2.4.14: claiming and forced loading are distinct. Check enabled features, ranks, effective permission and team quota across dimensions for the union of structure, ports, network, power and storage chunks. Native claim/load affects the player's current chunk. Submission, absent chat or old saved files are not current load proof. Verify native feedback and operation after leaving; do not edit persistence files.
- AE2 1080: ordinary wireless connectors are same-dimension. Quantum links need a natively produced matching singularity pair with complete frequency NBT, eight rings around each chamber, power/load at both ends and network attachment through a non-corner ring. Move NBT items through real slots, not no-NBT inventory_moves. Same live grid/active proves connection, not delivery: use a finite batch and source decrease/destination provider increase, then observe from another dimension.
- ME chest front opens its cell terminal; other faces access cell configuration. Choose actual facing and STORAGE_CELLS slots. EnderStorage 1.8.7 requires matching owner/frequency; wait for bag binding NBT. GT IO uses actual working faces. Carrying items through a portal is not automatic logistics.
- PersonalSpace 1.0.41: verify loaded destination support, full body clearance and return route before entry. Default unloaded heights and portal onGround are not clearance proof. Re-read connection/dimension/dual flight after transfer; leave safely using native movement. Wait for actual dual flight before further vertical input. A portal is not a rocket-travel test.

Use `mcb_spatial(mode="positions")` on loaded destinations without forcing unknown terrain to load. For takeoff use the supported `mcb_flight_check(action="probe")`; a submitted flight packet is not stable flight. GT placement may bind expectedPlacementFacing; creative requires placementFacingCreativeVersion=1 and actual serverConfirmed, zero source consumption and facing evidence. Replaced hatches need a fresh native structure check.

Stale GUI recipe text can coexist with a current structure failure: inspect active/progress and the reported invalid position, repair natively, recheck and enable. Empty widget pages may be soft-budget exhaustion; use nextOffset. Temporary flight height is not arrival: restore target height after horizontal clearance and verify the full path/inertia.

Keep complete production chains as task-specific compositions, with finite input/output, measured throughput and normal ticks. Do not add dedicated hard-coded chains or infer precise energy deltas from abbreviated scientific-notation displays. Creative/debug supply does not prove survival cost or net efficiency.

## NEI version references

Bookmark reference: [NEI 2.8.155-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.155-GTNH/NotEnoughItems-2.8.155-GTNH-sources.jar), SHA256 `fd7efd4ad77a7d250b2d2e9854cba7d643222a941cc28a4c808937505c0b2973`.

- Defaults/modifiers: NEIClientConfig.setDefaultKeyBindings, KeyManager.isKeyDown/isHashDown.
- Item/recipe toggles, count preservation, V and deletion: api/ShortcutInputHandler; favorites: recipe/GuiFavoriteButton and recipe/widget/RecipeWidget.
- Grouping, ordering, wheel and retrieval preparation: BookmarkPanel, bookmark/BookmarkGrid and SortableItem.
- Quantity/fallback: ItemQuantityField, PanelWidget, NEIClientUtils.cheatItem/getScrollMultiplier.
- BridgeNH coverage: NeiControlAdapter, NeiOverlayAdapter.bookmark, NeiItemControlAdapter and catalog/nei.py / catalog/gui.py.

Earlier baseline: [NEI 2.8.145-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.145-GTNH/NotEnoughItems-2.8.145-GTNH-sources.jar), SHA256 `d4ede32d2bd510e4d5312f79a2012da79ea7fec7f5eab23c52064e4377af3c5e`. Relevant classes also include RecipeItemInputHandler, GuiUsageRecipe, GuiRecipe, NEIRecipeWidget, SearchField/TextField/SearchTokenParser/ModNameFilter, GuiOverlayButton and DefaultOverlayHandler.

World settings can override NEI mode. Read current bindings and tooltips for key conflicts. Chemical search depends on the active provider, and recipe filling depends on the target GUI's handler.

## AE2 source references

[AE2 rv3-beta-1073-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Applied-Energistics-2-Unofficial/rv3-beta-1073-GTNH/Applied-Energistics-2-Unofficial-rv3-beta-1073-GTNH-sources.jar): AEBaseGui, GuiMEMonitorable/ContainerMEMonitorable, VirtualMEPatternSlot/VirtualMEPhantomSlot, MEGuiTextField/ItemRepo/NEISearchField, GuiPatternTerm/ContainerPatternTerm/GuiPatternTermEx, GuiCraftAmount/PacketCraftRequest/GuiCraftConfirm/GuiCraftingCPU, GuiInterfaceTerminal.

[NEE1.7.45 sources](https://github.com/GTNewHorizons/NotEnoughEnergistics/tree/1.7.45/src/main/java/com/github/vfyjxf/nee): NEEPatternTerminalHandler, NEECraftingTerminalHandler, GuiEventHandler, GregTech5RecipeProcessor, PacketNEIPatternRecipe, NEECraftingPreviewHandler. [BogoSorter hover search](https://github.com/GTNewHorizons/InventoryBogoSorter/blob/1.3.54-GTNH/src/main/java/com/cleanroommc/bogosorter/compat/ae2/Ae2TerminalSearchAdapter.java#L37). NEI Shift gating: IOverlayHandler, GuiOverlayButton, NEIClientConfig in2.8.145-GTNH.

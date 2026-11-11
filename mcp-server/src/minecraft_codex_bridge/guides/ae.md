# GTNH AE2 terminals, patterns and crafting

Agent reference: English; [Chinese companion](ae_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

For Minecraft 1.7.10 / GTNH AE2. Source baselines: AE2 rv3-beta-1073-GTNH, AE2FC 1.5.110-gtnh, NotEnoughEnergistics 1.7.45, NEI 2.8.145-GTNH and InventoryBogoSorter 1.3.54-GTNH. Later sections identify AE2 1080/AE2FC 1.5.113 separately. These source baselines do not establish complete live-test coverage.

## Batch configuration, installation and resumable orders

With authorized local developer access and aeGoalWorkflowVersion=1, reuse:

- `mcb_ae_configure`: 1..32 exact hosts/parts, priority or BLOCK/STORAGE_FILTER/ADVANCED_BLOCKING_MODE. Native submission must be followed by server-host readback; a text-box value is not persistence.
- `mcb_ae_install`: exact attachment face, hotbarSlot and expectedPart. placement=host uses the clicked host; adjacent uses its neighbor. Mounted face is the clicked face or its opposite respectively; center cable is 6. The native path tries host before neighbor, so bind the intended destination. Do not improvise asynchronous sneak/use pairs. Verify exact parts and full inventory conservation.
- `mcb_ae_order`: start with a unique requestId, terminal, exact item and amount. Preview, start once, follow native craftingId, collect and verify server receipt plus complete client slot identity/NBT. Resume the same requestId/session/world/dimension after timeout. Missing materials return preview; inventory space can be freed before continuation. Partial collection stops automation and preserves the real cursor.

All bind expectedSessionId/expectedWorld/dimension, default thirty seconds/max sixty. Configure/install retain completed entries on failure. Orders handle items, 1..64 within one native stack, with free CPU, empty cursor and an available empty inventory slot. A placed terminal needs an empty hotbar slot for opening; a portable terminal must be held. Omitted item.nbt means no NBT. Ordering clears search and selects CRAFTABLE; required type filters must already allow the item. At most 64 orders are retained per process without automatic eviction/reordering. Receipts do not survive client restart.

`collecting` means production finished but pickup remains; `delivery_sync` means server delivery occurred and only client synchronization remains. Resume read-only waiting, never collect again. approachWalkingSegmentsVersion=1 supports segmented walking within the same bounded window, preserving per-segment route and final rays. A server hit guides a new client ray, not proof of success. Ordinary waits sample near one second; orders retain native event waiting.

## Plan and observe the native path

Plan terminals, drives/storage, CPUs, interfaces and access before placing a controller. Declare placement_preflight.layoutComponents or builder placement.layoutComponents with unique id, role=equipment|io_routing and inclusive relativeBounds min/max XYZ. These are calibrated controller-relative world-axis offsets, not StructureLib ABC, and do not rotate automatically. Default above_ground includes equipment in floor-height planning.

Only pure IO may use hidden_bottom, exactly one layer below the above-ground envelope. Terminals/storage/CPUs remain equipment. Hidden routing still participates in bounds and needs existing clearance; hiddenRoutingBlocked does not authorize digging. Use returned assemblyLayout components worldBounds with the same proposedController. Undeclared equipment is uncovered; a layout declaration neither installs AE nor moves existing machines.

Read snapshot/GUI/inventory, current IDs/widgets/coverage, cursor and search. Prefer structured data, then exact-version source if needed. Use native screenshots only for necessary rendering evidence; do not normally save images or claim control over host media retention. Public tooltip reads do not move the physical pointer or emulate modifiers.

ME resources/pattern entries are VirtualMESlot objects, not vanilla slots. Generic gui_click is refused where cached hoveredVirtualSlot would make it unsafe. Use AE resourceId/named actions; only real inventory slots use windowId/slot. Player sneak and text characters do not emulate GUI modifiers. Record unsupported controls as native capability gaps; Computer Use cannot count as native acceptance.

NEI opened from a terminal exposes recipe PositionedStacks, not ME resources. Its raw recipe/display overclock data is not actual machine processing. Use semantic nei_open/nei_navigate and inspect coverage.

Prefer ae_install for parts: it owns Forge events, approach and finite-material acceptance. Controller-only right-click is not equivalent. Low-level input needs fresh ray/held-state verification and one submission; an unexpected GUI or timeout requires observation, not repeated use.

## Wiring, output and blocking

Choose cables and IO for actual channel needs, layout and access. Available cable types include covered, glass and smart cable. GT ME output buses/hatches, AE export buses and AE storage buses have distinct roles. Supported GT ME outputs can return directly to ME; ordinary outputs can push into compatible interfaces only when native push behavior and faces are verified. Size finite output capacity for batches/byproducts; do not claim performance improvements without evidence.

Locate pattern-sending interfaces near permitted return ports to share main-network access, while input subnets reach real ports. Pattern position does not determine feed order; priority/filtering/native port order do. Recompute after rotation, preserving isolation/channels/service clearance; there is no fixed tail direction or universally legal hatch position.

| Isolation | Required checks |
| --- | --- |
| Color | Supported adjacent components with different non-default colors. Fluix connects to other colors and is not isolation. |
| Anchor | Exact mounted face and intended neighboring cell; no need to blanket every segment with anchors. |
| Quartz fiber | Power sharing with separate data networks/channels. Facades do not provide this isolation. |

Reference channel rules allow eight channels through ordinary cable and 32 through dense trunks; count interfaces/storage buses and shared bottlenecks. Adjacent branches can still share a lower trunk. Check direct connections, trunk, online nodes and current grid IDs. Missing nodes do not prove isolation, and IDs are not stable across restart. Recompute mounted faces after orientation changes.

For the referenced blocking design, enable BLOCK=YES on the pattern-holding **sender**, and install an advanced blocking card on the receiving subnet interface. Receiver BLOCK and the card do not replace sender BLOCK. In GTNH 2.9.0 DEFAULT ignores declared catalysts; STRICT does not. Smart blocking is separate and must not be enabled without ordering/capacity validation.

After installation/removal/rotation, batch-read exact parts, grid, priority and filters with world_inspect_many(includeAe=true). Recovered configuration NBT does not prove correct reinstallation. Ordered feeding needs actual port order and strictly descending priorities, including unintended zeros. STORAGE_FILTER=NONE can expose non-extractable GT inputs. If public fields omit upgrade cards, inspect real upgrade slots or authorized native NBT capture; BLOCK does not prove a card exists. Configuration and real feed/return are separate acceptance; do not replay unknown orders.

Source topics: [network connections](https://gtnh.huijiwiki.com/wiki/应用能源2/连接ME网络), [facades](https://gtnh.huijiwiki.com/wiki/线缆伪装板), [autocrafting](https://gtnh.huijiwiki.com/wiki/应用能源与自动合成). [Assembly-line automation](https://gtnh.huijiwiki.com/wiki/装配线自动化) describes an older 2.8.0 design and is an interface-role example, not proof of current compatibility.

## Feed buffers and concurrency

Direct blocking against machine inputs can let residual materials from later stages prevent another batch; increased amperage does not resolve this condition. A possible design feeds single-slot staging inventories in ordered priority, then conveyors move into multi-slot inputs while the subnet observes staging. Validate native faces, cover direction/mode, priorities/filters, channels and isolation. This is an option, not a mandatory component tier or recipe-specific workflow.

Expose ports/staging/covers for rays and replacement. Staging inventories are equipment in placement planning; recheck access after rotation. GT covers may depend on a nine-region hit point, not side alone. Bind the same block-local hitPoint in approach/interact when interactionHitPointVersion=1 is advertised, then read actual cover face/mode.

Compose watches (about one second), captures and orders to verify stage progress, consumption and pickup. In referenced GT5U 5.09.54.205 assembly-line logic, mProgressTimeArray tracks stages and lEUt=working*baseEUt; currentParallel=1 is per-stage and stale idle energy is not concurrency proof. Deliver/drain before changing orders. Incomplete repeated materials and mixed recipes need separate cases; do not generalize one recipe. [Advanced assembly-line automation](https://gtnh.huijiwiki.com/wiki/进阶装配线自动化) provides a design reference. Re-read hotbar after rearrangement; preserve order-owned synchronization/full identity/empty-cursor checks.

## Structured reads and actions

`mcb_ae` requires current screenId. Scope auto/terminal/pattern/preview/cpu/interfaces defaults auto; offset zero, limit64/max128. Terminal reads filtered/sorted resources beyond scrollbar position; pattern reads full slots including hidden pages/empty entries; preview reads storage/pending/missing; CPU and interfaces read synchronized state. Inspect coverage/scanTruncated/byteLimited/budget/nextOffset/hasMore and pending synchronization. Missing data is not an empty network.

resourceId resolves actual typed AEStack/full NBT even when displayed identityNbt is truncated. Never reconstruct it from an icon/summary. Counts are decimal strings; action amount is a positive integer 1..9223372036854775807, not float/scientific notation.

| mcb_ae_action family | Fields and limits |
| --- | --- |
| terminal_search / terminal_setting | text with resources/inputs/outputs/names; advertised SORT_BY/SORT_DIRECTION/VIEW_MODE values. |
| terminal_type_filter | Current advertised type and boolean enabled. |
| resource_container | resourceId and fill_single/fill_all/drain_single/drain_all; native typed contents. |
| resource_take / resource_deposit | stack/single/half/inventory; all/single deposit from the real cursor. |
| craft_open / craft_request | Exact resource; amount, default STANDARD; lite/autoStart/follow default false. |
| pattern_set | input/output, full storage slot, resourceId or actual playerSlot0..35, amount; clear=true excludes source/amount. |
| pattern_mode | crafting/substitute/beSubstitute/inverted and boolean. |
| pattern_multiply / pattern_page | -8/-2/2/8 with native whole-pattern divisibility/overflow checks; valid current page. |
| pattern_encode / pattern_clear | move/wholeStack default false; wholeStack requires move=true. |
| preview_start / cpu_select | follow default false; actual serial or -1 for native auto. |
| job_cancel / job_pause | Current expectedCpuSerial; boolean paused for pause. |

Use only each action's own fields. Native GUI/container submission needs no physical hover/input, but submitted is not server completion. Wait and read AE/inventory/machine results; do not repeat writes for a delayed display. Reads are bounded:128 rows/page,4096 traversal entries/128KiB, cooperative4ms; getters are not forcibly preempted. Interfaces have at most64 slots/48KiB per entry. state.virtualSlots has at most64 registered typed entries; slotIndex is not a container slot. Continue from returned pagination only.

Protocol page ceiling127 is not actual capacity; the older extended terminal has pages0..1. Job serial guards apply to network GuiCraftingStatus; a fixed GuiCraftingCPU can be readable without an actionable network serial. Widget activate needs physical modifiers/buttons released to preserve callback meaning; wait/re-read instead of changing physical input. MUI2 commit controls blur validation/sync, whereas AE onTextChange may act immediately despite commit=false. Child IDs are bounded to128; inspect child truncation/parent paging.

## Search and human gesture reference

AE's search differs from NEI's. Right click clears/focuses; Enter blurs. Autofocus/search persistence are configurable. Setting buttons commonly cycle forward/backward with left/right click; read the actual tooltip/state.

NEI parsing may apply even without text synchronization. Depending on current config, plain terms, @GregTech display-name filtering, AND spaces, | OR, exclusion, quotes, wildcards and explicit regex are available. ALWAYS tooltip/oreDict/identifier filters do not guarantee #/$/& prefixes; see [NEI search](nei.md#search). Also inspect view mode/type filters/view cells. CRAFTABLE may display zero count; Shift pause affects list reordering, not the server.

| Reference input | Context/meaning |
| --- | --- |
| R / U | Hovered recipe/use query, search unfocused. |
| P | Hovered pattern recipe query. |
| F | NEI global field focus, not necessarily AE focus. |
| T | Enabled BogoSorter hover search with unfocused fields; escaped name enters ME search. |
| Container interaction key, commonly left Ctrl | Rebindable typed-container interaction. |
| pickItem, commonly middle click | Craftable-item quantity request, including stocked items. |
| Tab / Shift+Tab | Forward/backward interface-search focus. |

BogoSorter can return from NEI to the matching terminal. Unbound keys remain unbound; do not copy a private client's settings.

| Resource-list gesture | Native effect |
| --- | --- |
| Empty left click | Up to a stack to cursor; zero-stock craftable item opens request. |
| Held-item left / right | Deposit all / one, retaining unaccepted remainder. |
| Empty right click | Up to half a stack, rounded up. |
| Shift+left / Shift+right | Stack to inventory / one to cursor, possibly adding to a matching stack. |
| Space+click | Stacks to inventory until stock/capacity runs out. |
| Shift+wheel up / down | Deposit one from cursor / withdraw one. |
| Interaction key+left / right | Fill a container / deposit its contents; Shift adds batch handling. |

These apply to ME resources, not player or phantom slots. Container items and contents have distinct identities/types. Agents use semantic actions rather than physical gestures.

## Patterns and NEI transfer

Crafting patterns represent real table layouts; processing patterns declare machine IO, not execution. NEE may switch mode; verify after transfer. Virtual pattern editing does not consume the displayed real item. Native left click sets held quantity or clears when empty; right click increments/decrements. Container interaction can select typed contents.

- Processing-slot middle click opens a quantity expression (for example64*8), committed by Set/Enter.
- Whole-pattern buttons multiply/divide by2 or left-Shift8. All IO must divide exactly and remain in range or nothing changes. Middle click opens general multiplier, positive>1 or negative<-1. The baseline reads left Shift, not necessarily a rebound sneak key.
- Ordinary encode writes the output pattern; Shift moves it to inventory; interaction-key+Shift may copy all remaining blanks. Short inventory can drop items; do not confuse this with single encoding.
- The referenced extended terminal has32 slots (4x4x2) on its large side and8 (4x2) on its small side, invertible. Old16/4 names do not define capacity. Inversion clears entries beyond the smaller side: choose layout first and verify both pages.
- Typed IAEStack patterns require compatible interfaces/machines. Encodability is not execution proof; substitution and beSubstitution are distinct and incomplete execution knowledge stays explicit.

AE VirtualMEPhantomSlot widgets expose ae_phantom_slot ghost_drag(source)/phantom_click(button), native filtering and synchronization. Templates grant no items. Reopen or inspect native serialization to confirm configuration. Hovering empty cells can create empty client tags; native GUI resynchronization can restore NBT consistency. Do not globally equate null with empty compounds, or repeat successful placement because a final-item zero-count remnant remains.

Open the correct recipe from the intended terminal, transfer using its supported overlay, then inspect mode, quantities, IO, alternatives and pages. Table recipes belong in crafting terminals, machine processing recipes in compatible patterns. With shiftOverlayRecipe=true, referenced NEE1.7.45 handlers do not override NEI's Shift gate despite a noShift config entry; plain click may show ghosts. Current hints take precedence. Quantity/material modifier-wheel behavior depends on actual settings and cached alternatives.

After verifying the required encoded patterns or target markings, automatically clean the temporary NEI source ghosts and task-only bookmarks used to create them, following [NEI cleanup](nei.md#temporary-ghosts-and-bookmark-cleanup). Preserve the encoded patterns, saved target configurations, permanent bookmarks and those explicitly needed for later patterns or work. For large pattern batches, organize temporary bookmarks into separate pages/namespaces and clean each finished batch once its entries are no longer needed. Report any cleanup blocked by missing native actions instead of repeating encoding or claiming the bookmarks were removed.

Check config-dependent semantics:

- includeNonConsumableIngredients=false may omit zero-count GT tools/circuits/molds from the pattern; they can still be required in the machine.
- itemCombinationMode merges within native limits/processor exceptions, not infinitely.
- Probabilistic outputs are not guaranteed to be filtered. Verify before encoding required outputs that could leave a job waiting forever.
- useStackSizeFromNEI/keepGhostitems and old SlotFake versus current VirtualMEPatternSlot affect transfer/drag compatibility; not every combination is verified.

NEE request/missing-material/skip-preview modifiers can also be Shift-gated. Ordinary validation retains preview instead of auto-start.

## Crafting and diagnosis

Empty cursor -> exact resource -> quantity -> Next -> wait preview -> inspect missing items/CPU/memory -> Start -> observe the selected CPU. Quantity expressions may be available. Submission is not completion. Shift+Next auto-starts; interaction-key+Next auto-starts/follows; both together differ in the referenced implementation. Enter activates Next.

Preview Shift+Cancel may bookmark missing items; job Cancel actually cancels. Bind the correct CPU for Cancel/Suspend. Merge is potentially mutating. Diagnose craftability, pattern output identity/count/type, interface online/type support, CPU/storage/power, machine conditions/tools, then exact output return. Terminal acceptance is not full production.

## Interface terminals and portable owners

Input/output/name searches differ. Input/output must match one pattern and use NEI parsing; name filtering has separate semantics. Tab cycles input->output->name; autofocus is configurable. Group/offline/P2P/capacity filters can hide interfaces. Missing search results do not prove absence.

Later scope: AE2 1080, AE2FC1.5.113, GT5U5.09.54.205, WCT1.12.16. Pattern terminals edit virtual recipes; interface terminals manage real encoded patterns. `mcb_ae(scope="interfaces")` returns synchronized IDs/positions/dimensions/slot counts. interface_pattern_slot widgets bind interfaceId/patternSlot; activate sends native real-cursor pickup/place. Check source/destination/fullNBT/empty cursor. A36-slot GT ME pattern input hatch does not prove any machine accepts it or processed a pattern.

Select the actual busy CPU before cpu_locate_providers; wait for providerLocator coordinates/dimension. cpu_highlight_providers/interface_highlight close the GUI and highlight natively; navigation/machine inspection are separate. Interface is not controller, highlighting is not arrival. Packets lack request/job IDs: one unchanged screen/CPU holds one query, and external concurrency/job replacement limits correlation.

Ordinary wireless connectors are same-dimension; quantum links need separate pairing/loading/delivery acceptance in [operations.md](operations.md#cross-dimension-logistics-and-sustained-runs). Handheld wireless access points are different devices, wired on their actual back face. Check terminal energy/binding/range independently. CPU location depends on server IGridHost/openContext; a client-empty openContext alone is not unavailability, but a real coordinate response is still required.

Verify each ordinary/WCT/AE2FC wireless mode separately. Security-terminal binding uses real encoding slots; retrieve output before closing. Re-read IDs after universal-terminal mode changes. With aePortableOwnerVersion=1, scope=owner gives actual player/dimension/slot/item/network; equip then use that owner for portable ordering. Network-capable modes reuse preview/order/collection. Portable cells are independent storage, not network-order hosts. Owner identity excludes changing settings/energy NBT, but collection checks the whole inventory and permits only exact native-API-reproducible energy changes.

Scope=requests pages synchronized AE2FC requests/maintainers with positions, slots, typed resources and decimal counts; check online/slotsPending/slotsTruncated. Providers are necessary; stock alone creates no requests. A level emitter quantity is a threshold and batchSize=-1, not stock/batch size. Paging/state transitions do not prove large-network behavior. Referenced direct entry craft-click is unimplemented: reads do not place orders.

## Sources and verification limits

[AE2 rv3-beta-1073-GTNH sources](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Applied-Energistics-2-Unofficial/rv3-beta-1073-GTNH/Applied-Energistics-2-Unofficial-rv3-beta-1073-GTNH-sources.jar): AEBaseGui, GuiMEMonitorable/ContainerMEMonitorable, VirtualMEPatternSlot/VirtualMEPhantomSlot, MEGuiTextField/ItemRepo/NEISearchField, GuiPatternTerm/ContainerPatternTerm/GuiPatternTermEx, GuiCraftAmount/PacketCraftRequest/GuiCraftConfirm/GuiCraftingCPU, GuiInterfaceTerminal.

[NEE1.7.45 sources](https://github.com/GTNewHorizons/NotEnoughEnergistics/tree/1.7.45/src/main/java/com/github/vfyjxf/nee): NEEPatternTerminalHandler, NEECraftingTerminalHandler, GuiEventHandler, GregTech5RecipeProcessor, PacketNEIPatternRecipe, NEECraftingPreviewHandler. [BogoSorter hover search](https://github.com/GTNewHorizons/InventoryBogoSorter/blob/1.3.54-GTNH/src/main/java/com/cleanroommc/bogosorter/compat/ae2/Ae2TerminalSearchAdapter.java#L37). NEI Shift gating: IOverlayHandler, GuiOverlayButton, NEIClientConfig in2.8.145-GTNH.

Source conclusions are not new live verification. Runtime rebinding, probability filtering, all drag paths, conflicts, maximum wireless range/upgrades, pins/item-stream diagnostics and complete substitution execution need environment-specific acceptance. Installed versions alone do not prove availability.

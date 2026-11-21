# BridgeNH operation checks and boundaries

For Minecraft 1.7.10. Use existing tools to assemble the required operation checks. Current health, permissions and coverage decide availability. `mcb_operation_check` is a diagnostic entry point, not an extra preflight around workflows which already own their checks. Record missing native paths using [workspace.md](workspace.md); external input cannot establish native acceptance.

## Choosing an interface

Use the structured action whenever it covers the requested operation. For example, use `mcb_chest_transfer` for an observed stack in an open vanilla chest, `mcb_nei_navigate` for recipe pages, `mcb_navigate` for travel and `mcb_mine` for a specific block. For a GUI control, inspect the relevant pages of `mcb_gui_widgets` and use the advertised `mcb_gui_widget_action`, including supported scroll and drag actions. If coverage leaves the target unidentified, keep the step pending.

On a permission denial, stale target, partial result or unknown outcome, stop. Read the current permissions, target and resulting state before deciding what remains. Switching to coordinates or replaying the input can bypass a refusal or repeat a change.

## GUI mouse gestures

Use the virtual mouse for an authorized gesture on an identified, visible control that lacks a structured action (for example, scrolling a custom help panel), or for an explicit test of the pointer itself. Read the current `screenId` and scaled size from `mcb_gui`. Convert framebuffer pixels to GUI coordinates if needed. When the client advertises `gui.virtual_mouse`, call `mcb_virtual_mouse` with the current `expectedSessionId`. Use `move` to hover, `click` for one click, `scroll` with signed `notches`, or `drag` from `x/y` to `toX/toY`. Input follows the screen’s draw cycle. Vanilla lists read the wheel while drawing.

During takeover, `virtualMouse.displayMode` is `pointer` during a gesture, button hold, brief coordinate-click feedback or `mcb_gui_pointer` hover lease. The pointer follows the GUI target. Otherwise `status` shows a separate Codex logo below the world crosshair or at the same position in an idle GUI. Manual control and F1 in the world select `hidden`. A held button selects pressed, text focus or editing selects text, and a native wait or active task takes precedence as busy. `indicate_thinking` sets busy for `durationMillis` (0–120000) without showing a pointer. Zero, a screen change or handoff clears it. Configured Codex hooks show busy during compaction. Completion or interruption clears only the compaction signal.

For separate calls, `press` returns a `gestureId`. Use it for subsequent moves and the final `release` within the 15-second hold lease. A screen or size change interrupts the gesture. After an interruption, inspect the visible state and carried stack. A dispatch receipt records input delivery only. Verify the intended effect, such as the visible page or synchronized counts at both ends of a transfer. For a pointer test, verify the requested pointer behavior. Even if a gesture succeeds, report the missing structured action, receipt, readback and any unresolved result in the [workspace gap template](workspace.md#native-control-gap-and-feedback-template). Existing `mcb_gui_pointer` hover and `mcb_gui_click` retain their own parameters and share pointer ownership.

## Server logs and runtime observations

Use `mcb_help({"topic":"maintenance"})` for [server log access, permissions and runtime observations](maintenance.md#server-logs-and-runtime-observations).

## Grounded movement on LAN and dedicated servers

Use `mcb_spatial` for a ray or a bounded page of positions. An authenticated operator client with enhanced observation uses the server tick when the server advertises `spatialObservationVersion=1`. Read the receipt's source and coverage before selecting a destination. For a dropped item, inspect its current position with `mcb_entities` and confirm collection from the player's inventory after arrival.

Use `mcb_navigate` with the current session, dimension and the server's `worldName` or `worldFolder`. With `operationPolicy.effective.clientGroundPlanVersion=1`, the client searches loaded terrain, checks each movement and replans up to four times when blocked. Longer level point routes advance through local legs as chunks arrive. Supply `segmented=true` for an explicit timeout up to 60000 ms. A route may use a vanilla wooden door, vanilla ladders or a same-color OpenBlocks elevator. Door use needs an empty hotbar slot. Check `serverProbes` and `serverArrival` for final confirmation. A server advertising `walkArrivalVersion=1` confirms settled arrival in one request. Older clients advertising only `remoteGroundWalkVersion=1` support level routes within 32 blocks.

Use `mcb_approach` with one observed block position and `targetIdentity` containing its registry name and block metadata. `move=false` returns the planned waypoints. `interact=true` submits one normal block use after both ends confirm arrival and the visible face. To deposit items, observe the opened chest and its slots, transfer the intended stacks and read back the inventory. Inspect the current pose and container after an interrupted walk or delayed GUI.

## Set create-world options by target value

For one-call world setup and readback, see [create-world options](testing.md#set-create-world-options-by-target-value) in `mcb_help({"topic":"testing"})`.

## Combine repeated operations and readbacks

Before issuing a prepared series of actions, combine the dependent actions and checks into one `mcb_sequence`. This covers repeated widget edits, slot gestures, NEI navigation/readback, AE configuration and other allowed tools. The worker performs each native action once and waits for declared observations internally. Use direct target setters, existing inventory/AE/bookmark batches and goal workflows when they already own the required acceptance. Independent reads use `mcb_observe`. Do not replace synchronization with repeated clicks.

An action may include `after` with its own `id`, read-only `tool` and optional args/bind/fields. Without `condition` it reads once; with `condition` it performs a bounded read-only wait and accepts the parent action. References to that ID use the complete fresh result. All expanded steps count toward 32; all IDs, arguments and references are checked before execution. An explicit partial, refused or unknown GUI, NEI or AE action stops before its `after` and later steps; other domain workflows retain their own acceptance. Inspect retained receipts and plan only reconciled remaining work.

Loaded-world chains require `expectedWorld`. At an unloaded menu, use `menuScreenId` instead. This requires the advertised native unloaded-menu guard and permits only health/GUI/widget reads, widget/key actions and assertions. Each action checks the current idle unloaded state on the client thread; screen identity remains mandatory. Re-read a newly opened screen within the chain and bind its observed controls. World loading or a pending lifecycle task stops continuation.

For example, a known menu button and expected destination class can be opened and verified in one call:

```json
{"expectedSessionId":"<current-session>","menuScreenId":"<current-screen>","steps":[{"id":"open","kind":"action","tool":"mcb_gui_widget_action","args":{"screenId":"<current-screen>","widgetId":"<observed-button>","action":"activate"},"after":{"id":"opened","tool":"mcb_gui","condition":{"path":"screen","operator":"equals","value":"<observed-destination-class>"},"fields":["screenId","controls.widgets"]}}]}
```

Later actions can bind screenId from `opened.screenId` and widgetId from `opened.controls.widgets` with an exact unique match. Use equivalent `after` reads for NEI/AE results and exact cursor/slot acceptance between inventory gestures. Known synchronization delays belong in an `after` condition or an explicit wait step; never replay the preceding action on timeout.

Widget action projections retain actual `after` state and `localSelectionMatched` when available. Use that same-call result for local text/selection/value checks; do not read the identical state again merely to obtain a receipt. Server-backed effects still require their actual synchronized or authoritative observation. A batch reduces agent round trips, while native callbacks, guard checks and necessary game waits remain.

## History and one-call diagnosis

Use `mcb_recording(view="summary")` for passive player history, rather than custom log scripts. `list` pages this client's recordings; `events` uses recordingId/since/nextSince and optional kinds. Summaries cover commands, GUI, movement, slots and server block/AE-part changes, while reporting sequence gaps, omitted payloads, incomplete tails and truncation. Repeated state observations are not action counts. Disk history works offline and after restart, with no takeover/replay; a missing stop marker means unknown. `live` reads current enablement/queue metrics. A supported standalone CLI entry is `scripts/mcb.ps1 -Profile <profile> -Action Recording`; its selected profile is not a live chat binding.

Before AE work, read [wiring and blocking](ae.md#wiring-output-and-blocking). Choose cables and IO for the actual layout. Inspect sender blocking and receiver advanced-blocking separately, and re-read priority/filter/orientation/network after changes.

Obtain the current session/world and observed coordinates; replace placeholders with those observations and the actual recipe data.

```json
{"kind":"machine","expectedSessionId":"<current-session>","expectedWorld":"<current-world>","target":{"x":0,"y":64,"z":0,"dimension":0}}
```

| Check kind | Reads | Scope |
| --- | --- | --- |
| `machine` | `mcb_machine_inspect` | Exact world/dimension/position and authorized machine observation. |
| `ae` | Fresh GUI -> AE | Current open AE screen, optional scope (default auto), 16 entries. |
| `ae_buffers` | World inspection with includeAe | Exact declared interface/part host, local developer capability; not necessarily a controller. |
| `nei` | GUI -> NEI summary -> visible recipes with the same contextToken | Four entries in the current screen; no opening/navigation. |

AE/NEI checks require current expectedSessionId; machine/buffer checks also bind expectedWorld and target. Default budget is ten seconds, maximum 30000 ms. Workflow output is bounded to 56 KiB, within 64 KiB including fixed guidance. Preserve values/metadata/missingFields/omittedFields/coverageSummary and failure position. Reads are non-atomic, with no pagination, retries, takeover, screenshots or mutation. `completed` only means reads completed; `productionVerified=false`. Incomplete synchronization stays unknown. Capability/context/timeout failure stops further steps without switching clients or input methods.

## Process balance before construction

`mcb_operation_check(kind="process",process=...)` is offline. Declare only recipes already selected and verified from current recipe data:

```json
{"kind":"process","process":{"machines":["processor"],"resources":[{"id":"input","identity":{"kind":"item","registryName":"example:input","metadata":0},"role":"feed","initial":"6"},{"id":"output","identity":{"kind":"item","registryName":"example:output","metadata":0},"role":"product","initial":"0","expectedFinal":"2"}],"recipes":[{"id":"convert","machine":"processor","source":"<observed-recipe-and-version>","batches":2,"inputs":[{"resource":"input","amount":"3"}],"outputs":[{"resource":"output","amount":"1"}]}]}}
```

Roles are finite `feed`, unlimited auxiliary `external`, `intermediate`, final `product`, and reusable/circulating `cycle`. Fluid identity uses kind=fluid/actual fluidName. Counts are exact nonnegative decimal strings; recipe amounts are positive. External initial count must be zero and the same resource cannot also be produced. Catalysts use input-shaped declarations but are required, not consumed. Batches are explicit; each machine must be declared and each source recorded.

The tool checks missing producers/consumers/machines, disconnected products, unrecovered cycles and startup shortages, then simulates bounded complete batches. Feed/intermediate default to zero final balance; declare expectedFinal for permitted buffers or a specific product/cycle quantity. Balances and unlimited external consumption are separate. Pairwise batch multiples are local constraints, not proof of the smallest whole-line feed. For example, 360 and 11000 per batch align at 275 and nine batches respectively.

Only deterministic caller-selected recipes/counts are supported. It does not solve a full recipe graph or treat probability averages as guaranteed output. Order affects competing resources; exhausted computation returns unknown. `planValid` is the result, while completed only means computation ended. Confirm actual machine conditions and later real consumption/production.

## Site readiness before feeding

`kind="site"` binds session/world and a dimension with one or more declared collections:

- `machines`: id, position, optional metaTileId and requirements `{path,operator,value}` with equals/not_equals/gt/gte/lt/lte. Checks identity, formation, enabled state, maintenance and component coverage; standard GT mufflers include actual facing/front air. Declare voltage/heat/mode from supported fields. Controller storedEU=0 does not mean its energy hatch is empty.
- `endpoints`: id, position, targetIdentity, mountingSide and network. Use actual block/GT/part identity and face (ordinary blocks normally -1). Same network labels require the same actual grid; `isolatedNetworks` declares pairs which must differ. Active nodes do not prove transfer; logisticsTransferVerified remains false.
- `clearance`: named positions expected to be vanilla air; unloaded is unknown.
- `layout`: id, kind (machine/ae/pipe/clearance) and min/max XYZ bounds. Checks exclusive-volume overlap. Do not double-count a hatch already inside the machine volume; use multiple boxes for real occupancy/access instead of assuming a bounding box is solid.

Readiness is passed/blocked/unknown with reasons. A pass covers only declared supported checks, not power throughput, fluid transfer, chunk loading, every machine condition or production. It does not recheck structures, start machines or move materials. Process/site inputs and outputs are each limited to 64 KiB. `outputComplete=false` identifies omitted whole rows; omitted balances/failures are not zero. Split meaningful subplans when needed.

## Batch receipts and recovery

`mcb_ae_install` returns a checkpoint. Resume with that unchanged checkpoint and the same targets/session/world/dimension. It re-reads all destinations: completed entries must still match; other exact existing parts are skipped, and only missing items need material/placement preflight. Existing matching parts do not prove their origin or past consumption. Conflicts, drift, unloaded targets or nonempty uncertainIndices refuse continuation. Reconcile uncertain effects first, then explicitly plan any new action.

Sequence wait/assert can declare `accepts:["<earlier-action-id>"]` using an observation after that action, not a pre-action baseline or submission receipt. Checkpoints distinguish acknowledged, condition_verified, outcome_unknown, needs_reconciliation and not_dispatched. All declared acceptance conditions must pass; this proves only those observed conditions, not later cleanup/production. Compact results may use stepIndex/acceptedByIndices. Arbitrary sequences do not resume automatically; bind fresh GUI state and compose only remaining work.

## General machine work

Plan exact materials, equipment/IO/ME positions, orientation, player access and dependencies once in task notes. Batch-check the current environment, pose, materials and capacity, then re-read changes. `path` follows actual flight state: creative or permitted flight is not proof of current flight, and a landed player can walk.

For authorized no-NBT creative supply, `mcb_supply_items` accepts 1..8 kinds, each 1..64 and within native stack limits. It normally requires an empty inventory. Only advertised `nativeSupplyPreserveInventoryVersion>=1` allows explicit preserveInventory=true with a whole-batch reservation in ordinary 36-slot capacity. Counts are additions; subtract existing stock first. Offhand space does not count and different NBT/identity cannot merge. Insufficient capacity refuses before native supply. Verify both sides' final inventory once per batch; do not refill uncertain quantities. Use [NEI batch retrieval](nei.md#batch-retrieval-and-creative-inventory-sync) when actual panel selection is required.

Split placement/navigation into bounded `mcb_sequence` batches (up to 32 steps), AE installation into `mcb_ae_install` and configuration into `mcb_ae_configure`. Preserve per-action distance, rays, inventory/session checks and actual server confirmation. Batch-read final blocks, direction and networks. Keep completed effects after a failure and continue only reconciled missing steps. Report preparation-to-acceptance time, agent calls and internal RPCs; fewer outer calls do not mean fewer required native checks.

AE installation may reuse request-bound static capabilities, but still reads current snapshots and final rays for each target. Close only an actually open GUI. Do not duplicate health/close/target preflight outside it. Context/background-control changes stop the workflow. Inventory, geometry and authority are not cached as permanent authorization.

TP approach preserves verified dual-sided airborne flight for upper/lower face access with full body clearance. If landed or inconsistent, require supported feet; do not automatically take off. Exhausted candidate/ray/time coverage is partial, not proof of unreachable geometry or permission to bypass final reach/rays.

| Operation | Completion evidence |
| --- | --- |
| Observation/transform | Correct world/owner/orientation/coverage; no implied placement or formation. |
| Movement/approach | Actual posture, body clearance, destination and native allowed-face/ray checks; retain partial movement. |
| Placement/mining/rotation | Held identity, reachable native action, then actual block/part/facing/drops/materials. |
| Container transfer | Current window/real slots, exact source and empty cursor; source/destination/cursor/unrelated-item conservation. |
| GUI/configuration | Fresh screen/widget/resource identities and synchronized or server readback. |
| Waiting | Explicit condition, original deadline and coverage; timeout does not resubmit the preceding write. |

Sequence bindings can supply side/button/mode and XYZ, but must satisfy each schema. Missing/boolean/out-of-range sides cannot be clicked. Do not change step IDs or extend a deadline to replay an uncertain action. Refresh coordinate planning as a read rather than rerunning construction.

`mcb_inventory_moves` accepts 1..16 disjoint source/destination indices 0..35, whole no-NBT stacks with exact expectedItem and empty destinations. It preflights the entire batch, opens ordinary inventory once and verifies native pickup/place, cursor and unrelated full NBT. Success leaves final positions; reverse movement needs an explicit new declaration. No rollback/replay. Use exact real-slot operations for NBT, merging or dependent transfers.

Disk and pattern editing compose current observations -> exact identity assertion -> current bindings -> native slot/AE action -> sync/count/cursor acceptance. Real slots, AE resources and ghosts are separate. `mcb_plan_visits` orders 1..32 declared feet positions with optional dependencies, rejects cycles/missing IDs and compares stable topological versus nearest-ready order without increasing estimated distance. Same-position visits can merge; all task IDs survive. Euclidean distance is not a shortest/reachable route guarantee. Navigation and task semantics remain separate.

## Exact interaction, walking and finite tail completion

Use the same block-local `hitPoint` with a target's exact identity/allowedSide in approach and interaction. Native client/server rays and normal reach still apply. A successful click does not prove cover placement or machine state.

Walking preserves full-body collision, hazard and liquid checks. Thin supports can provide continuous native contact during translation; they need not fill the whole footprint. Wait for two distinct stable native ticks on both sides before moving; released keys do not mean inertia stopped. Supported fractional-height transitions do not guarantee arbitrary terrain traversal; differences below 0.125 are not independent jump/drop edges.

If preinstalled survival outputs cause native shell construction to report no_progress, do not repeat survivalConstruct. Within an existing complete blueprint task, `survivalBuild.finishAirTail=true` can use its full material receipt, captured coordinates and preserved-port checks to preflight at most 16 real-air tail positions and total inventory, place normally and independently verify formation. Only finite player materials are supported, not AE sources. Standalone survival_build lacks that complete task provenance; compose observations/materials/placement explicitly instead of rerunning an entire build to adopt an existing structure.

## Orientation, rotation and reflection

1. Distinguish world XYZ, StructureLib ABC and controller-relative world offsets. Use complete public direction/rotation/flip, including UP/DOWN; swapping X/Z or reading a screenshot is insufficient.
2. `mcb_structure_transform` binds actual source/target controllers, public piece and expectedAlignment, deriving axes from public zero/unit-offset queries. Named points and inclusive bounds use controller-relative world XYZ; all eight box corners transform. A proposed anchor does not prove a placed controller.
3. Without a target reference controller, use targetAlignment plus anchor and advertised structureAlignmentFramesVersion=1. The exclusive `world_structure(alignmentFrames=true)` view exposes ExtendedFacing matrices and the source's current limits. Cross-check the public probes/matrix and recheck the source afterward. Returned target controller=null/placed=false remains unplaced. An allowed source frame does not prove new-site/mode validity.
4. Transform world sides 0..5. Attachment faces and mounted faces may be opposites; verify this relationship before reusing either face. Side 6 is only a declared center/node, never a seventh clickable face. Thin parts still need actual shape/rays.
5. World gravity, ground, support and body clearance do not rotate with a machine. Recheck terminals/CPUs/drives for access after transformation and recompute hidden routing in final world Y. Geometric facing does not alter metadata or replace native placement/wrench rules. Place, inspect actual facing, then perform an independently authorized correction if needed. Elevated survival construction does not grant flight/TP.
6. Point IDs/order do not infer port role, registration index, repeat count or recipe-slot order. Mirroring is not evidence of a changed roleIndex; verify formation, port order and feeding separately.

Transforms are read-only, default ten seconds/max sixty, with bounded reads and final owner/alignment checks. Missing public transforms, drift or budget exhaustion stop. `placementVerified=false` and `reachVerified=false` remain explicit.

## Machine and recipe evidence

Identify owner/dimension/mode, formation, recipe result and stop reason before checking voltage/current/energy, maintenance, coils/glass and special conditions. Ordinary energy-hatch limits do not generalize to multi-amp/laser machinery; absent fields are unknown.

Source scope: GT5U 5.09.54.205 `MTEMultiBlockBase.runMachine` continues an active batch while allowedToWork controls later starts; `stopMachine` clears progress/pending outputs. Disabled does not prove stopped. Subclass overrides need separate checks. `ProcessingLogic.validateAndCalculateRecipe` consumes through helper.build before applyRecipe/onRecipeStart, so a failing startup hook need not refund inputs. Inspect actual inventory before refilling/restarting. The base completion path attempts outputs then clears pending products; blocked output need not remain at full progress.

Prove exact consumption, actual output, recovery and stopping. Progress reset, recipe counters, cached matches, Waila text and NEI overclock are insufficient. Use decimal longs and complete NBT; a machine watch alone does not prove conservation.

Distinguish whole blocks from same-cell AE parts before removal. Use a compatible native wrench/sneak-use with exact targetPart for individual parts; whole-cell mining can destroy other parts or the block behind it. Tool gestures and retained configuration NBT are version-specific. Read remaining parts and recovered items; never reconstruct drops from registry names alone.

AE handles resources, patterns, dispatch and receipt; a processing pattern does not run the machine recipe. Check catalysts, reusable tools, chances, container returns and substitutions. requestedPending/startReady=false means read the same preview, not craft_request again. CPU cancellation may leave pending interface sends: inspect CPU, explicit host buffers, machine inventory and return-to-network separately. One ae_buffers host does not prove the entire network empty. NEI exposes recipes/candidates, not inventory or actual overclock. Release residual ghosts after synchronized configuration; do not generate materials in survival for ghost marking. Other mods' script/component semantics do not become generic GT/AE rules.

## Cross-dimension logistics and sustained runs

Verify chunk loading, delivery and sustained operation using the [cross-dimension checks](testing.md#cross-dimension-logistics-and-sustained-runs) in `mcb_help({"topic":"testing"})`.

## PersonalSpace portal GUI

`GuiEditWorld` in PersonalSpace 1.0.41 uses existing widget reads/actions. Open settings with native empty-hand sneak-use, then bind current screen/widget IDs and supported actions. presetEntry edits presets; toggles/day controls use set_selected; sliders use set_value; moreSettingsButton/backToMainButton navigate; save/cancel finish.

The block dropdown uses native set_text search and actual options.index with set_selected. addMode=true adds a layer on each selection: do not repeat it. Close an open dropdown with activate before another control. Logical hover supports the native dropdown for five seconds without moving the OS mouse; expiry follows native behavior and never changes option numbering.

The mod parses presets/text/sliders on natural render frames. commit=true delays local blur until that frame; require pendingNativeFrame=false before save, then reopen/read persisted values. Disabled terrain-generation settings retain native restrictions. Per-frame rebuilt layer-row IDs are read-only; edit using presets/dropdown, not stale rows. Sliders quantize natively. The adapter does not write the dimension model/NBT and has no hard dependency when absent.

## Implementation and version references

Implementation references: MachineDataAdapter, AeDataAdapter, AeWorldAdapter, NEI view adapters and guarded workflows. Versioned source references include GT5U 5.09.54.205 MTEMultiBlockBase/ProcessingLogic, ServerUtilities CmdLoad/ClaimedChunks, AE2 QuantumCluster/TileQuantumBridge/EntitySingularity/WireLessToolHelper, PersonalSpace PortalTileEntity and EnderStorage native inventories. For AE2 1080 and AE2FC 1.5.113, use the current client’s advertised capabilities and coverage to select supported operations.

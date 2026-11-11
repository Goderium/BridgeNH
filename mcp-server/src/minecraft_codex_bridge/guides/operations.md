# BridgeNH operation checks and boundaries

Agent reference: English; [Chinese companion](operations_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

For Minecraft 1.7.10. Use existing tools to assemble the required operation checks. Current health, permissions and coverage decide availability. `mcb_operation_check` is a diagnostic entry point, not an extra preflight around workflows which already own their checks. Record missing native paths using [workspace.md](workspace.md); external input cannot establish native acceptance.

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

Versioned reference mechanisms must be revalidated against the installed client:

- ServerUtilities 2.4.14: claiming and forced loading are distinct. Check enabled features, ranks, effective permission and team quota across dimensions for the union of structure, ports, network, power and storage chunks. Native claim/load affects the player's current chunk. Submission, absent chat or old saved files are not current load proof. Verify native feedback and operation after leaving; do not edit persistence files.
- AE2 1080: ordinary wireless connectors are same-dimension. Quantum links need a natively produced matching singularity pair with complete frequency NBT, eight rings around each chamber, power/load at both ends and network attachment through a non-corner ring. Move NBT items through real slots, not no-NBT inventory_moves. Same live grid/active proves connection, not delivery: use a finite batch and source decrease/destination provider increase, then observe from another dimension.
- ME chest front opens its cell terminal; other faces access cell configuration. Choose actual facing and STORAGE_CELLS slots. EnderStorage 1.8.7 requires matching owner/frequency; wait for bag binding NBT. GT IO uses actual working faces. Carrying items through a portal is not automatic logistics.
- PersonalSpace 1.0.41: verify loaded destination support, full body clearance and return route before entry. Default unloaded heights and portal onGround are not clearance proof. Re-read connection/dimension/dual flight after transfer; leave safely using native movement. Wait for actual dual flight before further vertical input. A portal is not a rocket-travel test.

Use `mcb_spatial(mode="positions")` on loaded destinations without forcing unknown terrain to load. For takeoff use the supported `mcb_flight_check(action="probe")`; a submitted flight packet is not stable flight. GT placement may bind expectedPlacementFacing; creative requires placementFacingCreativeVersion=1 and actual serverConfirmed, zero source consumption and facing evidence. Replaced hatches need a fresh native structure check.

Stale GUI recipe text can coexist with a current structure failure: inspect active/progress and the reported invalid position, repair natively, recheck and enable. Empty widget pages may be soft-budget exhaustion; use nextOffset. Temporary flight height is not arrival: restore target height after horizontal clearance and verify the full path/inertia.

Keep complete production chains as task-specific compositions, with finite input/output, measured throughput and normal ticks. Do not add dedicated hard-coded chains or infer precise energy deltas from abbreviated scientific-notation displays. Creative/debug supply does not prove survival cost or net efficiency.

## PersonalSpace portal GUI

`GuiEditWorld` in PersonalSpace 1.0.41 uses existing widget reads/actions. Open settings with native empty-hand sneak-use, then bind current screen/widget IDs and supported actions. presetEntry edits presets; toggles/day controls use set_selected; sliders use set_value; moreSettingsButton/backToMainButton navigate; save/cancel finish.

The block dropdown uses native set_text search and actual options.index with set_selected. addMode=true adds a layer on each selection: do not repeat it. Close an open dropdown with activate before another control. Logical hover supports the native dropdown for five seconds without moving the OS mouse; expiry follows native behavior and never changes option numbering.

The mod parses presets/text/sliders on natural render frames. commit=true delays local blur until that frame; require pendingNativeFrame=false before save, then reopen/read persisted values. Disabled terrain-generation settings retain native restrictions. Per-frame rebuilt layer-row IDs are read-only; edit using presets/dropdown, not stale rows. Sliders quantize natively. The adapter does not write the dimension model/NBT and has no hard dependency when absent.

## Sources and verification scope

Implementation references: MachineDataAdapter, AeDataAdapter, AeWorldAdapter, NEI view adapters and guarded workflows. Versioned source references include GT5U 5.09.54.205 MTEMultiBlockBase/ProcessingLogic, ServerUtilities CmdLoad/ClaimedChunks, AE2 QuantumCluster/TileQuantumBridge/EntitySingularity/WireLessToolHelper, PersonalSpace PortalTileEntity and EnderStorage native inventories. Source conclusions are not a new live test. AE 1080/AE2FC 1.5.113 documentation and older adapter baselines must be reconciled with current capability/coverage.

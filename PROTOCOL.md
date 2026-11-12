# BridgeNH (MCB) protocol v1

[English](PROTOCOL.md) | [简体中文](PROTOCOL_zh.md)

Target: Minecraft Java 1.7.10 with Forge / GT New Horizons.
The Forge client mod and external Python MCP server share this contract.
The Chinese companion explains this contract for human review; operation names,
fields, schemas and actual calls remain defined by this English reference and code.

## Transport

- HTTP bound to `127.0.0.1`, default port `25671` (configurable).
- `POST /v1/call`, `Content-Type: application/json`, `Authorization: Bearer <session token>`.
- Request: `{"requestId":"unique-id","operation":"client.snapshot","args":{}}`.
- Success: `{"requestId":"unique-id","ok":true,"data":{...}}`.
- Failure: `{"requestId":"unique-id","ok":false,"error":{"code":"...","message":"..."}}`.
- All reads and actions touching Minecraft execute on the relevant game thread.
- Bound request bodies, queues, result sizes, operation durations, and scan volumes.
- A queued request whose cancellation is acknowledged must never perform a later action.
- Preserve request IDs; do not retry mutations automatically.
- Never log authentication tokens or put them on stdout of the MCP server.

`POST /v1/cancel` uses the same authentication and a body containing only the
original `requestId`. Its standard response has `data.status` of `cancelled`,
`already_running`, or `not_found`. Cancellation and starting a queued operation
must be atomic with respect to each other. A bounded short-lived cancellation
tombstone prevents an in-flight call from starting after its cancellation arrived
first. An action that already started cannot be rolled back by cancellation.
The MCP client makes a short shielded best-effort cancellation request after
timeout/cancellation; without an acknowledgement it reports an uncertain action
outcome and never silently retries.

The mod writes `config/codexbridge/runtime.json` under its game directory with
`{"protocolVersion":1,"baseUrl":"http://127.0.0.1:25671","token":"..."}`.
The session token is randomly generated each client launch. Supervised chat MCP
starts unbound and requires explicit `mcb_connection` selection using the actual
chat identity, current generation and either `prismRoot`/`instanceName` or a listed
`clientId`. Profiles and discovered runtimes are inventory, not default bindings.
Status and `mcb_health.data.mcpConnection` report the actual selected client.

Legacy standalone in-process adapters retain `MCB_RUNTIME_FILE`,
`MCB_INSTANCE_PATH`, discovery-root variables and explicit `MCB_URL`/`MCB_TOKEN`.
Their legacy omitted/`auto` runtime discovery can choose the newest valid runtime;
this is not the supervised chat binding policy. Use an explicit selector for
standalone operations. These environment variables do not redirect a running
chat binding. Keep runtime files and credentials out of version control.

## Built-in feedback explanations

The MCP companion attaches optional top-level `feedback` to relevant tool
results. `source=mcb_builtin` identifies fixed shipped MCB interpretations,
independent of local knowledge/history and available even when the client or
local journal is unavailable. No extra tool/guide read or client RPC is needed.
At most four deduplicated `items` provide a stable `id`, fixed receipt path `at`,
`meaning` and `next`; `explanationsLimited` explicitly reports omitted advice.
These explain current receipt semantics, not a newly proven root cause.
`ok`, `isError`, original `error`, `data`, native images and acceptance evidence
remain unchanged. `automaticReplayAllowed=false` never changes permissions or
executes an action/fallback. Normal complete results acquire no generic advice.

Exact known codes cover stale context, capability/authority and binding failures,
takeover, waits, transport timeouts, invalid responses, unloaded coverage, reach,
flight and Waila partial text. Unknown codes receive an explicit unclassified
explanation, never a guessed cause. Explicit unknown effects take priority;
`outcome_unknown=false` alone does not mean an action never ran, and
`sideEffectsPossible=true` alone does not turn known effects into unknown effects. Only explicit
`original_action_executed=false` or acknowledged queued cancellation explains
nonexecution of that request, not rollback of earlier steps. Scheduled/submitted
receipts are distinguished from completion, and failed non-atomic workflows
preserve earlier effects. Paging/partial/unavailable observations and Waila's
preview-only NBT truncation retain their distinct evidence limits.

Interpretation inspects fixed receipt surfaces only, not tooltip text, item NBT,
world data, raw error messages or arbitrary nested objects. No screenshot,
network request, retry, generated script or persistent per-call plan is involved.

## Initial operations

### MCP-only bounded workflows

`mcb_action_recovery` is a worker-local recovery interface, not a new HTTP
mutation. `list` reads retained action IDs without game calls; `inspect` and
`reconcile` use existing guarded `baubles.inspect` and `world.inspect_many`
reads. Reconciliation requires the current session, original exact save-folder
argument and dimension, repeated matching full-NBT client/server inventory and
block evidence, and an explicit acknowledgement. Its receipt releases only the
selected returned creative placement's replay guard; the historical outcome
remains unknown and a new placement requires a separate request. Other unknown
actions remain protected. See the [scope and procedure](mcp-server/src/minecraft_codex_bridge/guides/maintenance.md#reconcile-a-returned-creative-placement).

`mcb_sequence` and `mcb_observe` execute existing HTTP operations in the Python
service; they do not require a separate workflow operation in the mod. They support one agent doing
discovery, planning and execution without generated scripts or subagents.

`mcb_sequence` requires `expectedSessionId` and 1..32 `steps`. Any action also
requires `expectedWorld`. Steps have unique ASCII IDs (at most 32 characters),
`kind` (`read`, `action`, `wait`, `assert`), and existing `tool`/`args`. An assert
uses `source` to identify an earlier result. Conditions contain `path`,
`operator` (`equals`, `not_equals`, `exists`, `contains`, `gt`, `gte`, `lt`, `lte`)
and a literal `value` or `valueFrom:{step,path}`. Numeric comparisons preserve
integer/decimal-string precision and reject booleans as numbers. A scalar
`bind:{windowId:{step:"gui",path:"windowId"}}` can resolve an argument from an
earlier full result, independent of its returned projection. Binding is limited
to advertised scalar arguments, never operation names or executable code.
An array reference can add `match:{"semantic":"structure_recheck"}` and
`field:"widgetId"`, or match actual item identity/count fields and return slot.
Matching uses at most eight dotted scalar JSON equality fields and a maximum
4096-entry observed array. Exactly one match is required; absent/ambiguous entries
block the action. Selection operates on retained raw observations, not projections.

The service prevalidates the entire plan before HTTP, pins runtime credentials,
discovers capabilities once, and establishes background protection at most once.
Reads/waits and completion boundaries check the selected world/session/profile;
every action additionally checks protection. These checks do not make observations
atomic across ticks. Waits repeat only whitelisted idempotent observations; no
action is retried. Lifecycle, screenshots, cursor-consuming scans, recursive
convenience workflows and explicit mode/profile switches are excluded.

`mcb_observe` accepts 1..8 `views:{id,tool,args?,fields?,omitEmptySlots?}` with
optional expected session/world. It never takes over or dispatches writes. Both
tools accept `timeout_ms` (default 10000, maximum 60000) and `max_return_bytes`
(16384..262144, default 65536). Plans are limited to 64 KiB and retained complete
results to 2 MiB. Metadata traversal has an 8192-node/10-ms cooperative limit.
Timeout cancellation cleanup may add up to two seconds; model planning and tool
dispatch latency are outside the execution budget.

Each observation has `values` keyed by the requested dotted path, `missingFields`,
`metadata`, `coverageSummary` and `emptySlotFiltering`. Only explicitly empty real
slots are filtered; original slot IDs are retained. Requested identities, NBT and
long quantities are kept intact or omitted as whole fields. Omission is explicit,
including metadata traversal/output limits. A non-null nextOffset alone does not
imply more pages; use hasMore or nextCursor. No automatic pagination occurs.
Repeated nested false partial/truncation/byte-limit flags are summarized as
`coverageSummary.falseFlagsSummarized`; root flags and true nested flags remain
explicit. Requested values still retain their original flags unchanged.

Reports retain every step's `completed`/`failed`/`not_executed` state, action
request IDs, `dispatchedActions`, `acknowledgedActions`, `outcomeUnknown`, failure
phase and elapsed time even when payloads are omitted. Completion confirms
acknowledgement, not resulting production: use fresh read/wait/assert checks of
real outputs. On first failure later ordinary steps are not run; no rollback occurs.
Optional `cleanup` declares 1..4 `{id,tool:"mcb_machine_work",args}` literal stops
with `working:false`, the same expectedWorld and an expected meta-tile ID. The
entire cleanup is prevalidated before HTTP. After a dispatched action, stops are
attempted on success or handled failure/timeout under the pinned runtime,
session/world/profile/protection guards. `cleanup_timeout_ms` is a separate
100..5000 ms budget (default 2000); transport cancellation may add up to 2 seconds
per budget. Cleanup cannot reacquire protection or bypass guards. External
cancellation does not guarantee cleanup. `cleanupStatus`, per-stop receipts,
executionElapsed_ms and cleanupElapsed_ms distinguish phases. No stop is retried;
an acknowledged stop needs a fresh allowedToWork/active observation.
Inspect actual state before recovery or replay.
Individual tools and script-driven tests remain supported.

| Operation | Arguments | Purpose |
| --- | --- | --- |
| `bridge.health` | `{}` | Protocol, mod/game versions, capabilities, session ID, active control profile |
| `client.snapshot` | `{}` | Player position/look/health, world/dimension, screen, crosshair target, focused/paused state |
| `client.window` | `{}` | Client/framebuffer dimensions and actual SDL/lwjgl3ify window mode, flags, identity and supported actions |
| `client.window_action` | `{expectedSessionId,expectedWindowId,expectedMode,action:"maximize"\|"restore"\|"windowed"\|"minimize"\|"resize",width?,height?}` | Guarded runtime native window request; no OS input, implicit fullscreen transition or configuration save |
| `gui.inspect` | `{waitFor?:{timeoutMillis?,conditions?}}` | Current screen, public buttons/container slots and supported widgets; optional guarded native synchronization wait |
| `gui.tooltip` | `{screenId,slot?,x?,y?,advanced?}` | Current client item/widget tooltip; exactly one real slot or a complete coordinate pair |
| `nei.inspect` | `{screenId,offset?,limit?,includeTooltips?,advanced?}` | Bounded current NEI handler/stack and supported GT recipe/display data |
| `nei.open` | `{screenId,mode,source,...}` | Native crafting/usage query for a precise registry/inventory/container/current-recipe stack |
| `nei.navigate` | `{screenId,action,index?}` | Native handler/page selection or back |
| `gui.widgets` | `{screenId,offset?,limit?}` | Stable live control IDs, current state/options and advertised named actions |
| `gui.widget_action` | `{screenId,widgetId,action,...}` | Native activate/edit/select/focus for a supported live control |
| `ae.inspect` | `{screenId,scope?,offset?,limit?}` | Current terminal/pattern/preview/CPU/interface synchronized data and actual resource identities |
| `ae.action` | `{screenId,action,...}` | Native named AE operations with typed live resource IDs and exact decimal quantities |
| `waila.inspect` | `{includeNbt?,advanced?}` | Current crosshair Waila cache/provider data with explicit target and freshness |
| `inventory.inspect` | `{}` | Player slots and selected hotbar slot |
| `world.inspect` | `{x,y,z,dimension?}` | Loaded block registry name/meta, tile class, bounded inventory/fluid/GT information |
| `world.scan` | `{x?,y?,z?,dimension?,radiusChunks?,verticalRadiusChunks?,radius?,limit?}` or `{cursor,limit?}` | One budgeted page of loaded non-air blocks, with coverage and explicit continuation |
| `world.scan_config` | `{radiusChunks?,verticalRadiusChunks?,cacheRadiusChunks?,cacheVerticalRadiusChunks?,cacheMaxMiB?}` | Read or change independent probe/cache radii and cache budget; startup defaults come from Forge config, runtime changes are session-only |
| `client.screenshot` | `{}` | Actual client framebuffer as base64 PNG, width/height |
| `player.look` | `{yaw,pitch}` | Change local player look through game state |
| `player.hotbar` | `{slot}` | Select hotbar slot 0 through 8 |
| `player.input` | `{keys:[...],ticks}` | Hold a supported movement/use/attack binding for a bounded number of client ticks; release automatically |
| `player.interact` | `{action:"use"|"attack",x?,y?,z?,side?,sneaking?,restoreView?}` | Normal interaction; explicit use keeps target view; local developer mode can restore the prior view |
| `gui.slot_click` | `{windowId,slot,button?,mode?}` | Normal container click with current window ID verification |
| `gui.close` | `{}` | Close current game screen normally |
| `gui.open_inventory` | `{}` | Open the player inventory through the normal client GUI |
| `gui.click` | `{screenId,x,y,button?}` | Click/release scaled GUI coordinates; reject stale screen identity |
| `gui.key` | `{screenId,keyCode,character?}` | Send a supported navigation key; reject stale screen identity |
| `chat.send` | `{message}` | Native player chat. Arbitrary game commands require mutable full policy plus actual game authority; local MCB controls remain recoverable. |
| `bridge.policy` | `{action?:"status"|"set"|"reload",scope?:"current"|"defaults",mode?,movement?,readOnly?,terrainChanges?}` | Shared local command/MCP policy endpoint. Only set accepts options. Current save/server choices persist locally; effective authority is always observed anew. |

Reese/Angelica settings advertise `sodiumSettingsControlsVersion=1`, use `screenKind:"settings"`
and expose the current rendered native frame tree through `gui.widgets` and
`gui.inspect.controls`. Old roots retained by Reese after resize are excluded. Page selectors
use `set_selected`; native sliders and scrollbars use `set_value`; search uses
`set_text`. Use the actual advertised ranges, steps and choices. Off-viewport
controls are disabled until scrolled into view. Rebuilt/removed controls reject
old IDs. `pendingValue` reads the edit buffer, `baselineValue` the option's edit
baseline, and `appliedValue` its actual binding or NotFine store, with an explicit
`appliedValueAvailable` flag. `settings.pendingCount` includes all native pages.
Apply/Undo are normal native button callbacks. A callback receipt does not prove
persistence after restart or a resulting measured frame rate.

`clientWindowControlsVersion=2` adds guarded minimize and resize (`width=256..8192`,
`height=144..8192`, required only for resize). Windowed resize, maximize and restore retain the current
foreground window and Z order on Windows through temporary native protection;
manual activation remains available after the operation. Minimizing an active
window lets Windows select another foreground window normally. Fullscreen transitions
remain explicit. `confirmed` reports native state/dimension readback; client and
framebuffer dimensions can follow on a later normal resize event. A false or
unknown receipt never authorizes replay. `foregroundPreserved` compares native
foreground handles across the operation; concurrent user focus changes can also
make it false. `window.startupMode` and `startupModeApplied` describe the early
startup policy; `focused` and `minimized` remain current state, not historical
proof of uninterrupted focus preservation.

Capabilities must describe which operations are actually implemented. Do not
advertise unsupported operations. The MCP server must surface a clear error for
an unsupported capability. `OperationPolicies` separates saved intent from actual authority. Modes are
`survival`, `survival_efficient`, `creative`, `full`; movement is `path` or `tp`.
Commands and MCP accept these English enum values only, without localized aliases.
The response contains `selected`, `effective`, `scopeKind`, `scopeConfigured` and
an ephemeral revision. Internal `profile` values remain backend context evidence,
not user-selectable policy. `bridge.profile` is unsupported. Every raw HTTP call
passes `GameBridge.prepareOperation` before dispatch; queued continuations reject
an invalidated policy revision. Read-only mutations and unclassified operations
fail before submission. TP requires normal command permission, even in creative.
The shared movement resolver is used by public composites; execution enums are
private native/workflow details. Existing remote execution limitations remain
explicit through the effective capability fields and routing errors.

`gui.inspect` includes a `screenId` that changes when the GUI instance changes,
plus scaled GUI `width` and `height`. GUI input and tooltip queries require that exact identity.
`gui.key` accepts the implementation's documented navigation-key whitelist,
not an arbitrary method name or Java expression.

`gui.inspect` also accepts bounded `waitFor:{timeoutMillis?,conditions?}` with its
developer AFTER guard. In multiplayer, the authenticated server must advertise
`serverBridge.server.guiNativeWaitVersion=1` and grant operator observations.
The existing client END-tick observer samples the actual server container at most
four times per second, within the same deadline. It binds connection, dimension,
client screen/window and an opaque token for the server container object. Canonical
SHA-256 covers complete ordinary slots, registered phantom items/fluids and full NBT;
oversized or incomplete evidence never counts as synchronized. The server snapshot
is the native last-announced ordinary slots, not later live inventory mutations.
At most one container-bound native resynchronization is sent; no input is replayed.
The same `guiWait` and `containerSynchronization` contract applies to local and
remote use. This capability requires server MCB, not QA.

Phantom item display NBT has one narrowly verified exception: the client may
reproduce the item's native durability-display initialization on isolated copies
of an absent/empty-NBT source. The projected stack must exactly equal the displayed
stack, and the source must exactly match the authoritative phantom configuration.
This never relaxes ordinary inventory, fluid, non-default durability or business
NBT comparisons. Such acceptance reports `fullPhantomSlotsEqual:false`,
`phantomConfigurationConfirmed:true`, `phantomDisplayInitializationVersion:1`,
`phantomDisplayInitializationVerified:true` and a bounded
`phantomDisplayInitializedCount`; it does not claim raw phantom NBT equality.
Remote verification requires versioned per-section and per-phantom digests;
older server evidence can only pass the existing exact-equality path.

`guiWait.observationReady` and `unmetConditions` distinguish synchronization
failure from unsatisfied declared predicates. Timeout errors preserve the wait
receipt and an actionable `reason`, and never authorize replaying the interaction.
Reuse `mcb_gui(waitFor={})` before treating an initially empty phantom widget as
confirmed empty; an unconfirmed display read is not server configuration evidence.

`bridge.health.interactionSneaking=true` advertises scoped sneak support for
`player.interact`. On a reachable block-use action, the optional boolean
`sneaking` sends normal look/sneak packets before use, then restores the previous
client/server sneak state. It does not simulate physical Shift, alter tooltip
modifier state, or directly place a multiblock. Old mods lacking the health flag
are refused by the MCP adapter before takeover. Omitting the argument preserves
the previous interaction behavior. Snapshot player data includes sneaking and
flying for recovery checks. A submitted interaction still requires world/GUI
confirmation, especially when using StructureLib construction triggers.

`bridge.health.interactionBlockTargeting=true` advertises explicit block-use
targeting. Supplying x/y/z requires a loaded, reachable, visible target face.
`bridge.health.interactionViewBinding=true` additionally advertises the view option.
The client aims and synchronizes its crosshair/look at the verified
hit before use and retains that view by default. Explicit use can set
`restoreView:true` in local developer mode to restore the prior look/crosshair
and send the previous look packet in `finally`. Player mode refuses that option.
Both modes retain normal reach and visible-face checks. This
explicit operation never follows a false block-use return with a generic
`sendUseItem`/air-use action. Its receipt includes `blockTarget` (x/y/z/side),
`airUseFallback=false`, actual `viewRestored`, and `endingView:{yaw,pitch}`. These are submission
details, not proof of world effects. MCP refuses explicit coordinate operations
on old mods lacking the relevant flags; sequence checks include bound coordinates.
Coordinate-free interaction retains its previous normal fallback behavior.
For hologram triggers, an unexpected configuration GUI indicates incorrect
interaction and requires diagnosis, not automatic retry.

`bridge.health.serverPauseGuard=true` advertises fail-fast `world_paused`
responses for server-thread operations while the local world is paused, without
queueing the operation. Client GUI/snapshot queries and closing the current GUI
remain available; confirm `snapshot.paused=false` before observing server state.

The original visible-button and ordinary-container-slot arrays remain. The
inspection adds bounded `widgets`, `widgetVisited`, `widgetTruncated`,
`widgetLimit`, `budget` and `coverage`. Coverage describes `vanilla`, `mui2`,
`aeVirtualSlots`, `richTooltips`, `text` and modifier policy. Supported public
MUI2 widget identities, geometric bounds, enabled state and cached text can be
reported; arbitrary private fields and complete drawn-label trees are not read.
The generic widget/tooltip reader does not expose the AE resource grid; the
dedicated `ae.inspect` operation does. Public AE button messages can be read.
The new paged control DTO is also included as `controls`; a missing widget or
text field is not proof that the GUI is empty.

`gui.tooltip` requires `screenId` and exactly one selector: an ordinary container
`slot` number, or both scaled `x` and `y`. Selectors are nonnegative 32-bit
integers and the mod checks actual screen bounds/slots. `advanced` is a boolean
defaulting to false. The actual current `ItemStack.getTooltip(player, advanced)`
is preferred, preserving the stack's NBT without dumping it. Supported public
AE button and cached MUI2 RichTooltip readers supplement it, without invoking
dynamic builders or reproducing rich icons, clipping or topmost hit routing.
The response includes `screenId`, `source`, optional `item`/`widget`, bounded
`tooltip`, `tooltipTotalLines`/`tooltipTruncated` when available, `advanced`,
current physical `modifiers` and explicit `coverage`/`budget`. Unavailable
providers return a reason or bounded provider error. `advanced` does not
simulate Shift/Ctrl/Alt. These reads do not move the mouse, alter hover, draw a
tooltip, request a screenshot or change game state.

`gui.click` explicitly rejects ModularUI2 and AE2 `AEBaseGui` screens: their
event routing and native/cached hover state are not reproduced by vanilla
callbacks. Ordinary real container slots remain accessible with
`gui.slot_click`; AE2 virtual ME entries are not vanilla slot indexes. Custom
widgets use `gui.widget_action` or `ae.action` through their dedicated supported
adapters for background use. Agents must not
silently fall back to system mouse input. Vanilla scrolling-list row selection
also requires native mouse events; `client.load_world` avoids that list entirely.
`gui.slot_click` rejects creative inventory screens and drag mode 5; creative
players may still use it in normal machine/chest containers.

Coordinate clicks scope the existing `GuiDraw.getMousePosition` hook to the
exact scaled callback point during native press/release. NEI recipe clicks also
reuse a 2-second screen/view-bound logical pointer lease for intervening natural
frames, because some native sliders commit their selection on a later update.
This NEI path requires background takeover, observed native pointer hooks and
neutral physical input. It never moves the OS cursor or forces draw/update calls.
`inputReceipt.coordinateDispatch.logicalMouseReads` records actual callback
coordinate reads separately from render-frame diagnostics; `naturalFramePointer`
records the lease. Neither proves parameter acceptance. Inspect the native recipe
after its normal update; custom handler sliders need not appear in `widgets` or
the BlockRenderer-only `preview` section. Never replay a click solely because an
immediate receipt reports no observed widget change.

Shared bounds: chunk scan radii 1..8 independently, legacy block radius 0..128,
scan result limit 1..512; input 1..200 client ticks;
PNG payload at most 8 MiB. Container mode 2 uses hotbar buttons 0..8; other
supported click modes accept buttons according to normal Minecraft semantics.

Only inspect already loaded chunks. No arbitrary Java evaluation, filesystem
access, unbounded NBT dumps, silent teleports, or direct save editing. Generic
GT adapter support is optional and must tolerate absent mods. Agents prioritize
current client GUI/inventory/item/tooltip data, then local exact-version source
only when those data are insufficient. Screenshots are temporary fallback
evidence only when necessary or explicitly permitted. Do not collect or persist
screenshots by default; retain structured/text conclusions where possible.
The bridge returns image content without writing PNG files and does not promise
to remove media retained by the Codex application or SDK conversation history.

## Native bookmark management

`nei.bookmarks` is a read-only, current-screen observation of namespace IDs, active
group/layout state, paged entry IDs and retention. `nei.bookmark_action` is a
controlled mutation requiring the matching `screenId` and `contextToken`.
State includes actual item/NBT, quantity, order, groups, visible rows and ownership;
stale observations are refused before editing. Boundaries are 64 namespaces,
4096 observed entries, 128 entries per read and 512 entries per deletion batch.

Actions cover item addition, explicit retention, item/recipe/group deletion,
task cleanup across namespaces, native row grouping, item/group ordering,
view/crafting/collapse modes, recipe collapse, native quantity deltas and namespace
selection/new-page use. A recipe bookmark is removed as one complete same-group,
same-namespace recipe, including associated item entries. Copies elsewhere remain.
Native NEI methods perform edits; no OS input, direct NEI file rewrite, real inventory
operation or pattern encoding is involved. Native save is requested and memory
effects are read back; disk durability needs separate acceptance.

`taskId` and `retention=temporary/permanent/future` classify actual entry objects.
Whole-recipe `nei.navigate(action=bookmark)` optionally accepts these fields and
registers only newly created entries. Cleanup preserves untracked/retained entries
and linked recipes containing them. Explicit deletion of retained entries requires
`releaseRetained=true` once they are no longer needed. Ownership lasts for the
current client/world/native object lifetime; a reset preserves unknown entries.
Partial failure reports unknown effects with no automatic replay. Read the current
state before recovery. The [NEI guide](mcp-server/src/minecraft_codex_bridge/guides/nei.md#native-bookmark-management)
describes the exact action selections and native grouping constraints.

## Current NEI recipe data

Observed bookmark-panel templates may legitimately have `count=0` when quantity
was not saved. They retain that raw count and full NBT in `nei.view`, while still
receiving a screen/view-bound `neiItemId` and the existing `ghostSource` reference.
`nei.item_action(action=take)` and semantic ghost consumers apply their requested
positive quantity only to an independent copy. Creative/native-NEI permissions,
inventory capacity, item limits and exact source revalidation still apply; the
submission receipt is not inventory acceptance. Zero-count ordinary item-panel
entries and owned inventory slots are not reclassified as valid templates.

`nei.inspect` requires the exact current `screenId` and a supported NEI
`GuiRecipe`. It reads public `getHandler`/`getRecipeIndices` and the handler's
actual `PositionedStack` inputs, outputs and other stacks; it does not open
recipe screens, click virtual stacks, enumerate a global registry or build a
recipe graph. `offset` is 0..100000, default 0; `limit` is 1..16, default 4.
`includeTooltips` and `advanced` are booleans defaulting to false. Optional
item tooltips use the actual stack's `getTooltip(player, advanced)` with its NBT
context intact, without dumping raw NBT or simulating modifier keys.

The response includes `screenId`, `handler {class,id,name}`, total handler
`recipeCount`, bounded `visibleRecipeIndices`, `visibleRecipeCount`,
`visibleIndicesTruncated`, `recipes`, `offset`, `nextOffset`, `hasMore`,
`includeTooltips`, `advanced`, current physical-key `modifiers`, `coverage`,
`limits`, `budget`, `byteLimited`
and `nonAtomic`. Pagination offsets select the current visible original-handler
reference list, not the entire handler recipe database. `recipeIndex` is already
an original handler reference and must not be mapped a second time.

The added `navigation` object reports handlers (index/id/name/recipeCount),
handlerCount/handlerIndex/pageIndex/pageCount and its own offset/nextOffset/
hasMore. Its handler pagination reuses the request's offset/limit but is distinct
from top-level visible-recipe pagination. `nei.open` replies include opened,
mode/source/queryItem/queryIdentity and navigation if a recipe GUI opened;
`nei.navigate` returns action/navigated and navigation where available (back
can return without it). A copied query preserves source identity, while NEI
may normalize its own copy for recipe matching.

Each recipe includes `pageOffset`, `recipeIndex`, `inputs`, `outputs`, `other`,
`positionsVisited` and explicit `positionsTruncated`/`softBudgetPartial` when
incomplete. The shared position/time/byte budget can expire before the output or
other-stack getter runs; empty arrays with incomplete coverage do not mean that
the recipe has no outputs or other stacks. Positioned
stacks provide `relx`, `rely`, `width`, `height`, `chance`, `acceptsLabel`, bounded
public `tooltip`, `tooltipTruncated`, current `item` identity or null,
`alternatives`, `alternativesTotal`, `alternativesTruncated` and `source`.
`chance` is the raw NEI value in 0.01% units (10000 means 100%), not a percentage.
Alternatives are actual stack identities with optional item tooltip data,
not guessed equivalent recipes. They are NEI virtual stacks rather than
container slot numbers and cannot be passed directly to `gui.slot_click`.

Limits are 16 recipes, 64 positions per recipe, 32 alternatives per position,
256 visible references and a 128 KiB response budget. The 4 ms soft budget
reports actual duration/overrun and partial coverage; a public getter cannot
be preempted. `coverage.extraDrawnText` describes drawing outside the supported
public adapters. Supported GT recipes also return `recipes[].gregTech`: raw
EU/t and duration ticks, item/fluid inputs and outputs, raw 10000-based chances,
metadata conditions and `displayOverclock`. The display calculation follows
the NEI calculator and is not a real machine's processing result. Check this
adapter's coverage and errors; other handlers' custom drawing can remain unavailable.
GT data include specialValue/flags/chanceDenominator (10000),
items/fluids inputs/outputs with entries (index/stack/chance), total/truncated,
specialItems/neiDescription/metadata (keyClass/identifier/valueClass/value),
metadataTotal/metadataTruncated, alternative inputs/oreDictionaryIds/recipeMap,
softBudgetPartial and byteLimited. Each GT recipe is bounded to 32 KiB.
Missing information is unknown, not zero or a complete-page guarantee. MCP
`mcb_nei` is a repeatable read-only query, never acquires takeover, requests a
screenshot or automatically enumerates further pages. Prefer these current
client data; consult local source only for data outside the reported coverage.

## Named NEI, AE and GUI operations

Named actions operate through supported native APIs, widget validation and
normal synchronized container packets. They never depend on physical mouse
hover, synthesize OS input or request screenshots. Every mutation participates
in MCP's existing automatic takeover and once-only request handling. Failed
takeover blocks the action; timeout or submitted status requires fresh
observation, never automatic repetition. Native server permissions and normal
validation still apply; no new feature authorization modes are introduced.

`nei.open` requires `mode` of `crafting` or `usage`, `source`, and the exact
current `screenId`; only this operation accepts an empty screenId when no GUI
exists. Sources accept only their own fields:

| Source | Fields |
| --- | --- |
| `registry` | Required registryName (1..256), metadata 0..32767 default 0; count-one registry template without actual-stack NBT |
| `inventory` | Required slot 0..255, resolved against current real inventory |
| `container` | Required slot 0..2147483647, current real slotNumber |
| `recipe` | Required original handler recipeIndex 0..100000; group inputs/outputs/other default inputs, position 0..63 default 0, alternative 0..31 default 0 |

Live sources use the actual `ItemStack.copy` and preserve identity/NBT. Do not
invent a target from a display name or slot index in another GUI. `nei.navigate`
requires a nonempty current screenId and `action` of handler/page/back.
Handler/page require index 0..100000 checked against the current native range;
back accepts no index. Read `gui.inspect` and `nei.inspect` after navigation.

`gui.widgets` requires screenId, offset 0..100000 default 0 and limit 1..128
default 64. Optional semantic structure_recheck/power_switch skips unrelated
slots and reads supported two-state buttons only. Pagination always uses original
tree positions; widgetId's numeric portion is not an offset. Empty filtered pages
can advance, and partial/hasMore expose insufficient coverage.
The same default DTO is included under `gui.inspect.controls`. Entries
provide stable widgetId, parentId/childIds, class/name/kind and x/y/width/height, available
text/value/selected/enabled/valid/focused, supportedActions and native ranges.
Supported public MUI2 tooltip and texture data identify GT `power_switch` and
`structure_recheck` semantics without machine names, fixed widget IDs or coordinates.
IDs bind the actual widget in its current screen, including duplicate native
button IDs; replaced/closed widgets are stale. Parents/children can be outside
the current page. Coverage, visited, nextOffset/hasMore, scanTruncated,
byteLimited, nonAtomic and budget describe a bounded observation. childIds caps
128 per widget with childCount/childIdsTruncated; parentId remains available
through the paginated tree.

`gui.widget_action` requires screenId/widgetId and exactly the named action's
fields: activate has no value; set_text requires text (0..1024) with commit
default true; set_value requires a finite number checked against native slider
ranges; set_selected requires a native state index 0..2147483647; focus requires
focused boolean. `recheck_structure` has no value and is accepted only on a
publicly identified two-state GT structure-recheck widget; pending state does
not get toggled off. It follows the normal MUI2 press/value/C2S synchronization,
not a direct machine-specific validator. Re-read components after the server check.
Only the live control's advertised actions are accepted.
Public MUI2 phantom inputs additionally advertise `ghost_drag` with exactly one
`source`: `{registryName,metadata?,count?}`, `{playerSlot,count?}`, or
`{fluidName,amount?}` for a phantom fluid slot. Item metadata/count default to
0/1; fluid amount defaults to 1000. A player-slot template preserves the actual
full NBT on a copy; no owned item or cursor is consumed. The fluid-name source
uses GT's public NEI display-stack factory with its amount NBT retained, and is
unavailable when that optional API is absent (use a real filled container source).
The native `handleDragAndDrop` sends its normal sync packet; filters, slot limits
and native same-type decrement/swap semantics apply. Read `ghostSlot` first and
re-read after sync, rather than replaying a successful drag. `submitted=false`
with `phantomAction.accepted=false` means the native widget refused the drag.
`phantom_click` requires button 0/1 for items or 0/1/2 for fluids and invokes the
normal press/release against the actual cursor; it also supports manually
configuring a supplied/given item or filled cell. Phantom item slots have no
ordinary container-slot number. Debug bus amounts/toggles remain normal GUI
text/choice controls. Debug templates are nonconsumable configuration, including
finite-mode virtual amounts; these observations cannot prove finite material
conservation. Both operations require a registered, initialized current-screen
phantom sync handler and report only local submission, not server acceptance.
Activate requires physical Shift/Ctrl/Alt and both mouse buttons to be released;
otherwise input_busy prevents native callbacks interpreting a different action.
The adapter does not change these physical inputs.
MUI2 ButtonWidget activation resolves the current native bounds, temporarily sets
logical GUI coordinates, and invokes the native non-rendering frame update and
screen press/release lifecycle. Native hit testing must select the exact bound
widget before the press; occlusion, changed layout, or an active drag refuses it.
ModularPanel owns accepted tap/release dispatch. Coordinates are restored, and a
screen-changing press never sends a release into the replacement screen.
`nativeCallbackReturns.dispatch=mui2_native_screen_press_release` reports screen
consumption, not server confirmation; inspect synchronized state after the action.
commit controls native MUI2 focus-removal validation/sync; AE text fields invoke
their native onTextChange immediately even with commit=false. Vanilla text is
applied locally for the owning GUI to consume.

`ae.inspect` requires screenId and scope auto/terminal/pattern/preview/cpu/
interfaces/cell_config/configuration/owner (default auto), offset 0..100000 default 0, limit 1..128 default 64.
Terminal entries are the actual filtered/sorted view independent of scrollbar;
pattern entries include full hidden-page/empty input/output storage slots;
preview exposes synchronized storage/pending/missing; CPU entries include
summaries and current job; interfaces use synchronized entries. Pending sync
is explicit, and none of these imply access to unloaded or inaccessible data.
state.virtualSlots describes at most 64 actual registered AE virtual slots
with slotIndex/hidden/x/y/phantom/storage/resourceId/decimal amount; consult
totalVirtualSlots/virtualSlotsTruncated. These are separate from real container
slotNumber values.

Resources expose a screen-bound canonical resourceId, type/name, decimal-text
amount/requestable, craftable, item icon and bounded normalized identityNbt /
identityTruncated. All large AE quantities, including CPU counts, are decimal
strings. Resource resolution retains the original actual AEStack with type/NBT;
never reconstruct it from a display icon, metadata alone or truncated NBT.

AE goal extension (`aeGoalWorkflowVersion=1`, owned integrated developer context):

- `configuration` reads synchronized priority/settings and exact placed owner;
  `owner` reads the same XYZ/dimension/optional mounted-part identity alone.
  `world.inspect(includeAe=true).ae.hosts[].configuration` is the authoritative
  readback, with `priority` when supported and the bounded native `settings` map.
- `ae.action` adds `priority_open`, `priority_set(priority=int32)` and
  `host_setting(setting,value,expectedValue)`. Host settings are the three binary
  enums BLOCK, STORAGE_FILTER and ADVANCED_BLOCKING_MODE. They send native GUI
  packets once; their submission receipts explicitly do not confirm server state.
- `player.place_block` optionally accepts `expectedPart` plus `attachmentBinding`
  and `partPlacement=adjacent|host`. Host mode binds the destination to the clicked
  host itself; the ordinary ItemBlock attachment contract remains adjacent-only.
  Native AE installation uses Minecraft's scoped-sneak mouse callback, including
  its ordinary Forge event path, then the existing authoritative inventory barriers.
- Internal `ae.order` accepts `action=start|status|take`, requestId and exact
  expectedSessionId/expectedWorld/dimension. Start additionally binds windowId,
  owner, canonical identityNbt, amount and selectedCpuSerial. Take binds the current
  windowId/owner. All actions run on the server owner queue. Start uses the normal
  ContainerCraftConfirm.startJob callback and requires current native CRAFT access
  and an idle CPU. Take requires the same grid/owner and native EXTRACT access,
  then uses PICKUP_SINGLE and Container.slotClick. No synthetic resource creation
  or inventory/NBT setter is used by the adapter.
- States are running, crafted, completed, canceled, start_unknown or take_unknown.
  The receipt is reserved before native mutation. Repeated start with the same
  exact declaration or repeated completed take returns the original receipt.
  Status observes the retained ICraftingLink, not a GUI-local CPU serial. Partial
  take never retries. Records are capped at 64 for the current bridge runtime.

The MCP goal tools are `mcb_ae_configure`, `mcb_ae_install`, `mcb_ae_order`; their
bounded schemas are in ae_task_schema.py. They are not executable caller plans
and are not recursively available inside sequence steps. See README for limits,
resume behavior and native delivery prerequisites.

All `ae.action` calls require current screenId and action; only its listed
fields are accepted. Amount is canonical positive decimal text in
1..9223372036854775807, not a JSON float:

| Action | Fields beyond screenId/action |
| --- | --- |
| terminal_search | text 0..1024; field resources/inputs/outputs/names default resources |
| terminal_setting | setting SORT_BY/SORT_DIRECTION/VIEW_MODE; value 1..64 exact advertised enum |
| terminal_type_filter | type 1..128 from current state.typeFilters and required enabled boolean |
| resource_take | resourceId; mode stack/single/half/inventory default stack |
| resource_deposit | mode all/single default all; current real cursor source |
| resource_container | resourceId and required mode fill_single/fill_all/drain_single/drain_all; native typed-resource container handling |
| craft_open | resourceId |
| craft_request | amount; mode native CraftingMode string 1..64 default STANDARD; lite/autoStart/follow booleans default false |
| pattern_set | side input/output and slot 0..1023 checked against full storage; exactly resourceId or playerSlot 0..35 plus amount, or clear=true without source/amount; clear=false is rejected |
| pattern_mode | field crafting/substitute/beSubstitute/inverted and boolean value |
| pattern_multiply | factor -8/-2/2/8; all-slot overflow/divisibility precheck |
| pattern_page | page 0..127 checked against current native geometry |
| pattern_encode | move/wholeStack booleans default false; wholeStack=true requires move=true |
| pattern_clear | no extra fields |
| preview_start | follow default false; native readiness checked |
| cpu_select | serial -1..2147483647; -1 native automatic selection where supported |
| job_cancel | expectedCpuSerial 0..2147483647 matching current selected busy CPU |
| job_pause | expectedCpuSerial and paused boolean; native toggle only when synchronized state differs |

Results expose submitted/action/screenId and available before/after/request/
target, not proof of asynchronous completion. Reinspect after ticks.
Exact RC1 extended pattern pages are native 0..1, narrower than the protocol
upper bound. Guarded job pause/cancel require GuiCraftingStatus's selected busy
network CPU serial; a fixed GuiCraftingCPU can expose job data but cannot supply
that serial table for these actions.
AE/control traversal is capped at 4096 records with 128 records per page,
128 KiB AE / 96 KiB controls output and 4 ms cooperative soft budget.
At least one record can make progress; public getters cannot be preempted.
Identity text is capped at 4096 characters, while native identity resolution
uses the actual retained object. Interface inventories cap 64 slots and 48 KiB
per entry, exposing total/truncation/slotsByteLimited. MCP never drains
subsequent pages automatically.

## Paged scan and session defaults

`world.scan_config {}` reads `radiusChunks`, `verticalRadiusChunks`, their
`radiusBlocks`/`verticalRadiusBlocks` equivalents, the independent
`cacheRadiusChunks`/`cacheVerticalRadiusChunks` and block equivalents,
`cacheMaxMiB`, `maxCacheRadiusChunks`, `maxCacheMiB`, `cachePolicy`, the nested
`cache` counters, `maxRadiusChunks`, `memoryOnly` and `revision`.
Probe defaults start at one chunk (16 blocks) of radius; the local loaded-chunk
cache starts at four chunks (64 blocks) of radius to favor repeated navigation
and structure reads. Probe radii may independently change to 1..8; cache radii
to 1..32 (512 blocks). `cacheMaxMiB` defaults to 2048 and accepts 64..16384.
This is conservative section/reference/entry retention accounting, not a
measurement or hard bound of the JVM heap or the native chunk object graph.
No memory is preallocated for the budget. An omitted setting is preserved;
all supplied values must validate before any setting changes. Changed
settings invalidate old scan cursors and clear the local cache. Startup values
are read from the Forge `scan` configuration category; runtime changes are
memory-only and do not write that file or acquire background input protection.
The lazy 16x16x16 section LRU only uses already loaded chunks within the actual
player-centered cache radii, with distinct world/dimension/section keys. Every
read checks live native block/metadata and chunk identity; different-dimension
reads without a matching player bypass retention. Exact native Block/BlockAir
geometry is reused for A* planning with current bounds verification; overridden
mod collision remains live. Entities, tile contents and final movement collision
proofs are never cached. World/chunk unload and settings/profile changes release
entries. Sections expire after five idle minutes, with at most 64 idle removals
per client tick. No full-volume prefetch runs. MCP `mcb_scan_config` has mutation annotations
because the same operation can set defaults; it is not a `wait_for` observation.
`bridge.health` reports the same object as `scanDefaults` and advertises the
current scan/cache budgets in `limits`, with live cache counters as `scanCache`.

An initial `world.scan` call may omit all coordinates to use the current player's
feet block. Explicit `x`, `y` and `z` must be supplied together, with x/z within
-30000000..29999999 and y within 0..255. Player profile uses its current client
dimension; developer profile uses a local non-LAN integrated-server dimension.
Scanning a different loaded dimension requires an explicit complete center.
Omitted chunk radii use the current session settings. Legacy `radius` supplies
the same horizontal and vertical block radius, 0..128, and cannot be combined
with either chunk-radius argument. Explicit small legacy-radius calls remain
compatible with older mods; new default/chunk/cursor behavior requires the new
implementation and is never silently emulated or retried. `limit` is 1..512,
defaulting to 128.

Each call observes one page and only already loaded chunks. It does not return
inventories or machine internals. `blocks` contains compact non-air identities:
coordinates, registry name, metadata and optional tile class. Response fields
include `source`, `dimension`, `center`, clipped `bounds`, `radiusBlocks`,
`verticalRadiusBlocks`, `requestedVolume`, `scannableVolume`, `outOfWorld`,
`complete`, `truncated`, `nextCursor`, `stopReason`, `nonAtomic` and `observation`.
`stopReason` is `complete`, `result_limit`, `byte_limit` or `tick_budget`.
Complete means traversal finished, not full visibility: `unloaded` can remain
nonzero. Scanning is not an atomic world snapshot; cursors retain traversal
metadata only. Pages never replay old block results: the local cache only reuses
live-verified identities/registry names and audited planning geometry. Unknown
generic chunk providers remain unknown rather than force-loaded.

`coverage` reports cumulative `examined`, `air`, `unloaded`, `nonAir`,
`remaining`, `fraction` and `order`. Traversal order is ascending chunkX,
chunkZ, sectionY, localX, localY, localZ within clipped bounds. `page` reports
the same per-page counts plus `startOffset`, `endOffsetExclusive`,
`cellVisits`, `sectionProbes` and `blockBytes`. The legacy top-level
`examined` and `unloaded` are per-page counts. `visitedSpans` identifies visited
chunk sections with clipped bounds, cell/traversal offset ranges and
`kind` of `unloaded`, `air` or `observed`. Positions in an observed span not
listed as non-air blocks were observed as air; unloaded spans and `remaining`
are unknown, never air. Returned block count is not scanned-volume coverage.
`timing` includes `elapsedNanos`, cumulative `scanNanos`, `ageMillis`,
`pageNumber`, `startWorldTick` and `endWorldTick`.

Each page and all scans sharing an owning game tick use a common budget of
4096 cell visits, 512 section probes and 2000000 ns. Whole known-air or unloaded
sections can cover many positions without visiting every cell, so covered
positions and cell visits differ. The compact `blocks` byte budget is 128 KiB;
it is not the entire response size because spans/metadata are separate. The
whole response still obeys the transport 1 MiB bound. Tick budget exhaustion can
return zero progress with a continuation. Do not immediately busy-loop that
cursor in the same tick, or automatically drain the maximum requested volume.
The 2 ms execution budget is cooperative: cold class initialization, provider
calls, setup and response encoding cannot be preempted and may overrun it.
Use actual timing fields and client measurements rather than treating the
configured budget as a hard call-duration or frame-time guarantee.

Continue using only the returned opaque `nextCursor` plus optional `limit`;
center, dimension and radii must not be repeated. At most eight scan states are
retained, expiring after five idle minutes. World/dimension/permission or default
setting changes invalidate their context. After successful HTTP response
delivery the old cursor is consumed and the next cursor becomes active; failed
delivery preserves a continuation's old traversal position for a fresh
observation, without replaying cached block data. First-page failed delivery
discards metadata because the caller has no continuation yet. An in-flight
cursor is `busy`; expired, consumed or context-invalid cursors return
`stale_cursor`. Running cancellation is not an atomic rollback. MCP never
retries a cursor or caches its state and marks `mcb_world_scan` read-only but
non-idempotent. It is excluded from `mcb_wait_for` polling.

## Development operations

| Operation | Arguments | Purpose |
| --- | --- | --- |
| `client.diagnostics` | `{}` | Runtime/heap, frame samples, focus/pause and bounded server tick-cost diagnostics |
| `input.bindings` | `{query?,offset?,limit?}` | Registered runtime vanilla/Forge key bindings and non-consuming held/physical state |
| `client.mods` | `{query?,offset?,limit?}` | Loaded mod identities and versions, sorted and paginated |
| `client.messages` | `{since?,query?,limit?}` | Bounded ring of visible received chat/command messages with sequence numbers |
| `registry.items` | `{query?,offset?,limit?}` | Search registered item identities and basic names |
| `registry.item` | `{registryName,metadata?}` | Exact item identity, stack limit and bounded tooltip |
| `registry.variants` | `{registryName,query?,offset?,limit?}` | Normal creative variants of one specified item, including GT machine metadata |
| `world.inspect_many` | `{positions:[{x,y,z}],dimension?}` | Up to 16 loaded blocks inspected in one game-thread dispatch; per-position results/errors |
| `world.tiles` | `{x,y,z,radius?,query?,offset?,limit?,dimension?}` | Developer search of nearby loaded tile entities and GT/TST identities |
| `world.structure` | `{x,y,z,dimension?,piece?,offsetA?,offsetB?,offsetC?,variant?,offset?,limit?,query?}` | Developer read of public StructureLib structure/chamber planning data and loaded observations |
| `machine.inspect` | `{x,y,z,dimension?,componentOffset?,componentLimit?}` | Owning-server GT/TST machine diagnostics, public components/ports and upgrades |
| `world.entities` | `{x?,y?,z?,dimension?,radiusChunks?,verticalRadiusChunks?,query?,offset?,limit?}` | Bounded existing loaded entities under the current profile |
| `world.spatial` | `{mode,dimension?,...}` | Loaded-world raycast or per-position occupancy/collision/support/liquid/light |
| `state.diff` | `{sections?,offset?,limit?}` or `{baselineId,offset?,limit?}` | Immutable structured baseline and explicit bounded comparison-node pages |
| `machine.set_working` | `{x,y,z,dimension?,expectedWorld,expectedMetaTileId,working}` | Developer setting of the normal GT machine permission to work |
| `dev.teleport` | `{x,y,z,dimension?,expectedWorld}` | Explicit developer teleport of the local creative server player within loaded chunks |

Mod/item/message pages return at most 128 entries. Queries are bounded plain
strings. `registry.variants` enumerates only one explicitly named item's normal
creative variants, not every item in the pack. Tooltips and third-party strings
are bounded; unsupported providers must return useful errors instead of hiding
them. Received messages use a 128-entry ring and sequence numbers scoped to the
game session.

`world.tiles` is developer-only, uses radius at most 64, and examines at most
8192 already loaded tile entries. Its result must expose candidate-limit and
page truncation so callers can distinguish an incomplete scan from no matches.
`world.inspect_many` follows the selected profile: client-visible block data
for `player`, authoritative server details for `developer`. Authenticated remote
operator servers advertising `guardedObservationVersion=1` support guarded
`machine.inspect`, `world.inspect` and `world.inspect_many` through the existing
bounded remote transport. The shared owner adapter preserves `includeAe` and
compact/noncompact semantics. Replies retain `source=remote_server_tick`, session,
connection, dimension and server tick, followed by a fresh client AFTER guard.
Combined local player-inventory/native-wait extensions remain separate capabilities;
unsupported remote extensions fail without entering an integrated-server queue.
Each batch point is limited to 48 KiB; an oversized point returns its own error
and recommends a separate `world.inspect`, without discarding other points.

`world.structure` requires developer profile in the current local integrated
world not opened to LAN, a loaded GT controller and supported public StructureLib
definition/alignment APIs. Optional `piece` is at most 128 characters; explicit
offsetA/B/C must be supplied together within -2048..2048. `variant` is 1..64,
default 1, used only as a planning trigger stack size without granting or consuming
items. Trust StructureLib's descriptions, candidates and chamber/offset/alignment
parameters when exposed, rather than recreating geometry or inferring upgrades from images.
Plans do not call structure validation, machine checks, hints or placement and
are not proof that the structure is formed.

No machine-name/ID tables or inferred controller anchors are used. Omitted,
null or empty `piece` selects `mode:"definition_discovery"`: bounded public
piece discovery, `definition` availability/source/reason, `pieces` and empty
`positions`. The `IStructureDefinition` contract does not enumerate names;
the existing public `StructureDefinition.getStructures()` map can do so when
the actual definition supports it. Missing enumeration is explicitly unavailable.
Piece names alone do not identify chambers, installed modules or active pieces.

A selected piece without offsetA/B/C selects `mode:"piece_geometry"`,
`coordinateSpace:"piece_origin"`, `offsets:null` and
`worldPositionsAvailable:false`. Positions have definition-local a/b/c only,
without world x/y/z, loaded/observed fields or world-dependent candidates.
The public definition does not retain an exposed controller anchor, so these
coordinates cannot be used for automatic placement or floor-height calculations.
Explicit known offsets enable aligned world positions and loaded observations.
Coverage states unavailable full hatch/count predicates, module layout,
controller offsets and dynamic/repeated/active-piece rules.

Located responses contain `controller` coordinates/dimension/metaClass, `piece`,
`variant`, `offsets {a,b,c}`, `alignment {direction,rotation,flip}`, `planSource`,
`query`, `positions`, `offset`, `nextOffset`, `hasMore`, `visited`,
`compiledEntries`, `byteLimited`, `nonAtomic`, `coverage`, `limits` and `budget`.
A located position has x/y/z and a/b/c, `elementClass`, public
`descriptionKeys`/translated `description`, bounded item `candidates`,
`candidateCoverage`, `loaded` and optional `observed {registryName,metadata}`.
Coverage distinguishes geometry, description, candidates, validation and
observations; unloaded observations remain unknown and public candidates are
suggestions rather than every predicate accepted by a machine.

`offset` is 0..100000, default 0; `limit` is 1..128, default 64; plain `query`
is at most 128 characters. Use the returned `nextOffset` with `hasMore` and the
same controller/world/alignment/piece/variant/query. Filtered or navigation-only
pages can return no positions while `nextOffset` advances: never add the output
count to invent a cursor. Nonzero offsets require an issued checkpoint for the
current plan; stale context or eviction returns `stale_cursor`. Offset 0 reuses
a matching plan and does not discard issued checkpoints. Checkpoints can be
reread until the plan is invalidated or evicted. At most four plans and 64
checkpoints per plan are retained, without caching current world observations.
The planner caps compiled entries at 100000, traversed entries at 2048 per page,
positions at 128 and candidates at 16 per position, with a 128 KiB result budget.
Its 4 ms soft budget allows at least one compiled-entry step; public getters
cannot be preempted. `budget.actualElapsedMicros` and `softBudgetExceeded` expose
an overrun rather than claiming a hard execution-time guarantee. MCP does not
automatically enumerate the entire plan.

Both developer mutations require a local integrated world not opened to LAN,
the current `expectedWorld` matching its world folder or name, and explicit
target arguments. Machine writes additionally verify the inspected runtime
meta-tile ID. An ID is a runtime identity guard, not a stable source-code ID.
The operation calls the machine's normal enable/disable API; it does not reset,
finish or bypass a recipe. Teleport requires a creative server player, stays in
the current dimension, and refuses unloaded destinations. It reports server
`before` and `after` positions for explicit restoration. These are server feet
coordinates; snapshot keeps legacy `player.y` and adds `feetY`/`eyeY` to remove
client eye-offset ambiguity. No save files or NBT are edited directly.
The work result describes the permission at the operation boundary. Subsequent
normal machine ticks can disable an incomplete structure or stopped machine;
callers must observe state rather than assume the permission persists.

Performance samples describe measured execution time and sample age. A paused
or stale server measurement must not be presented as current TPS.

## Loaded entities, space, bindings and state differences

`world.entities` uses the existing loadedEntityList under the current profile
(client player data or authoritative integrated-server developer data).
Optional dimension is a signed 32-bit integer. A complete x/y/z center uses
x/z within +/-30000000 and y 0..255; omitted center uses player feet.
Horizontal/vertical radiusChunks are independently 1..8 with session scan
defaults. Query is at most 128 characters, offset 0..100000 default 0,
limit 1..128 default 64. Responses report source/dimension/center/bounds,
entities, nextOffset/hasMore, coverage/limits/budget and nonAtomic. Available
entity fields include id/uuid/type/class/name, position/motion/rotation/bounds,
alive/health, item and owner. Missing optional fields are unknown. Traversal
is bounded and never force-loads a chunk or enumerates all pages automatically.
Offset is an index in the current raw loaded-candidate list, not matched-entry
count; ordering can change between pages. Bounds report radiusBlocks and
verticalRadiusBlocks. Actual positions and motion use x/y/z objects, yaw/pitch
are scalar fields, and entity bounds report min/max coordinates.

`world.spatial` requires mode `positions` or `raycast` and optional dimension.
Positions mode requires 1..128 integer x/y/z points using normal block bounds;
width 0.1..4 and height 0.1..8 default to the player dimensions. Offset is
0..127 default 0, limit 1..128 default 32. It reports loaded/occupiable/canStand,
collision boxes/support/liquid/light and explicit unknown/partial coverage.
Occupancy and canStand can be null when unloaded or entity coverage is partial;
they are not false/true defaults. Ray status is observed/unloaded/budget_partial,
hit may be null, and entityCoveragePartial is reported separately. Geometry
checks do not claim exhaustive mod-hazard detection.
It cannot accept ray fields. Raycast accepts either complete `from`/`to`
vectors together or distance 0.1..128 along the player's current view (default
reach); explicit vectors have x/z +/-30000000 and y -4096..4096, and the mod
checks their Euclidean distance within 0.1..128. includeEntities defaults true.
Raycast cannot accept position paging/size arguments. Hit type/face/vector/
distance/item/entity ID are returned where available. Unloaded ray intervals
remain unknown, rather than proof of a clear path. Neither mode loads chunks,
moves the player or promises pathfinding.

`input.bindings` reads registered runtime vanilla/Forge bindings with query
at most 128 characters, offset 0..100000 default 0 and limit 1..128 default 64.
It reports description/category/keyCode/keyName/held/physicalDown, modifiers
and coverage, without consuming isPressed or synthesizing input. Runtime
values take priority over a static guide or old options file. Named semantic
actions avoid physical-key/hover prerequisites for supported controls.
Binding entries also include index/displayName/bound; unavailable physicalDown
or modifier state can be null. Private shortcut registries and GUI shortcut
gates are outside this registered-binding view. Offset/nextOffset are registered
array positions rather than filtered result counts.

`state.diff` without baselineId creates an immutable baseline of selected
sections: player/inventory/gui/messages, 1..4 unique sections, default the
first three. It returns baseline metadata and empty changes without repeating
the full snapshot. With baselineId (1..128), sections cannot be respecified.
Offset 0..100000 default 0 and limit 1..128 default 64 apply to explicit pages.
Offset/nextOffset refer to baseline comparison nodes, not the number of changed
entries: unchanged or budget-only pages can have empty changes yet advance.
Every page observes current state freshly against the same immutable baseline;
it does not consume/advance it or cache current observations. Read nextOffset
and hasMore explicitly, never infer progress from changes length.

Responses include baselineId/created/sections/baselineBytes/baselineNodeCount,
offset/nextOffset/hasMore, changes (path/kind/beforePresent/afterPresent/before/
after and optional valueTruncated), nonAtomic, coverage and budget. A single
change value above 8 KiB becomes a typed byte-count summary with
valueTruncated=true. Baselines cap at four, 256 KiB and 16384 nodes each, expire
after five idle minutes, and invalidate on world/profile context changes. Comparison
pages cap at 512 nodes, 128 changes, 128 KiB output and a 4 ms soft budget.
Session/baselineTick/currentTick/nodesCompared/byteLimited distinguish context,
age and progress. captureNanos measures source-state collection separately;
the comparison's budget does not bound game-data getter execution.
MCP marks this read-only tool non-idempotent because its first call stores a
baseline; it is excluded from wait_for. No image data is stored, no full-page
loop is automatic, and no game file or inventory is modified.

`machine.inspect` uses the existing local integrated developer context (not
opened to LAN), loaded target coordinates with optional dimension,
componentOffset 0..100000 default 0 and componentLimit 1..128 default 32 on the
owning server thread. It returns current public machine diagnostics,
components/ports and available upgrades with exact live identities, long
quantities and bounded coverage/paging. It does not call structure validators,
reset recipes or mutate inventories to obtain a result.
Results include state/upgrades/shutdownReason/checkRecipeResult/modes/processing
and components (roles/entries/total/offset/nextOffset/hasMore/softBudgetPartial).
`softBudgetPartial` is true only when the time budget stopped enumeration before
the next entry; an empty directory or completed final page remains complete even
if earlier getters exceeded the budget. The separate root budget reports elapsed time.
`components.directoryFingerprint` hashes the live world/controller object identity
hashes and ordered role/count/registered-object identity hashes (at most 100000
registrations). It detects directory changes between pages without retaining world
references or calling extra component getters; it is not an atomic state snapshot.
Site acceptance follows `nextOffset` only for incomplete validated pages, using
the same guarded owner, session/connection, directory fingerprint and role summary.
It shares the original site deadline and permits at most 32 continuation reads.
Changed directories, missing fingerprints on partial legacy responses, malformed
pages and exhausted limits remain unknown. Complete single-page reads need no
extra call, and no action or whole-site replay is performed.
Entries report role/roleIndex/index, position/tier/loaded/valid and reason;
registered lists can include invalid or unloaded components.
The module directory reads public `IModularizedMachine.getAllModularHatches`
when implemented. `components.modularHatches` states API availability and, only
on success, `registeredCount`. The `modular_hatch` role shares normal pagination;
entries expose `IModularHatch.getType` as `moduleType` and public `IExecutionCore`
state (`idle`, `working`, `usesMainMachinePower`, `eut`,
`needProgressingTimeTicks`) plus available `mainMachine` identity/position.
Core inspection also exposes these fields at the root. A controller component
query compares the linked object via `controllerIdentityMatches`; standalone
core queries omit that comparison. Core EU/t and progress retain getter semantics,
without inferred recipe/controller meaning. This is the current registered
collection, including idle/invalid entries, not an exhaustive physical scan,
chamber layout, count predicate or active-piece plan. Missing APIs/getter failures
are explicit unavailable results; no working/boost filter or machine-specific
fallback is used. The processing
signedCalculatedEUt is already calculated, negative for consumption and positive
for generation; actualEnergyUsagePerTick is a consumption getter while negative,
not raw recipe EU/t. lastMatchedRecipe is a matching cache, and
lastProcessingCalculation can persist after completion, neither proves an
active recipe. TST base modifiers and GT base upgrade flags have explicit
machineSpecificCoverage; arbitrary machine upgrade internals are unknown.
The machine response is bounded to 128 KiB.
Public ME output Environment.getProvider cache totals are exposed as
`outputStorage.cachedAmount/cacheCapacity`, canonical decimal long strings.
They exclude the inserted cell and connected network and carry no item identity;
no cache enumeration or flush is performed. Missing/failing APIs are explicit.
Use finite real inputs and target output deltas to verify one production; neither
recipesDone nor cached recipe results alone are completion proof.

`player.flight` accepts `action:set`, `flying` and `expectedWorld` in a local
creative developer world with flight allowed and no GUI. It uses normal abilities
packet synchronization. Grounded flight enabling is refused: first perform a
short normal jump. Snapshot `player.serverFlight` is a server-END-tick sample,
with available/flying/allowFlying/creative/onGround/dimension/worldTick/ageMillis;
context mismatch or age above 1000 ms reports unavailable instead of old data.
Wait for the requested server flight before elevated teleport. `dev.teleport`
accepts optional `requireFlying:true` to refuse it if server flight is absent.
These controls do not remove normal interaction distance/face rules.

`waila.inspect` reads only the current crosshair/client cached target and
provider state. includeNbt and advanced default false; optional NBT is bounded.
cacheTarget/matchesVanillaCrosshair/nbtCoordinatesMatch identify the cache;
cacheTimerMillis/cacheTimerAgeMillis refer to Waila's request-throttle timer,
not NBT payload age. cacheTimerMeaning makes that distinction explicit, and
nbtPayloadReceivedAt is unavailable. nbtSnapshotTruncated reports only external
preview truncation. Provider input uses a separate complete, isolated native
copy after preflight (16384 nodes, depth32, estimated1MiB); it is never silently
trimmed and these are local-copy limits, not network packet limits.
providerNbt reports availability/completeness/reason and budget usage.
Provider reports contain class/callback/available/error and bounded errorDetails
(underlying exception types, BridgeException codes and frames, without raw provider messages);
sections body/head/tail contain bounded tooltip text and providerBudgetPartial.
Optional NBT is bounded SNBT (4096 characters, 256 nodes, depth 6, arrays 32).
Providers cap at 64 and total output at 128 KiB. These cached observations are
not a current authoritative machine query. It does not aim the player, draw overlays
or turn a cached target into an arbitrary-block API. Provider errors and missing
data remain explicit.

complete/partial/status and completeness summarize failed callbacks, callback
line truncation and provider-budget coverage. MCP returns WAILA_PARTIAL with
ok=false/isError=true while retaining data if text is incomplete, including
explicit coverage failures from older bridges. Preview truncation alone does
not fail otherwise complete provider text. Completeness covers registered text,
not overlay pixel layout, exact cache freshness or authoritative machine state.

client.diagnostics includes read-only displayState: client, native LWJGL,
GUI and framebuffer/texture sizes, fullscreen states and GUI-scale preference.
client.screenshot captures a fresh RenderTick.END frame and preserves source,
capturedAtMillis, cached=false, original/texture sizes, scaled and displayState
through MCP. Encoded image width/height can be scaled; they do not establish an
OS window resize. Neither read activates/resizes the window, changes fullscreen
or writes preferences. External Windows capture is outside this frame source.

## MCP-side conveniences

Expose readable MCP tools for the operations above. `wait_for` runs bounded
polling against structured state and returns the last observation on timeout;
conditions may use a dotted JSON path with `equals`, `contains`, or `exists`.
Only repeatable reads are accepted; scan cursors, state.diff and scan configuration are
excluded. Do not poll forever or silently repeat action calls. The MCP screenshot tool
returns image content. Tool failures use MCP `isError` with useful details.

`mcb_logs` tails only the fixed `logs/latest.log` adjacent to the configured
game runtime file. It accepts a severity, plain query and line limit, not an
arbitrary path. Read bytes and returned text are bounded; authorization,
tokens, passwords and launcher authentication lines are redacted.

`mcb_capture` collects named diagnostics, snapshot, GUI, inventory and optional
block observations. `include_screenshot` defaults to false; at most one temporary
screenshot is requested only when explicitly selected for necessary or permitted
visual evidence. It records begin/end, session/profile
and world/player origins, and reports per-observation errors. This collection
is not atomic across client/server/render threads. JSON observations and the
PNG keep separate explicit size limits.

`mcb_machine_watch` samples only a read-only block query for at most 30 seconds
and 64 samples. It returns selected bounded dotted-path fields, elapsed times,
partial results and truncation/errors. It never polls a mutation or retries a
failed action. Default fields use the `gregtech` object from `world.inspect`.
Its signedCalculatedEUt is the current machine calculation from the public
getEUtForDamageCalc getter: negative consumes, positive generates, with
eutMeaning explaining this distinction. It is not the original NEI recipe
rawEUt. The watch default selects gregtech.signedCalculatedEUt.

`mcb_help` reads bundled GTNH operation guides with a fixed `topic` enum:
`overview`, `operations`, `nei`, or `ae`. It needs no running game or authentication token and
returns at most 32 KiB of structured guide data. The same guides are exposed as
fixed MCP resource URIs `minecraft-codex-bridge://agent-guides/{topic}`.
Default and current RC1 bindings, source versions and verification limits must
remain explicit. GUI/inventory tool descriptions point callers to these guides
before operating GTNH's NEI and AE2 interfaces.

`mcb_operation_check` is a fixed read-only workflow shortcut. `kind=machine`
reads generic public GT state; `ae` binds the current GUI screen to one AE page;
`ae_buffers` explicitly reads one loaded owner's AE details; `nei` binds the
current screen and summary context token to one visible-recipe page. Every call
requires `expectedSessionId`; machine/ae_buffers also require `expectedWorld`
and `target:{x,y,z,dimension}` with existing local developer permissions.
Only ae accepts `scope` (the existing ae.inspect enum). No write, takeover,
pagination, retry, screen opening or machine-specific rule is introduced.
The default total timeout is 10s, maximum 30s. Existing workflow projection,
coverage, omitted fields and session guards are retained in a 56 KiB workflow
budget plus bounded advice (data under 64 KiB). `status=completed` means reads
completed; `completionScope=read_only_observations` and `productionVerified=false`
prevent interpreting it as production acceptance. Failure preserves completed
observations and stops later steps. Versioned guidance distinguishes GT normal
disable/fault stop/consumed-input failures, AE logistics and NEI lookup. It does
not replace existing recipe/QA acceptance or declare a root cause from UI text.

## Client lifecycle and background control

| Mod operation / MCP tool | Arguments | Behavior |
| --- | --- | --- |
| `client.worlds` / `mcb_worlds` | `{query?,offset?,limit?}` | Read-only existing save list, returning `folder`, `name`, `requiresConversion` |
| `client.load_world` / `mcb_load_world` | `{folder,expectedSessionId}` | Load one exact existing save from a normal supported main menu |
| `client.leave_world` / `mcb_leave_world` | `{expectedSessionId,expectedWorld}` | Normal local integrated-world save and return to main menu |
| `client.exit` / `mcb_exit` | `{expectedSessionId,expectedWorld?}` | Normal client shutdown; loaded worlds require the world guard |
| `client.background` / `mcb_background` | `{enabled}` | Install/restore temporary background input protection |
| `client.mouse` / `mcb_mouse` | `{enabled}` | `true` restores manual mouse; `false` installs passive mouse protection independently |
| `client.unfocused` / `mcb_unfocused` | `{enabled}` | `true` temporarily disables lost-focus pausing; `false` restores its previous setting independently |
| MCP-only `mcb_launch` | `{windowMode?:"background"\|"minimized"\|"normal"}` or `{action:"launch",windowMode?}`; `{action:"status",timeout_ms:1000..60000}` | Launch once; status is a bounded read-only current-health/process continuation with no dispatch |

`expectedSessionId` must match the current health/snapshot session. Save folders
must be single existing list entries, not paths; creation and old-save conversion
are refused. Loading and unloaded-client exit accept vanilla main menu or the
known CustomMainMenu `GuiCustom` with public `guiConfig.name == "mainmenu"`.
Loaded-world lifecycle actions require a local integrated world not opened to
LAN and `expectedWorld` matching its folder or name.

Load/leave/exit return a scheduled ticket before the action. The HTTP response
must be written and closed before the job can run; failed delivery cancels the
job before execution. `snapshot.lifecycle` exposes the current ticket and
`idle/awaiting_response/scheduled/running/awaiting_world/completed/failed/cancelled`
states. Client world/server/screen identity is rechecked before execution.
Loading/saving may block the client thread, so observations can temporarily time
out. A running action is not reversible by a request cancellation; observe its
ticket and do not repeat the action while its outcome remains unresolved. Exit
completion is checked by loss of that client session and process shutdown, rather
than an impossible post-exit snapshot.

Startup preserves the existing mouse helper and pause setting. The public
MouseHelper extension, enabled only on takeover, suppresses mouse grabs,
cursor warps and physical movement deltas; the lost-focus pause setting is
temporarily false. Existing native grab is refused because this RC1's release
implementation itself warps the cursor. No native keyboard/mouse input or
window activation is performed by these game operations. Disabling background
or controlled exit restores the original helper and pause value; a later
third-party helper replacement is preserved rather than overwritten. The bridge
never calls `saveOptions`, although other game code can persist the temporary
pause value while it is enabled. This protection starts on takeover and does
not guarantee that OS window creation or unrelated third-party native calls
cannot change focus.

The two controls are independent. `background.enabled` means complete
protection; `mouseEnabled`, `unfocusedEnabled`, `helperInstalled` and the actual
`pauseOnLostFocus` describe each part. `client.background` remains the combined
switch. Lifecycle actions require complete protection and otherwise return
`background_required`; they do not implicitly change modes. Disabling either
part while a lifecycle ticket is pending returns `busy`.

MCP validates tool arguments, checks current health/capabilities, and ensures
complete protection before player/GUI actions, chat, machine writes, teleport
and load/leave/exit. If necessary it calls `client.background {enabled:true}`;
failure prevents sending the original action. Reads, profile selection, launch
and explicit mode switches do not acquire control. No ownership state is cached
across sessions. Restoring manual input is honored immediately, but a subsequent
MCP control action will acquire protection again.

Forge's public `ClientCommandHandler` registers `/mcb` with `/codexbridge` as
alias. `agent on|off`, `mouse on|off`, `unfocused on|off`, `background on|off`, `takeover`,
`release` and `status` call the same control logic locally, without sending the
command to a server or requiring developer/operator permission. Bridge chat
also recognizes only these exact local roots; all other slash commands retain
the developer/local-world guard.

`/mcb agent on|off` is a combined background-mode shortcut, without changing
the access profile. `/agent on|off` is also registered as a short form unless
another mod already owns that client command. Existing commands are preserved;
bridge chat checks the exact root and our actual registration before local
dispatch, never falling through to server chat on conflict.

`/mcb scan N [V]` sets the session probe horizontal/vertical chunk radii to N/V
in 1..8; `/mcb cache N [V]` sets independent local cache radii in 1..32.
Omitting V uses N for both. `/mcb cache memory N` sets the retention budget in
MiB (64..16384). `/mcb scan status` or `/mcb cache status` reads both groups.
The `/codexbridge` alias supports the same commands. These commands use the
same memory-only settings, clear old cursors/cache on change and preserve access
profile and input mode.

The launcher currently supports a configured portable Prism installation on
Windows with verified `portable.txt`, `prismlauncher.cfg`, `InstanceDir`,
`instance.cfg` and a Minecraft 1.7.10 `mmc-pack.json`. Paths come from
`MCB_PRISM_EXE`/`MCB_INSTANCE_PATH` or the standard Prism `MCB_RUNTIME_FILE`
layout, not caller-supplied commands. The runtime file need not exist yet.
Normal GUI processes are distinguished from help/version/launch CLI helpers;
the current data directory is preserved to reuse the existing account session.
Hidden auxiliary processes, a named mutex and target-Java detection prevent
duplicate dispatch. The result is only `scheduled`, not Minecraft readiness.
It does not automate login and never retries uncertain launch outcomes.

`windowMode` defaults to `minimized`: start minimized to the taskbar without activation.
`background` displays the game without activation; `normal` uses normal window behavior.
All modes retain taskbar eligibility. `window.taskbarButtonRequested` reports the actual
Win32 `WS_EX_APPWINDOW` flag, not a taskbar UI observation. The launcher
atomically writes `config/codexbridge/window-startup.txt` in the selected game
directory after duplicate-client checks and before dispatch. This preference also
applies to later manual Prism starts; a new MCP launch without a mode selects
minimized again. Status never changes it. The client requires the early SDL/RFB
window hook (window-controls v2); an older installed JAR does not apply this policy.
`windowModeRequested` and `windowModeConfirmed=false` distinguish launch intent from
native verification. Read `mcb_window` after the bridge becomes ready. The mode
controls the game window only; Prism prelaunch dialogs can still take keyboard
focus. A window remaining below another window does not establish uninterrupted
typing. Track this launcher boundary in [NATIVE_CONTROL_GAPS.md](docs/NATIVE_CONTROL_GAPS.md).

After an uncertain dispatch, its error includes a read-only `continuation` to
`mcb_launch(action=status)`. This also works from a fresh MCP connection without
runtime.json. A status window samples current native health or the existing
fixed-instance process diagnostic, at least one second apart. It returns
`readOnly=true`, `launchDispatched=false`, `startup`, `windowExpired` and actual
`readiness` when available. A missing Java sample is `java_not_observed`, never
proof that a delayed dispatch failed. Query failures remain unknown, and no
result from this continuation permits automatic launch replay.

Tests must distinguish mocked transport/protocol tests from actual Minecraft
runtime tests and Codex tool registration tests.

## Instance-local agent runtime journal

The MCP Adapter records bounded typed call receipts automatically under the
pinned game's `.minecraft/agent-runtime-logs/normal/`. This includes existing
`call_tool.py --transport in-process` calls; raw direct HTTP requests and private
agent reasoning are outside this recorder. No extra game RPC is sent for logging.
Receipts contain UTC time, tool, safe method/facts, session digest, environment,
bounded operation names and adapter elapsed milliseconds. Health receipts retain
the executing bridge fingerprint when available. Raw arguments/responses,
commands/chat, screenshots, inventory/NBT and credentials are omitted.

`mcb_run_log` runs locally without takeover, developer profile or game calls:

- `status` (default): paths, limits, issue counts and triage counts separating
  `openUnreviewed`, `openReviewed`, `deferred` and `resolved`. Open candidates
  are not confirmed current bugs. Also returned automatically
  in health's top-level `agentRunLog`; ordinary receipts return an event ID.
- `read`: `category=normal` returns up to 100 newest receipts, scanning at most
  1 MiB. Optional `tool` and exclusive UTC `before` filter the tail; millisecond
  timestamps are not unique cursors. Error/optimization reads support `id`,
  `state`, `offset`, `limit` and `reviewStatus=unreviewed|reviewed`, with explicit
  non-atomic paging and `hasMore`. Issue reads omit detailed operation traces
  by default; `includeDetails:true` restores the complete retained receipt.
- `report`: enrich an existing `id`, or create a finding using `category=error`
  or `optimization`, `title` and `observed`; optional `expected`, `reproduction`,
  `suggestion` and related `eventId`. Text is bounded and redacted. It is an
  agent-reported observation/hypothesis, never a proved root cause or acceptance.
- `resolve`: exact issue `id` and `resolution`, optional
  `resolutionKind=fixed|expected|superseded`, and up to 32 distinct exact
  `relatedIds`. All IDs must exist; one transaction closes them while retaining
  original notes, occurrences and receipts. New recurrence reopens the automatic
  issue and its directly linked resolved finding, never silently replaying work.
- `defer`: exact issue `id`, `reason` and `resumeWhen` record explicit postponement.
  Recurrence updates evidence/counts but leaves it deferred. `report` with that
  ID reopens it; no scheduler or automatic resumption is created.
- `configure`: hot-set `enabled`, `normalMaxMiB` (4..1024, default64),
  `normalMaxDays` (1..365, default30), `slowGoalMs` (100..3600000, default10000).
  Disabling stops automatic receipts; reports/reads/exports remain available.
- `export`: optional issue `id`/category/state; writes a local ZIP with
  `report.md` and `report.json`, at most 100 selected issues and 100 recent
  receipts. Partial flags are explicit. No network upload or arbitrary paths.

Normal segments rotate at 4 MiB. Under a cross-process nonblocking lock, old
owned segments are deleted by age and total-byte budget, including on status or
configuration. Errors, uncertain outcomes and failed workflows become error
issues; partial results and goals over the configured adapter-time threshold
are optimization candidates, not inferred bugs. Repeated signatures merge.
Confirmed native AE running orders with resumable timeout receipts and successful
read-only pages with an advancing next offset stay in normal history. Failures,
unknown outcomes, incomplete actions and unconfirmed waits are not suppressed.
Existing historical records are not automatically reclassified or closed.
Exports always include retained detailed receipts regardless of compact reads.
Agent planning, approval and recipe-wait interpretation remain separate from
this automatic duration measurement.

Issues are in a separate SQLite store (256 MiB hard cap). Unresolved summaries
are never evicted by normal-log cleanup; full/busy/unavailable storage is
explicitly reported without replacing the game result or retrying actions.
Exports rotate separately at 64 MiB. Journal I/O has a bounded best-effort wait,
no unbounded worker queue, and no screenshot cache. The tool validates fixed
paths under the pinned runtime; without a live runtime, an explicit binding to
the same `.minecraft/config/codexbridge/runtime.json` permits local reporting.
The journal is distinct from `mcb_knowledge`'s shared local experience cache.

## MCB operating-knowledge distillation

The shared local experience store also provides an MCB-specific knowledge layer,
independent of any external development knowledge base. It is delivered by the MCP companion;
no Java/JAR update or Minecraft restart is required for this layer.

Health and relevant call results automatically include
`localKnowledge.operatingLessons`: at most two current-binding distilled or
agent-reported lessons plus matching shipped guidance.
Duplicate raw history hints are omitted when distilled advice is present;
explicit `query` still exposes those receipts. No extra game RPC,
mandatory pre-operation query, plan file, screenshot or external model is used.
The deterministic distiller reads at most 256 recent aggregate receipts per
selected binding/tool and exposes `scanLimited`/`hasMore`; it does not load or
scan the complete history on every call. Existing receipts remain the evidence
store; automatic lesson views are derived rather than duplicated in another log.

Automatic observations group the same binding/tool/method and failure signal,
uncertain outcome or exact acceptance scope. At least two occurrences form a
pattern; three occurrences across two client sessions corroborate repetition.
The method is a fixed allowlisted projection, not all original arguments or
target state; current mode, upgrades, resources and geometry still need checks.
Neither threshold proves a cause or a recovery. Scheduled/returned/planned-only
receipts never become accepted-method lessons. Mixed accepted/failed outcomes
are context-sensitive and cannot qualify for promotion. Timings describe adapter
observations only, not causality, CPU cost, model/approval time or a fastest-method
ranking. GUI/arrival acceptance never becomes production acceptance.

Additional `mcb_knowledge` actions (all local, no takeover):

- `lessons`: optional `tool`, exact lesson `id` and `limit` 1..5. Historical
  advice requires the Adapter's currently observed compatible context and runtime
  session; fixed shipped guidance remains available without a live client.
- `learn`: `tool`, `kind=procedure|recovery|pitfall`, `title`, `when`,
  `instruction`, `verification`, and `evidenceIds` (1..8 distinct existing,
  unexpired, same-tool/current-binding receipt IDs). Optional `counterexampleIds`
  has the same bounds and must not overlap supporting IDs. Add meaningful reusable
  lessons once at task completion, not after every click. Repeating the same
  title/conditions/instruction/verification merges source IDs, at most eight per
  side; it does not create an unbounded stream of copies.
- `review`: same selectors as `lessons`, works offline across historical
  bindings. `appliesToCurrentBinding` is explicit; an old adapter/source binding
  never silently becomes current. This is the fixed-content promotion review.
- `retire`: exact lesson `id` disables suggestions but preserves the note.
  Exact `forget` can delete a learned lesson or an experience and its traces;
  deleting source experience invalidates dependent lesson evidence.

Agent-authored text is bounded/redacted using the current credential, and remains
`origin=agent_reported`, `interpretationVerified=false` even when its receipts
contain repeated acceptance. Missing/expired sources require revalidation;
counterexamples mark a dispute. Neither is suggested automatically. An exact-ID
inspection or offline review can still show these states and retired notes.
At most 2,000 authored lessons share the existing 256 MiB SQLite limit; retired
notes can be forgotten to free that count. The existing version-1 receipt schema
is extended additively, retaining old experiences. Source expiration remains 180
days; a retained note with expired evidence does not stay endorsed.

`promotionCandidate` flags repeated observations for engineering review. This API
does not generate Java/Python, change policy, replay mutations or grant permissions.
Shipped guidance is selected by tool and current outcome, covering client-owned
interaction approaches, verified NEI ghosts/cursor release and uncertain effects.

## Passive player recording

`playerActivityRecordingVersion=1` advertises native `client.recording`, exposed by
the read-only `mcb_recording` tool's default `view=live`. `limit` is 1..128,
`since` is a nonnegative integer within the latest recording's sequence range.
A supplied stale recording identity is refused. Reads do not consume events,
change takeover, start a recording, or accept arbitrary file paths.

The same MCP tool provides worker-local `view=list|events|summary` with
`readerVersion=1`; no Java update or game RPC is needed for these disk views.
Only the selected instance's fixed `mcb-recordings/recording-<UUID>.jsonl` paths
are accepted, with no symlink redirection or fallback to another instance.
`list` uses `offset`/`limit` and newest-modified ordering. `events` and `summary`
accept a historical `recordingId` (default newest modified), `since` and optional
exact `kinds`. IDs are file-local, not the current client's session; bind the ID
before continuing. `events.nextSince` advances past inspected nonmatching events;
`hasMore` describes the current captured file prefix, not future appends.

Each file scan captures its initial byte length and validates complete JSONL
lines, recording/session identity and sequence order. Gaps and explicit payload
omissions are reported under `integrity`; a trailing partial line is excluded
and counted as `incompleteTailBytes`. Malformed complete lines, duplicate/reversed
sequences, mixed identities, shrink/replace and budget overrun refuse the read.
The reader does not repair, overwrite, enable or replay recordings. A disk read
does not prove Java's flushed watermark or observe live enabled/queue counters;
`liveStateObserved=false`, and no stop marker gives `recordingState=unknown`.

`summary` returns counts over the selected sequence/kind window, first/last pose,
per-dimension bounds, slot baseline/delta observation counts, grouped distinct
server before/after transitions and bounded highlights. Repeated identical
transitions have observation counts; neither these nor Forge request counts are
physical-action counts. Summary list truncation is explicit and leaves total
counts/integrity intact. Native read coverage and intent/acceptance limits remain.
Budgets: 257 recording files, 32 MiB per file, 16 KiB per line, 8-second scan;
24 KiB event content per page and 48 KiB summary. Disk reads are independent
MCP operations, not native sequence reads. Existing `scripts/call_tool.py` and
`scripts/mcb.ps1 -Profile <profile> -Action Recording` call this same adapter implementation.

Enable/disable with the local client commands `/mcb record on|off|status`.
Events carry `recordingId`, client `sessionId`, monotonic `seq`, wall-clock
`timeMillis`, `kind`, `source` and typed `details`. Native world ticks, sides,
screen/window bindings and sampled positions are included where applicable.
Forge events report cancellation at observation, not eventual execution.
`block_state_observed` uses bounded 1/5/20-tick follow-ups, never chunk loading;
unavailable identities/parts remain unknown. GUI slot and supported widget
changes are separate from input intent, and `causationProven` remains false.
NBT summaries carry an exact hash of the observed native SNBT serialization;
bounded text is explicitly truncated and is not a full inventory backup.

The response includes `enabled`, `path` (fixed instance-local JSONL),
`oldestSeq`, `latestSeq`, `nextSince`, `historyGap`, `hasMore`, `writtenSeq`,
`flushedSeq`, `flushPending`, `writerActive`, `stopReason`, `rejectedEvents` and
coverage. Memory is capped at 2 MiB/8192 events; individual events at 16 KiB
(oversized payloads become explicit omission records); event pages at 24 KiB.
A single bounded asynchronous writer flushes at least once per second while
healthy. Queued/written data is not claimed flushed. File/directory caps and
world/player changes stop recording; files are never deleted or overwritten.
Defaults and detailed coverage are documented in README's passive recording
section. No screenshots, physical input, automatic replay or mod-specific
arbitrary packet decoding are added.

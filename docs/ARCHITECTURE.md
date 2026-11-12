# BridgeNH code map and boundaries

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE_zh.md)

Agent entry: English. Chinese companion: human reading and review.

BridgeNH exposes one MCP service. Its frontend owns the connection; a private worker
owns business code and talks to the Forge mod over authenticated localhost HTTP.
Operations remain composable. Machine-specific layouts, recipes and complete
production plans belong to task scripts, not permanent tool handlers.

## Python package

Paths below are relative to `mcp-server/src/minecraft_codex_bridge/`.

| Layer | Files | Responsibility |
| --- | --- | --- |
| Stable transport | `frontend.py`, `backend_supervisor.py`, `backend_transport.py` | MCP connection, immutable worker snapshots, verified replacement and rollback |
| Worker/session entrypoints | `backend_worker.py`, `adapter_session.py`, `command.py`, `server.py` | Transport-specific startup and calls into the same `Adapter` |
| Catalog | `catalog/`, `tools.py`, `validation.py` | Tool declarations, ordered public catalog and argument validation |
| Application | `application/adapter.py`, `application/dispatch.py` | One execution envelope, explicit handler routes and native-operation fallback |
| Control boundary | `application/control.py` | Takeover, capabilities and native action context/receipt guards |
| Read boundary | `application/reads.py` | Bounded condition waits, composite owner reads and frozen cursor paging |
| Presentation | `application/results.py`, `application/descriptions.py` | MCP result/image rendering and advertised tool/resource descriptions |
| Domain handlers | `application/handlers/local.py`, `observations.py`, `tasks.py` | Local files/knowledge/lifecycle, compound reads, and composed actions |
| Domain implementation | Existing task/planning modules listed below | State machines, geometric calculations and domain acceptance |
| Evidence | `knowledge.py`, `operation_knowledge.py`, `run_log.py`, `player_recordings.py`, `call_timing.py`, `feedback.py` | Historical evidence, bounded records and diagnostics |
| HTTP/connection | `bridge.py`, `runtime_discovery.py`, `operation_policy.py` | Credentials, guarded RPC, transport cancellation and local/remote routing |
| Prism selection | `prism_instances.py`, `local_config.py`, `client_connections.py` | Separate Prism directory/instance name resolution, compatible profiles, bounded inventory and explicit client selection |

`Adapter.call_tool` owns validation timing, feedback and bounded evidence logging.
`Adapter._call_tool` validates before dispatch and converts failures consistently.
Handlers do not create another adapter, connection or workflow lock. Native writes
continue through the existing lock and control guard. Composed tasks receive that
same lock and retain their own bounded deadlines and acceptance checks.

`completed_result` is shared by operations whose success means `status=completed`.
AE orders deliberately retain their distinct resumable-pending policy. Partial
Waila results, image validation, GUI timeouts and unknown outcomes retain their
existing classifications; a common result helper must not erase these distinctions.

`server.py` retains the previously used import surface for local runners and
integrations. New business code imports `application` services directly. The
stable frontend must not import the catalog, HTTP client or application package.

## Catalog classification

`catalog/common.py` contains schema builders, shared identifiers, `ToolSpec` and
operation permission classifications. Domain declarations are grouped in
`client.py`, `player.py`, `world.py`, `gui.py`, `nei.py`, `ae.py` and `qa.py`.
`catalog/native.py` preserves the public order independently of this grouping.

`tools.py` assembles these declarations with composed tools and sequence schemas.
Its staged assembly is intentional: wait/sequence allowlists depend on the native
catalog and its permissions. Do not derive these allowlists from every later
composite tool, which could admit recursion or unsupported observation paths.
Existing schema imports from `tools` continue to resolve to the same objects.

To add or change an operation:

1. Choose the existing native operation or domain implementation first.
2. Put its schema/declaration in the corresponding catalog domain. Keep public
   tool order explicit in `catalog/native.py` for native tools.
3. For a Python composite, add one handler and register it in
   `application/dispatch.py`; every `operation=None` tool requires a handler.
4. Reuse control/read services and pass the existing lock, session binding and
   deadline into lower-level work. Do not retry an uncertain mutation.
5. Verify arguments, permissions, completion evidence and actual SDK/worker paths.

## Operation policy boundary

`OperationPolicies` owns bounded local intent and per-save/server choices.
`LocalJsonConfig` provides the same optimistic atomic persistence for it and actor
permissions. `OperationPolicyRules` classifies authenticated RPC operations once;
`GameBridge.prepareOperation` applies it on the client owning thread before all
HTTP dispatch, including composite primitives. `GameQueue.Control` rejects stale
policy revisions on subsequent tickets. Native adapters retain their existing
reach, ownership, actual game authority and no-replay checks.

Local `/mcb policy` and MCP `mcb_policy` both call `bridge.policy`. Python public
schemas remove executor choices through the bounded `policy_schema` builder;
private workflow schemas retain executor-specific proofs. The Adapter resolves
saved movement before composites; native navigation resolves it directly in the
existing client preflight. Actor roles remain independent. Remote OP observations
reuse the existing self sample and expire rather than trusting a handshake forever.

## Domain implementations

These existing modules remain separate from transport and are not moved solely to
rename imports. Choose the owner of a change by responsibility:

| Domain | Primary modules |
| --- | --- |
| Composition and evidence | `workflows`, `task_reports`, `operation_checks`, `native_wait` |
| Movement and interaction | `interaction_approach`, `interaction_samples`, `navigation_gate`, `visit_planning`, `survival_posture` |
| Construction | `construction_tasks`, `construction_tail`, `survival_checkpoints`, `placement_tasks`, `placement_attachment`, `structure_transform`, `assembly_plan` |
| Inventory/materials | `inventory_moves`, `whole_stack_transfer`, `material_sources`, `ae_inventory`, `provider_counts`, `nbt_order` |
| Recipes | `recipe_tasks`, `multi_recipe_tasks`, `fluid_recipe_tasks` |
| AE | `ae_tasks`, `ae_orders`, `ae_task_schema` |
| QA | `qa_tasks`, `qa_debug_tasks`, `qa_machine_flows`, `qa_flow_schema` |

## Reload boundary

Application changes replace the private worker. The stable frontend,
initialization instructions and dependency versions must match. Tool/resource
additions, removals and schema changes are committed with the replacement worker;
the frontend emits MCP `list_changed` notifications after successful activation.
Native rediscovery depends on the MCP host supporting these notifications.
Orders and unresolved effects are transferred through the existing versioned
checkpoint; replacement does not resume or replay game actions.

The legacy in-process server also supports data-only catalog refresh.
`catalog/sources.py` declares a fixed, dependency-ordered source bundle.
`catalog_refresh.py` interprets those declarations without executing edited
source. Internal imports are flattened only for that bounded interpreter; loaded
helpers and executable code stay frozen. Tool topology/permissions/helper changes
are refused. Shared catalog containers and cached validators update together
under the existing action lock.

Executable change detection covers nested Python packages, including added and
removed files. New modules must be present in the worker snapshot and wheel.
Changing the stable frontend, dependencies or initialization instructions requires reconnecting
the MCP service; editing compatible business code does not require restarting Codex.

## Forge mod

`loader.rfb.WindowFocusPlugin` wraps fixed SDL window calls in lwjgl3ify Display before Forge initialization. Native helpers carry `@Lwjgl3Aware`; do not mark the entire JAR aware, because other classes require LWJGL2 remapping. Startup policy is a bounded per-client file. Windows operations protect only the owned HWND, restore temporary focus/style/procedure state in `finally`, and use normal SDL resize events. Minimized startup creates a hidden, non-minimized SDL window, then shows and minimizes it under the guard. SDL's initially minimized creation path shows the HWND before that guard and can omit its taskbar button. Restoring a minimized window hides that already minimized HWND before showing it without activation; Windows can otherwise activate it after earlier maximize/restore cycles. No external foreground window is activated to compensate for focus theft.

Java sources use `src/main/java/com/Goderium/BridgeNH/`, with `BridgeNH.java` as
the sole handwritten root-package entry point. Subpackage names are lowercase;
class names use UpperCamelCase. Tests follow the same domain packages. Foreign
API fixtures in test sources keep the corresponding mod's namespace.

| Package | Responsibility |
| --- | --- |
| `common.bridge` | Dispatch, queues, request guards, readiness and capabilities |
| `common.dialogue` | Server commands and attributed player dialogue |
| `common.ae`, `inventory`, `machine`, `recipe`, `structure`, `world`, `qa` | Shared domain adapters and their state models |
| `client.control`, `lifecycle`, `recording` | Native controls, menu/world lifecycle and player recording |
| `client.gui`, `nei`, `navigation`, `render` | GUI adapters, NEI observations, movement and framebuffer capture |
| `config` | Configuration persistence, limits and operation policies |
| `loader`, `loader.core`, `loader.rfb` | Client initialization and isolated Forge/RFB loading hooks |
| `mixin` | Mixin classes referenced by the shared Mixin configuration |
| `network` | Local HTTP and authenticated client/server packet transports |
| `util` | Shared JSON/NBT, fixed API access, geometry, diagnostics and generated build/version identity |

Core/RFB loading hooks have their own subpackages. Transformer exclusions must
stay scoped to those hooks; excluding all of `loader` would also exclude the
Minecraft-facing `ClientBootstrap` from normal class transformation.

`GameBridge` owns client/server/render queues, actor bindings and action lifecycle.
`ClientReadiness` reduces observed inputs into menu/local/remote readiness without
reading Minecraft state. `BridgeCapabilities` groups capability declarations and
limits; it receives current access flags and never caches readiness.

Existing native adapters remain responsible for GUI/NEI/AE, world observations,
navigation, construction, recording and optional providers. Queue ownership and
client/server authority stay in the coordinator. Moving an adapter to another
Java package must not widen access to mutable bridge state or bypass side guards.

`PersonalSpaceGuiAdapter` integrates the optional portal editor into the same
screen/widget identity and action contract. Its bounded public widget tree avoids
scanning unrelated block/model lists. Input calls the native widgets; fixed private
dropdown geometry/search helpers only support the original popup path. Settings
are never written into dimension models. The existing Forge draw-post observer
supplies the natural-frame save barrier, and `LogicalGuiPointer` supplies the
protected popup hover lease. Native widgets require the actual GuiScreen draw
hooks; NEI pointer calls additionally require the CodeChicken mouse getter hook.
No optional-mod class is a compile/runtime link, and no new Mixin is required.

## Player dialogue

`AgentDialogueCommand` registers a side-neutral server command, and
`AgentDialogueMailbox` owns bounded messages, leases and private receipts on the
server thread. `agent.dialogue` reuses the existing authenticated Forge transport
and operator checks. Player input is not executed as a server command or code.
The server root is `/mcb` with a legacy `/mcbagent` alias; the client control
command forwards the explicit dialogue/permission subcommands through the native player connection.
Optional public replies claim a message in the same authoritative mailbox,
then reuse session/connection-checked `chat.send`. A claimed reply is never
sent twice, including after an unknown packet outcome. Private receipts remain
separate; server chat submission does not certify that moderation allowed it.

`dialogue.py` manages one instance-bound relay and its OS lock; `dialogue_host.py`
routes queued input through App Tools and native steering through `dialogue_ipc.py`.
`dialogue_log.py` stores bounded metadata and reply-reference mappings in a local
SQLite journal. Compact player prompts carry a nickname, mode, random short
reference and body; identifiers and accepted native turn IDs stay in that journal.
Reply resolution checks the exact thread/session/lease, never an implicit latest
message. Failure to save a mapping prevents dispatch; a later journal failure
does not replay an already dispatched input or reply.
The IPC adapter only discovers/probes the bound local owner and requests steering;
the desktop owner supplies `expectedTurnId` to `turn/steer`. It never starts a turn,
claims ownership, or subscribes to conversation history. The relay uses the
current chat's identity, never another Codex agent/process. It separates queued,
dispatching, delivered, replied and uncertain states; uncertain submissions are
not replayed. The public MCP entry is `mcb_dialogue`, routed by the existing local
application handlers. See [lifecycle and host limitations](PLAYER_DIALOGUE.md).

## Verification

Run Python tests from `mcp-server` with the project virtual environment. The
architecture tests cover categorized catalog publication, nested code changes,
handler coverage and frontend isolation. Worker tests use real child processes
and a continuing MCP session, including failed replacement and unresolved actions.

For Java changes, use the wrapper at the repository root: `compileJava build`.
The standard `check`/`build` lifecycle includes the existing `bridgeSelfTest` harness;
it does not require inventing a JUnit suite for the main-based self-tests.
The self-tests use mocked game objects and do not constitute live-client acceptance.
Deploy only a verified runtime JAR to the explicitly authorized test client;
compare the installed hash and use normal MCB reads/actions for live verification.

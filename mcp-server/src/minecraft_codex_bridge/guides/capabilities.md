# BridgeNH tool capability index

Agent reference: English; [Chinese companion](capabilities_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

Read offline with `mcb_help({"topic":"capabilities"})`. This index covers all public tools shipped with this revision, including the four frontend management tools. It explains what to choose and what still needs verification. A listed tool is not a promise that every client, server, mod version or permission level supports it.

## Find a feature before reading source

1. Choose the relevant group below. Read [overview](overview.md) for binding and policy, [operations](operations.md) for machine/inventory workflows, [NEI](nei.md), [AE2](ae.md), [testing](testing.md), or [maintenance](maintenance.md) for the detailed boundary.
2. Query the loaded worker catalog by a tool-name fragment: `mcb_help({"topic":"tools","query":"bookmark","limit":20})`. Query matching is against names, not natural-language descriptions. For exact arguments and the complete description use `mcb_help({"topic":"tools","tool":"mcb_nei_bookmark_action"})`.
3. Continue catalog pages only with the returned `nextOffset` and `catalogFingerprint` as `expectedCatalogFingerprint`. Summary descriptions may be truncated; the exact-tool query returns the complete schema. Re-read after a reload.
4. `mcb_backend_status`, `mcb_backend_restart`, `mcb_clients` and `mcb_connection` belong to the frontend. Use their host-advertised direct schemas; the worker's `topic="tools"` does not list them. A worker-catalog `UNKNOWN_TOOL` for one of these does not mean the management tool is absent.
5. For game operations, confirm the explicit client binding and current `mcb_health` capability/policy. Read relevant current identities and coverage. If the host retains an old tool list, follow the guarded compatibility-call procedure in [maintenance](maintenance.md#discover-and-call-current-tools). Only inspect version-matched source when the guides, live schema and native observations leave a specific question unresolved.

Read-only tools can still require a live session, supported server bridge or higher observation permission. Control calls normally establish background protection; reads preserve the player's control choice. Unknown effects require reconciliation, not replay.

## Connection, help and maintenance

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_help` | Read fixed offline guides or the loaded worker catalog and exact schemas. Use this index for discovery; use `topic="tools"` for current arguments. |
| `mcb_clients` | List inventory within an explicit Prism directory. Does not bind, launch or prove that a runtime file represents an online client. |
| `mcb_connection` | Inspect, explicitly bind or unbind this conversation. Bind with actual chat ID, current generation and a new request ID; a fresh frontend starts unbound. |
| `mcb_backend_status` | Read this frontend/worker, generations, fingerprints and catalog-notification observations. No game call and no restart. |
| `mcb_backend_restart` | Replace compatible worker code, tools/resources and guides on the same MCP connection. Verify state handoff and actual host rediscovery; see [maintenance](maintenance.md). |
| `mcb_health` | Observe bound instance/session, readiness, executing build, authority and capabilities. A current readiness receipt does not prove future loading or action completion. |
| `mcb_policy` | Read or persist save/server operation intent. Survival/efficient/creative/full, movement and QA ceilings remain intersected with actual authority; no automatic OP or flight grant. |

## Client lifecycle and native window

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_launch` | Start the selected instance once; windowMode=minimized is default (taskbar), with background (visible without activation) and normal alternatives (requires client window-controls v2). action="status" is read-only continuation. Java existence is not readiness; never replay an uncertain launch. |
| `mcb_worlds` | List existing local saves and exact folder names. Does not create, delete or load a world. |
| `mcb_load_world` | From the main menu, schedule loading an authorized existing exact save folder with the current session. Wait for actual world readiness. |
| `mcb_connect_server` | Schedule normal Minecraft connection to an explicit host and separate port. Uses launcher authentication; observe the actual connection and world, not only the scheduled ticket. |
| `mcb_leave_world` | Normally save/leave the owned integrated world or disconnect a server. Bind current session and `health.lifecycleTarget.expectedWorld`; verify return to menu. |
| `mcb_exit` | Normally save/disconnect and close the selected client. Verify process exit; a scheduled acknowledgment is not shutdown completion. |
| `mcb_window` | Read actual window mode, native flags, client/framebuffer sizes and supported actions. Fullscreen preference and live fullscreen state are different. |
| `mcb_window_action` | Maximize, restore, minimize, resize or explicitly leave fullscreen. Windows operations preserve focus and stacking. resize requires width/height. Bind current session/window/mode; verify confirmed and later dimensions. Does not save window preferences. |

For a first launch after reconnect, an offline client cannot yet satisfy live binding. Use the supported fixed-instance launcher with the user's explicit selection, then bind after readiness; see [maintenance](maintenance.md#client-startup-and-binding).

## Manual control, input ownership and bindings

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_background` | Combined agent takeover/release: protect physical mouse and run unfocused, or restore manual control and the previous pause setting. Verify `background` readback. |
| `mcb_mouse` | Change only physical mouse handling: true allows manual mouse, false protects it. Does not change unfocused execution. |
| `mcb_unfocused` | Change only unfocused execution, restoring the previous pause setting when disabled. Does not change physical mouse handling. |
| `mcb_bindings` | Read actual registered key bindings, categories, key codes and modifier/held state. Does not press keys or consume a key event. |
| `mcb_task_status` | Read the current native input/navigation task ID, phase and owned keys. Use the returned identity for a later cancel. |
| `mcb_task_cancel` | Cancel one exact task in its current runtime session and release its owned keys. Never use an old ID to cancel a replacement task. |

## Combined observation, waits and evidence

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_snapshot` | Read current player/world/screen, pause, focus, crosshair and available server-control evidence. Keep stable screen kinds separate from raw class names. |
| `mcb_observe` | Combine 1..8 supported read views with field projection and explicit coverage. Non-atomic; does not take over, write, include screenshots or automatically exhaust pages. |
| `mcb_wait_for` | Bounded wait on a repeatable read's structured condition. Repeats observations, never the original action; timeout leaves completion unresolved. |
| `mcb_sequence` | Run guarded read/action/wait/assert steps with current bindings. Also carries one guarded compatibility call to a current worker tool; no recursion or automatic rollback/replay. |
| `mcb_state_diff` | Freeze selected player/inventory/GUI/message baselines and page comparisons with current state. Continuations cannot change sections; inspect omissions and consumed cursor rules. |
| `mcb_capture` | Collect bounded diagnostics and selected observations. Non-atomic; screenshots are off by default and require an actual visual need. |
| `mcb_screenshot` | Read the real client framebuffer when visual evidence is needed. It is not an OS capture or a substitute for available structured observations. |
| `mcb_server_observe` | Read authenticated remote status, self, blocks or machines through the client. Respect connection/session/dimension and server permission gates; not arbitrary remote control. |
| `mcb_operation_check` | Run a fixed read-only machine, AE, AE-buffer or NEI operating check. Keep observed facts separate from guidance and remaining unknowns. |

## GUI reading and semantic actions

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_gui` | Read current screen/window, slots, text, controls and coverage. Supported native waits can prove synchronization; `screenOpen` alone does not. |
| `mcb_gui_widgets` | Discover current widget IDs, supported named actions, values and bounds. Prefer actionable filtering; page incomplete coverage before declaring a control absent. |
| `mcb_gui_widget_action` | Activate or edit an advertised native widget; supported phantom/ghost actions use real adapter semantics. Verify synchronized values and ghost cleanup, not only submission. |
| `mcb_gui_tooltip` | Read a real slot or current scaled-position tooltip without moving the physical mouse. Prefer actual stack/NBT/fluid data to registry templates. |
| `mcb_gui_pointer` | Set/clear a short-lived logical, screen-bound hover without OS mouse movement. Observe a completed native frame before claiming hover-dependent text. |
| `mcb_gui_click` | One low-level scaled GUI click under the exact screen ID. Prefer semantic actions; AE virtual grids and MUI2 controls can refuse generic coordinates. |
| `mcb_gui_key` | Fixed navigation keys or one printable BMP character under current screen identity. Does not simulate physical R/U/A or modifier-held shortcuts. |
| `mcb_open_inventory` | Open normal player inventory from world view. Supported creative clients select the player-inventory tab for normal synchronization; inspect actual GUI/slots. |
| `mcb_open_pause_menu` | Open the native in-game menu from world view with an empty cursor. Discover its current widgets; pause can stop integrated-server progress. |
| `mcb_close_gui` | Submit one normal close. For later low-level movement/feeding, verify a newer matching server/client container state instead of assuming a close acknowledgment. |

## Items, inventory and accessories

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_inventory` | Read actual player slots, selected hotbar and cursor; request NBT when identity matters. Truncated NBT cannot prove exact equality. |
| `mcb_items` | Search registered item identities without granting items. Page the returned catalog; registration is not ownership. |
| `mcb_item` | Inspect one registry/metadata count-one template and tooltip/fluid-container information. This does not reconstruct a live stack's NBT. |
| `mcb_item_variants` | Page an item's public creative variants without spawning them. Variants are templates, not inventory observations. |
| `mcb_hotbar` | Select an actual hotbar slot 0..8. Verify the held stack before an interaction or placement. |
| `mcb_slot_click` | Perform a native click on an actual container slot with current window ID. Modes have distinct semantics; AE virtual resource IDs are not slot numbers. |
| `mcb_transfer_whole_stack` | Transfer one declared untagged whole stack into an empty supported MUI2 destination. Requires fresh slot/owner bindings and full conservation verification. |
| `mcb_inventory_moves` | Batch 1..16 disjoint untagged whole-stack moves between player slots through normal inventory actions. Stops on uncertainty; does not grant or recreate items. |
| `mcb_supply_items` | Explicit creative supply of a bounded item batch under real authority. Honor empty-inventory or advertised preserve-inventory preflight and verify actual additions. |
| `mcb_baubles` | Read optional accessory inventory, identities and available ability/slot evidence. Client/server ownership and API coverage remain explicit; no equipment writes. |
| `mcb_open_baubles` | Send one normal accessory-inventory opening packet from world view. Wait for matching current GUI/server container; the receipt does not prove equipment or flight. |

## Movement and real interaction

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_look` | Set native player yaw/pitch. Preserve accumulated yaw when restoring and verify the current crosshair/target before use. |
| `mcb_input` | Hold supported in-game keys for bounded ticks and release automatically. Uses game input, not OS injection; inspect effects after timeout rather than replaying. |
| `mcb_interact` | Normal player use/attack on a current or explicit reachable target/face. Optional sneak/hit-point semantics need advertised support; controller use may differ from the full Forge input chain. |
| `mcb_interact_sample` | A declared bounded series of real right-clicks on one already-reachable target. Not a machine-state setter or an excuse to repeat an uncertain batch. |
| `mcb_navigate` | Execute one guarded navigation goal using saved movement policy and actual walking/flying state. Arrival needs current client/server evidence; no automatic terrain clearing. |
| `mcb_approach` | Approach observed block targets and optionally interact/open their GUI. Owns its reach/view/arrival work; do not duplicate it around a workflow that already includes approach. |
| `mcb_plan_visits` | Read-only ordering of declared feet stops with dependencies and route estimates. Does not move the player or prove a collision-free executable route. |
| `mcb_follow` | Start/read/stop a native dynamic follow task for one resolved loaded player in an integrated world. Inspect the owned task and actual movement; not remote-server support. |
| `mcb_flight` | Set supported local creative flight through normal ability synchronization. Existing authority and actual airborne state are required; no game-mode or equipment grant. |
| `mcb_flight_check` | Inspect or explicitly probe real survival/creative flight. Permission alone is not flight; invalidate evidence after equipment, world, mode or authority changes. |
| `mcb_teleport` | Protected teleport under saved TP policy and real command authority. Verify loaded destination/body/support and actual arrival; creative mode alone is insufficient. |

## World geometry and construction

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_scan_config` | Read/set bounded scan radii, cache radii and memory budget. Changes invalidate relevant cached views; they do not load chunks. |
| `mcb_world_scan` | Page loaded non-air blocks using supported radius, absolute-bound or directional selection. Unloaded/partial space is unknown; continue only with the returned cursor. |
| `mcb_world_inspect` | Inspect one loaded block and available owner/machine data. Enhanced tile information depends on actual context and authority. |
| `mcb_world_inspect_many` | Batch exact loaded coordinates, with bounded compact or detailed modes. Check every position and coverage; do not infer omitted inventory is empty. |
| `mcb_world_tiles` | Discover nearby loaded tile entities in an authorized local developer world. Candidate/scan caps can make counts inexact; does not load chunks. |
| `mcb_entities` | Page already loaded entities with available identities, bounds, motion, health and ownership. Missing fields or pages are unknown. |
| `mcb_spatial` | Read loaded occupancy, collision, support or ray evidence. Bind the actual dimension and complete requested positions; unknown is not air or safe support. |
| `mcb_waila` | Read current crosshair provider text and optional bounded NBT. Match the real target and inspect provider completeness; cached display text is not authoritative machine acceptance. |
| `mcb_world_structure` | Read public StructureLib pieces/geometry from a supported loaded controller or registered prototype. Missing offsets/requirements stay unknown; hints do not prove formation. |
| `mcb_structure_transform` | Map public structure coordinates/faces between observed frames or an explicitly supported target alignment. Keep controller identities, offsets, dimension and coverage. |
| `mcb_placement_preflight` | Plan a fresh controller from public geometry and an anchor without writes. Inspect floor, required air, conflicts and full coverage before construction. |
| `mcb_schematic_goal` | Compare a bounded generic schematic against server-authoritative blocks and suggest repair order. Does not place/remove blocks or prove StructureLib formation. |
| `mcb_place_block` | Place the selected real block or explicitly bound AE part through normal supported placement. Verify actual target state and real inventory deduction. |
| `mcb_blueprint_build` | Compose native blueprint construction with declared controller/port goals and actual formation/role acceptance. Needs complete geometry and authorized space; no machine-specific hidden layout. |
| `mcb_survival_build` | Bounded native survival construction with the real blueprint, actor and materials. Respect material-source and flight guards; verify actual placement and material consumption; placement hints alone are insufficient. |

## Machine operation and recipes

Read [operations](operations.md) before feeding, starting, stopping or recovering production.

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_machine_inspect` | Read current machine state/components and supported remote authoritative observations. A cached last recipe or displayed progress does not prove current production. |
| `mcb_machine_capture` | Capture selected loaded local tiles in one server callback, then page frozen values by capture ID. Include NBT for serialized state; it does not expose every transient field. |
| `mcb_machine_watch` | Bounded read-only sampling of selected machine fields. Does not repeat writes; execution timing and sampled progress do not establish CPU cost or TPS. |
| `mcb_machine_work` | Change a supported GT machine's normal allowed-to-work state under exact owner/world identity. Disabling may let an active batch finish; verify stopped state separately. |
| `mcb_recipe_query` | Query public runtime recipes or one exact native backend match without opening NEI. Inspect access, completeness and actual candidate validation; catalog results do not prove executability. |
| `mcb_recipe_once` | Run one supported quantitative recipe workflow with real controls and consumption/output/recovery/stopped checks. Choose no-generation/preservation for real inventory; explicit creative preparation can clear/supply it. |

## NEI and linked research

Read [NEI](nei.md) for scope, temporary cleanup and the remaining unsupported gestures.

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_nei_open` | Open crafting/usage for an exact current inventory/container/recipe source or an explicit registry template. Live sources preserve NBT; a template does not. |
| `mcb_nei` | Read the current recipe handler's original indices, positioned stacks and supported GT metadata. Display overclock is a UI calculation, not machine execution. |
| `mcb_nei_navigate` | Native handler/page/back/overlay/pause/resume/bookmark operations under current screen identity. Bookmark saves a whole recipe; read actual additions and quantities. |
| `mcb_nei_view` | Read structured panels, handlers, recipes, previews, research/tree details and supported hover evidence. Respect section coverage, context token and pagination. |
| `mcb_nei_item_action` | Native search, fake-cursor pick/release or authorized creative take. Search is asynchronous; release preserves real items; take requires actual inventory-delta verification. |
| `mcb_nei_bookmarks` | Read namespaces, groups, exact entry IDs and retention. Unknown existing entries are not adopted as temporary; refresh tokens after state/layout changes. |
| `mcb_nei_bookmark_action` | Add/remove items or whole recipes, group/reorder/move pages, adjust demand, retain and clean task-owned entries. Recipe deletion includes linked items; preserve unrelated/retained entries. |
| `mcb_nei_preview_control` | Operate advertised structure-preview controls through native callbacks. First read current preview/control identities; the preview is not a built structure. |
| `mcb_research` | Read exact NEI-linked or unambiguous visible Thaumonomicon research and player-visible pages/dependencies. Does not grant research or prove every machine prerequisite. |

## AE2 network and crafting

Read [AE2](ae.md); synchronized GUI, CPU, provider buffers, storage cells and player inventory are separate evidence sources.

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_ae` | Read current synchronized terminal, pattern, cell configuration, preview, CPU, interface or request data under the exact screen ID. Respect each scope's completeness. |
| `mcb_ae_action` | Named terminal/resource/crafting/pattern/cell/CPU/interface actions using observed identities. Verify synchronized settings, actual transfer or exact job; submission alone is insufficient. |
| `mcb_ae_configure` | Batch supported priority/blocking/storage-filter settings on exact placed hosts through normal GUI packets and authoritative readback. Requires supported local workflow context and empty cursor. |
| `mcb_ae_install` | Batch declared real AE parts/cables with exact faces and adjacent/host placement semantics. Uses native interactions; verify attachment and full inventory conservation. |
| `mcb_ae_material_source` | Observe a public cell-container anchor's real grid, power, extract permission and finite/creative source mode. Reuse its bound source fields; readiness is not extraction proof. |
| `mcb_ae_order` | Start, resume or inspect one exact native item/NBT crafting order and pickup. Preserve request/job identities and pending phases; completion requires actual collection. |

## Optional QA

These tools require the advertised QA integration and appropriate policy/authority. They are not prerequisites for ordinary BridgeNH use. Read [testing](testing.md); do not install QA, raise policy or use a production area merely because a tool exists.

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_qa_catalog` | Discover supported cases, public fixture plans, diagnostics and same-run evidence. Missing QA/API is explicitly unavailable; prototypes are not formed machines. |
| `mcb_qa_run` | Submit one authorized case or supported immutable declaration group. Pin the actual runner/world/dimension and inspect its returned run ID; submission is not PASS. |
| `mcb_qa_status` | Read a specific run's activity and available bounded waits. Infinite observation-ready output does not prove finite completion or stable stop. |
| `mcb_qa_results` | Page same-run cases/issues and optional detailed evidence. Preserve run identity, ownership and coverage; old global results are not current acceptance. |
| `mcb_qa_cancel` | Request a supported owned run's normal stop/drain. Observe the same run to verify recovery and stopping; no global cancellation or unsupported forced stop. |
| `mcb_qa_command` | Invoke only existing public QA commands under their precise scope. Clearing fixtures and forgetting records differ; preserve ownership and never use this as arbitrary command execution. |
| `mcb_qa_test_once` | Complete a supported finite test workflow with its own discovery, preflight, submission, waits, results and after-checks. Require full-flow evidence when needed; avoid duplicating its internal checks. |

## Diagnostics, messages, logs and knowledge

| Tool | Use and verification boundary |
| --- | --- |
| `mcb_diagnostics` | Read bounded JVM/heap/FPS and server execution timing, or supported fixed-client process diagnostics when offline. Execution timing is not TPS; process presence is not world readiness. |
| `mcb_logs` | Read bounded tails from the selected client's fixed game logs. Historical errors and incomplete tails do not prove current state or non-execution. |
| `mcb_mods` | Inspect active mod versions and actual loaded sources; optional runtime evidence identifies executing code. A candidate file or current disk hash alone does not prove loaded bytes. |
| `mcb_messages` | Read bounded in-game chat/system messages after the returned sequence. Does not send messages or read arbitrary files. |
| `mcb_chat` | Send authorized native client commands or player chat within the game limit. Server permissions still apply; inspect feedback and effects after submission. |
| `mcb_dialogue` | Start/read/stop an authorized player-to-chat relay or reply to one exact message reference. Player text remains attributed input, not system authority; never replay uncertain delivery. |
| `mcb_recording` | Review passive player history using summary/list/events or current live recording state. Disk history works offline; gaps and missing stop markers remain unknown, with no replay. |
| `mcb_run_log` | Read/configure the selected instance's agent journal; report, resolve, defer or export bounded findings. History and agent summaries do not prove game effects. |
| `mcb_knowledge` | Query/review/record local operating lessons with real evidence and compatibility context. Separate hypotheses from verified findings. |

## Completion and missing coverage

Use the smallest existing workflow that owns the requested effect and its acceptance. Preserve complete identities, decimal quantities, current session/world/dimension and ownership. Automatically clean verified task-only NEI ghosts/bookmarks while preserving retained entries, real items and saved target configurations. Stop owned input tasks before returning manual control.

When a required feature is unavailable, record the exact missing native observation/action and current evidence using [workspace](workspace.md). Update this index and the relevant detailed guide when adding or changing a tool, including its counterpart for human review. Do not replace missing coverage with screenshots, OS input or direct save edits and call that native success.

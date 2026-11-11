# BridgeNH maintenance and tool discovery

Agent reference: English; [Chinese companion](maintenance_zh.md) for human review ([reading rules](workspace.md#reusable-startup-instructions)).

Read offline with `mcb_help({"topic":"maintenance"})`. Use [capabilities](capabilities.md) to find a tool, then its live schema.

## What can reload

| Change | Supported path | What remains to verify |
| --- | --- | --- |
| Compatible Python worker logic, tool additions/removals/schemas, packaged guide text/resources | Deploy the reviewed change to the registered runtime, then `mcb_backend_restart`. | Same frontend/binding, new worker, verified handoff, correct catalog/guide and actual relevant calls. |
| Stable MCP frontend/supervisor, initialization instructions, Python interpreter or dependencies | Reconnect the MCP service after the authorized update. Worker reload cannot replace its own frontend. | New frontend starts unbound; explicitly bind this chat again. MCP reconnection and a Codex application restart are different actions. |
| Java mod code or client runtime dependencies | Normally save/exit and restart only the selected authorized Minecraft client. | Observe new session, executing build/capabilities and real readiness; a replaced JAR on disk is insufficient. |
| Unsupported host tool rediscovery or host feature setting | Diagnose host support and configuration separately. | A server notification is not proof that the host exposes the tool. Respect any restriction on required host restarts. |

The frontend snapshots its own registered package directory. Editing another checkout does not update an installed wheel, and worker reload does not switch installations. Check the registration/runtime used by this connection before deployment. Keep source, installed runtime and distribution artifacts distinct; a documentation edit does not silently rebuild or reinstall a release.

Compatible reload preserves the conversation binding, AE order phases and unresolved action records. It makes no game RPC, requires no running Minecraft client and never resumes/replays game actions. A worker swap still requires a complete valid handoff; an active call must finish first. Binding a different client is a separate operation with separate guards.

## Perform and verify a worker hot restart

1. Validate the intended change and finish any active tool call. Read `mcb_backend_status({})`. Retain `frontendPid`, `workerPid`, `generation`, the bound conversation/client identity and relevant pending-action state.
2. Invoke `mcb_backend_restart` once with a fresh `requestId` and that current `expectedGeneration`. `timeout_ms` defaults to 30000 and allows 1000..45000. Optional `expectedSourceFingerprint` means the expected **candidate source** fingerprint; do not copy the old worker's fingerprint after changing its source.
3. Check the actual reply: `ok`, `state="running"`, `connectionPreserved=true`, unchanged frontend and binding, a different worker PID and advanced generation. Inspect transferred orders/failed-action counts and `catalogUpdate`. `gameReadiness="not_observed"` is normal: reload did not query the game.
4. For tool additions/removals/schema changes, inspect `catalogGenerations` and a later status's `catalogNotification`. Successful notification delivery and matching `lastListedGenerations.tools` show protocol progress; they still do not establish model-visible availability.
5. Verify the host's actual native tool list/schema and call the new or changed tool within the authorized scope. Verify removal from the host list when removing a tool. For guide-only work, read the changed `mcb_help` topic and its actual content. Do not restart a game merely to verify an offline guide.
6. Record activation, host rediscovery, actual call result and remaining limits separately. Remove any temporary acceptance probe and verify the original runtime/catalog are restored. Never inject a probe as a routine prerequisite for ordinary reloads.

If the restart response is lost or uncertain, inspect status first. Reusing the same request ID with the identical arguments retrieves that recorded restart result; it is not a second restart. A changed argument set with the same ID is refused. After a definite refusal and a corrected cause, a new attempt uses a new ID and fresh generation. This restart-specific result retrieval never authorizes repeating a game action.

**Verified scope:** on 2026-11-11, Codex desktop 26.1103.7773.0 with `mcp_tool_list_changed` enabled rediscovered a newly added read-only tool, exposed it in the current chat's native catalog and executed it. Removal propagated on the next worker reload. The frontend and binding were unchanged, with no Codex/Minecraft restart and no game calls. Other host versions still need their own observed support.

## Discover and call current tools

The [capability index](capabilities.md) explains every shipped tool's purpose. The current worker remains the authority for its executable schemas:

```json
{"topic":"tools","query":"bookmark","limit":20}
```

Send that object to `mcb_help`. `query` filters tool names, case-insensitively. To see the complete contract for one tool:

```json
{"topic":"tools","tool":"mcb_nei_bookmark_action"}
```

For subsequent pages, use returned `nextOffset` as `offset`, retaining the same query and returned `catalogFingerprint` as `expectedCatalogFingerprint`. Stop when `hasMore=false`. A catalog-change error means start discovery again; do not stitch different revisions together. Exact-tool queries contain full `inputSchema` and annotations; list descriptions can be shortened.

These queries do not call Minecraft and work without an online game. The four frontend tools (`mcb_backend_status`, `mcb_backend_restart`, `mcb_clients`, `mcb_connection`) remain direct host tools and are outside this worker catalog. Use their directly advertised schemas. The catalog is not a list of permissions or a guarantee of game support.

When a host still exposes old native tool definitions, the existing `mcb_sequence` also supports **one guarded compatibility call** to a current public worker tool. This can use the updated implementation without claiming native host rediscovery:

1. Query that tool's complete current schema with `mcb_help(topic="tools",tool=...)` and validate the intended arguments.
2. Read `data.invocation.callGuards` from the reply. It is available only for a bound supervised connection. Use this chat's current binding; never invent IDs or substitute another chat's guards.
3. Supply those exact guard fields together with `call:{"tool":<returned tool name>,"args":<validated arguments>}` to `mcb_sequence`. This is a single-call envelope, not a normal `steps` plan. Do not mix the two forms.
4. Read the target's actual result/error and apply its normal acceptance and no-replay rules. Permissions, takeover, time limits, journaling and uncertainty remain in force. Frontend management tools cannot be routed this way; `mcb_sequence` cannot call itself.

The worker catalog fingerprint in `mcb_help` is **not interchangeable** with the supervisor's full contract fingerprint in `mcb_backend_status`. Copy the returned `callGuards` unchanged. After reload or rebinding, refresh them. `TOOL_CATALOG_CHANGED` or `BINDING_GENERATION_CHANGED` refuses before dispatch; an unknown target action outcome still requires reconciliation rather than a new call.

## Diagnose the layer that failed

| Observation | Meaning and next step |
| --- | --- |
| `BACKEND_BUSY_NOT_DISPATCHED` | Another worker call is active. Let it complete and inspect status; do not interrupt or replay that call merely to reload. |
| `BACKEND_GENERATION_CHANGED` | The observed worker generation is stale. Read current status and reconcile what already activated before a new attempt. |
| `BACKEND_FRONTEND_RECONNECT_REQUIRED` or initialization/runtime incompatibility | The change crosses the stable boundary. Finish independent validation and use an authorized MCP reconnect; repeating worker reload cannot load a new frontend. |
| Candidate/schema/handoff validation fails | Inspect the precise refusal and actual current worker status. Do not claim activation, discard unresolved state or replay actions; correct the reviewed change first. |
| Restart succeeds but desired code/guide is unchanged | Verify which installed runtime was updated, its source fingerprint and actual loaded catalog/content. Editing a different source directory is not deployment. |
| `catalogUpdate` reports a change, but host tools remain old | Separate notification failure, a host that has not re-listed, and a host/model list that remains stale. Use current-schema discovery/guarded compatibility calls where supported; do not claim native refresh from the server receipt alone. |
| Game is offline after successful reload | This is not a reload failure. Continue offline guide/status work; launch only the explicitly selected client if needed and authorized. |

An existing frontend process may lack the catalog notification implementation. It needs one authorized MCP reconnect to load that frontend capability; worker reload cannot update that frontend implementation. Subsequent compatible worker/catalog changes can then use hot restart.

## Codex host configuration and disabled controls

Check effective configuration origin before concluding that MCB forces itself on. Some Codex desktop versions make **project-origin MCP entries read-only in their settings UI**, disabling both the settings gear and enable switch. User-level configuration and project-level overrides are separate from BridgeNH's per-chat client binding.

Within an authorized configuration repair, preserve the complete registration/options, remove the conflicting project override and update installer `installation.codexConfig` if moving the registration to user config. Back up and compare unrelated settings. Keep each chat explicitly bound; moving a registration does not choose a default Minecraft client or replace a running frontend.

Host support for `mcp_tool_list_changed` is separate from bridge support. Confirm the effective setting and loaded host behavior. A setting change or old host may require a host restart. If the task prohibits that restart, report the boundary and pause only the dependent step.

Do not assume a host Settings **Restart** button reconnects only one MCP server. In the inspected Codex UI it targets the host app-server. Respect other chats and the user's restart constraints; use `mcb_backend_restart` for the supported single-connection worker operation.

## Client startup and binding

New MCP frontends start unbound, and explicit binding authenticates live client health. A stopped client therefore cannot complete its first live bind. Installation profiles and recently used instances remain inventory, not default bindings.

For an authorized first startup, use the installed shared launcher CLI `python -m minecraft_codex_bridge.launcher_cli` with the registered runtime's Python and explicit `--prism`, `--instance` and `--runtime` paths from the selected Prism instance. The source wrapper is `scripts/launch_client.py`. This is the same bounded fixed-instance launcher used by `mcb_launch`; it performs no login automation, OS keyboard/mouse input or world creation. Do not guess paths or select another client.

After one launch dispatch, continue only its read-only `--status --timeout-ms 30000` path if readiness is unresolved. When live health is ready, bind with this chat's actual ID and current binding generation, then observe `mcb_health`. An existing binding survives normal client stop/start and credential rotation; use its `mcb_launch(action="status")` or authorized launch without switching instances. A scheduled load/leave/exit still needs its own completion evidence.

## Keep help current when maintaining tools

For a new or changed tool, update its [capabilities](capabilities.md) row, detailed guide and `_zh.md` companion. Follow the repository's [documentation standards](../../../../AGENTS.md#documentation-standards) for style, scope, shared rules and examples. Preserve tool-specific prerequisites and verification limits; fixed guide topics serve English.

Validate the public index against the loaded catalog including frontend tools, local links, UTF-8, redaction, offline guide reads and the 32 KiB raw/serialized English-guide limit. Use existing relevant regressions. After an authorized installed guide update, hot-reload this connection and read the actual revised topic. Source editing, runtime deployment, distribution packaging and publication are separate checkpoints; report exactly which ones were completed.

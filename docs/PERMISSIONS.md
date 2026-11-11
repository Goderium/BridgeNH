# Player permissions and operation policies

[English](PERMISSIONS.md) | [简体中文](PERMISSIONS_zh.md)

BridgeNH has two independent controls. **Player permission groups** govern messages, private debug and group administration. **Operation policies** govern the agent's permitted operations in a save/server. Neither grants Minecraft OP, creative mode or flight, nor replaces explicit chat-to-client binding.

## Player permission groups

These are defaults; configuration can narrow abilities but cannot exceed role ceilings.

| Group | Default abilities | Activation |
| --- | --- | --- |
| `guest` | Own identity/help; no sending, debug or management | Default for unregistered players |
| `user` | `say`, `steer`, own `inbox` | Explicit server assignment |
| `admin` | User abilities, private debug, group inspection/assignment and permissions reload | Explicit assignment; debug display needs separate opt-in |
| `agent` | Eligible to establish the actual dialogue lease; while bound, sending/debug and the same permission-management operations as the server console | Explicit registration and an active lease; otherwise effective guest |
| Server console / RCON | Full dialogue administration | Actual console identity; command blocks and other senders do not inherit it |

The dialogue lease differs from a chat selecting its MCP client. Registration alone does not prove active control. OP does not automatically become a BridgeNH administrator; admin membership grants no OP.

A bound `agent` account with effective `manage` permission can inspect and change **other players' groups**, assigning `guest`, `user`, `admin` or `agent`, and reload the permissions configuration. This includes granting or revoking administrator and agent membership. For these BridgeNH permission-management operations it has the same authority as the server console and can execute authorized changes directly through `/mcb group` and `/mcb permissions reload`; a human administrator or console does not need to execute them on its behalf.

Permissions live in the **server's** `config/codexbridge/permissions.json`. Singleplayer uses the same path in that client, shared across its saves rather than stored in world NBT. Remote servers use their own file; editing the client file cannot grant server access.

Bootstrap administrators/agent accounts from the server console, usually without the leading slash. After registration and binding, an agent with effective `manage` permission can manage further assignments itself. Replace `<player-or-uuid>` with an actual online player name or full UUID:

```text
/mcb whoami
/mcb group get <player-or-uuid>
/mcb group set <player-or-uuid> user
/mcb group set <player-or-uuid> admin
/mcb group set <player-or-uuid> agent
/mcb debug on
/mcb debug off
/mcb permissions reload
```

Assignments persist by UUID; online names only resolve UUIDs. Successful commands save immediately; a failed write does not change memory alone. Reload after manual edits. External disk changes cause commands to refuse overwrite until reloaded. Invalid config disables player permissions; repair and reload from console. Labels can change and abilities can narrow, but guest elevation, user debug/management, OP inheritance, unbound-agent privileges and a non-guest default are rejected.

Debug defaults off. Administrators/active agents opt in with debug on; server diagnostics remain logged. Group changes clear that player's debug opt-in. Revoking sending cancels undelivered messages; revoking the active agent registration closes its lease. Already delivered chat input cannot be retracted.

Ordinary sending players need neither BridgeNH nor OP. The agent client and server need compatible versions; the server dialogue bridge additionally requires actual OP level 2 and registered agent membership for the agent account. Execution context comes from the live connection. See [player dialogue](PLAYER_DIALOGUE.md).

## Operation policy modes

Modes define ceilings. **Actual authority, client/server support, individual tool rules and read-only selection still decide execution.**

| Mode | Ordinary native player operations | Enhanced machine/structure reads | Material supply | Direct backend operations | QA under auto |
| --- | --- | --- | --- | --- | --- |
| `survival` | Allowed with actual materials/native interactions | No | No | No | `off` |
| `survival_efficient` | Allowed | With actual authority | No | No | `observe` |
| `creative` | Allowed | With actual authority | With actual creative/command authority | No | `test` |
| `full` | Allowed | With actual authority | With actual authority | Supported direct machine changes/game commands, with real permission checks | `manage` |

Ordinary operations include supported movement, GUI, inventory and native interaction, not every tool. Choosing creative does not switch game mode; full does not bypass server permission or enable local-only tools remotely. An actually creative player cannot perform protected mutations under a survival policy, preventing free-material behavior from being treated as survival.

Policies live in the **client's** `config/codexbridge/policies.json`, separately scoped by singleplayer save or server endpoint. New scopes use defaults: survival, path, readOnly=false, terrainChanges=false, qa=auto. This file differs from server player permissions.

Local client commands share the `mcb_policy` endpoint:

```text
/mcb policy status
/mcb policy mode survival
/mcb policy mode survival_efficient
/mcb policy mode creative
/mcb policy mode full
/mcb policy move path
/mcb policy move tp
/mcb policy readonly on
/mcb policy readonly off
/mcb policy terrain on
/mcb policy terrain off
/mcb policy qa auto
/mcb policy reload
```

These are alternatives, not a script to execute sequentially. Inspect status and choose the intended option; reload after manual edits. Tool scope=current updates the current scope; scope=defaults explicitly changes defaults. Already configured save/server selections remain independent.

## Movement, read-only and terrain

| Setting | Meaning and limit |
| --- | --- |
| `movement=path` | Walk or use actual existing flight. Creative/allowFlying alone does not mean the player is flying. |
| `movement=tp` | Requires normal TP command permission and a supported local backend. Unavailable TP resolves to path before dispatch, never as an automatic alternative after uncertain effects. |
| `readOnly=true` | Refuses classified mutations and new QA/fixture cleanup. It is not a client freeze: movement, lifecycle, dialogue and policy controls have separate rules. |
| `terrainChanges=true` | Records permission intent for route terrain changes; it does not add automatic digging/filling or a terrain planner. |

Movement retains actual posture, reach, collision, destination and required client/server guards. Policy changes invalidate stale waits/scans and related contexts; they do not roll back completed effects or replay actions.

## QA subpolicy

Horizon-QA/TST-QA are optional integrations. Ordinary use does not need them. auto follows the mode ceiling above; explicit levels can narrow it, never exceed it.

| Level | Ability |
| --- | --- |
| `off` | Capability discovery remains available; no new unrelated-run observation or tests. |
| `observe` | Supported catalog/status/results; no fixture creation/cleanup. |
| `test` | Provider-declared controlled finite cases or generic declarations in an authorized area; arbitrary Java callbacks are not thereby sandboxed. |
| `manage` | Supported management, with actual authority, ownership and scope checks; QA teleport also needs TP permission. |

Select using `/mcb policy qa off`, observe, test, manage or auto. After downgrade/read-only, explicit owned-run reads, supported normal stop and release of finished records remain possible; other players' runs remain excluded. forget releases a record, not its blocks. See the [testing guide](../mcp-server/src/minecraft_codex_bridge/guides/testing.md).

## Live state is authoritative

Inspect `/mcb policy status`, `mcb_policy` and `mcb_health.operationPolicy` for selected intent and effective capabilities. Player groups, operation policy, game authority and chat binding are distinct. A tool in the catalog does not prove runtime availability. Inspect actual state after failure/uncertainty instead of resubmitting an action to test permission.

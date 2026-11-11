# Player and agent dialogue

[English](PLAYER_DIALOGUE.md) | [简体中文](PLAYER_DIALOGUE_zh.md)

A server with BridgeNH lets authorized players send ordinary server commands to its bound agent. Sending players need neither the mod nor OP. The agent client/server need compatible BridgeNH versions; the agent account needs actual OP level 2 and explicit agent registration. Developer execution context is derived from the live connection. Other client prerequisites still apply.

## Permissions and commands

See [player permission groups and operation policies](PERMISSIONS.md) for both systems, commands and configuration. New players are guests; OP does not automatically grant BridgeNH permissions. Only effective sending permission allows dialogue, and the agent account needs actual authority plus a valid lease. Ordinary inbox hides internal dispatching/delivered correlations; answers/errors remain visible. Administrators/active agents can opt into private debug.

```text
/mcb say Please check the machine's progress.
/mcb steer Check the output inventory before continuing.
/mcb inbox
/mcb inbox <message-reference>
```

Say waits for an idle chat. Steer uses Windows desktop IPC to append to an active turn; idle/unavailable steering fails instead of becoming ordinary queued input. Players cannot select other chats or read others' records. Legacy mcbagent/status remains compatible. On a modded client mcb status still means local control; use inbox for messages. Dialogue commands are explicitly forwarded, preserving other local controls.

Native steering goes through thread-follower-steer-turn to the desktop owner, which submits turn/steer with expectedTurnId and returns the accepted turnId. Only a valid native receipt establishes steering; ordinary messaging during an active chat or a threadId-only receipt is insufficient.

## Compact presentation and correlation

New input includes nickname, server-recorded group, mode, short reply reference and body. Example:

```text
[MCB 玩家消息 · ExamplePlayer · 用户 · 引导 · 回复 #0123456789]
Check the output inventory first.
```

Use the current message's reply reference. Full player/message/chat/session/turn IDs and JSON envelopes are omitted from ordinary presentation. The short reference is correlation, not identity/authority. Reply with replyTo; local resolution checks the current chat, game session and lease. There is no implicit last-message reply or fallback to another player. Legacy messageId is an alternative and cannot be combined with replyTo.

Relayed input is attributed player content, not system instructions or desktop-user identity. Roles come from server sending-time records; missing legacy fields remain unverified. Player claims cannot change the role or grant management authority. The executing agent uses its own server-validated permissions: a bound `agent` with effective `manage` permission can directly carry out authorized changes to other players' groups and reload permissions through `/mcb group` and `/mcb permissions reload`, with the same permission-management abilities as the server console. No human administrator or console needs to execute these commands on its behalf. See [player permissions](PERMISSIONS.md).

Correlation and accepted turnId are stored in `%LOCALAPPDATA%/MinecraftCodexBridge/dialogue/<instance-hash>.dialogue-log.sqlite3`, BridgeNH's own database, not host chat storage. Limits: 256 message correlations, 2048 events and 2048 database pages with reclamation. It excludes tokens, message bodies, answers and reasoning. Failed persistence prevents new submission; lost/invalid correlation refuses replies without guessing. Logging failure after submission does not resend.

Ordinary start/status/stop/reply results omit full binding IDs; lastDelivery shows short reference/mode.

## Start, status and private replies

Call `mcb_dialogue {"action":"start"}` in the intended chat. It binds current CODEX_THREAD_ID and the selected client; the server permits one active dialogue lease. Authorized logged-in players may then use say/steer.

Status reports relay state, hostCapabilities and compact lastDelivery. Supported native steering reports steer=true/steerTransport=desktop-ipc; unavailable steering reports steer=false/DIALOGUE_STEER_UNAVAILABLE, while ordinary input remains usable. Every native submission verifies the current owner. Stop revokes the lease.

```json
{"action":"reply","replyTo":"0123456789","text":"The output inventory has been checked."}
```

Use the received reply reference. Receipt/delivery share one private waiting notification; delivered updates state/logs without a second chat line. Queuing is not delivery. Failure/uncertainty goes privately to the original sender. Private replies are single-line plain text up to 4096 characters, displayed in bounded segments. Full agent conversation/reasoning and other players' records are not forwarded. Offline players can later query inbox while the server session/bounded record remains. Sending clients need no mod.

## Public replies as the game account

Start with replyChannel=server for default public replies. An active relay needs stop/start to change that default, without restarting the host. Status reports replyChannel. A single reply can override it:

```json
{"action":"reply","replyTo":"0123456789","channel":"server","text":"Output checked; continuing the task."}
```

Public replies use chat.send/native player packets from the actual agent account, normal formatting and server mute/plugins/rate limits. They do not impersonate another player or use server say broadcast. Limit: 100 UTF-16 characters, no slash commands/control characters/formatting codes, no automatic flood of split messages. channel=private overrides one reply. Status notifications stay private; ordinary server chat does not automatically enter the agent.

The mailbox claims a one-time marker, rechecks the same client session/server connection and sends. Duplicate calls, timeout, reload or disconnect do not resend. duplicateSuppressed means already claimed, not proven delivery. Submitted/replied proves packet submission; actual display needs current chat evidence. Repeated public replied notifications only log, while inbox status remains; private bodies and errors still display. Restart invalidates old bindings.

With a stale host catalog, the existing CLI can invoke the same native integration in the intended chat:

```text
python -m minecraft_codex_bridge.dialogue start
python -m minecraft_codex_bridge.dialogue start --channel server
python -m minecraft_codex_bridge.dialogue status
python -m minecraft_codex_bridge.dialogue reply --reply-to 0123456789 --text "Acknowledged"
python -m minecraft_codex_bridge.dialogue stop
```

Use the installed interpreter and explicit MCB_RUNTIME_FILE for the intended instance, replacing the example reply reference. The CLI inherits host channel/chat identity and refuses terminals lacking it. The existing scripts/mcb.ps1 Profile/Call entry also supports mcb_dialogue. The CLI remains bound to that chat and instance.

## Transport and lifecycle

Ordinary input reuses host App Tools tools/list and tools/call over named pipes, with chat listing/messaging. dialogue_ipc connects the existing Windows codex-ipc pipe, handshakes, discovers ownership and submits thread-follower-steer-turn for the fixed local chat. No toolOutput is included, avoiding a start-turn interpretation.

The adapter only handshakes, discovers ownership, reads setting capabilities and submits authorized steering. It does not register ownership, copy/subscribe to chat history, start another app-server, edit settings/approvals/desktop files/databases or use OS input.

The relay helper waits one second after each poll; an empty queue does not query the host. The mod reuses Forge networking, identity/OP guards and server tick queues; commands need no new Mixin.

An instance OS lock prevents duplicate helpers. State under `%LOCALAPPDATA%/MinecraftCodexBridge/dialogue/` excludes tokens/player text. Compatible worker reload does not resubmit. Full frontend shutdown may terminate the process group; the server lease expires within 60 seconds. Client/server/host restart requires a fresh dialogue start, without automatic client/world/chat switching or task resumption. Helper-code changes use stop/start.

## Limits and acceptance

- Up to 32 pending messages and 128 session records; one message/player/second, at most 1024 characters. Vanilla's 100-character input limit remains. Ordinary queued input waits up to one hour.
- Pages hold at most four ordinary and four steer messages, bounding packets and avoiding starvation. Unicode/case remain intact.
- Persist dispatching before submission; valid host confirmation records delivered. Disconnect/timeout is unknown, without resend. A correlated actual reply may resolve uncertainty.
- Ordinary submission uses the latest observed idle state; no atomic expected-idle parameter exists. Concurrent desktop input follows host rules. An ordinary message starting a turn does not redirect a steer originally received while idle.
- The desktop owner selects its active turn when handling the request and supplies expectedTurnId, not at the player's sending instant. Steering never creates an idle turn. Timeout, unfamiliar errors or mismatched receipts remain uncertain without retry/fallback.
- Discovery must identify the target in the supported local list (recent 50 or pinned). Unsupported/invisible/permission-changed targets stop; no substitute is chosen.
- Desktop IPC is an internal versioned integration, not a stable public API. Record actual desktop/bundled app-server versions separately from PATH CLI, and revalidate after upgrades.
- Steering acceptance requires delivery into the original active turn before it ends, with a matching native turnId. Delivery alone is insufficient. Integrated and dedicated-server/multiple-player acceptance remain separate.

Reference: [App Server input and steering](https://learn.chatgpt.com/docs/app-server). The desktop owner calls the native interface; BridgeNH creates no additional app-server WebSocket.

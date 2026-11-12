# Native client control: open capability gaps

[English](NATIVE_CONTROL_GAPS.md) | [简体中文](NATIVE_CONTROL_GAPS_zh.md)

The acceptance target is a complete agent-operated client workflow through native BridgeNH tools, without Computer Use, OS input or a human completing missing steps. This register separates source/documentation findings from explicitly scoped live acceptance. Check current advertised capabilities before using an entry.

## Reporting and completion

Use the packaged [gap template](../mcp-server/src/minecraft_codex_bridge/guides/workspace.md#native-control-gap-and-feedback-template) for evidence and recovery.

A fix is complete only after the intended native path verifies actual effects in its stated environment. Mocked tests and manual/Computer Use success alone leave native acceptance open. Preserve failed/unknown action identities; do not retry under a new ID to bypass the recorded result.

## Verified native paths

NEI bookmark management was verified through the installed stdio MCP on **GT New Horizons 2.9.0 (Minecraft 1.7.10, NotEnoughItems 2.8.171-GTNH)**: item/recipe creation and deletion, complete same-group recipe deletion, group creation/removal, ordering, cross-namespace group moves, view/crafting modes, collapse, quantities, retention and task cleanup. Full client restart verified native saved namespaces, groups, item NBT and demand. Cleanup preserved pre-existing bookmarks and actual inventory; transient ownership became untracked after restart. This acceptance covers these tested operations, not every NEI gesture or version.

Tool-catalog hot reload was verified on **Codex desktop 26.1103.7773.0**, with `mcp_tool_list_changed` enabled, on 2026-11-11. After `mcb_backend_restart`, a newly added read-only probe appeared in the current chat's native tool catalog and was called successfully. A second worker reload removed it from that same catalog. The frontend process and conversation binding remained unchanged; neither Codex nor Minecraft restarted, and the probe made no game calls. Temporary runtime changes were fully restored. This closes rediscovery acceptance for the tested host; other hosts still need to support MCP list-change notifications. Stable frontend, initialization-instruction or dependency changes still require MCP reconnection.

## Open gaps

| Area | Current boundary | Required native acceptance |
| --- | --- | --- |
| NEI favorite trees and remaining gestures | Favorite recipe-tree creation and some modified gestures remain outside the bookmark-management actions. Ordinary `mcb_gui_key` characters cannot emulate physical shortcuts. | Expose the required native action with current identities, bounded scope and readback when a task needs it. |
| Third-party GUI coverage | Supported widget adapters do not cover every mod screen/control. Missing coverage is unknown, not an empty screen. | Reproduce the missing control, add native observation/action where feasible, verify persistence/effects through the agent-facing path without external input. |
| Prism prelaunch dialogs | Windows acceptance with Prism 11.0.3 recorded foreground and keyboard-focus events from launcher dialogs before Java starts, even when before/after foreground handles were unchanged. Game `windowMode` controls the client SDL window. Prism has no quiet-dialog launch option in its [CLI declaration](https://github.com/PrismLauncher/PrismLauncher/blob/11.0.3/launcher/Application.cpp). | A supported launcher-side path must preserve focus without changing a shared Prism process or suppressing another client's dialogs. Game-window acceptance does not close this gap. |

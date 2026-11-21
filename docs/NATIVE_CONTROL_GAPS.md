# Native client control gaps

[English](NATIVE_CONTROL_GAPS.md) | [简体中文](NATIVE_CONTROL_GAPS_zh.md)

## Open gaps

| Area | Current limit |
| --- | --- |
| Mouse capture in an inactive window | A minimized Windows client can retain a mouse grab. For an explicit handoff, read `releasedExistingGrab` and check that keyboard focus stays with the foreground application. |
| NEI bookmarks | The bookmark actions do not create favorite recipe trees or expose every modified gesture. `mcb_gui_key` sends characters and cannot invoke a physical shortcut. |
| Third-party GUI controls | Some mod screens expose only part of their widget tree. If coverage is incomplete, the state of an omitted control is unknown. |
| Action recovery | `mcb_action_recovery` covers returned standalone creative ItemBlock placements. A survival material order reconciles its own pickups and transfers by order ID. Missing pickup history or missing server transfer evidence leaves the step pending. Other survival consumption needs operation-specific evidence. |
| Prism prelaunch dialogs | On Windows, Prism 11.0.3 dialogs can take keyboard focus before Java starts. `windowMode` applies to the game window. The [Prism CLI](https://github.com/PrismLauncher/PrismLauncher/blob/11.0.3/launcher/Application.cpp) has no option to suppress those dialogs. |

Record the missing native operation, affected environment and recovery steps in the [workspace gap template](../mcp-server/src/minecraft_codex_bridge/guides/workspace.md#native-control-gap-and-feedback-template). Preserve the original action ID if its outcome is unknown. Close the entry when the native operation produces the intended game state in the affected environment.

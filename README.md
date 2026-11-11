# BridgeNH

[English](README.md) | [简体中文](README_zh.md)

A local MCP bridge for **GT New Horizons / Minecraft 1.7.10**, connecting MCP agents such as Codex to the Minecraft client for structured observations and native player operations.

**This project was completed 100% by GPT. Humans only provided the test content and paid for tokens and electricity.**

- Read world, inventory, GUI, NEI recipe and AE network state.
- Move, interact, build, organize items and craft through supported native tools, with receipts and diagnostics.
- Bind each chat to its explicitly selected client.
- Use native window controls, screenshots, player dialogue and supported tool-catalog hot updates.

## Install and use

Automatic installation supports **Windows, Python 3.11+, Codex, and a Minecraft 1.7.10 Forge client managed with Prism Launcher**.

Client prerequisite mods: **UniMixins, StructureLib and lwjgl3ify**.

1. Download **`BridgeNH-<version>-windows.zip`** from the project's release assets, extract it and run `install.cmd`.
2. Specify the **Prism directory** using its actual location.
3. Specify the **instance name** of the selected client.
4. Start that client after installation and explicitly bind it in the chat that will operate it. Each new chat selects independently.

Replace the placeholders before requesting binding:

```text
Prism directory: <actual Prism directory>
Instance name: <actual instance name>
Bind this chat to that client.
```

See the [installation guide](INSTALL.md), [player permissions and operation policies](docs/PERMISSIONS.md), and [player dialogue](docs/PLAYER_DIALOGUE.md).

Agent rules, including documentation language, are in [AGENTS.md](AGENTS.md). The [workspace guide](mcp-server/src/minecraft_codex_bridge/guides/workspace.md) provides a reusable startup template and recordkeeping conventions.

Find every public tool in the [capability index](mcp-server/src/minecraft_codex_bridge/guides/capabilities.md), also available offline with `mcb_help({"topic":"capabilities"})`. The [maintenance guide](mcp-server/src/minecraft_codex_bridge/guides/maintenance.md), `mcb_help({"topic":"maintenance"})`, explains hot restart, tool-directory refresh and reconnection. Query `mcb_help({"topic":"tools","tool":"mcb_nei_bookmark_action"})` for an exact current worker-tool schema.

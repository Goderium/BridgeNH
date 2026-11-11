# BridgeNH MCP adapter

[English](README.md) | [简体中文](README_zh.md)

BridgeNH connects MCP clients to a local Minecraft 1.7.10 / GT New Horizons client.

Python 3.11 or newer is required. The companion BridgeNH Forge mod must be
installed in the selected game instance. Every supervised MCP connection starts
unbound. Supply the Prism data directory as `prismRoot` to `mcb_clients`, then
bind with separate `prismRoot` and `instanceName` fields in `mcb_connection`.
Names are matched only within that directory; ambiguous names require explicit
selection. Each conversation retains its own binding.

The Windows release ZIP includes the mod, installer and complete installation
guide. Installing this Python package alone does not install the mod or register
an MCP server. The `minecraft-codex-bridge-setup` command supports configuration
with an existing runtime. Run it with `--help` for options.

`mcb_help` and guide resources serve English; `_zh.md` companions are for human review. Use `mcb_help({"topic":"workspace"})` for documentation rules and a reusable startup template.

Start feature discovery with `mcb_help({"topic":"capabilities"})`, which indexes every public tool. Use `mcb_help({"topic":"maintenance"})` for hot reload and reconnect boundaries, and `mcb_help({"topic":"tools","tool":"mcb_nei_bookmark_action"})` for a complete current worker-tool schema.

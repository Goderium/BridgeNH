# BridgeNH

[English](README.md) | [简体中文](README_zh.md)

面向 **GT New Horizons / Minecraft 1.7.10** 的本地 MCP 桥接模组，让 Codex 等支持 MCP 的 Agent 连接客户端，读取结构化状态并执行原生玩家操作。

**本项目 100% 由 GPT 完成。人类只提供了测试内容，以及 token 和电费。**

- 读取世界、背包、GUI、NEI 配方和 AE 网络状态。
- 通过受支持的原生工具移动、交互、建造、整理物品和合成，并保留回执与诊断。
- 每个对话明确选择并绑定自己的客户端。
- 提供原生窗口控制、截图、玩家对话及受支持的工具目录热更新。

## 安装与使用

自动安装支持 **Windows、Python 3.11+、Codex，以及使用 Prism Launcher 管理的 Minecraft 1.7.10 Forge 客户端**。

客户端前置模组：**UniMixins、StructureLib、lwjgl3ify**。

1. 从本项目发布附件下载 **`BridgeNH-<版本>-windows.zip`**，解压后运行 `install.cmd`。
2. 指定 **Prism 目录**，填写实际位置。
3. 指定 **实例名**，填写要绑定的客户端名称。
4. 安装完成后启动该客户端，在需要操作它的对话中明确绑定。每个新对话都独立选择。

替换占位内容后，可以直接发送：

```text
Prism 目录：<实际 Prism 目录>
实例名：<实际实例名>
请将当前对话绑定到这个客户端。
```

详细步骤见[安装说明](INSTALL_zh.md)。玩家请阅读[权限组与操作策略](docs/PERMISSIONS_zh.md)和[玩家对话](docs/PLAYER_DIALOGUE_zh.md)。

Agent 约定及文档语言规则见英文 [AGENTS.md](AGENTS.md)；[工作目录指南（中文）](mcp-server/src/minecraft_codex_bridge/guides/workspace_zh.md)提供可复制的启动模板和记录规范。

全部公开工具见[能力索引](mcp-server/src/minecraft_codex_bridge/guides/capabilities_zh.md)，对应离线入口为 `mcb_help({"topic":"capabilities"})`。[维护指南](mcp-server/src/minecraft_codex_bridge/guides/maintenance_zh.md)说明热重启、工具目录刷新和重连，对应 `mcb_help({"topic":"maintenance"})`。准确的当前 worker 工具 schema 可用 `mcb_help({"topic":"tools","tool":"mcb_nei_bookmark_action"})` 查询。

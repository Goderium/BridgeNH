# BridgeNH MCP 适配器

[English](README.md) | [简体中文](README_zh.md)

BridgeNH 将 MCP 客户端连接到本地 Minecraft 1.7.10 / GT New Horizons 客户端。

需要 Python 3.11 或更新版本，目标客户端需已安装配套 BridgeNH Forge 模组。每个受管理的 MCP 对话连接启动时未绑定；先向 `mcb_clients` 提供 `prismRoot`，再用独立的 `prismRoot` 和 `instanceName` 字段调用 `mcb_connection` 绑定。名称仅在所选目录内匹配，重名需明确选择，每个对话保留自己的绑定。

Windows 发布 ZIP 包含模组、安装器和完整安装说明。单独安装 Python 包不会完成模组安装或 MCP 注册；已有运行环境可用 `minecraft-codex-bridge-setup` 配置，参数见 `--help`。

`mcb_help` 和指南资源提供英文，`_zh.md` 供人工阅读。文档规则和可复制的启动模板通过 `mcb_help({"topic":"workspace"})` 获取。

查询工具功能时，使用 `mcb_help({"topic":"capabilities"})`，其中按用途列出全部公开工具。热重载与重连边界见 `mcb_help({"topic":"maintenance"})`；完整的当前 worker 工具 schema 用 `mcb_help({"topic":"tools","tool":"mcb_nei_bookmark_action"})` 查询。

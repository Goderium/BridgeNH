# BridgeNH 安装说明

[English](INSTALL.md) | [简体中文](INSTALL_zh.md)

每个客户端只安装一个兼容的 BridgeNH 模组 JAR，不要与旧 MCB JAR 同时安装。

## 下载与安装

下载发布附件 **`BridgeNH-<版本>-windows.zip`**，解压后双击 `install.cmd`。不需要管理员权限；PowerShell 执行策略仅对安装进程生效，不修改整机策略。发布包没有代码签名，请从可信来源下载并核对 SHA-256。

自动安装支持 Windows、已有的 Minecraft 1.7.10 Prism/Forge 客户端、带 pip/venv 的 Python 3.11+ 和 Codex。客户端前置模组：**UniMixins、StructureLib、lwjgl3ify**。安装 Python 依赖时需要访问配置的软件源；ZIP 不包含离线 Python 环境。

Horizon-QA/TST-QA 为可选测试集成，普通使用不需要；安装器不会自动安装或开启。相关验证参考[分层测试指南](mcp-server/src/minecraft_codex_bridge/guides/testing_zh.md)。

安装器先要求 **Prism 目录**，再要求该目录内的 **实例名**。可使用显示名或实例文件夹名；缺失、重名时需要明确选择，不按最近启动/修改时间猜测。缺少 Python 时先安装 Python 3.11+，或指定已有解释器。自动启动沿用便携 Prism 支持范围；自行启动时使用 `-ManualLaunch`。

安装器创建独立 MCP Python 环境、验证导入、安装模组并登记一个 stdio MCP 服务。它复用已有全局/当前/已知项目中的 MCB 注册，保留无关配置和工具审批覆盖；存在多份注册时明确选择目标，不提升审批权限。新对话仍需显式绑定，安装 profile 不是默认客户端。

需要替换 JAR 时，先正常关闭**选定的客户端**；相同 JAR 不重复替换。旧 JAR 备份到该实例 `.minecraft/config/codexbridge/mod-backups/`，其他模组不替换。配置修改前生成带时间的 `.bak`。依赖安装失败不会登记不可用的 MCP；后续阶段失败时，已成功完成的阶段可能保留。查看 `logs/install.log`；源码安装使用忽略目录 `build/logs/install.log`。修正错误后重新运行同一安装器。

安装后启动所选客户端。修改配置不会替换当前正在运行的 MCP 进程；按宿主支持使用重连或新连接。只有宿主确实要求时才考虑重启 Codex。兼容 Python worker 修改继续使用热重载。

## 让 Agent 安装

可以说：“从这个解压目录安装 BridgeNH。” Agent 先执行只读检查：

```powershell
.\install.ps1 -Inspect -NonInteractive
```

输出为 JSON。`status=needs_input` 时，`questions` 包含稳定 key、问题和可选 choices；只补充缺失内容，不重新询问有效配置，也不猜客户端。以下占位符需替换后使用：

```powershell
.\install.ps1 -Inspect -NonInteractive -PrismRoot '<Prism directory>' -InstanceName '<instance-name>'
.\install.ps1 -NonInteractive -PrismRoot '<Prism directory>' -InstanceName '<instance-name>'
```

Python 对应 `--prism-root`、`--instance-name`。旧 `-Instance '<full instance path>'` 与可选 `-Prism '<prismlauncher.exe>'` 保持兼容，不能与新的选择字段混用。

其他参数包括 `-Python`、`-Profile`、`-Config`、`-CodexConfig`、`-Scope user`、`-Jar`、`-ManualLaunch`。已有环境可用 `-ConfigureOnly -RuntimePython '<existing MCP python.exe>'` 仅配置，不重新安装模组/Python。退出码 0 为成功/就绪，2 为缺输入，1 为失败；非交互方式保留结构化结果。

已有通用脚本入口：

```powershell
.\scripts\mcb.ps1 -Profile default -Action Snapshot
.\scripts\mcb.ps1 -Profile default -Action Call -Tool mcb_health
```

删除解压目录后，已登记的 MCP 仍可工作。安装解释器支持 `-m minecraft_codex_bridge.setup --show-config`，也可在明确 profile/runtime 选择后使用 `-m minecraft_codex_bridge.tool_cli mcb_snapshot --transport in-process`。这些是独立临时 CLI 连接，不是当前聊天的运行绑定。

## Agent 工作区与知识库

项目约定及文档语言规则见 [AGENTS.md](AGENTS.md)。[工作目录指南](mcp-server/src/minecraft_codex_bridge/guides/workspace_zh.md)提供启动模板、任务记录和原生控制反馈；将相关规则合入选定项目，笔记保存在安装目录和游戏存档之外。

安装检查/完成 JSON 的 `agentGuide` 提供源文件和 `mcb_help({"topic":"workspace"})` 入口，固定资源为 `minecraft-codex-bridge://agent-guides/workspace`。删除解压目录后，指南仍可从已安装 Python 包离线读取，无需游戏会话或令牌。

## 配置位置与对话绑定

| 安装方式 | 配置 | Python 环境 |
| --- | --- | --- |
| 源码目录 | `<checkout>/.mcb.local.json`，Git 忽略 | `<checkout>/mcp-server/.venv/` |
| 发布包 / MCP 与模组 | `%APPDATA%/MinecraftCodexBridge/config.json` | 同级 `runtime/` |

显式 `MCB_CONFIG_FILE` 或 `-Config` 优先。两种方式使用相同 version 1 格式。安装器/独立 CLI 使用 activeProfile，`MCB_PROFILE` 或 `-Profile` 可另选；仅用作客户端清单时 activeProfile 可省略/null。路径可以相对配置文件。配置不要写入凭据；模组仍在所选实例 `.minecraft/config/codexbridge/runtime.json` 生成/轮换令牌。

```json
{
  "version": 1,
  "activeProfile": "default",
  "profiles": {
    "default": {
      "prismRoot": "<Prism directory>",
      "instanceName": "<instance-name>"
    }
  }
}
```

示例仅说明字段，必须填写实际值。instanceName 是名称而非路径；安装器保存解析后的实例文件夹名。旧 instancePath/prismExe profile 可读，但不能把 instancePath 与新字段混在同一 profile。

聊天 MCP 启动时未绑定。先用 `mcb_clients(prismRoot=...)` 列举，再用 `mcb_connection(action="bind", prismRoot=..., instanceName=..., ...)` 绑定，同时提供实际对话 ID、当前绑定代数及新请求 ID。清单中的 clientId 是另一种选择方式，不能和目录/名称混用。选择不会回退到其他目录或最近使用客户端，状态会报告实际解析出的路径。

宿主须为每个对话启动独立 stdio 前端。若启动未提供 CODEX_THREAD_ID，首次成功显式绑定会固定调用方声明的真实对话 ID，标记 ownerSource=explicit_bind_request；必须从实际宿主环境取得，不能编造或借用。之后身份不符会被拒绝，解绑也不会清除前端归属。原客户端有未解决工作时不允许切换；兼容 worker 重载保留绑定，完整前端重连重新绑定。

独立进程内适配器保留旧 MCB_INSTANCE_PATH、MCB_RUNTIME_FILE 或 MCB_URL/MCB_TOKEN 入口；不要在外部 CLI 留下过期选择，它们也不会重定向运行中的聊天。项目仍需有 MCP 注册访问权限，绑定不会代改宿主配置。

安装选择与[玩家权限组/操作策略](docs/PERMISSIONS_zh.md)相互独立；任何一个都不授予游戏权限。

## 源码构建与发布

在项目根目录用 **JDK 25** 运行 Gradle。构建自动解析 Java 8 编译工具链，以及固定的 StructureLib、UniMixins、RetroFuturaBootstrap、Gson 和 ASM API；无需本机游戏目录，这些 API 不打进模组。

```powershell
.\gradlew.bat --no-daemon build
python -m pip install -e ".\mcp-server[test]" "build>=1.2,<2"
python -m pytest mcp-server/tests
python -m build --outdir build/dist mcp-server
python scripts/prepare_release.py
```

运行 JAR 位于 `build/libs/BridgeNH-<version>.jar`，发布产物和校验和位于 `build/dist/`。构建后可用 `install.ps1 -Scope source` 配置开发安装。

同步维护 gradle.properties 的 modVersion 和 Python `_version.py` 的版本。发布验证拒绝版本/tag 不匹配。Actions 构建检查 push/PR；匹配的 `v<version>` tag 还会创建包含 JAR、Windows ZIP、Python 分发和校验和的 GitHub Release；预发布版本标为 prerelease。

ZIP 使用明确文件清单，包含安装器、Python 包、运行 JAR、使用说明及占位示例，排除本机配置、令牌、存档、日志、缓存和虚拟环境。manifest.json 记录文件哈希，ZIP 另有 `.sha256`。

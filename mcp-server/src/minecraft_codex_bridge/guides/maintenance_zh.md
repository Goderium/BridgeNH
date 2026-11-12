# BridgeNH 维护与工具发现指南

[English](maintenance.md) | [简体中文](maintenance_zh.md)

供人工阅读和审核；Agent 使用英文入口，完整规则见[启动模板](workspace_zh.md#可复制的启动提示词)。

`mcb_help({"topic":"maintenance"})` 可离线读取英文指南。先用[能力索引](capabilities_zh.md)选择工具，再查询实时 schema。

## 哪些修改可以热重载

| 修改 | 支持的路径 | 还需核对 |
| --- | --- | --- |
| 兼容的 Python worker 逻辑、工具增删/schema、包内指南文本/资源 | 将已审核修改部署到所注册运行目录，再调用 `mcb_backend_restart` | 前端/绑定不变、新 worker、状态交接、实际目录/指南与相关调用 |
| 稳定 MCP 前端/监督器、初始化说明、Python 解释器或依赖 | 在获准更新后重新连接 MCP 服务；worker 重载不能替换自己的前端 | 新前端不默认绑定，需重新明确绑定本对话；MCP 重连与重启 Codex 应用不是同一动作 |
| Java 模组代码或客户端运行依赖 | 正常保存退出并重启所选、获准的 Minecraft 客户端 | 新会话、实际执行版本/能力与就绪状态；磁盘 JAR 已替换并不足够 |
| 宿主不支持目录刷新，或宿主功能设置改变 | 单独排查宿主支持和有效配置 | 服务端发通知不代表宿主已暴露工具；遵守宿主重启限制 |

前端从自身所注册包目录建立 worker 快照。修改另一份源码目录不会更新已安装 wheel，热重启也不会切换安装目录。部署前确认当前连接实际使用的注册/runtime，区分源码、已安装运行目录和分发包；修改文档不应附带未经授权的重新打包或发行版重装。

兼容重载保留对话绑定、AE 订单阶段和未确定动作记录，不调用游戏，不要求 Minecraft 运行，不自动续做或重放动作。交换 worker 仍需完整、有效的状态交接；已有调用必须先结束。切换客户端是另一项独立操作。

## 执行并验收 worker 热重启

1. 验证待更新内容，等待当前工具调用结束。读取 `mcb_backend_status({})`，保留 `frontendPid`、`workerPid`、`generation`、绑定的对话/客户端和必要的待定动作状态。
2. 用新 `requestId` 和当前 `expectedGeneration` 调用一次 `mcb_backend_restart`。`timeout_ms` 默认 30000，允许 1000..45000。可选 `expectedSourceFingerprint` 指**候选新源码**指纹，改源码后不要直接填旧 worker 的指纹。
3. 检查真实回执的 `ok`、`state="running"`、`connectionPreserved=true`，确认前端/绑定未变、worker PID 已更换、代数增长；核对订单/失败动作交接数量和 `catalogUpdate`。`gameReadiness="not_observed"` 正常，因为重载没有查询游戏。
4. 工具增删/schema 变化时，检查 `catalogGenerations` 和后续状态中的 `catalogNotification`。通知成功、`lastListedGenerations.tools` 更新至当前代数仅证明协议进度，还不代表模型可见列表已刷新。
5. 检查宿主实际原生工具列表/schema，并在获准范围调用新增或变更工具；删除工具时核对宿主列表已移除。仅改指南时，直接读取变更后的 `mcb_help` 内容，不为离线指南验收重启游戏。
6. 分别记录激活、宿主刷新、真实调用和剩余限制。若用了临时验收探针，删除后核对运行文件和目录已恢复；探针不是日常重载的必需前置。

热重启回执丢失或结果不确定时，先读状态。以相同请求 ID 和完全相同参数读取已记录的重启结果，不会再次重启；同 ID 改参数会拒绝。明确拒绝且原因已修正后，新尝试使用新 ID 和最新代数。这项重启结果查询规则不授权重放游戏动作。

**已验证范围：**2026-11-11，在启用 `mcp_tool_list_changed` 的 Codex 桌面版 26.1103.7773.0 上，新加入只读工具经热重启出现在当前对话的原生目录并成功调用，后续 worker 热重启又同步移除该工具。前端和绑定保持不变，没有重启 Codex/Minecraft，也没有游戏调用。其他宿主版本仍需观察自身支持情况。

## 发现与调用当前工具

[能力索引](capabilities_zh.md)解释全部随包工具的用途，准确可执行 schema 以当前 worker 为准。将以下对象传入 `mcb_help`：

```json
{"topic":"tools","query":"bookmark","limit":20}
```

`query` 按名称不区分大小写匹配。查询一个工具的完整契约：

```json
{"topic":"tools","tool":"mcb_nei_bookmark_action"}
```

读取后续页时，将返回的 `nextOffset` 作为 `offset`，保留相同 query，并将 `catalogFingerprint` 传为 `expectedCatalogFingerprint`；`hasMore=false` 后停止。目录变化错误表示需要重新开始发现，不得合并不同目录版本的分页结果。单工具查询返回完整 `inputSchema` 和 annotations，列表摘要可能缩短。

这些查询不调用 Minecraft，无需在线游戏。`mcb_backend_status`、`mcb_backend_restart`、`mcb_clients`、`mcb_connection` 是前端直接工具，不在 worker 目录中，使用宿主直接声明的 schema。目录不等于权限清单，也不保证游戏支持。

宿主仍暴露旧工具定义时，已有 `mcb_sequence` 支持对当前公开 worker 工具执行**一次受保护兼容调用**。它可使用新实现，但不能据此宣称原生目录已刷新：

1. 以 `mcb_help(topic="tools",tool=...)` 查询完整最新 schema，并核对拟用参数。
2. 从回执读取 `data.invocation.callGuards`；只有已绑定的监督式连接提供它。使用当前对话绑定，不编造 ID、不借另一对话校验值。
3. 把原样 guard 字段与 `call:{"tool":<返回的工具名>,"args":<已核对参数>}` 一并传入 `mcb_sequence`。这是单调用封装，不是普通 `steps` 计划，不能混用两种形式。
4. 检查目标实际结果/错误，沿用它的验收和不重放规则。权限、接管、时限、日志与不确定状态仍然有效；前端管理工具不支持这种路由，`mcb_sequence` 不能递归调用自己。

`mcb_help` 的 worker 工具指纹与 `mcb_backend_status` 的监督器完整契约指纹**不能互换**。原样使用返回的 `callGuards`，重载或重绑后刷新。`TOOL_CATALOG_CHANGED`、`BINDING_GENERATION_CHANGED` 是派发前拒绝；目标动作若已产生未知效果，仍须核对，不得通过更换请求重新执行。

## 核对已有回执的创造模式放置

`BACKEND_ACTION_REPLAY_REQUIRES_RECONCILIATION` 表示当前 worker 保留了效果未确定的匹配动作。游戏会话变化或 worker 重启不会清除保护。在保留原动作的同一已绑定 MCP 连接中使用 `mcb_action_recovery`：

1. 调用 `{"action":"list"}`，按工具及参数选择原始 `actionId`。此步骤离线执行，不导入其他对话的记录。
2. 调用 `action="inspect"`，提供该 `actionId`、当前 `expectedSessionId`、原 `expectedWorld` 和 `dimension`。原 `expectedWorld` 必须与当前准确存档目录名一致，仅显示名称匹配不足以通过检查。检查该本地世界中健康的创造模式玩家、空闲输入、空光标、客户端与服务器完整背包及 NBT 一致、原目标为空且原相邻支撑仍存在。此步骤保留重放保护。
3. 核对返回的当前 `sourceItem` 和新放置意图。调用 `action="reconcile"`，提供相同保护字段、返回的 `evidenceFingerprint` 和 `acknowledgement="allow_new_placement_after_verified_reset"`。重新进行原生读取，证据必须与指纹一致；证据变化或不完整时保留保护。
4. `reconciled` 回执记录允许另行请求新放置。恢复本身不修改游戏状态。通过原工具明确提交预期放置，并核对服务器效果。后续放置若再次产生不确定结果，将成为新的未解决动作，即使参数和回执与旧动作相似。

此路径仅支持已有正常回执的独立创造模式 ItemBlock 回调：目标原本为空，未预计消耗物品，且不涉及替换、攻击、空中使用回退、部件、跳跃放置或精确附着约束。传输结果未知、生存模式消耗及其他动作类型尚不支持。旧回执不保留原物品 NBT；当前背包一致只能确认当前拟用物品，不能证明历史物品守恒或原动作结果。`originalOutcomeKnown=false` 保持明确。读取非原子，核对是重复观察，不锁定后续游戏状态。

核对只解除该动作的匹配重放保护，不清除 worker 保守保留的未分类结果标记、不授权切换客户端，也不解决其他动作。回执随兼容 worker 交接保留；最多保存 64 条记录，新增未知动作前可淘汰已核对记录。不得编辑内部记录来恢复动作。

## 按失败层定位

| 观察 | 含义与下一步 |
| --- | --- |
| `BACKEND_BUSY_NOT_DISPATCHED` | 有 worker 调用未完成；等待并查状态，不为重载中断或重放它 |
| `BACKEND_GENERATION_CHANGED` | 观察的代数已过时；读最新状态，先核实已激活的版本与内容 |
| `BACKEND_FRONTEND_RECONNECT_REQUIRED` 或初始化/运行环境不兼容 | 修改越过稳定边界；完成独立验证，按授权重连 MCP，重复 worker 重载无效 |
| 候选/schema/状态交接验证失败 | 检查准确拒绝和当前 worker 状态，不宣称激活成功、不丢弃结果未知的操作记录、不重放动作，先修正变更 |
| 重启成功，但目标代码/指南未更新 | 核对实际更新了哪个 runtime、源码指纹和已加载目录/内容；改另一目录不等于部署 |
| `catalogUpdate` 已变化，宿主工具目录仍未更新 | 区分通知失败、尚未重新列举与宿主/模型列表仍缓存；支持时可用当前 schema 和兼容调用，不能仅凭服务端回执宣称原生刷新 |
| 重载成功后游戏离线 | 不属于重载失败；可以继续离线指南/状态工作，需要游戏时只启动明确选择且获准的实例 |

现有前端进程可能未实现目录通知，需要一次获准 MCP 重连才能加载该前端能力，worker 热重载不能更新该前端实现。完成后，后续兼容 worker/目录变更可使用热重启。

## Codex 宿主配置与禁用控件

先检查有效配置来源，不要直接判断 MCB 强制开启。部分 Codex 桌面版本会把**来自项目配置的 MCP 条目设为只读**，同时禁用齿轮和启用开关。用户级配置、项目覆盖与 BridgeNH 每对话客户端绑定是不同层次。

在已获准的配置修复范围内，把注册移到用户配置时，应完整保留注册/选项，移除冲突项目覆盖，并同步安装器的 `installation.codexConfig`；事先备份并比较无关设置。每个对话仍明确绑定，移动注册不选择默认 Minecraft 客户端，也不替换已有运行前端。

宿主对 `mcp_tool_list_changed` 的支持与桥接自身能力分开。核对有效设置和已加载行为。设置改变或旧宿主可能需要宿主重启；任务禁止时报告边界，仅暂停依赖步骤。

不要假设宿主设置中的 **Restart** 只重连单个 MCP 服务：已检查的 Codex 界面实际指向宿主 app-server。尊重其他对话与用户的重启约束；支持的单连接 worker 更新使用 `mcb_backend_restart`。

## 客户端启动与绑定

启动器 CLI 接受 `--window-mode background|minimized|normal`，默认 `minimized`，最小化至任务栏并保留图标，不能与 `--status` 同用。所选客户端 JAR 须提供窗口控制 v2，启动后通过 `mcb_window` 验收。游戏窗口建立前，Prism 自身的对话框仍可能获得焦点。

新 MCP 前端从未绑定开始，而明确绑定需要认证在线客户端 health。因此，关闭的客户端不能完成首次在线绑定；安装 profile 和最近使用实例仍只是清单，不是默认绑定。

获准首次启动时，使用注册 runtime 的 Python 执行已安装共享启动器 `python -m minecraft_codex_bridge.launcher_cli`，明确给出所选 Prism 实例的 `--prism`、`--instance`、`--runtime` 路径；源码包装入口是 `scripts/launch_client.py`。它与 `mcb_launch` 使用相同的有界固定实例启动逻辑，不自动登录、不注入系统键鼠、不创建世界。不得使用未经核实的路径或改选其他客户端。

一次启动派发后仍未就绪，只续查其只读 `--status --timeout-ms 30000`。在线 health 就绪后，用本对话实际 ID 和当前绑定代数绑定，再读 `mcb_health`。已有绑定可跨正常客户端退出/启动及凭据轮换保留，使用该绑定的 `mcb_launch(action="status")` 或已获准启动，不切换实例。载入、离开、退出的 scheduled 回执仍各自需要完成证据。

## 维护工具时同步帮助

新增或修改工具时，更新[能力索引](capabilities_zh.md)对应行、详细指南及 `_zh.md` 译文。文风、说明范围、共性规则与示例遵循项目的[文档准则](../../../../AGENTS.md#documentation-standards)。保留工具特有前提和验收边界，固定指南主题返回英文。

核对公开索引覆盖当前目录及前端工具，检查本地链接、UTF-8、去敏、离线读取，以及英文指南的 32 KiB 原始/序列化限制，复用相关已有回归。获准更新已安装指南后，热重载当前连接并读取实际新版主题。源码修改、运行目录部署、分发打包和发布是独立阶段，按实际完成范围汇报。

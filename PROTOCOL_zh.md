# BridgeNH 协议 v1：中文说明

[English](PROTOCOL.md) | [简体中文](PROTOCOL_zh.md)

本文件供人工理解和审核说明性约束。Agent 读取英文协议；实际 operation、字段、schema 和调用示例保留[英文主协议](PROTOCOL.md)及源码定义，不另造中文接口。目标为 Minecraft 1.7.10 Forge / GT New Horizons。

## 传输、取消与绑定

本机 HTTP 绑定 127.0.0.1，默认端口 25671，可配置。POST /v1/call 使用 JSON 与 Bearer 会话令牌，请求包含 requestId、operation、args；响应保留 requestId，成功提供 data，失败提供 error.code/message。所有接触游戏的读取/动作都在所属游戏线程执行，请求、队列、返回、时长和扫描体积均有界。不得记录令牌或写入 MCP 标准输出。

POST /v1/cancel 使用同样认证，仅提交原 requestId，返回 cancelled/already_running/not_found。取消与队列启动之间必须原子协调；短时取消标记避免取消先到而请求后执行。已经开始的动作不会因取消回滚。超时后的尽力取消没有确认时仍是未知效果，不允许静默重试修改。

模组在游戏目录 config/codexbridge/runtime.json 写入协议版本、本机 URL 和每次启动随机生成的令牌。受管理的聊天 MCP 启动时**未绑定**，必须以实际对话 ID、当前代数及 prismRoot/instanceName 或已列出的 clientId 显式绑定；profile 和发现记录只是清单。状态及 health 的 mcpConnection 报告真实绑定。

旧独立进程内适配器保留 MCB_RUNTIME_FILE、MCB_INSTANCE_PATH、发现目录变量及 URL/TOKEN 入口，其省略/auto 发现仍可能选最新运行记录；这不是聊天绑定策略。独立调用应明确固定目标，旧环境变量不会重定向运行中的聊天。运行文件和凭据不纳入版本控制。

## 内置解释与结果含义

相关结果可以附带 source=mcb_builtin 的固定 feedback，最多四条去重说明，包含稳定 id、回执位置、含义与下一步；说明省略会显式标记。它不依赖历史知识或额外 RPC，不改原始结果、错误、图像与验收证据，不授予权限或执行回退。

仅已知代码获得明确解释，未知代码不猜根因。未知效果优先；outcome_unknown=false 不等于从未执行，sideEffectsPossible=true 也不必然是未知。只有明确未派发或队列取消确认，才能证明该请求未执行，不能证明之前动作已回滚。submitted、scheduled、completed、真实状态验收各有区别。只读部分覆盖、分页、Waila 显示缓存也不能合并成完整证据。

## 有界组合操作

`mcb_action_recovery` 是 worker 本地恢复接口，不是新的 HTTP 修改操作。`list` 离线读取保留的动作 ID；`inspect` 和 `reconcile` 复用已有的受保护 `baubles.inspect`、`world.inspect_many` 读取。核对要求当前会话、原始准确存档目录参数及维度、重复一致的客户端/服务器完整 NBT 背包与方块证据，以及明确确认。回执只解除所选创造模式放置的重放保护，历史结果仍为未知，新放置必须另行请求，其他未知动作继续受保护。详见[适用范围和流程](mcp-server/src/minecraft_codex_bridge/guides/maintenance_zh.md#核对已有回执的创造模式放置)。

观察、序列、条件等待、接近、建造、配方及 AE 等组合复用既有原语、锁、当前身份和统一期限。详细字段、白名单和具体上限保留在英文协议和当前工具 schema。组合不意味着跨 tick 原子快照，也不意味着后续动作失败时能撤回先前效果。

序列绑定来自此前完整观察；动态屏幕、窗口、真实槽、虚拟资源不得混用。动作之后的实际读取才能作为效果验收，提交回执或动作前基线不能证明完成。默认不自动翻遍所有页，不重放动作或消费过的游标。返回预算省略和元数据覆盖必须保留未知，不从短 NBT、图标或浮点近似重建身份/数量。

机器、产线、访问路线和坐标由当前任务组合，不能把一位用户的整套布局固化为通用接口。规划、目录候选、nativeDone、recipe count 或 QA testPassed 均不能单独证明结构成立、原料消耗、产物交付和停止。

## GUI、NEI 与 AE

GUI 读取只覆盖支持的公开按钮/槽位/控件，先检查 coverage。真实 ItemStack 提示优先于无 NBT 模板；高级提示不模拟实体修饰键，逻辑悬停不移动系统鼠标。每次界面/控件变化后重新取得身份。菜单/设置保存沿用原生完成路径，必要时重新打开验收。

NEI 读取当前 handler/原始配方引用及实际 PositionedStack，分页是当前可见引用而非全局配方图。配方原始消耗、概率、条件和显示超频分别记录；截断前尚未访问的输出不能视为空。语义查询、整方收藏、overlay 和面板操作必须使用各自支持参数；整方 bookmark 不等于物品级收藏。

`nei.bookmarks` 只读当前书签页、分组、可见行、精确条目及保留状态；`nei.bookmark_action` 在匹配 `screenId/contextToken` 后调用 NEI 原生方法，完成增删、分组、排序、调量、页面选择及跨页任务清理。配方书签按同组、同一命名空间中的整份配方删除，包含输入、输出和关联物品，不扩展到其他位置的独立收藏。观察上限为 64 页/4096 项，单页最多返回 128 项，单次删除最多 512 项。以 `taskId` 与 temporary/permanent/future 记录临时、永久和待用归属；自动清理保护未登记或保留项及其关联配方。明确不再需要保留项后，显式删除使用 `releaseRetained=true`。客户端/世界/原生对象重载后丢失归属的条目保持未知并保留。只请求原生保存，不以内存回读代替磁盘持久化验收；部分失败先核对，不重放。具体参数见[原生书签管理](mcp-server/src/minecraft_codex_bridge/guides/nei_zh.md#原生书签管理)。

AE 真实物品槽、网络 resourceId、VirtualMESlot 和幽灵样板各自独立。数量保持十进制 long 字符串，动作解析保留完整实际 NBT。取存、样板编辑、预览、启动、CPU 管理和远端接口操作使用正常容器/网络路径；提交后等待同步再读。订单续查沿用同一身份；服务端已交付但客户端未同步时只等待，不再次取货。CPU 取消不证明接口缓存和机器已经清空。

完整使用说明见[NEI](mcp-server/src/minecraft_codex_bridge/guides/nei_zh.md)、[AE](mcp-server/src/minecraft_codex_bridge/guides/ae_zh.md)及[通用操作](mcp-server/src/minecraft_codex_bridge/guides/operations_zh.md)。

## 世界、结构、移动及开发操作

扫描只读已加载范围，返回有界页及真实访问覆盖，未加载/未访问不等于空气。游标会过期和消费，空页可能仍有进展；续页使用实际返回值，不自行按条数推算。缓存不代替实时库存、碰撞或最终验收，也不加载新区块。

结构规划只使用公开接口。缺少控制器偏移时只能返回 piece-origin 几何，不提供猜测世界坐标。piece/variant/alignment、分页、元素候选和实际 observed 各有边界；候选不等于完整接受规则，规划不等于结构成立。布局需要覆盖整体体积、地板、空气、附属设备和访问空间。

移动依实际步行/飞行/TP 策略、游戏授权及执行后端。允许飞行不等于实际飞行；跨维度后重绑并重新取得必要的双端姿态。原生距离、身体空间、支撑、危险和射线检查保留；临时避障高度不等于抵达。开发读取、物资生成、直接后台修改及 QA 有各自能力/权限，详见[玩家权限组与操作策略](docs/PERMISSIONS_zh.md)。

## 生命周期与后台控制

`clientWindowControlsVersion=2` 增加 `minimize` 与 `resize`；后者要求 `width=256..8192`、`height=144..8192`，其他动作不接受尺寸参数。Windows 下的窗口尺寸调整、最大化及还原通过临时原生保护保留焦点及窗口层级，操作后仍可手动激活。最小化当前活动窗口时，Windows 正常选择其他前台窗口。全屏切换仍须明确请求。`confirmed` 表示原生状态或尺寸回读，客户端及帧缓冲尺寸可能在后续正常事件中更新；未知结果不得重放。`foregroundPreserved` 比较操作前后的前台窗口，用户同时切换窗口也可能使其为 false。

启动参数 `windowMode` 可为 `minimized`（默认，最小化到任务栏且不激活）、`background`（显示但不激活）或 `normal`（普通启动）。启动器在排除重复客户端后、发送启动命令前，将偏好原子写入所选游戏目录的 `config/codexbridge/window-startup.txt`。该偏好也适用于后续手动 Prism 启动；下一次 MCP 启动省略参数时重新选择最小化到任务栏。状态查询不修改偏好。旧版客户端 JAR 不支持此策略，须安装带窗口控制 v2 的版本。`windowModeRequested` 与 `windowModeConfirmed=false` 仅表示启动意图；就绪后读取 `mcb_window`。窗口的 `startupMode`、`startupModeApplied` 表示早期策略，`focused`、`minimized` 表示当前状态，不等于启动全过程的焦点证明。

该模式只控制游戏窗口；Prism 启动前的对话框仍可能取得键盘焦点。窗口未置顶不能证明输入没有中断。启动器侧边界见[原生控制缺口](docs/NATIVE_CONTROL_GAPS_zh.md)。

启动、进程诊断、加载、连接、保存退出、关闭和原生窗口控制具有不同的完成证据。Java 进程存在不代表桥接或世界就绪，启动未知时只续查同一客户端，不自动再次启动。生命周期使用明确目标和当前会话/世界；不能作用于其他聊天的客户端。

控制动作按需建立物理鼠标保护及失焦运行，失败则不执行原动作；只读操作不自动接管。手动恢复鼠标后，下一次控制仍可能接管，交还人工前先停止控制任务。直接 HTTP 生命周期调用方须满足对应保护，MCP 可复用既有预处理。原生生命周期和窗口接口不需要 Computer Use；其他未覆盖界面必须如实记录缺口。

## 实例运行日志

Adapter 在固定客户端的 `.minecraft/agent-runtime-logs/normal/` 自动记录有界类型化回执，不额外发送游戏 RPC；原始 HTTP 和私有推理不在此记录范围。回执保留时间、工具、允许的事实、会话摘要、环境和适配耗时，不保存原始参数/响应、命令聊天、图像、完整库存/NBT 或凭据。

| mcb_run_log 动作 | 说明 |
| --- | --- |
| status | 路径、限制及未审核/已审核/延期/已解决计数；候选不等于当前已确认缺陷 |
| read | 有界读取普通回执或问题，保留分页/部分覆盖；普通时间戳不是唯一游标 |
| report | 新增或补充观察、预期、复现及建议，关联实际事件；仍是 Agent 报告，不自动成为根因或验收 |
| resolve | 按存在的精确问题 ID 和解决说明关闭，可关联实际相关问题；复发可以重新打开 |
| defer | 明确延期原因和恢复条件，不创建调度或自动恢复 |
| configure | 有界热调节日志开关、大小、天数和慢操作阈值，不改变游戏结果 |
| export | 本地导出有界 report.md/report.json ZIP，不上传、不接受任意输出路径 |

普通段按 4 MiB 轮换，默认总预算 64 MiB/30 天；可配置范围和其他精确上限见英文协议。错误、未知和失败工作流形成问题，部分结果/慢操作仅作为优化候选。相同信号合并；已确认原生 AE 续查超时和正常前进的只读分页不自动当错误。旧记录不自动重新分类。

问题库独立使用 256 MiB SQLite 上限，未解决摘要不随普通日志清理丢弃；满、忙或不可用均明确报告，不覆盖游戏结果或重试动作。导出另有 64 MiB 轮换。日志耗时是 Adapter 观察，不是模型计划、审批、CPU 或整项任务耗时。

## 操作知识

共享经验层独立于外部知识库。health 和相关结果可带最多两条适用的 operatingLessons 及内置说明，不新增强制查询、截图或自动脚本。确定性归纳只读取有界相关历史，重复信号不证明因果或修复，GUI/到达验收不能升级成生产验收。

lessons/query 检索；learn 记录有证据的 procedure/recovery/pitfall；review 核对适用范围；retire 停止推荐但保留记录；精确 forget 可删除记录并使依赖证据失效。evidenceIds 必须真实存在、同工具/当前绑定且未过期，不能复制示例编号。已过期、反例或退役状态需明确，不自动推荐。

Agent 撰写的经验保持 agent_reported/interpretationVerified=false，不因重复回执自动成为已证实因果。推广候选用于标记值得工程审核的重复观察；该 API 不生成 Java/Python 代码、不改变策略、不重放动作或授予权限。结合[工作区指南](mcp-server/src/minecraft_codex_bridge/guides/workspace_zh.md)维护可复用知识和恢复记录。

## 被动录制与反馈

被动录制记录支持的玩家/界面/移动/槽位/方块或部件变化，不接管、不回放，也不默认保存截图。summary/list/events 读取固定客户端的历史，live 查看当前状态。检查事件序号缺口、载荷省略、半行和截断；重复观察不等于动作次数，缺少停止标记不等于正常结束。

无法通过原生接口完成必要读取或动作时，记录期望、实际、复现、覆盖、部分效果、恢复和原生验收条件。Computer Use、外部输入或人工补做不能作为原生通过，详见[能力缺口登记](docs/NATIVE_CONTROL_GAPS_zh.md)。

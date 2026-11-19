# BridgeNH 协议 v1：中文说明

[English](PROTOCOL.md) | [简体中文](PROTOCOL_zh.md)

本文件供人工理解和审核说明性约束。Agent 读取英文协议；实际 operation、字段、schema 和调用示例保留[英文主协议](PROTOCOL.md)及源码定义，不另造中文接口。目标为 Minecraft 1.7.10 Forge / GT New Horizons。

## 传输、取消与绑定

本机 HTTP 绑定 127.0.0.1，默认端口 25671，可配置。POST /v1/call 使用 JSON 与 Bearer 会话令牌，请求包含 requestId、operation、args；响应保留 requestId，成功提供 data，失败提供 error.code/message。所有接触游戏的读取/动作都在所属游戏线程执行，请求、队列、返回、时长和扫描体积均有界。不得记录令牌或写入 MCP 标准输出。

POST /v1/cancel 使用同样认证，仅提交原 requestId，返回 cancelled/already_running/not_found。取消与队列启动之间必须原子协调；短时取消标记避免取消先到而请求后执行。已经开始的动作不会因取消回滚。超时后的尽力取消没有确认时仍是未知效果，不允许静默重试修改。

模组在游戏目录 config/codexbridge/runtime.json 写入协议版本、本机 URL 和每次启动随机生成的令牌。受管理的聊天 MCP 启动时**未绑定**，必须以实际对话 ID、当前代数及 prismRoot/instanceName 或已列出的 clientId 显式绑定；profile 和发现记录只是清单。状态及 health 的 mcpConnection 报告真实绑定。

旧独立进程内适配器保留 MCB_RUNTIME_FILE、MCB_INSTANCE_PATH、发现目录变量及 URL/TOKEN 入口，其省略/auto 发现仍可能选最新运行记录；这不是聊天绑定策略。独立调用应明确固定目标，旧环境变量不会重定向运行中的聊天。运行文件和凭据不纳入版本控制。

## 服务端运行信息读取

`server.observe` / `mcb_server_observe` 在既有读取中增加 `kind=logs|diagnostics|mods`，均要求当前 `expectedSessionId`、`expectedConnectionId` 和 `dimension`。客户端应用增强观察策略；服务端独立核验实际玩家、连接、维度及 OP 2 级权限，异步日志读取完成后仍需再次核验。握手声明 `serverLogsVersion=1`、`serverDiagnosticsVersion=1`、`serverModsVersion=1` 并扩充 `readOperations`。仅更新客户端不会使旧服务端获得这些能力。

`logs` 仅接受 `log=latest|fml-server-latest`，默认 latest；`level=ANY|WARN|ERROR`，默认 WARN；最多 256 字符、不区分大小写的字面 `query`；以及 1..128 的 `limit`，默认 128。只读取服务端进程目录内 `logs/latest.log` 或 `logs/fml-server-latest.log`，拒绝重定向路径及非普通文件。文件不存在或不可读时返回 `log_unavailable`。每次最多读取 65,536 字节，丢弃首个不完整行，敏感行在查询匹配前脱敏；每行最多 2,048 个 UTF-16 字符，JSON 数据最多 24,000 个 UTF-8 字节，并受 28,672 字节的 Forge 响应上限约束。ANY 保留未标注级别的堆栈行，WARN 包含 ERROR/FATAL，ERROR 包含 FATAL。

日志 I/O 使用一个后台线程，最多排队 16 项。`readSource=server_process_log_file`、`capturedOutsideGameThread=true` 标识文件采集；`source=remote_server_tick` 标识经过核验的结果交付。返回文件大小及修改时间、读取字节数及时间戳、脱敏及裁剪数量、`truncated`、`byteLimited` 和非原子历史覆盖说明。返回结果不能证明更早或并发写入的内容不存在。内置服务端设置 `sharedClientServerLog=true`，其 JVM 信息和 FML 清单也属于与客户端共享的进程。

`diagnostics` 不接受额外参数，返回版本、品牌、构建指纹、玩家及模组数量、JVM 内存及运行时长、最多 100 个已有 tick 耗时样本、有限的已加载世界信息和日志读取队列数量。世界数组最多检查 256 项、返回 64 行；计数不完整时为 null 并设置 `worldCountComplete=false`，省略行时设置 `worldsTruncated=true`。不遍历世界或实体、不新增持续采样。`estimatedTpsIfSustained` 根据不含休眠的执行耗时估算，并非实测 TPS；暂停或停滞时样本可能陈旧。`jvm.uptimeMillis` 是 JVM 运行时长，不是世界或服务器运行时长。

`mods` 分页读取已加载 FML 模组的 ID、名称及版本，不扫描 JAR。`offset` 为 0..10000，默认 0；`limit` 为 1..64，默认 16。按 `nextOffset`/`hasMore` 继续，检查 `byteLimited` 和每行的 `fieldsTruncated`。日志过滤、offset 和 limit 仅由各自适用的 kind 接受；未知字段会被拒绝。`samplingMillis` 与日志的 `backgroundReadMillis` 报告单次请求耗时。

## 内置解释与结果含义

相关结果可以附带 source=mcb_builtin 的固定 feedback，最多四条去重说明，包含稳定 id、回执位置、含义与下一步；说明省略会显式标记。它不依赖历史知识或额外 RPC，不改原始结果、错误、图像与验收证据，不授予权限或执行回退。

仅已知代码获得明确解释，未知代码不猜根因。未知效果优先；outcome_unknown=false 不等于从未执行，sideEffectsPossible=true 也不必然是未知。只有明确未派发或队列取消确认，才能证明该请求未执行，不能证明之前动作已回滚。submitted、scheduled、completed、真实状态验收各有区别。只读部分覆盖、分页、Waila 显示缓存也不能合并成完整证据。

## 有界组合操作

`mcb_action_recovery` 是 worker 本地恢复接口，不是新的 HTTP 修改操作。`list` 离线读取保留的动作 ID；`inspect` 和 `reconcile` 复用已有的受保护 `baubles.inspect`、`world.inspect_many` 读取。核对要求当前会话、原始准确存档目录参数及维度、重复一致的客户端/服务器完整 NBT 背包与方块证据，以及明确确认。回执只解除所选创造模式放置的重放保护，历史结果仍为未知，新放置必须另行请求，其他未知动作继续受保护。详见[适用范围和流程](mcp-server/src/minecraft_codex_bridge/guides/maintenance_zh.md#核对已有回执的创造模式放置)。

观察、序列、条件等待、接近、建造、配方及 AE 等组合复用既有原语、锁、当前身份和统一期限。详细字段、白名单和具体上限保留在英文协议和当前工具 schema。组合不意味着跨 tick 原子快照，也不意味着后续动作失败时能撤回先前效果。

动作可包含 `after:{id,tool,args?,bind?,condition?,timeout_ms?,interval_ms?,fields?,omitEmptySlots?}`：未指定 condition 时读一次，指定时只重复读取并等待条件成立，自动关联父动作的验收。后续步骤可引用该 id。展开后的步骤统一预校验，并计入 32 步上限；等待期间不重发动作。

未加载世界时使用 `menuScreenId` 代替 `expectedWorld`，仅允许 health、GUI、控件读取和 widget/key 动作及断言。入口在接管前校验屏幕，且必须具备 `contextRpc.unloadedMenuVersion>=1`。`includeContext.menuOnly=true` 在客户端所属线程检查 `worldLoaded=false`、`unloadedMenu=true`，后者要求无玩家、无集成服务器、无待处理生命周期任务且存在界面。每次读取也检查后置上下文。进入世界或上下文失效后停止剩余步骤，已发生的按钮效果不回滚。此模式不能包含世界操作或机器清理；旧客户端在动作前拒绝。

GUI、NEI、AE 动作明确返回部分完成、拒绝、未确认或未知结果时，以 `ACTION_NOT_CONFIRMED` 停止序列，保留实际回执和未执行步骤。控件结果默认保留 `after`、目标匹配及相关原生回读字段；本地控件值不代表服务端效果。

序列绑定来自此前完整观察；动态屏幕、窗口、真实槽、虚拟资源不得混用。动作之后的实际读取才能作为效果验收，提交回执或动作前基线不能证明完成。默认不自动翻遍所有页，不重放动作或消费过的游标。返回预算省略和元数据覆盖必须保留未知，不从短 NBT、图标或浮点近似重建身份/数量。

机器、产线、访问路线和坐标由当前任务组合，不能把一位用户的整套布局固化为通用接口。规划、目录候选、nativeDone、recipe count 或 QA testPassed 均不能单独证明结构成立、原料消耗、产物交付和停止。

## GUI、NEI 与 AE

MUI2 滚动区域通过 `scrollAxes` 报告可用轴、当前偏移、最大偏移及动画目标。`scroll` 接受 `axis=vertical|horizontal` 和 `direction=up|down`，每次滚动一格。`drag_scrollbar` 接受同一轴及 `percent=0..100`。行按钮的 `activate` 根据布局变换后的边界和面板原生事件顺序命中，允许事件穿过可透传的文字和布局子控件。点击后可读取选中行或详情。命令方块可编辑字段标记为 `command_block_input`，单行上限为 16,384 个 UTF-16 单元、16,368 个 UTF-8 字节，并受原生字段限制。`expectedText` 和 `localTextMatchesRequested` 均比较完整文本。另行点击“完成”后，再核对命令的实际效果。

原版 `GuiCreateWorld` 的游戏模式、世界类型、更多选项页面、地图特性、作弊及奖励箱按钮提供 `kind=choice`、`setting`、`selected`、`value` 和 `options[].index/value`。`set_selected` 按观察到的选项索引调用原生按钮回调，直至回读目标值。已满足目标时不调用按钮，当前隐藏但仍可读取值的选项也适用。世界类型取自当前可创建的注册项，索引可能不连续。游戏模式对作弊默认值的影响，以及切换世界类型时清空自定义生成参数等原生行为均保留。

仅在此菜单中，`set_selected_many` 接受 `selections:[{widgetId,selected}]`，包含 1..8 个不同的当前控件，不使用顶层 widgetId。修改前统一核对身份和目标选项，再按明确的页面顺序执行，例如游戏模式、更多选项页面、世界类型。需要修改隐藏选项时，应先通过前一步切换到所在页面。创建、取消和任意按钮不属于批量选择范围。单项最多执行 32 次原生切换，并检查 25 ms 协作式预算；批量在各项之间检查 100 ms 预算。状态不变或异常、控件禁用、页面改变或回调失败时停止，保留已发生的变化，不回滚、不自动重放。

以 `localSelectionMatched=true` 确认菜单目标。单项提供 `after` 和包含回调次数及前后菜单状态的 `nativeSelection`；批量提供各项回执及最终 `afterState`，必须与所有请求值一致。这些是尚未创建世界的本地菜单设置，不是存档创建、配置持久化或服务器确认。

GUI 读取只覆盖支持的公开按钮/槽位/控件，先检查 coverage。真实 ItemStack 提示优先于无 NBT 模板；高级提示不模拟实体修饰键，逻辑悬停不移动系统鼠标。每次界面/控件变化后重新取得身份。菜单/设置保存沿用原生完成路径，必要时重新打开验收。

NEI 读取当前 handler/原始配方引用及实际 PositionedStack，分页是当前可见引用而非全局配方图。配方原始消耗、概率、条件和显示超频分别记录；截断前尚未访问的输出不能视为空。语义查询、整方收藏、overlay 和面板操作必须使用各自支持参数；整方 bookmark 不等于物品级收藏。

`nei.bookmarks` 只读当前书签页、分组、可见行、精确条目及保留状态；`nei.bookmark_action` 在匹配 `screenId/contextToken` 后调用 NEI 原生方法，完成增删、分组、排序、调量、页面选择及跨页任务清理。配方书签按同组、同一命名空间中的整份配方删除，包含输入、输出和关联物品，不扩展到其他位置的独立收藏。观察上限为 64 页/4096 项，单页最多返回 128 项，单次删除最多 512 项。以 `taskId` 与 temporary/permanent/future 记录临时、永久和待用归属；自动清理保护未登记或保留项及其关联配方。明确不再需要保留项后，显式删除使用 `releaseRetained=true`。客户端/世界/原生对象重载后丢失归属的条目保持未知并保留。只请求原生保存，不以内存回读代替磁盘持久化验收；部分失败先核对，不重放。具体参数见[原生书签管理](mcp-server/src/minecraft_codex_bridge/guides/nei_zh.md#原生书签管理)。

AE 真实物品槽、网络 resourceId、VirtualMESlot 和幽灵样板各自独立。数量保持十进制 long 字符串，动作解析保留完整实际 NBT。取存、样板编辑、预览、启动、CPU 管理和远端接口操作使用正常容器/网络路径；提交后等待同步再读。订单续查沿用同一身份；服务端已交付但客户端未同步时只等待，不再次取货。CPU 取消不证明接口缓存和机器已经清空。

完整使用说明见[NEI](mcp-server/src/minecraft_codex_bridge/guides/nei_zh.md)、[AE](mcp-server/src/minecraft_codex_bridge/guides/ae_zh.md)及[通用操作](mcp-server/src/minecraft_codex_bridge/guides/operations_zh.md)。

## 世界、结构、移动及开发操作

扫描只读已加载范围，返回有界页及真实访问覆盖，未加载/未访问不等于空气。游标会过期和消费，空页可能仍有进展；续页使用实际返回值，不自行按条数推算。缓存不代替实时库存、碰撞或最终验收，也不加载新区块。

结构规划只使用公开接口。缺少控制器偏移时只能返回 piece-origin 几何，不提供猜测世界坐标。piece/variant/alignment、分页、元素候选和实际 observed 各有边界；候选不等于完整接受规则，规划不等于结构成立。布局需要覆盖整体体积、地板、空气、附属设备和访问空间。

移动依实际步行/飞行/TP 策略、游戏授权及执行后端。允许飞行不等于实际飞行；跨维度后重绑并重新取得必要的双端姿态。原生距离、身体空间、支撑、危险和射线检查保留；临时避障高度不等于抵达。开发读取、物资生成、直接后台修改及 QA 有各自能力/权限，详见[玩家权限组与操作策略](docs/PERMISSIONS_zh.md)。

### 世界滚轮、准星选取与按坐标复制

`player.world_mouse` 在关闭 GUI 后使用当前玩家视角。`wheel` 向 Forge 发送一格滚轮事件，`sneaking=true` 在派发期间按住当前绑定的潜行键。模组可消费事件，其余情况执行原生快捷栏滚动。回执包含 `eventCanceled`、`slotBefore`、`selectedSlot` 和 `item`。`pick_block` 调用准星所指方块的 Forge 选取逻辑，已有匹配物品时只切换快捷栏选择。创造模式生成新物品时，通过该快捷栏索引实际绑定的容器槽位同步，回执包含 `creativeSlotPacketSent` 和 `nativeContainerSlotId`。

`inventory.copy_block` 要求当前会话、自有集成世界、实际创造模式及创造补给权限。提供维度、坐标、已观察到的方块注册名和元数据，以及索引为 0..35 的背包空槽。先关闭容器并清空光标。GT 机器及管道另需 `expectedMetaTileId`，副本保留物品形式的覆盖板及机器自行导出的设置和子类型标记。其他方块采用原生 Forge 选取结果，`side` 为 0..5，默认顶面。源方块保留在原位。无物品形态或物品标签超限时，操作在写入前结束。`serverSlotConfirmed` 表示服务端槽位回读结果，`tagPreview` 是有界预览。回执不确定时，先检查指定槽位。

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

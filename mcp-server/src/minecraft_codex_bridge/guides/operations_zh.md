# GTNH 操作检查与边界

适用 Minecraft 1.7.10。以当前 health、权限和 coverage 为准。`mcb_operation_check` 用于独立排错，既有工作流已做的检查无需重复。

## 接口选择

已有结构化接口时须直接调用。原版箱内的物品转移用 `mcb_chest_transfer`，配方翻页用 `mcb_nei_navigate`，寻路用 `mcb_navigate`，采掘用 `mcb_mine`。界面控件用 `mcb_gui_widgets` 查相关页，再用 `mcb_gui_widget_action` 执行支持的动作，包括滚动、拖动。覆盖不足且目标不明时保持待确认。

遇到权限拒绝、目标失效、部分完成或结果未知时，先核实权限、目标及已发生的变化。禁止用坐标绕过拒绝或重放未确认的动作。

## 界面鼠标手势

仅在可见控件已识别、手势已获授权且无对应结构化动作（如滚动自定义帮助面板），或明确测试指针本身时使用虚拟鼠标。用 `mcb_gui` 读取 `screenId` 和缩放尺寸，必要时将帧缓冲像素换算为 GUI 坐标。客户端声明 `gui.virtual_mouse` 时，向 `mcb_virtual_mouse` 提供当前 `expectedSessionId`。`move` 悬停，`click` 单击，`scroll` 按带符号的 `notches` 滚动，`drag` 从 `x/y` 拖至 `toX/toY`。事件随界面绘制投递，原版列表在绘制时读取滚轮。

接管时，手势、按住鼠标键、坐标点击的短暂反馈及 `mcb_gui_pointer` 悬停租期内，`virtualMouse.displayMode` 为 `pointer`，指针跟随 GUI 操作点。其余时间为 `status`，Codex 标志位于准星下方，闲置界面位置相同。交还手动控制或在世界中按 F1 隐藏 HUD 后为 `hidden`。按住显示按下状态，文本聚焦或编辑显示文本状态，原生等待或活动任务优先显示忙碌。`indicate_thinking` 以 `durationMillis`（0 至 120000 毫秒）设置忙碌而不唤出指针，设为 0、换屏或交还时清除。启用 Codex 钩子后，对话压缩也显示忙碌，完成或中断仅清除压缩信号。

分次手势用 `press` 返回的 `gestureId`，15 秒内移动并 `release`。换屏或尺寸变化后检查界面和光标栈。派发只证明送达。补缺时回读实际效果，即使成功也按[缺口模板](workspace_zh.md#原生控制缺口与必要反馈)记录缺少的动作、回执和结果。指针专项测试只验证所测行为。`mcb_gui_pointer` 悬停与 `mcb_gui_click` 单击保留原参数，共用指针占用状态。

## 服务端日志与运行信息

查询服务端日志、权限和运行信息时，读取 `mcb_help({"topic":"maintenance"})` 中的[服务端日志与运行信息](maintenance_zh.md#服务端日志与运行信息)。

## 局域网与专用服务器上的地面移动

使用 `mcb_spatial` 获取射线或一页坐标的空间信息。客户端具备当前服务器的 OP 权限并启用增强观测、服务端声明 `spatialObservationVersion=1` 时，由服务端游戏线程读取。选择目的地前，检查回执标明的数据来源和观测范围。拾取掉落物时，先用 `mcb_entities` 确认当前位置，到达后从玩家物品栏核实拾取结果。

调用 `mcb_navigate` 时提供当前会话、维度以及服务端的 `worldName` 或 `worldFolder`。当 `operationPolicy.effective.clientGroundPlanVersion=1` 时，客户端在已加载区块内规划和检查移动，遇阻最多重算四次。较远的平地坐标按区块加载进度分段前进。需要显式延长时限时，传入 `segmented=true`，最多 60000 毫秒。路线可经过原版木门、梯子和同色 OpenBlocks 电梯。开门需要一个空快捷栏位。回执中的 `serverProbes` 和 `serverArrival` 记录终点核验次数与结果。支持 `walkArrivalVersion=1` 的服务端通过一次请求核实停稳。仅声明 `remoteGroundWalkVersion=1` 的旧客户端支持 32 格以内的同高度路线。

使用 `mcb_approach` 接近单个已观测方块，传入坐标及包含注册名和方块元数据的 `targetIdentity`。`move=false` 返回规划路径。`interact=true` 会在双端确认到达且方块面可见后提交一次普通使用操作。向箱子存放物品时，读取打开的箱子和槽位，转移指定物品后回读库存。行走中断或界面延迟出现时，先检查当前站位和容器。

## 按目标值配置创建世界选项

按目标值一次设置并回读创建世界选项，见 `mcb_help({"topic":"testing"})` 的[创建世界设置](testing_zh.md#按目标值配置创建世界选项)。

## 合并重复操作与结果回读

执行已经明确的连续操作前，将存在依赖的动作和校验组合为一次 `mcb_sequence`。适用于连续控件编辑、物品槽操作、NEI 导航与读取、AE 配置及其他允许的工具。执行器对每个动作只调用一次，在内部等待声明的观察结果。已有目标值设置、库存、AE、书签批量操作或目标流程覆盖验收时，优先复用；互相独立的读取使用 `mcb_observe`。不能通过重复点击代替同步等待。

动作可添加 `after`，包含独立 `id`、只读 `tool` 及可选 args/bind/fields。没有 `condition` 时读一次，有 condition 时进行有界只读等待，并关联父动作的验收。后续引用使用该 id 对应的完整新结果。展开后的全部步骤计入 32 步上限，所有身份、参数和引用在执行前统一校验。GUI、NEI、AE 动作明确返回部分完成、拒绝或未知结果时，停止其 after 及后续步骤；其他领域流程沿用各自的验收逻辑。根据保留的回执确认当前状态，再安排剩余操作。

世界内流程需要 `expectedWorld`。未加载世界的菜单使用 `menuScreenId` 代替，要求客户端声明原生菜单边界检查能力，仅允许 health、GUI、控件读取、widget/key 动作及断言。每个动作都在客户端线程检查当前仍处于空闲且未加载世界的状态，并保留屏幕身份校验。界面跳转后，在同一流程内读取新屏幕并绑定其当前控件；世界开始加载或出现生命周期任务时停止后续操作。

以下示例将已知菜单按钮操作和目标界面校验合并为一次调用：

```json
{"expectedSessionId":"<current-session>","menuScreenId":"<current-screen>","steps":[{"id":"open","kind":"action","tool":"mcb_gui_widget_action","args":{"screenId":"<current-screen>","widgetId":"<observed-button>","action":"activate"},"after":{"id":"opened","tool":"mcb_gui","condition":{"path":"screen","operator":"equals","value":"<observed-destination-class>"},"fields":["screenId","controls.widgets"]}}]}
```

后续动作可从 `opened.screenId` 绑定 screenId，从 `opened.controls.widgets` 精确唯一匹配 widgetId。NEI、AE 可声明相应 after 读取，物品槽操作之间可加入准确的光标与槽位验收。已知同步延迟使用 after 条件或显式 wait 步骤处理；超时后不重发前一动作。

控件动作投影在可用时保留实际 `after` 状态和 `localSelectionMatched`。本地文本、选项、数值可复用同次调用的结果，不必仅为获取回执再次查询相同状态。涉及服务端的效果仍需实际同步或权威读取确认。批量操作减少 Agent 往返次数，原生回调、边界检查和必要的游戏等待仍然保留。

## 一次调用的快捷检查

玩家操作历史直接使用 `mcb_recording({"view":"summary"})`，不再临时编写日志读取脚本。
`view=list` 列出本实例历史记录；`view=events` 按 `recordingId` / `since` / `nextSince`
分页，可用 `kinds` 过滤事件。摘要统计指令、GUI、移动、槽位变化和服务端方块/AE部件变化，
同时报告序号缺口、载荷省略、半行和摘要截断。重复状态观察不等于多次动作。
历史读取在客户端离线或重启后仍可用，不接管操作、不回放；无停止标记时状态为未知。
需要当前开启状态与写入队列指标时使用 `view=live` (默认)。现有对话的工具目录尚未更新时，
可用项目通用入口 `scripts/mcb.ps1 -Profile <profile> -Action Recording`，复用同一个 MCP 实现。

搭建 AE 前读取 [AE 指南](ae_zh.md) 的布线、输出与阻挡说明。按当前任务选择线缆与 IO。供料端 BLOCK 与接收端高级阻挡分别验收，拆装后批量读回优先级、过滤、方向和网络。

取得当前会话、世界和观察到的坐标；将占位值替换为这些观察结果及实际配方数据。

```json
{"kind":"machine","expectedSessionId":"<current-session>","expectedWorld":"<current-world>","target":{"x":0,"y":64,"z":0,"dimension":0}}
```

| kind | 实际读取 | 权限与范围 |
| --- | --- | --- |
| machine | `mcb_machine_inspect` | 明确世界/维度/坐标，当前本地 developer 权限；通用机器公开状态 |
| ae | `mcb_gui` -> 用新 screenId 调 `mcb_ae` | 当前已打开的 AE 界面；可选 scope，默认 auto；一页16条 |
| ae_buffers | `mcb_world_inspect(includeAe=true)` | 明确世界/维度/坐标，本地 developer 与已广告能力；只检查指定 owner |
| nei | `mcb_gui` -> `mcb_nei_view(summary)` -> 同 contextToken 的可见 recipes | 当前界面，一页4条；不打开或切换配方 |

AE 示例：`{"kind":"ae","expectedSessionId":"CURRENT_SESSION","scope":"cpu"}`。NEI 示例：`{"kind":"nei","expectedSessionId":"CURRENT_SESSION"}`。`ae_buffers` 的参数与 machine 相同，只替换 kind；坐标应为已确认的接口/部件宿主，不默认是机器控制器。

默认总预算10秒，`timeout_ms` 最大30000。工作流结果上限56 KiB，加固定说明后不超过64 KiB的data预算。保留每步 observation 的 values、metadata、missingFields、omittedFields、coverageSummary 和失败位置。多次读取非原子；不自动翻页、重试、接管鼠标、截图或改变世界。页面尚未同步或读取不完整时仍须保留未知，`status=completed` 仅表示本轮读取完成。`productionVerified=false` 明确不提供生产验收。缺能力、上下文漂移或超时会停止后续步骤，不退回其他实例或操作方式。

### 构建前的工序收支检查

`mcb_operation_check(kind="process", process=...)` 不访问游戏。将已有 NEI/配方查询取得的确定配方写入临时计划:

```json
{"kind":"process","process":{"machines":["processor"],"resources":[{"id":"input","identity":{"kind":"item","registryName":"example:input","metadata":0},"role":"feed","initial":"6"},{"id":"output","identity":{"kind":"item","registryName":"example:output","metadata":0},"role":"product","initial":"0","expectedFinal":"2"}],"recipes":[{"id":"convert","machine":"processor","source":"<observed-recipe-and-version>","batches":2,"inputs":[{"resource":"input","amount":"3"}],"outputs":[{"resource":"output","amount":"1"}]}]}}
```

例子仅说明格式，不代表真实配方。资源角色为有限主输入 `feed`，无限辅助输入 `external`，中间物 `intermediate`，最终产物 `product`，循环库存/非消耗工具 `cycle`。流体身份使用 `{"kind":"fluid","fluidName":"实际名称"}`。数量是精确非负十进制字符串；每配方数量必须大于零。`external.initial` 必须为 `"0"`，不可同时产出该资源。`catalysts` 与 inputs 同格式，要求存在但不扣除。配方用 `batches` 声明本次次数，`machine` 必须在 machines 中，source 记录已查询的出处。

工具检查缺生产/消费工序、缺机器、产品与有限输入脱节、循环缺回收、启动缺料，并有限模拟完整批次。feed/intermediate 默认最终为零；允许缓存时显式写 expectedFinal。product 可写 expectedFinal 检查预期总量。cycle 允许转换为其他循环缓存，需要保留特定种类数量时同样声明 expectedFinal。`balances` 和 externalInputs 分开记录有限资源与无限辅助消耗。一入一出中间物返回 pairwiseBatchMultiples，例如每次360、下游11000对应上游275次/下游9次；这只是局部整批约束，不是整线最小投料证明。

只支持调用者已明确选择的确定配方与次数；不自动查询整个配方网，不把概率期望当确定产出。配方顺序影响竞争资源的模拟；达到计算上限返回 unknown。`planValid` 才是声明计划检查结果，`status=completed` 只表示计算结束。仍需核实当前版本配方与机器条件，后续用实际基线/增量验收，不能把推算当作游戏产物。

### 投料前的整站检查

`mcb_operation_check(kind="site", expectedSessionId=..., expectedWorld=..., site=...)` 复用既有只读机器与方块批查。site 包含 dimension，以及以下任意非空集合:

- machines：`[{"id":"furnace","position":{"x":0,"y":64,"z":0},"metaTileId":1000}]`。核对身份、成型、启用、维护及组件覆盖；标准 GT 消声仓自动读取真实朝向与前方空气。可加 requirements，每项 `{path,operator,value}`，operator 为 equals/not_equals/gt/gte/lt/lte。热量、模式、电压等按实际公开字段声明；不凭主机 storedEU=0 推断能源仓无电。
- endpoints：`[{id,position,targetIdentity,mountingSide,network}]`。targetIdentity 沿用已有方块/GT/部件身份，mountingSide 为实际宿主面，普通方块通常 -1。同名 network 必须落在相同实际 grid；isolatedNetworks 可声明 `[["main","subnet"]]` 检查隔离。活动节点不证明真实抽取或物料返回，logisticsTransferVerified 固定为 false。
- clearance：`[{id,position}]`，检查声明位置为原版空气；加载缺失返回 unknown。
- layout：`[{id,kind,bounds}]`，kind 为 machine/ae/pipe/clearance，bounds 使用 minX/minY/minZ/maxX/maxY/maxZ。检查独占体积相交；已包含在整机体积内的仓室不要重复声明占位。可以用多个小盒描述实际占位及通道，不从一个包围盒推断内部空气。

结果 readiness 为 passed/blocked/unknown，每项保留原因。passed 只覆盖本次声明及已支持的检查；不证明供电吞吐、流体传输、所有机器特性、强加载或生产完成。不执行结构复查、开机、搬料或重放；既有工作流已验收的内容无需外层逐项再读。

process/site 的输入各限64 KiB，返回也限64 KiB。outputComplete=false 时 omissions 标记省略的完整行及数量，汇总判定仍基于全部声明；不能把被省略的余额或失败明细当作零。必要时按有意义的子计划查询。

### 批量回执与恢复

`mcb_ae_install` 返回 checkpoint。失败后把它原样放入同一参数的 resume，保留原 targets、session、世界及维度。恢复先批量读取全部目的位置：已完成位置必须仍匹配，其他已存在的精确部件也跳过，只对缺项预检材料并安装。已匹配部件仅证明当前存在，不推断其来源或历史扣料。位置冲突、完成项漂移、未加载或 uncertainIndices 非空时拒绝继续。不清除结果未知的动作来强行重试；先用既有读取核对其稳定结果，再明确制定新操作。

通用 `mcb_sequence` 的 wait/assert 可加 `accepts:["之前的actionId"]`，将一个后续实际读回条件与动作关联。assert 的 source 必须是动作之后的 read/wait，不能引用提交回执或动作前状态。checkpoint.actions 分开表示 acknowledged、condition_verified、outcome_unknown、needs_reconciliation、not_dispatched。同一动作声明的所有 accepts 条件均通过后才能标记 condition_verified；它只证明声明条件在相应观察时成立，不推断产量或后续 cleanup 后状态。小返回预算使用 stepIndex/acceptedByIndices 引用原步骤。任意动作序列不自动续放；用未执行清单及新鲜绑定临时组合下一段，不沿用旧 GUI 标识或重发聊天命令。

## 通用机器操作

### 独立操作与临时组合

MCB 的操作边界按可复用动作划分。当前任务决定目标、布局、配方和执行顺序，再临时组合现有工具；不增加装配线从搭建到取货的专用完整操作。每步保留输入、前置条件、真实效果、完成凭据和失败语义。

复杂产线先把精确材料表、机器/IO/ME 位置、方向、玩家站位与依赖写入任务笔记，集中查询一次，执行阶段只补查变化。用现有批量读取完成实例/世界/策略、实际姿态、材料和背包容量预检。`path` 按当前真实飞行状态选执行方式；创造模式或允许飞行本身不意味着已经飞行，落地后可以步行。

已知无 NBT 创造材料可用 `mcb_supply_items` 一批 1..8 种，每种 1..64 且不超原生堆叠上限。默认仍要求空背包；health 广告 `nativeSupplyPreserveInventoryVersion>=1` 时可明确 `preserveInventory=true`，保留原物品并在服务端按整批预留普通 36 格容量。数量是额外发放量，先从材料需求扣除已有库存；空副手不算普通插入容量，身份及 NBT 不同不能合并。不足时整批在原生发放前拒绝。回执只说明提交，批次末统一核对双端实际库存；不逐件补读背包或重发效果未知的数量。需要 NEI 实际选取时按 [NEI 批量取物](nei_zh.md#批量取物与创造背包同步) 组合搜索、绑定和获取。

安装阶段按步数、超时、容量和依赖拆为有意义的批次：普通机器放置与移动用 `mcb_sequence` (最多 32 步)，ME 部件用 `mcb_ae_install`，配置用 `mcb_ae_configure`。保留工具内部的射线、距离、库存和会话校验，批次末用已有批量观察核对实际方块、方向和网络。中途失败先看完成步骤与效果未知标记，再读受影响位置；已完成的步骤保留，只续接未完成部分，不回放整批。连接成功不代表机器已经供料或生产。记录从准备到验收的总耗时、Agent 调用数与工具内部 RPC 数，不把减少外层调用等同于取消底层检查。

ME 批装的内部接近步骤复用本次请求绑定的静态能力发现，仍读取每个目标前的实时快照和最终射线；只在当前确有 GUI 时关闭。不必在外层为每个部件再加 health、关窗或相同目标的预检。会话、运行实例、世界、维度或后台控制发生变化时仍停止，不自动重放。此优化不缓存库存、世界几何或当前权限，不省略单次放置的服务端确认与 NBT/数量守恒。

共享接近在 `tp` 策略下保留当前双端已确认的空中飞行姿态，因而上下面安装可以选择有完整身体净空的空中站位。落地或飞行证据不一致时仍要求有支撑的终点，不自动起飞。接近搜索耗尽射线或时间预算时先检查回执的候选覆盖与姿态；`partial` 不代表目标不可达，也不允许绕过最后的正常距离和双端射线验收直接放置。

| 独立操作 | 前置条件和完成依据 |
| --- | --- |
| 观察与坐标转换 | 明确世界、owner、朝向和覆盖范围；读取成功只证明取得所声明的观察，不证明可放置或已成型 |
| 移动与接近 | 正确模式、姿态、完整身体空间和目标身份；按原生终点、允许面与射线验收，保留实际部分移动 |
| 放置、挖掘、转向 | 实际选中物品、正常可达面、原生动作；随后分别核对方块/部件、朝向、掉落或有限材料变化 |
| 容器搬运 | 当前窗口与真实槽绑定、来源身份及空光标；一次拿起/放下后确认来源、目标、光标及无关物品守恒 |
| GUI 与配置编辑 | 新鲜 screenId/widgetId/资源身份；实际同步值或服务端效果回读，submitted 不等于完成 |
| 条件等待 | 明确观测条件、原期限和覆盖要求；超时结束本次观察，不重发前一步写操作 |

`mcb_sequence` 可绑定前一步的观察、断言身份，再执行动作并等待效果。`side`、`button`、`mode` 与 x/y/z 一样支持 `bind`，实际值仍须满足目标工具的 schema；缺失、布尔值或范围外面号不能提交点击。动作结果未知时保留光标、部分完成和凭据，不通过更换步骤 ID、延长时间或重新组合来重放。坐标规划先作为独立读取取得，结果用于本任务下一段序列；不要重复运行整个施工流程来刷新一次坐标。

`mcb_inventory_moves` 接受1..16组 sourceIndex/destinationIndex 和完整 expectedItem(registryName/metadata/count/hasTag=false)。只搬整个无NBT栈到实际空槽，所有源/目标索引0..35互不重叠；整批先校验实际来源、空位及原生槽绑定，只打开一次普通背包，逐组复用原生拿起/放下、光标守恒与无关完整NBT校验。成功保留最终位置，不自动归还；需要归还时另声明反向移动。失败保留已经完成及结果未知的动作，没有回滚或重放。带NBT栈、合并和依赖链使用原有真实槽操作，并单独验收。

AE安装和设置沿用现有 `mcb_ae_install` / `mcb_ae_configure` 的批次。磁盘、样板GUI仍临时组合：读当前GUI/资源/真实槽 → 断言精确身份 → 绑定 screenId/windowId/slot/button/mode → 调既有原生槽或 `mcb_ae_action` → 等待实际同步值、光标及数量。真实槽、AE虚拟资源与幽灵样板不混用编号；不新增固定磁盘或配方流程。

`mcb_plan_visits` 只安排调用者声明的1..32个 feet 访问点，可用 after 声明依赖。未声明依赖的访问允许重排；重复ID、缺失依赖和环在访问游戏前拒绝。起点读取实际当前脚底位置；稳定拓扑基线与“最近的可执行点”比较，只保留不增加估算路程的次序，并把连续同位置任务合为一次访问。结果保留每个taskId，距离是欧氏几何估算，不证明最短路、可达或无障碍。任务依次调用现有导航，在同一访问点临时组合观察、取料和动作；规划本身不决定材料、布局或机器语义。

### 精确交互、步行与有限尾壳

`hitPoint={x,y,z}` 约束方块内的具体点击点；在 `mcb_approach.targets` 声明单个 allowedSide 和 targetIdentity，再将同一点用于 `mcb_interact`。原生双端射线及正常距离仍需通过，点击成功不证明盖板安装面或机器状态。

步行保留完整身体碰撞、危险和液体检查；薄部件允许整个平移区间连续存在原生支撑接触，不再要求整块脚底覆盖。起步前等待两个不同原生 tick 的双端静止，不把已释放按键当作惯性结束。原生高度转换可连接起点、目标的小数高度与整数地面，0.125 格以下差异暂不作为独立跳落边；不是任意地形可达保证。

生存预装输出导致原生外壳补全返回 no_progress 时，不重复 survivalConstruct。既有 `mcb_blueprint_build.survivalBuild.finishAirTail=true` 可在该次任务的完整材料回执、捕获坐标和端口保持检查后，预检最多 16 个真实空气余项及合计库存，再复用原生逐块放置，最后独立确认成型。只支持有限玩家材料，不支持 AE 材料来源。单独 `mcb_survival_build` 不保有完整构建任务，应由调用者组合同样的观察/候选/库存/放置步骤；不要事后重新运行完整建造工具来接管已有结构。

### 主机朝向、旋转与翻转

1. **分清坐标系。** 世界 XYZ、StructureLib 的 ABC、相对控制器的世界 XYZ 偏移是不同表达。只改变方向名或 x/z 交换不覆盖 UP/DOWN、附加旋转和翻转。使用当前公开 `alignment` 的完整 direction/rotation/flip；不从截图、旧记录或玩家看向推断。
2. **按观察变换。** `mcb_structure_transform` 接受 `source`、`target` 的实际坐标、公开 `piece` 和 `expectedAlignment`，复用公开零/单位偏移查询得到三轴变换。`points` 是有 id 的源控制器相对世界 XYZ 偏移；`bounds` 是同坐标系的包含端点盒，转换全部八个角再取新 min/max。默认目的锚点是 target 控制器，也可声明拟建 `anchor`；后者不证明目的位置已有主机。

   没有目标参考主机时，改用 `targetAlignment` 加必填 `anchor`。需要原生 `structureAlignmentFramesVersion=1`；`mcb_world_structure(alignmentFrames=true)` 是独占的只读控制器视图，公开实际 ExtendedFacing 枚举矩阵及当前源主机 getAlignmentLimits 的允许值。新规划把源零/单位探针与枚举当前矩阵交叉核对，结束复查实际源身份和朝向。目标返回 controller=null、placed=false；sourceControllerAlignmentAllowed=false仍是明确不允许，true也只说明当前源主机的公开限制，不证明新位置、完整机器或模式合法。
3. **区分方向的用途。** `sides` 转换0..5的世界方向；相邻附着面与部件安装面可能互为反面，应分别绑定，不能直接复用同一面号。6仅在目标接口定义为中心槽/唯一节点时保持6，它不是可右键的第七个面。薄部件最终命中仍按真实形状和原生射线检查。
4. **保持世界重力。** 主机转向不会旋转世界的地面、支撑、跳跃或玩家身体。几何转换后的 AE 终端/CPU/驱动器仍要重新检查露地高度与通行净空；纯 IO 的隐藏层按最终世界 Y 重新安排，不能把模板原来的底层直接当作新的地下层。
5. **验证实际放置朝向。** 几何面号转换不改方块 metadata，也不替代各模组的玩家视角放置规则、扳手规则或合法主机朝向限制。仓室坐标可以随结构转换，开口面仍可按最终通道、AE线路与访问需求独立安排；刚性变换的面号是几何结果，不是所有接口必须采用的工作面。先普通放置，读服务端实际朝向；需要转向时独立执行受约束的工具动作，再读回确认。高位可组合已有接近、有限材料脚下放置及正常下降，不授予飞行或直接改位置。
6. **重新确认业务顺序。** 转换保留调用者 point id 和数组顺序，不推断仓室角色、注册索引、结构重复次数或配方槽顺序。镜像后空间左右变化尤其不能替代实际注册的 roleIndex。机器成型、顺序和供料语义单独验收。

该转换只读、无放置/旋转/提示动作，默认10秒、最大60秒，共用有限读取预算。源和目的 owner、朝向在读取后再次核对；变化、无公开变换、缺能力或超预算即停止。结果 `placementVerified=false`、`reachVerified=false`，不缓存为后续动作的永久授权；机器模式和完整动态结构规则仍需另读。不同主机、朝向、布局和配方通过这些独立操作临时组合。

先辨认实际 owner、维度、当前模式、结构成立、配方结果与停机原因，再按任务检查真实电压/电流/储能、维护、玻璃/线圈及特殊条件。不把普通双能源仓上限推广为多A/激光巨型机器的上限。字段缺失不能推断为零或无要求。

GT5U **5.09.54.205** 的 `MTEMultiBlockBase.runMachine` 在已有进度时继续当前批，`isAllowedToWork` 控制后续启动。`stopMachine` 则清除进度和待输出。因此普通 disable 不等于故障停止，不能从 allowedToWork=false 推断产线已静止。子类覆盖需另核，不把基类结论强套所有机器。

同版本 `ProcessingLogic.validateAndCalculateRecipe` 先执行 helper.build 消耗输入，后进入 `applyRecipe/onRecipeStart`；启动钩子失败不保证返还原料。HIP、溶解罐等具体规则不写入通用机器控制器。失败后先核对实际库存、当前结果和条件，不直接补投或重复启动。

该 GT 基类完成时尝试输出后清除待产物，不保证输出堵塞时保持满进度等待。生产验收需要完整身份、实际消耗、实际产物、回收与停机证据。进度归零、recipe count、缓存 lastMatchedRecipe、Waila 文本或 NEI 显示超频都不足以证明生产。数量保留十进制 long，NBT 不由图标或短摘要重建。`mcb_machine_watch` 可观察演变，但本身不证明资源守恒。

## AE 与 NEI 的职责

拆 AE 同格部件时，按当前版本用兼容扳手潜行右键，绑定实际 targetPart，回读服务端剩余部件。整格挖掘可能损及其他部件或后方方块。回收物品可能带配置 NBT，勿仅按注册名补发。

AE 管理网络资源、样板与收发，机器执行处理配方。核对催化剂、非消耗工具、概率、返还、替代及数量。NEI 转移后回读 AE 同步配置，再核实实际原料。区分 AE resourceId、幽灵槽与玩家真实 slot。

预览为 requestedPending 或 startReady=false 时等原预览同步，不重发 craft_request。CPU 取消后检查待发送物料，分别核对 CPU、接口宿主、机器库存及回网产物。ae_buffers 仅覆盖指定宿主。外部库存可见不能确认 CPU 收货。勿自动清缓存、抽料或重单。

NEI 提供配方、用途及结构候选。保留原始 recipeIndex、上下文、分页、概率单位及非消耗工具条件。库存、超频、安装和成型须实测。ghost 用目标配置 API，验收同步后释放。生存模式不得用 give/take 配置幽灵槽。

SFM/OC 的数量、组件地址与恢复方法，以及魔法、星系模组的条件，查对应模组接口。勿执行 Wiki 脚本或推测未知 API。

## 跨维度加载、物流与连续运行

验证区块加载、跨维度交付及连续运行时，见 `mcb_help({"topic":"testing"})` 的[跨维度验收](testing_zh.md#跨维度加载物流与连续运行)。

## PersonalSpace 门户 GUI

PersonalSpace 1.0.41 的 `GuiEditWorld` 接入既有 `mcb_gui_widgets` / `mcb_gui_widget_action`。
对已有门户空手使用原生潜行右键打开设置，以当次 `screenId`、`widgetId` 和 `supportedActions` 操作，
无需截图定位。`presetEntry` 编辑预设，开关和昼夜按钮支持 `set_selected`，滑条支持 `set_value`，
`moreSettingsButton` / `backToMainButton` 切页，`save` 保存，`cancel` 取消。
方块下拉框通过 `set_text` 原生搜索，读取 `options` 的原始 `index` 后 `set_selected`；
`addMode=true` 每次选择会添加一层，不应重复提交。下拉框打开时先对其 `activate` 关闭，才能操作其他控件。
适配复用五秒逻辑悬停维持原生下拉框，不移动系统鼠标；超时后按原生规则关闭，下次选择仍使用原始选项编号。

该模组在自然渲染帧中解析预设、文本和滑条，`commit=true` 将本地失焦延后到该帧完成，防止扩展字段被旧模型覆盖。
保存前确认 `pendingNativeFrame=false`，保存后重新打开并读取实际值，才可确认持久化。
禁用的地形生成设置仍受模组限制；地层行控件每帧重建，短暂行编号只读，
用预设文本及添加地层下拉框编辑，不重放旧编号。滑条按原生像素量化，检查实际回读。
适配只使用原生控件输入，不写维度模型或 NBT；未装 PersonalSpace 时没有硬依赖。

## 实现与版本参考

- 当前 MCB `MachineDataAdapter`，`AeDataAdapter`，`AeWorldAdapter`，NEI view 与 guarded workflow 的实际实现。
- GT5U `5.09.54.205` sources.jar：`gregtech/api/metatileentity/implementations/MTEMultiBlockBase.java`，`gregtech/api/logic/ProcessingLogic.java`。
- 使用 AE2 1080 或 AE2FC 1.5.113 时，按当前客户端报告的能力和覆盖范围选择操作。

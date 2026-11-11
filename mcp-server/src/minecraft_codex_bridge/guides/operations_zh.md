# GTNH 操作检查与边界

[English](operations.md) | [简体中文](operations_zh.md)

供人工阅读和审核；Agent 使用英文入口，完整规则见[启动模板](workspace_zh.md#可复制的启动提示词)。

适用 Minecraft 1.7.10。使用现有工具组合所需操作检查。 实际能力以当前 health 与返回 coverage 为准。`mcb_operation_check` 是独立排错入口；既有搭建、配方与 QA 工作流已完成的预检和验收不要重复调用。

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
{"kind":"machine","expectedSessionId":"CURRENT_SESSION","expectedWorld":"CURRENT_WORLD","target":{"x":0,"y":64,"z":0,"dimension":0}}
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
{"kind":"process","process":{"machines":["reactor"],"resources":[{"id":"ore","identity":{"kind":"item","registryName":"example:ore","metadata":0},"role":"feed","initial":"6"},{"id":"metal","identity":{"kind":"item","registryName":"example:metal","metadata":0},"role":"product","initial":"0","expectedFinal":"2"}],"recipes":[{"id":"convert","machine":"reactor","source":"REPLACE_WITH_OBSERVED_RECIPE_AND_VERSION","batches":2,"inputs":[{"resource":"ore","amount":"3"}],"outputs":[{"resource":"metal","amount":"1"}]}]}}
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

拆除前区分完整方块与 AE 同格部件。具体工具手势依当前版本核对；整格挖掘可能破坏其他部件或后方方块。拆线缆、面板、接口使用兼容的原生扳手潜行右键，绑定实际 targetPart，并验收服务端剩余部件。回收物品可能保留配置 NBT，不能只按注册名重建或补发。

AE 负责网络资源、样板、发配和收货；处理样板不执行机器配方。校验催化剂、非消耗工具、随机产物、容器返还、替代和数量。NEI 转移后用 AE 同步数据验收，幽灵配置不等于实际原料。AE resourceId、幽灵槽与玩家真实 slot 不共用编号。

预览 requestedPending 或 startReady=false 时读取现有预览同步，不重发 craft_request。CPU 取消后仍可能有接口待发送物料；将 CPU、明确接口宿主、机器库存和产物返回网络分别核对。ae_buffers 只覆盖指定宿主，不证明所有接口或全网都已清空。普通外部库存可见性不等于 CPU 已收货。不自动清缓存、抽料或重新下单。

NEI 负责当前配方/用途/结构候选，不代表真实库存或真实超频结果。保留原始 recipeIndex、上下文、分页、概率单位和未消耗工具语义。模板与投影不证明安装或成型。原生 ghost 标记使用目标支持的配置 API；同步验收后释放残留 ghost，不在生存模式 give/take 材料来配置幽灵槽。

SFM/OC 的数量、组件地址与恢复流程，以及魔法/星系的特殊条件仍属于对应模组。本入口不执行 Wiki 脚本，不推断未知控制 API，不把这些语义混入 GT/AE/NEI 操作。

## 跨维度加载、物流与连续运行

ServerUtilities 2.4.14 的认领和强加载分别验收。先核对 world.chunk_claiming/chunk_loading、ranks 是否启用及当前有效权限，将机器完整结构、外部仓室、网络、供电和存储端点的区块并集计入团队跨维度总配额。原生 /chunks claim、/chunks load 作用于玩家当前区块，依次提交并确认效果。submitted、无聊天消息或较旧存盘文件都不能证明当前加载状态；原生通知、后续实际保存及离开后的运行证据分别判断。不编辑世界或强加载持久化文件。

AE2 1080 普通无线连接器只支持同维度。跨维度量子桥用原生爆炸生成的一对纠缠奇点，两端各八个环围住一个链接仓，各放一个保留完整 freq 的奇点，分别供电并加载。外部网络接非角环。使用普通 GUI 槽搬运带 NBT 奇点，不传给仅接受 hasTag=false 的 mcb_inventory_moves。两端同一当前 grid ID 和 active 只证明连接；空有限盘与一批确定数量物品的来源减少、目的 provider 增加才证明交付。再从第三维度观察两端，不用玩家自然加载掩盖失效。

ME 箱子正面打开元件终端，其他面打开元件配置；正面没有可读盘时可能仅有提示。按实际方向选择可见 side，以 ContainerChest 的 STORAGE_CELLS 真槽安装有限盘。EnderStorage 1.8.7 的两端频率和 owner 必须一致；末影袋原生绑定后等待实际 NBT 同步。GT 仓室物流先核对真实工作面，再让漏斗或相邻库存按该面连接。手持门户搬运不等于自动 IO。

PersonalSpace 1.0.41 门户先核实目标地形、脚底支撑、完整身体净空和返程，不能相信未加载地形上的默认高度。跨维度后重读当前连接和双端飞行样本；门户内 onGround 不证明完整身体净空。确认安全方向后可正常跳跃/步行离开，然后复用接近规划。起飞包提交后等待双端 flying 与姿态稳定，再追加升降输入；已落地可继续步行。不用 TP 代替门户，门户不计入火箭出行验收。

操作时复用 mcb_spatial(mode=positions) 核对已加载目的地的支撑、危险和身体净空；未知地形保持未知，不为检查强行加载。需要起飞时优先 mcb_flight_check(action=probe)，其双端确认与受检竖直路线完成后再导航；mcb_flight 的 submitted 回执仍只表示提交。GT 放置可用 expectedPlacementFacing 约束实际视线；创造模式须具备 placementFacingCreativeVersion=1，并验收 serverConfirmed、serverSourceConsumption=0 与实际机器朝向。替换仓室后重新触发原生结构检查，再读取 structureFormed，不沿用替换前的成型结果。

原生 GUI 同时显示旧的“发电中”配方结果和“由于结构不完整关机”时，结合当前 active/progress 判断；按 GUI 给出的无效方块坐标读取现场，再做原生修补、结构复查和启用。mcb_gui_widgets 的空分页可能只是 soft budget 耗尽，应沿 nextOffset 继续；不能当作按钮不存在。飞行为绕过下降障碍临时保持高度时，最终水平位置就绪后必须恢复真实目标高度；仍逐步验证完整身体和惯性路径，不以临时高度判定到达。

完整生产链由当前任务组合通用机器与物流操作，不新增专用整链工具。以有限输入、完整产物、正常 tick、实际吞吐和停止证据验收；科学计数缩写不能用于精确余额差，创造/debug 供料不能证明生存成本或净效率。

上述机制依据 SU CmdLoad/ClaimedChunks、AE2 QuantumCluster/TileQuantumBridge/EntitySingularity/WireLessToolHelper、PersonalSpace PortalTileEntity、EnderStorage 原生库存。mcb_knowledge 的本地经验保留 evidenceIds 与适用连接，只作提示，不自动重放动作或将代理解释升级为已验证策略。

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

## 来源与验证层级

- 当前 MCB `MachineDataAdapter`，`AeDataAdapter`，`AeWorldAdapter`，NEI view 与 guarded workflow 的实际实现。
- GT5U `5.09.54.205` sources.jar：`gregtech/api/metatileentity/implementations/MTEMultiBlockBase.java`，`gregtech/api/logic/ProcessingLogic.java`。已核源码，不代表本次游戏运行验收。
- AE 1080/AE2FC 1.5.113 的教程结论保留文档级证据；部分原生适配最初按 AE 1073 实现，当前支持以实时 capability 和 coverage 为准。

# BridgeNH 分层测试与可选 QA

适用：Minecraft 1.7.10，MCB 的现有基础测试、Horizon-QA/TST-QA 公开适配和真实客户端操作。本指南通过 `mcb_help({"topic":"testing"})` 离线读取。

## 按改动选择验证层

先写清本次要证明的行为、预期结果和环境，再选择能覆盖它的最小测试集合。日常下单或移动不需要先跑开发测试；没有源码的安装用户也不必构建项目。修复后优先跑相关回归，通过后仅在新改动、失败或未覆盖风险需要时扩大范围。

| 改动或目标 | 基础测试 | 可选游戏内 QA | 真实客户端/服务端验收 |
| --- | --- | --- | --- |
| 参数校验、数量计算、回执与等待 | 已有 Python/Java 单元及协议测试 | 仅涉及真实游戏执行时选用 | 检查修改影响的实际工具路径 |
| 配方消耗、产出、ME 缓存和交付 | 身份、NBT、long 数量、失败边界 | 复用有限配方、ME 输出及通用声明流程 | 保留实际物流/下单/取货的必要验收 |
| 结构朝向、仓室注册、IO 布局 | 已有坐标变换与绑定测试 | 已有用例或通用声明能表达时复用 | 实际放置与结构检查；朝向来自当前公开变换 |
| 飞行、接近、射线、挖掘、GUI | 对应控制、同步与失败回执回归 | 服务端用例只能覆盖局部逻辑 | 必须走 Agent 实际使用的客户端操作路径 |
| 跨维度、长周期、整条生产链 | 对应边界和协议回归 | 适合覆盖有限局部环节 | 正常世界 tick、实际区块加载、输入输出和目标运行时长 |
| 指南、安装和配置 | 解析、打包、离线读取及配置保留 | 不需要 | 需要时新 MCP 连接与普通只读接口 |

基础测试、游戏内 QA、客户端集成服务端、独立服务端是不同证据层。单元测试的模拟游戏不算实机；QA 通过不替代 GUI 或真实玩家操作验收；单人通过不自动推广到独立服务器。

源码开发复用现有 `mcp-server/tests` 的相关 pytest 文件和项目根 Gradle 的 `bridgeSelfTest`。Java 修改按项目要求编译。安装包不携带开发测试和编译依赖，不要求普通用户补装它们。构建、依赖及本机路径沿用当前项目配置。

任何步骤必须借助桌面 Computer Use 时，须立即[按错误主动反馈](workspace_zh.md#原生控制缺口与必要反馈)，并单独记录任务结果。该步骤通过预期接口端到端完成后，方可通过原生验收。

验收 Windows 窗口控制时，在所选客户端验证启动模式，并重复执行还原、缩放、最大化、最小化序列。检查原生窗口状态、帧缓冲尺寸及任务栏按钮，在操作全过程记录前台窗口和键盘焦点变化；操作前后句柄相同不能排除短暂输入中断。被动读取操作系统状态属于测量，不属于客户端控制。Prism 启动前对话框应单独验收；游戏窗口通过测试不能证明启动器启动期间输入始终连续。

## QA 可选，普通服务器不依赖 QA

- MCB 提供通用操作及 QA 桥接。Horizon-QA 提供测试框架，TST-QA 提供相关用例和私人维度公开适配。它们不是日常操作、服务器聊天、权限配置或普通读取的前置条件。
- 普通安装只安装 MCB，客户端前置模组为 UniMixins、StructureLib、lwjgl3ify。不自动安装、启用或配置 Horizon-QA/TST-QA；不为进入某个测试层提升游戏权限、切换策略或更换实例。
- 仅当任务适合且已获准进行游戏内测试时发现 QA 能力。先复用当前 health 的连接/权限信息；需要选择用例时读取 `mcb_qa_catalog({"section":"capabilities"})` 和有界 `tests` 查询。工具目录列出 QA 名称不证明模组已安装、可执行或已经执行过。
- 缺少模组、模式关闭、公开 API 不兼容、权限不足或用例未覆盖时，如实记录 QA 层未执行及原因，继续独立可做的基础测试和已授权真实操作。不把未执行写成通过。如果用户要求的结论必须依赖该层，保留该结论未验收并说明缺口；不静默降低验收要求。
- QA 运行失败与 QA 不可用分开处理。已提交、超时或结果未知时先追踪同一次运行，不得以 QA 为可选项为由忽略失败，也不得自动重新运行或更换路径重复产生副作用。

## 复用现有 QA 执行流程

QA 使用现有策略作用域，保存在 `config/codexbridge/policies.json`，单人存档和服务器地址各自沿用上次选择。`mcb_policy` 与本地 `/mcb policy qa auto|off|observe|test|manage` 共用接口。`auto` 默认对应 survival=off、survival_efficient=observe、creative=test、full=manage；显式 QA 档位只能收缩主策略的上限。降低主策略时立即收缩有效权限，不自动提升为 full。

- `off`：仍可查询 capabilities；不开始观察其他运行或创建测试。
- `observe`：查询目录、状态和结果；不创建、清理测试区。
- `test`：在专用区域精确选择 QA 提供方声明的受控用例，或已有通用声明流程。当前 TST-QA 将审查过的有限回调与声明流程标为 `tstqa-controlled-policy-v1`；任意 Java 回调不视为沙箱。创造策略不能执行未声明用例、全局 runall 或诊断管理命令。原生全局运行器不能证明此契约时须返回不可用，不能自动提权。
- `manage`：允许现有 QA 管理能力，仍需真实单人/服务器权限，并遵守作用域和归属校验。QA 的 tp 还需 movement=tp 和实际指令权限。

策略不会授予服务器 OP；聊天的 AGENT/管理员身份也不代替 OP。`readOnly` 禁止新测试和测试区清理。降级或只读后，已拥有任务仍可按显式 runId 查询、请求现有正常停止和释放已结束记录；不可借此读取其他玩家运行或全局停止。停止沿用提供方能力：当前私人运行器只支持仍在运行的 debug-source 用例正常停料排空，不新增对有限回调的强制中断；已完成任务会返回已结束。`mcb_qa_command(command="clear", args=[runId])` 只清理本次拥有且已结束、方块未被外部修改的测试区；`forget` 只释放记录，保留方块。以能力查询确认当前运行器支持这些操作。

缺少 QA、远端未安装或策略关闭时，capabilities 返回 `available=false` 和原因，不把可选依赖缺失变成普通操作故障。预派发拒绝明确标为未执行，与提交后超时/结果未知区分；后者必须追踪同一次运行，不自动重试。

1. 绑定实际实例、存档/服务器、会话、维度和权限。远程执行还需当前服务端明确支持 QA。选择已授权的专用测试区域；QA 的准备可能放置或清除方块，不在生产设施上运行。正常世界与测试世界的选择沿用本任务授权，不因指南自行建立或重置存档。
2. 优先查找已安装的用例。有限配方、ME 输出和数值边界优先复用现成用例。新机器参数在已支持范围内用不可变 `machineFlows` 声明，不为每台机器添加专用 MCB 工具或完整生产链入口。不能表达的机制记录缺口，不得将替代配方验证视为对目标机制的覆盖。
3. 已知目标用例后优先使用 `mcb_qa_test_once`。它内部完成新鲜目录/测试区计划、空间预检、一次运行、等待、结果分页及运行后检查；不在 Agent 侧重复同一套预检。选择用例时的查询只用于决策，不替代执行时的新鲜校验。现有整体时间、请求和空间预算保持有效；不承诺一次执行任意长的整链。
4. 有限机器全流程要求显式 `requireFullFlow=true`。只有目标本来就是原生断言或旧用例范围时才使用 `false`，并说明其边界。通用声明组复用当前工具的 1..10 条限制及每条实际身份、配方与测试区证据；不用模糊名称或旧 ID 填参数。
5. 需要分阶段或已有运行时，复用 `mcb_qa_run/status/results` 的实际 runId。私人维度是否支持有界等待以实时能力为准；支持时使用已有 `waitMillis`，其余 Agent 侧查询通常约 1 秒一次。不增加外层高频轮询，不改写工作流已有等待器。
6. 无限诊断不属于有限通过判定。如任务确实需要，使用已有直接运行/状态与正常停止入口，分别保留观察就绪、量化产出、停止和恢复证据。停止仅针对本次拥有的运行，不调用全局取消。

QA 可固定测试输入和预期，MCB 的实际操作接口仍保持通用。原生放置、GUI 输入、AE 下单等用户操作验收继续临时组合已有工具。已有工作流带有验收时直接复用其证据，不为每层再次搭建整套机器。

## 结果和时间必须真实

- `submitted` 只证明提交；`testPassed` 与 `fullFlowVerified` 是不同结论。完整流程须包含真实准备、输入消耗、实际产出、取回、停止及要求的稳定观察。以当前工具明确的 coverage、数量和同次运行证据为准。
- 结果分页必须属于同一 runId。`partial`、截断、旧报告、未验证归属和缺失字段保持未知。原生全局运行状态不能视为归属于本次运行的结果。
- 保留完整物品身份/NBT和十进制 long 数量。ME provider 本地缓存、进入存储盘、CPU 收货和玩家取货分别验收。创造供料或注入边界数据只证明对应层，不证明生存造价、真实配方制造或容量上限。
- GT 专用快进只覆盖测试区域内支持的 GT 方块和供能任务，不推进 AE 或全局世界时间。AE 交付、跨维度物流和时序相关验收使用正常 tick。当前有限工作流的正常时序要求不能由外部快进绕过。
- 统计从准备到验收的总耗时、Agent 工具调用、内部请求及必要的恢复成本。快进/模拟结果与正常世界时间分别记录，不将内部执行时间视为整项耗时。
- 清理只处理本次明确拥有、范围完整且状态已核对的测试产物。当前 QA `forget` 释放的是已完成运行的记录/租约，不代表方块已删除。保留仍需诊断的现场；不扩大为整片世界清理或回滚。

## 将结果接入项目记录

复用 [workspace.md](workspace_zh.md) 的 `tasks/<task-id>/state.md`，不另造平行日志。每层保留以下字段；这是任务报告格式，不是新增 MCP 参数:

```text
目标/改动与验收条件:
环境: 版本, 实例/服务器, 世界/维度, 实际权限与策略
基础测试: passed | failed | skipped | unavailable, 命令与证据, 未覆盖范围
游戏内 QA: passed | failed | skipped | unavailable, 原因/选择依据
QA 关联: 实际 selector/runId, testPassed, fullFlowVerified, 覆盖/分页及时间方式
客户端/独立服务端: 分别记录实际执行内容和结果, 不相互替代
外部协助是否需要或已使用、尚未解决的原生控制缺口:
剩余缺口/未知动作/恢复状态/测试产物清理:
总耗时和调用成本:
```

发现问题时优先补入最靠近根因的现有回归集合。经验文件记录触发条件、处理与证据；知识库只保存可复用机制及适用范围。未安装 QA 的普通服务器也可维护这些记录，并继续使用正常 MCB 功能。

## 按目标值配置创建世界选项

使用当前 `screenId` 读取 `mcb_gui_widgets`，设置 `actionableOnly=false` 以包含另一页面的选项。原版创建世界选项提供 `setting`（`game_mode`、`world_type`、`more_world_options`、`map_features`、`allow_commands`、`bonus_chest`）、当前 `selected`/`value` 和 `options[].index/value`。将目标值匹配到实际返回的索引，不根据翻译后的按钮文字或过滤数组位置推测索引。

调用一次 `mcb_gui_widget_action(action="set_selected",widgetId=...,selected=...)`，工具会执行所需的原生切换，并在同一回执中返回 `localSelectionMatched`、`after` 和 `nativeSelection.afterState`。已满足目标时不调用按钮。已确认的本地菜单值不需要再次点击或另行读取以重复确认同一选择；需要服务器同步的游戏效果仍应单独验收。

多个选项使用 `action="set_selected_many"`，提供 1..8 个不同控件的 `selections:[{widgetId,selected}]`，不使用顶层 widgetId。使用当前控件身份及实际选项索引，按需要修改的控件可见性排列顺序，例如先选创造模式，再进入更多选项页面，最后选择超平坦。组合前使用 `actionableOnly=false` 读取；隐藏页面的控件身份仍可观察，但修改前须先切换页面。已满足目标的隐藏选项无需切换页面。

仅在 `localSelectionMatched=true` 且最终 `afterState` 与请求一致时，确认整组设置完成。`status=partial` 时检查各项回执和当前值，再处理未完成部分，不重放整个批次。创建及取消按钮不能加入批次。游戏模式与作弊默认值联动、世界类型切换时清空自定义预设等行为仍由原生逻辑处理。结果只配置待创建世界的菜单值，不创建或保存存档。需要组合更长的菜单流程时，使用下述 `mcb_sequence` 菜单模式。

## 跨维度加载、物流与连续运行

ServerUtilities 2.4.14 的认领和强加载分别验收。先核对 world.chunk_claiming/chunk_loading、ranks 是否启用及当前有效权限，将机器完整结构、外部仓室、网络、供电和存储端点的区块并集计入团队跨维度总配额。原生 /chunks claim、/chunks load 作用于玩家当前区块，依次提交并确认效果。submitted、无聊天消息或较旧存盘文件都不能证明当前加载状态；原生通知、后续实际保存及离开后的运行证据分别判断。不编辑世界或强加载持久化文件。

AE2 1080 普通无线连接器只支持同维度。跨维度量子桥用原生爆炸生成的一对纠缠奇点，两端各八个环围住一个链接仓，各放一个保留完整 freq 的奇点，分别供电并加载。外部网络接非角环。使用普通 GUI 槽搬运带 NBT 奇点，不传给仅接受 hasTag=false 的 mcb_inventory_moves。两端同一当前 grid ID 和 active 只证明连接；空有限盘与一批确定数量物品的来源减少、目的 provider 增加才证明交付。再从第三维度观察两端，不用玩家自然加载掩盖失效。

ME 箱子正面打开元件终端，其他面打开元件配置；正面没有可读盘时可能仅有提示。按实际方向选择可见 side，以 ContainerChest 的 STORAGE_CELLS 真槽安装有限盘。EnderStorage 1.8.7 的两端频率和 owner 必须一致；末影袋原生绑定后等待实际 NBT 同步。GT 仓室物流先核对真实工作面，再让漏斗或相邻库存按该面连接。手持门户搬运不等于自动 IO。

PersonalSpace 1.0.41 门户先核实目标地形、脚底支撑、完整身体净空和返程，不能相信未加载地形上的默认高度。跨维度后重读当前连接和双端飞行样本；门户内 onGround 不证明完整身体净空。确认安全方向后可正常跳跃/步行离开，然后复用接近规划。起飞包提交后等待双端 flying 与姿态稳定，再追加升降输入；已落地可继续步行。不用 TP 代替门户，门户不计入火箭出行验收。

操作时复用 mcb_spatial(mode=positions) 核对已加载目的地的支撑、危险和身体净空；未知地形保持未知，不为检查强行加载。需要起飞时优先 mcb_flight_check(action=probe)，其双端确认与受检竖直路线完成后再导航；mcb_flight 的 submitted 回执仍只表示提交。GT 放置可用 expectedPlacementFacing 约束实际视线；创造模式须具备 placementFacingCreativeVersion=1，并验收 serverConfirmed、serverSourceConsumption=0 与实际机器朝向。替换仓室后重新触发原生结构检查，再读取 structureFormed，不沿用替换前的成型结果。

原生 GUI 同时显示旧的“发电中”配方结果和“由于结构不完整关机”时，结合当前 active/progress 判断；按 GUI 给出的无效方块坐标读取现场，再做原生修补、结构复查和启用。mcb_gui_widgets 的空分页可能只是 soft budget 耗尽，应沿 nextOffset 继续；不能当作按钮不存在。飞行为绕过下降障碍临时保持高度时，最终水平位置就绪后必须恢复真实目标高度；仍逐步验证完整身体和惯性路径，不以临时高度判定到达。

完整生产链由当前任务组合通用机器与物流操作，不新增专用整链工具。以有限输入、完整产物、正常 tick、实际吞吐和停止证据验收；科学计数缩写不能用于精确余额差，创造/debug 供料不能证明生存成本或净效率。

上述机制依据 SU CmdLoad/ClaimedChunks、AE2 QuantumCluster/TileQuantumBridge/EntitySingularity/WireLessToolHelper、PersonalSpace PortalTileEntity、EnderStorage 原生库存。mcb_knowledge 的本地经验保留 evidenceIds 与适用连接，只作提示，不自动重放动作或将代理解释升级为已验证策略。

## NEI 版本参考

书签增补来源：[NotEnoughItems 2.8.155-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.155-GTNH/NotEnoughItems-2.8.155-GTNH-sources.jar)，SHA256 `fd7efd4ad77a7d250b2d2e9854cba7d643222a941cc28a4c808937505c0b2973`。源码默认键不能证明当前内存绑定；应读取当前客户端。

- 2.8.155 默认绑定/修饰键：`NEIClientConfig.setDefaultKeyBindings:850-899`，`KeyManager.isKeyDown/isHashDown`。
- 物品/配方 toggle、数量保留、V 与组删除：`api/ShortcutInputHandler.java:81-178,355-475`；favorite 树：`recipe/GuiFavoriteButton.java:125-205`；优选替代原料：`recipe/widget/RecipeWidget.java:463-470`。
- 删除范围、拖排、工具区、滚轮、取物准备：`BookmarkPanel.java:318-373,555-606,652-903,988-1127`；组作用域：`bookmark/BookmarkGrid.java:318-343,1153-1269`，`bookmark/SortableItem`。
- 数量控件、0 回退和有限/单件领取：`ItemQuantityField.prepareStackWithQuantity/onMouseWheel`，`PanelWidget.mouseUp/onMouseWheel/handleKeyPress`，`NEIClientUtils.cheatItem:212-235/getScrollMultiplier:567-575`。
- MCB 能力核对：`NeiControlAdapter`，`NeiOverlayAdapter.bookmark`，`NeiItemControlAdapter`，MCP `catalog/nei.py` 和 `catalog/gui.py`。

精确版本源码：[NotEnoughItems 2.8.145-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.145-GTNH/NotEnoughItems-2.8.145-GTNH-sources.jar)。源码归档 SHA256：`d4ede32d2bd510e4d5312f79a2012da79ea7fec7f5eab23c52064e4377af3c5e`。

- 默认/实际按键加载：`NEIClientConfig.java:849`，`KeyManager.java:40,69,153`。
- 悬停与查询/书签：`RecipeItemInputHandler.java:35`，`api/ShortcutInputHandler.java:154,166,307,355`，`recipe/GuiUsageRecipe.java:112`，`BookmarkPanel.java:318`。
- 焦点/导航/滚轮：`recipe/GuiRecipe.java:621,738`，`PanelWidget.java:281,308`，`recipe/NEIRecipeWidget.java:175,486`。
- 搜索：`SearchField.java:210,230,249`，`TextField.java:60`，`SearchTokenParser.java:31,127,339,477`，`search/ModNameFilter.java:35`。
- 数量与配方填料：`ItemQuantityField.java:108`，`BookmarkPanel.java:1026,1130`，`bookmark/BookmarkGrid.java:1214`，`recipe/GuiOverlayButton.java:124,204,331,344`，`recipe/DefaultOverlayHandler.java:72`。
- 收藏树：`recipe/GuiFavoriteButton.java:80,125,184,203`，`recipe/NEIRecipeWidget.java:303`。

世界设置可以覆盖 NEI 模式。按键冲突时，读取当前绑定和提示。化学式搜索取决于当前搜索提供方，配方填料取决于目标界面的处理程序。

## AE2 源码依据

AE2 证据为 [rv3-beta-1073-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Applied-Energistics-2-Unofficial/rv3-beta-1073-GTNH/Applied-Energistics-2-Unofficial-rv3-beta-1073-GTNH-sources.jar)，及相关配置条件。类名行号均对应该版:

- 虚拟槽/点击/键状态：`AEBaseGui.java:410,768,1101,1111,1125`，`GuiMEMonitorable.java:681,701`，`GuiPatternTerm.java:237`，`ServerHelper.java:45`。
- 存取/滚轮：`GuiMEMonitorable.java:705,715,737,741,771,778,813`，`ContainerMEMonitorable.java:669,691,717,739,764,782,815,970,1207,1218`。
- 搜索：`MEGuiTextField.java:123`，`GuiMEMonitorable.java:903,966,1142`，`ItemRepo.java:253,280,410`，`NEISearchField.java:85`。
- 样板数量/倍率/编码：`VirtualMEPatternSlot.java:24`，`VirtualMEPhantomSlot.java:96`，`GuiPatternValueAmount.java:61`，`GuiPatternTerm.java:97,118,130`，`ContainerPatternTerm.java:71,269,579,586,638`，`GuiAmount.java:181,237`。
- 32/8 增广：`PartPatternTerminalEx.java:17,79`，`GuiPatternTermEx.java:163,190,237`。
- 合成/接口：`GuiCraftAmount.java:134,188`，`PacketCraftRequest.java:131`，`GuiCraftConfirm.java:346,427,978,1100`，`GuiCraftingCPU.java:169`，`GuiInterfaceTerminal.java:187,1091,1112,1140,1277,1314,1640`。

扩展源码：[NEE 1.7.45](https://github.com/GTNewHorizons/NotEnoughEnergistics/tree/1.7.45/src/main/java/com/github/vfyjxf/nee)，`NEEPatternTerminalHandler.java:55,213`，`NEECraftingTerminalHandler.java:53`，`GuiEventHandler.java:247,251,333`，`GregTech5RecipeProcessor.java:77,105`，`PacketNEIPatternRecipe.java:79,94`，`NEECraftingPreviewHandler.java:62`。[BogoSorter T 悬停搜索](https://github.com/GTNewHorizons/InventoryBogoSorter/blob/1.3.54-GTNH/src/main/java/com/cleanroommc/bogosorter/compat/ae2/Ae2TerminalSearchAdapter.java#L37)。NEI Shift 门控：`IOverlayHandler.java:33`，`GuiOverlayButton.java:133,331`，`NEIClientConfig.java:1168` (2.8.145-GTNH)。

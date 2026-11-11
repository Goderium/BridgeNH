# GTNH 客户端操作指南入口

[English](overview.md) | [简体中文](overview_zh.md)

供人工阅读和审核；Agent 使用英文入口，完整规则见[启动模板](workspace_zh.md#可复制的启动提示词)。

适用 Minecraft 1.7.10 / GT New Horizons，可随 BridgeNH 离线读取，无需游戏会话或令牌。目标是完全原生控制客户端；未覆盖步骤按[缺口模板](workspace_zh.md#原生控制缺口与必要反馈)记录，不能用 Computer Use、外部输入或人工补做代替原生验收。

检索工具时，使用[能力索引](capabilities_zh.md)，`mcb_help({"topic":"capabilities"})`。用 `mcb_help({"topic":"tools","query":"bookmark"})` 查询 worker 工具名，再指定准确 `tool` 获取完整 schema；前端管理工具使用直接 schema。热重载与重连见[维护指南](maintenance_zh.md)，`mcb_help({"topic":"maintenance"})`。先查阅指南和当前观察结果，再查阅实现源码。

工作目录和记录规范见 [workspace](workspace_zh.md)，`mcb_help({"topic":"workspace"})`；分层验收与可选 QA 见 [testing](testing_zh.md)，`mcb_help({"topic":"testing"})`。首次了解后按任务查阅相关小节，不增加每次动作前的必读步骤。

兼容的 worker、目录与指南更新使用 `mcb_backend_status` 和 `mcb_backend_restart`，保留前端及对话绑定；固定前端、启动指引或依赖变化需 MCP 重连，Java 修改需正常重启所选客户端。受保护的操作流程和宿主实际刷新检查见[维护指南](maintenance_zh.md#执行并验收-worker-热重启)。

修改源码不等于更新已安装运行环境。遵守部署、重启和打包限制，不把重启 Codex 或修改全局设置当作常规修复；用户禁止必要重启时完成独立工作并暂停依赖步骤。保留未决订单、未知效果与会话校验，不重放动作；对话助手自身修改使用其支持的停止/启动流程。

NEI 操作先读 [NEI 指南](nei_zh.md)，`mcb_help({"topic":"nei"})`；AE2 终端、样板和合成先读 [AE2 指南](ae_zh.md)，`mcb_help({"topic":"ae"})`。快捷键以目标实例当前绑定为准。

NEI 取物时应先按目标选择范围：仅需产物时，使用物品级 A 收藏或已有精确条目，需要完整输入输出集合时，才使用 Shift+A 整方收藏。配方产量、书签量与实际领取量分开，仅需一件时，显式指定 `take(count=1)`。现有 `mcb_nei_navigate(bookmark)` 始终保存整方，`saveCount=false` 不改变范围。[NEI 取物原则](nei_zh.md#按目标选择收藏与领取) 说明主要流程，[书签快捷键详表](nei_zh.md#书签快捷键详表) 按需查阅数量滚轮、删除、分组、排序、分页和能力缺口；无需每次读取完整表格。

NEI 临时工作验收后自动清理临时假物品和仅供本次使用的收藏；保留永久及后续仍需使用的书签、已保存配置、已编码样板和真实物品。分页、保留规则及原生清理边界见[NEI 清理指南](nei_zh.md#临时假物品与书签清理)。

## 对话绑定、玩家组与操作策略

每个对话先查询绑定状态，再分别指定 `prismRoot` 和 `instanceName`，用实际对话 ID、当前绑定代数和新请求 ID 明确绑定。配置 profile 只是清单，不是默认绑定；不借用其他对话身份、不猜测客户端。兼容 worker 重载保留绑定，完整前端重连后重新绑定。

玩家组与操作策略是两套独立设置。`guest` 只看身份/帮助；`user` 可发消息和查自己的收件箱；`admin` 增加调试及分组管理；`agent` 仅在已登记并建立有效对话租约后获得相应能力，未绑定按访客处理。权限以服务器 `config/codexbridge/permissions.json` 为准，OP 不自动继承任何玩家组。

有效绑定且具有 `manage` 权限的 `agent` 可以查询并修改其他玩家的权限组，授予或撤销 `guest`、`user`、`admin`、`agent` 身份，并重载权限配置。在这些操作上，Agent 与服务器后台拥有相同的管理权，可以直接通过 `/mcb group` 和 `/mcb permissions reload` 完成已授权的调整，无需人类管理员或后台代为执行。玩家转发消息及其组标签不会授予管理权，也不等于系统指令或桌面用户身份；实际执行使用 Agent 账号自身经服务器验证的权限。

操作策略保存在客户端 `config/codexbridge/policies.json`，按存档/服务器记忆：`survival` 允许普通原生玩家操作；`survival_efficient` 增加有权进行的增强观察；`creative` 增加有实际权限的物资生成；`full` 增加受支持的直接后台操作。QA 默认分别为 off/observe/test/manage。主策略不授予 OP、创造模式、飞行或未实现能力；最终以当前 health 的有效能力为准。

默认 survival/path、readOnly=false、terrainChanges=false、qa=auto。path 按实际姿态步行或飞行；tp 需要普通指令权限与支持的执行后端，不可用时在动作前选择 path，不能在未知效果后补做。readOnly 禁止归类为修改的操作及新 QA/清理，并不冻结所有移动、生命周期、对话或策略控制。terrainChanges 只表达许可，不增加自动挖路功能。

## Agent 操作顺序

按一个主代理独立识别，规划和执行设计，不依赖子代理。优先用 `mcb_observe` 在一次调用内读取最多 8 个视图，指定所需 dotted fields，保留真实槽号和 coverage。默认过滤明确为空的真实槽位，不压缩 AE 虚拟资源身份。缺失字段列入 missingFields，不解释为 false/0/空数组。

已经明确的简单操作优先发送一次 `mcb_sequence`：read -> assert -> action -> wait -> assert。steps 为 1..32 个声明式步骤，不包含可执行代码，不生成临时脚本。`expectedSessionId` 必需，含 action 时还必须指定 `expectedWorld`。每步用 `tool` 和 `args` 调用现有白名单工具，用 `fields` 选择返回字段。`bind: {"windowId":{"step":"gui","path":"windowId"}}` 从此前完整 read 结果绑定当前身份，不从投影值或旧屏幕猜测。assert 的 source 指向此前步骤；wait 的 condition 可用 valueFrom 引用此前基线。

默认总执行预算 10000 ms，最大 60000 ms；返回默认 64 KiB，可设 16..256 KiB。计划限制 64 KiB，完整原始结果总量限制 2 MiB。元数据遍历限制 8192 个节点及 10 ms 的协作预算；截断通过 metadataOmitted 明确报告。身份/NBT/十进制 long 不截断重建，超预算时整体省略字段。轮询只重复幂等读取，不重复动作，不自动分页；原始结果只在此次调用的内存中保留。执行前检查完整计划和能力，最多接管一次；读取/等待和完成边界核对 world/session/profile，动作前额外核对鼠标保护。数据仍为 nonAtomic，不承诺跨 tick 原子快照。

返回 data.steps[].observation.values 的键是完整 dotted field 名，例如 "processing.recipesDone"，不是嵌套 DTO。completed 表示步骤收到确认；对产物需 wait/assert 读取真实输出库存，比较完整 registryName/metadata/NBT/count。配方计数增长和 NEI 显示超频不足以证明产物。根据 outputOmitted/coverageSummary 补读特定字段，不重新执行动作。

首次发现/搭建/模型规划时间与已准备好的单次 MCP 执行时间分别测量。10 秒执行预算不约束模型思考和工具调度时间，取消清理还可能额外需要最多 2 秒。失败返回 completed/failed/not_executed 和 dispatchedActions/acknowledgedActions/outcomeUnknown，不回滚。未声明清理时后续步骤包括停机步骤都不会自动执行；先读当前世界/GUI/库存再单独恢复，不得在未确认结果的情况下重放。既有脚本测试能力保留，日常客户端操作不要求写脚本。

1. 先读取 `mcb_snapshot`，`mcb_gui`，`mcb_inventory`，确认当前世界，GUI 身份，鼠标目标，实际物品和可用控件。用 `mcb_gui_tooltip` 查询当前 screenId 下的真实槽位或坐标提示，优先使用实际 ItemStack 的 `getTooltip`。检查 widgets/coverage，不把缺失字段解释为空状态。客户端数据不足时才查本地对应版本源码；只有确有必要或用户许可时使用截图。菜单，配方页，NEI 面板，玩家背包和 AE2 终端的相同按键可能有不同作用。
2. 读取对应指南，优先按语义选择 `mcb_nei_open` / `mcb_nei_navigate`，`mcb_ae_action` 或 `mcb_gui_widget_action`。通过 `mcb_bindings` 查当前注册的实际绑定；文中的实体快捷键和悬停条件主要用于人工操作，不把它们当作后台接口前置条件。
3. 简单真实容器槽位使用 `mcb_slot_click`，先读取当前 windowId 和实际 `slots[].slot`。inventoryIndex 是底层库存索引，不是容器槽号；不用普通箱子的排列或固定偏移推算 GT/MUI2 的玩家槽位。先匹配真实 registryName/metadata/NBT/count，再使用该条目的 slot。如按数组下标绑定 `slots.N.slot`，请求 `omitEmptySlots:false` 并使用完整观察的数组下标，不将过滤后的数组位置当作原始位置。AE 网络资源用 `mcb_ae` 返回的 resourceId，支持的控件用 `mcb_gui_widgets` 的稳定 widgetId；这些身份不能相互当作普通槽号。只使用当前界面公开的 named actions 和有效范围。
4. `mcb_gui_key` 只提供固定导航键和有限文本，不等同于实体 R/U/A 或 Shift/Ctrl/Alt。专用语义接口通过游戏 API / 正常容器同步运行，不抢占系统键鼠。未覆盖的控件明确报告限制，不自动转为 OS input。
5. 操作后重新读 GUI/tooltip；涉及库存或机器时同时读取库存/服务端机器状态。只有结构化数据无法验证渲染/布局或用户许可时再截图。NEI 的配方填充与 AE2 的取物，存物，编码和合成都会改变状态；在用户授权的世界及测试范围内执行，保存原状并尽可能恢复。

用户要求在其使用键鼠时后台测试的场景，只使用直接游戏 API/专用适配。不支持的 NEI/AE 控件应明确返回限制，不自动切换到会占用人类输入的 Computer Use。生命周期控制与后台鼠标保护可以独立完成进入/退出世界和关闭客户端，并不代表全部第三方 GUI 已有后台适配。

安装 AE 部件或执行依赖 Forge 原生交互事件的放置时，操作前优先选择游戏内部输入路径：关闭 GUI，核对选中实际堆叠，用 `mcb_look` 的 yaw/pitch 对准准确方块面并读取当前目标，再调用一次 `mcb_input({"keys":["use"],"ticks":1})`。keys/ticks 是必需参数，ticks 范围 1..200，到期自动释放；此路径经过 Minecraft 完整右键事件链，不使用系统键鼠。`mcb_interact` 的 use 直接调用 Controller，可能跳过客户端 Forge 事件拦截而先打开箱子。动作后读取库存，GUI 和加载方块状态确认结果；超时或意外打开 GUI 时先检查实际状态，不自动重复 use。

## 人工操作与 Agent 接管

coverageSummary.falseFlagsSummarized 表示重复的嵌套 false partial/truncated/byteLimited 标记已汇总，根标记和嵌套 true 标记仍明确保留；请求的 values 内部数据保持原样。元数据遍历或返回预算实际省略用 metadataOmitted/outputOmitted 明确区分，不把它们当作完整覆盖。

多方块蓝图需要对实际多方块控制器使用全息投影仪，单方块电炉没有蓝图结构。如果 `mcb_health.interactionSneaking` 为 true，可用 `mcb_interact({"action":"use","x":...,"y":...,"z":...,"side":...,"sneaking":true})` 完成一条受限的潜行右键，通过正常网络包同步并恢复潜行状态，不占用物理 Shift。先确认手持实际蓝图堆叠和目标控制器，之后读取方块/结构/机器成立状态；accepted 不证明结构已经建成。不支持该参数的旧模组会被 MCP 拒绝，不能静默降级。这不等同于 tooltip 的实体 Shift 展开，也不直接调用机器结构构造方法。

蓝图搭建前必须检查整个结构占用空间，不能把主机默认放在地板上。先确认 `worldPositionsAvailable=true` 且控制器定位参数已知；`coordinateSpace="piece_origin"` 的定义原点几何不是主机相对位置，不能据此算地板高度或自动改结构。优先读取 StructureLib 的公开结构规划，使用当前 piece/variant/alignment 的坐标变换，取得相对主机的最低 Y 偏移 `relativeMinY`。用已加载方块/空间数据检查整个底部投影处的地板，取地板方块坐标的最高值 `floorBlockY`；最低主机高度为 `controllerY >= floorBlockY + 1 - relativeMinY`。这里 floorBlockY 是地板方块的 Y 坐标，不是玩家脚底高度或 snapshot 的 legacy posY。真空冷冻机的主机下方还有一层结构，正常朝向 relativeMinY=-1：地板在 Y=1 时，先用底层外壳垫到 Y=2，主机放 Y=3，整体结构占 Y=2..4。不把这一偏移硬编码成所有机器的规则。

确定高度后检查最终占用体积，包括必须留空的内部位置、周围设备、屋顶、仓室和预计升级空间。地板和已有建筑不作为可替换材料；冲突时抬高或换位置并重新读取计划，不默认挖地板、覆盖设备或直接尝试蓝图。临时垫块必须避开结构要求的空气位置，优先用最终底层外壳作支撑；完成后移除本次临时支撑并补回本次造成的地面缺口。规划分页/未加载/截断部分是未知，不从第一页推断全结构最低层，不自动耗尽大范围扫描。先显式读取所需规划页和冲突点；已公开可靠的完整边界可用于定位，数据不足时继续查询客户端，不从截图猜高度。

蓝图右键必须命中控制器，不对空气使用。`mcb_health.interactionBlockTargeting=true` 表示显式 x/y/z 的方块交互会临时对准已验证的目标面，同步视角和准星，只发送一次方块右键，视角行为以当前工具参数和回执为准；即使 accepted=false 也不追加通用物品右键。回执 blockTarget 为实际方块目标，airUseFallback=false，viewRestored 表示实际视角恢复行为；它们不证明结构成立。旧模组缺少该能力时 MCP 在动作前拒绝。打开全息投影仪配置界面属于错误交互的信号，不是成功搭建的正常步骤：停止后续蓝图动作，检查手持堆叠、目标和实际方块，修正原因后再规划，不得在未确认结果的情况下重放。

蓝图交互后先读客户端 snapshot 的 target/screen/paused；target 是当前视角的准星信息，不能将这一值误认为刚才的 blockTarget。暂停界面会停住服务端 tick；新模组以 world_paused 立即拒绝相应服务端操作，不入队等待 8 秒。仅在需要恢复本次错误动作打开的界面时关闭它，确认 paused=false 后再补读服务端状态。最后分别验证地板完整、真实方块位置、仓室身份、structureFormed、维护状态和实际产物，不仅检查交互回执。

模组启动时保留正常鼠标和原有失焦暂停设置。MCP 执行玩家/GUI 输入，聊天，机器修改，传送和生命周期动作前，自动检查并开启 "屏蔽物理鼠标 + 失焦继续运行"。只读查询，截图，指南，profile 切换，启动客户端及显式模式开关不会接管。开启保护失败时不执行原动作，不强制释放已有原生鼠标抓取。人工恢复鼠标后，下一次 MCP 控制动作仍会再次接管；交还人工操作时先停止正在执行的 Agent 控制任务。

玩家可在聊天框输入以下纯客户端指令，不要求作弊或 OP，不发送给服务器。`/codexbridge` 是 `/mcb` 的别名。

| 指令 | 行为 |
| --- | --- |
| `/mcb agent on` | 切换 Agent 场景：同时屏蔽物理鼠标并开启失焦继续运行 |
| `/mcb agent off` | 切换玩家场景：恢复原鼠标处理和此前暂停设置 |
| `/mcb mouse on` | 恢复原鼠标处理，不改变失焦运行开关 |
| `/mcb mouse off` | 屏蔽物理鼠标移动，不改变失焦运行开关 |
| `/mcb unfocused on` | 开启失焦继续运行，不改变鼠标开关 |
| `/mcb unfocused off` | 撤销失焦运行覆盖，恢复此前暂停设置 |
| `/mcb takeover` | 同时屏蔽鼠标并开启失焦运行 |
| `/mcb release` | 恢复人工鼠标和此前暂停设置 |
| `/mcb status` | 查看两个开关的实际状态 |
| `/mcb scan N [V]` | 设置 Agent 探测默认横向/纵向半径，单位区块，各 1..8；省略 V 时两个半径都设为 N |
| `/mcb cache N [V]` | 设置本地已加载区块缓存横向/纵向半径，单位区块，各 1..32；省略 V 时两个半径都设为 N |
| `/mcb cache memory N` | 热调节缓存预算，单位 MiB，64..16384；默认 2048，按需分配 |
| `/mcb scan status` | 查询会话扫描默认半径 |

也可使用更短的 `/agent on` 和 `/agent off`。如果其他模组已占用客户端 `/agent` 根命令，桥接保留它，改用 `/mcb agent on|off`。这些场景切换不改变 player/developer 权限 profile。

`/mcb background on|off` 是组合模式开关。MCP 对应 `mcb_mouse({"enabled":true|false})`，`mcb_unfocused({"enabled":true|false})`，`mcb_background({"enabled":true|false})`；这里的 `true|false` 是参数选项说明，调用时须传一个实际布尔值。`mouse.enabled=true` 表示人工鼠标可用，与 `background.enabled=true` 的保护含义相反。未完成的生命周期操作期间，禁止关闭任一保护。直接调用 HTTP 生命周期 API 前须先显式开启完整保护；MCP 自动完成这一步。

## 信息来源与适用边界

GUI/tooltip 判断的优先级是当前客户端数据 -> 数据不足时的本地精确版本源码 -> 确有必要或用户许可时的截图。`mcb_gui` 返回公开按钮/槽位及支持的 widgets/coverage，不承诺读取所有第三方 GUI 内部字段。`mcb_gui_tooltip` 使用当前 screenId 和一个真实 slot 或一对缩放坐标 x/y，不改变真实悬停，不移动鼠标。`mcb_item` 用 registryName + metadata 创建数量为 1 的描述堆叠，不包含当前背包堆叠的原始 NBT；实际堆叠提示优先通过 GUI 获取。tooltip 的 advanced 选项不等同于模拟 Shift/Ctrl/Alt。

当前 NEI 配方 GUI 可用 `mcb_nei` 读取公开 handler/recipe indices 和真实 PositionedStack 输入/输出/其它堆叠及物品候选，必须提供当前 screenId。每页 limit 最多 16，默认 4，不自动遍历全部页面。`includeTooltips` 默认 false，需要物品提示时明确开启，从真实 ItemStack.getTooltip 获取。支持的 GT recipe 还返回 gregTech 原始输入输出/流体/EUt/耗时/概率/metadata条件及显示超频；显示超频不代表机器实际处理。此查询不是全局配方数据库/配方图，自定义绘制仍可能没有适配；先检查 coverage，数据不足才查本地对应源码。

`mcb_gui_widgets` 提供支持控件的稳定 widgetId，父子关系，当前文本/值/选中/焦点/可用状态，supportedActions 和范围；同一数据也在 `mcb_gui.controls` 中。`mcb_gui_widget_action` 按名称 activate/set_text/set_value/set_selected/focus，严格采用当前控件支持的操作和原生验证/同步。页面切换或控件替换后重新查询，不重用失效身份。

常用设置菜单中的原版选项/声音滑块通过 `set_value` 使用真实设置单位，返回实际值和原生像素量化结果。原生列表支持滚动；已支持的世界/语言列表返回实际数据中的行标识并支持单击选择，不按另一个目录的排序猜索引，不双击自动进入。原版和 Controlling 按键行绑定实际 `KeyBinding`，可用 `set_value` 修改键码，保留已有组合修饰键；先滚动并重读可见条目。设置保存使用正常完成/返回按钮，需要时重开客户端核对。不绘制界面、不注入系统输入、不直接编辑 options 文件；未适配的第三方界面仍须检查 coverage。

`mcb_diagnostics` 在原生桥不可用时可读取经过校验的固定 Prism 实例的宿主进程状态，明确 `worldState=not_observed`；Java 存在不等于桥或存档就绪。正常退出删除 runtime 后，`mcb_logs` 仍可读取同一实例固定日志并过滤凭据。目标或进程信息不完整时不推断客户端已停止，不自动重试启动。启动、保存退出、重开及读回原存档可组合已有生命周期工具，无需 Computer Use。

启动回复不确定时使用 `mcb_launch(action=status,timeout_ms=30000)`，只读续查固定实例，无需重复启动或重启 Codex；也可在新 MCP 连接中续查。每轮读取间隔至少约 1 秒，窗口 1..60 秒。检查 `startup`、`readiness`、`windowExpired`；Java 尚未出现、查询失败和窗口到期均不授权重发 launch。默认 `mcb_launch({})` 仍只启动一次；CLI 对应 `launch_client.py --status --timeout-ms 30000`。普通 `mcb_wait_for` 和 `mcb_machine_watch` 默认 1 秒采样，可按任务明确指定间隔。

`mcb_ae` 读取当前 terminal/pattern/preview/cpu/interfaces 同步状态。资源身份来自实际 AEStack 的类型和完整 NBT，图标/短 NBT 不能用于重建操作目标。大数量用十进制字符串，不转浮点数。`mcb_ae_action` 提供搜索/设置/存取/合成/样板/CPU/job语义操作；submitted 只是提交，等正常同步后再读数据确认完成，不自动重试。

`mcb_machine_inspect` 读取当前加载机器的state/诊断结果/modes/processing/公开组件端口/升级。signedCalculatedEUt为已计算的带符号EU/t(负为消耗)，不等于原始配方EU/t；lastMatchedRecipe/lastProcessingCalculation可能是完成后仍保留的缓存，不证明当前正在执行。components.nextOffset/hasMore按当前公开role序列分页，不触发结构复查。`mcb_waila` 读取当前准星Waila缓存提供者文本和可选有限NBT；cacheTarget/matchesVanillaCrosshair/nbtCoordinatesMatch帮助核对目标，cacheTimerAgeMillis仅是请求节流timer年龄，NBT实际收到时间不可得，不等于服务端实时状态。`mcb_entities` 分页读取已加载实体；`mcb_spatial` 提供逐点碰撞/支撑/液体/光照或射线和明确未加载未知，不从截图猜通行性，也不保证自动寻路。

模块目录统一通过公共 `IModularizedMachine.getAllModularHatches` 读取。`components.modularHatches.available/registeredCount` 表明接口是否可用及已登记数量；`modular_hatch` 条目与普通仓室共用分页，返回模块类型，执行核心 idle/working/EU/t/needProgressingTimeTicks 和可用的关联主机身份/坐标。已登记目录可能含失效或闲置条目，不证明完整实体布局，接受规则或必需数量。核心 EU/t 和进度字段保留公共 getter 含义，不推断配方/主机语义。独立核心查询不做 controllerIdentityMatches 比较。缺接口或读取失败明确报告 unavailable，不逐机补规则。

`mcb_state_diff` 初次创建所选 player/inventory/gui/messages 的不可变baseline，不重复返回全snapshot。后续带baselineId，按 nextOffset/hasMore显式分页，offset是基线比较节点而非变化条目数；空changes也可能仍有进展。每页current是新观察，不是跨页原子快照。大变化值会带valueTruncated摘要；基线会过期/因world或profile改变失效。不存图像，不自动遍历，不用wait_for重复创建baseline。

不默认采集或持久化截图。必要或得到用户明确许可时才临时读取画面，尽可能将判断转为结构化字段或文字结论，不默认写入 PNG 或历史截图。`mcb_capture` 的 `include_screenshot` 默认 false，截图工具通过 MCP 返回短期处理所需的图像内容；桥接本身不写截图文件，也不能保证清除 Codex 应用内部的会话/媒体历史。

静态快捷键是对应模组版本的参考，不是当前客户端的实时配置或完整实机验收。当前绑定、提示和能力优先；数据不足时再核对精确版本源码，不得使用未经确认的历史快捷键。

`mcb_world_scan({})` 从当前玩家脚下开始，默认探测横向/纵向各 1 区块半径 (16 格)。`mcb_scan_config({})` 同时查询探测、缓存设置和命中统计；`radiusChunks`/`verticalRadiusChunks` 各 1..8，`cacheRadiusChunks`/`cacheVerticalRadiusChunks` 各 1..32，默认缓存各 4 区块 (64 格)。`cacheMaxMiB` 默认 2048，可设为 64..16384，按需分配；预算是保守的引用/条目记账，不是完整 JVM 或原生区块对象图的内存上限。每次查询核对实时方块/metadata 和世界/区块身份，不缓存实体、仓室内容或最终碰撞验收，不加载新区块。未提供的设置保留，修改清除旧游标和缓存，不接管鼠标。Forge `scan` 配置保存启动默认值；MCP 或 `/mcb scan N [V]`，`/mcb cache N [V]`，`/mcb cache memory N` 热调节当前会话。

扫描每次只返回预算内的一页，不加载区块，不返回机器库存。显式块数 `radius` 保留为横向/纵向相同的 0..128 格兼容参数，不能与区块半径混用。初次指定中心时必须提供完整 x/y/z；player profile 只能查询当前维度，developer 可带完整中心查询已加载的本地维度。续页仅传上页 `nextCursor` 和可选 `limit`，不重传中心/范围/维度。游标会过期，响应成功送达后旧游标被消费；同游标执行中返回 busy，状态失效返回 stale_cursor，不自动重试。每页查看 `complete`，coverage，page，`visitedSpans`，`stopReason` 和 `nextCursor`。预算耗尽可能返回零进度；visitedSpans 标明已观察/空气/未加载的区段，未访问和未加载的位置不等于空气。不默认循环完最大扫描体积，不用返回 blocks 数量推算覆盖率，不通过 wait_for 重复扫描游标。

`mcb_world_structure` 只读取现成公共接口，不按机器名/ID添加专用规则。省略 piece 返回有界结构片段发现；`definition` 说明枚举接口是否可用。指定 piece 但缺少 offsetA/B/C 时，只返回 `piece_origin` 几何，`offsets=null`，`worldPositionsAvailable=false`，不返回错误的世界坐标或 observed。三个偏移量全部已知时才取得对齐世界位置和已加载方块观察。公共接口无法提供控制器偏移量，完整仓室数量/接受规则，模块布局和动态片段激活计划时，读取 coverage 并明确告知用户缺口，不从片段名称或几何猜测，不自动修改实体结构。已公开的元素说明和候选可直接使用，但候选不是完整接受谓词。variant 仅为规划 trigger 的堆叠数量，不发放或消耗物品。规划不是结构已成立的证明，已加载位置的 observed 才是当前观察。分页只使用返回的 `nextOffset` 和 `hasMore`，过滤或导航可导致空 positions 但 nextOffset 仍前进，不用结果数自增 offset，不自动穷举整份计划。

模组结构化检查补充界面操作：`mcb_item_variants` 查询一个物品的创造变体；`mcb_world_inspect_many` 比较机器和端口；`mcb_machine_watch` 记录进度/能源/配方结果，默认读取gregtech.signedCalculatedEUt(当前机器已计算EU/t, 负消耗/正生成)，不能当作NEI recipe.rawEUt原始配方值；`mcb_capture` 保存带时间信息的非原子状态集合，默认不带截图。本桥接不会直接编辑存档或任意执行 Java。

# GTNH AE2 终端，样板与自动合成指南

[English](ae.md) | [简体中文](ae_zh.md)

供人工阅读和审核；Agent 使用英文入口，完整规则见[启动模板](workspace_zh.md#可复制的启动提示词)。

适用 Minecraft 1.7.10 的 GTNH AE2。基础来源版本：AE2 rv3-beta-1073-GTNH、AE2FC 1.5.110-gtnh、NEE 1.7.45、NEI 2.8.145-GTNH、InventoryBogoSorter 1.3.54-GTNH。较新接口/便携能力另列 AE2 1080 等版本。这些源码基线不代表完整实机验收，仍须核对当前绑定、设置、能力和同步状态。

## 批量配置、安装和可续查订单

当前本机 developer bridge 若报告 `aeGoalWorkflowVersion=1`，优先使用以下组合入口，复用已有接近、原生交互和状态确认，减少逐步调用。下面是新增能力契约；实机是否完成仍以当前回执为准。

- `mcb_ae_configure`：1..32 个精确宿主/部件，配置 priority 或 BLOCK / STORAGE_FILTER / ADVANCED_BLOCKING_MODE。先发送原生配置操作，再读取服务端宿主状态以确认；文本框显示值不能证明保存成功。
- `mcb_ae_install`：提供 attachment 的精确面、hotbarSlot、expectedPart。`placement=host` 安装到交互目标宿主，`adjacent` 安装到相邻方块位置。mounted side 分别等于点击面、其反面，线缆为 6。原生路径先尝试宿主位置，再尝试相邻位置，桥接会预检目标路径；不得将短时 sneak/use 两个异步动作组合为连续操作。结果需精确部件和完整玩家库存守恒证明。
- `mcb_ae_order`：`action=start` 提供唯一 requestId、terminal、item、amount；自动预览、启动一次、跟踪原生 craftingId、按量取货，核对服务端回执及客户端目标槽完整身份/NBT。超时用 `action=resume` 和同一 requestId/会话/世界/维度续查。缺料返回预览，背包满可腾空后续查；部分取货停止自动操作，保留实际光标状态。

三个入口都要求 expectedSessionId / expectedWorld / dimension，默认 30 秒、最大 60 秒。配置和安装在首个失败处停止并保留此前成功完成的项目。订单目前限物品，1..64 且不超过物品原生一组上限，需要空闲 CPU、空光标及一个可用空背包槽；放置式终端还需空快捷栏槽位以打开界面，便携终端需手持其实际物品。省略 item.nbt 表示无 NBT。会清空终端搜索并切换至 CRAFTABLE，类型过滤需事先启用。每个进程最多保留 64 个订单，不自动淘汰或重新下单；客户端重启后不恢复原生订单回执。

原生已生产完成而尚未取货时为 `collecting`；服务端已交付、客户端尚未同步时为 `delivery_sync`。后者只读等待，续查同一 requestId，不再次取货。支持 `approachWalkingSegmentsVersion=1` 时，较远步行接近复用分段导航，60 秒窗口内仍须逐段验路及最终原生射线确认。服务端已有命中点可优先作为客户端新射线的方向，不复用旧命中作为成功证明，也不增大射线预算。普通界面/状态等待默认约 1 秒读取；原生订单保留事件等待。

## 操作路径确认

从零搭建机器和 AE 系统时，先统一规划再放控制器：把所有终端、驱动器/存储、合成 CPU、接口和需要保留的操作空间加入 `mcb_placement_preflight.layoutComponents`，或构建器的 `placement.layoutComponents`。每项含唯一 `id`、`role=equipment|io_routing`、`relativeBounds={min:{x,y,z},max:{x,y,z}}`。坐标是当前已校准朝向下相对控制器的世界 XYZ 偏移（包含边界），不是 ABC；不自动旋转。默认 `placement=above_ground`，设备的最低层及整个占地共同参与地面高度计算，不得先贴地放置控制器，再向下增设 AE 设备。

只有纯 IO 线路可声明 `role=io_routing, placement=hidden_bottom`，其范围必须恰好是地上总包络下方的一层；终端、存储、CPU 等不能归入隐藏线路。隐藏线路仍参与占位/高度边界检查，但不要求这层线路露出地面。线路所在槽必须已有净空，泥土或已有设备挡住时返回 `hiddenRoutingBlocked`，不自动挖掘或覆盖；其他底层地面不必挖空。使用回执 `assemblyLayout.components[].worldBounds` 安装 AE，全部坐标跟随同一个 `proposedController` 平移。未声明的附属设备不在覆盖范围内；省略此参数仍只规划机器。该布局声明本身不安装 AE，也不搬移已有机器。

先读 snapshot/gui/inventory，按当前 screenId/widgets/coverage 确认终端、光标和搜索状态。需要提示时用 gui_tooltip；字段缺失不是空。优先结构化观察，数据不足才查精确版本源码；不用旧槽号或旧截图坐标。

支持的公开AE提示适配与专用 `mcb_ae` 各有明确coverage，不等于全ME网络/未加载数据或任意私有字段。`gui.tooltip` 不移动真实鼠标，不改变缓存hover，不发送取物/合成动作；advanced也不模拟Shift/Ctrl/Alt。先用 `mcb_bindings` 查看实际运行时绑定，静态按键表主要供人工操作参考。

从终端打开的NEI GuiRecipe用 `mcb_nei` 读取当前handler的真实PositionedStack输入/输出/候选，必要时includeTooltips。支持的GT recipes有gregTech原始物品/流体/EUt/耗时/概率/metadata条件和显示超频，明确不同于机器实际计算；未适配绘制文本仍按coverage视为未知。查询结果不是AE网络虚拟槽，不直接当container slotNumber；不枚举全局配方图。`mcb_nei_open` / `mcb_nei_navigate` 提供语义查询与导航，不需要系统快捷键。

不默认采集或保存截图。必要或用户明确许可时只作临时画面判断，尽量转为结构化字段或文字；`mcb_capture` 默认不含截图，本桥接不主动写入 PNG，不声称能够清除 Codex 应用内部媒体历史。

本版本的 ME 列表及样板输入输出使用 **VirtualMESlot**，不属于vanilla inventorySlots。`mcb_slot_click` 的普通container index不能替代ME显示格。AEBaseGui.mouseClicked依赖cached hoveredVirtualSlot，所以桥接仍拒绝它的通用mcb_gui_click。用下述mcb_ae/resourceId和mcb_ae_action专用适配，普通真实背包槽才用windowId/slotId。支持控件用mcb_gui_widgets的稳定widgetId和advertised actions，不得推测悬停状态或使用系统输入。

GUI Ctrl/Shift/Alt/Space来自实体键或AE可重绑交互键；player.input.sneak不等于GUI修饰键，mcb_gui_key也不保证触发NEI/BogoSorter/Forge全局事件。受支持的动作使用 named API 和正常容器同步；未覆盖的组合键/第三方动作明确报告限制，不自动转系统输入。下列手势表不代表每个实体组合已由MCP实现。

安装 AE 线缆/终端部件优先用 `mcb_ae_install`，声明真实附着目标、部件及安装面，复用原生 Forge 事件链、接近和有限材料验收。`mcb_interact` 的普通右键不等价于部件安装；低层 `mcb_input` 仅在明确原生交互及新鲜射线后使用一次。意外打开 GUI 或超时先观察，不能重复 use。

## 通用布线、输出与阻挡检查

按当前任务的频道、布局和操作空间选择线缆与 IO；可选包层、玻璃或智能线缆。GT ME 输出总线/输出仓、AE 输出总线和 AE 存储总线承担不同角色。受支持的 GT ME 输出可直接返回网络，普通输出接接口需验证原生推送、接受面和实际物料返回；容量按有限批量和副产物规划。未测量时不声称性能提升。

**同时规划供料接口与物料返回端口的位置。** 按机器允许的实际输出位置，把处理样板主网接口放在输出附近，共用短主网接入；投料子网贯穿输入。样板接口无需紧邻控制器或首个输入仓，分料顺序仍按优先级、过滤和真实端口验收。普通输出可在接受面相邻位置安装二合一接口，ME 输出就近返回主网络。保留隔离、频道、检修净空和物料返回检查；朝向改变后重新计算，不固定“尾端”或世界方向。这不证明任意仓位合法或加工性能提升。

布线按目的选择隔离方式：

- **染色**：支持颜色隔离的相邻组件使用不同的非默认颜色；Fluix 默认色可连接其他颜色，不能用作隔离色。核对具体设备是否支持连接隔离，不能只看外观。
- **线缆锚**：安装在需要断开直接连接的那一面；精确 mounted side 与目标邻格一起验收。大量锚会降低可读性，不默认在每条线缆上安装线缆锚。
- **石英纤维**：隔开两侧 ME 网络连接，同时共享 AE 能量；不能当作数据/频道连接。线缆伪装板不提供这种隔离。

普通线缆路径最多 8 频道，致密主干最多 32；把存储总线、接口等频道需求一并计入，避免普通支线汇聚成瓶颈。局部隔开相邻支线，不等于整个网络已分离：若支线仍接到同一底层主干，它们可以属于同一投料子网。分别验证不需要的直接连接、主干路径、设备在线和主/子网公开 grid ID；节点缺失不能证明隔离，grid ID 不能跨重启直接比较。改变机器朝向后重新解析世界坐标和部件安装面，不得直接沿用原有安装面。

阻挡按实际物流角色检查，而非按左/右位置记忆：**存放样板并供应材料的接口开启 `BLOCK=YES`；接收材料的子网接口安装高级阻挡卡**，使供料端能检查目标子网的占用。接收端自己的 BLOCK 开关不能代替供料端开关；高级阻挡卡也不是供料端 BLOCK 的替代品。2.9.0 的高级阻挡 DEFAULT 忽略规定的催化剂，STRICT 不忽略；智能阻挡允许当前非阻挡样板继续投料，是另一个设置，有序分槽或容量未验证时不默认开启。

装配、拆换或转向后，用现有 `mcb_world_inspect_many(includeAe=true)` 批量核对精确部件、网络、优先级和过滤设置；不能因带配置 NBT 的部件回收成功就假定设置仍正确。有序分槽按实际机器端口顺序验收严格递减优先级，检查是否混入默认 0；物品存储总线需要报告不可抽出的 GT 输入时，确认 `STORAGE_FILTER=NONE`。公共配置字段不显示升级卡时，按需要读取真实 GUI 升级槽，或在已授权本地 developer 范围使用 `mcb_machine_capture(includeNbt=true)` 的原生序列化，不能仅根据 BLOCK 值推断升级卡是否存在。配置读取与真实投料、物料返回验证分开，结果未知不重放订单。

机制来源：[连接 ME 网络](https://gtnh.huijiwiki.com/wiki/应用能源2/连接ME网络)、[线缆伪装板](https://gtnh.huijiwiki.com/wiki/线缆伪装板)、[自动合成](https://gtnh.huijiwiki.com/wiki/应用能源与自动合成)（含 2.9.0 阻挡说明）；[装配线自动化](https://gtnh.huijiwiki.com/wiki/装配线自动化)明确为 2.8.0 教程，仅作接口角色和有序投料的范例，不覆盖新版差异。实际选型依据当前任务，部件与配置按当前版本读回。

## 流水线的投料缓存与并发验收

高级阻挡直接观察机器输入时，后续片层余料可能阻止下一批投料，加安培不能解决。一种可选布局是按片序优先级投向单槽暂存，再用传送带送入多槽输入，子网只观察暂存层。它不是固定机器或等级要求；应核对实际接受面、盖板方向/模式、优先级/过滤、频道及隔离。

重 IO 布局应露出仓口、暂存箱和盖板，保留外侧视线、站位及拆装通路；纯线缆沿底部或端部走，避免挡在端口正前方。暂存箱属于设备，纳入整体位置规划。换朝向后重新验收地面访问，不能只检查最低 Y。GT 盖板安装/撬除还取决于九宫格命中点，side 只约束射线面；在 approach 的目标和 interact 中绑定同一 `hitPoint`（方块内 0..1），如 SOUTH 中心 `{x:0.5,y:0.5,z:1}`；能力为 `interactionHitPointVersion=1`。随后读回实际盖板面和模式。

组合现有 watch（约 1 秒）、capture、order，按配方实际片数核对运行中进度、耗电和取货。上述版本 mProgressTimeArray 保存片层进度，lEUt = working * baseEUt；currentParallel=1 是单片内部并行，闲置旧耗电不能证明并发。前单交付并排空后切单；重复材料不足整组、混合配方等边界另测，不默认开智能阻挡或推广到全部配方。参考[进阶装配线自动化](https://gtnh.huijiwiki.com/wiki/进阶装配线自动化)。整理和配置可能改变快捷栏，下一动作前重读库存；订单内置客户端槽同步等待，保留完整身份和空光标核对。保持通用动作组合。

## 结构化读取与语义操作

`mcb_ae` 必需当前screenId，scope=auto/terminal/pattern/preview/cpu/interfaces(默认auto)，offset默认0，limit默认64/最大128。terminal读取实际过滤/排序列表，不按scrollbar裁断；pattern包括隐藏页和空的完整输入输出槽；preview读取storage/pending/missing，cpu读取摘要和当前job，interfaces读取已同步条目。先看coverage/scanTruncated/byteLimited/budget/nextOffset/hasMore和同步状态，不自动穷举或把缺字段当空网络。

resourceId绑定当前屏幕中实际AEStack的type+NBT，即使identityNbt被截断，运行时resolver仍保留完整实际身份。图标名字/metadata/截断NBT不能重建操作目标。amount/requestable/storage/pending/missing及CPU大数量均为十进制字符串；操作amount必须是1..9223372036854775807的正整数字符串，如"512"，不传浮点或科学计数法。

`mcb_ae_action` 必需screenId/action，严格使用该action的参数:

- terminal_search(text, field=resources/inputs/outputs/names), terminal_setting(setting=SORT_BY/SORT_DIRECTION/VIEW_MODE, value=当前advertised enum).
- terminal_type_filter(type=当前state.typeFilters中的类型, enabled严格布尔)；resource_container(resourceId, mode=fill_single/fill_all/drain_single/drain_all)走原生typed资源容器处理，不把流体显示图标当普通物品。
- resource_take(resourceId, mode=stack/single/half/inventory), resource_deposit(mode=all/single, 当前真实cursor来源), craft_open(resourceId).
- craft_request(amount, mode默认STANDARD, lite/autoStart/follow默认false)；常规开发测试保持预览再核对。
- pattern_set(side=input/output, slot=完整storage索引, resourceId或真实playerSlot0..35加amount; 清空用clear=true且不传source/amount), pattern_mode(field=crafting/substitute/beSubstitute/inverted, value布尔).
- pattern_multiply(factor=-8/-2/2/8, 原生全槽溢出/整除校验), pattern_page(page), pattern_encode(move/wholeStack默认false, wholeStack=true须move=true), pattern_clear.
- preview_start(follow默认false)，cpu_select(serial, -1为原生自动)，job_cancel(expectedCpuSerial)，job_pause(expectedCpuSerial, paused布尔)。Job操作必须匹配当前选中CPU，不猜旧截图或其它CPU。

所有操作通过正常GUI/container包提交，不要求真实hover/实体键鼠。返回submitted不能证明服务端完成，等正常tick/sync后再读mcb_ae、库存和机器状态，不重复提交补偿未变画面。每页最多128条，遍历最多4096条/128KiB，4ms软预算不可抢占getter；interface内部slot最多64且每条48KiB，total/truncation/slotsByteLimited说明缺失。state.virtualSlots最多64个真实注册虚拟槽，使用其typed resourceId/十进制amount，不把slotIndex当作container slotNumber。分页只用返回nextOffset/hasMore，不自动全量遍历。

page的协议上界127不是所有终端的页数；精确参考版本样板终端仅原生0..1，当前范围由状态读取和native校验确认。job_cancel/job_pause的网络CPU serial守卫适用于GuiCraftingStatus；固定方块GuiCraftingCPU仍可读取当前job，不伪造网络serial执行操作。`mcb_gui_widget_action` 的activate需当前实体Shift/Ctrl/Alt及左右鼠标按钮均未按住，防止原生callback把语义变成修饰键操作；桥接不改变这些实体输入，条件不符时先等待并重新读取状态。

set_text的commit控制MUI2原生失焦校验/同步；AE文本框的原生onTextChange会立即生效，commit=false不表示暂存。childIds每控件最多128个，检查childCount/childIdsTruncated并按parentId的分页关系读树，不把缺失childIds当作无子控件。

## 搜索，视图与实际快捷键

AE 自己的搜索框不同于 NEI 全局搜索。右键清空并聚焦，Enter 失焦；SEARCH_MODE、SearchBoxFocusPriority、preserveSearchBar 按实际配置确认，不假设自动聚焦。设置按钮通常左键正向、右键反向循环，读取当前 tooltip。

NEI 存在时 AE 列表复用 NEI 搜索解析器，即使没有开启 NEI 文字同步模式。在相应搜索配置下可用语法为普通词，`@GregTech` 模组显示名称，空格 AND，`|` OR，`-词` 排除，引号短语，`*`/`?` 通配符和 `r/表达式/` 正则。例 `@GregTech circuit`。tooltip/oreDict/identifier 为 ALWAYS 时，普通词会检索它们，不承诺 `#`/`$`/`&` 是专用前缀；详见 NEI 指南。

搜索不到物品还要检查显示模式 (全部/已储存/可合成/物品流)，类型过滤与 View Cell。这些在文本过滤之外再次筛选。可合成视图可能把显示数量置 0 以便请求合成。`PAUSE_WHEN_HOLDING_SHIFT=YES` 只暂停终端列表重排，不暂停服务器/机器/合成工作。

| 参考默认按键 | 含义/前提 |
| --- | --- |
| R / U | 搜索未聚焦，悬停物品查 NEI 配方/用途 |
| P | 搜索未聚焦，悬停样板查看其配方 |
| F | NEI 全局搜索框焦点，不是 AE 自己的搜索快捷键 |
| T | BogoSorter 悬停搜索：AE/NEI 搜索都未聚焦，悬停物品把转义显示名填入 ME 搜索 |
| 左 Ctrl | AE 可重绑容器内容交互键；并非所有 Ctrl 语义都等于硬编码的左 Ctrl |
| 中键 | 当前 vanilla pickItem，ME 可合成项可打开数量请求 |
| Tab / Shift+Tab | 接口终端的三个搜索框正向/反向切换 |

T 来自已安装且启用 enableTerminalHoverSearch 的 BogoSorter 扩展，不是 AE 原生焦点键；可从 NEI 返回对应终端搜索。其他快捷键是否绑定以当前 mcb_bindings/Controls 为准，不得直接沿用历史客户端配置或其他版本的操作说明。

## ME 资源列表存取

下表针对 ME 资源列表，不针对玩家背包或样板占位。非物品资源/容器交互另按类型处理。

| 输入 | 行为 |
| --- | --- |
| 空手左键已有存量 | 最多一组到光标 |
| 手持物品左键 | 将光标上的全部物品存回网络，容量不足时保留剩余物品 |
| 空手右键已有存量 | 最多一组的一半，向上取整，到光标 |
| 手持物品右键 | 存入 1 个 |
| Shift+左键 | 最多一组到玩家背包 |
| Shift+右键 | 取 1 个到光标，可向相同堆叠追加 |
| Space+点击 | 重复取组到背包，直到存量不足或背包装不下 |
| 空手左键 0 存量的可合成项 | 打开合成数量请求 |
| pickItem (通常为中键) 点可合成项 | 有存量也可打开合成数量请求 |
| Shift+滚轮向上 (正 delta) | 光标物品向网络存入 1 个，光标为空不执行 |
| Shift+滚轮向下 (负 delta) | 从网络取 1 个到光标 |

容器内容交互键 (通常为左 Ctrl)+左键从网络填充一个合适容器；+右键把一个容器内容存入网络；再加 Shift 批量处理。玩家背包中的容器格也可用交互键快速存取内容。容器物品与其中流体是不同资源，检查身份和类型后操作。

## 样板编辑与 GTNH 增强

工作台合成样板记录真实工作台配方，布局位置有意义。处理样板记录机器的输入/输出约定，编码不等于机器已经运行。NEE 可按配方类型自动切换模式，转入后仍须核对。

样板输入/输出是虚拟占位，编辑时不消耗放入的真实物品。手持物品左键按持有数量设置占位，空手左键清空；同物品右键加 1，空手右键减 1，减到 0 清空。容器交互键可登记容器内容而非容器本身；NEI 流体显示项会转为流体资源。

- **处理中样板单格数量**：中键打开数量框，支持正整数/算式，如 `64*8`，Set/Enter 提交。
- **整份处理样板倍率**：倍率按钮左键 x2，左 Shift+左键 x8；右键除 2，左 Shift+右键除 8。所有非空输入输出都必须整除，否则全部不变；溢出也不执行。此按钮中键打开任意倍率框，正数 >1 乘，负数 <-1 除。这里固定读取左 Shift，不一定跟随重绑的潜行键。
- **编码**：普通编码写输出样板槽；Shift+编码写完移入背包；容器交互键 (通常为左 Ctrl)+Shift+编码会整组复制剩余空白样板并移背包，不要误认为只编码一张。背包空间不足可能掉落物品。
- **增广样板终端**：本版大侧为 4x4x2 页 = **32 槽**，小侧每页 4 格共 **8 槽**，可倒置。UI enum/旧 README 仍叫 16/4，不代表当前容量。切换倒置会清空缩为 8 槽的一侧超出的占位。先定布局再填配方，核对两页。
- **资源类型**：处理中样板基于 IAEStack，可接受非物品类型。接口/机器仍必须支持该类型，界面可编码不保证机器可处理。
- **替代设置**：substitution 与 beSubstitution 是不同选项；以当前按钮提示确认，完整执行端语义尚需按版本核实，不得在未核实语义的情况下默认启用。

## NEI -> 样板/合成终端

从目标终端按 R 打开正确机器的配方，选择正确配方后 **Shift+点击 ? 按钮**，回终端核对模式，数量，输入输出和替代材料。合成终端只转实际工作台配方；机器配方转处理样板终端。槽位不够使用增广终端，转入后检查两页及是否有遗漏。

所需样板或目标标记写入并核对完成后，按[NEI 临时项清理规则](nei_zh.md#临时假物品与书签清理)自动清理本次使用的临时来源假物品与收藏。保留已编码样板、已保存的目标配置，以及永久书签和明确后续写样板或其他任务仍要使用的书签。批量写样板时可将临时收藏按批次新建页面整理，各批次完成且不再需要对应条目后及时清理。缺少原生清理动作时如实记录待清理项，不重复编码，也不声称书签已经删除。

NEI 配置为 `shiftOverlayRecipe=true` 时。虽 NEE 配置保留 `noShift=true`，1.7.45 的两个 handler 未覆盖 `requireShiftForOverlayRecipe`；精确 NEI 默认接口和按钮仍按自己的 Shift 设置分派。因此可靠源码路径写为 Shift+?，不保证普通 ? 可转入，普通点击可能只显示幽灵布局。按钮即时 tooltip 优先。

NEE 数量调整键 (通常为左 Ctrl)+滚轮改变处理占位数量，最低 1。材料键 (通常为左 Shift)+滚轮循环转入配方缓存的等价材料，`allowSynchronousSwitchIngredient=true` 可同步切换同类材料；不把所有手工占位都视为有可循环替代项。

GTNH 机器调试需保留这些差异:

- `includeNonConsumableIngredients=false`：GT5 处理器省略 NEI 数量 0 的不消耗材料，可能包括电路/模具；机器仍可能需要预放工具。样板中没有不代表配方不需要。
- `itemCombinationMode=ENABLED`：会按处理器规则合并同类输入，仍受最大堆叠及处理器例外约束，不是无限合并。
- **不能假定概率产物一定会被过滤**：旧 NEE README 有该说明，但当前核心输出打包/GT5 处理器未见显式过滤，未追踪每个 NEI handler 的输出生成。编码前核对随机副产物，防止把它写为必达输出后工作一直等待。
- `useStackSizeFromNEI=true` / `keepGhostitems=true` 会影响编辑。旧拖拽实现使用 SlotFake，当前 AE 使用 VirtualMEPatternSlot；全部拖拽兼容仍需按当前环境验证。优先配方转入，普通点击和中键数量框。

NEE 合成终端还提供请求/缺料修饰键 (通常为左 Ctrl) 和跳过预览键 (左 Alt)。当前 NEI Shift 门控可能同时适用，最终组合需看 tooltip/实际结果。默认调试保留预览，不使用跳过预览的路径。

## 自动合成与排查

推荐流程：清空光标 -> 搜索目标 -> 中键可合成项 -> 输入数量 -> Next -> 等待计算预览 -> 核对缺料/CPU/内存 -> Start -> 查询对应 CPU 的工作状态。数量可用算式。发送后观察，不得因按钮未立即变化而在未确认结果的情况下重试 Start/编码。

Shift+Next 会自动开始；容器交互键 (通常为左 Ctrl)+Next 自动开始并 follow；同时按两者反而不选这两种自动开始。常规测试不持修饰键，保留确认页。Enter 会触发当前 Next。

确认页的 Shift+Cancel 可把缺料加入 NEI 书签后退出；CPU 工作状态页的 Cancel 才实际取消工作。确认目标 CPU 后再执行 Cancel/Suspend。同物品已有工作时可出现 Merge，不把界面按钮文字视为无副作用查询。

排查顺序：目标是否可合成 -> 样板类型/输出身份和数量 -> 接口在线及资源类型 -> CPU/存储/能源 -> 机器结构/电压/配方/不消耗工具 -> 输出返回网络且身份数量匹配。终端成功受理不能替代机器完整运行验证。

## ME 接口终端

三个独立搜索框为输入，输出，接口/分组名称。输入与输出必须在同一张样板上同时匹配，两框复用 NEI 解析；名称为文本词过滤，不等于全部 NEI 语法。Tab 按输入 -> 输出 -> 名称循环，Shift+Tab 反向。autoFocusInterfaceTerminalSearch 可配置，读取实际焦点。

列表区容器交互键 (左 Ctrl)+滚轮到头/尾，Shift+滚轮按分组跳转。模式可能过滤 offline/P2P 输出/无空位/损坏条目。搜索不到不直接判定接口不存在；异常高亮先查样板无效或资源类型不匹配。

### 较新版本：远端接口、样板输入总成与原生定位

本节核对 AE2 1080、AE2FC 1.5.113、GT5U 5.09.54.205、WCT 1.12.16。**样板终端**编辑配方，**接口终端**管理同网接口里的真实编码样板。编辑用的虚拟输入/输出、接口真实样板、ME 资源显示格和玩家背包不能混用槽号。

`mcb_ae(scope=interfaces)` 给出当前同步的接口 ID、坐标、维度和实际槽数。`mcb_gui_widgets` 中 `kind=interface_pattern_slot` 绑定 `interfaceId/patternSlot`，用现有 `mcb_gui_widget_action(action=activate)` 发送原生取放包。它搬运真实光标物品，不使用普通 `mcb_slot_click` 的容器索引。放入后检查源/目的槽、完整 NBT 和空光标。GT 样板输入总成(ME)有 36 槽；能远程放样板不证明某台机器接受此仓室或已加工。

远端查看任务时，先选实际忙碌 CPU。当前 CPU 资源控件 `kind=cpu_locate_providers` 可发送一次 AE 原生接口查询；在 `mcb_ae(scope=cpu).state.providerLocator` 等待实际返回的维度/坐标。`cpu_highlight_providers` 和 `interface_highlight` 使用 AE 原生高亮并关闭 GUI，然后可组合 `mcb_navigate` 到接口附近、`mcb_world_inspect` 确认设备、`mcb_machine_inspect` 读取对应机器的实时进度。接口不等于控制器，高亮提交不等于到达。原生定位包没有请求/任务 ID，同一不变界面/CPU只持有一个查询；外部并发查询和任务替换的关联仍有限制。

AE2 1080 无线连接器只支持同维度，用无线工具绑定后检查同一当前 grid ID 与在线状态。跨维度量子桥的原生配对、加载和有限交付见 operations 指南。手持终端使用的无线访问点是另一个设备；其有线连接面为原生朝向的背面，必须确认实际接网。访问点、手持终端能量/绑定/范围分别检查。无线查询 CPU 供料接口受原生 `IGridHost/openContext` 条件限制，不把固定终端的定位成功推广到全部便携 GUI。

普通无线终端、WCT、AE2FC 无线样板/接口/请求/通用终端应逐个验证。安全终端以真实编码槽绑定，关闭前取回输出。通用终端换模式后重新获取 screenId/widgets。`aePortableOwnerVersion=1` 时，`mcb_ae(scope=owner)` 给出玩家、维度、槽位、物品及网络绑定；装备到快捷栏后复制为 `mcb_ae_order.terminal={portable:owner}`。可合成的终端模式复用同一预览/原生订单/精确取货流程；便携元件为独立存储，无网络绑定，不作为网络订单入口。宿主身份不比较持续变化的设置/电量 NBT；取货仍检查全部库存，只允许由物品原生能量 API 在副本上精确复现的扣电变化。

`mcb_ae(scope=requests)`（无线请求页 auto 也可）分页读取 AE2FC 同步的请求/维持器位置、槽位、资源及十进制大数量；分别检查 online、slotsPending、slotsTruncated。非空列表需网络内支持的提供者；ME 库存本身不生成请求。等级发射器是支持的提供者，其 quantity 是阈值，batchSize=-1，不是库存量或加工批量。分页和有限状态切换不能作为大网络压力测试的证明。当前版本原生条目的直接合成点击尚未实现，读列表不等于发订单。CPU 定位按实际运行时宿主是否实现 IGridHost 启用；`openContext` 只在服务端设置，客户端为空不能判为不可用。仍需收到原生坐标回复，不能由类名或按钮启用推出成功。

## 精确来源和验证边界

AE2 证据为 [rv3-beta-1073-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Applied-Energistics-2-Unofficial/rv3-beta-1073-GTNH/Applied-Energistics-2-Unofficial-rv3-beta-1073-GTNH-sources.jar)，及相关配置条件。类名行号均对应该版:

- 虚拟槽/点击/键状态：`AEBaseGui.java:410,768,1101,1111,1125`，`GuiMEMonitorable.java:681,701`，`GuiPatternTerm.java:237`，`ServerHelper.java:45`。
- 存取/滚轮：`GuiMEMonitorable.java:705,715,737,741,771,778,813`，`ContainerMEMonitorable.java:669,691,717,739,764,782,815,970,1207,1218`。
- 搜索：`MEGuiTextField.java:123`，`GuiMEMonitorable.java:903,966,1142`，`ItemRepo.java:253,280,410`，`NEISearchField.java:85`。
- 样板数量/倍率/编码：`VirtualMEPatternSlot.java:24`，`VirtualMEPhantomSlot.java:96`，`GuiPatternValueAmount.java:61`，`GuiPatternTerm.java:97,118,130`，`ContainerPatternTerm.java:71,269,579,586,638`，`GuiAmount.java:181,237`。
- 32/8 增广：`PartPatternTerminalEx.java:17,79`，`GuiPatternTermEx.java:163,190,237`。
- 合成/接口：`GuiCraftAmount.java:134,188`，`PacketCraftRequest.java:131`，`GuiCraftConfirm.java:346,427,978,1100`，`GuiCraftingCPU.java:169`，`GuiInterfaceTerminal.java:187,1091,1112,1140,1277,1314,1640`。

扩展源码：[NEE 1.7.45](https://github.com/GTNewHorizons/NotEnoughEnergistics/tree/1.7.45/src/main/java/com/github/vfyjxf/nee)，`NEEPatternTerminalHandler.java:55,213`，`NEECraftingTerminalHandler.java:53`，`GuiEventHandler.java:247,251,333`，`GregTech5RecipeProcessor.java:77,105`，`PacketNEIPatternRecipe.java:79,94`，`NEECraftingPreviewHandler.java:62`。[BogoSorter T 悬停搜索](https://github.com/GTNewHorizons/InventoryBogoSorter/blob/1.3.54-GTNH/src/main/java/com/cleanroommc/bogosorter/compat/ae2/Ae2TerminalSearchAdapter.java#L37)。NEI Shift 门控：`IOverlayHandler.java:33`，`GuiOverlayButton.java:133,331`，`NEIClientConfig.java:1168` (2.8.145-GTNH)。

上面的基础版本手势表依据对应源码和配置条件，未实测全部按键/回调。运行中重绑可与磁盘不同。noShift 最终覆盖，概率输出过滤，虚拟格拖拽和第三方冲突保留未确认。较新版本 远端接口、无线与便携能力见独立小节；最大无线范围、全部升级、pins/物品流诊断以及 substitution 完整执行链仍未完整核对，不将模组安装版本视为全功能可用的保证。

AE 当前可见 `VirtualMEPhantomSlot` 通过 `mcb_gui_widgets` 的 `ae_phantom_slot` 使用已有 `ghost_drag(source)` / `phantom_click(button)`。复用原生类型过滤、点击及同步；真实槽号不能用于虚拟槽。模板不授予物品，回执只证明提交，关闭重开或服务端原生序列化确认配置。

空 AE 盘悬停可使客户端产生空标签；1k/64k 已验证 `mcb_gui(waitFor={})` 的原生槽位重同步可恢复完整 NBT 一致。不要把 null 和空复合标签全局等同。最后一件物品 count=0 残影同样先同步并重读；不重放已成功的放置。

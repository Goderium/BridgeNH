# GTNH NEI 操作指南

[English](nei.md) | [简体中文](nei_zh.md)

供人工阅读和审核；Agent 使用英文入口，完整规则见[启动模板](workspace_zh.md#可复制的启动提示词)。

适用 Minecraft 1.7.10 的 GTNH NotEnoughItems。原生书签 API 参考 **2.8.171-GTNH** 源码；快捷键参考 **2.8.155-GTNH**，其他基础行为参考 **2.8.145-GTNH**。这些源码基线不代表所有快捷键均已实机验证。升级或重绑后读取当前 Controls、绑定与 tooltip。

## 按目标选择收藏与领取

- 只需最终产物：人工悬停目标产物按 **A** 做物品级收藏，再从书签领取；不为取一件而用 **Shift+A** 保存整套原料。需要输入输出关系或整方用量时才收藏配方。`bookmarkItemsWithRecipe` 可附带配方身份，不等于把原料也收藏。
- 分开判断配方单轮产量、书签记录数量和本次领取数量。一轮产出 N 件但只需 1 件时，用物品书签的原生数量调整或已有 `mcb_nei_item_action(action="take",neiItemId=...,count=1)`。不从配方倍率推断领取量。完整 metadata/NBT 保留，批次末核对实际库存增量和光标。
- Agent 优先复用已观察的精确面板条目和 `take(count=...)`。需要新增物品书签时，用 `mcb_nei_bookmark_action(action="add_item")` 与实际 `neiItemId`；整方 `mcb_nei_navigate(action="bookmark")` 用于保存配方。有 NBT/动态参数的产物不能重建成无 NBT 注册模板。

取物原则读到这里即可；具体组合按需查下方 [书签快捷键详表](#书签快捷键详表)。表是人工原生操作参考，不要求每次逐行阅读或模拟快捷键。

## 临时假物品与书签清理

仅为标记、写样板或其他一次性步骤创建的 NEI 假物品与收藏，默认按临时项处理。核对操作结果后，或任务放弃且这些临时项已不再需要时，Agent 应主动清理，作为完成操作的一部分，无需等待另一次清理指令。

- 先确认目标标记、配置或已编码样板正确，再清理临时来源假物品和已无用途的任务书签。保留已保存的目标标记、已编码样板及真实光标/背包物品；已经配置好的虚拟槽位不属于待清理的 NEI 来源假物品。
- 受支持的语义假物品标记已自动释放 NEI 假光标，应检查返回的 `ghostRelease`。原生或手动拖动留下的假物品，可使用当前界面身份调用 `mcb_nei_item_action(action="release",screenId=...)`，确认 `ghostRelease.available=true` 且 `ghostPresentAfter=false`。此操作保留目标配置和收藏，因此不等于完成书签清理。清理失败时先核对已提交的结果，再单独恢复清理，不能为清除假物品而重复标记或编码。
- 记录本次新建了哪些书签、所在页及需要保留的原因。复用既有书签不表示它可以被删除。永久书签及明确后续要用的书签，保留到明确不再需要时，并在任务记录或交接中保留用途说明。
- 大量书签可以新建书签页，按任务、配方批次或用途分页整理，将临时条目与保留条目分开。书签过多时可以手动清理过期或已无用途的条目；永久及明确后续仍要用的条目继续保留。删除关联配方或整组前确认实际范围，清理后核对保留条目仍在。

所参考 NEI 版本的独立书签集合称为 namespace（命名空间）：通过命名空间的 `<` / `>` 控件可到达末尾空白集合，用作新页；最后一页有条目后，NEI 会再提供一个空白集合。它与同一集合内的可见内容翻页、配方分组是不同概念。读取当前控件并核对切换结果，记录页面用途；空白集合被移除后，页号可能改变。`mcb_nei_navigate(action="page")` 翻的是配方页，不能代替书签集合切换。

Agent 通过下述原生书签操作完成清理。释放假光标是另一项操作，会保留收藏。对保留项、关联配方及失败或不支持的操作，如实记录跳过原因和剩余临时项；不静默改用 Computer Use、系统键鼠、编辑文件或人工补做。

## 原生书签管理

先调用 `mcb_nei_bookmarks(screenId=...)` 读取 `contextToken`、当前书签页、各页的命名空间 ID、分组、当前可见行及精确条目 ID。条目分页只覆盖当前命名空间，后续页沿用令牌；界面、布局、物品/NBT、数量、分组或保留标记变化后重新读取。

`mcb_nei_bookmark_action` 均需当前 `screenId` 与 `contextToken`：

| 动作 | 目标与效果 |
| --- | --- |
| `add_item` | 实际 `neiItemId`、`taskId`，可选 `count`（默认零）、`groupId`、`retention`。保留来源完整 NBT，新增单物品书签；已有条目不被覆盖或自动接管。 |
| `retain` | 明确的 `entryIds`、`taskId` 和 `retention=temporary/permanent/future`，分别表示临时、永久、后续待用；这是对已有条目的显式归类。 |
| `cleanup` | 按 `taskId` 一次清理各页中的本任务临时条目；保留未登记、永久及待用条目。关联配方含保留或未登记条目时，整份关联配方跳过。 |
| `remove` / `remove_recipe` / `remove_group` | 按精确 `entryIds` 或 `groupId` 删除。删除配方书签时，一次性删除同组、同一命名空间中整份配方的输入、输出及关联物品；其他位置的独立收藏保留。普通物品书签单独删除，remove_recipe 可由关联条目明确选择整份配方。永久/待用条目明确不再需要后，用 `releaseRetained=true` 解除保护并删除。 |
| `namespace_new` / `namespace_select` | 选中原生末尾空白集合，或当前返回的 `namespaceId`。在空白集合中新增条目后，NEI 会提供下一张新页。 |
| `group_rows` | 使用本次 `visibleRows` 的 startRow/endRow；不传 groupId 时新建组，零表示移回默认组，也可指定已有组。沿用原生关联配方/链的行分组规则。 |
| `group_mode` / `recipe_collapse` | 设置组的 viewMode（`DEFAULT`/`TODO_LIST`）、crafting 或 collapsed；配方折叠用 entryId/collapsed，须属于实际 crafting 组。 |
| `move_item` / `move_group` | 用当前条目 ID 或可见行排序；move_group 可通过 sourceNamespaceId 把另一页的组移入当前页，包括新空白页。原生视图与配方链限制继续生效。 |
| `quantity` | 按 item/recipe/group 范围与精确 entryId/groupId，应用有符号、有界的倍率 delta。只改书签需求量，不改真实库存。 |

保存整份配方时，使用 `mcb_nei_navigate(action="bookmark",...,taskId=...,retention="temporary")`，仅将本次新建条目登记到该任务。提供任务时默认归为临时；需要复用的条目在清理前改为 future/permanent。保留标记跟随本次客户端/世界中的实际原生书签对象；客户端、世界或原生书签重载后，失去归属的条目按未登记处理并保留。按任务记录核对当前条目后重新明确归类，不按物品名或旧页码猜测。

回读原生 `amount` 和 `multiplier`：`add_item.count` 是传入 NEI 的来源堆叠数量，保存后的需求量由原生流体/NBT 转换决定。普通组调量只作用于正需求条目，零需求模板保持不变。`remove_group` 同时删除原生分组设置；零号组是 NEI 永久存在的默认组。

单次清理最多选中 512 项，观察范围上限为 64 个命名空间、4096 项书签；更大的工作拆成有界任务。检查 deletedEntryIds/deletedCount、skippedEntryIds、cleanupComplete 和返回的 view。`changed=false` 不证明所要求的移动已经成功。操作会请求 NEI 原生保存，内存回读不等于磁盘持久化验收。部分失败或效果未知时先核对当前状态，不自动重放删除、分组或样板编码。

## 先确认焦点，悬停和按键来源

Agent 优先读取当前客户端的 `mcb_gui`，`mcb_gui_widgets`，`mcb_inventory`，`mcb_gui_tooltip`，当前配方页的 `mcb_nei`，必要时精确身份的 `mcb_item`。当前实际 ItemStack 的 `getTooltip` 比重建的 registryName + metadata 堆叠更能反映 NBT/状态；控件提示读取支持的公开适配并返回 coverage。数据不足才核对本地该版本源码，不把静态指南视为实时 GUI 状态。只有渲染/布局验证确有必要或用户许可时截图，不默认用截图识别物品和 tooltip。`gui.tooltip` 不改变真实hover，语义打开/导航使用下述独立接口。

`mcb_nei({"screenId":"当前screenId"})` 读取当前 NEI GuiRecipe 的公开 handler，recipe indices，PositionedStack 输入/输出/其它物品和候选。offset 默认 0，limit 默认 4 且最多 16；按返回的分页信息明确选择下一页，不自动全量遍历。`includeTooltips` 默认 false，需要时明确开启以读取实际 ItemStack.getTooltip，advanced 不模拟修饰键。NEI 虚拟堆叠不是 container slotNumber，不能直接传入 mcb_slot_click。支持的GT配方附gregTech：原始物品/流体输入输出，rawEUt/rawDurationTicks，概率/metadata条件及displayOverclock；后者仅NEI显示计算，不能代替实际机器超频。查看coverage/provider errors，未适配自定义绘制文本仍是未知，不得将缺失字段视为 0。查询不枚举全局配方数据库/配方图，不改变界面或执行实体R/U/A。

分页 offset/nextOffset 只对应当前可见原始 recipe refs，不是整个 handler 的 recipeCount。每份配方最多访问 64 个位置，每个位置最多 32 个候选；查看 positionsVisited，positionsTruncated，softBudgetPartial，alternativesTruncated 和 budget。软预算可能在读取 outputs/other 前耗尽，因此不完整结果中的空数组不代表配方没有输出或其它堆叠。chance 是 NEI 的 0.01% 原始单位，10000 表示 100%；不直接按百分比解读。modifiers 只报告当前实体键状态，advanced 不代表按住 Shift。

不默认采集或保存截图。必要或用户明确许可时只临时判断，尽量保留结构化/文字结论；`mcb_capture` 默认不含截图，本桥接不主动写入 PNG，不声称能够清除 Codex 应用内部媒体历史。

打开背包或容器 GUI，把真实鼠标停在目标物品上，再按查询快捷键。搜索框获得焦点时，R/U/A 等字母可能只输入文本。先点击文本框外或用 Enter/Esc 取消焦点；首次 Esc 可能只取消搜索焦点，下一次才返回上一界面。

2.8.145 已使用 vanilla KeyBinding；先用 `mcb_bindings` 查看已注册的运行时description/category/keyCode/keyName/held/physicalDown，不消耗 isPressed 状态。静态核对来自 `.minecraft/options.txt` 的 `key_nei.options.keys.*` 和 Controls 菜单。`config/NEI/client.cfg` 中留存的旧 `keys.gui.*` 和 Ctrl/Shift hash 值不能视为当前绑定。下表列出参考默认键，当前运行时绑定优先。 Shift/Ctrl/Alt常是动作语义，并非均有单独绑定项。

桥接 `mcb_gui_key` 的字符回调不能模拟真实 `Keyboard.isKeyDown`，鼠标悬停或修饰键。它只允许固定导航键及有限文本输入，不把 `character="r"` 当作NEI实体R。支持的查询/导航优先用语义API，不通过系统键鼠完成；未适配的书签/配方填充动作报告coverage限制。

## 后台语义打开与导航

`mcb_nei_open` 传当前screenId，mode=crafting或usage，source=registry/inventory/container/recipe。registry用registryName和可选metadata创建无实际NBT的模板；inventory用实际inventory slot(0..255)，container用当前真实slotNumber，recipe用当前handler原始recipeIndex和group(inputs/outputs/other)，position(0..63)，alternative(0..31)。实际堆叠来源保留NBT，优先于重新构建模板。各source只接受自己的参数。没有GUI时screenId必须明确传空字符串；有GUI时必须匹配当前身份。

`mcb_nei_navigate` 用当前非空screenId和action=handler/page/back。handler/page必需当前有效index，back不传index。这些动作经NEI正常API完成，不需要悬停或实体R/U/箭头键。`action="bookmark",index=原始recipeIndex,saveCount=...` 保存**整份配方**，默认 `saveCount=true`；false 只是不保存数量，不会变成仅收藏产物。它保留已存在的配方，不等同于原生 A/Shift+A 的切换删除行为。提交后重新读取GUI和配方页，校对真实handler/recipeRef；不因异步状态未变就重复动作。稳定控件可通过mcb_gui_widgets读取supportedActions，再按widgetId语义操作，不猜旧截图坐标。

## 批量取物与创造背包同步

先核对已有库存、普通 36 格容量、每种堆叠上限和整批数量，再在 `mcb_sequence` 中组合各项 `search` -> 等待精确 panel 身份 -> 绑定本次 `neiItemId` -> `take`。搜索是异步的，保留约 1 秒的等待间隔；不为每次 take 再读背包。批次末统一核对完整身份/NBT 与实际数量增加，提交回执不证明拾取，失败或未知时先核对原批次，不重复领取。

Minecraft 1.7.10 的创造物品分类页会忽略普通背包的部分 `S2FPacketSetSlot`，快捷栏更新与普通背包更新不能等同。health 广告 `inventoryPlayerTabVersion>=1` 时，`mcb_open_inventory` 自动通过现有原生标签回调选中玩家物品栏，并返回实际 GUI 状态；复用该结果的 screenId，无需追加一次 GUI 查询。旧模组需使用返回的 `inventoryTabWidgetId` 选择玩家物品栏。`take` 要求玩家物品栏页签，在分类页会在发放前拒绝。已发生服务端有物品而客户端未同步时，先核对双端数量，可用正常容器打开触发原生全槽同步；关闭分类页本身不保证补发之前丢失的槽包，不用再次 take 修补显示。

## 查询，书签与导航

| 参考默认输入 | 悬停位置或前提 | 行为 |
| --- | --- | --- |
| R | 物品，搜索未聚焦 | 查看获取/合成配方 |
| U | 物品，搜索未聚焦 | 查看用途，通常包含该物品作为机器/催化剂的配方 |
| Ctrl+U | 同上 | 只查普通用途，排除催化剂查询 |
| Shift+R / Shift+U | 同上 | NEI 本体没有另设“配方树”动作，仍走 R/U 查询 |
| Backspace | 配方界面，文本框未聚焦 | 返回上一层配方查询 |
| Esc / E | 配方界面 | 返回最初容器；文本框焦点优先处理 |
| Up/Down | 配方界面，文本框未聚焦 | 切换配方分类/机器 handler |
| Left/Right | 同上 | 翻当前分类的配方页 |
| PgUp/PgDn | NEI 面板 | 面板翻页 |
| O | 容器 GUI | 显示/隐藏 NEI |
| B | 容器 GUI | 显示/隐藏书签面板 |

书签的 A/Shift+A、删除、滚轮、领取和分组见下表。bookmarkItemsWithRecipe 和 bookmarkRecipeWithCount 均须读取实际配置，不得直接沿用历史客户端配置值。

NEI 面板/配方图标通常左键查合成，右键查用途。作弊模式及权限允许时，面板普通点击可能发物品，Shift+左/右键可保持查询行为。普通背包槽位点击仍是拿取物品。每世界的 NEI 模式可覆盖全局设置；须读取当前世界的实际 NEI 模式，不能从静态示例推断；查询优先使用语义入口。

## 书签快捷键详表

以下为 **2.8.155-GTNH** 的源码行为。这些是源码默认键，不证明当前客户端采用相同配置。 可重绑项为 `bookmark.add`，`bookmark.remove_recipe`，`bookmark.favorite`，`bookmark.favorite_item`，`bookmark.pull_items`，`bookmark.chat_link`，`bookmark.open_crafting_tree`，`bookmark.hide`，`gui.prev/next`；A 的添加和组删除是两个独立绑定项，不保证用户重绑后仍相同。Shift/Ctrl/Alt 与鼠标组合由相应处理器判定，并非均有独立 Controls 项。`config/NEI/client.cfg` 顶部旧 `keys.gui.*` 不作为当前实际绑定；运行时以 `mcb_bindings`/Controls 为准。搜索框聚焦时字母可被视为文本。

表内 **读** = `mcb_nei_view(section="panel",panel="bookmarks",scope="visible"或"all")` 读取当前命名空间；保留 contextToken/分页/neiItemId，不把 all 当作所有命名空间。**原生限定** = 当前没有对应 MCB 语义动作；人工可在游戏内按表操作，Agent 不用 `mcb_gui_key` 伪造修饰键或自动转为 Computer Use。先用 `mcb_gui_pointer` 设置逻辑悬停后，可用已完成自然帧的 `mcb_nei_view(section="detail",target="hover_hotkeys")` 按需读取本地化快捷键提示；无已完成帧时是未知。`mcb_gui_tooltip` 本身不移动悬停。

### 收藏、删除与配方树

| 默认输入 | 悬停/作用域 | 动作与数量，是否联动 | 何时用 | 已有 MCB 入口 |
| --- | --- | --- | --- | --- |
| A | 目标物品，不在已有书签上 | 切换该物品收藏，不补存原料；通常不记数量，已有同配方/配置可影响关联和保量 | 只需目标产物 | add_item 明确添加；remove 明确删除 |
| Ctrl+A | 同上 | 物品收藏并保留来源数量；物品面板会应用原生数量控件 | 需要保留一个物品的需求量 | add_item 指定已核对的 count |
| Shift+A | 配方物品或配方填充按钮 (旧说明常称 ?) | 切换该配方的原料和所选输出；无可用配方时退回物品收藏；保量受 `bookmarkRecipeWithCount` 控制 | 需要整方关系 | `mcb_nei_navigate(action="bookmark",index=...,saveCount=...)` 为保留已有的整方添加，不执行切换删除 |
| Ctrl+Shift+A | 同上 | 整方收藏并保量，原料/输出按配方相联 | 需要整方数量 | 同上，显式 `saveCount=true` |
| A | 已有展开书签 | 删除单项；配方链关联条目/折叠组可拒绝单删，不能假定已删除 | 整理一个条目 | remove 指定 entryIds；配方书签按同组整份配方一次删除 |
| Shift+A | 已有配方书签 | 删除关联配方；crafting 关系可扩展到关联链，折叠 crafting 组会整组删除 | 明确删除整方/链 | remove_recipe 明确选择配方；整组另用 remove_group |
| Shift+A | 组左侧边栏，或默认组页码工具区，无物品悬停 | 删除整个目标组，不是新增收藏 | 明确清理整组 | remove_group 指定当前 groupId，检查保留项 |
| 左键 / 滚轮 | 配方 favorite 按钮 | 切换 preferred recipe / 多输出时选择关联输出；不领取物品 | 设置树的优选配方 | 左键可用当前坐标 `mcb_gui_click`；滚轮原生限定 |
| Shift+A | favorite 按钮 | 另建基于 favorites 的配方树书签组，与在输出上保存单方不同 | 需要多步配方树 | 原生限定，整方 bookmark 不等价 |
| Shift+F | NEI 物品/历史面板 | 有 favorite 时保存配方树组；在配方可替代原料上由另一处理器切换 preferred ingredient | 优选路线/原料 | 原生限定；不等同普通 F 搜索 |
| T / Shift+T | crafting 模式组边栏 / favorite 按钮 | 打开组 crafting tree / 先保存树再打开；不改变真实库存 | 查看链关系 | 原生限定；已打开树可用 `mcb_nei_view(section="tree")` 读取 |
| Ctrl+L / Ctrl+Shift+L | 物品 / 关联配方；组边栏 Ctrl+L | 发送物品/配方/整组聊天链接，不调整数量 | 仅用户要求分享时 | 原生限定，有实际发送副作用 |

原生收藏是 toggle，重复 A 可能删除。`saveCount=false` 的 count=0 书签仍是带完整身份/NBT的**模板**，不是实际库存或“只需零件”。已有零数量模板修复保留；取物时在副本上应用正数，不回写书签。若原生 Shift+A 退回单物品保量，也不能把它视为明确的物品级入口。

### 数量与滚轮

滚轮上/下在数量路径为增加/减少。下表的普通物品单位、配方倍率、整组需求不是同一量；大步长使用原生档位对齐，不是保证每个显示数都简单加减步长。

| 输入 | 悬停/作用域 | 步进与联动范围 | 何时用 | 已有 MCB 入口 |
| --- | --- | --- | --- | --- |
| Ctrl+滚轮 | 展开组中的物品级 ITEM 书签 | 普通物品倍率 +/-1，可降至 0；附带 recipeId 不自动变成整方调量 | 单物品需求 | quantity scope=item，指定当前 entryId 与 delta |
| Ctrl+滚轮 | 配方的 RESULT/INGREDIENT 书签 | 配方倍率 +/-1，同配方输入输出联动；链中间配方下限可为 1 | 整方批量 | quantity scope=recipe，指定当前 entryId 与 delta |
| Ctrl+滚轮 | 组边栏/默认组页码工具区，或折叠组图标 | 整组比例/链输出倍率调整；普通组只调正数量条目，不承诺每项各 +/-1 | 整组需求 | quantity scope=group，指定 groupId 与 delta |
| Ctrl+Alt+滚轮 | 书签物品 | 使用可见且非零的数量控件值作步长；否则流体显示项 144，其它按最大堆叠数；仍沿用上面物品/配方/折叠组作用域 | 大步调量 | quantity 指定作用域，以已核对步长作为 delta |
| Ctrl+Alt+滚轮 | 组边栏或默认组页码工具区 | 可见非零数量控件作步长，否则 64；调整整组 | 大批组需求 | quantity scope=group，明确 delta |
| 滚轮 / Shift+滚轮 / Ctrl+滚轮 | 全局数量输入框 | +/-1 / +/-10 / +/-64，最低 0；同按 Shift 和 Ctrl 时 Shift 优先；改控件，不直接改书签组 | 控制默认领取/大步长 | 原生限定；仅在实际广告控件时用 widget 的 set_text/set_value |
| Up/Down | 数量框已聚焦 | +/-1，最低 0；不是书签翻页 | 精调控件 | `mcb_gui_key` 导航键，必须已有正确焦点；读回数值 |
| Shift+滚轮 | 书签带替代项提示 | 切换原生提示/书签的替代物品，不是数量；需实际存在替代项 handler | 选替代物 | 原生限定；`detail target="bookmark_permutations"` 可读候选 |
| 普通滚轮 | 书签正文 | 上一/下一页，不是数量 | 查看其它条目 | 可用读的分页取数据，不是改变 UI 页 |

例：单轮输出 4 件，需要 1 件，整方倍率为 1 仍表示 4 件。应选物品级收藏再调其数量，或直接对精确条目 `take(count=1)`，不能靠把配方倍率滚到 1 实现。书签数量为 0 时原生数量准备会回退到数量控件/默认堆叠，普通左键未必只领 1 件。

### 领取、移物与实际合成

| 输入 | 悬停/前提 | 数量与影响范围 | 何时用 | 已有 MCB 入口 |
| --- | --- | --- | --- | --- |
| 左键 / 右键 | 容器中的书签，NEI 允许作弊，不按 Shift；当前不是 GuiRecipe | 按书签准备数量领取 / 领取 1 件；0 数量会回退；生成真实物品 | 授权创造取物 | `mcb_nei_item_action(action="take",neiItemId=...,count=1..64)`，默认 1，独立于书签量；还受原生堆叠上限 |
| 左键 / 右键 | GuiRecipe 中，或按 Shift，或不允许作弊 | 查询合成 / 用途，不保证发物品 | 查配方 | `mcb_nei_open(mode="crafting"或"usage",...)` |
| 中键 / 原生拖动物品 | 面板条目 | 创建 NEI 假拖放/模板，不是库存到货证明 | 幽灵标记 | 优先现有 ghostSource；`ghost_pick` 仅接受当前支持的 item 面板命中，不声称支持书签全范围 |
| V / Shift+V | 物品，当前容器有 IBookmarkContainerHandler | 从实际容器提取目标量 / 只补扣除玩家库存后的缺口，不凭空发放 | 生存取物 | AE 场景用 `mcb_ae_action(action="resource_take",resourceId=...,mode="single"等)`；其它容器按支持能力操作，无通用 V 入口 |
| V / Shift+V；或左键 P 按钮 (可按 Shift) | 组边栏；P按钮针对默认组 | 提取整组需求 / Shift 时只补缺口；P 是界面按钮标签，不是键盘 P | 整组取料 | 原生限定；不得将一次 take 视为整组提取 |
| S / Shift+S / Ctrl+Shift+S | 支持布局/填料的配方或书签 | 幽灵布局 / 尽可能填料 (默认上限 64 份) / 按书签倍率填料 (上限 64，非书签 1 份)；第三方兼容语义可能不同 | 查看/填合成格 | 当前配方 `mcb_nei_navigate(action="overlay",index=...,shift=...)`；不承诺支持 S 的所有倍率语义 |
| Shift+C / Ctrl+Shift+C | 支持 autocrafting 的配方/书签，或 crafting 组边栏 | 正常合成 / 计入已有库存只合成缺口；消耗材料，组可运行整链 | 真正合成 | 原生限定；AE 下单用既有 AE 操作，不把它当 GT 机器加工 |

Ctrl+左键还有依赖服务端 NEI 与 infinite handler 的无限物品分支，不用作有限领取。`take` 用独立正数量并保留原物品完整 NBT，仍需创造、developer、空光标、原生权限和容量；在创造玩家物品栏页操作。提交回执不是到货，批次末核对精确库存/NBT/数量。仅标记用 ghostSource，不为生存标记生成真实物品。

### 分组、排序、折叠与分页

| 输入 | 悬停/作用域 | 动作与整组影响 | 何时用 | 已有 MCB 入口 |
| --- | --- | --- | --- | --- |
| 左键拖动 / 右键拖动 | 书签左侧组边栏跨行 | 创建/纳入组 / 将选中行排除到默认组；松开后提交分组，不删除真实物品 | 整理分组 | group_rows 使用本次可见行和目标组 |
| Shift+左键拖动 | 物品 / 组边栏 | 重排条目 / 移动整组；TODO/crafting 的 RESULT 可带整方一起移动 | 排序 | move_item/move_group，沿用原生排序规则 |
| 左键 / 右键 | 组边栏或默认组页码工具区 | 切换组视图模式 / 切换 crafting chain，不是领取 | 切换展示/链计算 | group_mode 明确设置目标模式 |
| Alt+左键 | 组边栏、默认组页码工具区或折叠组图标 | 折叠/展开整组；crafting 配方图标可折叠关联配方 | 收起/展开 | group_mode 或 recipe_collapse 明确设置 collapsed |
| PgUp/PgDn，或页箭头左键 | 悬停书签面板 / 对应页箭头 | 上一/下一页，不改数量 | 翻书签页 | `mcb_gui_key` 的导航键须正确逻辑悬停；普通箭头可点；读的 offset 不是 UI 页 |
| 滚轮，或 < / > 按钮左键 | 命名空间切换区 | 上一/下一 namespace，与组、面板页不同 | 切换收藏集合 | namespace_select/namespace_new，或已观察到的原生按钮 |
| B | 容器 GUI，搜索未聚焦 | 显示/隐藏书签面板，不删除书签 | 展示开关 | 原生限定；`mcb_gui_key` 字符 b 不等于实体 B |

物品/配方/整组删除、数量及排序使用上述原生书签接口。favorite 配方树创建及仍标明“原生限定”的其他手势保留各自覆盖边界。普通原生点击仍需当前 screenId、坐标和目标证据；`mcb_nei_navigate(action="page")` 只翻配方页。

## 搜索

F 在参考版本聚焦可见的全局搜索框，不是旧教程的 Ctrl+F。右键清空，左键双击切换背包匹配高亮；聚焦时 Up/Down 切换历史。widgetAutofocus 可配置，不假设打开背包自动聚焦。

在 Extended 模式 patternMode=1、spaceMode=0 等相应配置下，空格为 AND，`|` 为 OR，`-` 排除，双引号包短语，`*`/`?` 为通配符，`r/.../` 为显式正则。`@GregTech` 按模组显示名过滤，不保证缩写/modId；`%` 为 subsets；`=` 化学式依赖已安装的扩展提供方。先检查当前设置。

搜索配置 `0=ALWAYS`、`1=PREFIX`、`2=NEVER`。tooltip/identifier/oreDict 为 ALWAYS 时普通词已搜索这些内容；`#`/`&`/`$` 只有相应过滤器设为 PREFIX 才可能注册，不能把历史设置视为所有用户的语法。

配方界面的放大镜打开独立配方过滤框，不是全局物品搜索。它支持 `<text` 原料过滤，`>text` 输出过滤，`!text` 排除配方等；按住 Shift 查看该界面的语法提示，以当前版本提示为准。

## Shift，滚轮和数量

| 输入/目标 | 行为 |
| --- | --- |
| 配方页按住 Shift | 暂停原料替代项自动轮换 |
| Shift+滚轮，悬停有替代项的原料/催化剂 | 手动切换替代物品，优先于分类翻页 |
| Shift+滚轮，没有被特定 widget 接管的配方区域 | 切换配方分类/机器 handler |
| 普通滚轮，配方正文/页码区 | 当前分类翻页，handler/widget 可先接管 |
| 普通滚轮，NEI 物品面板 | 面板翻页 |

数量控件，书签配方倍率和实际物品堆叠不是同一个状态。先确认鼠标在哪个控件上，再调整。

## 幽灵布局，实际填料与合成

S 悬停已识别配方的物品时显示幽灵布局，需该 GUI/handler 支持 overlay renderer。鼠标离开物品后按 S 可清除布局。幽灵布局不代表物品已放入槽位。

Shift+S 尝试把已有材料填入支持的合成网格；默认 transfer 的 multiplier=0 表示尽可能填最多 64 份，仍受库存/槽位限制。Ctrl+Shift+S 在书签上按书签倍率填入，上限 64；普通非书签目标为 1 份。旧第三方 handler 可能只有 maxTransfer 兼容语义，不保证精确指定份数。

配方页 ? 按钮要求容器和 handler 有适配。NEI 配置为 `shiftOverlayRecipe=true` 时，普通左键通常显示布局，Shift+左键通常实际填料。AE/NEE 等 handler 能覆盖此设置，应先看按钮 tooltip 和对应 AE 指南。按钮不存在或禁用不代表配方缺失，也可能是容器类型不匹配。

Shift+C 在支持的合成容器中按配方/书签组执行正常合成；Ctrl+Shift+C 只合成缺少的数量，计入现有库存。需要实际材料和支持的 autocrafting handler，不能强制 GT 机器绕过电压/结构/配方条件。

V 在支持 `IBookmarkContainerHandler` 的存储容器中按书签提取物品；Shift+V 只补玩家已有库存之外的缺口。这些动作会取物/移物/消耗材料，操作后检查库存和界面状态。

## GTNH 收藏与配方树

配方旁的星形按钮左键切换 preferred/favorite recipe；多输出配方可在星号上滚轮切换关联输出。**Shift+A 悬停星号** 保存基于 favorite 的配方树到书签组，与悬停输出或 ? 按钮保存单个配方不同。favorite depth 由当前配置决定。

Shift+F 在物品/历史面板有 favorite 时保存 favorite recipe tree；在配方中的可替代原料上切换 preferred ingredient。F/Shift+F 的含义依位置变化。T 在启用 crafting mode 的书签组上打开 crafting tree；星号上 Shift+T 可先保存树再打开。

GTNH 安装的 BogoSorter 还可用 T 悬停搜索 AE 终端，其焦点/界面条件与书签树不同；详见 AE 指南。不把同一按键在不同界面的作用合并成一个固定动作。

Ctrl+C 复制转义的搜索名称；Ctrl+D 复制矿辞列表；Ctrl+X 复制 registry identifier，可能含 `/metadata`。Ctrl+L / Ctrl+Shift+L 会发送物品/配方/组聊天链接，涉及发送，不作为默认调试步骤。F7/F9 是世界刷怪/区块 overlay，与配方 S 不同。Z 的 item zoom/next tooltip 默认存在重复绑定，冲突处理需按当前环境验证。

## 来源与验证边界

书签增补来源：[NotEnoughItems 2.8.155-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.155-GTNH/NotEnoughItems-2.8.155-GTNH-sources.jar)，SHA256 `fd7efd4ad77a7d250b2d2e9854cba7d643222a941cc28a4c808937505c0b2973`。源码默认键不能证明当前内存绑定；应读取当前客户端。

- 2.8.155 默认绑定/修饰键：`NEIClientConfig.setDefaultKeyBindings:850-899`，`KeyManager.isKeyDown/isHashDown`。
- 物品/配方 toggle、数量保留、V 与组删除：`api/ShortcutInputHandler.java:81-178,355-475`；favorite 树：`recipe/GuiFavoriteButton.java:125-205`；优选替代原料：`recipe/widget/RecipeWidget.java:463-470`。
- 删除范围、拖排、工具区、滚轮、取物准备：`BookmarkPanel.java:318-373,555-606,652-903,988-1127`；组作用域：`bookmark/BookmarkGrid.java:318-343,1153-1269`，`bookmark/SortableItem`。
- 数量控件、0 回退和有限/单件领取：`ItemQuantityField.prepareStackWithQuantity/onMouseWheel`，`PanelWidget.mouseUp/onMouseWheel/handleKeyPress`，`NEIClientUtils.cheatItem:212-235/getScrollMultiplier:567-575`。
- MCB 能力核对：`NeiControlAdapter`，`NeiOverlayAdapter.bookmark`，`NeiItemControlAdapter`，MCP `catalog/nei.py` 和 `catalog/gui.py`。这些为源码与指南范围说明，不声称每行快捷键完成实机复测；count=0 兼容修复的既有用途不变。

精确版本源码：[NotEnoughItems 2.8.145-GTNH sources.jar](https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/NotEnoughItems/2.8.145-GTNH/NotEnoughItems-2.8.145-GTNH-sources.jar)。源码归档 SHA256：`d4ede32d2bd510e4d5312f79a2012da79ea7fec7f5eab23c52064e4377af3c5e`。

- 默认/实际按键加载：`NEIClientConfig.java:849`，`KeyManager.java:40,69,153`。
- 悬停与查询/书签：`RecipeItemInputHandler.java:35`，`api/ShortcutInputHandler.java:154,166,307,355`，`recipe/GuiUsageRecipe.java:112`，`BookmarkPanel.java:318`。
- 焦点/导航/滚轮：`recipe/GuiRecipe.java:621,738`，`PanelWidget.java:281,308`，`recipe/NEIRecipeWidget.java:175,486`。
- 搜索：`SearchField.java:210,230,249`，`TextField.java:60`，`SearchTokenParser.java:31,127,339,477`，`search/ModNameFilter.java:35`。
- 数量与配方填料：`ItemQuantityField.java:108`，`BookmarkPanel.java:1026,1130`，`bookmark/BookmarkGrid.java:1214`，`recipe/GuiOverlayButton.java:124,204,331,344`，`recipe/DefaultOverlayHandler.java:72`。
- 收藏树：`recipe/GuiFavoriteButton.java:80,125,184,203`，`recipe/NEIRecipeWidget.java:303`。

以上为对应版本源码行为及配置条件说明。内存中临时重绑，世界 NEI 模式覆写，第三方 GUI 适配，化学式 provider 和 Z 冲突未全部实测。不要因为指南存在就声称桥接已原生支持 NEI 快捷键或所有机器补料。

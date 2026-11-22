# 执行数据库

`mcb_packdata` 读取当前对话所绑定 Prism 实例已发布的 SQLite 索引。客户端关闭后仍可查询。Windows 默认位置为 `%LOCALAPPDATA%/MinecraftCodexBridge/packdata`；自定义目录时，导入器和适配器使用相同的 `MCB_PACKDATA_ROOT`。

## 选择数据来源

| 用途 | 来源 |
| --- | --- |
| 生存用料、查找配方、任务定义和手册 | 查询已发布索引，按记录的版本重复规划。规划有限批次时，将实测库存和可用机器交给[产线规划器](planner_zh.md)。 |
| 玩家当前进度、库存、供电、机器成型及交付 | 读取绑定客户端或获准的服务端观察。继续订单前核对实际消耗与产出。 |
| 配方改动、GUI 处理器、修饰键提示或数据不一致 | 在受影响的客户端上按实际脚本、语言和处理器查看[实时 NEI](nei_zh.md)，再采集新版本供后续查询。 |
| 创造与模组开发 | 比较修改前后的导出，并在指定世界验证受影响的行为。创造供给与测试夹具沿用原有的显式策略和角色要求。 |

## 检索记录

`status` 显示各数据集版本、源文件哈希、处理器覆盖及最近一次实例文件检查。传入上次的 `knownUpdateId` 可查看此后最近五次发布，包括不同数据集的变更。出现 `hasMore` 或编号未知时，可分页查看更新历史。`update_status` 列出已提交的更新，`changes` 可按更新编号、记录种类或变更类型读取前后摘要。完整的变更记录见 JSONL 审计文件。首次基线列出已有覆盖；某个处理器或可选数据源停止返回内容时，需要单独核实。

```json
{"action":"search","kind":"quest","query":"Motors","limit":5}
{"action":"related","resource":"gregtech:gt.blockmachines","metadata":201,"limit":3}
{"action":"search","kind":"recipe","handler":"gt.recipe.alloysmelter","resource":"gregtech:gt.metaitem.01","metadata":11300,"direction":"output"}
```

`related` 按物品身份关联处理器、配方、任务目标和奖励、指南图标与物品列表、结构控制器与材料、凿刻组、方块、提示文本以及已有的 RecEx 记录。取得处理器 ID 后，用 `search(kind="recipe", handler=...)` 查看该组配方。仅在正文中提到而没有结构化物品引用的记录，可另行按文字搜索。`get(kind,id,expectedRevision)` 读取完整记录；长记录及搜索结果用 `nextOffset` 翻页。对单类关联结果翻页时，可将返回的数据集版本传入 `expectedRevisions`。

配方检索接受注册名、`fluid:<name>` 或 `ore:<key>`。按物品查询 NEI 输入时，也检索已知矿辞成员和导出的替代项。数量、概率、零数量催化物和处理器专用条件以原记录为准。RecEx 的 `registry_recipe` 保留独立导出器中的注册表记录、来源类别和机器名称；其收录范围可能与 NEI 不同。

## 维护数据库

在源码或安装包解压目录的根目录中，使用适配器的 Python 环境运行脚本。任务书与 GuideNH 使用固定版本的 [Alu 导出器](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/tools/make-packdata.py)。比较或发布已有导出时，传入已完成的导出目录。更新仅替换选中的数据集。

```text
python scripts/update_packdata.py fetch --instance "<instance>" --exporter "<checkout>/tools/make-packdata.py" --language zh_CN --out "<workspace>/books" --no-publish
python scripts/update_packdata.py compare --instance "<instance>" --books "<workspace>/books"
python scripts/update_packdata.py update --instance "<instance>" --books "<workspace>/books"
python scripts/update_packdata.py get --instance "<instance>"
python scripts/update_packdata.py check --instance "<instance>" --nei "<export>"
python scripts/update_packdata.py compare --instance "<instance>" --nei "<export>" --audit-jsonl "<workspace>/preview.jsonl"
python scripts/update_packdata.py update --instance "<instance>" --nei "<export>" --audit-jsonl "<workspace>/changes.jsonl"
python scripts/update_packdata.py check-inputs --instance "<instance>" --datasets nei quests guide
```

最后一条命令按需计算实例静态输入文件的哈希，与采集时的输入清单比较，记录新增、删除及修改。缺少采集清单的旧导出显示为未经核实。NEI 检查覆盖模组 JAR、脚本、指定的 GT 配置和 Prism 组件信息。服务端覆盖项及动态配方需要实时核对。例如，已记录的输入相同时，AE2 外观板处理器在不同启动中仍可能返回不同数量的展示配方。调整产线计划前，应先比对涉及的处理器和具体配方。常规查询只读取上次检查结果。

采集当前 NEI 需要已关闭的独立 Windows Prism 实例、目标整合包、支持 `javac --release 8` 的 JDK，以及含有 `alu.aiplayer.export.NeiExport` 的 Alu 游戏运行版 JAR。采集器的构建方法见[固定版本的上游导出说明](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/docs/PACKDATA.md)。脚本启动指定实例，加载临时世界，采集已注册的配方处理器，并在退出后归档临时世界。每次采集使用新目录。若需先审核差异，再更新数据库，可运行以下命令。

```text
python scripts/update_packdata.py fetch-nei --instance "<instance>" --provider-jar "<alu-exporter.jar>" --out "<workspace>/nei-new" --no-publish
python scripts/update_packdata.py fetch-tooltips --instance "<instance>" --nei-base "<workspace>/nei-new" --tooltip-item "gregtech:gt.blockmachines@140" --out "<workspace>/nei-sampled" --no-publish
python scripts/update_packdata.py compare --instance "<instance>" --nei "<workspace>/nei-sampled" --audit-jsonl "<workspace>/samples-preview.jsonl"
python scripts/update_packdata.py update --instance "<instance>" --nei "<workspace>/nei-sampled"
```

`fetch-nei` 可在同一次启动中接收多个 `--tooltip-item` 参数。`fetch-tooltips` 在实例和安装输入仍相符的完整基线上补采。每次最多指定 32 个带元数据的物品注册名，默认采集客户端当前语言下的普通、Shift、Ctrl 三种视图，并列出无法读取的视图。原有的其他物品与视图会保留。导出中包含的结构、凿刻和方块数据一并纳入索引。若启动回执不明确，保留输出目录。待目标实例退出后，用 `finish-nei --instance <instance> --out <output> --no-publish` 核验并整理已完成的导出。若导出未完成，使用相同选择参数运行 `cleanup-nei`，清理该轮临时文件并恢复设置。

临时采集世界在窗口失焦时继续运行。如果窗口始终在后台，采集器可关闭一次初始的原版暂停菜单。将窗口切到前台后，后续暂停菜单由玩家控制。退出时采集器恢复失焦设置。采集中断后，待目标客户端退出，`cleanup-nei` 会恢复备份的游戏选项和窗口模式。

要导入独立的 [RecEx](https://github.com/GTNewHorizons/RecEx) 数据，安装适配器的 `recex` 可选依赖并指定已完成的 JSON 文件。

```text
python -m minecraft_codex_bridge.recex_packdata --instance "<instance>" --source "<export>/recipes.json" --mode compare
python -m minecraft_codex_bridge.recex_packdata --instance "<instance>" --source "<export>/recipes.json" --mode update
```

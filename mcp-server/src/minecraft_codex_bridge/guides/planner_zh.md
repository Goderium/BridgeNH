# 规划有限批次产线

`mcb_plan_production` 从当前绑定实例的 NEI 索引构造多级工艺图，调用固定版本的 GTNH Factory Flow 和 HiGHS WASM 求解器。游戏关闭时也可读取索引。结果以调用方提供的库存、机台数量和目标时间为条件。机台摆放、实际供电、配方执行和最终收货均需在游戏内核实。

## 准备求解器

按[整合包资料指南](packdata_zh.md)发布实例的 NEI 索引，安装 Node.js 22 或更高版本，并使用 MCP 的 Python 环境。在 BridgeNH 源码或安装包解压目录中为此实例安装一次。Git 对象库需要包含固定的 Alu 提交，可以使用稀疏检出，无需运行 npm install。

```powershell
git clone --no-checkout https://github.com/aruru-project/alu_gtnh_aiplayer.git <alu-checkout>
python scripts/plan_production.py --instance <Prism-instance-directory> --install-solver <alu-checkout>
python scripts/plan_production.py --instance <Prism-instance-directory> --request <request.json>
```

安装器读取固定提交的 Git 对象，校验文件摘要，将运行资产和许可证放在该实例的索引旁。MCP 进程需能读取同一索引根目录并找到 `node`。求解以独立进程运行，限时 18 秒，逐次核对运行文件与 WASM 的摘要。受支持的单方块电力机型会比对实例的配方处理器、参考机型目录和 GregTech 机器元数据。现场拥有的数量和供电条件由调用方提供。[来源、版本、许可证和哈希](https://github.com/aruru-project/alu_gtnh_aiplayer/blob/2abfae1a423f407f36cd9f73c0ede81a01e9a706/brain/vendor/gtnh-planner/provenance.json) · [GregTech 机器标识](https://github.com/GTNewHorizons/GT5-Unofficial/blob/bb00f3f1023e20d3cce84cf09abb442d22a3a5a5/src/main/java/gregtech/api/enums/MetaTileEntityIDs.java)

## 规划四块青铜板

MCP 与 CLI 读取相同的 JSON。先用 `mcb_packdata` 获取物品身份、配方 ID、处理器名称及机型。物品使用准确的 `namespace:item@metadata`，流体使用 `fluid:name`。物品按件、流体按 mB 计量。目标表示最终可用量，因此已有成品可以抵扣目标。供料边界使缺口保留为指定的原料。

```json
{
  "target": {"resource": "gregtech:gt.metaitem.01@17300", "amount": 4},
  "inventory": [
    {"resource": "gregtech:gt.metaitem.01@11035", "amount": 3},
    {"resource": "gregtech:gt.metaitem.01@11057", "amount": 1},
    {"resource": "gregtech:gt.integrated_circuit@1", "amount": 1}
  ],
  "feedstocks": ["gregtech:gt.metaitem.01@11035", "gregtech:gt.metaitem.01@11057"],
  "machines": [
    {"handler":"gt.recipe.alloysmelter","machine":"gregtech:gt.blockmachines@201","tier":"LV","count":1},
    {"handler":"gt.recipe.metalbender","machine":"gregtech:gt.blockmachines@221","tier":"LV","count":1}
  ],
  "horizonTicks": 1200
}
```

在 GTNH 2.9.0 中，合金炉以 15 EU/t 运行 200 tick 制成 4 个青铜锭，卷板机再以 24 EU/t 运行 4 批，每批 76 tick，复用 1 号电路。两台机器串行共需 504 tick，按 1200 tick 的窗口计算，平均负载为 8.58 EU/t。两台同时满载时为 39 EU/t。

指定路线时，从本实例 NEI 状态取得 `expectedRevision`，按产物传入 `recipeChoices`。将准确的产物标识填入 `resource`，将本次 `mcb_packdata` 查询结果中所选配方的 `id` 填入 `recipeId`。重新导出可能改变配方 ID。分别固定中间产物和成品即可指定两步路线。找不到的配方以及与指定机台不兼容的配方都会明确返回原因。将锡库存和供料边界换成 `gregtech:gt.metaitem.01@2057` 即可评估锡粉，导出中有对应的独立合金炉配方。

## 读取结果

工业高炉以 `machine` 指定控制器，`coilTier` 指定线圈，`energyHatch` 指定标准能源仓。每台高炉按一个与所报电压等级一致的标准能源仓建模。例如，铜镍线圈配 HV 能源仓时，三项依次为 `gregtech:gt.blockmachines@1000`、`cupronickel` 和 `gregtech:gt.blockmachines@43`。运行前在世界中核对结构成型、维护、排气和实际供电。

工序返还的试剂可以供后续批次使用。将启动用量计入 `inventory`，在 `feedstocks` 中声明其供料边界，按工序顺序确认首次投入和返还时间。例如，钨酸锂路线在酸处理后返还氯化钙。

- `steps` 按物料流向列出整数批次、配方引用、总耗用量和产量、复用工具及每个输入槽选择的具体材料。GT 数量为零的模具或电路需预留，后续批次可继续使用。明确的矿辞槽会展开已导出的同矿辞成员，普通精确 GT 输入只采用导出所列的身份。未耗用的库存与确定性副产物见 `remainingInventory`。预留工具单列。
- `flows` 汇总边界上的物料总量和期望、求解速度，单位为件/秒或 mB/秒。中间产物连接到下游输入。熔融青铜路线可以使用 MV 流体提取机、LV 流体固化机和复用板模。每个青铜锭提供 144 mB，每块板消耗 144 mB。已有 144 mB 熔融青铜可以少做一批提取。
- `missing` 列出执行条件中缺少的物料。`machinePools` 对照分数负载和整数机台。`averageEUt`、`integerBuildPeakEUt` 和 `serialScheduleTicks` 分别反映时间窗口内的平均用电、整数机台同时满载功率和串行调度时间。每一步可用现有相同机台与足量工具并行多个独立批次。串行排程给出一个足够的时间上界。超过窗口时，即使平均产能允许，也需另做流水调度核验。
- 查阅 `status`、`issues`、`guards`、`rejectedRecipes` 及求解器诊断。候选路径优先利用已有资源，找到可由库存供料的路径后可提前结束，结果不证明全局最优。没有证实有限启动顺序的物料循环会得到明确提示。概率产物、NBT 信息损失、通配元数据、未确认的特殊条件、未建模的机器、多方块的配置及搜索超限，都需更换路径或原生核查。导出器也可能遗漏隐藏 NBT 或配方限制，对标签敏感的工艺需核对游戏原配方和物品。
- `installationInputs` 表示与导出修订相符的最近一次文件检查。`refresh_recommended` 表示需刷新或原生复核，`not_checked` 表示没有匹配的检查。文件一致只覆盖记录的输入。将一次工作记为实际生产前，应读取绑定世界的机台、供电、投料、复用工具、中间产物和最终收货。

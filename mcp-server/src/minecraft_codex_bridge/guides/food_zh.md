# 生存进食与持续补饱

通过 `mcb_inventory` 核实实际快捷栏堆叠。关闭 GUI，在允许修改的策略下使用具备正常饥饿机制的存活生存角色。权限上限较高的策略同样允许实际生存角色按原生机制进食。成功使用后，目标槽位保持选中。饥饿值已满时，普通食物会在切槽前返回 `already_full`。

## 吃一份食物

```json
{"action":"eat","slot":2,"registryName":"minecraft:apple","metadata":0}
```

将实际槽位、注册名、元数据作为上述参数传给 `mcb_food`。BridgeNH 对原生动作为进食的物品使用一次，只在这份食物仍被使用期间维持使用键。结果为 `eaten` 时，服务端已通知同一角色完成这份食物，原槽少了一份或留下最后一份使用后的容器，客户端饥饿值与新的服务端更新均显示增加。

## 让治愈之斧持续补饱

```json
{"action":"healing_axe","slot":4,"registryName":"ExtraUtilities:defoliageAxe","metadata":0}
```

GTNH 2.9.0 使用 Extra Utilities 1.2.12。治愈之斧必须处于当前选中的手持槽位。世界总刻数每到 40 的倍数，斧会补 1 点饥饿值，饱和度修正系数为 0.2。正常每秒 20 刻时，约两秒补 1 点。剧烈活动可能消耗得更快。保持选中即可持续补饱，切换到其他槽位后效果停止，重新选中即可恢复。[GTNH 2.9.0 模组清单](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0/README.md) · [Extra Utilities 1.2.12 发布包](https://www.curseforge.com/minecraft/mc-mods/extra-utilities/files/2264383)

选中时饥饿值已满会返回 `selected_at_full`。存在缺额时，工具等到首次补饱得到验证，再返回 `replenished`。后续补饱由斧本身持续生效。`passiveWhileSelected` 为 true 表示当前仍持有该斧。可用 `mcb_hotbar` 正常切换至其他槽位，BridgeNH 尊重后续选槽。

## 核对结果

进食或等待斧首次增益时，另一个已连接的 MCP 工作进程可通过 `mcb_task_status` 读到 `owner="survival.food"`。调用 `mcb_task_cancel`，传入 `action="cancel"`、同一 `expectedSessionId` 及刚读到的 `taskId`，即可结束这次观察、释放它持有的使用键。原调用与取消回执均保留同一任务编号和食物结果。已开始使用时应核对剩余数量。斧在结束观察后仍按手持状态发挥原生效果，切换槽位即可停止补饱。

`timeout_ms` 限定单次观察时长，可设为 250 至 6500 毫秒，默认 5000。核对 `foodBefore`、`foodAfter`、可用时的 `serverFoodAfter` 和槽位物品数量。`foodGainVerified` 表示观测到饥饿值增加。原本已满时，该字段为 false。切换手持物、进食中断或效果未确认时会保留部分回执，再次使用前须读取真实饥饿值和背包。使用新版 BridgeNH 的服务端还可通过 `mcb_server_observe(kind=self)` 独立读取当前连接玩家的 `food` 和 `foodSaturation`。

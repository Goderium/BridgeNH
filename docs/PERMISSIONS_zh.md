# 玩家权限组与操作策略

[English](PERMISSIONS.md) | [简体中文](PERMISSIONS_zh.md)

BridgeNH 有两套独立设置：**玩家权限组**决定谁能向 Agent 发消息、查看调试和管理分组；**操作策略**决定 Agent 在当前存档或服务器允许使用哪些操作。它们都不会授予 Minecraft OP、创造模式或飞行能力，也不会代替对话与客户端的显式绑定。

## 玩家权限组

以下是默认能力；配置可以收紧，不能超过各组的上限。

| 组 | 默认能力 | 生效条件 |
| --- | --- | --- |
| `guest`（访客） | 查看自己的身份和帮助，不能发送消息、查看调试或管理分组 | 未登记玩家默认属于此组 |
| `user`（用户） | `say`、`steer`、查看自己的 `inbox` | 服务器明确授予 |
| `admin`（管理员） | 用户能力，以及私密调试、查询/修改分组、重载权限配置 | 服务器明确授予；调试显示需另行开启 |
| `agent`（Agent 账号） | 可以建立实际 Agent 对话绑定；绑定期间具备发送、调试，以及与服务器后台相同的权限管理能力 | 必须明确登记且实际对话租约有效；未绑定时按访客处理 |
| 服务器后台 / RCON | 全部对话管理能力 | 真正的服务器控制台身份；命令方块等其他发送者不属于此类 |

`agent` 的对话租约与 MCP 对话选择哪个客户端是不同的绑定。仅登记为 `agent`，不表示已经有 Agent 接管该账号。游戏 OP 不会自动成为 BridgeNH 管理员，`admin` 也不会自动获得 OP。

有效绑定且具有 `manage` 权限的 `agent` 账号可以查询并调整**其他玩家的权限组**，将其设为 `guest`、`user`、`admin` 或 `agent`，也可以重载权限配置。这包括授予或撤销管理员及 Agent 资格。在这些 BridgeNH 权限管理操作上，Agent 与服务器后台拥有相同的管理权，可直接通过 `/mcb group` 和 `/mcb permissions reload` 完成已授权的调整，无需人类管理员或服务器后台代为执行。

权限保存在**服务器**的 `config/codexbridge/permissions.json`。单人游戏使用该客户端的同一路径，不写入存档 NBT，同一客户端的存档共用玩家权限。联机以服务端配置为准，修改客户端文件不能取得服务器权限。

首次由服务器后台登记管理员或 Agent 账号。登记并绑定后，具有有效 `manage` 权限的 Agent 可以自行管理后续分组。后台输入指令时通常省略开头的 `/`。将 `<player-or-uuid>` 替换为实际在线玩家名或完整 UUID：

```text
/mcb whoami
/mcb group get <player-or-uuid>
/mcb group set <player-or-uuid> user
/mcb group set <player-or-uuid> admin
/mcb group set <player-or-uuid> agent
/mcb debug on
/mcb debug off
/mcb permissions reload
```

分组按 UUID 保存，在线名字只用于解析 UUID。指令修改成功后立即保存；写入失败不会只改变内存。手动编辑后执行 `permissions reload`；检测到外部修改时，指令会要求先重载，避免覆盖。无效配置会禁用玩家权限，修正后由后台重载恢复。组标签可以修改，能力可以收紧；不接受给访客增权、给用户调试/管理权限、OP 自动继承、免绑定 Agent 或非访客默认组。

调试默认关闭。管理员/有效 Agent 使用 `debug on` 后才会收到私密内部详情；服务器日志仍保留诊断记录。组变更会清除该玩家的个人调试开关。撤销发送权限会取消尚未投递的消息，撤销当前 Agent 资格会关闭租约；已经进入聊天的消息无法撤回。

普通发送玩家不需要安装 BridgeNH，也不需要 OP。承载 Agent 的客户端与服务器需要兼容版本；服务器对话桥还要求承载账号具有实际 OP 2 权限及已登记的 `agent` 组。其执行上下文以当前连接为准。完整消息用法见[玩家对话](PLAYER_DIALOGUE_zh.md)。

## 操作策略组

这些是允许能力的上限，**最终能否执行仍取决于当前游戏权限、客户端/服务端支持、具体工具及只读设置**。

| 策略 | 普通原生玩家操作 | 增强机器/结构观察 | 物资生成 | 直接后台操作 | 默认 QA 档位 |
| --- | --- | --- | --- | --- | --- |
| `survival`（生存） | 允许，使用实际物资和原生交互 | 不允许 | 不允许 | 不允许 | `off` |
| `survival_efficient`（生存高效） | 允许 | 有实际权限时允许 | 不允许 | 不允许 | `observe` |
| `creative`（创造） | 允许 | 有实际权限时允许 | 有实际创造/指令权限时允许 | 不允许 | `test` |
| `full`（完全支配） | 允许 | 有实际权限时允许 | 有实际权限时允许 | 支持的直接机器修改及游戏指令；仍检查实际权限 | `manage` |

普通操作包括受支持的移动、GUI、背包和原生交互，不等于所有工具均可用。选择 `creative` 不会切换游戏模式；选择 `full` 不会绕过服务器权限或让仅限本地的工具支持远程服务器。实际处于创造模式时，生存策略会拒绝受其保护的修改操作，避免把免消耗行为当作生存操作。

策略保存在**客户端**的 `config/codexbridge/policies.json`，按单人存档或服务器地址分别记住选择。新作用域采用默认值：`survival`、`path`、`readOnly=false`、`terrainChanges=false`、`qa=auto`。这与服务器玩家权限配置是两份不同文件。

在客户端聊天框使用以下本地指令；这些指令与 `mcb_policy` 共用同一接口：

```text
/mcb policy status
/mcb policy mode survival
/mcb policy mode survival_efficient
/mcb policy mode creative
/mcb policy mode full
/mcb policy move path
/mcb policy move tp
/mcb policy readonly on
/mcb policy readonly off
/mcb policy terrain on
/mcb policy terrain off
/mcb policy qa auto
/mcb policy reload
```

这是可选命令的列表，不是需要依次执行的脚本。先用 `status` 查看，再选择需要的一项；手动编辑文件后用 `reload`。工具的 `scope=current` 修改当前作用域，`scope=defaults` 明确修改默认值；已经单独配置的存档/服务器不会因此改成新的默认值。

## 移动、只读和地形设置

| 设置 | 含义与限制 |
| --- | --- |
| `movement=path` | 按当前真实状态步行或使用已有飞行能力；允许飞行、创造模式不等于已经飞行 |
| `movement=tp` | 需要普通 TP 指令权限及受支持的本地执行后端；不能使用 TP 时，在动作前解析为路径方式，不会在结果不确定后自动补做另一种移动 |
| `readOnly=true` | 拒绝归类为修改的操作，以及新 QA 测试/测试区清理；它不是客户端冻结开关，移动、生命周期、对话和策略控制仍有各自规则 |
| `terrainChanges=true` | 表示路线地形修改的许可意图；当前不会因此自动挖路、填路或增加挖掘规划器 |

所有移动仍需实际姿态、距离、碰撞、目标空间和必要的双端确认。调整策略会使旧等待/扫描等上下文失效；不会回滚已经执行的动作，也不会自动重放。

## QA 子策略

Horizon-QA/TST-QA 是可选测试集成，普通使用不需要安装。`qa=auto` 采用上表的主策略上限；显式选择只能收紧，不能超过该上限。

| 档位 | 能力 |
| --- | --- |
| `off` | 仍可发现是否有 QA 能力；不开始其他运行的观察或新测试 |
| `observe` | 查询支持的目录、状态和结果；不创建/清理测试区 |
| `test` | 在已授权测试区执行提供方声明的受控有限用例或通用声明流程；不代表任意 Java 回调已被沙箱化 |
| `manage` | 支持的测试管理能力，仍受权限、运行归属和范围校验；QA 传送还需要 TP 条件 |

使用 `/mcb policy qa off`、`observe`、`test`、`manage` 或 `auto` 选择。降低策略或启用只读后，仍可按明确的运行 ID 查询自己拥有的运行、请求提供方支持的正常停止及释放已结束记录；不能借此停止其他玩家任务。`forget` 释放记录，不代表删除测试区方块。完整测试边界见[测试指南（中文）](../mcp-server/src/minecraft_codex_bridge/guides/testing_zh.md)。

## 实际状态优先

以 `/mcb policy status`、`mcb_policy` 和 `mcb_health.operationPolicy` 的当前选择及有效能力为准。权限组不等于操作策略，操作策略不等于游戏授权，配置文件不等于对话绑定；工具列在目录中也不证明当前环境可执行。失败或结果未知时先检查实际状态，不重复提交操作来试探权限。

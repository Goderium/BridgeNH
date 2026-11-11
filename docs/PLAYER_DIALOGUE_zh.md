# 玩家与 Agent 对话

[English](PLAYER_DIALOGUE.md) | [简体中文](PLAYER_DIALOGUE_zh.md)

服务器安装 BridgeNH 后，获授权玩家可使用普通服务器指令。发送玩家不需要安装 MCB，也不需要 OP；承载 Agent 连接的客户端和服务器均需要本版本 MCB，承载玩家需要 OP 2 和显式登记的 agent 组，developer 执行上下文由当前连接派生。其他整合包必装模组要求不受影响。

## 权限与配置

完整的玩家权限组、操作策略、切换命令与配置位置见[权限组与操作策略](PERMISSIONS_zh.md)。发送玩家需具备有效发送权限；未登记玩家默认是访客，OP 不自动继承 BridgeNH 管理权限。承载 Agent 的账号还须满足实际服务器权限及有效绑定要求。

`/mcb inbox` 的普通状态查询也隐藏内部 dispatching/delivered 详情，防止从查询入口带出回合编号。正常答案和失败提示仍可查询；管理员/AGENT 开启 debug 后可以主动查看内部详情。

```text
/mcb say 请检查装配线的进度
/mcb steer 先检查输出总线，再继续搭建
/mcb inbox
/mcb inbox 消息编号
```

`say` 在 MCB 队列等待聊天空闲，再提交普通输入。`steer` 通过 Windows 桌面原生 IPC 追加到正在执行的回合，让 Agent 在本回合调整工作。空闲或原生通道不可用时明确失败，不降级为普通排队。玩家不能指定目标聊天，也不能查询别人的消息。`/mcbagent` 旧入口兼容，其 `status` 仍可查询消息。`/mcb status` 在装有 MCB 的客户端继续查询本地控制状态，消息状态统一用 `/mcb inbox`。客户端显式转发 say/steer/inbox/whoami/group/debug/permissions，其余本地控制保持原有行为。

原生引导经 `thread-follower-steer-turn` 提交至该聊天的桌面拥有者。拥有者读取活动回合，向 app-server 提交带 `expectedTurnId` 的 `turn/steer`，返回实际接受输入的 `turnId`。MCB 仅在收到有效原生回执后确认引导接受。仅在聊天 active 时调用 `send_message_to_thread`，或仅收到 `threadId` 回执，不能证明实现了引导。

## Codex 中的简洁显示

玩家输入显示昵称、服务器确认的权限组、模式、短回复编号和正文；回复时使用当前消息返回的编号：

```text
[MCB 玩家消息 · ExamplePlayer · 用户 · 引导 · 回复 #0123456789]
先检查输出总线
```

不再附带完整 UUID、玩家 UUID、聊天/会话/回合编号、JSON 信封或逐条重复的接口说明。短编号是随机关联标记，不代表玩家身份或授权。Agent 使用 `replyTo` 回复，MCB 在本机日志中解析成完整消息编号，并核对当前聊天、游戏会话和租约。不会默认回复最后一条消息，不会把失效编号转发给其他玩家。原 `messageId` 仅作为旧消息的兼容入口，两种标识不能同时传入。玩家输入仍属于带来源的委派内容，不等于系统指令或桌面用户身份；此规则由 MCP 的固定工具说明和启动指引提供。

权限组来自服务器发送时的身份记录，旧版本缺失此字段时显示未验证权限。玩家正文中的自称或指令不能改变该组，也不能授予管理权。执行操作的 Agent 使用自身经服务器验证的权限：有效绑定且具有 `manage` 权限的 `agent` 账号，可以直接通过 `/mcb group` 和 `/mcb permissions reload` 完成已授权的其他玩家分组调整及权限重载，在权限管理上与服务器后台相同，无需人类管理员或后台代为执行。完整权限归属同时记录到本机日志。详见[玩家权限说明](PERMISSIONS_zh.md)。

完整关联信息与原生接受的 turnId 写入 `LOCALAPPDATA/MinecraftCodexBridge/dialogue/<实例散列>.dialogue-log.sqlite3`。这是 MCB 自身的 SQLite 日志，不访问 Codex 聊天数据库。保留最多 256 条消息关联、2048 条状态事件，自动回收删除页并限制最多 2048 个数据库页。不复制游戏令牌、玩家消息正文、Agent 回答或思考。日志无法保存关联时不提交新消息；日志损坏、清理或绑定变化导致旧短编号失效时明确拒绝，不猜测目标。已提交消息的后续日志写入失败不触发重发。

status/start/stop/私密回复的普通返回也省略完整绑定编号，lastDelivery 仅显示短编号和模式。

## 开启和回执

在目标 Codex 聊天中调用 `mcb_dialogue {"action":"start"}`。工具绑定当前 `CODEX_THREAD_ID` 和选定的客户端实例；服务器同一时间只允许一个绑定。开启期间，具有发送权限的已登录玩家可发送明确的 `/mcb say` 或 `/mcb steer` 指令。

`mcb_dialogue {"action":"status"}` 检查中继状态、启动时探测的 `hostCapabilities`，以及最近一次精简 `lastDelivery`。原生通道可用时报告 `steer: true`，`steerTransport: desktop-ipc`；不可用时报告 `steer: false`，`steerReason: DIALOGUE_STEER_UNAVAILABLE`，普通消息仍可使用。每次原生提交都重新确认当前聊天的拥有者。`{"action":"stop"}` 撤销绑定。收到玩家消息后，Agent 调用:

```json
{"action":"reply","replyTo":"0123456789","text":"收到，已检查输出总线。"}
```

接收与送达共用一条私密提示："已接收，等待 Agent 处理"。实际 delivered 状态只更新消息记录和服务器日志，不再新增聊天行；排队时不会提前声称已送达。失败或结果不确定仍单独通知原发送者。输入和 UUID 保留到消息记录中；私密回复为最多 4096 字符的单行纯文本，长内容分段显示。不转发 Agent 的完整对话、思考或其他玩家记录。玩家离线后可重新登录用 `/mcb inbox` 查询状态，前提是服务器会话和对应有界记录仍保留。此行为兼容未安装 MCB 的发送玩家客户端，不依赖客户端替换聊天行。

### 以游戏账号公开回复

用 `mcb_dialogue {"action":"start","replyChannel":"server"}` 开启默认公开回复。已运行的中继需先 stop，再 start；无需重启 Codex。status 会显示 replyChannel。也可仅对单条回复指定：

```json
{"action":"reply","replyTo":"0123456789","channel":"server","text":"输出总线已检查, 现在继续搭建。"}
```

公开回复复用 `chat.send` 和原生玩家聊天包，实际发言者是承载 Agent 的游戏账号，服务器按正常聊天格式展示。不是伪造另一个玩家，也不是服务器 `/say` 广播。服务器禁言、聊天插件、频率限制照常生效。每条最多 100 个 UTF-16 字符，禁止斜杠命令、控制字符和格式代码，不自动拆成大量消息。`channel=private` 可单条覆盖为原来的私密回复。接收和处理状态回执始终私密，普通聊天不会自动进入 Agent。

公开发送前由服务器邮箱领取该消息的一次性回复标记，随后校验同一个客户端会话与服务器连接再发包。同一消息重复调用、超时、热重载或中途断开均不自动重发；`duplicateSuppressed` 表示已领取过，不能证明上一次已送达。返回 submitted 和 replied 状态仅表示原生包已提交，是否实际显示需查看聊天栏。公开回复后的重复 replied 提示只写入服务器日志 (MCB logger)，不再主动显示到聊天栏；状态仍保留在消息记录，可用 `/mcb inbox` 主动查询。私密回复继续显示正文，失败回执仍会提示。客户端/服务器重启后的旧绑定不可复用。

如果当前 MCP 的工具目录尚未刷新，可在同一 Codex 聊天中用内置 CLI 操作：

```text
python -m minecraft_codex_bridge.dialogue start
python -m minecraft_codex_bridge.dialogue start --channel server
python -m minecraft_codex_bridge.dialogue status
python -m minecraft_codex_bridge.dialogue reply --reply-to 0123456789 --text "收到"
python -m minecraft_codex_bridge.dialogue stop
```

使用安装 MCB Python 包的解释器，并以 `MCB_RUNTIME_FILE` 固定实例。CLI 从当前 Codex 进程继承宿主通道和聊天身份；普通终端缺少这些信息时会明确拒绝。也可通过 `scripts/mcb.ps1 -Profile <配置名> -Action Call -Tool mcb_dialogue -ToolArguments ...` 调用。

## 通道与生命周期

普通输入复用 Codex App Tools 的 `tools/list`、`tools/call` 命名管道，使用 `list_threads` 和 `send_message_to_thread`。原生引导由 `dialogue_ipc.py` 连接正在运行的 `\\.\pipe\codex-ipc`，完成握手及 `thread-owner-discovery`，固定本机和当前聊天后调用 `thread-follower-steer-turn`。该原生请求不携带 `toolOutput`，避免桌面将它改走 `turn/start`。

适配器只发出握手、拥有者探测、只读设置探测和引导四类请求。不注册聊天拥有权，不订阅或复制聊天历史，不调用开始回合、修改设置或审批接口。玩家身份与内容始终附带来源，不表示桌面用户身份或系统指令。不启动第二个 Codex 或 app-server，不改桌面安装包、聊天数据库或全局配置，不调用系统键鼠。

MCP 侧只增加一个 `mcb_dialogue` 工具。中继是 MCB 的辅助进程，每次轮询完成后等待 1 秒；空队列不查询 Codex。模组复用已有 Forge 网络通道、身份校验、OP 校验和服务器 tick 队列。新服务器指令不需要 Mixin。

辅助进程由实例级 OS 锁防止重复启动，运行状态存放在本机 `LOCALAPPDATA/MinecraftCodexBridge/dialogue/`，不含游戏访问令牌或玩家文本。兼容后端热重载不会重新提交消息。完整 MCP 连接关闭可能由宿主清理整个进程组；这时服务器租约最多 60 秒后过期。客户端/服务器或 Codex 重启后，需要重新开启绑定；不会自动连接其他聊天/存档，也不会自动恢复未完成玩家任务。修改中继自身代码后用 stop/start 应用，无须重启 Codex。

## 边界

- 32 条待处理消息、128 条会话记录；每玩家每秒最多一条，每条最多 1024 字符。原版聊天输入框的 100 字符限制仍生效。普通消息最多等待一小时。
- 每页最多 4 条普通输入和 4 条引导，避免普通队列挡住引导，并限制 Forge 包体积。Unicode 内容和大小写保留。
- 提交前先记录 `dispatching`。宿主确认才记录 `delivered`；连接中断/超时记为 `unknown`，不自动重发。实际收到消息后的关联回复可以解除不确定状态。
- 当前 App Tools 不提供 "仅在预期空闲状态提交" 的原子参数。普通消息按最近一次宿主状态选择提交；如果桌面用户恰好同时启动新任务，宿主处理方式取决于自身的消息规则。同一批次的普通消息启动新任务，不会使原本空闲时收到的引导自动转向该新任务。
- 原生 IPC 的目标是桌面拥有者处理请求时的活动回合。`expectedTurnId` 由拥有者提供，不是 MCB 在较早轮询时固定的编号；不承诺锁定玩家发送瞬间的回合。原生请求不会在空闲时自动创建新回合。超时、断线、陌生错误或回执身份不符按不确定处理，不重试、不回退到普通发送入口。
- 宿主需在当前本机聊天列表（最近 50 条或置顶）中识别目标；接口不支持、聊天不可见或权限变化时明确停止，不挑选替代聊天。该本机通道属于已安装桌面版的集成协议，升级后需要重新验证。
- 原生 IPC 是桌面内部的版本化集成协议，不是公开稳定 API。分别记录实际桌面版与内置 app-server 版本，不能用 PATH 中另装的 CLI 版本代替。 当前只支持已验证的 Windows 通道，升级后应重新验证协议。
- 验收原生引导时，要证明消息在原活动回合结束前进入，并有匹配的原生 turnId 回执；仅“消息送达”不足以证明引导。集成服务器与独立服务器、不同玩家场景分别记录，不互相替代。

官方参考：[App Server 输入与引导](https://learn.chatgpt.com/docs/app-server)。本机引导经桌面 IPC 的拥有者调用该原生接口，未建立额外 app-server WebSocket。

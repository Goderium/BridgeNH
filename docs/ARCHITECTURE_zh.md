# BridgeNH 代码分类与边界

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE_zh.md)

本文件供人工阅读，Agent 读取英文主文件。BridgeNH 只暴露一个 MCP 服务：固定前端持有连接，私有 worker 执行业务代码，通过带认证的本机 HTTP 连接 Forge 模组。操作保持可组合；具体机器布局、配方及整条产线计划属于任务，不固化为专用工具。

## Python 包

以下路径相对 `mcp-server/src/minecraft_codex_bridge/`。

| 层 | 文件/目录 | 职责 |
| --- | --- | --- |
| 固定传输 | frontend.py、backend_supervisor.py、backend_transport.py | MCP 连接、不可变 worker 快照、验证替换及回退 |
| Worker/会话入口 | backend_worker.py、adapter_session.py、command.py、server.py | 不同传输的启动，复用同一 Adapter |
| 工具目录 | catalog/、tools.py、validation.py | 声明、公开顺序和参数校验 |
| 应用 | application/adapter.py、dispatch.py | 统一执行封装、明确路由、原生操作分派 |
| 控制边界 | application/control.py | 接管、能力、动作上下文和回执校验 |
| 读取边界 | application/reads.py | 有界条件等待、复合归属读取、冻结游标分页 |
| 呈现 | application/results.py、descriptions.py | MCP 结果/图像和工具/资源说明 |
| 领域处理器 | application/handlers/local.py、observations.py、tasks.py | 本地记录/生命周期、组合读取、组合动作 |
| 领域实现 | 下表列出的任务/规划模块 | 状态机、几何和领域验收 |
| 证据 | knowledge.py、operation_knowledge.py、run_log.py、player_recordings.py、call_timing.py、feedback.py | 历史证据、有界记录及诊断 |
| HTTP/连接 | bridge.py、runtime_discovery.py、operation_policy.py | 凭据、受校验 RPC、取消及本地/远程路由 |
| Prism 选择 | prism_instances.py、local_config.py、client_connections.py | 分离目录/实例名、兼容 profile、有界清单和显式绑定 |

Adapter.call_tool 负责校验计时、反馈和有界日志；内部调用先校验再分派，并一致处理失败。处理器不新建第二个 Adapter、连接或工作流锁。原生修改仍经过已有锁/控制边界，组合任务继承同一锁、期限和验收。

completed_result 只适用于成功等于 completed 的操作。AE 可续查订单保留不同的 pending 语义；Waila 部分结果、图像校验、GUI 超时和未知效果不能被公共结果函数抹平。server.py 保留兼容导入入口，新业务代码直接导入 application。固定前端不得导入工具目录、HTTP 客户端或应用包。

## 目录分类与扩展

catalog/common.py 保存 schema 构建器、共享标识、ToolSpec 和权限分类。领域声明分在 client/player/world/gui/nei/ae/qa，catalog/native.py 单独保存公开顺序。tools.py 按阶段组合原生工具、复合工具和序列 schema；等待/序列白名单只依赖既定原生目录，不能从后来全部复合工具自动推导，以免允许递归或未支持读取。旧导入保持同一对象。

新增或修改操作时：

1. 先选择已有原生操作或对应领域实现。
2. schema 放入对应目录领域；原生公开顺序明确写入 native.py。
3. Python 组合操作在 dispatch.py 登记一个处理器；operation=None 必须有处理器。
4. 复用控制/读取服务、同一锁、会话和期限；未知修改不得重试。
5. 验证参数、权限、完成证据及实际 SDK/worker 路径。

## 操作策略边界

OperationPolicies 持有有界的本地意图和存档/服务器选择；LocalJsonConfig 同时为策略和玩家权限提供乐观并发、原子持久化。OperationPolicyRules 统一分类认证 RPC；GameBridge.prepareOperation 在客户端所属线程、HTTP 分派前应用，包括组合原语。GameQueue.Control 拒绝旧策略代数。各原生适配保留距离、归属、实际授权及防重放检查。

本地 `/mcb policy` 与 MCP `mcb_policy` 都调用 bridge.policy。公开 Python schema 通过有界 policy_schema 移除执行器选择；私有工作流保留对应证明。Adapter 在组合前解析已保存的移动意图，原生导航在既有前置检查中解析。玩家组独立；远程 OP 观察使用会过期的实际样本，不永久信任握手。

## 领域实现

| 领域 | 模块 |
| --- | --- |
| 组合/证据 | workflows、task_reports、operation_checks、native_wait |
| 移动/交互 | interaction_approach、interaction_samples、navigation_gate、visit_planning、survival_posture |
| 建造 | construction_tasks、construction_tail、survival_checkpoints、placement_tasks、placement_attachment、structure_transform、assembly_plan |
| 背包/材料 | inventory_moves、whole_stack_transfer、material_sources、ae_inventory、provider_counts、nbt_order |
| 配方 | recipe_tasks、multi_recipe_tasks、fluid_recipe_tasks |
| AE | ae_tasks、ae_orders、ae_task_schema |
| QA | qa_tasks、qa_debug_tasks、qa_machine_flows、qa_flow_schema |

这些模块与传输分离，不为改导入名而无关移动。

## 热更新边界

应用变化替换私有 worker；固定前端、启动指引及依赖版本须匹配。工具/资源增删及 schema 变化随新 worker 提交，激活成功后发送 list_changed。宿主是否原生重新发现仍取决于宿主支持。订单/未知效果经过版本化 checkpoint 交接，不自动续做或重放。

旧进程内服务器还支持纯数据目录刷新。catalog/sources.py 定义固定依赖顺序，catalog_refresh.py 解释声明但不执行编辑后的源码；已加载辅助代码保持冻结。工具拓扑、权限或辅助代码变化被拒绝。共享目录和校验器缓存在同一动作锁内更新。

可执行变化检测覆盖新增/删除的嵌套 Python 模块，新模块须进入快照和 wheel。固定前端、依赖、启动指引变化需要 MCP 重连；兼容业务代码不要求重启 Codex。

## Forge 模组

Java 使用 `src/main/java/com/Goderium/BridgeNH/`，BridgeNH.java 为根包入口；类名 UpperCamelCase，职责子包小写。自有测试跟随领域包，第三方 API 样本保留其命名空间。

| 包 | 职责 |
| --- | --- |
| common.bridge | 分派、队列、请求校验、就绪及能力 |
| common.dialogue | 服务器指令与带来源玩家对话 |
| common 下 ae/inventory/machine/recipe/structure/world/qa | 共享领域适配及状态 |
| client 下 control/lifecycle/recording | 原生控制、菜单/世界生命周期、录制 |
| client 下 gui/nei/navigation/render | GUI、NEI、移动和帧缓冲读取 |
| config | 配置持久化、限制、策略 |
| loader、loader.core、loader.rfb | 客户端初始化、隔离的 Forge/RFB 钩子 |
| mixin | Mixin 配置引用的类 |
| network | 本机 HTTP、认证客户端/服务端网络 |
| util | JSON/NBT、固定 API、几何、诊断、生成的构建身份 |

核心加载钩子单独隔离。Transformer 排除不能覆盖整个 loader，否则 ClientBootstrap 也无法正常转换。GameBridge 持有客户端/服务端/渲染队列、身份和动作生命周期；ClientReadiness 只归约已观察输入，不自行读取游戏；BridgeCapabilities 按当前访问标志组织声明，不缓存就绪状态。

原生领域适配仍负责 GUI/NEI/AE、世界、导航、建造、录制及可选提供方；线程队列和权限由协调层持有。移包不能扩大可变状态访问或绕过侧别检查。

PersonalSpaceGuiAdapter 复用同一屏幕/控件身份和动作契约，读取有界公开控件树。原生控件处理输入，固定的私有下拉几何/搜索辅助仅服务原有弹出流程，不直接写维度模型。Forge draw-post 观察提供自然帧保存屏障，LogicalGuiPointer 提供受保护的悬停租约。NEI 逻辑指针还需要 CodeChicken 鼠标 getter 钩子；可选模组类不形成硬依赖，不额外增加 Mixin。

## 玩家对话

AgentDialogueCommand 注册服务器指令；AgentDialogueMailbox 在服务端线程持有有界消息、租约和私密回执。agent.dialogue 复用认证 Forge 传输与 OP 检查，玩家正文不会作为服务器指令或代码执行。客户端仅转发明确的对话/权限子命令。

公开回复先在同一权威邮箱领取消息标记，再经会话/连接校验的 chat.send 发包，结果未知也不重复发送；私密回执独立，提交不代表通过服务器聊天管理。

dialogue.py 管理实例级中继/OS 锁；dialogue_host.py 分别路由排队输入和 dialogue_ipc.py 原生引导；dialogue_log.py 保存有界 SQLite 元数据和回复映射。简洁输入显示昵称、模式、短引用和正文，完整身份/turnId 留本机日志。回复精确绑定聊天/会话/租约，不猜最后一条；映射保存失败禁止派发，后续日志失败不重发。

IPC 仅发现/探测当前本机拥有者及请求引导，由桌面拥有者提供 expectedTurnId。它不开始回合、不认领聊天、不订阅历史、不借用其他进程身份。中继区分排队、派发、送达、回复及未知。公开工具仍是 mcb_dialogue，详见[玩家对话](PLAYER_DIALOGUE_zh.md)。

## 验证

Python 使用项目虚拟环境，在 mcp-server 运行相关测试；架构测试覆盖分类目录、嵌套代码、处理器及前端隔离，worker 测试使用真实子进程和持续 MCP 会话，涵盖替换失败和未知动作。

Java 使用根目录 wrapper 的 compileJava/build；check/build 包含既有 bridgeSelfTest。模拟游戏自测不是实机验收。部署仅限明确授权的测试客户端，核对安装哈希并通过原生读取/操作验证；用户要求先审核时不部署。

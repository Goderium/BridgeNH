# 原生客户端控制缺口

[English](NATIVE_CONTROL_GAPS.md) | [简体中文](NATIVE_CONTROL_GAPS_zh.md)

## 待补齐能力

| 领域 | 当前限制 |
| --- | --- |
| 非活动窗口的鼠标抓取 | Windows 客户端最小化后可能仍占用鼠标。显式交接控制权时，读取 `releasedExistingGrab`，并核对键盘焦点是否仍在前台应用。 |
| NEI 书签 | 书签操作尚不支持创建优选配方树，也未覆盖所有组合手势。`mcb_gui_key` 仅发送字符，无法触发实体快捷键。 |
| 第三方 GUI 控件 | 部分模组界面仅公开部分控件。控件覆盖不完整时，未列出的控件状态为未知。 |
| 动作恢复 | `mcb_action_recovery` 核对已有回执的独立创造模式 ItemBlock 放置。生存物料工单按编号核对本单拾取和搬运。拾取记录或服务端搬运凭据缺失时保留待核对步骤。其他生存消耗仍需对应动作的证据。 |
| Prism 启动对话框 | 在 Windows 上，Prism 11.0.3 的对话框可能在 Java 启动前取得键盘焦点。`windowMode` 作用于游戏窗口。[Prism 命令行接口](https://github.com/PrismLauncher/PrismLauncher/blob/11.0.3/launcher/Application.cpp)没有屏蔽启动对话框的选项。 |

使用[工作区缺口模板](../mcp-server/src/minecraft_codex_bridge/guides/workspace_zh.md#原生控制缺口与必要反馈)记录缺少的原生操作、受影响环境和恢复步骤。动作结果未知时保留原操作 ID。原生操作在受影响环境中产生预期游戏状态后，即可关闭该条目。

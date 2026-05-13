# AutoTask · AI 开发文档（aidoc）

> 本目录是给 **AI 编程代理 / 后续维护者** 看的"项目速读手册"。
> 目标：让接手的 AI 在 **一次检索内** 就能回答"这个项目做什么、怎么跑、在哪改、怎么扩展"。
>
> 如需给最终用户看的说明，请读根目录的 `README.md`；本目录偏重 **实现层细节**、**模块关系**、**扩展点** 和 **调试切入点**。

## 开工前硬约束（**违反将造成架构债，不可越过**）

任何涉及"读屏 / 执行 UI 动作 / 跨进程能力 / Service / Bridge / Task / AIDL"的改动，**开工前 100% 必读**：

1. `02-architecture.md` §2「进程模型」+ §2.1「进程注解」
2. `05-services-and-ipc.md` 全部
3. `03-engine-applet-model.md`（如果要执行 / 序列化任务，必读）
4. `09-development-guide.md`（特别是 §9 AI 警告 / §7 修改 AIDL 兼容策略）
5. **grep `@Privileged` / `@Local` / `@Anywhere`**，确认你引用的每个方法在哪个进程能跑

完成后**在 todo / PR 描述里加一条"已对照 aidoc 02 §2 + 05 + 09"**才能动键盘。

历史案例：2026-05-09 给 AI agent 接屏幕感知能力时跳过这一步，直接在主进程调 `currentService.uiAutomatorBridge`，结果 Shizuku 模式触发 `ensurePrivilegedProcess()` 抛异常，整个 agent 链路对 Shizuku 用户根本不工作。复盘见 `16-ai-inspector-capability.md` §13.8。

## 使用指引（AI agent 快速上手）

1. 先看 `01-overview.md` 明确项目身份与功能边界。
2. 新任务落地前必读 `02-architecture.md`，确认该任务影响哪些模块 / 进程。
3. 涉及"执行逻辑"（跑任务、判定、动作）时，读 `03-engine-applet-model.md`。
4. 要"加一个新动作 / 新条件 / 新事件"时，优先读 `04-feature-catalog.md` + `09-development-guide.md`。
5. 涉及 **Shizuku 进程 / 辅助功能服务 / IPC** 时，读 `05-services-and-ipc.md`。
6. 涉及 **事件分发、资源管理、任务调度、快照与持久化** 时，读 `06-runtime-and-events.md`。
7. 涉及 **UI**（编辑器、选择器、悬浮检查器）时，读 `07-ui-architecture.md`。
8. 构建 / 签名 / 混淆 / 付费版功能：`08-build-config-premium.md`。
9. 排查已知坑点：`10-troubleshooting.md`。
10. 术语与简写：`11-glossary.md`。
11. 发版说明：`12-release-notes.md`。
12. 尚未完成的工程优化：`13-todo.md`。
13. AI 接入设计草案：`14-ai-integration.md`。
14. AI 接入工作纪要：`15-ai-working-notes.md`。
15. AI 屏幕感知与 Inspector 接入（第二阶段核心能力）：`16-ai-inspector-capability.md`。
16. AI agent 经验本（跨 session 长期记忆）设计：`20-experience-book-design.md`（暂未实现，仅设计）。

## 文件索引

| 文件 | 内容 |
| --- | --- |
| [`01-overview.md`](01-overview.md) | 项目概述、目标、特性清单、版本/依赖一览、代码规模 |
| [`02-architecture.md`](02-architecture.md) | 顶层架构图（Gradle 多模块）、进程模型、运行模式切换 |
| [`03-engine-applet-model.md`](03-engine-applet-model.md) | `tasker-engine`：Applet / Flow / XTask / Runtime / Serialization |
| [`04-feature-catalog.md`](04-feature-catalog.md) | **功能矩阵**：所有 Registry × Event / Criterion / Action / Flow / Value |
| [`05-services-and-ipc.md`](05-services-and-ipc.md) | ShizukuAutomatorService、A11yAutomatorService、AIDL、Bridges |
| [`06-runtime-and-events.md`](06-runtime-and-events.md) | MetaEventDispatcher、Resident / Oneshot 调度、Storage、Snapshots |
| [`07-ui-architecture.md`](07-ui-architecture.md) | UI 层级、Editor、Inspector、Selector、Showcase、对话框栈 |
| [`08-build-config-premium.md`](08-build-config-premium.md) | Gradle 配置、签名、ssl 原生库、Premium、AppCenter、AutoStart |
| [`09-development-guide.md`](09-development-guide.md) | **扩展手册**：加 Applet / Registry / Event / Bridge 的标准流程 |
| [`10-troubleshooting.md`](10-troubleshooting.md) | 已知坑点、常见 bug、排查入口 |
| [`11-glossary.md`](11-glossary.md) | 术语表 |
| [`12-release-notes.md`](12-release-notes.md) | 发版说明、版本重点与验证记录 |
| [`13-todo.md`](13-todo.md) | 尚未完成的工程优化待办 |
| [`14-ai-integration.md`](14-ai-integration.md) | AI 接入设计草案、架构原则、MVP 路线、第二阶段屏幕感知里程碑 |
| [`15-ai-working-notes.md`](15-ai-working-notes.md) | AI 接入沟通纪要、方案演进理由、下一步锚点（含屏幕感知决议） |
| [`16-ai-inspector-capability.md`](16-ai-inspector-capability.md) | AI 屏幕感知专项设计：复用 Floating Inspector 能力让 AI 看见控件、生成可执行 UI 操作 |
| [`17-inspector-vs-agent-pathway-audit.md`](17-inspector-vs-agent-pathway-audit.md) | inspector 与 agent 执行路径对照审计 |
| [`18-ai-agent-thinking-upgrade.md`](18-ai-agent-thinking-upgrade.md) | agent ReAct 三段思考升级与 stuck detection / 失败策略记忆设计 |
| [`19-feature-audit.md`](19-feature-audit.md) | agent 功能现状与已发现 bug 的根因 / 方案审计 |
| [`20-experience-book-design.md`](20-experience-book-design.md) | **agent 经验本（跨 session 长期记忆）设计**（未实现，仅设计） |

## 重要约定

- **文件路径**均以仓库根目录为起点（例 `app/src/main/java/...`）。
- **进程注解**：`@Privileged`（Shizuku 特权进程）、`@Local`（App 进程）、`@Anywhere`（两处共用，必须兼容）—— 修改 Service / Bridge / Task 代码时务必尊重注解。
- **Registry id** 占 `Applet.id` 高 16 位，**appletId** 占低 16 位；改动 id 几乎等于破坏向后兼容（见 `03`、`09`）。
- **DTO 版本**：`XTaskDTO` 当前版本码 16；低于 16 的任务会被迁移并改名为 `.pv16`（见 `06`）。
- **AIDL 变化即 IPC 契约变化**：App 进程与特权进程版本必须同步升级。

## 文档维护说明（给后续 AI）

- 改动 **Registry / Option 字段** ⇒ 同步更新 `04-feature-catalog.md`。
- 新增 **AIDL 方法** ⇒ 更新 `05-services-and-ipc.md` 的 IPC 表。
- 改动 **Applet id 映射 / 序列化格式** ⇒ 更新 `03-engine-applet-model.md` 与 `06-runtime-and-events.md` 的 Storage 章节。
- 引入 **新 Bridge / 新事件分发器** ⇒ 更新 `02-architecture.md` 的依赖图与 `06`。
- 引入 **新依赖库** ⇒ 更新 `08-build-config-premium.md`。
- 改动 **反馈 & 交流** 的邮箱、QQ群、邮件模板或菜单入口 ⇒ 更新 `07-ui-architecture.md` 的"关于页与反馈交流"章节。
- 改动 **语音指令** 的入口、AppKey / AccessKey / Token 配置、权限、识别服务、匹配策略或执行规则 ⇒ 更新 `07-ui-architecture.md` 的"语音指令入口"章节。
- 引入 **AI Provider / Agent / Intent / 任务草稿生成** ⇒ 更新 `14-ai-integration.md`；如果设计取舍、方案转向或工作顺序发生变化，同步更新 `15-ai-working-notes.md`；并同步 `01` / `02` / `07` / `08` / `09` 中受影响章节。
- 改动 **AI 屏幕感知 / Inspector 复用 / `AiUiSnapshot` / `AiUiTarget` / 节点压缩策略 / `AiCapability.InspectScreen` 等屏幕相关能力** ⇒ **必须** 同步 `16-ai-inspector-capability.md`；如该改动影响第二阶段 Phase 2.A/2.B/2.C 划分，同时回写 `13-todo.md` 与 `14-ai-integration.md` §7.1。
- 改动 **AI agent 经验本 / 长期记忆 / `ai/agent/experience/` 相关代码** ⇒ 同步 `20-experience-book-design.md`，落地后还要在 `13-todo.md` 把对应 phase 标记完成。
- 发布新版本或调整版本号 ⇒ 更新 `12-release-notes.md` 与 `01-overview.md` / `08-build-config-premium.md` 的版本信息。
- 发现 **新坑点 / 复现 bug** ⇒ 进 `10-troubleshooting.md` 积累。

保持每篇文档**自包含 + 互相交叉引用**，不要让 AI 反复跳读源码。

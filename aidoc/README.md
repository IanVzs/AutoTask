# AutoTask · AI 开发文档（aidoc）

> 本目录是给 **AI 编程代理 / 后续维护者** 看的"项目速读手册"。
> 目标：让接手的 AI 在 **一次检索内** 就能回答"这个项目做什么、怎么跑、在哪改、怎么扩展"。
>
> 如需给最终用户看的说明，请读根目录的 `README.md`；本目录偏重 **实现层细节**、**模块关系**、**扩展点** 和 **调试切入点**。

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
- 发现 **新坑点 / 复现 bug** ⇒ 进 `10-troubleshooting.md` 积累。

保持每篇文档**自包含 + 互相交叉引用**，不要让 AI 反复跳读源码。

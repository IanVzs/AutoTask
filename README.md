
# AutoTask · 自动任务 + AI Agent

一款支持 [Shizuku](https://github.com/RikkaApps/Shizuku) 和辅助功能的 **自动任务工具**，并内置 **AI Agent**：你只需用一句中文说出目标（"在抖音搜下饭综艺"、"给小红书私信回个赞"……），AI 会自己看屏幕、自己规划步骤、自己点按钮 / 输入文本 / 滑动列表，多步迭代直至完成或主动放弃；结果通过通知告知，执行心得自动沉淀进 **AI 经验本**，下次同类任务越用越聪明。

> 派生自 [xjunz/AutoTask](https://github.com/xjunz/AutoTask)（Apache-2.0）。本仓库由 [IanVzs](https://github.com/IanVzs) 在原项目之上加入 AI 接入第二阶段（agent loop / 经验本 / 一键转草稿 / 结果通知等，2026-05 起持续迭代）。

## 简介

本应用专注于帮助你执行自动任务，相比同类产品具有以下特点：

- 🧠 **AI Agent 自动多步执行**：用自然语言下达目标，AI 看屏幕做 ReAct 三段思考，复用现有 Applet 管道执行点击 / 输入 / 滑动 / 启动 App 等真实操作；每步可选用户介入。
- 📚 **AI 经验本（跨 session 长期记忆）**：每次会话**自动**沉淀 markdown 笔记 + 结构化 JSON；下次同类任务开局自动召回相关经验注入 prompt，让 AI 不再"健忘"。
- 🪄 **成功经验一键转任务草稿**：把 AI 跑通的多步序列翻译成可编辑 `XTask`，弹编辑器审核后保存为常驻 / 一次性任务 —— AI 探路一次，传统任务执行一辈子。
- 🔔 **结果通知**：agent 跑完独立通知告知成功 / 失败 / 中止 / 越权 / AI 错误；不打扰前台监听通知。
- 🔐 **隐私默认安全**：set_text 实际内容**永不写盘**；手机号 / 邮箱 / 身份证 / 卡号正则脱敏；经验本 `data_extraction_rules.xml` 排除 Auto Backup / D2D 迁移，仅本地。
- ⚡ **双运行模式**：**Shizuku** 特权进程（功能最全）/ **辅助功能** 模式（无 root）。
- 🛠 **传统规则树编辑器**：`Flow` 容器 + `Applet` 可视化拼任务；常驻 + 一次性 + 手势录制 + 节点检查器。
- ♻️ **不需要刻意保活便可常驻后台**（两种模式默认系统保活）。
- 🔋 **省电、占用低**（事件驱动 + Kotlin 协程，长任务不阻塞 CPU）。
- 🔓 **代码开源，安全可信**（Apache-2.0）。
- 🎨 Material 3 风格 UI。

---

## 🧠 AI Agent 能力详解

### 一句话定位

**"AutoTask 的 AI Agent 不是规则匹配的语音助手，而是真正会看屏幕、会反思、会试错、会越用越聪明的 Android 自动化代理。"**

### 端到端体验流

```text
你说：              "帮我在抖音搜下饭综艺"
        ↓
意图理解：          VoiceAiInterpreter → CreateTaskDraft 命中 agent
        ↓
任务级授权：        弹一次确认对话框（目标 / App / 步数 / 时长 / 能力）
        ↓
经验本召回：        AiAgentExperienceBook.recall(goal, targetApps)  ← 历史经验注入 prompt
        ↓
ReAct loop：       observation → last_action_review → reflection → action
                    ┃             ┃                   ┃            ┃
                    抓快照        反思上一步真做到没   推下一步方向  执行真实点击/输入/滑动
                    ↻ 最多 30 步 / 5 分钟，或 done / give_up / 越权立刻停
        ↓
独立通知告知结果：   ai_agent_outcome channel（横幅 + 声音）
        ↓
经验本自动沉淀：    `${filesDir}/ai_agent_experience/<ts>_<sid>.txt`（markdown + JSON 嵌块）
        ↓
（可选）一键转草稿：用户在 UI 点 "转任务草稿" → ExperienceToTaskConverter → FlowEditor
```

### 三模块全景

```
┌──────────────────────────┐    ┌────────────────────────┐    ┌──────────────────────────────┐
│ agent 执行模块             │    │ 经验本                  │    │ 草稿生成模块                   │
│ AiAgentSession           │ →  │ AiAgentExperienceBook  │ ←  │ ExperienceToTaskConverter    │
│ ReAct loop + UI 操作执行  │ 自动│ 纯数据层 (txt+JSON)      │ 用户│ step 序列 → XTask → FlowEditor │
└──────────────────────────┘ 沉淀 └────────────────────────┘ 触发 └──────────────────────────────┘
```

模块边界、单向数据流、为什么要这么设计等内部细节维护在 [`aidoc/20-experience-book-design.md`](aidoc/20-experience-book-design.md) §0。

### AI Agent 内置的"防胡来"机制

agent 不是裸跑大模型，专门加了一系列约束让它**别出格、别死循环、别选错节点**：

| 机制 | 说明 |
|---|---|
| **任务级授权** | 每个新会话弹一次对话框列出"目标 / App / 步数 / 时长 / 能力"，一键允许整段会话 |
| **App scope 锁** | 切到非授权 App 立即停止 (`OutOfScope`)；launcher / systemui 等过场包名豁免 |
| **步数 / 时长上限** | 默认 30 步 / 5 分钟，到顶即停 (`LimitExceeded`) |
| **未授权能力即停** | 每步执行前校验 `requiredCapability()`；不在授权范围内不静默跳过 |
| **每步决策面板** | 悬浮窗征询同意 / 拒绝 / 换一个，默认 3 秒倒计时自动同意；可改 `wait_for_user` / `disabled` |
| **ReAct 三段强制思考** | observation / last_action_review / reflection 在生成 action 之前**必填**，治"AI 不读现场 / 死循环不反思" |
| **Stuck Detection** | 连续 N 步无进展 → prompt 强制注入 STUCK 警示要求换方向；硬限 6 步直接 give_up |
| **Silent-fail 二次拉黑** | 步骤报 OK 但屏幕签名前后一致（节点选错 / 不响应）→ 第二次拉黑该 target，session 兜底拒收 |
| **失败策略长期记忆** | 同一 (pkg, activity, actionType, target 关键字段) 累积失败次数喂回 prompt 让 AI 看到"这条路已经堵了" |
| **隐私脱敏** | `set_text` 实际内容永不写盘；text/desc 跑 PII 正则；仅本地 `filesDir`，不上传 |

### 「人工智能」页 UI

底栏第 4 个 Tab，⭐ 闪光图标。包含：

1. **状态卡** — 监听状态 + 启停按钮
2. **AI 经验本卡** — 显示"已记录 N 条 · 占用 NN KB"，点击弹列表
3. **文字命令卡** — 输入框 + 发送（不必走语音）
4. **最近一次命令卡** + **执行记录列表**

经验本对话框：BottomSheet 列表 + 顶部"清空" + 每条"转任务草稿"+"查看完整记录" + 长按删除。详情对话框：可滚可复制展示原 txt 全文（markdown + JSON 嵌块），让你确认 AI 实际拿到的是什么。

### 设计文档与扩展指针

| 主题 | 文档 |
|---|---|
| AI 接入设计草案 | [`aidoc/14-ai-integration.md`](aidoc/14-ai-integration.md) |
| AI 接入工作纪要 / 决策演进 | [`aidoc/15-ai-working-notes.md`](aidoc/15-ai-working-notes.md) |
| AI 屏幕感知与 Inspector 复用 | [`aidoc/16-ai-inspector-capability.md`](aidoc/16-ai-inspector-capability.md) |
| inspector vs agent 路径审计 | [`aidoc/17-inspector-vs-agent-pathway-audit.md`](aidoc/17-inspector-vs-agent-pathway-audit.md) |
| ReAct 三段思考升级 | [`aidoc/18-ai-agent-thinking-upgrade.md`](aidoc/18-ai-agent-thinking-upgrade.md) |
| agent 功能现状 + bug 根因审计 | [`aidoc/19-feature-audit.md`](aidoc/19-feature-audit.md) |
| **AI 经验本设计与实现** | [`aidoc/20-experience-book-design.md`](aidoc/20-experience-book-design.md) |

aidoc 索引见 [`aidoc/README.md`](aidoc/README.md)。

---

## 截图

| 「人工智能」页 + AI 经验本入口卡 | AI Provider 设置（DeepSeek 等 OpenAI 兼容） | 任务列表（亮） | 任务编辑器 / Inspector（暗） |
|---|---|---|---|
| <img src="/app/screenshots/aipage.jpg" alt="人工智能页 + AI 经验本卡片" style="zoom:25%;" /> | <img src="/app/screenshots/aisetting.jpg" alt="AI Provider 设置" style="zoom:25%;" /> | <img src="/app/screenshots/Screenshot_light_1.png" alt="任务列表" style="zoom:25%;" /> | <img src="/app/screenshots/Screenshot_night_1.png" alt="任务编辑器 暗色" style="zoom:25%;" /> |

---

## 实现

### Shizuku 模式

利用 Shizuku 授予特权，使用安卓内置的 [UiAutomation](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/UiAutomation.java) 框架执行任务，详见 [ShizukuAutomatorService](app/src/main/java/top/xjunz/tasker/service/ShizukuAutomatorService.kt)。

> **注**：因为安卓系统只能注册一个 `UiAutomation` 服务，所以当自动任务服务激活时，其他 `UiAutomation`（如自动化测试、Thanox）会注册失败。如有需要请先停止自动任务服务，反之亦然。

### 辅助功能模式

使用辅助功能自带的 API 框架执行任务，详见 [A11yAutomatorService](app/src/main/java/top/xjunz/tasker/service/A11yAutomatorService.kt)。

### AI Agent loop（2026-05-09 落地，2026-05-13 加经验本）

- 入口：[`VoiceCommandService.runAgentFlow`](app/src/main/java/top/xjunz/tasker/voice/VoiceCommandService.kt)
- 核心循环：[`AiAgentSession.runLoop`](app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentSession.kt)
- 屏幕感知：[`ScreenSnapshotProvider`](app/src/main/java/top/xjunz/tasker/ai/agent/ScreenSnapshotProvider.kt) + [`AiNodeTreeCompactor`](app/src/main/java/top/xjunz/tasker/task/inspector/shared/AiNodeTreeCompactor.kt)
- prompt 构造 / LLM 调用：[`AiAgentPlanner`](app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentPlanner.kt)
- 单步动作执行（复用 Applet 管道）：[`AiAgentExecutor`](app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentExecutor.kt) + [`AgentActionDispatcher`](app/src/main/java/top/xjunz/tasker/service/AgentActionDispatcher.kt) + [`AiAgentTaskAssembler`](app/src/main/java/top/xjunz/tasker/task/inspector/shared/AiAgentTaskAssembler.kt)
- 经验本：[`ai/agent/experience/`](app/src/main/java/top/xjunz/tasker/ai/agent/experience/)（8 个文件）
- 决策面板：[`AiAgentOverlayController`](app/src/main/java/top/xjunz/tasker/ai/agent/overlay/AiAgentOverlayController.kt)
- 草稿翻译：[`ExperienceToTaskConverter`](app/src/main/java/top/xjunz/tasker/ai/agent/experience/ExperienceToTaskConverter.kt)

AI Provider 抽象为 OpenAI-compatible HTTP 接口（默认 DeepSeek），配置在「更多」→「AI 驱动」里设置 Base URL / API Key / 模型名 / 温度 / 超时等。**任何 AI Provider 配置都不内置在 APK 里**，请填自己的。

---

## 构建

构建环境要求：

- JDK 18。请通过 `JAVA_HOME`、IDE 或用户级 `~/.gradle/gradle.properties` 配置本机 JDK，不要把个人路径提交到仓库。
- Android SDK Platform 36、Build Tools 36.0.0。
- Gradle 由仓库内 wrapper 管理，直接使用 `./gradlew` 或 `make` 即可。

常用命令：

```bash
make help              # 查看所有命令
make debug             # 编译 debug APK
make install           # 编译 + adb 安装到当前设备
make run               # 启动 App
make logs              # 看当前进程 logcat
make test              # 跑 JVM 单元测试
make lint              # 跑 lint
```

如果 Android SDK 不在默认位置，可以在根目录创建未提交的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

签名配置仅在你需要使用自己的签名打包时填写，同样放在未提交的 `local.properties`：

```properties
storeFile=xxx
storePassword=xxx
keyAlias=xxx
keyPassword=xxx
```

多台设备同时连接时，`make install`、`make run` 等 adb 命令可以通过 `DEVICE=<adb 序列号>` 指定设备。

排障入口（包括 OEM 上 logcat 丢失等已知坑点）见 [`aidoc/10-troubleshooting.md`](aidoc/10-troubleshooting.md)。

---

## 注意事项

- 请在开源协议约束范围内使用本项目，**禁止用于非法用途**。
- AI Agent 跑高风险动作（支付 / 转账 / 删除 / 通话）前请务必审核每步动作；默认决策面板已开启倒计时确认。
- 经验本 `set_text` 实际内容已脱敏，但若你向 AI 发送的 user goal 本身含敏感信息（密码、私人地址等），仍会被记入经验本 markdown 段。请考虑措辞或在「人工智能」→ 经验本 → 长按删除。

## License

本项目基于 [xjunz/AutoTask](https://github.com/xjunz/AutoTask) 派生，遵循 [Apache-2.0 License](LICENSE) 开源。分发或修改时请保留原始版权与许可证声明。

*Original work Copyright 2023 xjunz*

*Modifications Copyright 2026 IanVzs and Contributors*

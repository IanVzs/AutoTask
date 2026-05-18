# 14 · AI 接入设计草案

本文件记录 AutoTask 正式接入 AI 驱动前的设计共识。目标不是把现有规则树推倒重来，也不是把 AI 限制成高级正则，而是让 AI 成为任务自动化体系里的新成员：能理解目标、规划动作、生成任务、解释运行结果，并在分级授权和可审计边界内调用现有执行能力。

沟通纪要、方案转向原因和下一步工作锚点见 `15-ai-working-notes.md`。**屏幕感知 / Inspector 接入这一关键能力**单独拆出，详见 `16-ai-inspector-capability.md`。

## 1. 当前基线

> **注**：本文档原本是 2026-05-08 的"AI 接入前"设计草案；后续大量内容已经实现。截至 2026-05-13 的实际状态：
> - **AI agent loop**（2026-05-09 落地）：`AiAgentSession` ReAct 三段思考、屏幕感知（`ScreenSnapshotProvider` + `AiNodeTreeCompactor`）、UI 操作执行（复用 Applet 管道）、决策面板、stuck 检测、失败策略记忆，已全部实现。
> - **AI 经验本 + 经验→草稿生成 三模块解耦**（2026-05-13 落地）：详见 `aidoc/20-experience-book-design.md` § 0。
> - **会话结果通知**（2026-05-13）：独立 channel `ai_agent_outcome`。
> - 「语音」Tab 已重塑为「人工智能」（图标 `ic_baseline_auto_awesome_24`）。
>
> 本节后面描述的"角色 / 成长路径 / 自治层级"等仍是当前架构的指导原则，但具体实现细节请以 `15` / `16` / `18` / `19` / `20` 为准。

截至 2026-05-08，项目已经完成以下准备（即"AI 接入前基线"）：

- 语音功能成为独立底部导航页，具备"识别文本 -> 解析命令 -> 匹配任务 -> 执行结果"的可观察状态流。
- Android 系统识别与阿里云 ASR 的选择逻辑已经抽象为 `AsrServiceType`，后续可以继续加入更多识别服务。
- 任务执行仍走现有 `XTask` / `Applet` / `AutomatorService` 管道，AI 不直接操作系统。
- 构建体验、CI、lint error、版权归属和文档入口已经收尾，适合作为 AI 接入前基线。

## 2. AI 在项目中的角色与成长路径

AI 不应该绕过 AutoTask 的规则树和权限体系，但也不应该只做“文本分类器”。合理形态是 **分级自治**：先辅助，再生成草稿，再在用户授权范围内代理执行，最终成为可托管的智能运行时。

建议定义六个角色：

1. **意图理解器**：把用户自然语言转换成结构化意图，例如“执行某个任务”“创建一个任务草稿”“解释失败原因”。
2. **任务编排助手**：根据意图生成 `Flow` / `Applet` 草稿，但默认只进入编辑器预览，不直接保存或执行。
3. **运行诊断助手**：读取识别文本、任务快照、错误日志和静态检查结果，用自然语言解释原因和修复建议。
4. **配置向导**：帮助用户配置 ASR、模型服务、隐私选项和能力开关。
5. **授权代理**：用户对某类低/中风险目标授予范围后，AI 可以在该范围内创建临时计划、请求必要权限并执行。
6. **智能运行时节点**：作为 Applet 体系中的判断或转换节点，在任务运行过程中读取受限上下文并输出结构化结果。
7. **屏幕感知者**（关键能力，详见 `16-ai-inspector-capability.md`）：复用 Floating Inspector 已有的"节点树识别 + 节点 → Applet 候选"管道，在用户授权范围内读取当前焦点窗口的可交互节点，生成针对节点的点击 / 输入 / 等待动作。**没有这一能力，AI 在 App 内自动化场景中的价值会大打折扣**，因此被列为第二阶段必须落地的核心能力。

自治层级建议：

| 层级 | 能力 | 默认交互 |
|------|------|----------|
| L0 建议 | 解释、诊断、给出下一步建议 | 直接展示，不修改状态 |
| L1 草稿 | 生成任务草稿、参数建议、修复建议 | 进入编辑器或确认页 |
| L2 确认执行 | 生成行动计划并请求执行 | 用户确认后执行 |
| L3 授权代理 | 在用户授予的范围内自动执行低/中风险动作 | 首次授权 + 审计记录 |
| L4 托管任务 | AI 参与持续观察、判断和复盘 | 仅用于明确创建的 AI 托管任务 |

## 3. 架构原则

AI 接入应遵守以下边界。这里的“边界”不是为了压低智能，而是为了让 AI 的行动空间可解释、可扩大、可撤销。

- **分级授权优先**：AI 生成任务、修改任务、执行动作前先经过风险分级；低风险可以在授权范围内自动执行，高风险必须确认。
- **结构化输出优先**：模型输出不直接拼接成代码或脚本，先解析成受控 DTO，再由本地代码转换为 `Applet`。
- **执行能力复用现有管道**：执行仍经 `LocalTaskManager`、`AutomatorService`、`Bridge` 和现有权限体系。
- **密钥由用户持有**：模型 API Key、ASR Key、Token 都保存在本机配置或用户自有服务端，不内置项目作者密钥。
- **云端最小化数据**：默认只上传完成当前请求所需文本；截图、节点树、任务列表、日志等敏感数据必须单独授权。
- **可观测可回退**：每次 AI 决策要能展示输入、输出、匹配结果和失败原因，方便用户纠错。

核心流水线：

```text
用户目标 / 语音 / 上下文
    -> AiIntent
    -> AiActionPlan
    -> RiskAssessment
    -> PermissionPolicy
    -> 用户确认 / 授权自动执行
    -> XTask / Applet / AutomatorService
    -> AuditLog / TaskSnapshot / 复盘
```

## 4. 推荐模块划分

第一阶段可以在 `app` 模块内新增 `ai/` 包，不急于拆独立 Gradle 模块：

- `ai/AiJson.kt`：AI DTO、授权和审计记录共用的 JSON 配置。
- `ai/model/`：结构化 DTO，例如 `AiIntent`、`AiTaskDraft`、`AiActionPlan`、`AiDiagnosis`。
- `ai/provider/`：模型服务抽象，例如 `AiProvider`、`OpenAiCompatibleProvider`、`LocalOnlyProvider`。
- `ai/prompt/`：Prompt 模板和上下文裁剪策略。
- `ai/agent/`：面向业务的编排层，例如 `VoiceAiInterpreter`、`TaskDraftAgent`、`SnapshotDiagnosisAgent`。
- `ai/policy/`：权限、风险和用户授权策略，例如 `AiRiskAssessor`、`AiPermissionPolicy`、`AiCapabilityGrant`。
- `ai/audit/`：AI 决策记录、执行记录和复盘入口，例如 `AiDecisionRecord`、`AiAuditStore`。
- `ui/ai/`：AI 设置页、对话页或任务草稿预览页。

如果后续 AI 逻辑变重，再考虑拆为 `ai-core` 或 `tasker-ai` library，避免第一阶段过度工程化。

## 5. 与现有框架的深度融合

AI 应该嵌入现有系统，而不是旁路执行。

| 现有框架 | AI 适配方式 |
|----------|-------------|
| `VoiceCommandService` | 作为自然语言入口和实时状态流，插入 `VoiceAiInterpreter`，把识别文本升级为 `AiIntent`。 |
| `VoiceCommandFragment` | 展示“理解中、计划、风险、确认、执行、复盘”等 AI 状态，不另造孤立入口。 |
| `XTask` / `RootFlow` / `Applet` | 作为 AI 行动计划的落地格式。AI 生成的是草稿或计划，本地转换器负责创建合法 Applet。 |
| `AppletOption` / Registry | 需要补充能力元数据，例如风险等级、所需权限、是否可由 AI 自动生成、是否需要每次确认。 |
| `FlowEditorDialog` | 作为 AI 草稿预览、人工修正和保存入口。AI 生成的任务必须能回到编辑器体系。 |
| `AutomatorService` | 仍是唯一执行端。AI 不直接点击、输入或执行 Shell，只提交经授权的现有任务/动作。 |
| `TaskSnapshot` | 作为 AI 诊断和复盘输入，让用户知道任务为什么成功、失败或被拒绝。 |
| `A11yAutomatorService.rootInActiveWindow` + `StableNodeInfo.freeze()` | **AI 屏幕感知的唯一数据来源**。AI 链路只读取节点树快照，不打开 Floating Inspector UI，不引用 `InspectorViewModel`，避免污染用户当前的 Inspector 状态。详见 `16-ai-inspector-capability.md`。 |
| `UiObjectFlowRegistry.containsUiObject` + `UiObjectCriterionRegistry` + `UiObjectActionRegistry` | AI 生成的"针对界面节点的动作"必须落到这套现成 Applet 组合上，与 `NodeInfoOverlay` / `AppletSelectorViewModel.acceptAppletsFromAutoClick` 走完全一致的执行管道，建议抽 `UiObjectFlowAssembler` 共用。 |
| `Preferences` / About 设置页 | 保存 Provider、模型、密钥、隐私和授权模式，不硬编码服务商；当前已提供 AI 开关、Base URL、API Key、模型名、温度、最大 tokens、超时和最低置信度配置入口。后续会新增"AI 屏幕感知"开关（默认关闭）。 |

### 5.1 Applet 能力元数据

为了让 AI 真正和现有 Applet 体系协作，需要给每个可生成/可执行能力加机器可读元数据：

- `riskLevel`：低 / 中 / 高 / 极高。
- `requiredPermissions`：麦克风、通知、悬浮窗、无障碍、Shizuku、网络、截图、文件等。
- `aiCreatable`：是否允许 AI 生成该 Applet。
- `aiAutoExecutable`：是否允许在授权范围内自动执行。
- `requiresPerRunConfirmation`：是否每次运行都必须确认。
- `sensitiveDataTypes`：可能读取或上传的数据类型。

第一阶段可以先用独立映射表维护，不急于改动 `AppletOption` 构造函数；稳定后再并入 DSL。

### 5.2 AI 行动计划

不要让模型直接输出 Applet 内部对象。建议中间层使用 `AiActionPlan`：

```text
AiIntent
  -> AiActionPlan(steps, requiredCapabilities, riskSummary)
  -> AiTaskDraft / ExistingTaskMatch / ClarificationQuestion
  -> 本地转换与确认
```

这样可以在计划层做解释、风险评估和权限申请，再决定是否进入 `FlowEditor` 或执行现有任务。

### 5.3 授权与审计

AI 的能力扩大后，必须有用户可理解的授权模型：

- 按能力授权：允许点击、输入、打开 App、读取节点树、调用网络模型等。
- 按范围授权：仅当前 App、指定 App、指定任务、指定时间段。
- 按风险授权：低风险自动执行，中风险首次确认，高风险每次确认，极高风险默认禁止。
- 按成本授权：每次请求前确认、每日 token/次数上限、仅 Wi-Fi、仅手动触发。
- 审计记录：保存用户目标、AI 计划、风险判断、用户选择、执行结果，支持在 UI 中回看。

## 6. 可选路线

### 路线 A：语音指令智能化

在现有 `VoiceCommandService` 后面增加 AI 理解层。识别文本先交给 AI 解析为结构化意图，再决定是匹配现有任务、询问澄清、生成任务草稿，还是提出一个需要授权的行动计划。

优点是入口自然、改动范围清晰、能复用现有语音页状态流。缺点是需要控制云端调用成本和响应延迟。

### 路线 B：AI 任务创建助手

新增“用一句话创建任务”能力。用户输入需求后，AI 生成 `AiActionPlan` 与 `AiTaskDraft`，App 本地把草稿转换成 `Flow` / `Applet` 并打开编辑器预览。

优点是价值感最强，可以把无代码编辑器升级成自然语言编辑器。缺点是必须严控 schema、兼容版本和危险动作确认。

### 路线 C：AI 运行诊断

基于 `TaskSnapshot`、静态检查错误、服务状态、权限状态生成解释和修复建议。第一阶段只读，不自动修改任务。

优点是风险低、适合快速落地，也能帮助后续开发调试。缺点是用户感知可能不如自动创建任务强。

### 路线 D：本地/云混合 Provider

先用 OpenAI-compatible HTTP 接口抽象，不绑定单一供应商。后续可接入本地模型、用户自建代理、商业模型或私有服务端。

优点是开放灵活，避免把项目锁死在某个云。缺点是设置页、错误处理、流式输出和限额管理要做扎实。

### 路线 E：AI 托管任务

新增一种“AI 托管任务”形态。它仍是 `XTask`，但某些判断节点由 AI 参与，例如识别弹窗类型、判断页面状态、从节点树中选择目标。用户显式创建这类任务后，AI 才能在任务运行期持续参与。

优点是生命力强，能把 AutoTask 从固定规则自动化推进到目标驱动自动化。缺点是需要更完整的授权、审计和失败回退。

## 7. 第一阶段 MVP

建议第一阶段做一个不失野心但可落地的闭环：

1. 增加 AI Provider 配置：Base URL、API Key、模型名、是否启用。（已开始）
2. 增加 `AiProvider` 抽象和一个 OpenAI-compatible 实现。（已开始）
3. 新增 `AiIntent`、`AiActionPlan`、`AiRiskAssessment`、`AiTaskDraft` 等 DTO。
4. 让语音页支持“AI 理解识别文本 / 手动输入文本”：输入自然语言，输出 `执行现有任务 / 创建任务草稿 / 需要授权的行动计划 / 无法理解 / 需要澄清`。（已开始）
5. 对“执行现有任务”沿用当前一次性任务管道，歧义时让用户选择。
6. 对“创建草稿”先支持低风险 Applet，例如打开 App、点击文本、等待、Toast、输入普通文本。
7. 对“需要授权的行动计划”展示风险、权限、成本和可撤销性，再让用户确认或拒绝。
8. 建立最小 AI 决策记录，让后续诊断和复盘有数据。

### 7.0 路由原则：代码匹配优先，AI 兜底（2026-05-08 起强制执行）

为了避免 AI 沦为"任何输入都先烧 token"的胶水层，语音/文本入口的处理顺序写死如下：

1. **代码匹配优先**：`VoiceCommandService.handleRecognizedText` 先调 `tryDirectTaskMatch(text)`：
   - 用原文整段在 `findTask` 里跑一次精确 + 模糊匹配（标准化大小写、去标点空白、双向 contains）。
   - 用 `VoiceCommandParser.parseRunTaskQuery(text)` 剥掉「执行/运行/启动/打开/开始」等前缀后再跑一次。
   - 任一候选**唯一命中**：直接 `launchTask`，写一条`voice_record_direct_match` 记录，**不调用 AI**，零 token 开销。
2. **AI 介入**：只有歧义命中（多个任务同名/相似）或全部 NotFound 时，才走 `runAiInterpretation`：
   - **必须**把 `TaskStorage.getAllTasks().map { it.title }` 传给 `VoiceAiInterpreter.interpret(text, knownTaskTitles)`。
   - prompt 里强制 AI 在 `RunExistingTask` 时把 `query` 严格设置为清单中的原始任务标题（保持空格/标点/大小写一致）。
   - 任务清单 > `MAX_TASK_TITLES_IN_PROMPT`（当前 60）时，按"标题字符与用户输入字符共现度"做一次轻量本地预筛，只把最相关的前 N 条塞 prompt。
3. **规则兜底**：AI 不可用 / 超时 / 欠费 / schema 校验失败时，退化到 `runRuleFallback` 跑现有 `VoiceCommandParser`，不影响离线场景。

`generateDraftWhenTaskMissing` 反向处理：它的语义是"现有任务确认没匹配上，AI 改去生成新任务"，**不**喂任务清单，避免 AI 又把意图掰回 RunExistingTask 形成死循环。

后续若加入"识别 → 嵌入向量 → ANN 搜索"做更鲁棒的本地匹配，应在第 1 步内完成，仍要保证"代码命中即直接执行，不调 AI"这条铁律。

## 7.1 第二阶段：屏幕感知与可执行 UI 操作（agent loop 形态）

第一阶段 MVP 让 AI 学会"理解 / 调度任务 / 生成低风险草稿"，但 AI 仍然看不到当前界面，无法生成"在 X 处点击 / 在 Y 输入框写字"这类真正贴近 App 内自动化的步骤。第二阶段在 2026-05-09 直接落地为 **agent loop 形态**，详见 `16-ai-inspector-capability.md` §13。

**为什么不再走 Phase 2.A/B/C 三段式**：planner-only（一次性出几步 Applet 草稿）的能力上限就是规则的低配版；真正能让用户感到"AI 不可替代"的是"读屏幕 → 决策 → 执行 → 看结果 → 再决策"的 agent 循环。三段式开发节奏会持续输出"看起来在做但用户不会用"的中间产物，浪费一两个开发周期。

**当前已落地（2026-05-09）**：

- 新增包 `app/src/main/java/top/xjunz/tasker/ai/agent/`：`AiUiSnapshot` / `ScreenSnapshotProvider` / `AiAgentAction` / `AiAgentExecutor` / `AiAgentPlanner` / `AiAgentSession` / `AiTaskScope` 七个文件。
- `Preferences` 加 `aiAgentEnabled`（默认 **true**）/ `aiAgentMaxSteps` (30) / `aiAgentMaxSeconds` (90)。
- `VoiceCommandService.runAiInterpretation` 在 `CreateTaskDraft` 分支启动 `runAgentFlow`：`planSession → 等用户授权 → 启 AiAgentSession → 每步追加到 records`。
- `VoiceCommandFragment` 弹任务级授权对话框，AboutFragment AI 配置弹窗加 agent 开关。

**仅四条不可妥协边界**（取代旧的"严格 redact / 节点上限 / 多重门禁"）：

1. 任务级授权，一次确认整段会话。
2. App scope 锁：切到非授权 App 自动停。
3. 步数 / 时长上限：默认 30 步 / 90 秒。
4. 未授权能力即停：每步执行前校验 `requiredCapability()`。

**仍保留的工程纪律**：

- AI 链路不允许实例化 `FloatingInspector` / `InspectorViewModel`；只通过 `A11yAutomatorService.rootInActiveWindow + StableNodeInfo.freeze()` 取数据。
- AI 输出的节点引用一律是 `AiUiTarget` 定位条件，由 `AiAgentExecutor` 在最新真实 AccessibilityNodeInfo 树里二次定位；**不允许** AI 直接操作 bounds 坐标点击。
- 节点树进入模型前经过 `AiNodeTreeCompactor` 裁剪（默认 80 节点 / 80 字符上限），但具体 redact 规则等运行体验稳定后再加。

**2026-05-09 增量里程碑**（详见 `16-ai-inspector-capability.md` §13.7）：

- **公共能力抽取 `task/inspector/shared/`**：`UiTreeQuery` / `NodeCriteriaExtractor` / `NodeToActionAssembler` / `AiUiTargetExtractor` 四个文件，让 inspector + agent 共享同一份"节点 → Applet / 节点 → AiUiTarget"逻辑。`NodeToActionAssembler` 也被 `ExperienceToTaskConverter` 复用——把经验本里成功会话的 step 序列翻成可编辑 XTask 草稿（见 `aidoc/13-todo.md` 2.1 与 `aidoc/20-experience-book-design.md`）。
- **决策面板 `ai/agent/overlay/`**：每步 agent action 在执行前通过悬浮窗征询用户**同意 / 拒绝 / 换一个**，倒计时默认同意；用户介入写入 history 喂给下一轮 AI 做自我学习。
- 用户可见的边界从"任务级一次性授权"加强为"每步可干预 + 任务级授权"，AI 自由度未变（默认 3 秒倒计时同意），但风险从"开闸放水"降到"协作闯关"。

## 8. 风险清单

- **隐私**：语音文本、任务名、节点树、日志都可能含个人信息。默认不要上传截图、节点树和完整日志。
- **安全**：AI 不能直接生成 Shell、Intent、文件删除、支付相关动作并自动执行。
- **成本**：语音连续监听会产生高频请求。应加去抖、手动确认、请求频率限制和 token 估算。
- **可靠性**：模型输出必须过 schema 校验，失败时回退到现有规则匹配。
- **延迟**：语音交互需要流畅反馈，UI 应展示“理解中 / 需要确认 / 已回退”。
- **兼容**：AI 生成的任务需要遵守 `XTaskDTO` 版本、Applet id 和序列化兼容规则。
- **越权**：长期授权必须可查看、可暂停、可撤销，不能隐藏在设置深处。
- **审计缺失**：AI 自动执行后如果没有记录，用户无法建立信任，也无法排查误操作。

## 9. 文档同步要求

接入 AI 后，需要同步维护：

- `01-overview.md`：产品定位和核心特性。
- `02-architecture.md`：新增 AI 模块和数据流。
- `07-ui-architecture.md`：AI 页面、语音页 AI 状态和配置入口。
- `08-build-config-premium.md`：新增依赖、网络配置、密钥配置方式。
- `09-development-guide.md`：新增 AI Provider / Agent / Intent 的扩展步骤。
- `10-troubleshooting.md`：模型请求失败、schema 解析失败、权限不足、`a11y_disabled` / `ui_target_not_found` / `screen_snapshot_too_large` 等排障入口。
- `13-todo.md`：拆分未完成的 AI 里程碑（包含第二阶段屏幕感知子任务）。
- `16-ai-inspector-capability.md`：屏幕感知 / Inspector 接入的专项设计，第二阶段任何代码或 capability 改动都要回写。

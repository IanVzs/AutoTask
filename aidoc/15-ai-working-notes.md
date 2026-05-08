# 15 · AI 接入工作纪要

本文件记录 2026-05-08 开始正式讨论 AutoTask 引入 AI 驱动时形成的沟通纪要、方案演进原因和下一步工作锚点。它不是最终架构规范；正式设计以 `14-ai-integration.md` 为准。本文件用于帮助后续开发者和 AI agent 快速恢复上下文，避免重新走弯路。

## 1. 讨论背景

当前项目已经完成 2.0 语音控制中心、构建体验优化、CI、lint error 修复、版权归属修正和 aidoc 文档整理。用户明确提出：

- AutoTask 将正式进入 AI 驱动阶段。
- AI 不是边缘增强，而是下一阶段“新成员”。
- 需要先收尾现有成果，再进行头脑风暴和架构设计。
- 设计必须优雅处理权限、智能和用户体验之间的平衡。
- AI 必须和现有框架深度融合，而不是另起一套旁路执行系统。

## 2. 初始方案

最初方案偏保守，核心想法是：

- 先从现有语音页切入。
- 在 `VoiceCommandService` / `VoiceCommandParser` 后加入 AI 意图理解。
- 第一阶段只让 AI 输出 `执行任务 / 创建草稿 / 需要澄清 / 无法理解`。
- 对执行任务只匹配现有一次性任务。
- 对创建草稿只生成低风险 Applet。
- 所有执行和保存都需要用户确认。

这个方案的优点是风险小、实现路径清晰、能复用现有语音状态流和 `XTask` 执行管道。

## 3. 用户反馈与关键转向

用户指出：如果把 AI 限制得太狠，AI 的作用会太小。好的 App 应该是 **权限、智能和体验的权衡艺术**。

这个反馈改变了设计重心：

- AI 不应该只是“高级正则”或“文本分类器”。
- 设计目标不是限制 AI 做事，而是让 AI 的能力可以被策略系统管理。
- AI 应该拥有逐步扩大的行动空间，但每一层空间都要有边界、过程可见、结果可追溯。
- AutoTask 应该从固定规则自动化，逐渐演进到目标驱动自动化。

因此，正式设计从“保守 MVP”升级为 **分级自治 + 授权策略 + 行动计划 + 审计记录 + Applet 元数据适配**。

## 4. 当前设计共识

### 4.1 AI 的定位

AI 是现有自动化体系的智能协作者和逐步成长的代理，不是绕过权限和规则树的超级执行器。

AI 应承担六类角色：

1. 意图理解器。
2. 任务编排助手。
3. 运行诊断助手。
4. 配置向导。
5. 授权代理。
6. 智能运行时节点。

### 4.2 分级自治

AI 能力分为 L0-L4：

| 层级 | 能力 | 默认交互 |
|------|------|----------|
| L0 建议 | 解释、诊断、给出下一步建议 | 直接展示 |
| L1 草稿 | 生成任务草稿、参数建议、修复建议 | 进入编辑器或确认页 |
| L2 确认执行 | 生成行动计划并请求执行 | 用户确认后执行 |
| L3 授权代理 | 在用户授权范围内自动执行低/中风险动作 | 首次授权 + 审计记录 |
| L4 托管任务 | AI 参与持续观察、判断和复盘 | 仅用于明确创建的 AI 托管任务 |

### 4.3 核心流水线

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

### 4.4 授权与决策记录

讨论中形成的判断：

- 授权是长期规则，回答“AI 被允许做什么”。
- 决策记录是每次行为的账本，回答“AI 这次为什么这样做、做了什么、结果怎样”。
- 授权不应是单个 `allowAi = true`，而应拆成能力、范围、风险、时间、成本和确认策略。
- 决策记录必须记录用户目标、AI 意图、行动计划、风险评估、命中的授权、用户选择和执行结果。
- 高风险和长期授权必须可查看、暂停和撤销。

建议的核心对象：

- `AiCapabilityGrant`
- `AiCapability`
- `AiRiskLevel`
- `AiDecisionRecord`
- `AiActionGate`
- `AiAuditStore`

### 4.5 用户交互原则

用户交互不应让 AI 变成打断式弹窗机器，而是按风险分层：

- 低风险：直接执行或轻提示，并记录。
- 中风险：首次确认，可选择记住同类授权。
- 高风险：每次确认，清楚展示读取什么、影响什么。
- 极高风险：默认拒绝；高级模式下也要逐次确认和强审计。

核心交互卡片：

- 理解卡片：展示 AI 如何理解用户目标。
- 行动计划卡片：展示 AI 准备做什么、需要哪些能力、风险等级。
- 授权弹窗：设置能力、范围、时长和确认策略。
- AI 任务草稿预览：进入现有 `FlowEditor` 体系，可试运行、保存、编辑或放弃。
- AI 决策记录页：展示每次 AI 行为的目标、计划、风险、授权和结果。

## 5. 与现有框架的协调方向

AI 接入不是新增孤立功能，而是改造现有框架以适应 AI 协作：

- `VoiceCommandService`：自然语言入口和实时状态流，插入 `VoiceAiInterpreter`。
- `VoiceCommandFragment`：展示理解、计划、风险、确认、执行、复盘等状态。
- `XTask` / `RootFlow` / `Applet`：作为 AI 行动计划落地格式。
- `AppletOption` / Registry：补充能力元数据，标注风险、权限、是否可由 AI 生成和自动执行。
- `FlowEditorDialog`：承接 AI 任务草稿预览、人工修正和保存。
- `AutomatorService`：仍是唯一执行端，AI 不直接点击、输入或执行 Shell。
- `TaskSnapshot`：作为 AI 运行诊断和复盘输入。
- `InspectorViewModel` / 节点树：可作为 AI 视觉/界面理解上下文，但需要单独授权和裁剪。
- `Preferences` / About 设置页：保存 Provider、模型、密钥、隐私和授权模式。

## 6. 方案变更理由

从保守方案转向分级自治方案，原因如下：

- 只做任务名匹配会浪费 AI 的核心价值。
- AutoTask 的现有规则树、Applet 和服务体系已经足够强，AI 应该成为它们的智能入口和编排层。
- 真正的风险不是 AI 能力强，而是能力没有策略、授权和审计。
- 分级自治可以同时满足智能体验和安全边界：低风险少打扰，高风险强确认。
- 决策记录能建立用户信任，也能为后续调试、复盘和模型优化提供依据。

## 7. 下一步工作锚点

下一步不要直接写网络请求或 Prompt。建议先落基础骨架：

1. 定义 `AiIntent`、`AiActionPlan`、`AiRiskAssessment`、`AiTaskDraft`。（已开始）
2. 定义 `AiCapability`、`AiCapabilityGrant`、`AiPermissionPolicy`。（已开始）
3. 定义 `AiDecisionRecord`、`AiAuditStore`。（已开始）
4. 定义 `AiActionGate.review(plan)`，让所有 AI 行动先过统一门禁。（已开始）
5. 为现有低风险 Applet 建立第一版能力元数据映射。
6. 接入 `AiProvider` 和 OpenAI-compatible 请求实现。（已开始）
7. 在语音页接入 AI 理解链路和手动文本输入入口。（已开始）
8. 在语音页接入“理解卡片”和“行动计划卡片”UI 草稿。

最重要的顺序：**先建结构化模型和授权门禁，再接模型服务**。

## 8. 当前未决问题

- 第一版 AI 设置入口放在关于页，还是新增专门的 AI 设置页？
- 授权记录第一版用 JSON 文件还是 SharedPreferences？
- 决策记录第一版保留最近多少条？
- 哪些 Applet 第一批标记为 `aiCreatable`？
- 是否需要给 AI 托管任务新增任务类型，还是先用普通 `XTask` + 元数据标记？
- 是否使用 Android `EncryptedSharedPreferences` 保存模型 API Key？
- 决策记录是否允许导出用于问题反馈？
- AI Provider 参数是否需要继续增加预设模板（当前默认 DeepSeek；后续可加 OpenAI、本地 Ollama、OpenRouter、其他兼容服务）？

## 9. 给下一轮实现的提醒

- 新建代码文件必须使用当前年份的 `Copyright (c) <year> IanVzs. All rights reserved.`。
- 不要把 AI API Key、模型供应商密钥或作者账号写入源码。
- 不要让 AI 直接调用 `AutomatorService` 或 Bridge；必须经过行动计划、风险评估和授权门禁。
- 不要让模型输出自由格式脚本；必须输出受控 DTO。
- 文档更新至少同步 `14-ai-integration.md`、`13-todo.md` 和受影响的架构/UI/构建文档。

## 10. 屏幕感知 / Inspector 接入专项纪要（2026-05-08）

第一阶段闭环（语音/文本理解 → 草稿 → 可执行节点 → 决策记录）跑通后，用户立即指出了下一阶段的关键缺口：

> **用户原话**：
> "涉及到 app 内操作 我希望 ai 可以使用悬浮助手识别控件的能力 这点你记录一下 这个能力至关重要 如果没有这个能力那 AI 就是形同虚设。"

### 10.1 共识

- 第一阶段 AI 实际能干的事局限于"打开 App / 等待 / Toast"；如果不让 AI 看见屏幕、定位控件，"AI 驱动"和"语音任务调度器"没有本质区别。
- AutoTask 自带的 Floating Inspector 已经把"读取节点树 + 选中节点 → 生成 Criterion Applet 候选"的最难部分做完了（核心入口：`A11yAutomatorService.rootInActiveWindow` → `StableNodeInfo.freeze()` → `NodeInfoOverlay.collectProperties()` → `EVENT_NODE_INFO_SELECTED` → `AppletSelectorViewModel.acceptApplets*`）。
- 因此第二阶段的策略是 **"复用，不新造"**：AI 只需要把"人类用手指点节点"这一步换成"模型用结构化定位条件 `AiUiTarget` 选节点"，剩下的都走现有 Applet / FlowEditor / AutomatorService 管道。

### 10.2 不可妥协的边界

- AI 链路**不**实例化 `FloatingInspector` 或写 `InspectorViewModel`，避免污染用户当前 Inspector 状态；只读 `rootInActiveWindow + freeze()` 这一最小数据源。
- 节点树进入模型前必须经过裁剪（节点上限、文本截断、敏感字段 redact）；密码 / 银行卡 / CVV 等节点禁止上传 text。
- AI 输出的节点引用一律是"定位条件 `AiUiTarget`"，由本地代码在执行前再到真实节点树里二次定位；模型不允许直接操作 bounds 坐标点击。
- `InspectScreen` 是分水岭权限，必须默认拒绝；首次启用要弹出解释卡片，明确告知会发送哪些字段。

### 10.3 已写入设计

- 新建 `16-ai-inspector-capability.md` 作为本能力的专项设计文档，覆盖入口地图、数据结构、压缩策略、转换链路、UI 与授权、风险、Phase 2.A / 2.B / 2.C 三步走路线、待决问题。
- `14-ai-integration.md` 在角色清单加入"屏幕感知者"，在框架适配表里把 `A11yAutomatorService.rootInActiveWindow + StableNodeInfo.freeze()` 与 `UiObjectFlowRegistry / Criterion / Action` 列为 AI 屏幕感知唯一接入点；新增 §7.1 第二阶段章节。
- `13-todo.md` 在 AI 第一阶段 MVP 之后拆出 `AI 第二阶段：屏幕感知与可执行 UI 操作` 高优先级条目。

### 10.4 仍需在开工前讨论

详见 `16-ai-inspector-capability.md` §11，最关键的几条：

- 节点压缩硬上限是否要按 App 类别做差异化默认值。
- 是否提供"完全禁止上传 text"的隐私模式开关。
- 决策记录里保留快照子集的容量与现有 `AiAuditStore` 是否分仓。
- 后续若加截图能力，应该走 A11y `takeScreenshot` 还是 Shizuku UiAutomation 的 `takeScreenshot()`。

## 11. 路由优先级与 AI prompt 任务清单（2026-05-08）

### 11.1 现状暴露的两个问题

第一阶段闭环跑通后，使用中暴露的体验问题：

> **用户原话**：
> "把代码匹配的优先级和重要程度都提高吧 如果可以使用代码匹配到已有任务 那么就直接执行 而不用浪费 AI token。每次给 AI 喂语料的时候有把当前有的任务喂给 ai 吗？怎么感觉很简单的匹配都匹配不到呢？"

代码侧确认两条都是真问题：

1. `VoiceCommandService.handleRecognizedText` 之前的实现是 `runAiInterpretation(text) ?: runRuleFallback(text)`：**AI 优先、规则兜底**。哪怕输入是"打开微信"且本地已有同名任务，也会先烧 token 调 AI。
2. `VoiceAiInterpreter.buildPrompt` 之前**只**注入 capability 清单，**完全没有**把当前任务标题列表喂给 AI——AI 在凭空猜一个 query 字符串，App 端再用这个猜出来的字符串去 `findTask` 模糊匹配，匹配不上的概率自然很高。

### 11.2 路由原则改为"代码匹配优先 + AI 拿任务清单兜底"

正式落地的处理顺序：

1. **代码直接匹配（零 token）**：`tryDirectTaskMatch(text)` 用原文整段 + `VoiceCommandParser.parseRunTaskQuery` 剥前缀后的 query，在 `findTask`（已有的精确 + 标准化模糊匹配）里跑两次，任一**唯一命中**直接 `launchTask` 并 return。
2. **AI 介入并附带任务清单**：歧义或全部 NotFound 时才调 `VoiceAiInterpreter.interpret(text, knownTaskTitles = TaskStorage.getAllTasks().map { it.title })`。prompt 强制 AI 把 query 严格等于清单里的原始任务标题。
3. **规则兜底**：AI 不可用 / 超时 / schema 失败时仍走 `runRuleFallback`，保证离线场景不失能。

### 11.3 设计取舍

- 任务清单超过 `MAX_TASK_TITLES_IN_PROMPT`（当前 60）时按"字符共现度"做一次轻量本地预筛，避免长清单烧 token；不引入分词 / embedding，把这一项推到后续再做。
- 歧义不直接报错，而是让 AI 用清单消歧——这正是 AI 在路由层最有价值的位置。
- `generateDraftWhenTaskMissing`（任务找不到时让 AI 生成新草稿）**反向处理**：不喂任务清单，否则 AI 容易又把意图掰回 RunExistingTask 形成死循环。
- 新增 `voice_record_direct_match` / `format_voice_command_direct_match` 两条 strings，让用户在记录卡片里能直观看到"这条没烧 token"。

### 11.4 同步更新

- `app/src/main/java/top/xjunz/tasker/voice/VoiceCommandService.kt`：新增 `tryDirectTaskMatch`，`runAiInterpretation` 提取并传入任务清单。
- `app/src/main/java/top/xjunz/tasker/ai/agent/VoiceAiInterpreter.kt`：`interpret` 新签名 `(text, knownTaskTitles)`；新增 `buildKnownTaskSection` + `rankByRelevance`；`buildPrompt` 强化"必须严格等于清单原始任务标题"约束。
- `app/src/main/res/values/strings.xml`：新增两条直接匹配相关字符串。
- `aidoc/14-ai-integration.md` §7.0：把这条路由原则写成正式条款。
- `aidoc/13-todo.md` 暂不需要新增条目，本次属于"第一阶段 MVP 已落地能力的关键改进"，不是新里程碑。

# 13 · 待办

本文件只记录尚未完成的后续优化项；已完成的构建体验、CI、lint error 修复和低风险 warning 清理不再列入。

> **开工前硬约束**（来自 README.md）：碰任何 service / bridge / task / AIDL / 跨进程的事，
> **必先**读 `02 §2` + `05` + `09`，并在 todo 里加一条"已对照 aidoc"。这条没做就动手，
> 大概率重蹈 2026-05-09 复盘里的覆辙（`aidoc/16-ai-inspector-capability.md` §13.8）。

## 高优先级

1. **AI 接入第一阶段 MVP**
   - 范围：`app/src/main/java/top/xjunz/tasker/ai/`、`voice/`、`ui/voice/`、`Preferences.kt`、`res/values/strings.xml`。
   - 目标：建立 AI Provider 抽象和 OpenAI-compatible 配置，让语音识别文本进入 AI 意图理解，输出"执行现有任务 / 创建任务草稿 / 需要授权的行动计划 / 需要澄清 / 无法理解"等受控结果。

2. **AI 第二阶段：屏幕感知与可执行 UI 操作**（**已转向 agent loop 形态，2026-05-09 一次性落地**）
   - 范围（已落地）：`app/src/main/java/top/xjunz/tasker/ai/agent/` 新增 7 个文件（`AiUiSnapshot` / `ScreenSnapshotProvider` / `AiAgentAction` / `AiAgentExecutor` / `AiAgentPlanner` / `AiAgentSession` / `AiTaskScope`），`Preferences` 加 agent 三项配置，`VoiceCommandService.runAgentFlow`，`VoiceCommandFragment` 任务级授权对话框，`AboutFragment` AI 配置加 agent 开关。
   - 设计依据：`16-ai-inspector-capability.md` §13、`14-ai-integration.md` §7.1、`15-ai-working-notes.md` §12。
   - **已不再适用**的旧子任务：原本计划的 Phase 2.A 只读 → 2.B 可执行 → 2.C 写入兜底三段式被合并成一次性 agent loop 闭环，不再分批落地。
   - **后续 follow-up 子任务**（明天起按需推进）：
     - **2.1 经验本驱动的「成功经验 → 任务草稿」（已实现，2026-05-13）**：用户决策两次反复，最终方案是 agent 仍**独立运行 + 通知汇报结果**（任务结束后弹 `ai_agent_outcome` 通知，每次跑完即丢，**不**自动落 XTask），但同时引入**经验本** (`ai/agent/experience/`) 沉淀每次执行心得（自然语言 markdown + 结构化 JSON 嵌块写到 `${filesDir}/ai_agent_experience/`），下一次类似任务开局会按 (用户 goal, 当前 App) 召回 top-N 注入 prompt 让 AI 越用越聪明；其中**成功经验**（outcome=Completed）支持用户在「人工智能」页 → 经验本对话框里**主动**点"转任务草稿"按钮，把多步 step 序列翻译成 RootFlow + Do + click/long_click/set_text/launch_app/global_back/home 序列后弹 `FlowEditorDialog` 让用户审核保存。详细设计与实现见 `aidoc/20-experience-book-design.md`。
     - **2.2 包名校验与用户编辑**：`runAgentFlow` 拿到 `plan.targetAppPackage` 后用 `PackageManagerBridge.loadPackageInfo` 校验存在性；任务级授权对话框允许用户手改包名 / 加多个包。
     - **2.3 中止运行中 session 的 UI 入口**：在记录卡片或浮动 toast 里加"停止 agent"按钮，调用 `coroutineScope.cancel()` 或新增 `AiAgentSession.cancel()`。
     - **2.4 节点压缩 redact**：把 `viewId` 命中 password / cvv / bank / phone / id_card 等关键词的节点的 `text` 强制 `redacted=true`，不上传明文。
     - **2.5 高风险 action 黑名单**：在 `AiAgentExecutor` 里对命中支付 / 转账 / 删除 / 通话相关关键词的目标节点做运行时拒绝。
     - **2.6 prompt 优化**：观察实跑 token 消耗，按需做"只发 diff 节点 / 仅发可点击 + editable / 历史压缩为摘要"等节流。
     - **2.7 V2 节点 picker：FloatingInspector 接管"换一个"按钮**：当前 `CandidateListPicker` (V1) 在决策面板内嵌候选列表；V2 把 `InspectorPickerStub` 替换成 `FloatingInspectorPicker`，弹起 inspector 让用户在屏幕上**直接点真节点**。`AiAgentNodePicker` 接口已经预留好，决策面板 UI / `AiAgentDecision` / session 集成都不需要改。
     - **2.8 agent 决策配置 UI**：`Preferences.aiAgentConfirmMode/Seconds/AllowReplace` 已有默认值（`auto_approve / 3 秒 / true`），覆盖常见场景；下一轮在 AI 配置弹窗加一个 "AI agent 决策" 子区块，含 4 种模式 spinner / 倒计时秒数 EditText / allowReplace CheckBox，让用户可见地控制行为。
   - 不可妥协边界（已写入代码 + 文档）：AI 链路不实例化 `FloatingInspector` / 不写 `InspectorViewModel`；节点引用一律是定位条件 `AiUiTarget` 由本地二次定位，禁止直接用 bounds 坐标点击；任务级授权 + App scope + 步数 / 时长 / 能力四条边界由 `AiAgentSession` 强制执行。

3. **AI 行动计划与分级授权**
   - 范围：未来 `AiIntent`、`AiActionPlan`、`AiRiskAssessment`、`AiPermissionPolicy`、`AiDecisionRecord`。
   - 目标：把 AI 能力设计成分级自治：建议、草稿、确认执行、授权代理、托管任务。不同风险等级对应不同确认、授权和审计策略。

4. **Applet 能力元数据适配**
   - 范围：`AppletOption` / Registry、未来独立 AI capability 映射表。
   - 目标：为 AI 可生成/可自动执行能力补充风险等级、所需权限、敏感数据类型、是否需要每次确认等元数据，让 AI 深度复用现有 Applet 体系。

5. **AI 输出安全边界**
   - 范围：`tasker-engine` 的 `XTask` / `Applet` 模型、`app/task/applet/option/registry/`、未来 `AiTaskDraft` DTO。
   - 目标：AI 不能绕过现有执行管道；生成任务必须经 schema 校验、风险分级和用户确认。高风险动作必须每次确认，长期授权必须可查看、暂停和撤销。

6. **AI 隐私、成本与审计控制**
   - 范围：未来 AI 设置页、请求上下文裁剪、语音页请求节流。
   - 目标：默认只上传当前用户明确提交的文本；截图、节点树、任务列表、日志等敏感上下文必须单独授权，并加入请求频率限制、成本上限、失败回退和 AI 决策记录。屏幕感知能力上线后，节点树压缩 / redact / 频率限制必须随之就位。

7. **密钥与服务配置外置**
   - 范围：`app/src/main/java/top/xjunz/tasker/api/Client.kt`、`app/src/main/java/top/xjunz/tasker/App.kt`。
   - 目标：更新接口 token、AppCenter secret 等不再硬编码在源码中，改为 `BuildConfig`、未提交的本机配置或 CI 注入。

8. **收敛明文网络访问**
   - 范围：`app/src/main/AndroidManifest.xml`、`api/Client.kt`。
   - 目标：默认关闭全局 `usesCleartextTraffic`，确有 HTTP 需求时用 `networkSecurityConfig` 仅放行指定域名。

## 中优先级

9. **处理 native 16KB page size 兼容**
   - 范围：`ssl` 模块和 APK 中的 native library。
   - 目标：确认 NDK/CMake 构建产物满足 Android 未来 16KB page size 设备要求。

10. **建立 lint warning baseline 或分批清理**
    - 范围：`make lint` 生成的 lint 报告。
    - 目标：先建立现有 warning 的 baseline，再让 CI 阻止新增 warning；`UnusedResources` 需要逐项确认，不直接批量删除。

11. **依赖升级评估**
    - 范围：`build.gradle.kts`、各模块 `build.gradle.kts`。
    - 目标：对 AndroidX、Material、Lifecycle、Coroutines、HiddenApiBypass 等依赖做兼容性测试后再升级。

## 低优先级

12. **统一 JVM 目标版本**
    - 范围：`app/build.gradle.kts` 与各 library 模块。
    - 目标：明确项目统一使用的 Java/Kotlin target，减少模块间工具链差异。

13. **重新设计崩溃处理委托**
    - 范围：`app/src/main/java/top/xjunz/tasker/ui/outer/GlobalCrashHandler.kt`。
    - 目标：评估是否保留自定义崩溃页，同时正确委托或替代系统默认 uncaught exception handler。

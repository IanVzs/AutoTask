/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.provider.AiProviderFactory

/**
 * Agent loop 的"嘴和脑子"：负责构造两类 prompt、调用 AI、解析模型输出。
 *
 * - [planSession]: 会话开始前问 AI："你打算在哪个 App 里做什么、需要哪些能力、估计几步？"
 *   输出供任务级授权对话框展示给用户。
 *
 * - [nextAction]: 进入 agent loop 后每一步问 AI："看了当前屏幕和过去历史，下一步该做什么？"
 *   AI 必须在固定动作 schema 内做选择，本地代码直接执行。
 */
object AiAgentPlanner {

    /**
     * 当前 agent 第一阶段对外开放的能力。模型在 prompt 里被告知只能用这些 type，
     * 任何超出的 type 都会被 [AiAgentAction.fromDto] 兜底成 Unknown。
     */
    private val OPEN_ACTIONS = listOf(
        AiAgentActionType.LAUNCH_APP,
        AiAgentActionType.CLICK,
        AiAgentActionType.LONG_CLICK,
        AiAgentActionType.SET_TEXT,
        AiAgentActionType.WAIT,
        AiAgentActionType.SCROLL,
        AiAgentActionType.GLOBAL_BACK,
        AiAgentActionType.GLOBAL_HOME,
        AiAgentActionType.DONE,
        AiAgentActionType.GIVE_UP
    )

    /** prompt 里附带的最近 N 条历史摘要，超出部分丢弃；防止 prompt 越来越长。 */
    private const val HISTORY_TAIL = 8

    suspend fun planSession(userGoal: String): AiAgentSessionPlanResult? {
        val provider = AiProviderFactory.createConfiguredProvider()
        if (provider == null) {
            AiAgentLog.w("planSession", "provider 未配置，跳过 agent 流程")
            return null
        }
        val prompt = buildSessionPlanPrompt(userGoal)
        AiAgentLog.i("planSession", "userGoal=\"$userGoal\" → 开始请求 plan")
        AiAgentLog.d("planSession.prompt", prompt)
        var rawResponse: String? = null
        return runCatching {
            withTimeout(Preferences.aiRequestTimeoutMillis.toLong()) {
                val response = provider.complete(prompt)
                rawResponse = response.text
                AiAgentLog.d("planSession.response", response.text)
                val dto = AiJson.decodeFromString<AiAgentSessionPlanDto>(extractJson(response.text))
                val capabilities = parseCapabilities(dto.requiredCapabilities).ifEmpty {
                    setOf(AiCapability.ReadNodeTree, AiCapability.ClickUi, AiCapability.InputText)
                }
                val plan = AiAgentSessionPlan(
                    targetAppPackage = dto.targetAppPackage?.trim()?.takeIf { it.isNotBlank() },
                    targetAppLabel = dto.targetAppLabel?.trim()?.takeIf { it.isNotBlank() },
                    summary = dto.planSummary?.trim()?.takeIf { it.isNotBlank() } ?: userGoal,
                    estimatedSteps = (dto.estimatedSteps ?: 6).coerceIn(1, 50),
                    capabilities = capabilities,
                    confidence = dto.confidence.coerceIn(0f, 1f)
                )
                AiAgentLog.i(
                    "planSession",
                    "解析成功：app=${plan.targetAppPackage}(${plan.targetAppLabel}) " +
                            "estSteps=${plan.estimatedSteps} caps=${plan.capabilities} " +
                            "isExecutable=${plan.isExecutable} conf=${plan.confidence}"
                )
                AiAgentSessionPlanResult(
                    plan = plan,
                    prompt = prompt,
                    rawResponse = response.text
                )
            }
        }.getOrElse { error ->
            AiAgentLog.e(
                "planSession",
                "请求或解析失败：${error.message ?: error::class.simpleName}",
                error
            )
            AiAgentSessionPlanResult(
                plan = null,
                prompt = prompt,
                rawResponse = rawResponse,
                providerError = error.message ?: error::class.simpleName
            )
        }
    }

    /**
     * @param plan 会话开场时由 [planSession] 产出的初始规划。会被原样塞进 prompt 让 AI 自检"是否
     *   还在原计划路径上"。允许为 null（适用于绕过 planSession 直接 agent 跑的场景），prompt 此时
     *   会退化成"无原计划"的提示，行为兼容旧实现。
     */
    suspend fun nextAction(
        userGoal: String,
        history: List<AiAgentStepRecord>,
        snapshot: AiUiSnapshot?,
        plan: AiAgentSessionPlan? = null,
        maxSteps: Int = AiTaskScope.DEFAULT_MAX_STEPS,
        /**
         * 当前页面"试过且没反应"的 target 描述列表（人话格式，比如 `id=com.xxx:id/btn,text=发送`）。
         * 由 [AiAgentSession] 维护并传入；planner 负责把它放进 prompt 顶部的"禁选区"，让 AI 显式
         * 看见"这些 target 已经试过没反应——绝对禁止再选"。
         */
        deadTargets: List<String> = emptyList(),
        /**
         * **C 长期失败策略记忆**（aidoc/18 §3.C）。比 deadTargets 更广义——同方向尝试（pkg+activity+actionType）
         * 的失败次数 + 最近一次诊断；让 AI 看到"这条路堵了"而不只是"这个 target 拉黑了"。
         */
        failedStrategies: List<String> = emptyList(),
        /**
         * **B Stuck Detection** 当前无进展步数（aidoc/18 §3.B）。
         * ≥ [stuckThreshold] 时 prompt 加 STUCK 警示要求 AI 强制反思 + 换方向。
         */
        stuckHits: Int = 0,
        stuckThreshold: Int = 3,
        /**
         * **跨 session 长期记忆**：会话开局由 [AiAgentSession] 调
         * [top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook.recall] 召回 top-N
         * 与本次任务最相关的历史经验，整段透传给 prompt 注入。null / 空 = 不注入。
         * AI 可以在 reflection 里参考这些经验避坑或抄已知可行路径。
         */
        experiences: List<top.xjunz.tasker.ai.agent.experience.ExperienceRecallEntry> = emptyList()
    ): AiAgentNextActionResult {
        val provider = AiProviderFactory.createConfiguredProvider()
        if (provider == null) {
            AiAgentLog.w("nextAction", "provider 未配置，返回 Unknown")
            return AiAgentNextActionResult(
                action = AiAgentAction.Unknown("AI Provider 未配置或未启用"),
                prompt = "",
                rawResponse = null,
                providerError = "provider_unconfigured"
            )
        }
        val prompt = buildNextActionPrompt(
            userGoal, history, snapshot, plan, maxSteps,
            deadTargets, failedStrategies, stuckHits, stuckThreshold, experiences
        )
        AiAgentLog.i(
            "nextAction",
            "step=${history.size} pkg=${snapshot?.packageName} nodes=${snapshot?.nodes?.size ?: 0} → 请求"
        )
        AiAgentLog.d("nextAction.prompt", prompt)
        var rawResponse: String? = null
        return runCatching {
            withTimeout(Preferences.aiRequestTimeoutMillis.toLong()) {
                val response = provider.complete(prompt)
                rawResponse = response.text
                AiAgentLog.d("nextAction.response", response.text)
                val jsonText = extractJson(response.text)
                // **Structured ReAct 解析**：先按新外层 schema 解析；retry 老格式作为兼容退化
                val reactDto = runCatching {
                    AiJson.decodeFromString<AiAgentReactResponseDto>(jsonText)
                }.getOrNull()
                val actionDto: AiAgentActionDto
                val obs: String?
                val review: String?
                val refl: String?
                when {
                    reactDto?.action != null -> {
                        actionDto = reactDto.action
                        obs = reactDto.observation
                        review = reactDto.lastActionReview
                        refl = reactDto.reflection
                    }
                    reactDto != null && reactDto.type != null -> {
                        // 兼容退化：AI 把 type 等字段放在外层（旧格式直接输出）
                        actionDto = AiAgentActionDto(
                            type = reactDto.type, packageName = reactDto.packageName, target = reactDto.target,
                            text = reactDto.text, seconds = reactDto.seconds, summary = reactDto.summary,
                            reason = reactDto.reason, thought = reactDto.thought, planStatus = reactDto.planStatus
                        )
                        obs = reactDto.observation
                        review = reactDto.lastActionReview
                        refl = reactDto.reflection
                    }
                    else -> {
                        // 完全 fallback：直接解析成老 ActionDto
                        actionDto = AiJson.decodeFromString<AiAgentActionDto>(jsonText)
                        obs = null; review = null; refl = null
                    }
                }
                val action = AiAgentAction.fromDto(actionDto)
                AiAgentLog.i(
                    "nextAction",
                    "解析成功 → ${describeAction(action)} [plan=${action.planStatus ?: "?"}] " +
                            "obs=${obs?.take(60).orEmpty()} | review=${review?.take(60).orEmpty()} | " +
                            "reflection=${refl?.take(60).orEmpty()}"
                )
                AiAgentNextActionResult(
                    action = action,
                    prompt = prompt,
                    rawResponse = response.text,
                    observation = obs,
                    lastActionReview = review,
                    reflection = refl
                )
            }
        }.getOrElse { error ->
            AiAgentLog.e(
                "nextAction",
                "请求或解析失败：${error.message ?: error::class.simpleName}",
                error
            )
            AiAgentNextActionResult(
                action = AiAgentAction.Unknown("解析模型输出失败：${error.message}"),
                prompt = prompt,
                rawResponse = rawResponse,
                providerError = error.message ?: error::class.simpleName
            )
        }
    }


    // ---------- prompt 构造 ----------

    private fun buildSessionPlanPrompt(userGoal: String): String {
        return """
你是 Android 自动化应用「AutoTask」的任务规划助手。
用户给出了一段自然语言目标，你需要做一次"开场规划"，告诉用户和系统：你打算在哪个 App 里完成它，需要哪些能力，预计几步，可能涉及的关键操作是什么。

只输出严格 JSON（不要 markdown，不要解释），schema：
{
  "target_app_package": "<目标 App 的包名，如 com.tencent.mm；不确定就留空字符串>",
  "target_app_label": "<目标 App 的中文/英文名，方便给用户看>",
  "plan_summary": "<2-3 句话说明你打算怎么做>",
  "estimated_steps": <预计步数整数，1-50>,
  "required_capabilities": [
    "ReadNodeTree", "ClickUi", "InputText", "LaunchIntent"
  ],
  "confidence": 0.0
}

可选 capability 取值（取你真正会用到的，越少越好）：
- ReadNodeTree: 读取屏幕节点树（agent 模式下默认就要）
- ClickUi: 点击按钮 / 列表项 / 入口
- InputText: 在输入框里写字
- LaunchIntent: 启动某个 App
- ExecuteExistingTask: 执行用户已有任务（agent 模式不需要）
- TakeScreenshot: 截屏（暂不开放）
- RunShell / ManageFiles / ForceStopApp: 高风险，**不要**返回

注意：
- 如果用户目标根本不需要在 App 内做 UI 操作（例如只是问问题），返回 estimated_steps=0、target_app_package="" 即可，agent 模式不会被启动。
- 包名要尽量准确，用户授权时会按这个限制 agent 只能在该 App 里操作。

用户目标：
$userGoal
        """.trimIndent()
    }

    private fun buildNextActionPrompt(
        userGoal: String,
        history: List<AiAgentStepRecord>,
        snapshot: AiUiSnapshot?,
        plan: AiAgentSessionPlan?,
        maxSteps: Int,
        deadTargets: List<String>,
        failedStrategies: List<String>,
        stuckHits: Int,
        stuckThreshold: Int,
        experiences: List<top.xjunz.tasker.ai.agent.experience.ExperienceRecallEntry> = emptyList()
    ): String {
        val historySection = buildHistorySection(history)
        val snapshotSection = buildSnapshotSection(snapshot)
        val planSection = buildPlanSection(plan, history.size, maxSteps)
        // 禁选区：精确 target hash 拉黑（再输出会被 session 拒收）
        val blacklistSection = if (deadTargets.isEmpty()) "" else buildString {
            appendLine()
            appendLine("⛔ 禁选目标（精确 target，session 兜底拒收）：")
            deadTargets.forEach { appendLine("  - $it") }
            appendLine("绝对禁止输出 target 等价于上面任何一项的动作；session 会硬拒。")
        }.trimEnd()
        // **C 长期失败策略记忆**：比禁选区更广义，让 AI 看到"这条路已经堵了"
        val failedSection = if (failedStrategies.isEmpty()) "" else buildString {
            appendLine()
            appendLine("⚠ 已尝试且失败的策略（不要再走同一方向，换别的路）：")
            failedStrategies.take(8).forEach { appendLine("  - $it") }
            if (failedStrategies.size > 8) appendLine("  ...（共 ${failedStrategies.size} 条，省略其余）")
        }.trimEnd()
        // **B Stuck Detection 警示**：达到阈值时强制 AI 在 reflection 里宣告"我意识到困住了"+ 给完全不同方向
        val stuckSection = if (stuckHits < stuckThreshold) "" else buildString {
            appendLine()
            appendLine("🚨 STUCK 警示：你已经连续 $stuckHits 步无进展（阈值 $stuckThreshold）。")
            appendLine("**本步必须**做以下两件事之一，不要再用同方向尝试硬撑：")
            appendLine("  ① 在 reflection 里**显式承认**「我意识到我困住了，原因是 X」+ 给一个**跟之前完全不同方向**的 action（比如改用 global_back / scroll / 完全不同的 target / 完全不同的 App 入口）；")
            appendLine("  ② 直接 give_up，让用户接管。")
            appendLine("**禁止**：再选一遍 deadTargets / failed strategies 里的任何东西，或换 className 但保持 text 不变这种伪'换方向'。")
        }.trimEnd()
        // **跨 session 经验本**：会话开局召回的 top-N 历史经验，给 AI 看以前在类似任务上学到的关键路径 / 已知陷阱
        val experienceSection = if (experiences.isEmpty()) "" else buildString {
            appendLine()
            appendLine("📚 你以前在类似任务上的经验（仅供参考；UI 可能已变化，要先用 observation 现场验证）：")
            experiences.forEachIndexed { i, e ->
                appendLine("--- 经验 ${i + 1}/${experiences.size} ---")
                val pkg = e.file.targetAppPackage ?: "?"
                val label = e.file.targetAppLabel?.let { " ($it)" } ?: ""
                appendLine("【目标】${e.file.userGoal}")
                appendLine("【App】${pkg}${label}")
                appendLine("【结果】${e.file.outcome} · ${e.file.outcomeLabel} · 共 ${e.file.steps.size} 步")
                if (e.file.keyLearnings.isNotEmpty()) {
                    appendLine("【关键路径】")
                    e.file.keyLearnings.take(6).forEach { appendLine("  - $it") }
                }
                if (e.file.failureTrapsAvoided.isNotEmpty()) {
                    appendLine("【已知陷阱】")
                    e.file.failureTrapsAvoided.take(6).forEach { appendLine("  - $it") }
                }
                if (!e.file.outcomeDetail.isNullOrBlank()) {
                    appendLine("【会话总结】${e.file.outcomeDetail.take(140)}")
                }
            }
            appendLine("注意：经验里的 viewId / text / contentDesc 可能已经过时；优先以当前 snapshot 为准。")
        }.trimEnd()
        return """
你是 Android 自动化应用「AutoTask」内嵌的 UI agent。每次给你「用户目标 + 会话规划 + 过去步骤 + 当前屏幕快照」，
你必须按 **ReAct 三段（Observation → Reflection → Action）** 严格输出 JSON。

═══════════════════════════════════════
■ 必填 JSON schema（顺序就是你的思考顺序，先想 observation 再想 action）
═══════════════════════════════════════
{
  "observation": "<必填，3-5 句>看完当前 snapshot 后我看到什么？activityName 是什么？关键节点（输入框/按钮/列表）有哪些？上一步 action 之后 UI 有没有产生可见变化（节点列表/text/页面切换）？",
  "last_action_review": "<必填，1-2 句>对照上一步 action 实际结果与你当时的预期：真做到我以为的事了吗？history 里追加的 ⚠ 提示我看到了吗？如果没生效，原因是什么？",
  "reflection": "<必填，2-4 句>基于上面 observation + review，下一步的方向是什么？为什么这个方向比其他备选好？",
  "action": {
    "type": "launch_app|click|long_click|set_text|wait|scroll|global_back|global_home|done|give_up",
    ... (按下方动作字段约定填)
  },
  "plan_status": "on_track|adjusted|off_track|unknown"
}

只输出一个 JSON 对象，不要 markdown / 不要数组 / 不要解释。

═══════════════════════════════════════
■ 用户目标 / 当前会话状态
═══════════════════════════════════════
目标：$userGoal
${if (experienceSection.isEmpty()) "" else "\n$experienceSection\n"}${if (stuckSection.isEmpty()) "" else "\n$stuckSection\n"}${if (blacklistSection.isEmpty()) "" else "\n$blacklistSection\n"}${if (failedSection.isEmpty()) "" else "\n$failedSection\n"}
$planSection

$historySection

$snapshotSection

═══════════════════════════════════════
■ 动作字段约定
═══════════════════════════════════════
- launch_app: { "type":"launch_app", "packageName":"<pkg>" }
- click / long_click: { "type":"click", "target": <AiUiTarget> }
   · target 优先用**有 text 或 contentDesc 的语义节点**——hint=「搜索」按钮 / desc=「发送」 这种；不要执着于必须 [C]，executor 会自动找 parent / 走坐标触摸。
- set_text: { "type":"set_text", "target": <AiUiTarget>, "text":"<内容>" }
   · target **必须** 是带 [E] 的节点；执行端会硬验证，不是 [E] 直接拒收。
- scroll: { "type":"scroll", "target": <AiUiTarget 可空>, "text":"down|up|left|right" }
- wait: { "type":"wait", "seconds": <1-30> }
- global_back / global_home: { "type":"global_back" }
- done: { "type":"done", "summary":"<向用户说明已经达成什么>" }
- give_up: { "type":"give_up", "reason":"<为什么放弃>" }

AiUiTarget 至少给一个非空字段：
{
  "viewId": "<resource id>",
  "textEquals": "<完整匹配可见文本>",
  "textContains": "<文本子串>",
  "contentDescEquals": "<完整 contentDescription>",
  "contentDescContains": "<contentDescription 子串>",
  "className": "<类名末段如 Button/EditText，慎用——AI 经常猜错真实 className 反而匹不中>",
  "matchIndex": <多匹配时取第几个，默认 0>
}

═══════════════════════════════════════
■ 行为准则（精简版，主要靠 ReAct schema 自我引导，不再堆砌规则）
═══════════════════════════════════════
1. **绝不操作 AutoTask 自己**：snapshot.packageName=`top.xjunz.tasker` 时只能 launch_app(目标 pkg) 或 give_up，禁止 click/set_text。
2. **目标已达成立刻输出 done**——尤其是当 reflection 已经看到结果时，不要硬找下一步硬撑预算。
3. **节点 flag**：[CLEFS] = C 可点 / L 可长按 / **E 可编辑** / F 可聚焦 / S 可滚动 / k 已勾选 K 未勾选 / D 禁用。
4. **target 锁定**：能用 text/desc 唯一锁定就用它；className 字段经常猜错（真节点是 LinearLayout 但你猜成 TextView），失败 1 次就别再带 className。
5. **不要操作快照里没列出的节点**——想点的入口不在就先 scroll 或 wait。
6. **global_home 之后必须 launch_app**：global_home 回桌面后 snapshot.packageName 会变成 launcher（com.bbk.launcher2 / com.miui.home 等），这时**必须** 立刻 launch_app(目标包名) 重新进入目标 App，不要在桌面找 App 图标 click。
7. **登录/支付/验证码** 等敏感界面立刻 give_up 让用户接管。
8. 步数预算 $maxSteps，珍惜每一步。


plan_status 含义：on_track（按规划） / adjusted（小调整） / off_track（偏离正在纠正） / unknown。
连续两步 off_track 没回正就直接 give_up。
        """.trimIndent()
    }

    private fun buildPlanSection(plan: AiAgentSessionPlan?, doneSteps: Int, maxSteps: Int): String {
        if (plan == null) {
            return "原会话规划：（本会话未经过 planSession，没有原计划可对照，自评 plan_status 用 unknown）"
        }
        val targetApp = plan.targetAppLabel ?: plan.targetAppPackage ?: "未指定"
        val capabilities = if (plan.capabilities.isEmpty()) "（未声明）"
        else plan.capabilities.joinToString(", ") { it.name }
        return buildString {
            appendLine("原会话规划（你在开场时自己给出的方向，请对照它做 plan_status 自检）：")
            appendLine("- 目标 App: $targetApp")
            appendLine("- 计划摘要: ${plan.summary}")
            appendLine("- 估计步数: ${plan.estimatedSteps}（已用 $doneSteps / 上限 $maxSteps）")
            appendLine("- 计划用到的能力: $capabilities")
        }.trimEnd()
    }

    private fun buildHistorySection(history: List<AiAgentStepRecord>): String {
        if (history.isEmpty()) return "过去步骤：（这是第一步）"
        val tail = if (history.size <= HISTORY_TAIL) history else history.takeLast(HISTORY_TAIL)
        return buildString {
            appendLine("过去步骤（最近 ${tail.size} 步，最旧 → 最新）：")
            tail.forEach { rec ->
                val pkg = rec.snapshotPackage?.let { " @$it" } ?: ""
                val ok = if (rec.result.ok) "OK" else "FAIL"
                val matched = rec.result.matchedNodeSummary?.let { " on $it" } ?: ""
                val msg = rec.result.message?.let { " | $it" } ?: ""
                val ps = rec.action.planStatus?.takeIf { it.isNotBlank() }?.let { " [plan=$it]" } ?: ""
                val intervention = formatIntervention(rec.userIntervention)
                appendLine("- #${rec.index}$pkg ${describeAction(rec.action)}$ps$intervention → $ok$matched$msg")
                // ReAct 三段写在子行——让 AI 看见自己 N 步前的思维链路（链式 self-consistency）
                rec.observation?.takeIf { it.isNotBlank() }?.let {
                    appendLine("    obs: ${it.take(120)}")
                }
                rec.lastActionReview?.takeIf { it.isNotBlank() }?.let {
                    appendLine("    review: ${it.take(120)}")
                }
                rec.reflection?.takeIf { it.isNotBlank() }?.let {
                    appendLine("    reflection: ${it.take(120)}")
                }
            }
            // 用户介入累计统计（让 AI 感知"我前几步被用户改了几次方向"）
            val interventions = history.mapNotNull { it.userIntervention }
            val replaced = interventions.count { it is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Replaced }
            if (replaced > 0) {
                appendLine("（累计：用户换节点 $replaced 步——请反思你的选择是否经常偏离用户意图）")
            }
        }.trimEnd()
    }

    private fun formatIntervention(d: top.xjunz.tasker.ai.agent.overlay.AiAgentDecision?): String {
        if (d == null) return ""
        return when (d) {
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.ApprovedManual -> " [用户同意]"
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.ApprovedAuto -> ""
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Terminate -> {
                val r = if (d.reason.isNullOrBlank()) "" else "(${d.reason})"
                " [用户终止${r}]"
            }
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Replaced -> {
                val hint = d.replacementHint?.let { "($it)" }.orEmpty()
                " [用户换为${hint}]"
            }
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Skipped -> ""
        }
    }

    private fun buildSnapshotSection(snapshot: AiUiSnapshot?): String {
        if (snapshot == null) {
            return "当前屏幕：（无法读取节点树，请先尝试 launch_app 或 wait）"
        }
        val header = buildString {
            append("当前屏幕")
            snapshot.packageName?.let { append(" @$it") }
            snapshot.activityName?.let { append("/$it") }
            append(" (${snapshot.screenWidth}x${snapshot.screenHeight})")
        }
        if (snapshot.nodes.isEmpty()) {
            return "$header：（节点列表为空，可能屏幕还在加载）"
        }
        // 头部统计——给 AI 一眼判断"此页能不能 set_text / 能不能 click"的依据。
        // editable=0 是关键信号：set_text 直接禁用，必须先 click 进入有输入框的页面。
        val clickableCnt = snapshot.nodes.count { it.clickable }
        val editableCnt = snapshot.nodes.count { it.editable }
        val scrollableCnt = snapshot.nodes.count { it.scrollable }
        return buildString {
            appendLine(header)
            appendLine(
                "节点 ${snapshot.nodes.size} 个（可点击 $clickableCnt / 可编辑 $editableCnt / 可滚动 $scrollableCnt）"
            )
            appendLine(
                "flag 含义：C=可点击 L=可长按 **E=可编辑（输入框）** F=可聚焦 S=可滚动 k/K=勾选状态 D=已禁用"
            )
            if (editableCnt == 0) {
                appendLine("⚠ 当前屏幕**没有任何 [E] 节点**——禁止使用 set_text，应先 click 进入有输入框的页面。")
            }
            appendLine("节点列表：")
            snapshot.nodes.forEach { n ->
                appendLine(formatNode(n))
            }
        }.trimEnd()
    }

    private fun formatNode(n: AiUiNode): String {
        val flags = buildString {
            if (n.clickable) append('C')
            if (n.longClickable) append('L')
            if (n.editable) append('E')
            if (n.scrollable) append('S')
            if (n.checkable) append(if (n.checked) 'k' else 'K')
            if (!n.enabled) append('D')
            if (n.focused) append('F')
        }.ifEmpty { "-" }
        val text = n.text?.let { " text=\"$it\"" } ?: ""
        val cd = n.contentDesc?.let { " desc=\"$it\"" } ?: ""
        val viewId = n.viewId?.let { " id=$it" } ?: ""
        val parent = n.parentId?.let { " parent=#$it" } ?: ""
        return "  #${n.id} [${n.className}] [$flags]$viewId$text$cd $parent bounds=(${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom})"
    }

    private fun describeAction(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch_app(${action.packageName})"
        is AiAgentAction.Click -> "click(${formatTarget(action.target)})"
        is AiAgentAction.LongClick -> "long_click(${formatTarget(action.target)})"
        is AiAgentAction.SetText -> "set_text(${formatTarget(action.target)}, \"${action.text.take(40)}\")"
        is AiAgentAction.Wait -> "wait(${action.seconds}s)"
        is AiAgentAction.Scroll -> "scroll(${action.direction})"
        is AiAgentAction.GlobalBack -> "global_back"
        is AiAgentAction.GlobalHome -> "global_home"
        is AiAgentAction.Done -> "done(${action.summary.take(40)})"
        is AiAgentAction.GiveUp -> "give_up(${action.reason.take(40)})"
        is AiAgentAction.Unknown -> "unknown(${action.raw.take(40)})"
    }

    private fun formatTarget(t: AiUiTarget): String = buildString {
        val parts = mutableListOf<String>()
        t.viewId?.let { parts.add("id=$it") }
        t.textEquals?.let { parts.add("text=$it") }
        t.textContains?.let { parts.add("text~$it") }
        t.contentDescEquals?.let { parts.add("desc=$it") }
        t.contentDescContains?.let { parts.add("desc~$it") }
        t.className?.let { parts.add("cls=$it") }
        if (t.matchIndex > 0) parts.add("idx=${t.matchIndex}")
        append(parts.joinToString(",").take(120))
    }

    private fun parseCapabilities(raw: List<String>?): Set<AiCapability> {
        if (raw.isNullOrEmpty()) return emptySet()
        val result = mutableSetOf<AiCapability>()
        for (item in raw) {
            val trimmed = item.trim()
            val match = AiCapability.entries.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true)
            }
            if (match != null) result.add(match)
        }
        return result
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end >= start) text.substring(start, end + 1) else text
    }

}

@Serializable
data class AiAgentSessionPlanDto(
    @SerialName("target_app_package") val targetAppPackage: String? = null,
    @SerialName("target_app_label") val targetAppLabel: String? = null,
    @SerialName("plan_summary") val planSummary: String? = null,
    @SerialName("estimated_steps") val estimatedSteps: Int? = null,
    @SerialName("required_capabilities") val requiredCapabilities: List<String>? = null,
    val confidence: Float = 0f
)

data class AiAgentSessionPlan(
    val targetAppPackage: String?,
    val targetAppLabel: String?,
    val summary: String,
    val estimatedSteps: Int,
    val capabilities: Set<AiCapability>,
    val confidence: Float
) {
    val isExecutable: Boolean
        get() = estimatedSteps > 0 && (capabilities.isNotEmpty()) &&
                (capabilities.any {
                    it == AiCapability.ClickUi || it == AiCapability.InputText ||
                            it == AiCapability.LaunchIntent || it == AiCapability.ReadNodeTree
                })
}

data class AiAgentSessionPlanResult(
    val plan: AiAgentSessionPlan?,
    val prompt: String,
    val rawResponse: String?,
    val providerError: String? = null
)

/**
 * Structured ReAct 响应（aidoc/18 §3.A）。
 *
 * - [action] 永远是真要执行的强类型动作。
 * - [observation] / [lastActionReview] / [reflection] 是 AI 在生成 action 前**强制**写下的
 *   ReAct 三段思考，session 会把它们 append 到 step record 给后续 history 用。
 *   AI 没遵守新格式（直接给老 ActionDto）时会是 null，session 仍能跑（兼容退化）。
 */
data class AiAgentNextActionResult(
    val action: AiAgentAction,
    val prompt: String,
    val rawResponse: String?,
    val providerError: String? = null,
    val observation: String? = null,
    val lastActionReview: String? = null,
    val reflection: String? = null
)

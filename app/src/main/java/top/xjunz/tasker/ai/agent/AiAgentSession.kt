/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.ai.agent.overlay.AiAgentConfirmMode
import top.xjunz.tasker.ai.agent.overlay.AiAgentDecision
import top.xjunz.tasker.ai.agent.overlay.AiAgentNodePicker
import top.xjunz.tasker.ai.agent.overlay.AiAgentOverlayController
import top.xjunz.tasker.ai.agent.overlay.InspectorPickerStub
import top.xjunz.tasker.task.inspector.shared.UiTreeQuery

/**
 * Agent 模式的执行循环。
 *
 * 使用方式：
 * 1. UI 层先用 [AiAgentPlanner.planSession] 拿到 [AiAgentSessionPlan]，弹"任务级授权"对话框给用户。
 * 2. 用户点允许 → 写一条 [AiTaskScope] 进 [AiTaskScopeStore]。
 * 3. 构造 [AiAgentSession] 并 [run]。Session 在协程里跑，每步通过 [callbacks] 回调出去，
 *    UI 实时把进度追加到记录卡片，**不再有任何中途弹窗**。
 *
 * Session 自己不创建协程，调用方负责 launch；这样 cancel / 生命周期管理留给上层。
 */
private const val OUT_OF_SCOPE_TOLERATE_HITS = 3

/**
 * **B Stuck Detection 阈值**（aidoc/18 §3.B）。连续这么多步无进展就触发 forced replan，
 * 在 prompt 里加 STUCK 警示要求 AI 给完全不同方向；超过 [STUCK_HARD_LIMIT] 直接 give_up。
 */
private const val STUCK_FORCED_REPLAN_AT = 3
private const val STUCK_HARD_LIMIT = 6

class AiAgentSession(
    val scope: AiTaskScope,
    /**
     * 开场 [AiAgentPlanner.planSession] 出的初始规划。每步 [AiAgentPlanner.nextAction] 都会带上它，
     * 让 AI 自检"是否还在原计划路径上"，并在 history 与 outcome 里沉淀 plan_status。
     * 允许 null（未走 planSession 的极端场景），prompt 会退化为"无原计划"模式。
     */
    val plan: AiAgentSessionPlan? = null,
    /**
     * 决策面板控制器。允许 null（无 UI 上下文，比如 service context 拿不到的场景）；
     * 非 null 但 `isAvailable() == false`（用户没授悬浮窗权限）时，所有决策都自动 [AiAgentDecision.Skipped]。
     */
    private val overlay: AiAgentOverlayController? = null,
    private val picker: AiAgentNodePicker = InspectorPickerStub(),
    private val callbacks: Callbacks,
    /**
     * **跨 session 长期记忆**：会话开始前由调用方（[top.xjunz.tasker.voice.VoiceCommandService.runAgentFlow]）
     * 调用 [top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook.recall] 召回的相关历史经验。
     * 整段透传给 [AiAgentPlanner.nextAction] 注入 prompt，session 内不重新召回（避免每步 IO）。
     * 默认空：未配置经验本 / 用户关闭 / 第一次跑都是合理的"空"。
     */
    private val experiences: List<top.xjunz.tasker.ai.agent.experience.ExperienceRecallEntry> = emptyList()
) {

    interface Callbacks {
        /** 每完成一步（不论成功失败）都会回调。 */
        fun onStep(record: AiAgentStepRecord)

        /** AI 当前正在思考下一步，UI 可以显示个 loading 状态。 */
        fun onThinking(stepIndex: Int) {}

        /** 整个会话结束，附上完整 outcome。 */
        fun onComplete(outcome: AiAgentSessionOutcome)
    }

    private val history = mutableListOf<AiAgentStepRecord>()
    private var stepIndex = 0

    /**
     * 已确认 silent-fail 的 target 黑名单，按 packageName 隔离——同一 viewId 在不同 App / 不同
     * Activity 含义不同，所以**不**全局共享。每页面独立维护。
     *
     * 维护规则：
     * - 上一步 silent-fail 检测命中（snapshot 签名前后一致）时，把上一步的 target 哈希加入当前 pkg 的集合；
     * - 下一轮调 [AiAgentPlanner.nextAction] 时把当前 pkg 的列表喂进去，prompt 顶部硬列"禁选区"；
     * - AI 仍输出黑名单内 target → session 直接拒收（写一条更强反馈），不浪费一次 perform。
     *
     * 这是用户在 deepseek 测试反馈"AI 一直点错节点"的核心修法——单纯在 history 里追加 ⚠
     * 提示对 AI 引导力不够，必须用最显眼的"禁选区"配合 session 兜底拒收。
     */
    private val deadTargetsByPkg = mutableMapOf<String, MutableSet<String>>()

    /**
     * **C 长期失败策略记忆**（aidoc/18 §3.C）。
     *
     * 把"我已经尝试过 X 种策略 + 都失败了"显式注入下一轮 prompt，让 AI **不必从 history 自己 inference**
     * 哪条路堵了——直接看顶部的"已尝试失败的策略"列表，跟禁选区是不同维度：
     * - 禁选区（[deadTargetsByPkg]）：精确 target hash 拉黑，AI 复用必拒收
     * - 失败策略（本 map）：更广义的"策略指纹"（pkg + activity + actionType + 关键 target 字段），
     *   AI 看完会主动避开同方向尝试，不只是同一 target
     *
     * key = `{pkg}|{activity}|{actionType}|{targetSemanticHash}`，value = 失败次数 + 最近一次诊断 message。
     */
    private val failedStrategies = mutableMapOf<String, FailedStrategy>()

    data class FailedStrategy(
        val key: String,
        val description: String,
        var attemptCount: Int = 1,
        var lastDiagnostic: String? = null
    )

    /**
     * **B Stuck Detection** 计数器（aidoc/18 §3.B）。
     * 累加规则见 [bumpStuckOnFailure] / [resetStuckOnProgress]，
     * ≥ [STUCK_FORCED_REPLAN_AT] 触发 prompt 注入 STUCK 警示要求 AI 重新思考；
     * ≥ [STUCK_HARD_LIMIT] 直接终止 session。
     */
    private var consecutiveUnproductiveSteps = 0

    private fun bumpStuckOnFailure(reason: String) {
        consecutiveUnproductiveSteps++
        AiAgentLog.w(
            "session.stuck",
            "无进展计数 +1 = $consecutiveUnproductiveSteps（原因：$reason）；阈值 $STUCK_FORCED_REPLAN_AT/$STUCK_HARD_LIMIT"
        )
    }

    private fun resetStuckOnProgress() {
        if (consecutiveUnproductiveSteps > 0) {
            AiAgentLog.i("session.stuck", "已取得进展，无进展计数清零（原 $consecutiveUnproductiveSteps）")
        }
        consecutiveUnproductiveSteps = 0
    }

    /**
     * 同一 (pkg, target) 命中 silent-fail 的次数。**累计 ≥ 2 才永久拉黑**，给"动画延迟期 click 不响应"
     * 留一次重试机会。
     *
     * 真实场景：deepseek/淘宝/京东这种重启动画的 App，启动后第一次 click 经常被状态机吞掉，
     * 屏幕节点签名不变；但**再次 click 同一节点**就生效了。如果第一次 silent-fail 直接拉黑，
     * 反而把唯一正确的入口锁死，AI 找不到出路。
     */
    private val silentFailHitsByPkgAndTarget = mutableMapOf<String, MutableMap<String, Int>>()

    /**
     * 连续 AI 解析失败次数。第一次允许重试（给网络抖动一次机会），第二次才真终止 session。
     * 任何**成功解析**的步骤会清零。这是用户在 deepseek 测试遇到的问题：单次 8s timeout
     * 就把整段 session 灭了，太脆弱。
     */
    private var consecutiveAiErrors = 0

    /**
     * 连续观测到 "pkg 不在授权范围且看上去像 launcher 过场" 的次数。
     * 累计 ≥ [OUT_OF_SCOPE_TOLERATE_HITS] 才真终止。
     *
     * 真实场景：vivo OriginOS 的 launcher 包名 `com.bbk.launcher2` 漏列了一段时间，
     * 还有不少第三方桌面 / 过场动画 App 没法穷举。下次 launch_app 之后碰到一帧过场就立刻
     * outOfScope 终止过于激进——给个 N 轮宽容窗口，等下一帧反弹回目标 App 再继续。
     */
    private var consecutiveOutOfScopeHits = 0

    /**
     * 进入循环；返回值与最后一次 [Callbacks.onComplete] 一致，方便调用方就地拿到结果。
     */
    suspend fun run(): AiAgentSessionOutcome = coroutineScope {
        AiAgentLog.i(
            "session.start",
            "id=${scope.sessionId} goal=\"${scope.userGoal}\" " +
                    "apps=${scope.targetApps} caps=${scope.capabilities} " +
                    "max=${scope.maxSteps}步/${scope.maxDurationSeconds}s " +
                    "planExecutable=${plan?.isExecutable}"
        )
        // Precheck: a11y / Shizuku 任一服务未启动 → agent 完全瞎，立刻终止避免浪费 token 跑空循环。
        if (!UiTreeQuery.isAutomatorServiceRunning()) {
            AiAgentLog.w("session.precheck", "AutomatorService 未运行（a11y / Shizuku 都没连），agent 无法读屏，立刻终止")
            val outcome = AiAgentSessionOutcome.ServiceNotConnected(history.toList())
            callbacks.onComplete(outcome)
            return@coroutineScope outcome
        }
        val outcome = runLoop(this)
        AiAgentLog.i(
            "session.end",
            "id=${scope.sessionId} outcome=${outcome::class.simpleName} steps=${outcome.history.size}"
        )
        callbacks.onComplete(outcome)
        outcome
    }

    private suspend fun runLoop(scopeCtx: kotlinx.coroutines.CoroutineScope): AiAgentSessionOutcome {
        while (scopeCtx.isActive) {
            // ---- 边界检查 ----
            if (stepIndex >= scope.maxSteps) {
                AiAgentLog.w("session.limit", "已达最大步数 ${scope.maxSteps}")
                return AiAgentSessionOutcome.LimitExceeded(history.toList(), reason = "已达最大步数 ${scope.maxSteps}")
            }
            if (System.currentTimeMillis() >= scope.deadlineMillis) {
                AiAgentLog.w("session.limit", "已达最大时长 ${scope.maxDurationSeconds}s")
                return AiAgentSessionOutcome.LimitExceeded(history.toList(), reason = "已达最大时长 ${scope.maxDurationSeconds}s")
            }

            // ---- 抓快照 ----
            overlay?.showStatus("正在读取屏幕节点...", stepIndex, scope.maxSteps)
            val snapshot = ScreenSnapshotProvider.capture()
            val pkg = snapshot?.packageName
            val currentSig = snapshot?.let { computeSnapshotSignature(it) }

            // ---- 上一步 silent-fail 检测 ----
            // 真实场景：deepseek 启动页 AI 把 TextView 误当输入框 click，task 报 OK 但屏幕节点
            // 签名前后一致——说明动作没让 UI 产生可见变化（节点选错 / 节点不响应）。
            // 把这条提示追加到 history 最后一项的 result.message，下一轮 prompt 让 AI 看见，
            // 配合 prompt 里的"换策略"准则，AI 会自己换 viewId / scroll / give_up。
            // 0 额外快照抓取——复用本轮抓的 snapshot 跟上一步保存的 preActionSignature 比对。
            if (history.isNotEmpty() && currentSig != null) {
                val last = history.last()
                if (last.preActionSignature != null &&
                    last.preActionSignature == currentSig &&
                    last.result.ok &&
                    actionMayChangeUi(last.action)
                ) {
                    AiAgentLog.w(
                        "session.silentFail",
                        "上一步 ${last.action::class.simpleName} 执行后屏幕节点签名未变化——" +
                                "目标可能选错 / 控件不响应"
                    )
                    // **二次拉黑机制**：第一次 silent-fail 只**计数 + 提示**（给动画延迟一次机会），
                    // 第二次同一 target silent-fail 才永久拉黑。避免把"启动期偶尔吞 click 但其实正确"的入口锁死。
                    val pkgKey = last.snapshotPackage ?: pkg ?: "*"
                    val targetHash = actionTargetHash(last.action)
                    val hits = if (targetHash != null) {
                        silentFailHitsByPkgAndTarget
                            .getOrPut(pkgKey) { mutableMapOf() }
                            .merge(targetHash, 1, Int::plus) ?: 1
                    } else 1
                    val msgSuffix = if (hits == 1) {
                        " ⚠ 执行后屏幕没有任何可见变化（节点签名前后一致）。可能是动画延迟或节点不响应——" +
                                "**允许你再试一次**同一 target；如果再次无效就请换 target / scroll / give_up。"
                    } else {
                        " ⚠ 执行后屏幕仍无变化，**已是第 ${hits} 次** silent fail。该 target 已被永久拉黑——" +
                                "必须换其他节点 / scroll / give_up。"
                    }
                    val patched = last.copy(
                        result = last.result.copy(
                            message = (last.result.message ?: "task 执行完成") + msgSuffix
                        )
                    )
                    history[history.size - 1] = patched
                    if (hits >= 2 && targetHash != null) {
                        deadTargetsByPkg.getOrPut(pkgKey) { mutableSetOf() }.add(targetHash)
                        AiAgentLog.w("session.blacklist", "已拉黑 pkg=$pkgKey target=$targetHash (累计 $hits 次 silent fail)")
                    } else {
                        AiAgentLog.w("session.silentFailFirstChance", "pkg=$pkgKey target=$targetHash 第一次 silent fail，给一次重试机会")
                    }
                    // **C 长期失败策略记忆**：silent-fail 也算失败策略
                    recordFailedStrategy(patched, snapshot?.activityName)
                    // **B Stuck Detection**：silent-fail 算无进展
                    bumpStuckOnFailure("silent-fail target=$targetHash")
                } else {
                    // 上一步 ok=true 且屏幕真变了 → 取得进展
                    // 注意：这里不能要求 actionMayChangeUi——wait 等动作虽然 may not change UI 但不算无进展，
                    // 它们的 stuck 计数已经在 execute 阶段（line ~533）的 ok 判定里处理过了。
                    // 这里专门处理"UI-affecting + ok + 签名真变"的进展场景。
                    if (last.result.ok && actionMayChangeUi(last.action)) {
                        resetStuckOnProgress()
                    }
                }
            } else if (history.isEmpty()) {
                // 第一步前没有 history，stuck 计数本就是 0；显式提示日志便于排查。
                resetStuckOnProgress()
            }

            // **B Stuck Detection 硬终止（统一位置）**：任何失败路径累加 stuck 后下一轮都会先到这。
            // 之前只在 deadTarget 拒收后查，silent-fail 累积超过硬限不会终止 session。
            if (consecutiveUnproductiveSteps >= STUCK_HARD_LIMIT) {
                AiAgentLog.w(
                    "session.stuckHardLimit",
                    "无进展计数 $consecutiveUnproductiveSteps ≥ 硬限 $STUCK_HARD_LIMIT，强制终止 session"
                )
                val outcome = AiAgentSessionOutcome.GivenUp(
                    reason = "stuck 硬限触发：连续 $consecutiveUnproductiveSteps 步无进展，AI 反思后仍无突破",
                    history = history.toList(),
                    lastRecord = history.lastOrNull() ?: AiAgentStepRecord(
                        index = stepIndex,
                        action = AiAgentAction.GiveUp("stuck-hard-limit"),
                        result = AiAgentStepResult(false, "stuck 硬限")
                    )
                )
                callbacks.onComplete(outcome)
                return outcome
            }

            // ---- App scope 检查 ----
            // 第一步前 App 还没启动是正常的（AI 可能要先 launch_app），允许 snapshot 为 null 或 在桌面/launcher。
            // 但一旦 history 里已经有 launch_app 成功后，AI 又跑去其他 App，立刻停下。
            if (pkg != null && history.isNotEmpty() && !scope.coversApp(pkg)) {
                if (isLauncherOrSystem(pkg)) {
                    // 已知的 launcher / 系统过场，正常容忍，重置计数
                    consecutiveOutOfScopeHits = 0
                } else {
                    // 未知第三方过场：累计 N 次后才真终止，给 OEM 闪屏 / 反弹回目标 App 留窗口
                    consecutiveOutOfScopeHits++
                    if (consecutiveOutOfScopeHits < OUT_OF_SCOPE_TOLERATE_HITS) {
                        AiAgentLog.w(
                            "session.outOfScopeTolerate",
                            "当前包 $pkg 不在授权 ${scope.targetApps} 内，但还在反弹宽容窗口" +
                                    "（${consecutiveOutOfScopeHits}/$OUT_OF_SCOPE_TOLERATE_HITS），等下一轮再看"
                        )
                        delay(400)
                        continue
                    }
                    AiAgentLog.w(
                        "session.outOfScope",
                        "当前包 $pkg 不在授权 ${scope.targetApps} 内，连续 $consecutiveOutOfScopeHits 次确认，停止"
                    )
                    return AiAgentSessionOutcome.OutOfScope(
                        history = history.toList(),
                        currentPackage = pkg
                    )
                }
            } else {
                // 包名 OK / snapshot 空 / 第一步前 → 正常状态，重置计数
                consecutiveOutOfScopeHits = 0
            }

            // ---- 问 AI ----
            callbacks.onThinking(stepIndex)
            overlay?.showStatus(
                "AI 正在规划第 ${stepIndex + 1} 步动作（最多等 ${Preferences.aiRequestTimeoutMillis / 1000}s）...",
                stepIndex, scope.maxSteps
            )
            val deadList = pkg?.let { deadTargetsByPkg[it]?.toList() }.orEmpty()
            // **C 长期记忆**：把所有失败策略整理成 prompt 可读的列表
            val failedList = failedStrategies.values
                .sortedByDescending { it.attemptCount }
                .map {
                    "${it.description}（失败 ${it.attemptCount} 次${it.lastDiagnostic?.let { d -> "；最后一次：${d.take(80)}" }.orEmpty()}）"
                }
            val nextResult = AiAgentPlanner.nextAction(
                userGoal = scope.userGoal,
                history = history,
                snapshot = snapshot,
                plan = plan,
                maxSteps = scope.maxSteps,
                deadTargets = deadList,
                failedStrategies = failedList,
                stuckHits = consecutiveUnproductiveSteps,
                stuckThreshold = STUCK_FORCED_REPLAN_AT,
                experiences = experiences
            )

            val action = nextResult.action

            // ---- 黑名单兜底拒收：AI 仍然输出禁选 target → 直接短路，不浪费 perform ----
            // prompt 已经显式列出禁选区，AI 还输出说明 prompt 引导失败，必须用程序硬拦。
            val targetHash = actionTargetHash(action)
            if (targetHash != null && pkg != null && deadTargetsByPkg[pkg]?.contains(targetHash) == true) {
                AiAgentLog.w(
                    "session.deadTarget",
                    "AI 又输出禁选 target=$targetHash 在 pkg=$pkg；session 拒收并提示换思路"
                )
                val record = AiAgentStepRecord(
                    index = stepIndex,
                    action = action,
                    result = AiAgentStepResult(
                        ok = false,
                        message = "禁选 target：$targetHash 已在本页面试过且无任何反应——请彻底换思路（不同 target / scroll / wait / give_up）。"
                    ),
                    snapshotPackage = pkg
                )
                history += record
                callbacks.onStep(record)
                stepIndex++
                // **B Stuck Detection**：deadTarget 拒收也算无进展
                bumpStuckOnFailure("deadTarget rejected: $targetHash")
                delay(150)
                continue
                // 硬限检查已移到 loop 起始的统一位置，下一轮 continue 后会触发
            }

            // ---- 终止条件 ----
            when (action) {
                is AiAgentAction.Done -> {
                    val record = recordNoOpStep(action, snapshot, ok = true, message = action.summary)
                    return AiAgentSessionOutcome.Completed(action.summary, history.toList(), lastRecord = record)
                }
                is AiAgentAction.GiveUp -> {
                    val record = recordNoOpStep(action, snapshot, ok = false, message = action.reason)
                    return AiAgentSessionOutcome.GivenUp(action.reason, history.toList(), lastRecord = record)
                }
                is AiAgentAction.Unknown -> {
                    consecutiveAiErrors++
                    val isRetriable = consecutiveAiErrors < 2  // 给第一次失败一次重试机会
                    val record = recordNoOpStep(
                        action, snapshot, ok = false,
                        message = if (isRetriable) {
                            "AI 调用失败（${nextResult.providerError ?: "未知"}），将自动重试一次"
                        } else {
                            "AI 调用连续失败 ${consecutiveAiErrors} 次，session 终止：${nextResult.providerError ?: action.raw}"
                        }
                    )
                    if (isRetriable) {
                        AiAgentLog.w(
                            "session.aiRetry",
                            "AI 第 $consecutiveAiErrors 次失败，等 1.5s 后重试：${nextResult.providerError}"
                        )
                        delay(1500)
                        continue
                    }
                    return AiAgentSessionOutcome.AiError(
                        reason = nextResult.providerError ?: action.raw,
                        history = history.toList(),
                        lastRecord = record
                    )
                }
                else -> {
                    // 成功解析任何 action → 清零失败计数（让间歇性失败不至于累积到上限）
                    consecutiveAiErrors = 0
                }
            }

            // ---- 防"AI 看不到 App 已启动 → 反复 launch 同一 pkg"卡死 ----
            // 现象：京东/淘宝这种大 App 冷启动期间，AI 拿到的 snapshot 还是桌面，会再 launch 一次。
            // 当前 snapshot pkg 已经是 AI 想 launch 的目标时，**不真的执行**，写一条反馈让 AI
            // 在下一轮看到"已经在前台了，请基于现有节点决定下一步"，避免 6 步全是 launch_app。
            if (action is AiAgentAction.LaunchApp && pkg != null && pkg == action.packageName) {
                AiAgentLog.w(
                    "session.skipRelaunch",
                    "AI 又想 launch_app($pkg) 但当前前台已是 $pkg，短路并提示 AI"
                )
                val record = AiAgentStepRecord(
                    index = stepIndex,
                    action = action,
                    result = AiAgentStepResult(
                        ok = false,
                        message = "$pkg 已在前台，无需再次 launch_app；请基于当前节点决定下一步动作（点击 / 输入 / 滚动 / done）"
                    ),
                    snapshotPackage = pkg
                )
                history += record
                callbacks.onStep(record)
                stepIndex++
                delay(150)
                continue
            }

            // 注：旧的 skipRepeatClick "上一步刚 click 同 target 直接拦截" 已删除——
            // 新的 silent-fail 二次拉黑机制更精细：第一次允许重试（给动画延迟一次机会），
            // 第二次永久拉黑后由 deadTarget 拒收。skipRepeatClick 跟二次机会冲突会把唯一正确入口锁死。

            // ---- capability 边界（轻量校验）----
            val capability = action.requiredCapability()
            if (capability != null && !scope.covers(capability)) {
                AiAgentLog.w(
                    "session.denyCap",
                    "AI 想用 $capability 但本次 scope 没有，停止 (scope.caps=${scope.capabilities})"
                )
                val record = recordNoOpStep(
                    action, snapshot, ok = false,
                    message = "用到未授权能力：$capability"
                )
                return AiAgentSessionOutcome.PermissionDenied(
                    capability = capability,
                    history = history.toList(),
                    lastRecord = record
                )
            }

            // ---- 决策面板：用户介入（同意 / 拒绝 / 换一个）----
            // 仅对"有 UI 副作用"的动作征询，wait 等无副作用动作直接放行。
            // overlay 不可用 / 用户在 Preferences 里禁用 / ManualOnly 模式但没人点 → Skipped。
            val decision: AiAgentDecision = if (overlay != null && actionNeedsConfirmation(action)) {
                val mode = AiAgentConfirmMode.parse(Preferences.aiAgentConfirmMode)
                overlay.requestDecision(
                    stepIndex = stepIndex,
                    action = action,
                    snapshot = snapshot,
                    mode = mode,
                    timeoutSeconds = Preferences.aiAgentConfirmSeconds.coerceIn(0, 60),
                    allowReplace = Preferences.aiAgentConfirmAllowReplace,
                    picker = picker
                )
            } else {
                AiAgentDecision.Skipped
            }

            // 用户主动终止 → 整段 session 立刻结束，不执行当前 action，不再循环
            if (decision is AiAgentDecision.Terminate) {
                AiAgentLog.w("session.userTerminate", "用户在决策面板主动终止：${decision.reason ?: "(no reason)"}")
                val record = AiAgentStepRecord(
                    index = stepIndex,
                    action = action,
                    result = AiAgentStepResult(
                        ok = false,
                        message = "用户在决策面板按下「终止」：${decision.reason ?: "(no reason)"}"
                    ),
                    snapshotPackage = pkg,
                    userIntervention = decision
                )
                history += record
                callbacks.onStep(record)
                return AiAgentSessionOutcome.Cancelled(history.toList())
            }

            val (executedAction, replacementHint) = when (decision) {
                is AiAgentDecision.Replaced ->
                    replaceTarget(action, decision.newTarget) to decision.replacementHint
                else -> action to null
            }

            // ---- 执行 ----
            // 状态文案优先用 reflection（ReAct 阶段产出，最反映 AI 真实意图）；
            // 退化到 thought（老格式 fallback）；都没有就只显示动作。
            val intentTail = (nextResult.reflection ?: executedAction.thought)
                ?.takeIf { it.isNotBlank() }
                ?.let { "（${it.take(60)}）" }.orEmpty()
            overlay?.showStatus(
                "正在执行：${describeActionShort(executedAction)}$intentTail",
                stepIndex, scope.maxSteps
            )
            val result = AiAgentExecutor.execute(executedAction)
            // 执行完后把结果立刻反馈到面板上——OK / FAIL + 简短消息，让用户知道这一步成败
            val resultTail = if (result.ok) "✓" else "✗"
            val resultMsg = result.message?.take(60) ?: ""
            overlay?.showStatus(
                "已完成：$resultTail ${describeActionShort(executedAction)}\n$resultMsg",
                stepIndex, scope.maxSteps
            )
            val record = AiAgentStepRecord(
                index = stepIndex,
                action = executedAction,
                result = result,
                snapshotPackage = pkg,
                userIntervention = decision.takeIf { it !is AiAgentDecision.Skipped },
                // 把执行**之前**的屏幕签名记下来，下一轮抓到新 snapshot 时跟它比对——
                // 签名一致就给 result.message 追加 silent-fail 警告，AI 在下一轮 prompt 看见就会换策略。
                preActionSignature = currentSig,
                // ReAct 三段保留进 record，下一轮 history section 喂回 AI 看自己的思维链
                observation = nextResult.observation,
                lastActionReview = nextResult.lastActionReview,
                reflection = nextResult.reflection
            )
            history += record
            callbacks.onStep(record)
            // **C 失败策略记忆 + B Stuck Detection**：ok=false 累加；ok=true 待下一轮 silent-fail 检查决定
            if (!result.ok) {
                recordFailedStrategy(record, snapshot?.activityName)
                bumpStuckOnFailure("execute ok=false: ${describeActionShort(executedAction).take(50)}")
            }
            stepIndex++
            // replacementHint 仅记录在 history.intervention 标签里给 AI 看，本地不需要再额外做事
            @Suppress("UNUSED_VARIABLE")
            val _hint = replacementHint

            // 一些动作内部已经 delay 过；这里再加一个微小间隔让 UI 有时间处理回调。
            delay(150)
        }
        return AiAgentSessionOutcome.Cancelled(history.toList())
    }

    /**
     * "有 UI 副作用"判断：影响系统状态的动作走决策面板，无副作用动作（wait）直接放行。
     * Done / GiveUp / Unknown 已经在 nextAction 之后立即 return，不会到这。
     */
    private fun actionNeedsConfirmation(action: AiAgentAction): Boolean = when (action) {
        is AiAgentAction.LaunchApp,
        is AiAgentAction.Click,
        is AiAgentAction.LongClick,
        is AiAgentAction.SetText,
        is AiAgentAction.Scroll,
        is AiAgentAction.GlobalBack,
        is AiAgentAction.GlobalHome -> true
        is AiAgentAction.Wait -> false
        is AiAgentAction.Done,
        is AiAgentAction.GiveUp,
        is AiAgentAction.Unknown -> false
    }

    /** 把 AI 原 target 换成用户挑的真节点对应的新 target；不支持 target 的动作原样返回。 */
    private fun replaceTarget(
        action: AiAgentAction,
        newTarget: AiUiTarget
    ): AiAgentAction = when (action) {
        is AiAgentAction.Click -> action.copy(target = newTarget)
        is AiAgentAction.LongClick -> action.copy(target = newTarget)
        is AiAgentAction.SetText -> action.copy(target = newTarget)
        is AiAgentAction.Scroll -> action.copy(target = newTarget)
        else -> action
    }

    /**
     * 两个 target 是否"实质相同"——优先比 viewId（最强信号），其次比 textEquals + className 组合。
     * 用于死循环检测：连续两次 click 同一节点时短路。
     */
    private fun sameTarget(a: AiUiTarget, b: AiUiTarget): Boolean {
        if (!a.viewId.isNullOrBlank() && a.viewId == b.viewId) return true
        if (a.viewId.isNullOrBlank() != b.viewId.isNullOrBlank()) return false
        return a.textEquals == b.textEquals &&
                a.textContains == b.textContains &&
                a.contentDescEquals == b.contentDescEquals &&
                a.contentDescContains == b.contentDescContains &&
                a.className == b.className
    }

    private fun formatTargetForLog(t: AiUiTarget): String {
        val parts = mutableListOf<String>()
        t.viewId?.let { parts.add("id=$it") }
        t.textEquals?.let { parts.add("text=$it") }
        t.textContains?.let { parts.add("text~$it") }
        t.contentDescEquals?.let { parts.add("desc=$it") }
        t.className?.let { parts.add("cls=$it") }
        return parts.joinToString(",").take(80).ifEmpty { "(?)" }
    }

    /**
     * **C 失败策略指纹**（aidoc/18 §3.C）：比 target hash 更广义的策略 key，让 AI 看到
     * "这个方向已经失败 N 次"——不止是同一 target，也包括"换 className 但其他字段不变" 的同方向尝试。
     *
     * key 包含 pkg + activity + actionType + 关键 target 字段。
     */
    private fun strategyKey(action: AiAgentAction, pkg: String?, activity: String?): String {
        val typeName = action::class.simpleName ?: "?"
        val targetPart = when (action) {
            is AiAgentAction.Click -> "click:${formatTargetForLog(action.target)}"
            is AiAgentAction.LongClick -> "long_click:${formatTargetForLog(action.target)}"
            is AiAgentAction.SetText -> "set_text:${formatTargetForLog(action.target)}|${action.text.take(20)}"
            is AiAgentAction.Scroll -> "scroll:${action.direction}:${action.target?.let { formatTargetForLog(it) } ?: "default"}"
            is AiAgentAction.LaunchApp -> "launch:${action.packageName}"
            else -> typeName
        }
        return "${pkg ?: "?"}|${activity ?: "?"}|$targetPart"
    }

    private fun strategyDescription(action: AiAgentAction, pkg: String?, activity: String?): String {
        val activityShort = activity?.substringAfterLast('.') ?: "?"
        val pkgShort = pkg?.substringAfterLast('.') ?: "?"
        return when (action) {
            is AiAgentAction.Click -> "click ${formatTargetForLog(action.target)} @ $pkgShort/$activityShort"
            is AiAgentAction.LongClick -> "long_click ${formatTargetForLog(action.target)} @ $pkgShort/$activityShort"
            is AiAgentAction.SetText -> "set_text \"${action.text.take(20)}\" → ${formatTargetForLog(action.target)} @ $pkgShort/$activityShort"
            is AiAgentAction.Scroll -> "scroll ${action.direction} @ $pkgShort/$activityShort"
            is AiAgentAction.LaunchApp -> "launch_app(${action.packageName})"
            else -> "${action::class.simpleName} @ $pkgShort/$activityShort"
        }
    }

    /**
     * 把一个失败的 step 记到 [failedStrategies]——后续 prompt 会显式列出，让 AI 不重复尝试。
     */
    private fun recordFailedStrategy(record: AiAgentStepRecord, activity: String?) {
        if (!actionMayChangeUi(record.action)) return  // wait/done/giveup 不算策略失败
        val key = strategyKey(record.action, record.snapshotPackage, activity)
        val existing = failedStrategies[key]
        if (existing != null) {
            existing.attemptCount++
            existing.lastDiagnostic = record.result.message?.take(120)
        } else {
            failedStrategies[key] = FailedStrategy(
                key = key,
                description = strategyDescription(record.action, record.snapshotPackage, activity),
                attemptCount = 1,
                lastDiagnostic = record.result.message?.take(120)
            )
        }
        AiAgentLog.d("session.failedStrategies", "记录失败策略：$key (累计 ${failedStrategies[key]!!.attemptCount} 次)")
    }

    /**
     * 统一的 target 哈希——格式跟 [formatTargetForLog] 一致，给 prompt 里的"禁选区"和
     * session 兜底拒收用。无 target 的动作（done/giveup/wait 等）返回 null。
     */
    private fun actionTargetHash(action: AiAgentAction): String? {
        val target = when (action) {
            is AiAgentAction.Click -> action.target
            is AiAgentAction.LongClick -> action.target
            is AiAgentAction.SetText -> action.target
            is AiAgentAction.Scroll -> action.target
            else -> null
        } ?: return null
        return formatTargetForLog(target)
    }

    /**
     * 计算 snapshot 的"指纹"——任何明显的页面切换都会让指纹变化，silent-fail 检测靠它判别。
     *
     * 指纹组成：
     * - packageName + activityName：跳转 Activity 必然命中
     * - 节点数 + 各类 flag 计数：弹窗 / 列表刷新会变
     * - 首尾节点的 viewId / className：节点顺序变了也能感知
     *
     * 不取节点 text 等长字段——一些 App 的状态栏时间会每分钟变，引入噪音。
     */
    private fun computeSnapshotSignature(snapshot: AiUiSnapshot): String {
        val nodes = snapshot.nodes
        val clickable = nodes.count { it.clickable }
        val editable = nodes.count { it.editable }
        val scrollable = nodes.count { it.scrollable }
        val first = nodes.firstOrNull()?.let { "${it.viewId.orEmpty()}|${it.className}" }.orEmpty()
        val last = nodes.lastOrNull()?.let { "${it.viewId.orEmpty()}|${it.className}" }.orEmpty()
        return "${snapshot.packageName}/${snapshot.activityName}#${nodes.size}c${clickable}e${editable}s${scrollable}|$first|$last"
    }

    /**
     * 给状态栏面板用的极简动作描述——一行就能放下。
     * 不调 [AiAgentOverlayController] 的 humanizeTarget，避免循环依赖；保持自己的轻量映射。
     */
    private fun describeActionShort(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "启动 ${action.packageName}"
        is AiAgentAction.Click -> "点击 ${formatTargetForLog(action.target)}"
        is AiAgentAction.LongClick -> "长按 ${formatTargetForLog(action.target)}"
        is AiAgentAction.SetText -> "输入「${action.text.take(20)}」到 ${formatTargetForLog(action.target)}"
        is AiAgentAction.Scroll -> "滚动 ${action.direction}"
        is AiAgentAction.Wait -> "等待 ${action.seconds}s"
        is AiAgentAction.GlobalBack -> "返回上一级"
        is AiAgentAction.GlobalHome -> "回到桌面"
        is AiAgentAction.Done -> "完成：${action.summary.take(40)}"
        is AiAgentAction.GiveUp -> "放弃：${action.reason.take(40)}"
        is AiAgentAction.Unknown -> "未识别"
    }

    /**
     * 该动作"按理"应该改变屏幕（点击 / 输入 / 滚动 / 返回 / 启动 App）。
     * 仅这些动作执行后屏幕没变化才报 silent-fail；wait 等等动作本就不改变屏幕，不算问题。
     */
    private fun actionMayChangeUi(action: AiAgentAction): Boolean = when (action) {
        is AiAgentAction.LaunchApp,
        is AiAgentAction.Click,
        is AiAgentAction.LongClick,
        is AiAgentAction.SetText,
        is AiAgentAction.Scroll,
        is AiAgentAction.GlobalBack,
        is AiAgentAction.GlobalHome -> true
        is AiAgentAction.Wait,
        is AiAgentAction.Done,
        is AiAgentAction.GiveUp,
        is AiAgentAction.Unknown -> false
    }

    private fun recordNoOpStep(
        action: AiAgentAction,
        snapshot: AiUiSnapshot?,
        ok: Boolean,
        message: String?
    ): AiAgentStepRecord {
        val record = AiAgentStepRecord(
            index = stepIndex,
            action = action,
            result = AiAgentStepResult(ok = ok, message = message),
            snapshotPackage = snapshot?.packageName
        )
        history += record
        callbacks.onStep(record)
        stepIndex++
        return record
    }
}

/**
 * 一些常见的"过场"包名，在 launch_app 之后短暂出现，不算"切到其他 App"。
 *
 * 注：很多 OEM 把 launcher 包名换成自己的（vivo `com.bbk.launcher2` 不带 launcher 子串、
 * 小米 `com.miui.home` 等），所以下面 [isLauncherOrSystem] 除了硬列名单还做后缀启发，
 * 仍然兜底不住时由 [AiAgentSession] 的"launcher 反弹宽容"机制再给 N 轮机会。
 */
private val LAUNCHER_OR_SYSTEM = setOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.android.launcher3",
    "com.miui.home",
    "com.huawei.android.launcher",
    "com.oppo.launcher",
    "com.vivo.launcher",
    "com.bbk.launcher2",                       // vivo OriginOS 真实 launcher 包名
    "com.bbk.launcher",
    "com.sec.android.app.launcher",
    "com.google.android.apps.nexuslauncher",
    "com.realme.launcher",
    "com.honor.launcher",
    "com.transsion.hilauncher",
    "android"
)

private fun isLauncherOrSystem(pkg: String): Boolean = pkg in LAUNCHER_OR_SYSTEM ||
        pkg.endsWith(".launcher") || pkg.endsWith(".launcher3") ||
        pkg.endsWith(".launcher2") || pkg.endsWith(".home") || pkg.endsWith(".systemui")

private fun AiAgentAction.requiredCapability(): top.xjunz.tasker.ai.model.AiCapability? = when (this) {
    is AiAgentAction.LaunchApp -> top.xjunz.tasker.ai.model.AiCapability.LaunchIntent
    is AiAgentAction.Click,
    is AiAgentAction.LongClick,
    is AiAgentAction.Scroll -> top.xjunz.tasker.ai.model.AiCapability.ClickUi
    is AiAgentAction.SetText -> top.xjunz.tasker.ai.model.AiCapability.InputText
    is AiAgentAction.GlobalBack,
    is AiAgentAction.GlobalHome -> top.xjunz.tasker.ai.model.AiCapability.ClickUi
    is AiAgentAction.Wait,
    is AiAgentAction.Done,
    is AiAgentAction.GiveUp,
    is AiAgentAction.Unknown -> null
}

/**
 * Session 的最终结果。所有分支都带 history，被以下三个**互不耦合**的下游消费：
 *
 * 1. 语音页 records 卡片实时审计（`VoiceCommandService.appendStepRecord` / `appendOutcomeRecord`）
 * 2. 结果通知正文拼装（`VoiceCommandService.notifyAgentOutcome`，channel `ai_agent_outcome`）
 * 3. **自动**沉淀到经验本（`AiAgentExperienceBook.recordSession`，agent 自身不感知此后续）
 *
 * Session 自身**不**直接产出可保存 XTask；草稿生成是**完全独立**的模块
 * （`ExperienceToTaskConverter`），只读经验本、由用户在 UI 主动触发，agent 既不调用也不依赖。
 * 详见 `aidoc/20-experience-book-design.md` §0 三模块解耦架构。
 */
sealed class AiAgentSessionOutcome {
    abstract val history: List<AiAgentStepRecord>

    data class Completed(
        val summary: String,
        override val history: List<AiAgentStepRecord>,
        val lastRecord: AiAgentStepRecord
    ) : AiAgentSessionOutcome()

    data class GivenUp(
        val reason: String,
        override val history: List<AiAgentStepRecord>,
        val lastRecord: AiAgentStepRecord
    ) : AiAgentSessionOutcome()

    data class LimitExceeded(
        override val history: List<AiAgentStepRecord>,
        val reason: String
    ) : AiAgentSessionOutcome()

    data class OutOfScope(
        override val history: List<AiAgentStepRecord>,
        val currentPackage: String
    ) : AiAgentSessionOutcome()

    data class PermissionDenied(
        val capability: top.xjunz.tasker.ai.model.AiCapability,
        override val history: List<AiAgentStepRecord>,
        val lastRecord: AiAgentStepRecord
    ) : AiAgentSessionOutcome()

    data class AiError(
        val reason: String,
        override val history: List<AiAgentStepRecord>,
        val lastRecord: AiAgentStepRecord
    ) : AiAgentSessionOutcome()

    data class Cancelled(
        override val history: List<AiAgentStepRecord>
    ) : AiAgentSessionOutcome()

    /**
     * 启动前 precheck 失败：当前 [top.xjunz.tasker.service.serviceController] 报告 service 没启动。
     * a11y 与 Shizuku 模式都没连，agent 完全无法读屏 / 派任务，直接终止避免浪费 token 跑空循环。
     */
    data class ServiceNotConnected(
        override val history: List<AiAgentStepRecord>
    ) : AiAgentSessionOutcome()
}

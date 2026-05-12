/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.overlay

import top.xjunz.tasker.ai.agent.AiUiTarget

/**
 * 决策面板的输出。每一步 agent action 在执行前都会通过 overlay 向用户征询，
 * 等待用户给出 [AiAgentDecision]。
 *
 * 设计要点：
 * - **不阻塞 session**：用 [kotlinx.coroutines.CompletableDeferred] 同步，session 协程 await 即可。
 * - **覆盖所有用户路径**：手动同意 / 手动拒绝 / 倒计时自动同意 / 倒计时自动拒绝 / 用户换节点 /
 *   overlay 不可用（未授权 SYSTEM_ALERT_WINDOW）静默跳过 / 不需要决策（wait / done / give_up）。
 * - **进 history**：写进 [top.xjunz.tasker.ai.agent.AiAgentStepRecord]，让 AI 在下一轮 prompt 里看到
 *   "上一步用户介入过"，自我学习。
 */
sealed interface AiAgentDecision {

    /** 用户主动按了"同意"按钮。 */
    data object ApprovedManual : AiAgentDecision

    /** 倒计时到时间没干预，按"默认同意"策略放行。仅当 [AiAgentConfirmMode.AutoApproveOnTimeout] 模式生效。 */
    data object ApprovedAuto : AiAgentDecision

    /**
     * 用户从候选列表里挑了一个真节点替换 AI 原选的 target。
     * @param newTarget 用户挑的节点的 [AiUiTarget]（由 [top.xjunz.tasker.task.inspector.shared.AiUiTargetExtractor] 生成）。
     * @param replacementHint 给 history / UI 显示的简短描述，例如 "用户换为 Button[text=发送]"。
     */
    data class Replaced(
        val newTarget: AiUiTarget,
        val replacementHint: String? = null
    ) : AiAgentDecision

    /**
     * 用户主动终止整个 session（按"终止"按钮）。
     * 不只是拒绝当前步——会让 [top.xjunz.tasker.ai.agent.AiAgentSession] 立刻 outcome `Cancelled`，
     * 整段 agent 操作流程中止。
     */
    data class Terminate(val reason: String? = null) : AiAgentDecision

    /**
     * 不需要走决策面板（wait / done / give_up / Unknown 等无 UI 副作用动作），直接放行。
     * 也用于"用户彻底关闭决策面板"或"overlay 未授权"两种全自动跑的场景。
     */
    data object Skipped : AiAgentDecision
}

/**
 * 决策模式偏好。用户在 AI 配置弹窗里选一个，写入 [top.xjunz.tasker.Preferences]。
 *
 * - [Disabled]：完全不弹决策面板，agent 全自动跑（仍受 [top.xjunz.tasker.ai.agent.AiTaskScope] 边界约束）。
 * - [AutoApproveOnTimeout]：弹面板，倒计时到自动同意。**默认**，平衡自动化与人工把关。
 * - [WaitForUserAfterTimeout]：弹面板，倒计时归零后**不自动决策**，停留等用户主动选。
 *   适合"我没看完不要替我做决定"的人。
 */
enum class AiAgentConfirmMode(val rawValue: String) {
    Disabled("disabled"),
    AutoApproveOnTimeout("auto_approve"),
    WaitForUserAfterTimeout("wait_for_user");

    companion object {
        fun parse(raw: String?): AiAgentConfirmMode {
            if (raw.isNullOrBlank()) return AutoApproveOnTimeout
            return entries.firstOrNull { it.rawValue == raw } ?: AutoApproveOnTimeout
        }
    }
}

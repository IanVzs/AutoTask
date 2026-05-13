/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiAgentPlanStatus
import top.xjunz.tasker.ai.agent.AiAgentSessionOutcome
import top.xjunz.tasker.ai.agent.AiAgentStepRecord

/**
 * 把 session 的 history + outcome 提炼成"AI 下次能直接读懂"的自然语言学习点。
 * **纯本地启发式**，不调 LLM。
 *
 * 三类输出：
 * - [keyLearnings]：成功且在轨 (on_track / adjusted) 的关键节点动作 + 文字描述
 * - [failureTrapsAvoided]：失败 / 偏轨 / silent-fail 的步骤，标注"为何不要这么做"
 * - 各类计数 / 摘要字段供索引使用
 */
internal object ExperienceLearningExtractor {

    data class Extracted(
        val keyLearnings: List<String>,
        val failureTrapsAvoided: List<String>,
        val planStatusDistribution: Map<String, Int>,
        val failCount: Int
    )

    fun extract(
        outcome: AiAgentSessionOutcome,
        history: List<AiAgentStepRecord>
    ): Extracted {
        val statusDist = history.groupingBy {
            AiAgentPlanStatus.parse(it.action.planStatus).name
        }.eachCount()
        val failCount = history.count { !it.result.ok } +
                if (outcome.isFailure()) 1 else 0

        val keyLearnings = mutableListOf<String>()
        val failureTraps = mutableListOf<String>()
        val seenSuccessKeys = mutableSetOf<String>()
        val seenFailKeys = mutableSetOf<String>()

        history.forEach { rec ->
            val action = rec.action
            val ok = rec.result.ok
            val status = AiAgentPlanStatus.parse(action.planStatus)
            val description = describeAction(action)
            val pkg = rec.snapshotPackage?.let { "[$it] " }.orEmpty()
            val msg = rec.result.message?.takeIf { it.isNotBlank() }
            val reflection = rec.reflection?.takeIf { it.isNotBlank() }

            when {
                ok && (status == AiAgentPlanStatus.OnTrack || status == AiAgentPlanStatus.Adjusted) -> {
                    val key = stableKey(action)
                    if (seenSuccessKeys.add(key) && keyLearnings.size < MAX_KEY_LEARNINGS) {
                        val line = buildString {
                            append(pkg)
                            append(description)
                            if (reflection != null) {
                                append("（理由：")
                                append(reflection.take(80))
                                append("）")
                            }
                        }
                        keyLearnings += line
                    }
                }

                !ok || status == AiAgentPlanStatus.OffTrack -> {
                    val key = stableKey(action)
                    if (seenFailKeys.add(key) && failureTraps.size < MAX_FAILURE_TRAPS) {
                        val line = buildString {
                            append(pkg)
                            append(description)
                            append(" → ")
                            append(if (ok) "off_track" else "FAIL")
                            msg?.let {
                                append("（")
                                append(it.take(80))
                                append("）")
                            }
                        }
                        failureTraps += line
                    }
                }
            }
        }

        return Extracted(
            keyLearnings = keyLearnings,
            failureTrapsAvoided = failureTraps,
            planStatusDistribution = statusDist,
            failCount = failCount
        )
    }

    private fun describeAction(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch_app ${action.packageName}"
        is AiAgentAction.Click -> "click ${formatTarget(action.target)}"
        is AiAgentAction.LongClick -> "long_click ${formatTarget(action.target)}"
        is AiAgentAction.SetText -> "set_text → ${formatTarget(action.target)}"
        is AiAgentAction.Scroll -> "scroll ${action.direction}" +
                (action.target?.let { " on ${formatTarget(it)}" } ?: "")
        is AiAgentAction.Wait -> "wait ${action.seconds}s"
        is AiAgentAction.GlobalBack -> "global_back"
        is AiAgentAction.GlobalHome -> "global_home"
        is AiAgentAction.Done -> "done: ${action.summary.take(40)}"
        is AiAgentAction.GiveUp -> "give_up: ${action.reason.take(40)}"
        is AiAgentAction.Unknown -> "unknown"
    }

    private fun formatTarget(t: top.xjunz.tasker.ai.agent.AiUiTarget): String {
        val parts = mutableListOf<String>()
        t.viewId?.let { parts.add("id=$it") }
        t.textEquals?.let { parts.add("text=\"${it.take(20)}\"") }
        t.textContains?.let { parts.add("text~\"${it.take(20)}\"") }
        t.contentDescEquals?.let { parts.add("desc=\"${it.take(20)}\"") }
        t.contentDescContains?.let { parts.add("desc~\"${it.take(20)}\"") }
        t.className?.let { parts.add("cls=$it") }
        return parts.joinToString(",").take(80).ifEmpty { "(?)" }
    }

    /** 用于 dedupe：同 actionType + 关键 target 字段视为"同一类经验"，避免 N 步同动作刷屏。 */
    private fun stableKey(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch_app|${action.packageName}"
        is AiAgentAction.Click -> "click|${targetKey(action.target)}"
        is AiAgentAction.LongClick -> "long_click|${targetKey(action.target)}"
        is AiAgentAction.SetText -> "set_text|${targetKey(action.target)}"
        is AiAgentAction.Scroll -> "scroll|${action.direction}|${action.target?.let(::targetKey) ?: ""}"
        is AiAgentAction.Wait -> "wait|${action.seconds}"
        is AiAgentAction.GlobalBack -> "global_back"
        is AiAgentAction.GlobalHome -> "global_home"
        is AiAgentAction.Done -> "done"
        is AiAgentAction.GiveUp -> "give_up"
        is AiAgentAction.Unknown -> "unknown|${action.raw.hashCode()}"
    }

    private fun targetKey(t: top.xjunz.tasker.ai.agent.AiUiTarget): String =
        listOfNotNull(
            t.viewId?.let { "id=$it" },
            t.textEquals?.let { "text=$it" },
            t.textContains?.let { "text~$it" },
            t.contentDescEquals?.let { "desc=$it" },
            t.contentDescContains?.let { "desc~$it" },
            t.className?.let { "cls=$it" }
        ).joinToString(",")

    private fun AiAgentSessionOutcome.isFailure(): Boolean = when (this) {
        is AiAgentSessionOutcome.Completed -> false
        else -> true
    }

    private const val MAX_KEY_LEARNINGS = 8
    private const val MAX_FAILURE_TRAPS = 8
}

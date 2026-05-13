/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiAgentPlanStatus
import top.xjunz.tasker.ai.agent.AiAgentSessionOutcome
import top.xjunz.tasker.ai.agent.AiAgentSessionPlan
import top.xjunz.tasker.ai.agent.AiAgentStepRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString

/**
 * 把 session 结束时的所有信息写成一个自包含 txt 文件。
 *
 * 格式：先 markdown 可读段落（人类 + AI 阅读用），再 ` ```json {...} ``` ` 嵌块（机器解析用，
 * 反序列化得到 [ExperienceFile]）。这样：
 * - AI 在 prompt 里只读 markdown，省 token
 * - UI / 转草稿 / 召回打分用 json 块拿到完整结构
 * - 用户直接打开文件能看到完整笔记，可手改 markdown 部分
 */
internal object ExperienceFileWriter {

    private const val JSON_FENCE_OPEN = "```json"
    private const val JSON_FENCE_CLOSE = "```"

    private val fileNameFormat by lazy {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
    private val readableTimeFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    data class WriteResult(
        val file: File,
        val indexEntry: ExperienceIndexEntry,
        val experienceFile: ExperienceFile
    )

    fun build(
        sessionId: String,
        userGoal: String,
        targetApps: Set<String>,
        plan: AiAgentSessionPlan?,
        outcome: AiAgentSessionOutcome,
        outcomeLabel: String,
        outcomeDetail: String,
        history: List<AiAgentStepRecord>,
        startedAtMillis: Long,
        finishedAtMillis: Long
    ): ExperienceFile {
        val extracted = ExperienceLearningExtractor.extract(outcome, history)
        val targetAppPackage = plan?.targetAppPackage ?: targetApps.firstOrNull()
        val targetAppLabel = plan?.targetAppLabel
        val steps = history.map { rec -> rec.toStep() }
        val convertibleToTask = outcome is AiAgentSessionOutcome.Completed &&
                steps.any { it.convertible }
        return ExperienceFile(
            sessionId = sessionId,
            finishedAtMillis = finishedAtMillis,
            durationMs = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L),
            targetAppPackage = targetAppPackage,
            targetAppLabel = targetAppLabel,
            userGoal = userGoal,
            goalKeywords = ExperienceKeywordExtractor.extract(userGoal),
            outcome = outcome::class.simpleName ?: "Unknown",
            outcomeLabel = outcomeLabel,
            outcomeDetail = outcomeDetail,
            planSummary = plan?.summary,
            planEstimatedSteps = plan?.estimatedSteps,
            planStatusDistribution = extracted.planStatusDistribution,
            keyLearnings = extracted.keyLearnings,
            failureTrapsAvoided = extracted.failureTrapsAvoided,
            convertibleToTask = convertibleToTask,
            steps = steps
        )
    }

    fun writeToDir(dir: File, exp: ExperienceFile): WriteResult {
        if (!dir.exists()) dir.mkdirs()
        val ts = Date(exp.finishedAtMillis)
        val shortId = exp.sessionId.takeLast(4).ifEmpty { "0000" }
        val name = "${fileNameFormat.format(ts)}_${shortId}.txt"
        val target = File(dir, name)
        val markdown = renderMarkdown(exp)
        val json = AiJson.encodeToString(exp)
        val body = buildString {
            append(markdown)
            appendLine()
            appendLine()
            appendLine("## Structured (machine-readable JSON, do NOT edit by hand)")
            appendLine(JSON_FENCE_OPEN)
            appendLine(json)
            appendLine(JSON_FENCE_CLOSE)
        }
        target.writeText(body, Charsets.UTF_8)
        val sizeBytes = target.length()
        val indexEntry = ExperienceIndexEntry(
            filename = name,
            sessionId = exp.sessionId,
            finishedAtMillis = exp.finishedAtMillis,
            targetAppPackage = exp.targetAppPackage,
            targetAppLabel = exp.targetAppLabel,
            userGoal = exp.userGoal,
            goalKeywords = exp.goalKeywords,
            outcome = exp.outcome,
            outcomeLabel = exp.outcomeLabel,
            stepCount = exp.steps.size,
            failCount = exp.steps.count { !it.ok },
            durationMs = exp.durationMs,
            convertibleToTask = exp.convertibleToTask,
            sizeBytes = sizeBytes
        )
        return WriteResult(target, indexEntry, exp)
    }

    private fun renderMarkdown(exp: ExperienceFile): String = buildString {
        appendLine("# AI Agent Experience · ${readableTimeFormat.format(Date(exp.finishedAtMillis))}")
        appendLine()
        appendLine("## Metadata")
        appendLine("- session_id: ${exp.sessionId}")
        appendLine("- finished_at: ${readableTimeFormat.format(Date(exp.finishedAtMillis))}")
        appendLine(
            "- target_app: ${exp.targetAppPackage ?: "—"}" +
                    (exp.targetAppLabel?.let { " ($it)" } ?: "")
        )
        appendLine("- user_goal: ${exp.userGoal}")
        appendLine("- outcome: ${exp.outcome}")
        appendLine("- outcome_label: ${exp.outcomeLabel}")
        appendLine("- duration_ms: ${exp.durationMs}")
        appendLine("- total_steps: ${exp.steps.size}")
        appendLine("- fail_steps: ${exp.steps.count { !it.ok }}")
        if (exp.planStatusDistribution.isNotEmpty()) {
            appendLine(
                "- plan_status: " + exp.planStatusDistribution.entries.joinToString(" ") {
                    "${it.key.lowercase()}=${it.value}"
                }
            )
        }
        appendLine("- convertible_to_task: ${exp.convertibleToTask}")
        if (exp.goalKeywords.isNotEmpty()) {
            appendLine("- goal_keywords: ${exp.goalKeywords.joinToString(", ")}")
        }
        if (!exp.planSummary.isNullOrBlank()) {
            appendLine()
            appendLine("## Plan Summary")
            appendLine(exp.planSummary)
        }
        appendLine()
        appendLine("## Final Outcome")
        appendLine(exp.outcomeDetail)

        if (exp.keyLearnings.isNotEmpty()) {
            appendLine()
            appendLine("## Key Learnings")
            exp.keyLearnings.forEach { appendLine("- $it") }
        }
        if (exp.failureTrapsAvoided.isNotEmpty()) {
            appendLine()
            appendLine("## Failure Traps")
            exp.failureTrapsAvoided.forEach { appendLine("- $it") }
        }

        if (exp.steps.isNotEmpty()) {
            appendLine()
            appendLine("## Step Trace")
            exp.steps.forEach { step ->
                val status = step.planStatus.lowercase()
                val okMark = if (step.ok) "OK" else "FAIL"
                appendLine("[${"%02d".format(step.index + 1)}] [$status] ${step.actionDescription} → $okMark")
                step.observation?.let { appendLine("     observation: ${it.take(160)}") }
                step.lastActionReview?.let { appendLine("     last_review: ${it.take(160)}") }
                step.reflection?.let { appendLine("     reflection: ${it.take(160)}") }
                step.matchedNodeSummary?.let { appendLine("     matched: $it") }
                step.resultMessage?.takeIf { it.isNotBlank() }?.let { appendLine("     msg: ${it.take(200)}") }
            }
        }
    }.trimEnd()

    /**
     * 把运行时 [AiAgentStepRecord] 转换成可序列化的 [ExperienceStep]。
     * 关键脱敏：set_text 实际内容不写盘（仅记 [hasTextInput=true]），target 字段统一过 [ExperienceRedactor]。
     */
    private fun AiAgentStepRecord.toStep(): ExperienceStep {
        val planStatusEnum = AiAgentPlanStatus.parse(action.planStatus)
        val baseTarget: top.xjunz.tasker.ai.agent.AiUiTarget? = when (val a = action) {
            is AiAgentAction.Click -> a.target
            is AiAgentAction.LongClick -> a.target
            is AiAgentAction.SetText -> a.target
            is AiAgentAction.Scroll -> a.target
            else -> null
        }
        val redactedTarget = ExperienceRedactor.redactTarget(baseTarget)
        val actionType = when (action) {
            is AiAgentAction.LaunchApp -> "launch_app"
            is AiAgentAction.Click -> "click"
            is AiAgentAction.LongClick -> "long_click"
            is AiAgentAction.SetText -> "set_text"
            is AiAgentAction.Wait -> "wait"
            is AiAgentAction.Scroll -> "scroll"
            is AiAgentAction.GlobalBack -> "global_back"
            is AiAgentAction.GlobalHome -> "global_home"
            is AiAgentAction.Done -> "done"
            is AiAgentAction.GiveUp -> "give_up"
            is AiAgentAction.Unknown -> "unknown"
        }
        val description = ExperienceLearningExtractorPublicApi.describeAction(action)
        val pkg = (action as? AiAgentAction.LaunchApp)?.packageName
        val direction = (action as? AiAgentAction.Scroll)?.direction
        val seconds = (action as? AiAgentAction.Wait)?.seconds
        val convertible = when (action) {
            is AiAgentAction.LaunchApp -> true
            is AiAgentAction.Click -> result.ok && redactedTarget != null && !redactedTarget.isEmpty
            is AiAgentAction.LongClick -> result.ok && redactedTarget != null && !redactedTarget.isEmpty
            is AiAgentAction.SetText -> result.ok && redactedTarget != null && !redactedTarget.isEmpty
            is AiAgentAction.GlobalBack -> result.ok
            is AiAgentAction.GlobalHome -> result.ok
            else -> false
        }
        return ExperienceStep(
            index = index,
            planStatus = planStatusEnum.name,
            actionType = actionType,
            actionDescription = description,
            target = redactedTarget,
            packageName = pkg,
            direction = direction,
            seconds = seconds,
            hasTextInput = action is AiAgentAction.SetText,
            ok = result.ok,
            resultMessage = ExperienceRedactor.redactText(result.message),
            matchedNodeSummary = ExperienceRedactor.redactText(result.matchedNodeSummary),
            snapshotPackage = snapshotPackage,
            observation = ExperienceRedactor.redactText(observation),
            reflection = ExperienceRedactor.redactText(reflection),
            lastActionReview = ExperienceRedactor.redactText(lastActionReview),
            convertible = convertible
        )
    }

    /**
     * 把内嵌 ` ```json ... ``` ` 块从 txt 内容里抠出来。返回 null = 没找到 / 解析失败。
     */
    fun parseJsonBlock(content: String): ExperienceFile? {
        val openIdx = content.lastIndexOf(JSON_FENCE_OPEN)
        if (openIdx < 0) return null
        val afterOpen = openIdx + JSON_FENCE_OPEN.length
        val closeIdx = content.indexOf(JSON_FENCE_CLOSE, startIndex = afterOpen)
        if (closeIdx < 0) return null
        val raw = content.substring(afterOpen, closeIdx).trim()
        return runCatching { AiJson.decodeFromString(ExperienceFile.serializer(), raw) }.getOrNull()
    }
}

/**
 * [ExperienceLearningExtractor] 的描述函数本来是 private —— 这里给 writer 暴露一个最小通道，
 * 避免重复实现。其它模块不要直接用本类。
 */
internal object ExperienceLearningExtractorPublicApi {
    fun describeAction(action: AiAgentAction): String = when (action) {
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
}

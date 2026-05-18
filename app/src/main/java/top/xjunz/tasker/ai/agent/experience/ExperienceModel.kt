/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.xjunz.tasker.ai.agent.AiUiTarget

/**
 * 单条经验在磁盘上的**结构化序列化 schema**（与可读 markdown 文本一起塞进同一个 txt 文件，
 * 写入时 markdown 在前、json 在尾部 ` ```json ... ``` ` 块里）。
 *
 * 这层 DTO 跟运行时的 [top.xjunz.tasker.ai.agent.AiAgentStepRecord] / [AiUiTarget] 解耦：
 * - 运行时数据是 sealed class + data class，加 @Serializable 会牵动 polymorphism 配置
 * - 写盘格式要稳定可读、可手改、可在不动业务代码的前提下加 / 减字段
 *
 * 字段命名上下文一致使用 snake_case（json 里），属性用 camelCase（kotlin 侧），靠 [SerialName] 桥接。
 */
@Serializable
data class ExperienceFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,

    @SerialName("session_id") val sessionId: String,
    @SerialName("finished_at_millis") val finishedAtMillis: Long,
    @SerialName("duration_ms") val durationMs: Long,

    @SerialName("target_app_package") val targetAppPackage: String? = null,
    @SerialName("target_app_label") val targetAppLabel: String? = null,
    @SerialName("user_goal") val userGoal: String,
    @SerialName("goal_keywords") val goalKeywords: List<String> = emptyList(),

    /** [AiAgentSessionOutcome] 的 sealed class simpleName，用于 UI 着色与"是否可转草稿"判断。 */
    val outcome: String,
    @SerialName("outcome_label") val outcomeLabel: String,
    @SerialName("outcome_detail") val outcomeDetail: String,

    @SerialName("plan_summary") val planSummary: String? = null,
    @SerialName("plan_estimated_steps") val planEstimatedSteps: Int? = null,
    @SerialName("plan_status_distribution")
    val planStatusDistribution: Map<String, Int> = emptyMap(),

    @SerialName("key_learnings") val keyLearnings: List<String> = emptyList(),
    @SerialName("failure_traps_avoided") val failureTrapsAvoided: List<String> = emptyList(),

    /**
     * outcome=Completed 且 steps 中含至少一个 [convertible=true] 的步骤时为 true。
     * UI 用它判断是否露出"一键转草稿"按钮。
     */
    @SerialName("convertible_to_task") val convertibleToTask: Boolean = false,

    val steps: List<ExperienceStep> = emptyList()
)

/**
 * 单步在磁盘上的快照。仅保留 AI 决策可见 + UI 展示 + 转草稿所需字段，**不**回写：
 * - set_text 实际内容（`hasTextInput=true` 占位即可）
 * - target 中命中的敏感正则文本（已被 [ExperienceRedactor] 替换为 `<redacted>`）
 * - 完整 snapshot（节点列表，仅留 packageName / activityName 摘要级别）
 */
@Serializable
data class ExperienceStep(
    val index: Int,
    @SerialName("plan_status") val planStatus: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("action_description") val actionDescription: String,
    val target: AiUiTarget? = null,
    @SerialName("package_name") val packageName: String? = null,
    val direction: String? = null,
    val seconds: Int? = null,
    @SerialName("has_text_input") val hasTextInput: Boolean = false,
    val ok: Boolean,
    @SerialName("result_message") val resultMessage: String? = null,
    @SerialName("matched_node_summary") val matchedNodeSummary: String? = null,
    @SerialName("snapshot_package") val snapshotPackage: String? = null,
    val observation: String? = null,
    val reflection: String? = null,
    @SerialName("last_action_review") val lastActionReview: String? = null,
    /** 该单步是否能被 [ExperienceToTaskConverter] 翻译成 task applet（launch/click/long_click/global_*）。 */
    val convertible: Boolean = false
)

/**
 * `index.json` 的整文件结构：版本号 + 所有经验条目的轻量元数据。
 * 不放正文（正文在各自的 txt 文件里），保证整文件一次 IO 可加载、能挂在内存做检索。
 */
@Serializable
data class ExperienceIndex(
    val version: Int = 1,
    val entries: List<ExperienceIndexEntry> = emptyList()
)

@Serializable
data class ExperienceIndexEntry(
    val filename: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("finished_at_millis") val finishedAtMillis: Long,
    @SerialName("target_app_package") val targetAppPackage: String? = null,
    @SerialName("target_app_label") val targetAppLabel: String? = null,
    @SerialName("user_goal") val userGoal: String,
    @SerialName("goal_keywords") val goalKeywords: List<String> = emptyList(),
    val outcome: String,
    @SerialName("outcome_label") val outcomeLabel: String,
    @SerialName("step_count") val stepCount: Int,
    @SerialName("fail_count") val failCount: Int,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("convertible_to_task") val convertibleToTask: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long
)

/**
 * 召回结果：携带索引元数据 + 已经从 txt 解析出的结构化全文（用于注入 prompt）。
 */
data class ExperienceRecallEntry(
    val index: ExperienceIndexEntry,
    val file: ExperienceFile,
    val score: Double
)

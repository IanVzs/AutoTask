/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class AiIntent(
    val type: AiIntentType,
    val rawText: String,
    val confidence: Float = 0f,
    val slots: Map<String, String> = emptyMap()
)

@Serializable
enum class AiIntentType {
    RunExistingTask,
    CreateTaskDraft,
    ModifyTaskDraft,
    DiagnoseRun,
    ConfigureAi,
    AskClarification,
    Unknown
}

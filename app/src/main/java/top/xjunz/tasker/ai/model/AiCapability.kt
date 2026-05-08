/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.model

import kotlinx.serialization.Serializable

@Serializable
enum class AiCapability {
    UnderstandText,
    MatchExistingTask,
    CreateTaskDraft,
    ModifyTaskDraft,
    ExecuteExistingTask,
    ClickUi,
    InputText,
    ReadNodeTree,
    ReadScreenText,
    ReadTaskList,
    ReadTaskSnapshot,
    UseNetworkModel,
    TakeScreenshot,
    RunShell,
    ManageFiles,
    LaunchIntent,
    ForceStopApp
}

@Serializable
enum class AiRiskLevel(val severity: Int) {
    Low(0),
    Medium(1),
    High(2),
    Critical(3);

    fun allows(other: AiRiskLevel): Boolean {
        return severity >= other.severity
    }

    companion object {
        fun maxOf(levels: Iterable<AiRiskLevel>): AiRiskLevel {
            return levels.maxByOrNull { it.severity } ?: Low
        }
    }
}

@Serializable
enum class SensitiveDataType {
    VoiceText,
    TaskList,
    TaskSnapshot,
    NodeTree,
    ScreenText,
    Screenshot,
    Clipboard,
    Notification,
    FileContent,
    AccountOrPayment
}

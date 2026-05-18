/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.audit

import kotlinx.serialization.Serializable
import top.xjunz.tasker.ai.model.AiActionPlan
import top.xjunz.tasker.ai.model.AiIntent
import top.xjunz.tasker.ai.model.AiRiskAssessment

@Serializable
data class AiDecisionRecord(
    val id: String,
    val timestamp: Long,
    val source: AiDecisionSource,
    val userGoal: String,
    val modelName: String? = null,
    val intent: AiIntent? = null,
    val actionPlan: AiActionPlan? = null,
    val riskAssessment: AiRiskAssessment,
    val matchedGrantIds: List<String> = emptyList(),
    val userDecision: AiUserDecision? = null,
    val executionResult: AiExecutionResult? = null,
    val error: String? = null
)

@Serializable
enum class AiDecisionSource {
    Voice,
    Editor,
    Inspector,
    Diagnosis,
    Manual
}

@Serializable
enum class AiUserDecision {
    Accepted,
    Rejected,
    GrantedOnce,
    GrantedPersistently,
    Cancelled
}

@Serializable
data class AiExecutionResult(
    val status: AiExecutionStatus,
    val message: String? = null,
    val taskId: String? = null,
    val snapshotId: String? = null
)

@Serializable
enum class AiExecutionStatus {
    NotStarted,
    Succeeded,
    Failed,
    Cancelled,
    RejectedByPolicy
}

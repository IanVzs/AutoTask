/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class AiActionPlan(
    val id: String,
    val userGoal: String,
    val intent: AiIntent,
    val steps: List<AiActionStep>,
    val scope: AiScope = AiScope.Any,
    val summary: String = userGoal
) {
    val requiredCapabilities: Set<AiCapability>
        get() = steps.flatMap { it.requiredCapabilities }.toSet()
}

@Serializable
data class AiActionStep(
    val id: String,
    val title: String,
    val description: String,
    val requiredCapabilities: Set<AiCapability>,
    val riskLevel: AiRiskLevel,
    val sensitiveDataTypes: Set<SensitiveDataType> = emptySet(),
    val reversible: Boolean = true
)

@Serializable
data class AiTaskDraft(
    val title: String,
    val summary: String,
    val steps: List<AiTaskDraftStep>,
    val sourcePlanId: String? = null
)

@Serializable
data class AiTaskDraftStep(
    val title: String,
    val description: String,
    val suggestedCapabilities: Set<AiCapability> = emptySet(),
    val riskLevel: AiRiskLevel = AiRiskLevel.Low
)

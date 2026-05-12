/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class AiRiskAssessment(
    val riskLevel: AiRiskLevel,
    val requiredCapabilities: Set<AiCapability>,
    val sensitiveDataTypes: Set<SensitiveDataType>,
    val reasons: List<String>,
    val requiresConfirmation: Boolean
)

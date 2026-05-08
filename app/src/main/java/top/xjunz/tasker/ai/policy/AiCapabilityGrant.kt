/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

import kotlinx.serialization.Serializable
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiRiskLevel
import top.xjunz.tasker.ai.model.AiScope

@Serializable
data class AiCapabilityGrant(
    val id: String,
    val capability: AiCapability,
    val scope: AiScope,
    val maxRisk: AiRiskLevel,
    val confirmationPolicy: AiConfirmationPolicy,
    val expiresAt: Long? = null,
    val costLimit: AiCostLimit? = null,
    val enabled: Boolean = true
) {
    fun isActive(now: Long): Boolean {
        return enabled && (expiresAt == null || expiresAt > now)
    }

    fun covers(
        capability: AiCapability,
        scope: AiScope,
        riskLevel: AiRiskLevel,
        now: Long
    ): Boolean {
        return isActive(now) &&
                this.capability == capability &&
                this.scope.covers(scope) &&
                maxRisk.allows(riskLevel)
    }
}

@Serializable
enum class AiConfirmationPolicy {
    AlwaysAsk,
    AskForHighRisk,
    AskOnceThenAllow,
    AllowWithinGrant
}

@Serializable
data class AiCostLimit(
    val maxRequestsPerDay: Int? = null,
    val maxTokensPerDay: Int? = null,
    val wifiOnly: Boolean = false
)

/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

import top.xjunz.tasker.ai.model.AiActionPlan
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiRiskAssessment

class AiActionGate(
    private val grantStore: AiGrantStore,
    private val riskAssessor: AiRiskAssessor = AiRiskAssessor(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun review(plan: AiActionPlan): AiGateResult {
        val assessment = riskAssessor.assess(plan)
        val now = clock()
        val grants = grantStore.listGrants()
        // 每条能力按它自身的风险等级查找授权，而不是用整个计划的最高风险。
        // 否则一旦计划里掺入一个 Medium 能力，所有 Low 能力的授权都会失效。
        val matchedGrants = assessment.requiredCapabilities.mapNotNull { capability ->
            val capabilityRisk = riskAssessor.riskOf(capability)
            grants.firstOrNull {
                it.covers(capability, plan.scope, capabilityRisk, now)
            }
        }
        val missingCapabilities = assessment.requiredCapabilities
            .filterNot { capability -> matchedGrants.any { it.capability == capability } }
            .toSet()

        if (missingCapabilities.isNotEmpty()) {
            return AiGateResult(
                status = AiGateStatus.RequiresGrant,
                assessment = assessment,
                matchedGrants = matchedGrants,
                missingCapabilities = missingCapabilities
            )
        }

        val requiresConfirmation = matchedGrants.any {
            it.confirmationPolicy.requiresConfirmation(assessment)
        }

        return AiGateResult(
            status = if (requiresConfirmation) {
                AiGateStatus.RequiresConfirmation
            } else {
                AiGateStatus.Allowed
            },
            assessment = assessment,
            matchedGrants = matchedGrants,
            missingCapabilities = emptySet()
        )
    }

    private fun AiConfirmationPolicy.requiresConfirmation(
        assessment: AiRiskAssessment
    ): Boolean {
        return when (this) {
            AiConfirmationPolicy.AlwaysAsk -> true
            AiConfirmationPolicy.AskForHighRisk -> assessment.riskLevel.severity >= 2
            AiConfirmationPolicy.AskOnceThenAllow -> false
            AiConfirmationPolicy.AllowWithinGrant -> false
        }
    }
}

data class AiGateResult(
    val status: AiGateStatus,
    val assessment: AiRiskAssessment,
    val matchedGrants: List<AiCapabilityGrant>,
    val missingCapabilities: Set<AiCapability>
) {
    val isAllowed: Boolean get() = status == AiGateStatus.Allowed
}

enum class AiGateStatus {
    Allowed,
    RequiresConfirmation,
    RequiresGrant
}

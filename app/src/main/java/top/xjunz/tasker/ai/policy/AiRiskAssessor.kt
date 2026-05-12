/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

import top.xjunz.tasker.ai.model.AiActionPlan
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiRiskAssessment
import top.xjunz.tasker.ai.model.AiRiskLevel
import top.xjunz.tasker.ai.model.SensitiveDataType

class AiRiskAssessor(
    private val capabilityRiskOverrides: Map<AiCapability, AiRiskLevel> = emptyMap()
) {

    fun assess(plan: AiActionPlan): AiRiskAssessment {
        val capabilityRisks = plan.requiredCapabilities.map(::riskOf)
        val stepRisks = plan.steps.map { it.riskLevel }
        val riskLevel = AiRiskLevel.maxOf(capabilityRisks + stepRisks)
        val sensitiveDataTypes = plan.steps.flatMap { it.sensitiveDataTypes }.toSet()
        val reasons = buildList {
            add("Plan has ${plan.steps.size} step(s).")
            if (plan.requiredCapabilities.isNotEmpty()) {
                add("Requires ${plan.requiredCapabilities.size} AI capability grant(s).")
            }
            if (sensitiveDataTypes.isNotEmpty()) {
                add("Touches sensitive data: ${sensitiveDataTypes.joinToString()}.")
            }
            plan.steps.filter { !it.reversible }.forEach {
                add("Step '${it.title}' is not easily reversible.")
            }
        }
        return AiRiskAssessment(
            riskLevel = riskLevel,
            requiredCapabilities = plan.requiredCapabilities,
            sensitiveDataTypes = sensitiveDataTypes,
            reasons = reasons,
            requiresConfirmation = riskLevel.severity >= AiRiskLevel.Medium.severity
        )
    }

    /**
     * 单个能力的默认风险等级。授权门禁会按这个粒度检查每条能力，
     * 而不是用整个计划的最高风险，这样低风险能力的授权能正确覆盖。
     */
    fun riskOf(capability: AiCapability): AiRiskLevel {
        return capabilityRiskOverrides[capability] ?: DEFAULT_CAPABILITY_RISKS.getValue(capability)
    }

    companion object {
        val DEFAULT_CAPABILITY_RISKS = mapOf(
            AiCapability.UnderstandText to AiRiskLevel.Low,
            AiCapability.MatchExistingTask to AiRiskLevel.Low,
            AiCapability.CreateTaskDraft to AiRiskLevel.Low,
            AiCapability.ReadTaskSnapshot to AiRiskLevel.Low,
            AiCapability.UseNetworkModel to AiRiskLevel.Medium,
            AiCapability.ExecuteExistingTask to AiRiskLevel.Medium,
            AiCapability.ModifyTaskDraft to AiRiskLevel.Medium,
            AiCapability.ClickUi to AiRiskLevel.Medium,
            AiCapability.InputText to AiRiskLevel.Medium,
            AiCapability.ReadTaskList to AiRiskLevel.Medium,
            AiCapability.LaunchIntent to AiRiskLevel.Medium,
            AiCapability.ReadNodeTree to AiRiskLevel.High,
            AiCapability.ReadScreenText to AiRiskLevel.High,
            AiCapability.TakeScreenshot to AiRiskLevel.High,
            AiCapability.ManageFiles to AiRiskLevel.Critical,
            AiCapability.RunShell to AiRiskLevel.Critical,
            AiCapability.ForceStopApp to AiRiskLevel.Critical
        )
    }
}

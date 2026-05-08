/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai

import top.xjunz.tasker.ai.audit.AiAuditStore
import top.xjunz.tasker.ai.audit.PreferencesAiAuditStore
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiRiskLevel
import top.xjunz.tasker.ai.model.AiScope
import top.xjunz.tasker.ai.policy.AiActionGate
import top.xjunz.tasker.ai.policy.AiCapabilityGrant
import top.xjunz.tasker.ai.policy.AiConfirmationPolicy
import top.xjunz.tasker.ai.policy.AiGrantStore
import top.xjunz.tasker.ai.policy.AiRiskAssessor
import top.xjunz.tasker.ai.policy.PreferencesAiGrantStore
import top.xjunz.tasker.app

/**
 * AI 子系统的全局入口：负责装配持久化的授权 / 审计存储，以及统一的行动门禁。
 *
 * 所有进入主流程的 AI 决策都应该通过 [actionGate] 检查，并把结果写入 [auditStore]。
 */
object AiCenter {

    private const val GRANTS_PREFS = "ai_grants"
    private const val AUDIT_PREFS = "ai_audit"

    val grantStore: AiGrantStore by lazy {
        PreferencesAiGrantStore(
            prefs = app.sharedPrefsOf(GRANTS_PREFS),
            defaults = defaultGrants()
        )
    }

    val auditStore: PreferencesAiAuditStore by lazy {
        PreferencesAiAuditStore(prefs = app.sharedPrefsOf(AUDIT_PREFS))
    }

    val actionGate: AiActionGate by lazy {
        AiActionGate(grantStore = grantStore, riskAssessor = AiRiskAssessor())
    }

    /**
     * 第一版默认授权：仅放行 AI 路由层最低风险能力，让"理解 + 匹配现有任务"无需逐次确认。
     * 任何更高风险的能力都必须由用户在设置中显式授予。
     */
    private fun defaultGrants(): List<AiCapabilityGrant> = listOf(
        AiCapabilityGrant(
            id = "default_understand_text",
            capability = AiCapability.UnderstandText,
            scope = AiScope.Any,
            maxRisk = AiRiskLevel.Low,
            confirmationPolicy = AiConfirmationPolicy.AllowWithinGrant
        ),
        AiCapabilityGrant(
            id = "default_match_existing_task",
            capability = AiCapability.MatchExistingTask,
            scope = AiScope.Any,
            maxRisk = AiRiskLevel.Low,
            confirmationPolicy = AiConfirmationPolicy.AllowWithinGrant
        ),
        AiCapabilityGrant(
            id = "default_execute_existing_task",
            capability = AiCapability.ExecuteExistingTask,
            scope = AiScope.Any,
            maxRisk = AiRiskLevel.Medium,
            confirmationPolicy = AiConfirmationPolicy.AllowWithinGrant
        ),
        AiCapabilityGrant(
            id = "default_create_task_draft",
            capability = AiCapability.CreateTaskDraft,
            scope = AiScope.Any,
            maxRisk = AiRiskLevel.Low,
            confirmationPolicy = AiConfirmationPolicy.AllowWithinGrant
        ),
        AiCapabilityGrant(
            id = "default_use_network_model",
            capability = AiCapability.UseNetworkModel,
            scope = AiScope.Any,
            maxRisk = AiRiskLevel.Medium,
            confirmationPolicy = AiConfirmationPolicy.AllowWithinGrant
        )
    )
}

/**
 * Audit 存储的便捷别名，让外部代码不需要直接知道 Preferences 实现。
 */
val aiAuditStore: AiAuditStore get() = AiCenter.auditStore

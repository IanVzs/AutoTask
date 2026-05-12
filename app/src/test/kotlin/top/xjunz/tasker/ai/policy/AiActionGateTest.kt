/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.xjunz.tasker.ai.model.AiActionPlan
import top.xjunz.tasker.ai.model.AiActionStep
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiIntent
import top.xjunz.tasker.ai.model.AiIntentType
import top.xjunz.tasker.ai.model.AiRiskLevel
import top.xjunz.tasker.ai.model.AiScope

class AiActionGateTest {

    @Test
    fun review_requiresGrantWhenCapabilityIsMissing() {
        val gate = AiActionGate(InMemoryAiGrantStore())

        val result = gate.review(createPlan(AiCapability.ClickUi, AiRiskLevel.Medium))

        assertEquals(AiGateStatus.RequiresGrant, result.status)
        assertEquals(setOf(AiCapability.ClickUi), result.missingCapabilities)
    }

    @Test
    fun review_allowsLowRiskPlanCoveredByGrant() {
        val gate = AiActionGate(
            InMemoryAiGrantStore(
                listOf(
                    grant(
                        capability = AiCapability.CreateTaskDraft,
                        maxRisk = AiRiskLevel.Low
                    )
                )
            )
        )

        val result = gate.review(createPlan(AiCapability.CreateTaskDraft, AiRiskLevel.Low))

        assertTrue(result.isAllowed)
        assertEquals(AiRiskLevel.Low, result.assessment.riskLevel)
    }

    @Test
    fun review_requiresConfirmationWhenPolicyAlwaysAsks() {
        val gate = AiActionGate(
            InMemoryAiGrantStore(
                listOf(
                    grant(
                        capability = AiCapability.InputText,
                        maxRisk = AiRiskLevel.Medium,
                        confirmationPolicy = AiConfirmationPolicy.AlwaysAsk
                    )
                )
            )
        )

        val result = gate.review(createPlan(AiCapability.InputText, AiRiskLevel.Medium))

        assertEquals(AiGateStatus.RequiresConfirmation, result.status)
    }

    @Test
    fun review_allowsMixedRiskPlan_whenEachCapabilityHasMatchingGrant() {
        val gate = AiActionGate(
            InMemoryAiGrantStore(
                listOf(
                    grant(
                        capability = AiCapability.MatchExistingTask,
                        maxRisk = AiRiskLevel.Low,
                        scope = AiScope.Any
                    ),
                    grant(
                        capability = AiCapability.ExecuteExistingTask,
                        maxRisk = AiRiskLevel.Medium,
                        scope = AiScope.Any
                    )
                )
            )
        )

        val plan = AiActionPlan(
            id = "plan-mixed",
            userGoal = "open douyin",
            intent = AiIntent(
                type = AiIntentType.RunExistingTask,
                rawText = "open douyin",
                confidence = 1f
            ),
            steps = listOf(
                AiActionStep(
                    id = "match",
                    title = "match",
                    description = "match",
                    requiredCapabilities = setOf(AiCapability.MatchExistingTask),
                    riskLevel = AiRiskLevel.Low
                ),
                AiActionStep(
                    id = "execute",
                    title = "execute",
                    description = "execute",
                    requiredCapabilities = setOf(AiCapability.ExecuteExistingTask),
                    riskLevel = AiRiskLevel.Medium
                )
            ),
            scope = AiScope.Any
        )

        val result = gate.review(plan)

        assertTrue(result.isAllowed)
        assertEquals(AiRiskLevel.Medium, result.assessment.riskLevel)
    }

    @Test
    fun review_requiresGrantWhenRiskExceedsGrant() {
        val gate = AiActionGate(
            InMemoryAiGrantStore(
                listOf(
                    grant(
                        capability = AiCapability.RunShell,
                        maxRisk = AiRiskLevel.High
                    )
                )
            )
        )

        val result = gate.review(createPlan(AiCapability.RunShell, AiRiskLevel.Critical))

        assertEquals(AiGateStatus.RequiresGrant, result.status)
        assertEquals(setOf(AiCapability.RunShell), result.missingCapabilities)
    }

    private fun createPlan(
        capability: AiCapability,
        riskLevel: AiRiskLevel
    ): AiActionPlan {
        return AiActionPlan(
            id = "plan-1",
            userGoal = "test goal",
            intent = AiIntent(
                type = AiIntentType.CreateTaskDraft,
                rawText = "test goal",
                confidence = 1f
            ),
            steps = listOf(
                AiActionStep(
                    id = "step-1",
                    title = "Test step",
                    description = "A test step",
                    requiredCapabilities = setOf(capability),
                    riskLevel = riskLevel
                )
            ),
            scope = AiScope.CurrentApp
        )
    }

    private fun grant(
        capability: AiCapability,
        maxRisk: AiRiskLevel,
        confirmationPolicy: AiConfirmationPolicy = AiConfirmationPolicy.AllowWithinGrant,
        scope: AiScope = AiScope.CurrentApp
    ): AiCapabilityGrant {
        return AiCapabilityGrant(
            id = "grant-$capability",
            capability = capability,
            scope = scope,
            maxRisk = maxRisk,
            confirmationPolicy = confirmationPolicy
        )
    }
}

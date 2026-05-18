/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import top.xjunz.tasker.ai.model.AiCapability
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次 [AiAgentSession] 的"任务级授权"。
 *
 * 用户在会话开始前**只确认这一次**：AI 可以在哪些 App 里用哪些能力跑多久。
 * 之后整个 session 内不再被任何弹窗打断；超出范围（切到非授权 App、超步、超时、用未授权能力）
 * 时 session 自动停下并上报，让用户决定是再给一次授权还是放弃。
 *
 * 这是与"按 capability 永久授权"互补的轻量级机制：
 * - 永久授权（[top.xjunz.tasker.ai.policy.AiCapabilityGrant]）适用于"AI 路由 / 草稿生成"等
 *   常态低风险路径，不需要每次问。
 * - 任务级授权（本类）适用于"在某 App 里执行 UI 操作"这种**有明确目标但风险中等**的会话。
 */
data class AiTaskScope(
    val sessionId: String,
    /** 用户原始目标，仅用于审计/UI 显示，不参与权限校验。 */
    val userGoal: String,
    /** 授权可以操作的 App 包名集合。空集合表示"任意 App"（仅限调试/演示，正常路径不应留空）。 */
    val targetApps: Set<String>,
    /** 本次允许使用的能力。 */
    val capabilities: Set<AiCapability>,
    /** 步数上限。超出后 session 自动停下。 */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 时长上限（秒）。 */
    val maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SECONDS,
    /** 是否允许 AI 调用 [AiCapability.LaunchIntent] 启动 App（哪怕目标 App 在 [targetApps] 内）。 */
    val allowLaunchApps: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val deadlineMillis: Long
        get() = createdAtMillis + maxDurationSeconds * 1000L

    fun coversApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (targetApps.isEmpty()) return true
        return packageName in targetApps
    }

    fun covers(capability: AiCapability): Boolean = capability in capabilities

    companion object {
        const val DEFAULT_MAX_STEPS = 30
        const val DEFAULT_MAX_DURATION_SECONDS = 300
    }
}

/**
 * Session 级授权存储。**仅 in-memory**，App 进程被杀就丢失，需要时再让用户重新确认。
 *
 * 第一阶段不持久化：每个新会话都被问一次。等运行体验稳定，再考虑加"记住此 App N 分钟"或者
 * 把授权写进 [top.xjunz.tasker.ai.policy.PreferencesAiGrantStore]。
 */
object AiTaskScopeStore {

    private val active = ConcurrentHashMap<String, AiTaskScope>()

    fun grant(scope: AiTaskScope) {
        active[scope.sessionId] = scope
    }

    fun get(sessionId: String): AiTaskScope? = active[sessionId]

    fun revoke(sessionId: String) {
        active.remove(sessionId)
    }

    /**
     * 清掉所有过期的 scope（超过 [AiTaskScope.deadlineMillis]）。
     * 由调用方在合适的时机触发，例如打开授权对话框时。
     */
    fun purgeExpired() {
        val now = System.currentTimeMillis()
        active.entries.removeAll { (_, scope) -> scope.deadlineMillis < now }
    }
}

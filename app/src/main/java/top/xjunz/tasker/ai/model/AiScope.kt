/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class AiScope(
    val type: AiScopeType,
    val target: String? = null
) {
    fun covers(other: AiScope): Boolean {
        if (type == AiScopeType.Any) {
            return true
        }
        return type == other.type && target == other.target
    }

    companion object {
        val Any = AiScope(AiScopeType.Any)
        val CurrentApp = AiScope(AiScopeType.CurrentApp)

        fun appPackage(packageName: String): AiScope {
            return AiScope(AiScopeType.AppPackage, packageName)
        }

        fun task(checksum: String): AiScope {
            return AiScope(AiScopeType.Task, checksum)
        }

        fun session(sessionId: String): AiScope {
            return AiScope(AiScopeType.Session, sessionId)
        }
    }
}

@Serializable
enum class AiScopeType {
    Any,
    CurrentApp,
    AppPackage,
    Task,
    Session
}

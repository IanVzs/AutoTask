/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import top.xjunz.tasker.ai.AiJson

/**
 * SharedPreferences 持久化的授权存储，支持加载默认授权列表。
 */
class PreferencesAiGrantStore(
    private val prefs: SharedPreferences,
    private val defaults: List<AiCapabilityGrant> = emptyList()
) : AiGrantStore {

    private val cache: MutableList<AiCapabilityGrant> = loadFromPrefs().ifEmpty {
        defaults.toMutableList().also(::persist)
    }

    override fun listGrants(): List<AiCapabilityGrant> {
        return cache.toList()
    }

    fun replaceAll(grants: List<AiCapabilityGrant>) {
        cache.clear()
        cache.addAll(grants)
        persist(cache)
    }

    private fun loadFromPrefs(): MutableList<AiCapabilityGrant> {
        val raw = prefs.getString(KEY_GRANTS, null) ?: return mutableListOf()
        return runCatching {
            AiJson.decodeFromString<List<AiCapabilityGrant>>(raw).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun persist(grants: List<AiCapabilityGrant>) {
        prefs.edit {
            putString(KEY_GRANTS, AiJson.encodeToString(grants))
        }
    }

    companion object {
        private const val KEY_GRANTS = "ai_capability_grants"
    }
}

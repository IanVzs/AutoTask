/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.audit

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import top.xjunz.tasker.ai.AiJson

/**
 * SharedPreferences 持久化的 AI 决策记录存储，并暴露 LiveData 供 UI 观察。
 *
 * 只保留最近 [capacity] 条；溢出条目会按时间顺序丢弃最旧的，避免无限制写入。
 */
class PreferencesAiAuditStore(
    private val prefs: SharedPreferences,
    private val capacity: Int = DEFAULT_CAPACITY
) : AiAuditStore {

    private val mutex = Mutex()

    private val cache: MutableList<AiDecisionRecord> = loadFromPrefs()

    private val liveRecords = MutableLiveData<List<AiDecisionRecord>>(cache.toList())

    fun observe(): LiveData<List<AiDecisionRecord>> = liveRecords

    override suspend fun append(record: AiDecisionRecord) {
        mutex.withLock {
            cache.add(0, record)
            while (cache.size > capacity) {
                cache.removeAt(cache.size - 1)
            }
            persist(cache)
            liveRecords.postValue(cache.toList())
        }
    }

    override suspend fun latest(limit: Int): List<AiDecisionRecord> {
        return mutex.withLock { cache.take(limit) }
    }

    override suspend fun clear() {
        mutex.withLock {
            cache.clear()
            persist(cache)
            liveRecords.postValue(cache.toList())
        }
    }

    private fun loadFromPrefs(): MutableList<AiDecisionRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return mutableListOf()
        return runCatching {
            AiJson.decodeFromString<List<AiDecisionRecord>>(raw).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun persist(records: List<AiDecisionRecord>) {
        prefs.edit {
            putString(KEY_RECORDS, AiJson.encodeToString(records))
        }
    }

    companion object {
        private const val KEY_RECORDS = "ai_decision_records"
        private const val DEFAULT_CAPACITY = 200
    }
}

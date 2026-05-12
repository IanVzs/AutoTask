/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.audit

interface AiAuditStore {

    suspend fun append(record: AiDecisionRecord)

    suspend fun latest(limit: Int): List<AiDecisionRecord>

    suspend fun clear()

}

class InMemoryAiAuditStore : AiAuditStore {

    private val records = mutableListOf<AiDecisionRecord>()

    override suspend fun append(record: AiDecisionRecord) {
        records += record
    }

    override suspend fun latest(limit: Int): List<AiDecisionRecord> {
        return records.asReversed().take(limit)
    }

    override suspend fun clear() {
        records.clear()
    }
}

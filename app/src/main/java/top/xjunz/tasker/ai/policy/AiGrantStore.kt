/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.policy

interface AiGrantStore {

    fun listGrants(): List<AiCapabilityGrant>

}

class InMemoryAiGrantStore(
    grants: List<AiCapabilityGrant> = emptyList()
) : AiGrantStore {

    private val grants = grants.toMutableList()

    override fun listGrants(): List<AiCapabilityGrant> {
        return grants.toList()
    }

    fun replaceAll(newGrants: List<AiCapabilityGrant>) {
        grants.clear()
        grants.addAll(newGrants)
    }
}

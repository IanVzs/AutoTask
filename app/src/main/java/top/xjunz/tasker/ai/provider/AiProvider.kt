/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.provider

interface AiProvider {

    suspend fun complete(prompt: String): AiProviderResult

}

data class AiProviderResult(
    val text: String,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

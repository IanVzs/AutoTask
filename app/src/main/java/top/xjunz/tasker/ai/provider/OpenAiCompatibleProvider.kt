/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import top.xjunz.tasker.ai.AiJson

class OpenAiCompatibleProvider(
    private val config: OpenAiCompatibleConfig,
    private val httpClient: HttpClient = defaultHttpClient(config.requestTimeoutMillis)
) : AiProvider {

    override suspend fun complete(prompt: String): AiProviderResult {
        return withContext(Dispatchers.IO) {
            val response = httpClient.post(config.chatCompletionsUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                config.apiKey?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
                setBody(
                    AiJson.encodeToString(
                        OpenAiChatRequest(
                            model = config.model,
                            temperature = config.temperature,
                            maxTokens = config.maxTokens,
                            messages = listOf(
                                OpenAiChatMessage(role = "user", content = prompt)
                            )
                        )
                    )
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw AiProviderException("AI request failed: ${response.status}")
            }
            val body = AiJson.decodeFromString<OpenAiChatResponse>(response.bodyAsText())
            val text = body.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (text.isEmpty()) {
                throw AiProviderException("AI response is empty")
            }
            AiProviderResult(
                text = text,
                model = body.model,
                promptTokens = body.usage?.promptTokens,
                completionTokens = body.usage?.completionTokens,
                totalTokens = body.usage?.totalTokens
            )
        }
    }

    companion object {
        private fun defaultHttpClient(requestTimeoutMillis: Int): HttpClient {
            return HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    this.requestTimeoutMillis = requestTimeoutMillis.toLong()
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = requestTimeoutMillis.toLong()
                }
            }
        }
    }
}

data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 512,
    val requestTimeoutMillis: Int = 8000
) {
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"
}

class AiProviderException(message: String) : RuntimeException(message)

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val temperature: Float,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<OpenAiChatMessage>
)

@Serializable
private data class OpenAiChatMessage(
    val role: String,
    val content: String
)

@Serializable
private data class OpenAiChatResponse(
    val model: String? = null,
    val choices: List<OpenAiChatChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiChatChoice(
    val message: OpenAiChatMessage? = null
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

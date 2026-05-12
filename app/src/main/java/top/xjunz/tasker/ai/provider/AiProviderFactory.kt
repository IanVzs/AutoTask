/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.provider

import top.xjunz.tasker.Preferences

object AiProviderFactory {

    fun createConfiguredProvider(): AiProvider? {
        if (!Preferences.aiEnabled) {
            return null
        }
        val baseUrl = Preferences.aiProviderBaseUrl?.takeIf { it.isNotBlank() }
            ?: return null
        val model = Preferences.aiProviderModel?.takeIf { it.isNotBlank() }
            ?: return null
        return OpenAiCompatibleProvider(
            OpenAiCompatibleConfig(
                baseUrl = baseUrl,
                apiKey = Preferences.aiProviderApiKey,
                model = model,
                temperature = Preferences.aiProviderTemperature,
                maxTokens = Preferences.aiProviderMaxTokens,
                requestTimeoutMillis = Preferences.aiRequestTimeoutMillis
            )
        )
    }
}

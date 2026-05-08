/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiCompatibleConfigTest {

    @Test
    fun chatCompletionsUrl_trimsTrailingSlash() {
        val config = OpenAiCompatibleConfig(
            baseUrl = "https://example.com/v1/",
            apiKey = "key",
            model = "model"
        )

        assertEquals("https://example.com/v1/chat/completions", config.chatCompletionsUrl)
    }
}

/*
 * Copyright (c) 2026 xjunz. All rights reserved.
 */

package top.xjunz.tasker.voice

object VoiceCommandParser {

    private val runPrefixes = listOf("执行", "运行", "启动", "打开", "开始")

    fun parseRunTaskQuery(text: String): String? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        for (prefix in runPrefixes) {
            if (normalized.startsWith(prefix)) {
                return normalized.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
            }
        }
        return normalized
    }
}

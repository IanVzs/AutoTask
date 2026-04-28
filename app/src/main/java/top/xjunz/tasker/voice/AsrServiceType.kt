/*
 * Copyright (c) 2026 xjunz. All rights reserved.
 */

package top.xjunz.tasker.voice

import androidx.annotation.StringRes
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.R

object AsrServiceType {

    const val AUTO = 0
    const val SYSTEM = 1
    const val ALIBABA = 2

    val all = intArrayOf(AUTO, SYSTEM, ALIBABA)

    @StringRes
    fun titleOf(type: Int): Int {
        return when (type) {
            SYSTEM -> R.string.asr_service_system
            ALIBABA -> R.string.asr_service_alibaba
            else -> R.string.asr_service_auto
        }
    }

    fun indexOf(type: Int): Int {
        val index = all.indexOf(type)
        return if (index == -1) 0 else index
    }

    fun isAlibabaConfigured(): Boolean {
        return !Preferences.speechRecognitionAppKey.isNullOrBlank() &&
                !Preferences.speechRecognitionAccessKeyId.isNullOrBlank() &&
                !Preferences.speechRecognitionAccessKeySecret.isNullOrBlank()
    }
}

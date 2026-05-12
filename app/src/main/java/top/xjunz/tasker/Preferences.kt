/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import top.xjunz.tasker.service.OperatingMode
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaType

/**
 * @author xjunz 2022/04/21
 */
object Preferences {

    private val global by lazy {
        app.sharedPrefsOf("global")
    }

    var operatingMode by global.primitive("operating_mode", OperatingMode.Privilege.VALUE)

    var showLongClickToSelectTip by global.primitive("tip_long_click_to_select", true)
    var showSwipeToRemoveTip by global.primitive("tip_swipe_to_remove", true)
    var showDragToMoveTip by global.primitive("tip_drag_to_move", true)
    var showToggleRelationTip by global.primitive("tip_toggle_relation", true)
    var showLongClickToHost by global.primitive("tip_long_click_to_host", true)

    var nightMode by global.primitive("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    var privacyPolicyAcknowledged by global.primitive("privacy_policy_acknowledged", false)

    var enableWakeLock by global.primitive("enable_wake_lock", false)

    var recordedVersionCode by global.primitive("recorded_version_code", -1)

    var speechRecognitionService by global.primitive("speech_recognition_service", 0)

    var speechRecognitionAppKey by global.nullable<String>("speech_recognition_app_key", null)

    var speechRecognitionAccessKeyId by global.nullable<String>("speech_recognition_access_key_id", null)

    var speechRecognitionAccessKeySecret by global.nullable<String>(
        "speech_recognition_access_key_secret",
        null
    )

    var speechRecognitionToken by global.nullable<String>("speech_recognition_token", null)

    var speechRecognitionTokenExpireTime by global.primitive("speech_recognition_token_expire_time", 0L)

    var aiEnabled by global.primitive("ai_enabled", false)

    var aiProviderBaseUrl by global.nullable<String>("ai_provider_base_url", "https://api.deepseek.com")

    var aiProviderApiKey by global.nullable<String>("ai_provider_api_key", null)

    var aiProviderModel by global.nullable<String>("ai_provider_model", "deepseek-chat")

    var aiProviderTemperature by global.primitive("ai_provider_temperature", 0.2f)

    var aiProviderMaxTokens by global.primitive("ai_provider_max_tokens", 512)

    // 30 秒——agent 模式 prompt 越来越长（snapshot + history + 黑名单），8 秒经常被偶发网络抖动 / 模型负载击穿。
    // 用户可在 AI 配置弹窗里改小（比如纯短问答场景）。
    var aiRequestTimeoutMillis by global.primitive("ai_request_timeout_millis", 30000)

    var aiVoiceMinConfidence by global.primitive("ai_voice_min_confidence", 0.6f)

    /**
     * 是否启用 AI agent 模式（屏幕感知 + UI 操作循环）。
     * 启用后，被 AI 判定为「需要在 App 里做 UI 操作」的输入会走 [top.xjunz.tasker.ai.agent.AiAgentSession]
     * 而非仅生成草稿文字。每次会话仍需用户在任务级授权对话框里允许后才会执行。
     */
    var aiAgentEnabled by global.primitive("ai_agent_enabled", true)

    /** Agent 会话默认步数上限。可在弹窗里临时调整。 */
    var aiAgentMaxSteps by global.primitive("ai_agent_max_steps", 30)

    /** Agent 会话默认时长上限（秒），默认 5 分钟，覆盖大多数任务链。 */
    var aiAgentMaxSeconds by global.primitive("ai_agent_max_seconds", 300)

    /**
     * Agent 决策面板模式。取值与 [top.xjunz.tasker.ai.agent.overlay.AiAgentConfirmMode.rawValue] 对齐：
     * `disabled` / `auto_approve` (默认) / `wait_for_user`。
     */
    var aiAgentConfirmMode by global.nullable<String>("ai_agent_confirm_mode", "auto_approve")

    /** 决策面板倒计时秒数。`wait_for_user` 模式下倒计时归零后继续等用户，不自动决策。 */
    var aiAgentConfirmSeconds by global.primitive("ai_agent_confirm_seconds", 3)

    /** 决策面板上是否显示"换一个"按钮。 */
    var aiAgentConfirmAllowReplace by global.primitive("ai_agent_confirm_allow_replace", true)

    init {
        // 老用户迁移：旧默认 8000ms 太短，agent 模式下 prompt 长 + DeepSeek 抖动会经常 timeout。
        // 任何持久化值 < 15000 都强制升级到 30000，给一次性"健康检查"机会。
        // 用户后续可在 AI 配置弹窗里手动改小，迁移不会再次触发（因为新值 ≥ 15000）。
        if (aiRequestTimeoutMillis in 1..14999) {
            aiRequestTimeoutMillis = 30000
        }
    }

    private fun <T> SharedPreferences.nullable(
        name: String,
        defValue: T?
    ): NullableConfiguration<T> {
        return NullableConfiguration(this, name, defValue)
    }

    private fun <T> SharedPreferences.primitive(
        name: String,
        defValue: T
    ): PrimitiveConfiguration<T> {
        return PrimitiveConfiguration(this, name, defValue)
    }

    @Suppress("UNCHECKED_CAST")
    class PrimitiveConfiguration<T>(
        private val sp: SharedPreferences,
        private val key: String,
        private val defValue: T
    ) : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>): T {
            return when (val type = property.returnType.javaType) {
                Boolean::class.java -> sp.getBoolean(key, defValue as Boolean) as T
                Int::class.java -> sp.getInt(key, defValue as Int) as T
                Long::class.java -> sp.getLong(key, defValue as Long) as T
                Float::class.java -> sp.getFloat(key, defValue as Float) as T
                else -> error("unsupported type: $type")
            }
        }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            sp.edit {
                when (val type = property.returnType.javaType) {
                    Boolean::class.java -> putBoolean(key, value as Boolean)
                    Int::class.java -> putInt(key, value as Int)
                    Long::class.java -> putLong(key, value as Long)
                    Float::class.java -> putFloat(key, value as Float)
                    else -> error("unsupported type: $type")
                }
            }
        }

    }

    @Suppress("UNCHECKED_CAST")
    class NullableConfiguration<T : Any?>(
        private val sp: SharedPreferences,
        private val key: String,
        private val defValue: T?
    ) : ReadWriteProperty<Any, T?> {

        override fun getValue(thisRef: Any, property: KProperty<*>): T? {
            return when (val type = property.returnType.javaType) {
                String::class.java -> sp.getString(key, defValue as? String) as T
                Set::class.java -> sp.getStringSet(
                    key, defValue as? Set<String>
                ) as T

                else -> error("unsupported type: $type")
            }
        }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T?) {
            sp.edit {
                when (val type = property.returnType.javaType) {
                    String::class.java -> putString(key, value as? String)
                    Set::class.java -> putStringSet(key, value as? Set<String>)
                    else -> error("unsupported type: $type")
                }
            }
        }
    }
}
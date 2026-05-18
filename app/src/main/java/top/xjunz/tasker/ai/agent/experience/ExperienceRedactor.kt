/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import top.xjunz.tasker.ai.agent.AiUiTarget

/**
 * 经验本写入前的隐私脱敏。规则尽量保守——宁可误删也不上盘可识别 PII。
 *
 * 当前覆盖：
 * - 手机号（11 位连续数字 + 国内常见 1[3-9] 起始）
 * - 邮箱
 * - 18 位身份证（含校验位 X）
 * - 13-19 位连续数字（粗匹配银行卡 / 长卡号）
 *
 * 不覆盖（依赖代码层不写）：
 * - [SetText] 实际输入内容（写入器固定写 `<redacted_input>`，不走本类）
 * - 完整 snapshot 节点正文（写入器只摘要 packageName / activityName，本类无机会作用）
 */
internal object ExperienceRedactor {

    private const val PLACEHOLDER = "<redacted>"

    private val phone = Regex("(?<![0-9])1[3-9]\\d{9}(?![0-9])")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val idCard = Regex("(?<![0-9])\\d{17}[0-9Xx](?![0-9])")
    private val longDigits = Regex("(?<![0-9])\\d{13,19}(?![0-9])")

    fun redactText(raw: String?): String? {
        if (raw.isNullOrEmpty()) return raw
        return raw
            .replace(phone, PLACEHOLDER)
            .replace(email, PLACEHOLDER)
            .replace(idCard, PLACEHOLDER)
            .replace(longDigits, PLACEHOLDER)
    }

    fun redactTarget(target: AiUiTarget?): AiUiTarget? {
        if (target == null) return null
        return target.copy(
            textEquals = redactText(target.textEquals),
            textContains = redactText(target.textContains),
            contentDescEquals = redactText(target.contentDescEquals),
            contentDescContains = redactText(target.contentDescContains)
        )
    }
}

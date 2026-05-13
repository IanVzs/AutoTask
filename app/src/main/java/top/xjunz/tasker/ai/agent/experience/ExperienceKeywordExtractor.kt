/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

/**
 * 极简关键词抽取：把用户 goal 字符串切成一组"信号词"，用于召回时与历史 entry 的关键词列表求交集打分。
 *
 * 不做正经分词（不引入分词库 / embedding），策略：
 * - 按常见标点 + 空白切分
 * - 中文段直接保留 1-3 字短语（按长度降序）；4 字以上整段保留
 * - 英文段切成单词，长度 ≥ 2 保留
 * - 去掉停用词（"的我了在和与"等）和纯数字
 * - 去重 + 限制最多 16 个
 */
internal object ExperienceKeywordExtractor {

    private val STOPWORDS = setOf(
        "的", "了", "和", "与", "在", "我", "你", "他", "她", "它",
        "把", "给", "让", "去", "来", "到", "上", "下", "吗", "啊",
        "请", "麻烦", "帮", "下"
    )

    private val SPLIT_REGEX = Regex("[\\s，。、,.;:?!？！\"'“”‘’()\\[\\]{}—\\-—_/|]+")

    private val ASCII_SEG = Regex("[A-Za-z0-9]+")
    private val CJK_SEG = Regex("[\\p{IsHan}]+")

    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val rawSegments = text.lowercase().split(SPLIT_REGEX).filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        rawSegments.forEach { seg ->
            // ascii 单词
            ASCII_SEG.findAll(seg).forEach { m ->
                val w = m.value
                if (w.length >= 2 && w.toIntOrNull() == null && w !in STOPWORDS) {
                    out.add(w)
                }
            }
            // cjk 短语：连续中文段整体保留 + 2-3 字滑窗（适合"抖音""搜索""综艺"这类常见词）
            CJK_SEG.findAll(seg).forEach { m ->
                val s = m.value
                if (s.length in 2..6 && s !in STOPWORDS) out.add(s)
                if (s.length >= 4) {
                    for (start in 0..s.length - 2) {
                        val w2 = s.substring(start, start + 2)
                        if (w2 !in STOPWORDS) out.add(w2)
                        if (start + 3 <= s.length) {
                            val w3 = s.substring(start, start + 3)
                            if (w3 !in STOPWORDS) out.add(w3)
                        }
                    }
                }
            }
        }
        return out.take(MAX_KEYWORDS)
    }

    fun overlap(a: Collection<String>, b: Collection<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val small: Collection<String>
        val big: Set<String>
        if (a.size < b.size) {
            small = a
            big = b.toHashSet()
        } else {
            small = b
            big = a.toHashSet()
        }
        return small.count { it in big }
    }

    private const val MAX_KEYWORDS = 16
}

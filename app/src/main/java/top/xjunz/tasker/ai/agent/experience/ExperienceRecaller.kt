/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * 召回打分：根据当前 (用户 goal, target App 集合) 给所有索引条目打分，取 top-N。
 *
 * score = pkg_match * 3 + keyword_overlap * 1 + outcome_failure_bonus + age_decay
 *   - pkg_match: target App 命中索引条目的 targetAppPackage 时 +3
 *   - keyword_overlap: 用户 goal 关键词与历史 entry goal_keywords 的交集大小
 *   - outcome_failure_bonus: 失败 / 偏轨经验 +0.5（"知道这条路堵了"对下次 AI 价值高）
 *   - age_decay: e^(-age_days / 30) 衰减（半衰期约 21 天）
 *
 * 阈值：单条 score < [MIN_SCORE_THRESHOLD] 的不返回，避免给 AI 注入低相关性的噪音。
 */
internal class ExperienceRecaller(
    private val index: List<ExperienceIndexEntry>,
    private val now: Long = System.currentTimeMillis()
) {

    fun recall(
        userGoal: String,
        targetApps: Set<String>,
        topN: Int
    ): List<RecallCandidate> {
        if (index.isEmpty() || topN <= 0) return emptyList()
        val queryKeywords = ExperienceKeywordExtractor.extract(userGoal)
        return index
            .asSequence()
            .map { entry ->
                val score = score(entry, queryKeywords, targetApps)
                RecallCandidate(entry, score)
            }
            .filter { it.score >= MIN_SCORE_THRESHOLD }
            .sortedByDescending { it.score }
            .take(topN)
            .toList()
    }

    private fun score(
        entry: ExperienceIndexEntry,
        queryKeywords: List<String>,
        targetApps: Set<String>
    ): Double {
        val pkgMatch = if (entry.targetAppPackage != null && entry.targetAppPackage in targetApps) 3.0 else 0.0
        val overlap = ExperienceKeywordExtractor.overlap(queryKeywords, entry.goalKeywords).toDouble()
        val failureBonus = when (entry.outcome) {
            "Completed" -> 0.0
            else -> 0.5
        }
        val ageMs = (now - entry.finishedAtMillis).coerceAtLeast(0L)
        val ageDays = TimeUnit.MILLISECONDS.toDays(ageMs).toDouble()
        val ageDecay = exp(-ageDays / 30.0)
        return (pkgMatch + overlap + failureBonus) * ageDecay
    }

    data class RecallCandidate(val entry: ExperienceIndexEntry, val score: Double)

    companion object {
        /**
         * 召回最低分阈值。<= 0 也算“可能相关”（pkg + overlap + bonus 全 0 时分数为 0），
         * 设为 0.4 把"完全无交集 + 无包名命中"的条目挡掉，只放真有信号的。
         */
        private const val MIN_SCORE_THRESHOLD = 0.4
    }
}

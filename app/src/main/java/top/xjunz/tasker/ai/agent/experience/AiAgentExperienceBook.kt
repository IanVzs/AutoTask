/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import android.content.Context
import kotlinx.serialization.encodeToString
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.agent.AiAgentLog
import top.xjunz.tasker.ai.agent.AiAgentSessionOutcome
import top.xjunz.tasker.ai.agent.AiAgentSessionPlan
import top.xjunz.tasker.ai.agent.AiAgentStepRecord
import top.xjunz.tasker.engine.task.XTask
import java.io.File

/**
 * AI agent 经验本 —— **跨 session 长期记忆**。
 *
 * 三个生命周期点：
 * 1. **写入**：`session` 跑完后，`VoiceCommandService.runAgentFlow` 调 [recordSession]，
 *    把 history + outcome + plan 提炼成可读 markdown + 结构化 json 嵌块，落到
 *    `${context.filesDir}/ai_agent_experience/<ts>_<sid>.txt`，并更新 `index.json`。
 * 2. **召回**：新 session 启动前，`AiAgentSession.run` 调 [recall]，按 (用户 goal, 当前 App)
 *    打分挑 top-N，结果由 `AiAgentPlanner.buildNextActionPrompt` 注入 prompt 让 AI 阅读。
 * 3. **UI**：经验本卡片 / 列表 / 详情读 [queryAll] / [loadEntry]，"一键转草稿"读 [convertToDraft]。
 *
 * 线程安全：写入与索引重写在 [synchronized(lock)] 内做（同一进程，单语音 service 触发，
 * 并发概率几乎为零，但加锁更稳）。读取走 `index.json` 内存缓存。
 *
 * 隐私：[ExperienceRedactor] 在写盘前做正则脱敏；set_text 实际内容**永不写盘**。
 * 文件夹应在 `data_extraction_rules.xml` 里被排除以防 Auto Backup 上云（后续接入）。
 */
object AiAgentExperienceBook {

    private const val DIR_NAME = "ai_agent_experience"
    private const val INDEX_NAME = "index.json"

    private val lock = Any()

    @Volatile
    private var dirRef: File? = null

    @Volatile
    private var indexCache: ExperienceIndex? = null

    fun isEnabled(): Boolean = Preferences.aiAgentExperienceBookEnabled

    /** 建议在 `Service.onCreate` 里调用一次，让目录就位 + 把 index 加载到内存。 */
    fun ensureInitialized(context: Context) {
        if (dirRef != null) return
        synchronized(lock) {
            if (dirRef != null) return
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            dirRef = dir
            indexCache = readIndexFromDisk(dir)
        }
    }

    /** 把一次会话写成单条经验文件并更新索引。失败不抛异常，只记 log。 */
    fun recordSession(
        context: Context,
        sessionId: String,
        userGoal: String,
        targetApps: Set<String>,
        plan: AiAgentSessionPlan?,
        outcome: AiAgentSessionOutcome,
        outcomeLabel: String,
        outcomeDetail: String,
        history: List<AiAgentStepRecord>,
        startedAtMillis: Long,
        finishedAtMillis: Long
    ) {
        if (!isEnabled()) return
        ensureInitialized(context)
        val dir = dirRef ?: return
        runCatching {
            val exp = ExperienceFileWriter.build(
                sessionId = sessionId,
                userGoal = userGoal,
                targetApps = targetApps,
                plan = plan,
                outcome = outcome,
                outcomeLabel = outcomeLabel,
                outcomeDetail = outcomeDetail,
                history = history,
                startedAtMillis = startedAtMillis,
                finishedAtMillis = finishedAtMillis
            )
            synchronized(lock) {
                val result = ExperienceFileWriter.writeToDir(dir, exp)
                val current = currentIndex()
                val merged = ExperienceIndex(
                    version = 1,
                    entries = current.entries + result.indexEntry
                )
                val evicted = evictIfOverBudget(dir, merged)
                writeIndexToDisk(dir, evicted)
                indexCache = evicted
                AiAgentLog.i(
                    "experience.write",
                    "wrote ${result.file.name} size=${result.indexEntry.sizeBytes}B " +
                            "total=${evicted.entries.size}"
                )
            }
        }.onFailure {
            AiAgentLog.w("experience.write", "记经验本失败：${it.message}")
        }
    }

    fun recall(
        context: Context,
        userGoal: String,
        targetApps: Set<String>,
        topN: Int = Preferences.aiAgentExperienceRecallTopN
    ): List<ExperienceRecallEntry> {
        if (!isEnabled() || topN <= 0) return emptyList()
        ensureInitialized(context)
        val dir = dirRef ?: return emptyList()
        val index = currentIndex().entries
        if (index.isEmpty()) return emptyList()
        return runCatching {
            val recaller = ExperienceRecaller(index)
            recaller.recall(userGoal, targetApps, topN)
                .mapNotNull { cand ->
                    val full = loadEntryFromDisk(dir, cand.entry.filename) ?: return@mapNotNull null
                    ExperienceRecallEntry(cand.entry, full, cand.score)
                }
        }.onFailure {
            AiAgentLog.w("experience.recall", "召回失败：${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 给 UI 列表用：所有索引（按时间倒序）。 */
    fun queryAll(context: Context): List<ExperienceIndexEntry> {
        ensureInitialized(context)
        return currentIndex().entries.sortedByDescending { it.finishedAtMillis }
    }

    /** 给 UI 详情用：加载完整正文。 */
    fun loadEntry(context: Context, filename: String): ExperienceFile? {
        ensureInitialized(context)
        val dir = dirRef ?: return null
        return loadEntryFromDisk(dir, filename)
    }

    /** 删一条。失败返回 false。 */
    fun delete(context: Context, filename: String): Boolean {
        ensureInitialized(context)
        val dir = dirRef ?: return false
        return synchronized(lock) {
            val target = File(dir, filename)
            val ok = !target.exists() || target.delete()
            if (ok) {
                val current = currentIndex()
                val newIndex = ExperienceIndex(
                    version = 1,
                    entries = current.entries.filterNot { it.filename == filename }
                )
                writeIndexToDisk(dir, newIndex)
                indexCache = newIndex
            }
            ok
        }
    }

    /** 一键清空。 */
    fun clearAll(context: Context) {
        ensureInitialized(context)
        val dir = dirRef ?: return
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            val empty = ExperienceIndex(version = 1, entries = emptyList())
            writeIndexToDisk(dir, empty)
            indexCache = empty
        }
    }

    fun usageBytes(context: Context): Long {
        ensureInitialized(context)
        val dir = dirRef ?: return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun convertToDraft(context: Context, filename: String): XTask? {
        val exp = loadEntry(context, filename) ?: return null
        return ExperienceToTaskConverter.convert(exp)
    }

    // ---------- 内部工具 ----------

    private fun currentIndex(): ExperienceIndex = indexCache ?: ExperienceIndex()

    private fun readIndexFromDisk(dir: File): ExperienceIndex {
        val f = File(dir, INDEX_NAME)
        if (!f.exists()) return ExperienceIndex()
        return runCatching {
            AiJson.decodeFromString(ExperienceIndex.serializer(), f.readText(Charsets.UTF_8))
        }.getOrElse {
            AiAgentLog.w("experience.index", "index.json 解析失败，重置为空：${it.message}")
            ExperienceIndex()
        }
    }

    private fun writeIndexToDisk(dir: File, index: ExperienceIndex) {
        val f = File(dir, INDEX_NAME)
        runCatching {
            f.writeText(AiJson.encodeToString(index), Charsets.UTF_8)
        }.onFailure {
            AiAgentLog.w("experience.index", "index.json 写入失败：${it.message}")
        }
    }

    private fun loadEntryFromDisk(dir: File, filename: String): ExperienceFile? {
        val f = File(dir, filename)
        if (!f.exists()) return null
        val content = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return ExperienceFileWriter.parseJsonBlock(content)
    }

    /**
     * 超额淘汰。规则（按删除优先级，从高到低）：
     * 1. 超过 30 天的 Completed
     * 2. 超过 90 天的非 Completed
     * 3. 任何超过 180 天的
     * 4. 实在不够，按 finishedAtMillis 升序删
     *
     * 删完一直瘦到目录大小回到上限的 80% 以下。
     */
    private fun evictIfOverBudget(dir: File, index: ExperienceIndex): ExperienceIndex {
        val budget = Preferences.aiAgentExperienceMaxBytes.coerceAtLeast(64 * 1024)
        val ceiling = (budget * 0.8).toLong()
        var entries = index.entries.toMutableList()
        var totalBytes = entries.sumOf { it.sizeBytes }
        if (totalBytes <= budget) return index
        val now = System.currentTimeMillis()

        fun deleteEntry(entry: ExperienceIndexEntry) {
            File(dir, entry.filename).delete()
            entries.removeAll { it.filename == entry.filename }
            totalBytes -= entry.sizeBytes
        }

        val priorities: List<(ExperienceIndexEntry) -> Boolean> = listOf(
            { now - it.finishedAtMillis > 30L * 24 * 3600_000 && it.outcome == "Completed" },
            { now - it.finishedAtMillis > 90L * 24 * 3600_000 && it.outcome != "Completed" },
            { now - it.finishedAtMillis > 180L * 24 * 3600_000 }
        )
        for (matcher in priorities) {
            if (totalBytes <= ceiling) break
            entries.filter(matcher).sortedBy { it.finishedAtMillis }.forEach {
                if (totalBytes <= ceiling) return@forEach
                deleteEntry(it)
            }
        }
        // 兜底：还超就按时间最旧顺序硬删
        entries.sortedBy { it.finishedAtMillis }.toList().forEach {
            if (totalBytes <= ceiling) return@forEach
            deleteEntry(it)
        }
        return ExperienceIndex(version = 1, entries = entries)
    }
}

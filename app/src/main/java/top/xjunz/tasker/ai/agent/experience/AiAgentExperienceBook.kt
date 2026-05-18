/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * 把一次会话写成单条经验文件并更新索引。失败不抛异常，只记 log。
     *
     * **suspend + Dispatchers.IO**：经验本所有写盘 / 索引 IO 都不能在主线程跑，
     * 否则 agent session 结束瞬间会卡顿（VoiceCommandService 在 Main.immediate 上）。
     */
    suspend fun recordSession(
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
        withContext(Dispatchers.IO) {
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
                    // **事务一致性**：只有 index.json 写盘成功才提交内存缓存。
                    // 如果磁盘写失败（IO 错 / 满磁盘 / 权限丢失）但仍然更新 indexCache，
                    // 进程被杀后下次启动 readIndexFromDisk 会读到旧 index.json，
                    // 导致刚写入的 txt 文件成为 "孤儿"（磁盘有、索引无）。
                    // 写盘失败时回滚到上一次 currentIndex，并删掉刚写出但没机会进索引的 txt
                    // 避免持续的孤儿文件累积。
                    val written = writeIndexToDiskOrFail(dir, evicted)
                    if (written) {
                        indexCache = evicted
                        AiAgentLog.i(
                            "experience.write",
                            "wrote ${result.file.name} size=${result.indexEntry.sizeBytes}B " +
                                    "total=${evicted.entries.size}"
                        )
                    } else {
                        runCatching { result.file.delete() }
                        AiAgentLog.w(
                            "experience.write",
                            "index.json 写盘失败，已回滚刚生成的 ${result.file.name}；indexCache 保持为旧值"
                        )
                    }
                }
            }.onFailure {
                AiAgentLog.w("experience.write", "记经验本失败：${it.message}")
            }
        }
    }

    /**
     * 按 (用户 goal, 当前 App) 召回 top-N 历史经验。
     *
     * **suspend + Dispatchers.IO**：召回过程涉及读多个 txt 文件解析 JSON 嵌块，
     * 不能在主线程跑。
     */
    suspend fun recall(
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
        return withContext(Dispatchers.IO) {
            runCatching {
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
    }

    /** 给 UI 列表用：所有索引（按时间倒序）。读 indexCache 内存即可，IO 只在 ensureInitialized 首次发生。 */
    suspend fun queryAll(context: Context): List<ExperienceIndexEntry> {
        ensureInitialized(context)
        // 仅读内存缓存 + sortedByDescending；首次 ensureInitialized 内的 readIndexFromDisk 也包到 IO 里
        return withContext(Dispatchers.IO) {
            currentIndex().entries.sortedByDescending { it.finishedAtMillis }
        }
    }

    /** 给 UI 详情用：加载完整正文。读盘 + 解析 JSON 嵌块都包在 IO 里。 */
    suspend fun loadEntry(context: Context, filename: String): ExperienceFile? {
        ensureInitialized(context)
        val dir = dirRef ?: return null
        return withContext(Dispatchers.IO) { loadEntryFromDisk(dir, filename) }
    }

    /** 删一条。失败返回 false。 */
    suspend fun delete(context: Context, filename: String): Boolean {
        ensureInitialized(context)
        val dir = dirRef ?: return false
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val target = File(dir, filename)
                val ok = !target.exists() || target.delete()
                if (ok) {
                    val current = currentIndex()
                    val newIndex = ExperienceIndex(
                        version = 1,
                        entries = current.entries.filterNot { it.filename == filename }
                    )
                    if (writeIndexToDiskOrFail(dir, newIndex)) {
                        indexCache = newIndex
                    }
                }
                ok
            }
        }
    }

    /** 一键清空。 */
    suspend fun clearAll(context: Context) {
        ensureInitialized(context)
        val dir = dirRef ?: return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                // 递归删除：防止未来出现子目录而漏删
                dir.walkBottomUp().forEach { f ->
                    if (f != dir) runCatching { f.delete() }
                }
                if (!dir.exists()) dir.mkdirs()
                val empty = ExperienceIndex(version = 1, entries = emptyList())
                if (writeIndexToDiskOrFail(dir, empty)) {
                    indexCache = empty
                }
            }
        }
    }

    /** 当前目录已用字节数（递归统计，防子目录漏算）。 */
    suspend fun usageBytes(context: Context): Long {
        ensureInitialized(context)
        val dir = dirRef ?: return 0L
        return withContext(Dispatchers.IO) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    suspend fun convertToDraft(context: Context, filename: String): XTask? {
        val exp = loadEntry(context, filename) ?: return null
        // ExperienceToTaskConverter 是纯内存计算，不涉及 IO；为保持 API 一致仍 suspend
        return exp.let { ExperienceToTaskConverter.convert(it) }
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
        writeIndexToDiskOrFail(dir, index)
    }

    /**
     * 原子化写 index.json：先写临时文件 `index.json.tmp`，重命名成 `index.json`。
     * 临时文件 + rename 保证：要么完全旧、要么完全新，不会出现写到一半被截断的脏数据。
     *
     * @return true = 真的成功落到磁盘；false = 任何环节失败（IO 异常 / rename 失败）。
     */
    private fun writeIndexToDiskOrFail(dir: File, index: ExperienceIndex): Boolean {
        val target = File(dir, INDEX_NAME)
        val tmp = File(dir, "$INDEX_NAME.tmp")
        return runCatching {
            tmp.writeText(AiJson.encodeToString(index), Charsets.UTF_8)
            // File.renameTo 在同目录下通常是原子的；若 target 存在则需先删，否则部分文件系统会拒绝
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("无法删除旧 ${target.name} 让位给新 index")
            }
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("renameTo 失败：${tmp.name} → ${target.name}")
            }
            true
        }.getOrElse {
            AiAgentLog.w("experience.index", "index.json 写入失败：${it.message}", it)
            runCatching { tmp.delete() }
            false
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
            // **事务一致性**：只有真删成功（或文件本来就不在）才更新索引；
            // 否则保留索引条目，避免"索引无条目但文件仍占用配额"的磁盘泄漏。
            val f = File(dir, entry.filename)
            val deleted = !f.exists() || f.delete()
            if (deleted) {
                entries.removeAll { it.filename == entry.filename }
                totalBytes -= entry.sizeBytes
            } else {
                AiAgentLog.w("experience.evict", "删除 ${entry.filename} 失败，保留索引条目避免孤儿配额")
            }
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

/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import android.util.Log
import top.xjunz.tasker.app
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 链路统一的 logcat 出口。**所有** AI agent 相关日志都走这里，便于：
 *
 * - 用 `make logs` / `adb logcat -s AiAgent:*` 一次性抓出完整链路（含 prompt、response、动作、节点、outcome）。
 * - 远程协作时让维护者只需要一段日志就能定位问题，不再依赖用户截图 / 复制详情卡片。
 *
 * **文件镜像**：vivo / xiaomi / oppo 等 OEM 默认会把第三方 App 主进程的 logcat 静默丢弃，
 * 只剩 :service 等特权进程能输出。为了让"反馈现场"在所有机型上都能拿到完整链路，所有
 * 日志同时写入 [logFile]（App 私有 external 路径），用户可通过 `adb pull` 直接取走，
 * 也可在 App UI 里加"导出日志"按钮（follow-up）。
 *
 * logcat 单条上限约 4 KB，超过会被静默截断。Prompt / Response 可能远超这个长度，
 * 因此 [chunked] 把长字符串按 [CHUNK_SIZE] 拆成多条 `[1/N] ...` 输出。
 *
 * 等级约定：
 * - `i` info：链路里程碑（session 开始 / 结束 / 每步动作摘要 / 抓快照结果）。
 * - `d` debug：详细内容（完整 prompt / response / snapshot 节点数与统计）。
 * - `w` warn：可恢复的异常（解析失败 / 节点未命中等）。
 * - `e` error：网络异常 / 协程异常等需要立刻关注的情况。
 */
object AiAgentLog {

    const val TAG = "AiAgent"

    /** logcat 单条 message 大小上限（字节估算），保留余量避免截断。 */
    private const val CHUNK_SIZE = 3500

    /** 日志文件大小上限（字节）；超过后截断保留尾部 [LOG_FILE_KEEP_BYTES]，避免无限增长。 */
    private const val LOG_FILE_MAX_BYTES = 4L * 1024 * 1024
    private const val LOG_FILE_KEEP_BYTES = 1L * 1024 * 1024

    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()

    /**
     * 日志文件位置：`/sdcard/Android/data/top.xjunz.tasker/files/agent.log`。
     * App 自身有写权限；adb 不需要 root，可直接 `adb pull` 取走。
     * 取不到 external dir（极少情况，比如外存被卸载）时返回 null，文件镜像静默禁用。
     */
    val logFile: File?
        get() = runCatching {
            app.getExternalFilesDir(null)?.let { File(it, "agent.log") }
        }.getOrNull()

    fun i(section: String, message: String) {
        Log.i(TAG, format(section, message))
        appendToFile("I", section, message, null)
    }

    fun w(section: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, format(section, message), error)
        else Log.w(TAG, format(section, message))
        appendToFile("W", section, message, error)
    }

    fun e(section: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, format(section, message), error)
        else Log.e(TAG, format(section, message))
        appendToFile("E", section, message, error)
    }

    /**
     * 调试级日志，长文本自动按 [CHUNK_SIZE] 拆段输出，避免 logcat 截断。
     * 文件镜像不拆段——文件没有 4 KB 限制，整段写入更利于事后 grep。
     */
    fun d(section: String, message: String) {
        if (message.length <= CHUNK_SIZE) {
            Log.d(TAG, format(section, message))
        } else {
            chunked(message).forEachIndexed { index, chunk ->
                val total = (message.length + CHUNK_SIZE - 1) / CHUNK_SIZE
                Log.d(TAG, format(section, "[${index + 1}/$total] $chunk"))
            }
        }
        appendToFile("D", section, message, null)
    }

    /**
     * 同步写文件——agent 链路调用频率不高（百毫秒一条量级），不做异步队列保持简单。
     * 任何 IO 异常都吞掉，绝不影响调用方 logcat 主流程。
     * 超过 [LOG_FILE_MAX_BYTES] 时截掉头部保留尾部 [LOG_FILE_KEEP_BYTES]，文件维持有界。
     */
    private fun appendToFile(level: String, section: String, message: String, error: Throwable?) {
        val target = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                target.parentFile?.takeIf { !it.exists() }?.mkdirs()
                if (target.length() > LOG_FILE_MAX_BYTES) truncateFromHead(target)
                target.appendText(buildString {
                    append(timestampFormat.format(Date()))
                    append(' ').append(level)
                    append(" [").append(section).append("] ")
                    append(message)
                    append('\n')
                    if (error != null) {
                        append("    ").append(error.toString()).append('\n')
                        error.stackTrace.take(8).forEach {
                            append("        at ").append(it.toString()).append('\n')
                        }
                    }
                })
            }
        }
    }

    /**
     * 文件超出上限时，读尾部 [LOG_FILE_KEEP_BYTES] 字节并重写文件。
     * 这种"丢头保尾"对调试更有价值——最近的现场更重要。
     */
    private fun truncateFromHead(file: File) {
        runCatching {
            val raf = java.io.RandomAccessFile(file, "r")
            val tail = ByteArray(LOG_FILE_KEEP_BYTES.toInt())
            raf.seek(file.length() - LOG_FILE_KEEP_BYTES)
            val read = raf.read(tail)
            raf.close()
            if (read > 0) {
                file.writeBytes(tail.copyOf(read))
                file.appendText("--- truncated head ---\n")
            }
        }
    }

    /**
     * 把多行结构化内容拆成 logcat 能消化的形式：先按换行切，再合并到 chunk 上限。
     * 比单纯 substring 更友好——chunk 边界尽量落在换行处，便于 grep。
     */
    private fun chunked(message: String): List<String> {
        if (message.length <= CHUNK_SIZE) return listOf(message)
        val out = ArrayList<String>(message.length / CHUNK_SIZE + 1)
        var start = 0
        while (start < message.length) {
            val end = (start + CHUNK_SIZE).coerceAtMost(message.length)
            // 尽量切在换行处，但只在 chunk 末尾的 ~10% 范围内回退，避免极端长行无效循环
            val cut = if (end == message.length) end
            else message.lastIndexOf('\n', end).let {
                if (it >= start + CHUNK_SIZE * 9 / 10) it + 1 else end
            }
            out.add(message.substring(start, cut))
            start = cut
        }
        return out
    }

    private fun format(section: String, message: String): String =
        "[$section] $message"
}

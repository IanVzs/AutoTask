/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.inspector.shared.AiNodeTreeCompactor

/**
 * 主进程的"屏幕快照"门面：通过 [top.xjunz.tasker.service.AutomatorService.captureUiSnapshotJson]
 * 跨进程拿到 [AiUiSnapshot] 的 JSON 字符串，反序列化即用。
 *
 * **为什么不在主进程直接读 root**：
 * - A11y 模式下 root 在主进程的 `A11yAutomatorService` 上，理论可读；
 * - Shizuku 模式下 root 在 `:service` 特权进程的 `uiAutomation` 里，主进程访问会触发
 *   `ensurePrivilegedProcess()` 抛异常。
 *
 * 统一通过 `currentService` 抽象 + `captureUiSnapshotJson` 抽象方法分发，
 * 上层永远不必关心当前是哪种模式。读屏 + 节点压缩**全都在执行端进程做**，
 * 跟 `aidoc/02 §2 进程模型` + `aidoc/09 §9 AI 警告`要求一致。
 */
object ScreenSnapshotProvider {

    private const val MAX_NODES = AiNodeTreeCompactor.DEFAULT_MAX_NODES
    private const val MAX_TEXT_LEN = AiNodeTreeCompactor.DEFAULT_MAX_TEXT_LEN

    /**
     * 抓一份 AI 可读的当前屏幕快照。
     * 返回 null 表示"暂时抓不到"——可能 service 没启动 / a11y 与 Shizuku 都没连 / 跨进程 IO 异常 /
     * service 端 root 暂时为 null（窗口切换瞬间）。调用方应让 AI 等下一轮重试。
     */
    suspend fun capture(): AiUiSnapshot? = withContext(Dispatchers.IO) {
        if (!serviceController.isServiceRunning) {
            AiAgentLog.w("snapshot", "AutomatorService 未启动（a11y / Shizuku 都没连）")
            return@withContext null
        }
        val json = runCatching {
            currentService.captureUiSnapshotJson(MAX_NODES, MAX_TEXT_LEN)
        }.onFailure {
            AiAgentLog.e("snapshot", "RPC captureUiSnapshotJson 异常：${it.message}", it)
        }.getOrNull()

        if (json.isNullOrBlank()) {
            AiAgentLog.w(
                "snapshot",
                "captureUiSnapshotJson 返回空——service 端可能 root=null 或临时不可读"
            )
            return@withContext null
        }
        val snapshot = runCatching {
            AiJson.decodeFromString(AiUiSnapshot.serializer(), json)
        }.onFailure {
            AiAgentLog.e("snapshot", "JSON 反序列化失败：${it.message}", it)
        }.getOrNull() ?: return@withContext null

        val clickable = snapshot.nodes.count { it.clickable }
        val editable = snapshot.nodes.count { it.editable }
        val scrollable = snapshot.nodes.count { it.scrollable }
        AiAgentLog.i(
            "snapshot",
            "pkg=${snapshot.packageName} act=${snapshot.activityName} " +
                    "size=${snapshot.screenWidth}x${snapshot.screenHeight} " +
                    "nodes=${snapshot.nodes.size} (clickable=$clickable editable=$editable scrollable=$scrollable)"
        )
        snapshot
    }
}

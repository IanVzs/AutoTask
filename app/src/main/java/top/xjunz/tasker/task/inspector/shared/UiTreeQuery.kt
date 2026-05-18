/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import android.util.Log
import top.xjunz.tasker.service.A11yAutomatorService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.inspector.StableNodeInfo
import top.xjunz.tasker.task.inspector.StableNodeInfo.Companion.freeze

/**
 * Inspector / AI agent / 任何"读屏"功能共用的最小公共抽象。
 *
 * **当前的边界**（按 `aidoc/02 §2 进程模型` + `aidoc/09 §9 AI 警告`）：
 *
 * - **本类只做 `A11yAutomatorService.rootInActiveWindow` 路径**——只供 inspector overlay 使用，
 *   它本来就只在 a11y 模式工作。
 * - AI agent **不再走本类**：agent 必须通过
 *   [top.xjunz.tasker.service.AutomatorService.captureUiSnapshotJson] 跨进程拿快照，
 *   由执行端进程（Shizuku 特权进程或主进程的 a11y service）做读屏 + 压缩 + JSON 序列化。
 *   见 [top.xjunz.tasker.ai.agent.ScreenSnapshotProvider]。
 *
 * 之前我把 [rawRoot] / `findNodes` / `findStableNodes` 等"主进程直接读 root + 遍历"的方法
 * 暴露给 agent，导致 Shizuku 模式下抛 `ensurePrivilegedProcess()`。已删除，避免后人复制粘贴再次踩坑。
 */
object UiTreeQuery {

    private const val TAG = "UiTreeQuery"

    /**
     * AutomatorService（a11y 或 Shizuku）是否处于可用状态。Inspector / agent 在做事之前都应该
     * 先调一下，没连直接给用户提示，避免业务方拿到 null 还以为只是临时窗口切换。
     */
    fun isAutomatorServiceRunning(): Boolean =
        runCatching { serviceController.isServiceRunning }.getOrDefault(false)

    /**
     * Inspector 专用的"抓 root + freeze"。**只在 a11y 模式工作**——主进程能访问到
     * [A11yAutomatorService.rootInActiveWindow]。Shizuku 模式 a11y service 没连时返回 null。
     *
     * AI agent **不要调本方法**，请走
     * [top.xjunz.tasker.ai.agent.ScreenSnapshotProvider.capture]。
     */
    fun captureFrozenRootForInspector(): StableNodeInfo? {
        val service = A11yAutomatorService.get()
        if (service == null) {
            Log.w(TAG, "captureFrozenRootForInspector=null reason=a11y_service_not_connected")
            return null
        }
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root == null) {
            Log.w(TAG, "captureFrozenRootForInspector=null reason=root_null")
            return null
        }
        return runCatching { root.freeze() }
            .onFailure { Log.w(TAG, "captureFrozenRootForInspector=null reason=freeze_throw msg=${it.message}", it) }
            .getOrNull()
    }
}

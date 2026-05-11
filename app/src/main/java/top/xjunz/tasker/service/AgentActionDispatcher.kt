/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.service

import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.tasker.ai.agent.AiAgentLog
import top.xjunz.tasker.engine.dto.toDTO
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.isAppProcess
import top.xjunz.tasker.task.inspector.shared.AiAgentTaskAssembler
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager
import top.xjunz.tasker.task.runtime.PrivilegedTaskManager

/**
 * AI agent「按 target 描述执行单步动作」的执行端共享派发器。
 *
 * A11y / Shizuku 两个 [AutomatorService] 实现的 `executeAgentActionByTarget` 共用这一段，
 * 避免两边各写一份漂移；详见 aidoc/17。
 *
 * 流程（KISS）：
 * 1. parseTarget：JSON → AiUiTarget；空 / 损坏直接 `callback(false)` 返回
 * 2. captureRoot：拿当前焦点窗口 root（A11y 模式 = `rootInActiveWindow`，Shizuku 特权进程 = `uiAutomation.rootInActiveWindow`）
 * 3. findRealNode：用 target 弱字段在 root 子树里 BFS 找真节点；找不到就失败
 * 4. buildTaskFromRealNode：用 `NodeCriteriaExtractor.extract(node)` 抽出**完整 11+ 字段**的 criteria，
 *    包成跟 inspector「自动点击」完全等价的 task 树
 * 5. 注册到 `LocalTaskManager` / `PrivilegedTaskManager`（两边的 manager 在自己的进程里），
 *    再通过 [scheduler]（每个 service 自己的 `scheduleOneshotTask` 入口）派给 oneshot scheduler
 * 6. callback 真实回报 task isSuccessful
 *
 * 这一段全程**0 网络 / 0 跨进程**——它在执行端进程内部跑，捕获到 root + 抽 criteria + 跑 task 一气呵成，
 * 跟 inspector 用户挑节点保存的 task 走完全同款路径。
 */
internal object AgentActionDispatcher {

    fun dispatch(
        actionType: Int,
        targetJson: String,
        extraText: String?,
        scheduler: (XTask, ITaskCompletionCallback) -> Unit,
        captureRoot: () -> AccessibilityNodeInfo?,
        callback: ITaskCompletionCallback
    ) {
        val procTag = if (isAppProcess) "main" else "service"
        AiAgentLog.i("dispatcher.$procTag", "enter actionType=$actionType json.len=${targetJson.length} extra=${extraText?.length ?: -1}")

        // **顶层 try-catch**：任何阶段抛异常都通过 callback(false) 反馈，**不要**让异常穿过 binder
        // 让 main 端只看到 "Required value was null" 这种没头没尾的消息——而是 service 端先在 logcat
        // 里详细打印异常 + 堆栈，再回报失败。这是修 5/11 14:52 NPE 的根本兜底。
        try {
            val target = AiAgentTaskAssembler.parseTarget(targetJson)
            if (target == null) {
                AiAgentLog.w("dispatcher.$procTag", "parseTarget 返回 null（JSON 损坏 / target 全空）；json=$targetJson")
                safeComplete(callback, false)
                return
            }
            AiAgentLog.d("dispatcher.$procTag", "parseTarget OK: $target")

            val root = captureRoot()
            if (root == null) {
                AiAgentLog.w("dispatcher.$procTag", "captureRoot 返回 null（rootInActiveWindow 不可用）")
                safeComplete(callback, false)
                return
            }
            AiAgentLog.d("dispatcher.$procTag", "captureRoot OK: pkg=${root.packageName} class=${root.className}")

            val node = AiAgentTaskAssembler.findRealNode(root, target)
            if (node == null) {
                AiAgentLog.w("dispatcher.$procTag", "findRealNode 没找到匹中 target 的节点；target=$target")
                safeComplete(callback, false)
                return
            }
            AiAgentLog.i(
                "dispatcher.$procTag",
                "命中节点 className=${node.className} viewId=${node.viewIdResourceName} " +
                        "text=\"${node.text}\" desc=\"${node.contentDescription}\" " +
                        "clickable=${node.isClickable} editable=${node.isEditable}"
            )

            val task = AiAgentTaskAssembler.buildTaskFromRealNode(node, actionType, extraText)
            if (task == null) {
                AiAgentLog.w("dispatcher.$procTag", "buildTaskFromRealNode 返回 null（criteria 全空 / actionType 不识别）")
                safeComplete(callback, false)
                return
            }
            AiAgentLog.i(
                "dispatcher.$procTag",
                "task 组装完成 checksum=${task.metadata.checksum} title=\"${task.metadata.title}\""
            )

            // 注册到当前进程的 task manager
            try {
                if (isAppProcess) {
                    LocalTaskManager.addOneshotTaskIfAbsent(task)
                } else {
                    PrivilegedTaskManager.addOneshotTaskIfAbsent(task.toDTO())
                }
            } catch (t: Throwable) {
                AiAgentLog.e("dispatcher.$procTag", "addOneshotTaskIfAbsent 失败：${t.message}", t)
                safeComplete(callback, false)
                return
            }
            AiAgentLog.d("dispatcher.$procTag", "task 已注册到 ${if (isAppProcess) "LocalTaskManager" else "PrivilegedTaskManager"}")

            // 派给 scheduler 跑——这一步内部异常会被 oneshotTaskScheduler 自己 catch，本层不再包
            try {
                scheduler(task, callback)
            } catch (t: Throwable) {
                AiAgentLog.e("dispatcher.$procTag", "scheduler 调度异常：${t.message}", t)
                safeComplete(callback, false)
            }
        } catch (t: Throwable) {
            // 任何上面没单独捕获的异常都走这里——比 binder 跨进程透传 NPE 更友好
            AiAgentLog.e("dispatcher.$procTag", "dispatch 顶层异常：${t::class.simpleName}: ${t.message}", t)
            safeComplete(callback, false)
        }
    }

    private fun safeComplete(cb: ITaskCompletionCallback, ok: Boolean) {
        runCatching { cb.onTaskCompleted(ok) }
    }
}

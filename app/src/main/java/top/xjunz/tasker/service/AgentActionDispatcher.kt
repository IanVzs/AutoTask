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

    /**
     * 最近一次 [dispatch] 的诊断信息（aidoc/18 §3.E.2 + §3.D）。
     * 任何分支失败 / 拒收都写进这里，main 端通过 [AutomatorService.getLastAgentDiagnostic]
     * 在 callback isSuccessful=false 后拉走，写进 step.result.message 喂给 AI 反思。
     *
     * 单实例 agent session 串行跑，不存在并发覆盖。多 agent 场景需要扩成 keyed map（暂不需要）。
     */
    @Volatile
    private var lastDiagnostic: String = ""

    fun getLastDiagnostic(): String = lastDiagnostic

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
        // 每次 enter 重置诊断；若中途失败会被赋值，成功时清成 "ok"
        lastDiagnostic = ""

        // **顶层 try-catch**：任何阶段抛异常都通过 callback(false) 反馈，**不要**让异常穿过 binder
        // 让 main 端只看到 "Required value was null" 这种没头没尾的消息——而是 service 端先在 logcat
        // 里详细打印异常 + 堆栈，再回报失败。这是修 5/11 14:52 NPE 的根本兜底。
        try {
            val target = AiAgentTaskAssembler.parseTarget(targetJson)
            if (target == null) {
                lastDiagnostic = "AI 给的 target JSON 损坏或所有字段都空，无法定位节点；原文=$targetJson"
                AiAgentLog.w("dispatcher.$procTag", lastDiagnostic)
                safeComplete(callback, false)
                return
            }
            AiAgentLog.d("dispatcher.$procTag", "parseTarget OK: $target")

            val root = captureRoot()
            if (root == null) {
                lastDiagnostic = "执行端拿不到当前焦点窗口的节点树（rootInActiveWindow=null）；可能 a11y 服务没连或窗口刚切换，下一轮 wait 再看"
                AiAgentLog.w("dispatcher.$procTag", lastDiagnostic)
                safeComplete(callback, false)
                return
            }
            AiAgentLog.d("dispatcher.$procTag", "captureRoot OK: pkg=${root.packageName} class=${root.className}")

            val node = AiAgentTaskAssembler.findRealNode(root, target)
            if (node == null) {
                // **E.2 细化失败原因**：BFS 找最近候选 + 列出 target 各字段哪些不符
                lastDiagnostic = diagnoseNoMatch(root, target)
                AiAgentLog.w("dispatcher.$procTag", "findRealNode 没找到匹中节点；diagnostic=$lastDiagnostic")
                safeComplete(callback, false)
                return
            }
            AiAgentLog.i(
                "dispatcher.$procTag",
                "命中节点 className=${node.className} viewId=${node.viewIdResourceName} " +
                        "text=\"${node.text}\" desc=\"${node.contentDescription}\" " +
                        "clickable=${node.isClickable} editable=${node.isEditable}"
            )

            // **D guardrails 预检查**：跑 task 之前先按 actionType 验证语义。
            // 避免浪费一次 perform，并能给 AI 精准反馈"为什么这个节点不能干这事"。
            val precheckMsg = preCheckActionSemantics(node, actionType)
            if (precheckMsg != null) {
                lastDiagnostic = precheckMsg
                AiAgentLog.w("dispatcher.$procTag", "preCheck 拒收：$precheckMsg")
                safeComplete(callback, false)
                return
            }

            val task = AiAgentTaskAssembler.buildTaskFromRealNode(node, actionType, extraText)
            if (task == null) {
                lastDiagnostic = "task 组装失败（criteria 全空 / actionType=$actionType 不识别）"
                AiAgentLog.w("dispatcher.$procTag", lastDiagnostic)
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

            // 派给 scheduler 跑——内部异常会被 oneshotTaskScheduler 自己 catch；包一层 wrapper callback
            // 用来把 task 的成败映射成有语义的诊断（如"task 跑通"或"perform 失败可能 RN 控件不响应"）。
            try {
                val wrapped = object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        if (isSuccessful) {
                            lastDiagnostic = "ok: 命中节点 ${nodeBrief(node)} 并 perform 成功"
                        } else {
                            // task 跑失败但节点匹中——通常是 perform 在 RN/Compose 自绘控件上 silent fail
                            lastDiagnostic = "task perform 返回失败：节点 ${nodeBrief(node)} 命中了但执行没生效。" +
                                    "可能原因：RN/Compose 自绘控件不响应 a11y action（建议改用 set_text+keyevent 兜底 / 改 click 父容器）"
                        }
                        runCatching { callback.onTaskCompleted(isSuccessful) }
                    }
                }
                scheduler(task, wrapped)
            } catch (t: Throwable) {
                lastDiagnostic = "task 调度异常：${t::class.simpleName}: ${t.message}"
                AiAgentLog.e("dispatcher.$procTag", lastDiagnostic, t)
                safeComplete(callback, false)
            }
        } catch (t: Throwable) {
            lastDiagnostic = "dispatch 顶层异常：${t::class.simpleName}: ${t.message}"
            AiAgentLog.e("dispatcher.$procTag", lastDiagnostic, t)
            safeComplete(callback, false)
        }
    }

    private fun safeComplete(cb: ITaskCompletionCallback, ok: Boolean) {
        runCatching { cb.onTaskCompleted(ok) }
    }

    /**
     * D guardrails 预检查：根据 [actionType] 验证节点语义，不通过返回拒收原因；通过返回 null。
     */
    private fun preCheckActionSemantics(node: AccessibilityNodeInfo, actionType: Int): String? {
        return when (actionType) {
            AiAgentTaskAssembler.ACTION_SET_TEXT -> {
                if (!node.isEditable) {
                    "set_text 拒收：命中节点 ${nodeBrief(node)} **不可编辑**（isEditable=false）。" +
                            "通常意味着这不是真输入框，是占位文字 / 显示文本。" +
                            "请重新选**带 [E] 标记**的节点；当前页面没有 [E] 节点就先 click 进入有输入框的页面"
                } else null
            }
            AiAgentTaskAssembler.ACTION_CLICK,
            AiAgentTaskAssembler.ACTION_LONG_CLICK -> {
                // click 不严格要求 isClickable=true（自绘控件 parent 可能可点），不拒收
                // 但完全不可点 + parent 也不可点的纯文本节点会让 perform silent fail；只 warn 不拒
                null
            }
            AiAgentTaskAssembler.ACTION_SWIPE -> {
                if (!node.isScrollable) {
                    "swipe 拒收：命中节点 ${nodeBrief(node)} **不可滚动**（isScrollable=false）。" +
                            "请改选 [S] 标记的节点（ScrollView / RecyclerView 等可滚动容器）"
                } else null
            }
            else -> null
        }
    }

    /**
     * 当 [findRealNode] 返回 null 时给 AI 的精细反馈：BFS 整棵树，找跟 target 字段**最接近**的节点，
     * 列出它哪些字段不符。比之前笼统的"未找到匹中"信息量高一个数量级。
     */
    private fun diagnoseNoMatch(root: AccessibilityNodeInfo, target: top.xjunz.tasker.ai.agent.AiUiTarget): String {
        // 为每个节点算一个"近似度"得分，挑分最高的报告
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        var bestMismatch = ""
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val n = queue.removeFirst()
            visited++
            val (score, mismatch) = scoreSimilarity(n, target)
            if (score > bestScore) {
                bestScore = score
                best = n
                bestMismatch = mismatch
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return if (best != null && bestScore > 0) {
            "在当前屏幕 ${visited} 个节点里没找到完全匹中 target=${targetBrief(target)} 的节点。" +
                    "**最接近的节点**：${nodeBrief(best)}；**不符的字段**：$bestMismatch。" +
                    "可能 target 字段定得太严（比如 className 写死成 TextView 但真节点是 LinearLayout），" +
                    "建议**只用 textEquals 或 contentDescEquals 这类语义字段，不带 className**重试"
        } else {
            "在当前屏幕 ${visited} 个节点里完全找不到任何近似 target=${targetBrief(target)} 的节点。" +
                    "可能页面已切换 / 节点已消失；建议下一步用 wait 重抓快照或 scroll 寻找"
        }
    }

    /** 给一个节点算跟 target 各字段的命中度，并返回不匹中的字段列表。 */
    private fun scoreSimilarity(node: AccessibilityNodeInfo, t: top.xjunz.tasker.ai.agent.AiUiTarget): Pair<Int, String> {
        var score = 0
        val mismatches = mutableListOf<String>()
        if (!t.viewId.isNullOrBlank()) {
            if (node.viewIdResourceName == t.viewId) score += 4 else mismatches.add("viewId 不符（实际=${node.viewIdResourceName}）")
        }
        if (!t.textEquals.isNullOrBlank()) {
            val nt = node.text?.toString()
            if (nt == t.textEquals) score += 3 else mismatches.add("text 不符（实际=\"$nt\"）")
        }
        if (!t.textContains.isNullOrBlank()) {
            val nt = node.text?.toString().orEmpty()
            if (nt.contains(t.textContains!!)) score += 2 else mismatches.add("text 不含「${t.textContains}」（实际=\"$nt\"）")
        }
        if (!t.contentDescEquals.isNullOrBlank()) {
            val cd = node.contentDescription?.toString()
            if (cd == t.contentDescEquals) score += 3 else mismatches.add("desc 不符（实际=\"$cd\"）")
        }
        if (!t.contentDescContains.isNullOrBlank()) {
            val cd = node.contentDescription?.toString().orEmpty()
            if (cd.contains(t.contentDescContains!!)) score += 2 else mismatches.add("desc 不含「${t.contentDescContains}」（实际=\"$cd\"）")
        }
        if (!t.className.isNullOrBlank()) {
            val cls = node.className?.toString().orEmpty()
            val matches = cls.equals(t.className, ignoreCase = true) ||
                    cls.substringAfterLast('.').equals(t.className, ignoreCase = true)
            if (matches) score += 1 else mismatches.add("className 不符（实际=${cls.substringAfterLast('.')}）")
        }
        return score to mismatches.joinToString("; ").ifEmpty { "无不符字段" }
    }

    private fun nodeBrief(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }?.let { "text=\"$it\"" }.orEmpty()
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { "desc=\"$it\"" }.orEmpty()
        val flags = buildString {
            if (node.isClickable) append('C')
            if (node.isEditable) append('E')
            if (node.isScrollable) append('S')
            if (isEmpty()) append('-')
        }
        return listOf(cls, text, desc, "[$flags]").filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun targetBrief(t: top.xjunz.tasker.ai.agent.AiUiTarget): String {
        val parts = mutableListOf<String>()
        t.viewId?.takeIf { it.isNotBlank() }?.let { parts.add("id=$it") }
        t.textEquals?.takeIf { it.isNotBlank() }?.let { parts.add("text=$it") }
        t.textContains?.takeIf { it.isNotBlank() }?.let { parts.add("text~$it") }
        t.contentDescEquals?.takeIf { it.isNotBlank() }?.let { parts.add("desc=$it") }
        t.contentDescContains?.takeIf { it.isNotBlank() }?.let { parts.add("desc~$it") }
        t.className?.takeIf { it.isNotBlank() }?.let { parts.add("cls=$it") }
        return parts.joinToString(",")
    }
}

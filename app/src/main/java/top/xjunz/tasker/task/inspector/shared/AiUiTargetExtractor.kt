/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.tasker.ai.agent.AiUiTarget

/**
 * 真实 [AccessibilityNodeInfo] → [AiUiTarget]：把"用户手动挑的真节点"翻译成 agent 能用的定位条件。
 *
 * 主要用于决策面板的"换一个"按钮：
 * 1. 用户认为 AI 选的 target 不对
 * 2. 用户从候选列表 / inspector 里挑了一个真节点
 * 3. 调 [extract] 拿到 AiUiTarget
 * 4. 替换原 [top.xjunz.tasker.ai.agent.AiAgentAction] 的 `target`，让 executor 用新 target 二次定位
 *
 * 字段优先级跟 [NodeCriteriaExtractor] 的 if 顺序对齐：viewId > textEquals > contentDescEquals > className。
 * AI agent 走这条路径替换 target 后，`buildHistorySection` 里也会原样显示，让 AI 在下一轮看到
 * "用户帮我挑了这个节点"，自我学习。
 */
object AiUiTargetExtractor {

    /**
     * 把节点的可识别属性全打包成 [AiUiTarget]，但单一字段命中能力就够了；多字段 AND 提高鲁棒性。
     *
     * 默认行为：把所有能拿到的字段都填上，让 [AiUiTarget.matches] 多重保险。如果命中节点过少
     * （字段太严），调用方可以选择只保留其中最强的一项再调 [AiUiTarget.copy]。
     */
    fun extract(node: AccessibilityNodeInfo): AiUiTarget {
        val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val className = node.className?.toString()
            ?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        return AiUiTarget(
            viewId = viewId,
            textEquals = text,
            contentDescEquals = contentDesc,
            className = className
        )
    }

    /**
     * 严格版本：只保留**唯一最强**的一项定位字段。优先级 viewId > textEquals > contentDescEquals > className。
     * 当某些 App 的节点 text 会随时间变化（动态计数 / 时间戳）时，AND 多字段反而容易失配，
     * 这种场景适合用 [extractStrictest]。
     */
    fun extractStrictest(node: AccessibilityNodeInfo): AiUiTarget {
        val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
        if (viewId != null) return AiUiTarget(viewId = viewId)

        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) return AiUiTarget(textEquals = text)

        val contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (contentDesc != null) return AiUiTarget(contentDescEquals = contentDesc)

        val className = node.className?.toString()
            ?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        if (className != null) return AiUiTarget(className = className)

        // 全空，返回空 target（agent 拿到会判定为 Unknown 并继续走它的兜底）
        return AiUiTarget()
    }
}

/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.serialization.Serializable

/**
 * 给 AI 看的"当前屏幕子集"。原始 AccessibilityNodeInfo 树往往动辄几百个节点，必须经过
 * [ScreenSnapshotProvider] 压缩为可交互或带文本的扁平节点列表后再喂给模型，否则 token
 * 浪费严重，且模型容易在不重要的容器节点上发挥幻觉。
 */
@Serializable
data class AiUiSnapshot(
    /** 抓取时刻，让模型知道这是"刚刚"的画面而不是历史。 */
    val captureTimeMillis: Long,
    /** 当前焦点窗口归属的包名，用于配合 [AiTaskScope] 做 App 范围检查。 */
    val packageName: String?,
    /** 当前焦点 Activity 的短类名（包名段裁掉后），可选。 */
    val activityName: String?,
    /** 屏幕物理像素宽度，AI 用于理解 bounds 大致占比。 */
    val screenWidth: Int,
    /** 屏幕物理像素高度。 */
    val screenHeight: Int,
    /** 已扁平化的节点列表，按"父在前，子在后"的顺序。 */
    val nodes: List<AiUiNode>
)

/**
 * 压缩后的单个节点。`id` 是本次快照内的稳定编号（从 0 开始，顺序与 [AiUiSnapshot.nodes]
 * 中的下标一致），`parentId` 指向同一快照里被保留的最近祖先（如果中间被剪掉的容器跨级保留）。
 *
 * **AI 不应**直接把 `id` 用作执行点击的目标，应通过 [AiUiTarget] 描述定位条件，由本地代码在
 * 执行瞬间用最新的真实节点树二次匹配。这样可以容忍快照与执行之间的微小差异。
 */
@Serializable
data class AiUiNode(
    val id: Int,
    val parentId: Int? = null,
    val className: String,
    val viewId: String? = null,
    val text: String? = null,
    val contentDesc: String? = null,
    val bounds: AiBounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val scrollable: Boolean = false,
    val focused: Boolean = false
)

@Serializable
data class AiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * AI 描述"我要操作的那个节点"用的定位条件。AI 在 prompt 里不被允许直接发节点 id，只能发
 * 这种"几个字段做 AND 匹配"的描述；本地代码在执行瞬间用最新的 [AiUiSnapshot] 或者直接
 * 在真实 AccessibilityNodeInfo 树里二次定位。
 *
 * 字段全部可选；至少需要任一字段非空才能成功匹配。`matchIndex` 在多结果时取第几个，默认第 0 个。
 */
@Serializable
data class AiUiTarget(
    val viewId: String? = null,
    val textEquals: String? = null,
    val textContains: String? = null,
    val contentDescEquals: String? = null,
    val contentDescContains: String? = null,
    val className: String? = null,
    val matchIndex: Int = 0
) {
    val isEmpty: Boolean
        get() = viewId.isNullOrBlank() &&
                textEquals.isNullOrBlank() && textContains.isNullOrBlank() &&
                contentDescEquals.isNullOrBlank() && contentDescContains.isNullOrBlank() &&
                className.isNullOrBlank()

    /**
     * 在快照里筛出所有匹配节点。`matchIndex` 边界检查由调用方负责。
     */
    fun filter(snapshot: AiUiSnapshot): List<AiUiNode> {
        if (isEmpty) return emptyList()
        return snapshot.nodes.filter { matches(it) }
    }

    fun matches(node: AiUiNode): Boolean {
        if (!viewId.isNullOrBlank() && node.viewId != viewId) return false
        if (!textEquals.isNullOrBlank() && node.text != textEquals) return false
        if (!textContains.isNullOrBlank()
            && (node.text.isNullOrEmpty() || textContains !in node.text)
        ) return false
        if (!contentDescEquals.isNullOrBlank() && node.contentDesc != contentDescEquals) return false
        if (!contentDescContains.isNullOrBlank()
            && (node.contentDesc.isNullOrEmpty() || contentDescContains !in node.contentDesc)
        ) return false
        if (!className.isNullOrBlank()) {
            val nodeClass = node.className
            if (!nodeClass.equals(className, ignoreCase = true) && !nodeClass.endsWith(className)) {
                return false
            }
        }
        return true
    }
}

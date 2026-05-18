/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.agent.AiBounds
import top.xjunz.tasker.ai.agent.AiUiNode
import top.xjunz.tasker.ai.agent.AiUiSnapshot
import top.xjunz.tasker.task.inspector.StableNodeInfo

/**
 * 把一棵 [StableNodeInfo] 节点树压缩 + 序列化成 [AiUiSnapshot] JSON 字符串，供 AI 跨进程使用。
 *
 * **用在哪儿**：
 * - 跨进程：`ShizukuAutomatorService.captureUiSnapshotJson` 在 `:service` 特权进程里调本方法，
 *   返回的 JSON 通过 AIDL 跨到主进程，由 [top.xjunz.tasker.ai.agent.ScreenSnapshotProvider] 反序列化。
 * - 同进程：`A11yAutomatorService.captureUiSnapshotJson` 在主进程调，省得序列化去同进程意义不大，
 *   但保持接口签名一致便于两端透明分发。
 *
 * 压缩规则与早期 `ScreenSnapshotProvider.compactNode` 严格一致（避免 prompt 里 AI 看到的字段集合改变
 * 导致行为漂移）：
 * - 只保留**可交互节点**（clickable / longClickable / editable / scrollable）
 * - 或保留**有显示文本 / contentDescription** 的叶节点
 * - 屏幕外节点直接丢弃
 * - 文本字段截断到 `maxTextLen` 字符
 * - 节点总数硬上限 `maxNodes`，超出按 DFS 顺序截断
 * - 被剪掉的容器，子节点的 `parentId` 跨级继承到最近保留的祖先
 *
 * 这套逻辑必须**不依赖任何主进程 only 的资源**（不依赖 a11y service 单例 / Application），只接受参数 +
 * 节点树 + 屏幕尺寸；这样在特权进程里也能跑。
 */
object AiNodeTreeCompactor {

    /**
     * @param rootNode 已经 freeze 过的节点树根。调用方负责把 [AccessibilityNodeInfo.freeze] 跑完。
     * @param screenWidth 屏幕物理像素宽度，用来计算节点 visible bounds。
     * @param screenHeight 屏幕物理像素高度。
     * @param packageName 当前焦点 App 包名（拿不到则 null）。
     * @param activityName 当前焦点 Activity 短类名（拿不到则 null）。
     * @param maxNodes 节点数上限，超出按 DFS 顺序截断。
     * @param maxTextLen 单字段文本长度上限，超过截断。
     * @return [AiUiSnapshot] 序列化的 JSON 字符串；调用方用 [AiJson.decodeFromString] 反序列化。
     */
    fun compactToJson(
        rootNode: StableNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String?,
        activityName: String?,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxTextLen: Int = DEFAULT_MAX_TEXT_LEN
    ): String {
        val global = Rect(0, 0, screenWidth, screenHeight)
        val out = ArrayList<AiUiNode>(64)
        compactNode(rootNode, parentCompactId = null, global = global, out = out, maxNodes = maxNodes, maxTextLen = maxTextLen)
        val snapshot = AiUiSnapshot(
            captureTimeMillis = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = out
        )
        return AiJson.encodeToString(AiUiSnapshot.serializer(), snapshot)
    }

    const val DEFAULT_MAX_NODES = 80
    const val DEFAULT_MAX_TEXT_LEN = 80

    private fun compactNode(
        node: StableNodeInfo,
        parentCompactId: Int?,
        global: Rect,
        out: MutableList<AiUiNode>,
        maxNodes: Int,
        maxTextLen: Int
    ) {
        if (out.size >= maxNodes) return
        val src = node.source
        val bounds = node.getVisibleBoundsIn(global)
        val visibleOnScreen = !bounds.isEmpty && Rect.intersects(bounds, global)
        val isInteractive =
            src.isClickable || src.isLongClickable || src.isEditable || src.isScrollable
        val hasText = !src.text.isNullOrBlank() || !src.contentDescription.isNullOrBlank()
        // 加保留：搜索框 / 输入框 / 可聚焦节点也保留——很多 App 把搜索框做成
        // EditText/SearchView 但既不 isClickable 也无 text/desc（hint 不是 text），
        // 之前的规则会把它剪掉，导致 AI 在节点列表里根本看不到搜索入口。
        val isLikelyInputOrSearch = isLikelyInputOrSearchNode(src)
        val isFocusable = src.isFocusable
        val keep = visibleOnScreen && (isInteractive || hasText || isLikelyInputOrSearch || isFocusable)

        val newParentId: Int? = if (keep) {
            val id = out.size
            out.add(toAiUiNode(id, parentCompactId, src, bounds, maxTextLen))
            id
        } else {
            parentCompactId
        }
        for (child in node.children) {
            compactNode(child, newParentId, global, out, maxNodes, maxTextLen)
            if (out.size >= maxNodes) break
        }
    }

    /**
     * 判断节点"很可能是搜索 / 输入入口"。优先看 className 末段：
     * EditText / AutoCompleteTextView / SearchView / SearchBar / TextInputEditText 都算。
     * viewIdResourceName 含 "search" / "input" / "edit" 等关键字也算。
     * 注意全部 case-insensitive，避免 OEM / 第三方控件大小写差异漏过。
     */
    private fun isLikelyInputOrSearchNode(src: AccessibilityNodeInfo): Boolean {
        val cls = src.className?.toString()?.substringAfterLast('.')?.lowercase().orEmpty()
        if (cls.isNotEmpty() && SEARCH_INPUT_CLASS_KEYWORDS.any { cls.contains(it) }) return true
        val viewId = src.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.isNotEmpty() && SEARCH_INPUT_ID_KEYWORDS.any { viewId.contains(it) }) return true
        return false
    }

    private val SEARCH_INPUT_CLASS_KEYWORDS = listOf(
        "edittext", "autocompletetextview", "searchview", "searchbar",
        "textinputedittext", "textinputlayout"
    )

    private val SEARCH_INPUT_ID_KEYWORDS = listOf(
        "search", "input", "edit", "query", "keyword"
    )

    @Suppress("DEPRECATION")
    private fun toAiUiNode(
        id: Int,
        parentId: Int?,
        src: AccessibilityNodeInfo,
        bounds: Rect,
        maxTextLen: Int
    ): AiUiNode {
        val rawClass = src.className?.toString().orEmpty()
        return AiUiNode(
            id = id,
            parentId = parentId,
            className = rawClass.substringAfterLast('.').ifEmpty { rawClass },
            viewId = src.viewIdResourceName?.takeIf { it.isNotBlank() },
            text = src.text?.toString()?.takeIf { it.isNotBlank() }?.take(maxTextLen),
            contentDesc = src.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?.take(maxTextLen),
            bounds = AiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = src.isClickable,
            longClickable = src.isLongClickable,
            editable = src.isEditable,
            checkable = src.isCheckable,
            checked = src.isChecked,
            enabled = src.isEnabled,
            scrollable = src.isScrollable,
            focused = src.isFocused
        )
    }
}

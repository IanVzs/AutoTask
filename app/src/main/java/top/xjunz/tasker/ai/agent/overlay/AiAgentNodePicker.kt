/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.overlay

import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiUiNode
import top.xjunz.tasker.ai.agent.AiUiSnapshot
import top.xjunz.tasker.ai.agent.AiUiTarget

/**
 * "换一个"按钮背后的节点选择器抽象。
 *
 * 用户点击"换一个"时，决策面板调 [pickReplacement]，由具体实现决定怎么帮用户挑一个**真实节点**
 * 来替换 AI 选错的 target。返回 null = 用户最终放弃替换（重新点了"取消"）。
 *
 * 当前提供 V1 实现 [CandidateListPicker]：从快照里挑 5 个最像的，让用户在 overlay 内部选。
 * 未来 V2 计划提供 [InspectorPickerStub] 的真实实现：调起 FloatingInspector，让用户在屏幕上**直接点目标节点**。
 */
interface AiAgentNodePicker {

    /**
     * @param original AI 原本选的动作（含 target）。
     * @param snapshot 决策面板拿到的当前屏幕快照。
     * @return 用户最终选定的节点定位条件；用户放弃替换则 null。
     */
    suspend fun pickReplacement(
        original: AiAgentAction,
        snapshot: AiUiSnapshot
    ): AiUiTarget?
}

/**
 * V1 实现：基于当前快照，按"与 AI 原 target 相似度"排序，挑出前 [topN] 个候选，
 * 由调用方（决策面板 UI）展开成一个内嵌列表让用户挑选。
 *
 * 排序规则（粗暴但够用）：
 * 1. 节点必须是可交互（clickable / longClickable / editable / scrollable）或带 text/contentDesc。
 * 2. 加分项：
 *    - viewId 跟 AI 原 target.viewId 相同 +10
 *    - text 包含 AI 原 target.textEquals/textContains +5
 *    - className 跟 AI 原 target.className 相同 +3
 *    - 自身 clickable +1（最常见的可点击目标）
 * 3. 同分按 snapshot 顺序保留（早出现的可能在屏幕上更显眼）。
 *
 * **不**真的弹 UI——picker 是纯 data 选择器，弹 UI 由决策面板自己负责。
 * 这里实现的是 [shortlist]，决策面板把 list 交给用户选，再调 [select] 完成 [pickReplacement]。
 *
 * 这种"两步走"是有意为之，让 V2（FloatingInspector 接管）替换 V1 时无需改决策面板交互模式。
 */
class CandidateListPicker(
    private val onPresent: suspend (List<AiUiNode>) -> AiUiNode?,
    private val topN: Int = 5
) : AiAgentNodePicker {

    override suspend fun pickReplacement(
        original: AiAgentAction,
        snapshot: AiUiSnapshot
    ): AiUiTarget? {
        val originalTarget = originalTarget(original)
        val candidates = shortlist(snapshot, originalTarget, topN)
        if (candidates.isEmpty()) return null
        val picked = onPresent(candidates) ?: return null
        return AiUiTarget(
            viewId = picked.viewId,
            textEquals = picked.text,
            contentDescEquals = picked.contentDesc,
            className = picked.className
        )
    }

    companion object {

        private fun originalTarget(action: AiAgentAction): AiUiTarget? = when (action) {
            is AiAgentAction.Click -> action.target
            is AiAgentAction.LongClick -> action.target
            is AiAgentAction.SetText -> action.target
            is AiAgentAction.Scroll -> action.target
            else -> null
        }

        /**
         * 公开给决策面板用：只算"挑出 top N 候选"，不调 onPresent。便于 UI 自己决定怎么呈现。
         *
         * **筛选铁律**：候选必须**对人有意义**——即必须满足以下任一：
         * - 自身有 text 或 contentDesc（人能看懂的语义）
         * - 自身可编辑 [E]（输入框，跟 set_text 配对）
         * - 自身可滚动 [S]（list / scroll view，跟 scroll 配对）
         *
         * **不收**纯 clickable 但完全没 text/desc 的容器——它们在候选列表里只能显示成
         * "区域 · 屏幕 X" 的废话，对用户挑选毫无帮助（这是用户在 deepseek 测试里反馈的痛点）。
         *
         * 排序综合三维度：
         * 1. 跟 AI 原 target 的语义相似度（text/desc/className 命中），最重要；
         * 2. 节点本身或 parent 是否真的可触发（[C] 或 parent.clickable=true）；
         * 3. 在屏幕上的位置——同分按 y 坐标从上到下排（更符合人扫视习惯）。
         */
        fun shortlist(snapshot: AiUiSnapshot, originalTarget: AiUiTarget?, topN: Int): List<AiUiNode> {
            if (snapshot.nodes.isEmpty()) return emptyList()
            // 建 parentId → node 索引，用来判断"我自己虽然 [-] 但 parent 是 [C]"的子节点
            val byId = snapshot.nodes.associateBy { it.id }
            return snapshot.nodes
                .asSequence()
                .filter { isUserMeaningful(it) }
                .sortedWith(
                    compareByDescending<AiUiNode> { score(it, originalTarget, byId) }
                        .thenBy { it.bounds.top }
                        .thenBy { it.bounds.left }
                )
                .take(topN)
                .toList()
        }

        /** 节点是否对用户"有意义"——决定能否进候选列表。 */
        private fun isUserMeaningful(node: AiUiNode): Boolean {
            if (!node.text.isNullOrBlank()) return true
            if (!node.contentDesc.isNullOrBlank()) return true
            if (node.editable) return true     // 输入框即使无 text 也要展示（可能 hint 没显示出来）
            if (node.scrollable) return true   // 列表/可滚区域，跟 scroll action 配对
            return false                       // 纯无 text 容器一律不收
        }

        private fun score(node: AiUiNode, t: AiUiTarget?, byId: Map<Int, AiUiNode>): Int {
            var s = 0
            // 自身或 parent 真可点 → 加分（"发送"按钮是子节点 [-]、parent [C] 的常见模式）
            if (node.clickable) s += 2
            else if (node.parentId?.let { byId[it]?.clickable } == true) s += 1
            if (node.editable) s += 2
            if (t == null) return s
            // 语义相似度（text/desc 命中是最强信号，权重高）
            if (!t.viewId.isNullOrBlank() && node.viewId == t.viewId) s += 8
            val text = node.text.orEmpty()
            if (!t.textEquals.isNullOrBlank() && text == t.textEquals) s += 10
            if (!t.textContains.isNullOrBlank() && text.contains(t.textContains)) s += 6
            val cd = node.contentDesc.orEmpty()
            if (!t.contentDescEquals.isNullOrBlank() && cd == t.contentDescEquals) s += 10
            if (!t.contentDescContains.isNullOrBlank() && cd.contains(t.contentDescContains)) s += 6
            if (!t.className.isNullOrBlank() &&
                (node.className.equals(t.className, ignoreCase = true) || node.className.endsWith(t.className))
            ) s += 3
            return s
        }
    }
}

/**
 * V2 占位：未来用 [top.xjunz.tasker.task.inspector.FloatingInspector] 让用户**直接在屏幕上点真节点**。
 *
 * 当前实现仅返回 null——决策面板看到 null 会回退成"用户取消替换"。
 * 实现 V2 时只需要：
 * 1. 弹起 FloatingInspector（mode=UI_OBJECT），让用户挑节点
 * 2. 监听 `FloatingInspector.EVENT_NODE_INFO_SELECTED` 拿到节点
 * 3. 用 [top.xjunz.tasker.task.inspector.shared.AiUiTargetExtractor.extract] 转 AiUiTarget
 * 4. 关闭 inspector → resume Continuation → 返回 target
 *
 * V2 启用后，决策面板 UI / [AiAgentDecision] / session 集成都不需要改动——这是 [AiAgentNodePicker] 接口
 * 设计的最大价值。
 */
class InspectorPickerStub : AiAgentNodePicker {
    override suspend fun pickReplacement(
        original: AiAgentAction,
        snapshot: AiUiSnapshot
    ): AiUiTarget? = null
}

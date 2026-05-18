/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.task.applet.option.AppletOptionFactory

/**
 * 把一个 `AccessibilityNodeInfo` 抽成一组 **Criterion Applet 候选**。
 *
 * 历史上这套逻辑只在 `NodeInfoOverlay.collectProperties` 里写死，给 capability 加新字段
 * （例如"在第几行 RecyclerView 子项里"）要改两处。
 *
 * 抽到这里以后两边都走同一份代码：
 * - `NodeInfoOverlay.collectProperties` → 调 [extract]，把 [Result.criteria] 列出来给用户勾选；
 *   `Result.defaultUncheckedIndices` 决定哪几条默认不勾。
 * - [AiAgentTaskAssembler.buildTaskFromRealNode] 一次性临时 task 流程 → 调 [extract]，
 *   按 [Result.semanticIndices] 过滤出语义字段，包成 `containsUiObject` 做执行端二次定位。
 *   AI agent 跑完即丢，**不**进入用户编辑器、**不**生成任何持久化草稿。
 *
 * 这套 if 链的语义和顺序与原 `collectProperties` 严格一致，**不要随意调整顺序**——
 * 用户已经习惯了 inspector 列表里这个排列方式，改顺序会破坏使用习惯。
 */
object NodeCriteriaExtractor {

    /**
     * @param criteria 全部生成的 Criterion Applet 列表。
     * @param defaultUncheckedIndices 默认**不**勾选的下标集合。当前 3 项："反转"语义的
     *   isSelected / isScrollable / childCount。Inspector UI 据此初始化复选状态。
     * @param semanticIndices "语义字段" 下标集合（aidoc/19 §F1 修法）——
     *   `isType / withId / textEquals / contentDesc` 这种**跟节点身份直接相关**的字段。
     *   AI agent 临时 task 用 [semanticIndices] 替代 [defaultUncheckedIndices] 的 filter，
     *   只用语义字段做 containsUiObject 二次定位，**剥掉所有状态字段**（isClickable/
     *   isLongClickable/isCheckable/isChecked/isEditable/isEnabled），避免：
     *   - P1：状态字段太严，inspector 用户挑节点时经常手动取消，AI agent 没法手动取消
     *   - P2：isChecked 陷阱（节点 isCheckable=true 但 isChecked=false 时仍加 isChecked=true 条件，
     *     永远匹不中——inspector 用户必须手动取消勾选才能命中）
     *   inspector 用户挑节点路径**不用** semanticIndices——保持原行为（用户可以勾选状态字段）。
     */
    data class Result(
        val criteria: List<Applet>,
        val defaultUncheckedIndices: Set<Int>,
        val semanticIndices: Set<Int>
    )

    fun extract(node: AccessibilityNodeInfo): Result {
        val registry = AppletOptionFactory.uiObjectRegistry
        val criteria = mutableListOf<Applet>()
        val uncheckedIndices = mutableSetOf<Int>()
        val semanticIndices = mutableSetOf<Int>()

        // ---------- 语义字段：跟节点身份直接相关，AI agent 只用这些 ----------
        if (node.className != null) {
            criteria.add(registry.isType.yieldWithFirstValue(node.className))
            semanticIndices.add(criteria.lastIndex)
        }
        if (node.viewIdResourceName != null) {
            criteria.add(registry.withId.yieldWithFirstValue(node.viewIdResourceName))
            semanticIndices.add(criteria.lastIndex)
        }
        if (node.text != null) {
            criteria.add(registry.textEquals.yieldWithFirstValue(node.text))
            semanticIndices.add(criteria.lastIndex)
        }
        if (node.contentDescription != null) {
            criteria.add(registry.contentDesc.yieldWithFirstValue(node.contentDescription))
            semanticIndices.add(criteria.lastIndex)
        }

        // ---------- 状态字段：节点当前状态。AI agent 跳过这些，inspector 用户可勾选 ----------
        if (node.isClickable) {
            criteria.add(registry.isClickable.yield())
        }
        if (node.isLongClickable) {
            criteria.add(registry.isLongClickable.yield())
        }
        if (!node.isEnabled) {
            criteria.add(registry.isEnabled.yieldCriterion(true))
        }
        if (node.isCheckable) {
            criteria.add(registry.isCheckable.yield())
        }
        @Suppress("DEPRECATION")
        if (node.isChecked || node.isCheckable) {
            criteria.add(registry.isChecked.yield())
        }
        if (node.isEditable) {
            criteria.add(registry.isEditable.yield())
        }

        // 反向语义：节点 isSelected=false 时给一条 "isSelected=true" 的反向条件，但默认不勾。
        // 跟原 collectProperties 一致，保留这个略 trick 的设计。
        criteria.add(registry.isSelected.yieldCriterion(!node.isSelected))
        if (!node.isSelected) {
            uncheckedIndices.add(criteria.lastIndex)
        }

        criteria.add(registry.isScrollable.yieldCriterion(!node.isScrollable))
        if (!node.isScrollable) {
            uncheckedIndices.add(criteria.lastIndex)
        }

        criteria.add(
            registry.childCount.yieldWithFirstValue(
                listOf(node.childCount, node.childCount)
            )
        )
        // childCount 这条默认不勾——大多数任务不需要严格 child 数，只是给用户一个开关
        uncheckedIndices.add(criteria.lastIndex)

        return Result(
            criteria = criteria,
            defaultUncheckedIndices = uncheckedIndices,
            semanticIndices = semanticIndices
        )
    }
}

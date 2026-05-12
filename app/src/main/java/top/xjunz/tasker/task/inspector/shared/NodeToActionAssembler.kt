/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import top.xjunz.tasker.R
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.ktx.str
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.editor.AppletReferenceEditor

/**
 * 把一组 Criterion Applet 候选包装成 `containsUiObject` Flow 的最小公共能力。
 *
 * 历史代码 [top.xjunz.tasker.ui.task.selector.AppletSelectorViewModel.acceptAppletsFromAutoClick]
 * 把这一段跟"是否要插当前 App 上下文 / 是否设 OR 关系"的 ViewModel 状态混在一起，
 * 抽出来后两边职责清晰：
 *
 * - **inspector 自动点击**：调 [wrapAsContainsUiObject] 拿到 Flow，再决定是否往 ViewModel 的 flow 里
 *   追加 isCertainApp + activityCollection（这部分依赖 service 状态，留在调用方）。
 * - **AI agent "保存为任务"** （未来 follow-up 2.1）：直接调 [wrapAsContainsUiObject]，
 *   逐条把 agent 历史的命中节点 → 同一个 wrap，构造可重放 XTask。
 *
 * 不在这里做的事：
 * - 不操作 [top.xjunz.tasker.engine.task.XTask] / RootFlow / Do 这些任务级容器
 * - 不调 [top.xjunz.tasker.service.A11yAutomatorService] / a11yEventDispatcher
 * - 不感知 ViewModel / LiveData
 *
 * 想加跟 ViewModel state 强相关的逻辑，请放回 ViewModel 自己。
 */
object NodeToActionAssembler {

    /**
     * @param criteria 从 [NodeCriteriaExtractor.extract] 拿到的 Criterion 候选（已被用户筛选过 / 直接全用）。
     * @param editor 引用编辑器；调用方可以传入自己持有的，便于跨多步统一管理 referent；不传则内部新建。
     * @return 配好 reference / referent 的 `containsUiObject` Flow，已经把 [criteria] 全部 add 进去；
     *   关系（AND/OR）由调用方根据上下文调整，**默认 AND**（containsUiObject.yield() 自带）。
     */
    fun wrapAsContainsUiObject(
        criteria: Collection<Applet>,
        editor: AppletReferenceEditor = AppletReferenceEditor(false)
    ): Flow {
        val containsUiObject =
            AppletOptionFactory.uiObjectFlowRegistry.containsUiObject.yield() as Flow
        editor.setReference(containsUiObject, 0, R.string.current_window.str)
        editor.setReferent(containsUiObject, 0, R.string.matched_ui_object.str)
        containsUiObject.addAll(criteria)
        return containsUiObject
    }
}

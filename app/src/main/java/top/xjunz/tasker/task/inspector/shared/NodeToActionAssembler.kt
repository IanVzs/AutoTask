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
 * - **旧 AI 草稿翻译**（[top.xjunz.tasker.ai.translator.AiActionToTask]）：把 AI 输出的 step
 *   翻译成可执行 task 时复用 wrap 逻辑。
 *
 * **AI agent 运行期不通过本类**：agent 单步动作走 [AiAgentTaskAssembler.buildTaskFromRealNode]
 * 自己 wrap（因为它跑在 :service 特权进程，本类用到的 `.str` 扩展会取 `App.instance` 崩）。
 *
 * 但**经验本→草稿生成模块** ([top.xjunz.tasker.ai.agent.experience.ExperienceToTaskConverter])
 * 复用本类：用户在 UI 主动点击"经验→任务草稿"时，从经验本读出 step 序列，每步通过本类
 * 包成 `containsUiObject` Flow，多步串成 doFlow 弹 FlowEditor 让用户审核。这跟 agent 自身完全解耦
 * （agent 不感知、不调用），只通过经验本作为单向数据中介。详见
 * `aidoc/20-experience-book-design.md` §0 三模块解耦架构。
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

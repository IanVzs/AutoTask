/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.experience

import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.R
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Do
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.ktx.str
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.ApplicationActionRegistry
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.GlobalActionRegistry
import top.xjunz.tasker.task.applet.option.registry.UiObjectActionRegistry
import top.xjunz.tasker.task.applet.option.registry.UiObjectCriterionRegistry
import top.xjunz.tasker.task.editor.AppletReferenceEditor
import top.xjunz.tasker.task.inspector.shared.NodeToActionAssembler

/**
 * 把一条**成功**经验里 [ExperienceFile.steps] 中所有 `convertible=true` 的步骤翻译成
 * 用户可以在 `FlowEditor` 里继续打磨的 [XTask] 草稿。
 *
 * 与 agent 运行期 [top.xjunz.tasker.ai.translator.AiActionToTask] 的区别：
 * - 那个是**单步一次性**的，一棵树跑一个动作
 * - 本类拼**多步序列**，整段 click/long_click/set_text/launch_app/global_back/home 串成 doFlow
 * - 输出 task 可保存可重放，**不**带 metadata.checksum（用 0，让 task editor 重算）
 *
 * 不支持的步骤（scroll / wait / done / give_up / unknown）跳过——MVP 不引入额外 applet 包装；
 * 用户可在编辑器里手动补齐。
 *
 * 与早期废弃的"agent 自动保存为任务"的本质不同：
 * - 用户**主动点击**"一键转草稿"按钮才发生，不是后台静默
 * - 转换结果**进编辑器**让用户审核 / 修改
 * - 经验本本体（txt）跟生成的 task 没有强绑定关系，删任何一边都不影响另一边
 */
internal object ExperienceToTaskConverter {

    fun convert(exp: ExperienceFile): XTask? {
        AppletOptionFactory.preloadIfNeeded()
        val factory = AppletOptionFactory

        // 跟 AiActionToTask 一样的 root + preload + 空 ifFlow + doFlow 模板，确保编辑器加载后能跑
        val root = factory.flowRegistry.rootFlow.yield() as RootFlow
        val preloadFlow = factory.flowRegistry.preloadFlow.yield()
        root.add(preloadFlow)
        val rootEditor = AppletReferenceEditor(false)
        rootEditor.setReferent(preloadFlow, 0, R.string.current_top_app.str)
        rootEditor.setReferent(preloadFlow, 3, R.string.current_window.str)
        val ifFlow = factory.flowRegistry.ifFlow.yield()
        root.add(ifFlow)
        val doFlow = factory.flowRegistry.doFlow.yield() as Do
        root.add(doFlow)

        var addedAnything = false
        exp.steps.filter { it.convertible }.forEach { step ->
            val ok = when (step.actionType) {
                "launch_app" -> step.packageName?.let {
                    addLaunchApp(doFlow, factory, it)
                } ?: false
                "click" -> step.target?.let {
                    addUiObjectAction(doFlow, factory, it, UiObjectActionKind.Click)
                } ?: false
                "long_click" -> step.target?.let {
                    addUiObjectAction(doFlow, factory, it, UiObjectActionKind.LongClick)
                } ?: false
                "set_text" -> step.target?.let {
                    // 实际文本不存盘；用一段醒目的占位，让用户进编辑器自己改
                    addUiObjectAction(doFlow, factory, it, UiObjectActionKind.SetText, SET_TEXT_PLACEHOLDER)
                } ?: false
                "global_back" -> addGlobal(doFlow, factory, GlobalActionKind.Back)
                "global_home" -> addGlobal(doFlow, factory, GlobalActionKind.Home)
                else -> false
            }
            if (ok) addedAnything = true
        }
        if (!addedAnything) return null

        val title = buildString {
            append("AI: ")
            append(exp.userGoal.take(40))
        }
        return XTask().apply {
            metadata = XTask.Metadata(
                title = title,
                taskType = XTask.TYPE_RESIDENT,
                description = buildDescription(exp)
            ).apply { version = BuildConfig.VERSION_CODE }
            flow = root
        }
    }

    private fun buildDescription(exp: ExperienceFile): String = buildString {
        append("由 AI 经验本一键转换：")
        append(exp.userGoal)
        appendLine()
        appendLine("会话于 ${exp.outcome} 状态结束 (${exp.outcomeLabel})。")
        appendLine("以下步骤已自动落地，请在编辑器里检查参数并按需补齐 wait / scroll / 输入文本：")
        exp.steps.filter { it.convertible }.forEachIndexed { index, step ->
            append(index + 1)
            append(". ")
            append(step.actionDescription)
            if (step.actionType == "set_text") {
                append("（输入内容已脱敏，请填实际文本）")
            }
            appendLine()
        }
    }.trimEnd()

    // ---------- applet 拼装 ----------

    private fun addLaunchApp(
        doFlow: Flow,
        factory: AppletOptionFactory,
        packageName: String
    ): Boolean = runCatching {
        val registry = factory.requireRegistryById(BootstrapOptionRegistry.ID_APP_ACTION_REGISTRY)
                as ApplicationActionRegistry
        doFlow.add(registry.launchApp.yieldWithFirstValue(packageName))
    }.isSuccess

    private enum class GlobalActionKind { Back, Home }

    private fun addGlobal(
        doFlow: Flow,
        factory: AppletOptionFactory,
        kind: GlobalActionKind
    ): Boolean = runCatching {
        val registry = factory.requireRegistryById(BootstrapOptionRegistry.ID_GLOBAL_ACTION_REGISTRY)
                as GlobalActionRegistry
        doFlow.add(
            when (kind) {
                GlobalActionKind.Back -> registry.pressBack.yield()
                GlobalActionKind.Home -> registry.pressHome.yield()
            }
        )
    }.isSuccess

    private enum class UiObjectActionKind { Click, LongClick, SetText }

    private fun addUiObjectAction(
        doFlow: Flow,
        factory: AppletOptionFactory,
        target: top.xjunz.tasker.ai.agent.AiUiTarget,
        kind: UiObjectActionKind,
        text: String? = null
    ): Boolean {
        if (target.isEmpty) return false
        val criteria = buildCriteria(target, factory.uiObjectRegistry)
        if (criteria.isEmpty()) return false
        val editor = AppletReferenceEditor(false)
        val containsUiObject = NodeToActionAssembler.wrapAsContainsUiObject(criteria, editor)
        doFlow.add(containsUiObject)

        val actionRegistry = factory.requireRegistryById(BootstrapOptionRegistry.ID_UI_OBJECT_ACTION_REGISTRY)
                as UiObjectActionRegistry
        val actionApplet = when (kind) {
            UiObjectActionKind.Click -> actionRegistry.click.yield()
            UiObjectActionKind.LongClick -> actionRegistry.longClick.yield()
            UiObjectActionKind.SetText -> {
                if (text == null) return false
                actionRegistry.setText.yield(1 to text)
            }
        }
        editor.setReference(actionApplet, 0, R.string.matched_ui_object.str)
        doFlow.add(actionApplet)
        return true
    }

    private fun buildCriteria(
        target: top.xjunz.tasker.ai.agent.AiUiTarget,
        registry: UiObjectCriterionRegistry
    ): List<Applet> {
        val out = mutableListOf<Applet>()
        target.viewId?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.withId.yieldWithFirstValue(it))
        }
        target.textEquals?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.textEquals.yieldWithFirstValue(it))
        }
        target.textContains?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.textContains.yieldWithFirstValue(it))
        }
        target.contentDescEquals?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.contentDesc.yieldWithFirstValue(it))
        }
        target.className?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.isType.yieldWithFirstValue(it))
        }
        return out
    }

    /** set_text 的占位文本，提醒用户在编辑器里改成实际内容。 */
    private const val SET_TEXT_PLACEHOLDER = "<请输入文本>"
}

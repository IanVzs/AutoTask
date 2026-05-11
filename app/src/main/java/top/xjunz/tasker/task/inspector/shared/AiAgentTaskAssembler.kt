/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.inspector.shared

import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.agent.AiUiTarget
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.UiObjectActionRegistry
import top.xjunz.tasker.task.editor.AppletReferenceEditor
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 AI agent 的「按 target 描述执行单步动作」请求**就地**组装成一棵跟 inspector「挑节点 → 自动点击」
 * 完全等价的最小 [XTask]。
 *
 * **必须在执行端进程调用**（A11y 模式下是主进程，Shizuku 模式下是 :service 特权进程）——因为
 * 需要现场拿 `uiAutomation.rootInActiveWindow` 找真节点。详见 aidoc/17。
 *
 * 相比早期的 [top.xjunz.tasker.ai.translator.AiActionToTask]：
 * - 那个老实现只用 AI target 的 5 个弱字段构造 criteria，**漏掉了 isClickable / isEditable 等关键状态约束**，
 *   导致 deepseek 这种屏幕上"占位 TextView + 真 EditText 文本一致"的 App 上 findFirst 命中错节点。
 * - 本类拿到真节点后调 [NodeCriteriaExtractor.extract] 抽出**完整 11+ 字段**criteria，
 *   跟 inspector 用户挑节点保存的 task 树**字段级一致**。
 */
object AiAgentTaskAssembler {

    /** action type ID，跟 IRemoteAutomatorService.aidl + AiAgentExecutor 三处统一约定，不可改值。 */
    const val ACTION_CLICK = 1
    const val ACTION_LONG_CLICK = 2
    const val ACTION_SET_TEXT = 3

    /**
     * 给临时 task 一个全局唯一 checksum；用 nanoTime 当种子保证不同步并发不冲突。
     * 不参与持久化校验（agent 临时 task 跑完即丢）。
     */
    private val checksumCounter = AtomicLong(System.nanoTime())

    /**
     * 解析 AI target JSON。返回 null = JSON 损坏 / target 全空，调用方应当返回失败。
     */
    fun parseTarget(targetJson: String): AiUiTarget? {
        return runCatching {
            AiJson.decodeFromString(AiUiTarget.serializer(), targetJson)
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    /**
     * 在 [root] 子树里找跟 [target] 弱匹配的真节点。规则跟 [top.xjunz.tasker.ai.agent.overlay.AiAgentOverlayController]
     * 的 previewTargetBounds 完全一致，并尊重 [AiUiTarget.matchIndex]。
     *
     * 弱匹配字段：viewId / textEquals / textContains / contentDescEquals / contentDescContains / className（末段）。
     * 全部 AND；任一非空字段不满足就跳过。匹中多个时返回第 [AiUiTarget.matchIndex] 个（默认 0）。
     */
    fun findRealNode(root: AccessibilityNodeInfo, target: AiUiTarget): AccessibilityNodeInfo? {
        val matches = collectMatches(root, target, limit = 16)
        if (matches.isEmpty()) return null
        val idx = (target.matchIndex ?: 0).coerceIn(0, matches.size - 1)
        return matches[idx]
    }

    /**
     * 用真节点 + 完整 criteria 组装跟 inspector 同款的 task。
     *
     * 树结构（跟 [top.xjunz.tasker.ui.task.selector.AppletSelectorViewModel.acceptAppletsFromAutoClick] 等价）：
     * ```
     * RootFlow
     * ├── isCertainApp(node.packageName)              ← 包名限定，跨页面 / 跨 App 自动失效
     * └── containsUiObject(完整 criteria，11+ 字段 AND)
     *     └── ... 由 NodeCriteriaExtractor.extract 抽出
     * └── action（click / longClick / setText）       ← reference = matched_ui_object
     * ```
     *
     * 不加 preloadFlow——inspector 的"自动点击"路径也不需要它（containsUiObject 直接用 current_window 引用）。
     *
     * @return 成功组装好的 [XTask]；nodeRoot 异常或字段缺失返回 null。
     */
    /**
     * **关键约束**：本方法运行在 :service 特权进程，**App.instance == null**！
     * 不能用 `R.string.xxx.str` / `app.getXxx()` 等任何依赖主进程 Application 的扩展。
     * 之前调用 `R.string.current_top_app.str` 会触发 `requireNotNull(App.instance)` 抛
     * `IllegalArgumentException("Required value was null.")`，跨 binder 透传成 main 端
     * 神秘 NPE，5/11 14:52 测试就是这个 bug。
     *
     * 修法：
     * 1. 不调 [NodeToActionAssembler.wrapAsContainsUiObject]（它内部调 .str）——本方法自己 wrap。
     * 2. 不加 isCertainApp 包名限定——agent 临时 task 跑完即丢，session 层 [AiAgentSession] 已经
     *    在 main 进程做了 scope 检查，重复加只会引入 [.str] 调用风险。
     * 3. setReference / setReferent 用**纯字符串字面量**——referent 名字只是树内部的标识符，
     *    任意字符串都行（只要 setReference 和 setReferent 用同一个）；不需要 i18n。
     */
    fun buildTaskFromRealNode(
        node: AccessibilityNodeInfo,
        actionType: Int,
        extraText: String?
    ): XTask? {
        AppletOptionFactory.preloadIfNeeded()
        val factory = AppletOptionFactory

        // 1. RootFlow（**不加** isCertainApp，避免 reference current_top_app 触发 .str）
        val root = factory.flowRegistry.rootFlow.yield() as RootFlow
        val editor = AppletReferenceEditor(false)

        // 2. preloadFlow 必须存在——它是 Referent，提供 root window 节点 (slot 3) 给 containsUiObject。
        //    setReferent 给 slot 3 起个名字（用纯字面量，不调 .str），containsUiObject 用同名 setReference 找它。
        val preloadFlow = factory.flowRegistry.preloadFlow.yield()
        root.add(preloadFlow)
        editor.setReferent(preloadFlow, 3, REFERENT_CURRENT_WINDOW)

        // 3. NodeCriteriaExtractor 抽完整 criteria（默认勾选）
        val extracted = NodeCriteriaExtractor.extract(node)
        val checkedCriteria = extracted.criteria.filterIndexed { i, _ ->
            i !in extracted.defaultUncheckedIndices
        }
        if (checkedCriteria.isEmpty()) return null

        // 4. 自己 wrap containsUiObject（不复用 NodeToActionAssembler——它的 .str 在 :service 进程崩）
        val containsUiObject =
            factory.uiObjectFlowRegistry.containsUiObject.yield() as Flow
        // **强制 REL_ANYWAY** 让它跟 preloadFlow 之间无条件执行：
        // preloadFlow children 为空，applyFlow 直接 return 保留入口 isSuccessful=false（task 初值）；
        // 默认 REL_AND 的 containsUiObject 见 isAnd=true != isSuccessful=false 会被 Flow.applyFlow:41 跳过。
        // (5/9 的同款 Do 跳过 bug，aidoc/17 § 5.1)
        containsUiObject.relation = Applet.REL_ANYWAY
        editor.setReference(containsUiObject, 0, REFERENT_CURRENT_WINDOW)
        editor.setReferent(containsUiObject, 0, REFERENT_MATCHED_UI_OBJECT)
        containsUiObject.addAll(checkedCriteria)
        root.add(containsUiObject)

        // 5. 加 action，reference[0] = matched_ui_object（用同款字面量保证 setReference / setReferent 匹配）
        //    action 默认 REL_AND，看 containsUiObject 是否找到节点（true 才执行）。
        val actionRegistry = factory.requireRegistryById(BootstrapOptionRegistry.ID_UI_OBJECT_ACTION_REGISTRY)
                as UiObjectActionRegistry
        val actionApplet: Applet = when (actionType) {
            ACTION_CLICK -> actionRegistry.click.yield()
            ACTION_LONG_CLICK -> actionRegistry.longClick.yield()
            ACTION_SET_TEXT -> {
                val text = extraText ?: return null
                actionRegistry.setText.yield(1 to text)
            }
            else -> return null
        }
        editor.setReference(actionApplet, 0, REFERENT_MATCHED_UI_OBJECT)
        root.add(actionApplet)

        // 6. 包成 XTask
        return XTask().apply {
            metadata = XTask.Metadata(
                title = "AI: ${actionTypeName(actionType)}",
                taskType = XTask.TYPE_ONESHOT,
                description = "agent 单步临时任务（按 inspector 同款链路），跑完即丢",
                checksum = nextChecksum()
            ).apply { version = BuildConfig.VERSION_CODE }
            flow = root
        }
    }

    /**
     * Referent 名字常量。**仅用于本类内部生成的 task 树**，不跟 inspector 保存的 task 共享——
     * 后者用 zh-CN 翻译字符串。不冲突即可。
     */
    private const val REFERENT_MATCHED_UI_OBJECT = "_ai_agent_matched_ui_object"
    private const val REFERENT_CURRENT_WINDOW = "_ai_agent_current_window"

    private fun actionTypeName(actionType: Int): String = when (actionType) {
        ACTION_CLICK -> "click"
        ACTION_LONG_CLICK -> "long_click"
        ACTION_SET_TEXT -> "set_text"
        else -> "unknown"
    }

    private fun nextChecksum(): Long = checksumCounter.incrementAndGet()

    // ---------- 内部：弱匹配实现 ----------

    /**
     * 按 [target] 在 [root] 子树里 BFS 收集匹配节点，最多 [limit] 个。
     * 不用 AccessibilityNodeInfo.findAccessibilityNodeInfosByXxx 因为它们对 viewId/text 单字段查询，
     * 我们要的是多字段 AND。
     */
    private fun collectMatches(
        root: AccessibilityNodeInfo,
        target: AiUiTarget,
        limit: Int
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(4)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val node = queue.removeFirst()
            if (matches(node, target)) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun matches(node: AccessibilityNodeInfo, t: AiUiTarget): Boolean {
        val viewIdOk = t.viewId.isNullOrBlank() ||
                node.viewIdResourceName == t.viewId
        val textEqOk = t.textEquals.isNullOrBlank() ||
                node.text?.toString() == t.textEquals
        val textContainsOk = t.textContains.isNullOrBlank() ||
                (node.text?.toString()?.contains(t.textContains!!) == true)
        val descEqOk = t.contentDescEquals.isNullOrBlank() ||
                node.contentDescription?.toString() == t.contentDescEquals
        val descContainsOk = t.contentDescContains.isNullOrBlank() ||
                (node.contentDescription?.toString()?.contains(t.contentDescContains!!) == true)
        val classOk = t.className.isNullOrBlank() || run {
            val cls = node.className?.toString().orEmpty()
            cls.equals(t.className, ignoreCase = true) ||
                    cls.substringAfterLast('.').equals(t.className, ignoreCase = true)
        }
        return viewIdOk && textEqOk && textContainsOk && descEqOk && descContainsOk && classOk
    }

}

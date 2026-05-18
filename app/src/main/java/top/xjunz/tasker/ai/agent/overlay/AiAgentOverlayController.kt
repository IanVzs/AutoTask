/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiAgentLog
import top.xjunz.tasker.ai.agent.AiBounds
import top.xjunz.tasker.ai.agent.AiUiNode
import top.xjunz.tasker.ai.agent.AiUiSnapshot

/**
 * Agent 模式下显示在屏幕上的"决策面板 + 节点高亮"控制器。
 *
 * 一个 [Service] 持有一个 controller。生命周期：
 * 1. session 开始 → [show]
 * 2. 每步 nextAction 后 → [requestDecision]，suspend 直到用户点同意 / 拒绝 / 换一个 / 倒计时到。
 * 3. session 结束 → [dismiss]，清掉 windowManager 上挂的所有 view。
 *
 * **优雅降级**：[isAvailable] 检查 SYSTEM_ALERT_WINDOW；用户没授权时所有方法都 noop，
 * agent 仍然能跑（只是没决策面板，等同于 [AiAgentConfirmMode.Disabled]）。
 *
 * 视图全程程序化构造，**不引入新 XML / databinding**，便于以后微调。
 */
class AiAgentOverlayController(private val service: Service) {

    private val windowManager: WindowManager =
        service.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var card: DecisionCard? = null
    private var highlightView: View? = null
    private var highlightDismissJob: Job? = null

    /** 当前 await 中的 deferred；新一步进来要先 cancel 上一步。 */
    private var pendingDeferred: CompletableDeferred<AiAgentDecision>? = null
    private var countdownJob: Job? = null

    // ---------- 公开 API ----------

    fun isAvailable(): Boolean = Settings.canDrawOverlays(service)

    /**
     * 把决策卡片挂到屏幕上**并立即可见**——常驻显示"AI agent 已启动，准备读屏..."这种状态文案，
     * 让用户随时看到 agent 在做什么。
     *
     * 历史 bug：之前 show 完 `visibility = GONE`，决策完之后又 GONE，session 整段过程
     * 用户看不到任何状态，AI 死循环 / 等 8s timeout 都不知情。改成全程常驻可见。
     */
    fun show() {
        if (!isAvailable()) {
            AiAgentLog.w("overlay", "SYSTEM_ALERT_WINDOW 未授权，跳过 overlay")
            return
        }
        if (card != null) return
        mainHandler.post {
            try {
                card = DecisionCard(service).also {
                    windowManager.addView(it.root, it.layoutParams)
                    it.root.visibility = View.VISIBLE
                    it.showIdlePlaceholder()
                }
                AiAgentLog.i("overlay", "决策面板已挂上 windowManager")
            } catch (t: Throwable) {
                AiAgentLog.e("overlay", "挂决策面板失败：${t.message}", t)
            }
        }
    }

    /**
     * Session 实时反馈用——AI 思考中 / 执行中 / 等待中 等状态都通过这里显示在面板顶部。
     * 在决策面板可见状态下安全调用：决策按钮在等用户点时不会被覆盖（status 显示在 actionView 下方）。
     */
    fun showStatus(statusText: String, progressDoneSteps: Int = -1, progressMaxSteps: Int = -1) {
        if (!isAvailable() || card == null) return
        mainHandler.post {
            card?.showStatus(statusText, progressDoneSteps, progressMaxSteps)
        }
    }

    /**
     * 同步 await 用户决策。**必须在协程里调**。
     *
     * @param picker "换一个"按钮的节点选择器；overlay 不可用时仍会返回 [AiAgentDecision.Skipped]。
     */
    suspend fun requestDecision(
        stepIndex: Int,
        action: AiAgentAction,
        snapshot: AiUiSnapshot?,
        mode: AiAgentConfirmMode,
        timeoutSeconds: Int,
        allowReplace: Boolean,
        picker: AiAgentNodePicker
    ): AiAgentDecision {
        // overlay 不可用 / 模式禁用 → 直接放行
        if (mode == AiAgentConfirmMode.Disabled || !isAvailable() || card == null) {
            return AiAgentDecision.Skipped
        }
        // 取消上一步残留
        pendingDeferred?.complete(AiAgentDecision.Skipped)
        countdownJob?.cancel()

        val deferred = CompletableDeferred<AiAgentDecision>()
        pendingDeferred = deferred

        // 在面板出现的同时，在屏幕上把目标节点画一个红框，让用户**眼睛直接看到要点哪一块**。
        // 配合卡片上的"输入框「发消息」 · 屏幕中部"，决策完全 informed。
        // 预匹配在主进程做：用 action target 的 viewId/text/desc/cls 去 snapshot 找最佳候选，
        // 拿不到就不画，不影响面板显示。
        val previewBounds = snapshot?.let { previewTargetBounds(action, it) }

        withContext(Dispatchers.Main) {
            val c = card ?: return@withContext deferred.complete(AiAgentDecision.Skipped).let { }
            c.bind(
                stepIndex = stepIndex,
                action = action,
                previewBounds = previewBounds,
                allowReplace = allowReplace,
                onApprove = {
                    deferred.complete(AiAgentDecision.ApprovedManual)
                    countdownJob?.cancel()
                    // **不再 GONE**——面板常驻；session 会通过 [showStatus] 切换到"执行中..."文案
                    c.showExecutingPlaceholder()
                },
                onTerminate = {
                    deferred.complete(AiAgentDecision.Terminate("用户主动终止"))
                    countdownJob?.cancel()
                    c.showExecutingPlaceholder()
                },
                onPickAnother = {
                    if (snapshot == null) {
                        deferred.complete(AiAgentDecision.Terminate("无快照可换，用户取消"))
                        countdownJob?.cancel()
                        c.showExecutingPlaceholder()
                        return@bind
                    }
                    countdownJob?.cancel()
                    c.expandCandidates(action, snapshot, picker, scope) { newTarget, hint ->
                        if (newTarget != null) {
                            deferred.complete(AiAgentDecision.Replaced(newTarget, hint))
                        } else {
                            // 用户在候选列表点了"取消" → 不强制终止，让面板回到等待状态
                            // （如果倒计时还在跑会继续；如果是 WaitForUser 模式会重新等用户）
                            // 这里简化：直接 Skipped 让 session 把当前 action 当默认通过
                            deferred.complete(AiAgentDecision.Skipped)
                        }
                        c.showExecutingPlaceholder()
                    }
                }
            )
            c.root.visibility = View.VISIBLE

            if (timeoutSeconds > 0 && mode == AiAgentConfirmMode.AutoApproveOnTimeout) {
                // 倒计时自动同意模式
                countdownJob = scope.launch {
                    val totalMillis = timeoutSeconds * 1000L
                    val tick = 50L
                    var elapsed = 0L
                    while (elapsed < totalMillis && !deferred.isCompleted) {
                        c.updateProgress(
                            ratio = (1f - elapsed.toFloat() / totalMillis).coerceIn(0f, 1f),
                            secondsLeft = timeoutSeconds - (elapsed / 1000).toInt(),
                            mode = mode
                        )
                        delay(tick)
                        elapsed += tick
                    }
                    if (!deferred.isCompleted) {
                        deferred.complete(AiAgentDecision.ApprovedAuto)
                        c.root.visibility = View.GONE
                    }
                }
            } else if (timeoutSeconds > 0 && mode == AiAgentConfirmMode.WaitForUserAfterTimeout) {
                // 倒计时归零后停留等用户：进度条跑完归 0，按钮不消失，提示文案变
                countdownJob = scope.launch {
                    val totalMillis = timeoutSeconds * 1000L
                    val tick = 50L
                    var elapsed = 0L
                    while (elapsed < totalMillis && !deferred.isCompleted) {
                        c.updateProgress(
                            ratio = (1f - elapsed.toFloat() / totalMillis).coerceIn(0f, 1f),
                            secondsLeft = timeoutSeconds - (elapsed / 1000).toInt(),
                            mode = mode
                        )
                        delay(tick)
                        elapsed += tick
                    }
                    // 进度条归零，但**不**自动决策；停留等用户点
                    if (!deferred.isCompleted) {
                        c.updateProgress(ratio = 0f, secondsLeft = 0, mode = mode)
                    }
                }
            } else {
                // Disabled 不该到这（前面已 return Skipped）；timeoutSeconds <= 0 就无倒计时
                c.updateProgress(ratio = 1f, secondsLeft = -1, mode = mode)
            }
        }

        // 等待用户决策 / 倒计时；如果有 previewBounds 就在屏幕画红框（不会阻塞 await）
        previewBounds?.let { highlightBounds(it, holdMillis = -1) }

        return deferred.await().also {
            AiAgentLog.i("overlay.decision", "step=$stepIndex → ${it::class.simpleName}")
            // 决策落地后立刻清掉红框——避免红框比目标动作还慢消失给用户造成"还在那个节点上"的错觉
            mainHandler.post { clearHighlightLocked() }
        }
    }

    /**
     * 用 AI 给的 target criteria 在 snapshot 里找最佳匹配节点，返回它的 bounds，让面板可以预览。
     * 实现刻意简化：跟 task 管道里 ContainsUiObject 的匹配语义对齐到"AND + 顺序"层面就够，
     * 不追求 100% 一致——预览即可，真匹配仍由 task 管道做。匹配不到就返回 null，面板照常显示。
     */
    private fun previewTargetBounds(
        action: AiAgentAction,
        snapshot: AiUiSnapshot
    ): AiBounds? {
        val target = when (action) {
            is AiAgentAction.Click -> action.target
            is AiAgentAction.LongClick -> action.target
            is AiAgentAction.SetText -> action.target
            is AiAgentAction.Scroll -> action.target
            else -> null
        } ?: return null

        val matches = snapshot.nodes.filter { node ->
            val viewIdOk = target.viewId.isNullOrBlank() || node.viewId == target.viewId
            val textEqOk = target.textEquals.isNullOrBlank() || node.text == target.textEquals
            val textContainsOk = target.textContains.isNullOrBlank() ||
                    (node.text?.contains(target.textContains!!, ignoreCase = false) == true)
            val descEqOk = target.contentDescEquals.isNullOrBlank() ||
                    node.contentDesc == target.contentDescEquals
            val descContainsOk = target.contentDescContains.isNullOrBlank() ||
                    (node.contentDesc?.contains(target.contentDescContains!!, ignoreCase = false) == true)
            // className 末段比对，跟 UiObjectCriterionRegistry.isType 的语义一致
            val classOk = target.className.isNullOrBlank() ||
                    node.className.substringAfterLast('.').equals(target.className, ignoreCase = true) ||
                    node.className.equals(target.className, ignoreCase = true)
            viewIdOk && textEqOk && textContainsOk && descEqOk && descContainsOk && classOk
        }
        if (matches.isEmpty()) return null
        val idx = (target.matchIndex ?: 0).coerceIn(0, matches.size - 1)
        return matches[idx].bounds
    }

    /**
     * 在屏幕上画一个半透明红框。
     * @param holdMillis 自动消失的毫秒数；< 0 表示**不自动消失**，必须由调用方显式 [clearHighlightLocked]。
     *
     * **坐标系修正**：[AiBounds] 来自 `AccessibilityNodeInfo.getBoundsInScreen`，是**含 status bar 的物理像素**。
     * 而 `WindowManager.LayoutParams` 在 vivo OriginOS / 部分 OEM 上即使加 `FLAG_LAYOUT_NO_LIMITS`，
     * `TYPE_APPLICATION_OVERLAY` 的 y 仍然从 status bar 下边缘开始算 → 高亮框比真实节点低一个 status bar 的高度
     * （典型 87-100 px）。这里主动读 status bar 高度并扣掉，让红框对齐节点。
     */
    fun highlightBounds(bounds: AiBounds, holdMillis: Long = 1000L) {
        if (!isAvailable()) return
        mainHandler.post {
            try {
                clearHighlightLocked()
                val view = View(service).apply {
                    background = GradientDrawable().apply {
                        setStroke(8, Color.parseColor("#FFFF3B30"))
                        setColor(Color.parseColor("#33FF3B30"))
                        cornerRadius = 8f
                    }
                }
                val statusBarPx = getStatusBarHeightPx()
                val lp = WindowManager.LayoutParams(
                    bounds.right - bounds.left,
                    bounds.bottom - bounds.top,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = bounds.left
                    // 减去 status bar 高度——bounds 含 status bar，但 OEM 渲染坐标系不含
                    y = (bounds.top - statusBarPx).coerceAtLeast(0)
                }
                AiAgentLog.d(
                    "overlay.highlight",
                    "bounds=(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}) statusBar=$statusBarPx → render(x=${lp.x},y=${lp.y},w=${lp.width},h=${lp.height})"
                )
                windowManager.addView(view, lp)
                highlightView = view
                if (holdMillis > 0) {
                    highlightDismissJob = scope.launch {
                        delay(holdMillis)
                        withContext(Dispatchers.Main) { clearHighlightLocked() }
                    }
                }
                // holdMillis < 0：不调度自动消失；上层（如 requestDecision）用户决策完后显式清掉
            } catch (t: Throwable) {
                AiAgentLog.e("overlay", "高亮失败：${t.message}", t)
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            countdownJob?.cancel()
            pendingDeferred?.complete(AiAgentDecision.Skipped)
            pendingDeferred = null
            clearHighlightLocked()
            card?.let {
                runCatching { windowManager.removeView(it.root) }
            }
            card = null
        }
        scope.cancel()
    }

    private fun clearHighlightLocked() {
        highlightDismissJob?.cancel()
        highlightDismissJob = null
        highlightView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        highlightView = null
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * 拿当前 status bar 高度（物理像素）。
     * 优先从 system 资源 `status_bar_height` 拿（最准确，跟实际渲染一致）；
     * 拿不到时按密度估 24dp 兜底（普通设备 ~67px @ 280dpi、~100px @ 420dpi）。
     */
    private fun getStatusBarHeightPx(): Int {
        return runCatching {
            val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) service.resources.getDimensionPixelSize(resId) else 0
        }.getOrDefault(0).takeIf { it > 0 }
            ?: (24 * service.resources.displayMetrics.density).toInt()
    }

    // ---------- 内部视图：决策卡片 ----------

    /**
     * 屏幕左上角的悬浮决策卡片，含：标题、动作描述、思考、倒计时进度条、3 个按钮、可展开的候选列表。
     * 视图全部程序化构造，不引入 XML，便于增删字段。
     */
    private inner class DecisionCard(service: Service) {

        private val titleView = TextView(service).apply { textSize = 14f; setTextColor(Color.WHITE) }
        private val actionView = TextView(service).apply { textSize = 14f; setTextColor(Color.WHITE) }
        private val locationView = TextView(service).apply {
            // 单独一行显示「位置：屏幕中部右侧」——视觉上跟红框呼应
            textSize = 12f
            setTextColor(Color.parseColor("#FFFFB300"))
        }
        private val thoughtView = TextView(service).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#CCFFFFFF"))
        }
        private val progressView = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 1000
        }
        private val countdownLabel = TextView(service).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#CCFFFFFF"))
        }
        private val approveBtn = Button(service).apply { text = "同意" }
        private val terminateBtn = Button(service).apply { text = "终止" }
        private val pickAnotherBtn = Button(service).apply { text = "换一个" }
        private val candidatesContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            // 注意：visibility 由外层 [candidatesScroll] 控制；本 LinearLayout 始终 VISIBLE，
            // 否则即使 ScrollView 显示，里面的 LinearLayout 是 GONE 子 view 也看不到（已被坑过一次）。
        }
        // 候选列表外套滚动容器——deepseek/微信 这种屏幕节点超过 10+ 时一屏放不下，必须能滑。
        // 高度限制为屏幕 50%，避免决策面板把整个屏幕都占了。
        private val candidatesScroll = android.widget.ScrollView(service).apply {
            visibility = View.GONE
            isFillViewport = false
            addView(
                candidatesContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // 按钮行单独提到字段层级——决策中显示，执行中 / 思考中隐藏，让面板状态机一目了然。
        private val btnRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(approveBtn, btnLp(weight = 1f))
            addView(terminateBtn, btnLp(weight = 1f, leftPx = 8))
            addView(pickAnotherBtn, btnLp(weight = 1f, leftPx = 8))
        }

        val root: LinearLayout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61C1C1E"))
                cornerRadius = 24f
            }
            setPadding(28, 20, 28, 20)
            addView(titleView)
            addView(actionView, marginParams(topPx = 6))
            addView(locationView, marginParams(topPx = 4))
            addView(thoughtView, marginParams(topPx = 4))
            addView(progressView, marginParams(topPx = 12, heightPx = 6))
            addView(countdownLabel, marginParams(topPx = 4))
            addView(btnRow, marginParams(topPx = 10))
            // 候选区高度上限 = 屏幕 50%，超过自动滚
            val maxCandidatesHeight = (service.resources.displayMetrics.heightPixels * 0.50f).toInt()
            addView(
                candidatesScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    maxCandidatesHeight
                ).apply { topMargin = 10 }
            )
            setOnTouchListener(makeDragListener())
        }

        /**
         * 初始挂上面板时显示——session 还没开始执行 / 还在等第一帧 snapshot。
         * 跟 [showStatus] 的差别：状态文案是固定的"启动中"，不带步数。
         */
        fun showIdlePlaceholder() {
            titleView.text = "AI Agent · 启动中"
            actionView.text = "正在准备读屏 + 等 AI 给出第一步动作..."
            locationView.visibility = View.GONE
            thoughtView.visibility = View.GONE
            progressView.visibility = View.GONE
            countdownLabel.visibility = View.GONE
            btnRow.visibility = View.GONE
            candidatesScroll.visibility = View.GONE
        }

        /**
         * 用户决策完后立刻进入这个状态——按钮行 GONE，actionView 换成"正在执行..."占位，
         * 倒计时文案隐藏。后续 session 会通过 [showStatus] 把更具体的"执行中：xxx"覆盖上来。
         */
        fun showExecutingPlaceholder() {
            actionView.text = "AI 正在执行刚才同意的动作..."
            locationView.visibility = View.GONE
            thoughtView.visibility = View.GONE
            progressView.visibility = View.GONE
            countdownLabel.visibility = View.GONE
            btnRow.visibility = View.GONE
            candidatesScroll.visibility = View.GONE
        }

        /**
         * Session 实时调用——显示"AI 思考下一步中..." / "正在执行：点击 xxx" / "已完成第 N 步"等。
         *
         * @param progressDoneSteps 已用步数（< 0 表示不显示进度）
         * @param progressMaxSteps  上限步数
         */
        fun showStatus(statusText: String, progressDoneSteps: Int, progressMaxSteps: Int) {
            if (progressDoneSteps >= 0 && progressMaxSteps > 0) {
                titleView.text = "AI Agent · 第 ${progressDoneSteps + 1} / $progressMaxSteps 步"
            }
            actionView.text = statusText
            // 思考 / 执行状态下隐藏决策细节；下次 bind 决策时再显示
            locationView.visibility = View.GONE
            thoughtView.visibility = View.GONE
            progressView.visibility = View.GONE
            countdownLabel.visibility = View.GONE
            btnRow.visibility = View.GONE
            candidatesScroll.visibility = View.GONE
        }

        val layoutParams: WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                (service.resources.displayMetrics.widthPixels * 0.78f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = (service.resources.displayMetrics.heightPixels * 0.08f).toInt()
            }

        @SuppressLint("ClickableViewAccessibility")
        private fun makeDragListener(): View.OnTouchListener {
            var startX = 0
            var startY = 0
            var startTouchX = 0f
            var startTouchY = 0f
            return View.OnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = layoutParams.x
                        startY = layoutParams.y
                        startTouchX = e.rawX
                        startTouchY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = startX + (e.rawX - startTouchX).toInt()
                        layoutParams.y = startY + (e.rawY - startTouchY).toInt()
                        runCatching { windowManager.updateViewLayout(root, layoutParams) }
                        true
                    }
                    else -> false
                }
            }
        }

        fun bind(
            stepIndex: Int,
            action: AiAgentAction,
            previewBounds: AiBounds?,
            allowReplace: Boolean,
            onApprove: () -> Unit,
            onTerminate: () -> Unit,
            onPickAnother: () -> Unit
        ) {
            titleView.text = "AI Agent · 第 ${stepIndex + 1} 步"
            actionView.text = describeAction(action)
            // 上一次决策完进入了 executing/status 状态把这些 GONE 了，本次重新决策时**恢复可见**
            progressView.visibility = View.VISIBLE
            countdownLabel.visibility = View.VISIBLE
            btnRow.visibility = View.VISIBLE

            // 位置行：优先显示**预匹配命中**节点的实际 bounds（最准）；
            // 没匹中（target 在当前快照里找不到）就提示用户"屏幕上没找到目标"——
            // 这是个明显的 red flag，用户大概率会拒绝。
            locationView.text = when {
                previewBounds != null -> "位置：${humanizeBounds(previewBounds)}（屏幕已用红框标出）"
                actionHasTarget(action) -> "⚠ 当前屏幕未找到目标，请谨慎决定"
                else -> ""
            }
            locationView.visibility = if (locationView.text.isNullOrBlank()) View.GONE else View.VISIBLE

            thoughtView.text = action.thought?.let { "思考：$it" }.orEmpty()
            thoughtView.visibility = if (thoughtView.text.isNullOrBlank()) View.GONE else View.VISIBLE
            candidatesContainer.removeAllViews()
            candidatesScroll.visibility = View.GONE
            approveBtn.setOnClickListener { onApprove() }
            terminateBtn.setOnClickListener { onTerminate() }
            pickAnotherBtn.visibility = if (allowReplace && actionHasTarget(action)) View.VISIBLE else View.GONE
            pickAnotherBtn.setOnClickListener { onPickAnother() }
        }

        fun updateProgress(ratio: Float, secondsLeft: Int, mode: AiAgentConfirmMode) {
            progressView.progress = (ratio * 1000).toInt()
            countdownLabel.text = when {
                secondsLeft < 0 -> "等待你决策"
                secondsLeft == 0 && mode == AiAgentConfirmMode.WaitForUserAfterTimeout ->
                    "倒计时结束，等待你点按钮"
                mode == AiAgentConfirmMode.AutoApproveOnTimeout ->
                    "${secondsLeft}s 后默认同意（点按钮可立即决策）"
                mode == AiAgentConfirmMode.WaitForUserAfterTimeout ->
                    "${secondsLeft}s 后停在这等你（不会自动决策）"
                else -> "等待你决策"
            }
        }

        fun expandCandidates(
            action: AiAgentAction,
            snapshot: AiUiSnapshot,
            picker: AiAgentNodePicker,
            scope: CoroutineScope,
            onPicked: (newTarget: top.xjunz.tasker.ai.agent.AiUiTarget?, hint: String?) -> Unit
        ) {
            // **不截断**——把所有"对人有意义"的节点都列出来（candidate row 自带滚动容器，详见
            // candidatesContainer 父级 ScrollView 设置）。用户反馈过 top-N 截断会漏掉关键节点
            // （比如 deepseek 启动屏底部 "发消息或按住说话" TextView 在第 9 个），让"换一个"形同虚设。
            // shortlist 内部按相似度 + 屏幕位置排序，靠前的都更可能是用户想要的。
            val candidates = CandidateListPicker.shortlist(
                snapshot,
                originalTarget(action),
                topN = Int.MAX_VALUE
            )
            // log 出实际呈现给用户的候选列表，便于诊断"用户说不知所云"——
            // 我们能知道排序后到底是哪些节点上桌
            AiAgentLog.i(
                "overlay.candidates",
                "为 ${describeAction(action)} 展示 ${candidates.size} 个候选：" +
                        candidates.joinToString(" | ") { formatNodeHuman(it) }
            )
            if (candidates.isEmpty()) {
                candidatesContainer.removeAllViews()
                candidatesContainer.addView(TextView(service).apply {
                    text = "当前屏幕没有可替换的候选节点"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                })
                candidatesContainer.addView(buildCancelButton(onPicked))
                candidatesScroll.visibility = View.VISIBLE
                return
            }
            candidatesContainer.removeAllViews()
            // 提示文案：教用户用"按住预览，松手选择"
            candidatesContainer.addView(TextView(service).apply {
                text = "按住下方候选预览屏幕高亮，松手在哪个候选上才真正选中："
                setTextColor(Color.parseColor("#FFFFB300"))
                textSize = 11f
            })
            candidates.forEachIndexed { index, node ->
                val row = buildCandidateRow(index, node)
                candidatesContainer.addView(row, marginParams(topPx = 4))
            }
            candidatesContainer.addView(buildCancelButton(onPicked), marginParams(topPx = 8))
            candidatesScroll.visibility = View.VISIBLE

            // 关键交互：候选 container 接管 touch 事件，按下 / 滑动到哪个 row 就预览高亮哪个节点；
            // 松手在该 row 上才"真选中"。这避免了用户只能盲选的问题——他能边看屏幕红框边挑。
            installCandidateTouchPreview(candidates, onPicked)

            // picker 接口在 V1 内部用 shortlist 自己挑候选，picker 参数仅为 V2（FloatingInspector）
            // 接管时保留接口稳定。这里显式标 used 让 lint 静音。
            @Suppress("UNUSED_VARIABLE")
            val placeholder = picker
            @Suppress("UNUSED_VARIABLE")
            val placeholderScope = scope
        }

        /**
         * 候选 row 仍然用普通 button 外观，但**禁用 OnClickListener**——所有触摸事件
         * 由 [installCandidateTouchPreview] 在 container 上统一处理，实现"按下预览高亮、松手选择"。
         * 这样能精确控制"手指划过 row 切换高亮"的效果，单 row OnClick 做不到。
         */
        private fun buildCandidateRow(index: Int, node: AiUiNode): View {
            return TextView(service).apply {
                text = "${index + 1}. ${formatNodeHuman(node)}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(20, 14, 20, 14)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FFFFFF"))
                    cornerRadius = 12f
                }
                tag = node  // hit-test 时拿到对应节点
            }
        }

        private fun buildCancelButton(onPicked: (top.xjunz.tasker.ai.agent.AiUiTarget?, String?) -> Unit): View {
            return Button(service).apply {
                text = "取消"
                isAllCaps = false
                setOnClickListener { onPicked(null, null) }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun installCandidateTouchPreview(
            candidates: List<AiUiNode>,
            onPicked: (top.xjunz.tasker.ai.agent.AiUiTarget?, String?) -> Unit
        ) {
            // 候选 row 在 candidatesContainer 内的索引——我们在前面 addView 时第一项是 hint TextView，
            // 之后才是候选 row（共 [candidates.size] 个），最后是"取消"按钮。
            // hitTest 时只算 candidate row 区间。
            val candidateStartIdx = 1  // 第 0 个是 hint TextView
            val candidateEndIdxExclusive = candidateStartIdx + candidates.size
            var lastHitIndex: Int = -1
            val normalBg = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = 12f
            }
            val hoverBg = GradientDrawable().apply {
                setColor(Color.parseColor("#66FF3B30"))
                cornerRadius = 12f
            }
            fun applyBg(rowIdx: Int, hovered: Boolean) {
                val v = candidatesContainer.getChildAt(rowIdx) ?: return
                v.background = if (hovered) hoverBg else normalBg
            }
            fun hitTestRow(yInContainer: Float): Int {
                for (i in candidateStartIdx until candidateEndIdxExclusive) {
                    val child = candidatesContainer.getChildAt(i) ?: continue
                    if (yInContainer >= child.top && yInContainer <= child.bottom) return i
                }
                return -1
            }
            // 注意：DOWN 阶段判断 y 是否落在候选 row 区间内——只有在区间内才返回 true 拦截，
            // 否则返回 false 让事件继续传给 child（取消按钮 / 提示 TextView 的 OnClickListener）。
            // 一旦 DOWN 返回 true，后续 MOVE/UP 都会走这里，child 的 click 不会再触发。
            candidatesContainer.setOnTouchListener { v, ev ->
                val hit = hitTestRow(ev.y)
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hit < 0) return@setOnTouchListener false  // 让事件走 child
                        // 关键：禁止外层 ScrollView 拦截后续 MOVE/UP（否则上下滑会触发滚动 → 收到 CANCEL）。
                        // 用户的"按住预览 → 滑到另一行 → 切换高亮"全靠这里。
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        applyBg(hit, hovered = true)
                        val node = candidates[hit - candidateStartIdx]
                        highlightBounds(node.bounds, holdMillis = -1)
                        lastHitIndex = hit
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hit != lastHitIndex) {
                            if (lastHitIndex >= 0) applyBg(lastHitIndex, hovered = false)
                            if (hit >= 0) {
                                applyBg(hit, hovered = true)
                                val node = candidates[hit - candidateStartIdx]
                                highlightBounds(node.bounds, holdMillis = -1)
                            } else {
                                clearHighlightLocked()
                            }
                            lastHitIndex = hit
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (hit >= 0) {
                            val picked = candidates[hit - candidateStartIdx]
                            val newTarget = top.xjunz.tasker.ai.agent.AiUiTarget(
                                viewId = picked.viewId,
                                textEquals = picked.text,
                                contentDescEquals = picked.contentDesc,
                                className = picked.className
                            )
                            onPicked(newTarget, "用户换为 ${formatNodeHuman(picked)}")
                        }
                        if (lastHitIndex >= 0) applyBg(lastHitIndex, hovered = false)
                        lastHitIndex = -1
                        clearHighlightLocked()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (lastHitIndex >= 0) applyBg(lastHitIndex, hovered = false)
                        lastHitIndex = -1
                        clearHighlightLocked()
                        true
                    }
                    else -> false
                }
            }
        }

        /**
         * 把 action 描述成 1-3 行人话，用于卡片正文。**只给人看**——viewId 等技术字段
         * 故意不出现，因为：
         * 1. 普通用户看 `com.deepseek.chat:id/btn_send_v2` 完全不知所云；
         * 2. 决策的关键是"AI 想做什么 + 想动哪个东西"，靠文本 / 描述 / 控件类型 / 位置足以判断；
         * 3. AI 内部仍然用 viewId 等强匹配执行——人面跟机面完全分离。
         */
        private fun describeAction(action: AiAgentAction): String = when (action) {
            is AiAgentAction.LaunchApp -> "启动 App：${action.packageName}"
            is AiAgentAction.Click -> "点击 ${humanizeTarget(action.target)}"
            is AiAgentAction.LongClick -> "长按 ${humanizeTarget(action.target)}"
            is AiAgentAction.SetText -> "向 ${humanizeTarget(action.target)} 输入文字「${action.text.take(30)}」"
            is AiAgentAction.Wait -> "等待 ${action.seconds} 秒"
            is AiAgentAction.Scroll -> "向${humanizeDirection(action.direction)}滚动"
            is AiAgentAction.GlobalBack -> "返回上一级（系统返回键）"
            is AiAgentAction.GlobalHome -> "回到桌面（Home 键）"
            is AiAgentAction.Done -> "完成：${action.summary.take(40)}"
            is AiAgentAction.GiveUp -> "放弃：${action.reason.take(40)}"
            is AiAgentAction.Unknown -> "未识别动作：${action.raw.take(40)}"
        }

        /**
         * 把节点选择条件翻成人话，**不展示 viewId**。
         * 优先级：text / contentDesc（人能读的内容） → 没有就退化到"控件类型 + 位置"。
         * 多个匹配条件之间用「，」连接，但只挑最 informative 的 1-2 个。
         */
        private fun humanizeTarget(t: top.xjunz.tasker.ai.agent.AiUiTarget): String {
            val text = t.textEquals ?: t.textContains
            val desc = t.contentDescEquals ?: t.contentDescContains
            val cls = humanizeClassName(t.className)
            return when {
                !text.isNullOrBlank() && cls != null -> "$cls「$text」"
                !text.isNullOrBlank() -> "「$text」"
                !desc.isNullOrBlank() && cls != null -> "$cls（描述：$desc）"
                !desc.isNullOrBlank() -> "（描述：$desc）"
                cls != null -> cls
                t.viewId != null -> "界面元素"
                else -> "未知目标"
            }
        }

        /**
         * 候选列表里每个候选节点的人话标签。规则跟 [humanizeTarget] 一致——
         * 优先 text/desc，次选控件类型+位置，**不暴露 viewId**。
         * 末尾追加屏幕区域帮用户分辨"两个'确定'按钮哪个是上面那个"。
         */
        private fun formatNodeHuman(node: AiUiNode): String {
            val cls = humanizeClassName(node.className)
            val text = node.text?.takeIf { it.isNotBlank() }
            val desc = node.contentDesc?.takeIf { it.isNotBlank() }
            val main = when {
                text != null && cls != null -> "$cls「$text」"
                text != null -> "「$text」"
                desc != null && cls != null -> "$cls（描述：$desc）"
                desc != null -> "（描述：$desc）"
                cls != null -> cls
                else -> "界面元素"
            }
            val area = humanizeBounds(node.bounds)
            return "$main · $area"
        }

        /** className 末段 → 中文人话；未知类名返回 null（让上层退化到不显示）。 */
        private fun humanizeClassName(className: String?): String? {
            if (className.isNullOrBlank()) return null
            val lower = className.substringAfterLast('.').lowercase()
            return when {
                lower.contains("edittext") || lower.contains("autocompletetext") ||
                        lower.contains("searchview") || lower.contains("textinputedittext") -> "输入框"
                lower.contains("imagebutton") -> "图标按钮"
                lower.contains("button") -> "按钮"
                lower == "textview" || lower.endsWith("textview") -> "文字"
                lower.contains("imageview") -> "图片"
                lower.contains("checkbox") -> "复选框"
                lower == "switch" || lower.contains("switchcompat") -> "开关"
                lower.contains("radiobutton") -> "单选项"
                lower.contains("seekbar") -> "滑块"
                lower.contains("recyclerview") || lower.contains("listview") -> "列表"
                lower.contains("scrollview") -> "可滚动区域"
                lower == "view" || lower.contains("framelayout") || lower.contains("linearlayout") ||
                        lower.contains("relativelayout") || lower.contains("constraintlayout") -> "区域"
                else -> className.substringAfterLast('.')
            }
        }

        /** bounds → 屏幕大致区域，避免暴露具体像素坐标。 */
        private fun humanizeBounds(b: AiBounds): String {
            val sw = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val sh = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            val v = when {
                cy < sh * 0.30 -> "顶部"
                cy < sh * 0.70 -> "中部"
                else -> "底部"
            }
            val h = when {
                cx < sw * 0.33 -> "左侧"
                cx < sw * 0.67 -> "中间"
                else -> "右侧"
            }
            return "屏幕$v$h"
        }

        private fun humanizeDirection(direction: String): String = when (direction.lowercase()) {
            "up" -> "上"
            "down" -> "下"
            "left" -> "左"
            "right" -> "右"
            else -> direction
        }

        private fun actionHasTarget(action: AiAgentAction): Boolean = when (action) {
            is AiAgentAction.Click,
            is AiAgentAction.LongClick,
            is AiAgentAction.SetText,
            is AiAgentAction.Scroll -> true
            else -> false
        }

        private fun originalTarget(action: AiAgentAction): top.xjunz.tasker.ai.agent.AiUiTarget? = when (action) {
            is AiAgentAction.Click -> action.target
            is AiAgentAction.LongClick -> action.target
            is AiAgentAction.SetText -> action.target
            is AiAgentAction.Scroll -> action.target
            else -> null
        }

        private fun marginParams(
            topPx: Int = 0,
            heightPx: Int = LinearLayout.LayoutParams.WRAP_CONTENT
        ): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            ).apply { topMargin = topPx }
        }

        private fun btnLp(weight: Float, leftPx: Int = 0): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                leftMargin = leftPx
            }
        }
    }
}

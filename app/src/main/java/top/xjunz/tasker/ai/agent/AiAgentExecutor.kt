/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.translator.AiActionToTask
import top.xjunz.tasker.app
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Do
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.ShellCmdActionRegistry
import top.xjunz.tasker.task.inspector.shared.AiAgentTaskAssembler
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager

/**
 * Agent 单步动作执行器。
 *
 * **架构对齐**（见 `aidoc/16-ai-inspector-capability.md` §13.8）：
 * 不再自己 `node.performAction(...)`。改成：
 * 1. [AiActionToTask.translate] 把 [AiAgentAction] 翻成临时单步 [XTask]
 * 2. 注册到 [LocalTaskManager.addOneshotTaskIfAbsent]（自动同步给特权进程的 PrivilegedTaskManager）
 * 3. 调 `currentService.scheduleOneshotTask(task, callback)` —— 跨进程派发
 * 4. await callback 拿到 ok/fail
 *
 * 这样：
 * - A11y / Shizuku 双模式自动支持（task 管道本就支持）
 * - 节点定位走 `containsUiObject + Criterion`，跟 inspector 完全一致
 * - 跨进程问题、错误处理、wakeLock、scheduler 全部复用现有能力
 *
 * 不通过 task 派发的动作：
 * - [AiAgentAction.Wait]：本地 delay 即可
 * - [AiAgentAction.Done] / [AiAgentAction.GiveUp] / [AiAgentAction.Unknown]：session 终止前会被
 *   [AiAgentSession] 拦下来，按理不会到这；保险起见仍报 ok=true / no-op。
 * - [AiAgentAction.Scroll]：ScrollMetrics 编码暂未实现，返回明确不支持的失败。
 */
object AiAgentExecutor {

    /** 单个 task 派发后等 callback 的最长时间，避免远端死锁锁住整段 session。 */
    private const val SCHEDULE_TIMEOUT_MILLIS = 30_000L

    /** [launchApp] 轮询前台 pkg 是否切换的间隔。 */
    private const val LAUNCH_POLL_INTERVAL_MILLIS = 200L

    /** [launchApp] 等待 pkg 切换的最长时间。 */
    private const val LAUNCH_MAX_WAIT_MILLIS = 2500L

    suspend fun execute(action: AiAgentAction): AiAgentStepResult {
        val result = when (action) {
            is AiAgentAction.Wait -> {
                delay(action.seconds * 1000L)
                AiAgentStepResult(ok = true, message = "已等待 ${action.seconds}s")
            }

            is AiAgentAction.LaunchApp -> launchApp(action.packageName)

            is AiAgentAction.Scroll -> dispatchScroll(action)

            // **新链路（aidoc/17）**：click / longClick 走 RPC `executeAgentActionByTarget`，
            // 让执行端进程拿真节点 + NodeCriteriaExtractor 抽完整 criteria，跟 inspector「自动点击」字段级一致。
            is AiAgentAction.Click -> dispatchAgentActionByTarget(
                AiAgentTaskAssembler.ACTION_CLICK, action.target, null,
                actionLabel = "click"
            )
            is AiAgentAction.LongClick -> dispatchAgentActionByTarget(
                AiAgentTaskAssembler.ACTION_LONG_CLICK, action.target, null,
                actionLabel = "long_click"
            )
            is AiAgentAction.SetText -> setTextWithFallback(action)

            // **GlobalBack/Home 走标准 inspector 链路**（5/11 18:21 撤销之前的 shell hack）：
            // 之前误以为是 OEM 限制了 a11y connection 的 GLOBAL_ACTION_BACK/HOME，
            // 改用 shell input keyevent + 二级 snapshot 验证。**完全错的诊断**。
            //
            // 真因（重新审 task 引擎源码）：AiActionToTask.translate 的 task 树**少了空 ifFlow**，
            // 导致 doFlow 永远被 shouldBeSkipped()，pressBack/pressHome **从来没真跑过**——
            // 这才是用户测试 task 报 OK 但屏幕没变的真因，跟 OEM 无关。
            // 修法在 AiActionToTask.kt：加空 ifFlow 让 ifSuccessful=true，doFlow 才能跑。
            //
            // 修好后 dispatchViaTask → AiActionToTask → pressBack/pressHome → uiAutomation.performGlobalAction()
            // 跟 inspector 用户保存的 GlobalAction task 走完全同款路径，标准方案。
            // 不再需要 shell hack。

            is AiAgentAction.Done,
            is AiAgentAction.GiveUp,
            is AiAgentAction.Unknown -> AiAgentStepResult(ok = true, message = "no-op")

            // GlobalBack / GlobalHome 不依赖节点匹配，仍走旧 task 管道（AiActionToTask）
            else -> dispatchViaTask(action)
        }
        AiAgentLog.i(
            "execute",
            "${action::class.simpleName} → ok=${result.ok}" +
                    (result.message?.let { " | $it" } ?: "")
        )
        return result
    }

    /**
     * scroll 派发：复用 inspector 同款 [UiObjectActionRegistry.swipe] 路径（详见 aidoc/18 §3.E）。
     *
     * direction 编码：把 AI 给的 "down/up/left/right" 跟 percent=0.5、speed=1000（合理默认）
     * 拼成 "down:0.5:1000" 通过 extraText 传给 service 端，service 用 SwipeMetrics.compose 还原成 Long。
     *
     * target 为空时：传一个仅含 className=ScrollView/RecyclerView 的"任意可滚动节点"target 给 service，
     * service 端 BFS 命中第一个匹配的就行——这跟 inspector 用户挑 scrollable 节点的语义等价。
     */
    private suspend fun dispatchScroll(action: AiAgentAction.Scroll): AiAgentStepResult {
        val target = action.target ?: AiUiTarget(
            // 没指定 target 时用最常见的 scrollable 容器类名做兜底；service 端 findRealNode BFS 会拿第一个。
            // 优先 ScrollView（最通用），未来 deepseek/RN 这种自绘列表可在这里加更多 fallback class 名。
            className = "ScrollView"
        )
        val extra = "${action.direction.lowercase()}:0.5:1000"
        return dispatchAgentActionByTarget(
            AiAgentTaskAssembler.ACTION_SWIPE, target, extra,
            actionLabel = "scroll(${action.direction})"
        )
    }

    /**
     * 通过 [AutomatorService.executeAgentActionByTarget] 跨进程派发动作（aidoc/17 KISS 重构）。
     *
     * 流程：
     * 1. 把 AI target 序列化成 JSON
     * 2. 调 service RPC：执行端进程拿到 root → BFS 找节点 → NodeCriteriaExtractor 抽**完整 criteria**
     *    → 包成 inspector 同款 task → 跑 → callback 真实回报成败
     * 3. callback 回 false 通常意味着：当前屏幕找不到匹中 target 的节点 / criteria 太严没命中 /
     *    节点 perform 真失败（极少；inspector 已 work 链路）
     *
     * 失败 / 超时映射成 [AiAgentStepResult]，message 给 AI 在下一轮 prompt 看。
     */
    private suspend fun dispatchAgentActionByTarget(
        actionType: Int,
        target: AiUiTarget,
        extraText: String?,
        actionLabel: String
    ): AiAgentStepResult {
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(
                ok = false,
                message = "AutoTask 服务未启动，无法执行节点动作。请去 AutoTask 主页『启动服务』后重试。"
            )
        }
        val targetJson = runCatching {
            AiJson.encodeToString(AiUiTarget.serializer(), target)
        }.getOrElse {
            return AiAgentStepResult(ok = false, message = "target 序列化失败：${it.message}")
        }

        val deferred = CompletableDeferred<Boolean>()
        runCatching {
            currentService.executeAgentActionByTarget(
                actionType, targetJson,
                // AIDL non-null 约束：click/longClick 这种没 text 的传 ""，service 端按 isEmpty 当 null。
                extraText ?: "",
                object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        deferred.complete(isSuccessful)
                    }
                }
            )
        }.onFailure {
            deferred.complete(false)
            AiAgentLog.e("execute.$actionLabel", "RPC 调用异常：${it.message}", it)
            return AiAgentStepResult(ok = false, message = "agent 动作 RPC 异常：${it.message}")
        }
        val ok = withTimeoutOrNull(SCHEDULE_TIMEOUT_MILLIS) { deferred.await() }
        return when (ok) {
            null -> AiAgentStepResult(
                ok = false,
                message = "等待执行完成超时（${SCHEDULE_TIMEOUT_MILLIS}ms），可能远端卡死，请下一步 wait 或 give_up"
            )
            true -> {
                // 拉服务端的精细诊断（成功也拉，"ok: 命中节点 X"，让 AI 在反思时知道实际命中啥）
                val diag = runCatching { currentService.getLastAgentDiagnostic() }.getOrDefault("")
                AiAgentStepResult(
                    ok = true,
                    message = if (diag.isNotBlank()) diag else "${actionLabel} 命中节点并执行完成"
                )
            }
            false -> {
                // **E.2 + D 关键反馈**：失败时拉精细诊断（哪些字段不符 / 节点不可编辑 / RN 不响应 等）
                val diag = runCatching { currentService.getLastAgentDiagnostic() }.getOrDefault("")
                AiAgentStepResult(
                    ok = false,
                    message = if (diag.isNotBlank()) "${actionLabel} 失败：$diag"
                    else "${actionLabel} 未在当前屏幕找到匹中 target 的节点（无诊断信息可用）"
                )
            }
        }
    }

    /**
     * setText 三层 fallback。设计动机：
     * - 标准 a11y `ACTION_SET_TEXT` 在 native AOSP EditText 上 100% 灵——但在 React Native /
     *   Compose 等自绘控件（deepseek、微信等）上系统会上报 "perform OK" 但实际**没有任何文本写入**，
     *   是众所周知的兼容性陷阱。
     * - 之前 [CoroutineUiObject.setText] 还有"假成功"bug 把这个问题彻底掩盖了——已修，会真返回 false。
     *
     * Fallback 链（从快到稳）：
     *  1. **a11y SET_TEXT** （走 task 管道）：原生 EditText 命中率最高，1ms 完成；
     *  2. **clipboard + KEYCODE_PASTE** （shell input 注入）：写主进程剪贴板，先 click 让节点聚焦
     *     输入法接管，再注入 PASTE keyevent。绕开 a11y 协议，对 RN/Compose/微信都灵。
     *
     * 任何一步成功就返回；最后一步还失败再 give up。每一步都把诊断细节塞进 result.message，
     * AI 在下一轮 prompt 里能看到"a11y SET_TEXT 失败，已 fallback 到剪贴板粘贴成功" 之类的故事。
     */
    private suspend fun setTextWithFallback(action: AiAgentAction.SetText): AiAgentStepResult {
        // 第一层：走新 RPC `executeAgentActionByTarget`（aidoc/17）——执行端进程拿真节点 +
        // NodeCriteriaExtractor 抽完整 criteria + 包成 inspector 同款 task + 跑 setText action。
        // 普通 native EditText 一发命中。
        val first = dispatchAgentActionByTarget(
            AiAgentTaskAssembler.ACTION_SET_TEXT, action.target, action.text,
            actionLabel = "set_text"
        )
        // **关键**：a11y SET_TEXT 在 deepseek/RN 等自绘控件上**系统层返回 true 但实际没写入**——
        // 这种 silent fail 是 Android 框架的已知陷阱，task callback 完全无法识别。
        // 必须执行后立刻抓一次 snapshot 验证 EditText.text 是否真的包含我们的输入，
        // 没有就直接走 paste fallback；不能等下一轮 session loop 的 silent-fail 检测（那时 fallback 已晚）。
        if (first.ok) {
            delay(250)  // 给 UI 反应时间
            if (verifyTextWritten(action)) {
                return first.copy(message = "set_text 成功（验证 EditText 已包含输入文字）")
            }
            AiAgentLog.w(
                "execute.setText",
                "a11y SET_TEXT 系统层返回 true 但屏幕 EditText 实际未写入文字（silent fail）；启动 paste fallback"
            )
        } else {
            AiAgentLog.w("execute.setText", "set_text 失败，启动剪贴板+PASTE fallback：${first.message}")
        }

        // 第二层：剪贴板 + KEYCODE_PASTE
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败，且 AutoTask 服务未运行无法 fallback 到剪贴板粘贴：${first.message}"
            )
        }
        runCatching { writeClipboard(action.text) }.onFailure {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败，写剪贴板也失败：${it.message}"
            )
        }
        // 先 click 节点让它聚焦——KEYCODE_PASTE 需要焦点在 EditText 上才会触发输入法粘贴
        val focusClick = dispatchAgentActionByTarget(
            AiAgentTaskAssembler.ACTION_CLICK, action.target, null, actionLabel = "set_text.focusClick"
        )
        if (!focusClick.ok) {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败 + 聚焦 click 也失败（${focusClick.message}），请用户手动接管"
            )
        }
        // 给输入法 / 焦点切换留点反应时间
        delay(200)
        // 注入 KEYCODE_PASTE (279) — 走特权进程的 ShellCmdAction（仅 Shizuku 模式可用）
        val pasteResult = dispatchShellViaTask("input keyevent 279")
        return if (pasteResult.ok) {
            AiAgentStepResult(
                ok = true,
                message = "a11y SET_TEXT 不响应（${first.message}），已 fallback 到剪贴板+KEYCODE_PASTE 粘贴"
            )
        } else {
            AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败 + KEYCODE_PASTE 注入也失败（${pasteResult.message}）。" +
                        "若使用 a11y 模式则不支持 shell 注入，请改用 Shizuku 模式或手动接管。"
            )
        }
    }

    /**
     * a11y SET_TEXT 之后立刻抓一次 snapshot，看 target 命中节点的 text 是否**真的包含**我们的输入文字。
     *
     * 匹配规则：用 target 在新 snapshot 找候选节点（跟 [AiAgentOverlayController.previewTargetBounds] 同款逻辑），
     * 看节点的 text 是否包含 action.text 前 16 字符。包含 = 系统真的写入了；不包含 = silent fail。
     *
     * 抓不到 snapshot 时**保守认为成功**（避免误判触发不必要的 fallback）。
     */
    private suspend fun verifyTextWritten(action: AiAgentAction.SetText): Boolean {
        val snapshot = ScreenSnapshotProvider.capture() ?: return true
        val target = action.target
        val needle = action.text.take(16)
        if (needle.isBlank()) return true
        val candidate = snapshot.nodes.firstOrNull { node ->
            val viewIdOk = target.viewId.isNullOrBlank() || node.viewId == target.viewId
            val textEqOk = target.textEquals.isNullOrBlank() ||
                    node.text == target.textEquals ||
                    (node.text?.contains(needle) == true)
            val classOk = target.className.isNullOrBlank() ||
                    node.className.substringAfterLast('.').equals(target.className, ignoreCase = true) ||
                    node.className.equals(target.className, ignoreCase = true)
            viewIdOk && classOk && (textEqOk || node.editable)
        } ?: snapshot.nodes.firstOrNull { it.editable }
        val written = candidate?.text?.contains(needle) == true
        AiAgentLog.d(
            "execute.setText.verify",
            "needle=\"$needle\" candidate.text=\"${candidate?.text}\" → written=$written"
        )
        return written
    }

    /**
     * 把文本写到主进程剪贴板。必须在主线程（ClipboardManager 是 main-thread API）。
     */
    private suspend fun writeClipboard(text: String) = withContext(Dispatchers.Main) {
        val cm = app.getSystemService(ClipboardManager::class.java)
            ?: error("ClipboardManager 不可用")
        cm.setPrimaryClip(ClipData.newPlainText("ai_agent_input", text))
    }

    /**
     * 通过 task 管道在特权进程跑一条 shell 命令。复用 [ShellCmdActionRegistry.executeShellCmd]——
     * 这个 applet 已经标 `.shizukuOnly()`，意味着 a11y 模式调用会失败，调用方需要做兜底文案。
     *
     * **5/11 18:30 修真 bug**：之前 task 树是 `RootFlow + preloadFlow + Do(REL_ANYWAY)`，
     * 但 `Do.shouldBeSkipped(runtime.ifSuccessful != true)` —— 没 If 跑过 ifSuccessful=null →
     * doFlow 永远被 skip → shellCmd **从来没真跑过**。setText paste fallback 一直假成功。
     * 修法：跟 inspector default flow 一致加空 ifFlow，让 ifSuccessful=true，doFlow 才能跑。
     */
    private suspend fun dispatchShellViaTask(cmd: String): AiAgentStepResult {
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(ok = false, message = "AutoTask 服务未运行")
        }
        val task = runCatching {
            val factory = AppletOptionFactory
            factory.preloadIfNeeded()
            val root = factory.flowRegistry.rootFlow.yield() as RootFlow
            root.add(factory.flowRegistry.preloadFlow.yield())
            // **关键**：加空 ifFlow，让 ifSuccessful=true（详见 Do.shouldBeSkipped 真因）
            root.add(factory.flowRegistry.ifFlow.yield())
            val doFlow = factory.flowRegistry.doFlow.yield() as Do
            root.add(doFlow)
            val shellRegistry = factory.requireRegistryById(BootstrapOptionRegistry.ID_SHELL_CMD_ACTION_REGISTRY)
                    as ShellCmdActionRegistry
            doFlow.add(shellRegistry.executeShellCmd.yieldWithFirstValue(cmd))
            XTask().apply {
                metadata = XTask.Metadata(
                    title = "AI: shell",
                    taskType = XTask.TYPE_ONESHOT,
                    description = "agent shell 单步：$cmd",
                    checksum = System.nanoTime()
                ).apply { version = BuildConfig.VERSION_CODE }
                flow = root
            }
        }.getOrElse {
            AiAgentLog.e("execute.shell", "构造 shell task 失败：${it.message}", it)
            return AiAgentStepResult(ok = false, message = "构造 shell task 失败：${it.message}")
        }
        val deferred = CompletableDeferred<Boolean>()
        runCatching {
            LocalTaskManager.addOneshotTaskIfAbsent(task)
            currentService.scheduleOneshotTask(
                task,
                object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        deferred.complete(isSuccessful)
                    }
                }
            )
        }.onFailure {
            deferred.complete(false)
            runCatching { LocalTaskManager.removeTask(task) }
            return AiAgentStepResult(ok = false, message = "shell 调度异常：${it.message}")
        }
        val ok = withTimeoutOrNull(SCHEDULE_TIMEOUT_MILLIS) { deferred.await() }
        runCatching { LocalTaskManager.removeTask(task) }
        return when (ok) {
            null -> AiAgentStepResult(ok = false, message = "shell 任务超时")
            true -> AiAgentStepResult(ok = true)
            false -> AiAgentStepResult(ok = false, message = "shell 任务失败（可能 a11y 模式不支持）")
        }
    }

    /**
     * **launch_app 不走 task 管道**——`ApplicationActionRegistry.launchApp` 内部硬写 `true` 返回，
     * 不论 `ActivityManagerBridge.startComponent` 实际是否拉起 App 都报告成功，会误导 AI。
     *
     * 改回主进程的 `app.startActivity(intent)`（之前已经验证过能 work），并且**轮询前台 pkg
     * 是否真的切到目标**——切到了再返回 OK，否则如实告知 AI"已发起但前台仍是 X"。
     */
    private suspend fun launchApp(pkg: String): AiAgentStepResult = withContext(Dispatchers.Main) {
        if (!serviceController.isServiceRunning) {
            return@withContext AiAgentStepResult(
                ok = false,
                message = "AutoTask 服务未启动，无法观察启动结果。请去 AutoTask 主页『启动服务』后重试。"
            )
        }
        val intent = runCatching { app.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return@withContext AiAgentStepResult(ok = false, message = "未安装或找不到 App: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }.onFailure {
            return@withContext AiAgentStepResult(ok = false, message = "启动失败: ${it.message}")
        }
        // 轮询前台 pkg 是否切到目标
        var observed: String? = currentForegroundPackage()
        if (observed != pkg) {
            val deadline = System.currentTimeMillis() + LAUNCH_MAX_WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                delay(LAUNCH_POLL_INTERVAL_MILLIS)
                observed = currentForegroundPackage()
                if (observed == pkg) break
            }
        }
        if (observed == pkg) {
            AiAgentStepResult(ok = true, message = "已启动并切换到 $pkg")
        } else {
            val pkgHint = observed ?: "未知（a11y 事件还没到，可能 App 已启但事件延迟）"
            AiAgentStepResult(
                ok = true,
                message = "已发起启动 $pkg，但等了 ${LAUNCH_MAX_WAIT_MILLIS}ms 当前前台仍是 $pkgHint，请在下一步用 wait 再观察或换策略"
            )
        }
    }

    private fun currentForegroundPackage(): String? {
        if (!serviceController.isServiceRunning) return null
        return runCatching {
            currentService.getCurrentComponentInfo().packageName
        }.getOrNull()
    }

    /**
     * 把 action 翻成临时单步 XTask 派给执行端进程跑。
     * 任何失败（service 没启动 / 翻译失败 / 调度异常 / 超时）都映射为带 message 的 [AiAgentStepResult]，
     * 上层 [AiAgentSession] 看到失败 message 会塞进 history 喂给 AI 自学。
     */
    private suspend fun dispatchViaTask(action: AiAgentAction): AiAgentStepResult {
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(
                ok = false,
                message = "AutoTask 服务未启动，无法执行节点动作。请去 AutoTask 主页『启动服务』后重试。"
            )
        }
        val task = AiActionToTask.translate(action) ?: return AiAgentStepResult(
            ok = false,
            message = "无法把 ${action::class.simpleName} 翻译为 task（action target 可能为空 / 不支持的动作类型）"
        )
        val deferred = CompletableDeferred<Boolean>()
        runCatching {
            LocalTaskManager.addOneshotTaskIfAbsent(task)
            currentService.scheduleOneshotTask(
                task,
                object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        deferred.complete(isSuccessful)
                    }
                }
            )
        }.onFailure {
            deferred.complete(false)
            // 调度异常也要清理，避免半挂的临时 task 留在 LocalTaskManager 累积
            runCatching { LocalTaskManager.removeTask(task) }
            AiAgentLog.e("execute", "scheduleOneshotTask 异常：${it.message}", it)
            return AiAgentStepResult(ok = false, message = "调度任务异常：${it.message}")
        }
        val ok = withTimeoutOrNull(SCHEDULE_TIMEOUT_MILLIS) { deferred.await() }
        // 不论成功失败 / 超时，都清掉这个一次性临时 task；它跑完即丢，不需要保留。
        // 不清理会导致 agent 跑久了 LocalTaskManager + PrivilegedTaskManager 都累积上百个临时 task。
        runCatching { LocalTaskManager.removeTask(task) }
        return when (ok) {
            null -> AiAgentStepResult(
                ok = false,
                message = "等待任务完成超时（${SCHEDULE_TIMEOUT_MILLIS}ms），可能远端卡死，请下一步 wait 或 give_up"
            )
            true -> AiAgentStepResult(ok = true, message = "task 执行完成")
            false -> AiAgentStepResult(
                ok = false,
                message = "task 执行返回失败（节点未命中 / 不可点 / 节点拒绝）"
            )
        }
    }
}

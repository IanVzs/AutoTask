/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker.service

import android.annotation.SuppressLint
import android.os.Looper
import android.os.PowerManager.WakeLock
import top.xjunz.tasker.bridge.OverlayToastBridge
import top.xjunz.tasker.bridge.PowerManagerBridge
import top.xjunz.tasker.engine.applet.base.AppletResult
import top.xjunz.tasker.engine.runtime.TaskRuntime
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.task.applet.flow.ref.ComponentInfoWrapper
import top.xjunz.tasker.task.event.A11yEventDispatcher
import top.xjunz.tasker.task.event.MetaEventDispatcher
import top.xjunz.tasker.task.event.NetworkEventDispatcher
import top.xjunz.tasker.task.event.PollEventDispatcher
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.OneshotTaskScheduler
import top.xjunz.tasker.task.runtime.ResidentTaskScheduler
import top.xjunz.tasker.uiautomator.CoroutineUiAutomatorBridge

/**
 * A service defines the common abstractions of [A11yAutomatorService] and [ShizukuAutomatorService].
 *
 * @author xjunz 2022/07/21
 */
interface AutomatorService {

    val isRunning: Boolean

    val uiAutomatorBridge: CoroutineUiAutomatorBridge

    val looper: Looper

    val eventDispatcher: MetaEventDispatcher

    val overlayToastBridge: OverlayToastBridge

    val residentTaskScheduler: ResidentTaskScheduler

    val oneshotTaskScheduler: OneshotTaskScheduler

    val a11yEventDispatcher: A11yEventDispatcher

    var wakeLock: WakeLock?

    fun getCurrentComponentInfo(): ComponentInfoWrapper {
        return a11yEventDispatcher.getCurrentComponentInfo()
    }

    /**
     * 把当前焦点窗口的节点树压缩成 [top.xjunz.tasker.ai.agent.AiUiSnapshot] 的 JSON 字符串。
     *
     * 抽象方法：A11y 模式（service 在主进程）走本地实现；Shizuku 模式（service 在特权进程）走 AIDL 远程。
     * 两边都用 [top.xjunz.tasker.task.inspector.shared.AiNodeTreeCompactor] 做压缩，保证 schema 一致。
     *
     * 这是 AI agent 在跨进程模式下"看见屏幕"的唯一新增 IPC 契约。返回空字符串表示"当前抓不到 root"
     * （a11y 未连 / 远端断开 / 窗口切换瞬间），调用方应等下一轮重试。
     */
    fun captureUiSnapshotJson(maxNodes: Int, maxTextLen: Int): String

    /**
     * AI agent 「按 target 描述执行单步动作」的就地组装入口（详见 aidoc/17）。
     *
     * 抽象方法：两端都执行同一段 KISS 流程：
     * 1. 拿当前焦点窗口 root（uiAutomation.rootInActiveWindow）
     * 2. 用 target 弱字段（viewId/textEquals/contentDescEquals/className）做 findFirst 找到真节点
     * 3. 调 [top.xjunz.tasker.task.inspector.shared.NodeCriteriaExtractor.extract] 抽出**完整 11+ 字段**的 criteria
     * 4. 用 [top.xjunz.tasker.task.inspector.shared.NodeToActionAssembler.wrapAsContainsUiObject] 包成 flow，
     *    在 flow 头部加 isCertainApp(node.packageName) 限定包名
     * 5. 加对应 action（click / longClick / setText）
     * 6. 通过 [top.xjunz.tasker.task.runtime.PrivilegedTaskManager] 注册 + 内部 oneshot scheduler 跑
     * 7. callback 真实回报 task isSuccessful
     *
     * 这条路径 100% 复用 inspector「挑节点 → 自动点击/输入」已 work 的链路，
     * 替代主进程在 [top.xjunz.tasker.ai.translator.AiActionToTask] 里**残缺**抽 criteria 的旧实现。
     *
     * @param actionType 1=click / 2=longClick / 3=setText
     * @param targetJson 序列化的 [top.xjunz.tasker.ai.agent.AiUiTarget]
     * @param extraText  仅 setText 用，其余传 null
     */
    fun executeAgentActionByTarget(
        actionType: Int,
        targetJson: String,
        // 兼容 AIDL non-null String 约束：调用方对"无 text 类动作"传 ""。service 端按 isEmpty 当 null。
        extraText: String,
        callback: ITaskCompletionCallback
    )

    /**
     * 拉执行端进程最近一次 [executeAgentActionByTarget] 的诊断信息（aidoc/18 §3.E.2 + §3.D）。
     * 失败时返回精细原因（哪些字段不符 / 节点 isEditable=false 等），main 端写进 step.result.message。
     * 没有最近诊断时返回 ""。
     */
    fun getLastAgentDiagnostic(): String

    fun scheduleOneshotTask(task: XTask, onCompletion: ITaskCompletionCallback)

    fun stopOneshotTask(task: XTask)

    fun suppressResidentTaskScheduler(suppress: Boolean)

    fun initEventDispatcher() {
        eventDispatcher.registerEventDispatcher(a11yEventDispatcher)
        eventDispatcher.registerEventDispatcher(PollEventDispatcher(looper))
        eventDispatcher.registerEventDispatcher(NetworkEventDispatcher())
        //eventDispatcher.registerEventDispatcher(ClipboardEventDispatcher())
        eventDispatcher.addCallback(residentTaskScheduler)
        eventDispatcher.addCallback(oneshotTaskScheduler)
    }

    fun prepareWorkerMode(acquireWakeLock: Boolean) {
        initEventDispatcher()
        if (acquireWakeLock) {
            acquireWakeLock()
        }
    }

    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock() {
        wakeLock = PowerManagerBridge.obtainWakeLock()
        wakeLock?.acquire()
    }

    fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    fun destroy() {
        AppletResult.drainPool()
        TaskRuntime.drainPool()
        releaseWakeLock()
    }

    fun getStartTimestamp(): Long

}
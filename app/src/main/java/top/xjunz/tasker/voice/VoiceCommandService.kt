/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.AiCenter
import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiAgentLog
import top.xjunz.tasker.ai.agent.AiAgentPlanner
import top.xjunz.tasker.ai.agent.AiAgentSession
import top.xjunz.tasker.ai.agent.AiAgentSessionOutcome
import top.xjunz.tasker.ai.agent.AiAgentSessionPlan
import top.xjunz.tasker.ai.agent.AiAgentStepRecord
import top.xjunz.tasker.ai.agent.AiDraftStep
import top.xjunz.tasker.ai.agent.AiTaskScope
import top.xjunz.tasker.ai.agent.AiTaskScopeStore
import top.xjunz.tasker.ai.agent.overlay.AiAgentOverlayController
import top.xjunz.tasker.ai.agent.overlay.InspectorPickerStub
import top.xjunz.tasker.ai.agent.VoiceAiInterpretation
import top.xjunz.tasker.ai.agent.VoiceAiInterpreter
import top.xjunz.tasker.ai.audit.AiDecisionRecord
import top.xjunz.tasker.ai.audit.AiDecisionSource
import top.xjunz.tasker.ai.audit.AiExecutionResult
import top.xjunz.tasker.ai.audit.AiExecutionStatus
import top.xjunz.tasker.ai.audit.AiUserDecision
import top.xjunz.tasker.ai.model.AiActionPlan
import top.xjunz.tasker.ai.model.AiActionStep
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.model.AiIntent
import top.xjunz.tasker.ai.model.AiIntentType
import top.xjunz.tasker.ai.model.AiRiskAssessment
import top.xjunz.tasker.ai.model.AiRiskLevel
import top.xjunz.tasker.ai.model.AiScope
import top.xjunz.tasker.ai.policy.AiGateResult
import top.xjunz.tasker.ai.policy.AiGateStatus
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager
import top.xjunz.tasker.task.storage.TaskStorage
import top.xjunz.tasker.ui.main.MainActivity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceCommandStatus {
    IDLE,
    LISTENING,
    RECOGNIZING,
    EXECUTING,
    ERROR
}

enum class VoiceCommandRecordResult {
    INFO,
    SUCCESS,
    FAILURE
}

data class VoiceCommandRecord(
    val timestamp: Long,
    val title: String,
    val detail: String? = null,
    val result: VoiceCommandRecordResult = VoiceCommandRecordResult.INFO,
    /** 调用 AI 时使用的完整 prompt，用于在记录详情里展开查看。 */
    val prompt: String? = null,
    /** AI 模型返回的原始文本（含 schema 校验失败时的内容）。 */
    val rawResponse: String? = null,
    /** 解析失败/被拒原因，例如 confidence 太低、provider 异常等。 */
    val diagnostic: String? = null
) {
    val hasInspectableAiTrace: Boolean
        get() = !prompt.isNullOrBlank() || !rawResponse.isNullOrBlank() || !diagnostic.isNullOrBlank()
}

data class VoiceCommandUiState(
    val isRunning: Boolean = false,
    val status: VoiceCommandStatus = VoiceCommandStatus.IDLE,
    val latestText: String? = null,
    val latestCommand: String? = null,
    val latestTaskTitle: String? = null,
    val latestResult: VoiceCommandRecordResult? = null,
    val records: List<VoiceCommandRecord> = emptyList(),
    val pendingDraft: VoiceCommandDraftPayload? = null,
    /**
     * AI agent 会话开始前等待用户授权的请求。Fragment 监听到非空值后弹出"任务级授权"对话框，
     * 通过 [VoiceCommandService.grantAgent]/[VoiceCommandService.denyAgent] 决策。
     */
    val pendingAgent: AgentRequestPayload? = null,
    /** 当前正在运行的 agent 会话标识，便于 UI 显示"AI 正在执行 #N 步"等状态。null 表示空闲。 */
    val activeAgentSessionId: String? = null
)

/**
 * AI 解析出的任务草稿建议，由语音页观察后弹出预览卡片，并提供进入编辑器的入口。
 */
data class VoiceCommandDraftPayload(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<AiDraftStep>,
    val confidence: Float
)

/**
 * 一次 AI agent 会话开始前需要用户确认的请求。会被 [VoiceCommandUiState.pendingAgent] 暴露给 UI。
 */
data class AgentRequestPayload(
    val sessionId: String,
    val userGoal: String,
    val plan: AiAgentSessionPlan,
    val targetApps: Set<String>,
    val maxSteps: Int,
    val maxSeconds: Int
)

class VoiceCommandService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "top.xjunz.tasker.voice.action.START"
        const val ACTION_STOP = "top.xjunz.tasker.voice.action.STOP"
        const val ACTION_HANDLE_TEXT = "top.xjunz.tasker.voice.action.HANDLE_TEXT"
        const val EXTRA_TEXT = "top.xjunz.tasker.voice.extra.TEXT"
        const val EXTRA_KEEP_RUNNING = "top.xjunz.tasker.voice.extra.KEEP_RUNNING"

        val isRunning = MutableLiveData(false)
        val uiState = MutableLiveData(VoiceCommandUiState())

        private const val CHANNEL_ID = "voice_command"
        private const val NOTIFICATION_ID = 0x610
        private const val RESTART_DELAY_MILLIS = 800L
        private const val MAX_RECORD_COUNT = 30

        /**
         * AI agent 执行结果通知 channel：与前台常驻通知 [CHANNEL_ID] 区分开，IMPORTANCE_DEFAULT
         * 让用户能即时感知（声音 + 横幅），点通知打开主页。每次任务跑完发一条独立通知，
         * 通知 ID 在 [OUTCOME_NOTIFICATION_ID_BASE] 之上递增（mod 一定上限避免无限堆积）。
         */
        private const val OUTCOME_CHANNEL_ID = "ai_agent_outcome"
        private const val OUTCOME_NOTIFICATION_ID_BASE = 0x611
        private const val OUTCOME_NOTIFICATION_ID_RANGE = 100
        private val outcomeNotificationCounter = AtomicInteger(0)

        /**
         * Fragment 处理完草稿（保存或忽略）后调用，清空 [VoiceCommandUiState.pendingDraft]，
         * 防止下次回到语音页又重新弹出。
         */
        fun consumeDraft(draftId: String) {
            val current = uiState.value ?: return
            val draft = current.pendingDraft ?: return
            if (draft.id != draftId) return
            uiState.postValue(current.copy(pendingDraft = null))
        }

        /**
         * 等待用户对 [AgentRequestPayload] 决策的 deferred 集合。Fragment 通过
         * [grantAgent]/[denyAgent] 完成它，service 内部 [runAgentFlow] 在 await。
         */
        private val pendingAgentDecisions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        /** 用户在任务级授权对话框点"允许"。 */
        fun grantAgent(sessionId: String) {
            val current = uiState.value ?: return
            val pending = current.pendingAgent
            if (pending == null || pending.sessionId != sessionId) return
            uiState.postValue(current.copy(pendingAgent = null))
            pendingAgentDecisions.remove(sessionId)?.complete(true)
        }

        /** 用户在任务级授权对话框点"拒绝"或取消。 */
        fun denyAgent(sessionId: String) {
            val current = uiState.value ?: return
            val pending = current.pendingAgent
            if (pending == null || pending.sessionId != sessionId) return
            uiState.postValue(current.copy(pendingAgent = null))
            pendingAgentDecisions.remove(sessionId)?.complete(false)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speechRecognizer: SpeechRecognizer? = null
    private var cloudRecognitionJob: Job? = null
    private var isStopping = false
    private var isListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        updateUiState {
            it.copy(
                isRunning = true,
                status = VoiceCommandStatus.LISTENING,
                latestResult = null
            )
        }
        appendRecord(
            title = getString(R.string.voice_record_service_started),
            detail = getString(R.string.voice_command_notification_text)
        )
        createNotificationChannel()
        createAgentOutcomeNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.voice_command_notification_text)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_HANDLE_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                cancelActiveListening()
                handleRecognizedText(
                    textRaw = text,
                    restartAfterProcessing = intent.getBooleanExtra(EXTRA_KEEP_RUNNING, false),
                    stopAfterProcessing = !intent.getBooleanExtra(EXTRA_KEEP_RUNNING, false)
                )
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        isStopping = false
        startListening()
        return START_STICKY
    }

    private fun cancelActiveListening() {
        isListening = false
        cloudRecognitionJob?.cancel()
        cloudRecognitionJob = null
        speechRecognizer?.cancel()
    }

    override fun onDestroy() {
        isStopping = true
        mainHandler.removeCallbacksAndMessages(null)
        cloudRecognitionJob?.cancel()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
        appendRecord(
            title = getString(R.string.voice_record_service_stopped),
            detail = getString(R.string.voice_record_service_stopped_detail)
        )
        updateUiState {
            it.copy(
                isRunning = false,
                status = VoiceCommandStatus.IDLE,
                latestResult = null
            )
        }
        isRunning.value = false
        super.onDestroy()
    }

    private fun startListening() {
        if (isStopping || isListening) return
        val selectedService = Preferences.speechRecognitionService
        AsrServiceType.candidatesOf(selectedService).forEach { service ->
            if (startListeningWith(service)) return
        }
        val message = when (selectedService) {
            AsrServiceType.SYSTEM -> getString(R.string.voice_command_no_recognizer)
            else -> getString(R.string.voice_command_alibaba_config_missing)
        }
        notifyAndStop(message)
    }

    private fun startListeningWith(service: Int): Boolean {
        return when (service) {
            AsrServiceType.SYSTEM -> startSystemListening()
            AsrServiceType.ALIBABA -> startAlibabaListening()
            else -> false
        }
    }

    private fun startSystemListening(): Boolean {
        val recognizer = speechRecognizer ?: createSpeechRecognizerIfAvailable()?.also {
            it.setRecognitionListener(this)
            speechRecognizer = it
        } ?: return false
        isListening = true
        updateUiState {
            it.copy(isRunning = true, status = VoiceCommandStatus.LISTENING, latestResult = null)
        }
        updateNotification(getString(R.string.voice_command_listening))
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        )
        return true
    }

    private fun startAlibabaListening(): Boolean {
        if (!AsrServiceType.isAlibabaConfigured()) {
            return false
        }
        isListening = true
        updateUiState {
            it.copy(isRunning = true, status = VoiceCommandStatus.LISTENING, latestResult = null)
        }
        updateNotification(getString(R.string.voice_command_alibaba_listening))
        cloudRecognitionJob = scope.launch {
            val result = runCatching {
                AlibabaCloudAsrClient.recognizeOnce(this@VoiceCommandService)
            }
            isListening = false
            result.onSuccess { text ->
                if (text.isNullOrBlank()) {
                    toast(R.string.voice_command_alibaba_no_result)
                    updateNotification(getString(R.string.voice_command_alibaba_no_result))
                    appendRecord(
                        title = getString(R.string.voice_record_no_result),
                        detail = getString(R.string.voice_command_alibaba_no_result),
                        result = VoiceCommandRecordResult.FAILURE,
                        status = VoiceCommandStatus.ERROR
                    )
                    restartListeningDelayed()
                } else {
                    handleRecognizedText(text)
                }
            }.onFailure {
                val message = it.message ?: getString(R.string.voice_command_no_recognizer)
                notifyAndStop(message)
            }
        }
        return true
    }

    private fun notifyAndStop(message: String) {
        toast(message)
        updateNotification(message)
        appendRecord(
            title = getString(R.string.voice_record_error),
            detail = message,
            result = VoiceCommandRecordResult.FAILURE,
            status = VoiceCommandStatus.ERROR
        )
        stopSelf()
    }

    private fun restartListeningDelayed() {
        if (isStopping) return
        mainHandler.postDelayed({ startListening() }, RESTART_DELAY_MILLIS)
    }

    private fun handleRecognizedText(
        textRaw: String,
        restartAfterProcessing: Boolean = true,
        stopAfterProcessing: Boolean = false
    ) {
        // ASR 引擎偶尔会输出"重复一遍"的合成文本（"...帅...帅"），或者输入流双触发拼接。
        // 检测整段是否由两段相同子串拼成，是就只取一段——避免 AI 看到"重复闲聊"判 Unknown。
        val text = deduplicateRepeatedText(textRaw)
        if (text != textRaw) {
            AiAgentLog.w("voice.dedupe", "原始文本 \"$textRaw\" 检测到首尾重复，去重后 \"$text\"")
        }
        toast(getString(R.string.format_voice_command_heard, text))
        updateUiState {
            it.copy(
                status = VoiceCommandStatus.RECOGNIZING,
                latestText = text,
                latestCommand = null,
                latestTaskTitle = null,
                latestResult = null
            )
        }
        scope.launch {
            if (!TaskStorage.storageTaskLoaded) {
                TaskStorage.loadAllTasks()
            }
            // 代码匹配优先：唯一命中现有任务时直接执行，避免无谓的 AI 调用与 token 开销。
            // 歧义 / 未命中才让 AI 介入，AI 还能借助任务清单做消歧与新草稿生成。
            if (tryDirectTaskMatch(text)) {
                finishTextProcessing(restartAfterProcessing, stopAfterProcessing)
                return@launch
            }
            runAiInterpretation(text)
                ?: runRuleFallback(text)
            finishTextProcessing(restartAfterProcessing, stopAfterProcessing)
        }
    }

    /**
     * 在调用 AI 之前先用纯本地规则尝试匹配现有任务：
     * - 用原文整段做一次精确 / 模糊匹配。
     * - 用 [VoiceCommandParser.parseRunTaskQuery] 剥掉常见前缀后再试一次。
     *
     * 任一候选**唯一命中**即直接执行，写一条"代码已直接匹配"记录并返回 true。
     * 命中歧义或全部 NotFound 时返回 false，让 AI（拿到任务清单后）继续接管。
     */
    private fun tryDirectTaskMatch(text: String): Boolean {
        val candidates = buildList {
            text.trim().takeIf { it.isNotEmpty() }?.let(::add)
            VoiceCommandParser.parseRunTaskQuery(text)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }.distinct()
        if (candidates.isEmpty()) return false
        for (query in candidates) {
            val match = findTask(query)
            if (match is MatchResult.Found) {
                updateUiState {
                    it.copy(
                        latestCommand = query,
                        status = VoiceCommandStatus.RECOGNIZING
                    )
                }
                appendRecord(
                    title = getString(R.string.voice_record_direct_match),
                    detail = getString(
                        R.string.format_voice_command_direct_match,
                        match.task.title
                    )
                )
                launchTask(match.task)
                return true
            }
        }
        return false
    }

    /**
     * AI 主路径：意图理解 → 行动计划 → 风险评估 → 授权门禁 → 写决策记录 → 执行/草稿。
     * 返回 null 表示 AI 没有给出有效解释，调用方应回退到规则解析。
     *
     * 这里会把当前任务清单一并喂给 AI，便于 AI 在 RunExistingTask 时把 query
     * 严格设置为本地真实存在的任务名，减少"猜了个不存在的任务名再 fuzzy 失败"的概率。
     */
    private suspend fun runAiInterpretation(text: String): Unit? {
        val knownTaskTitles = TaskStorage.getAllTasks()
            .map { it.title }
            .filter { it.isNotBlank() }
            .distinct()
        val result = VoiceAiInterpreter.interpret(text, knownTaskTitles) ?: return null
        val interpretation = result.interpretation
        if (interpretation == null) {
            // AI 调用本身完成了，但返回内容未通过 schema/置信度等校验，给出可点开排查的记录。
            val diagnosticParts = listOfNotNull(result.providerError, result.rejectionReason)
            appendRecord(
                title = getString(R.string.voice_record_ai_invalid_output),
                detail = diagnosticParts.firstOrNull()
                    ?: getString(R.string.voice_command_ai_fallback),
                result = VoiceCommandRecordResult.FAILURE,
                prompt = result.prompt,
                rawResponse = result.rawResponse,
                diagnostic = diagnosticParts.joinToString("\n").ifBlank { null }
            )
            return null
        }
        if (interpretation is VoiceAiInterpretation.Unknown) {
            appendRecord(
                title = getString(R.string.voice_record_ai_unknown),
                detail = interpretation.summary.ifBlank {
                    getString(R.string.voice_command_ai_fallback)
                },
                prompt = result.prompt,
                rawResponse = result.rawResponse
            )
            recordAiDecision(
                source = AiDecisionSource.Voice,
                userGoal = text,
                intent = AiIntent(
                    type = AiIntentType.Unknown,
                    rawText = text,
                    confidence = interpretation.confidence
                ),
                actionPlan = null,
                executionResult = AiExecutionResult(status = AiExecutionStatus.Cancelled)
            )
            return null
        }
        val plan = buildActionPlan(text, interpretation)
        val gateResult = AiCenter.actionGate.review(plan)
        appendAiPlanRecord(
            interpretation = interpretation,
            plan = plan,
            gateResult = gateResult,
            prompt = result.prompt,
            rawResponse = result.rawResponse
        )
        if (gateResult.status != AiGateStatus.Allowed) {
            recordAiDecision(
                source = AiDecisionSource.Voice,
                userGoal = text,
                intent = plan.intent,
                actionPlan = plan,
                riskAssessment = gateResult.assessment,
                matchedGrantIds = gateResult.matchedGrants.map { it.id },
                userDecision = AiUserDecision.Rejected,
                executionResult = AiExecutionResult(
                    status = AiExecutionStatus.RejectedByPolicy,
                    message = gateResult.status.name
                )
            )
            return Unit
        }
        when (interpretation) {
            is VoiceAiInterpretation.RunExistingTask -> handleRunExistingTask(
                text = text,
                interpretation = interpretation,
                plan = plan,
                gateAssessment = gateResult.assessment,
                matchedGrantIds = gateResult.matchedGrants.map { it.id }
            )
            is VoiceAiInterpretation.CreateTaskDraft -> {
                // 启用了 agent 模式 → 不再只生成文字草稿，先尝试让 AI 真的去 App 里干一次。
                // 失败（AI 判定 estimated_steps=0、provider 错误等）才回退到文字草稿弹窗。
                val handled = if (Preferences.aiAgentEnabled) {
                    runAgentFlow(text)
                } else {
                    false
                }
                if (!handled) {
                    handleCreateTaskDraft(
                        text = text,
                        interpretation = interpretation,
                        plan = plan,
                        gateAssessment = gateResult.assessment,
                        matchedGrantIds = gateResult.matchedGrants.map { it.id }
                    )
                }
            }
            is VoiceAiInterpretation.Unknown -> Unit // already returned above
        }
        return Unit
    }

    /**
     * Agent 流程：planSession → 等用户授权 → 启动 [AiAgentSession] → 每步追加到 records。
     *
     * 返回 true 表示已经走完 agent 流程（成功 / 拒绝 / 失败均算"已处理"），调用方不应再走旧的
     * 文字草稿路径。返回 false 表示 AI 自己判断"这事儿不需要 agent"，让上层回退。
     */
    private suspend fun runAgentFlow(text: String): Boolean {
        appendRecord(
            title = getString(R.string.voice_record_agent_planning),
            detail = getString(R.string.voice_record_agent_planning_detail)
        )
        val planResult = AiAgentPlanner.planSession(text)
        if (planResult == null) {
            appendRecord(
                title = getString(R.string.voice_record_agent_unconfigured),
                detail = getString(R.string.voice_record_agent_unconfigured_detail),
                result = VoiceCommandRecordResult.FAILURE
            )
            return false
        }
        val plan = planResult.plan
        if (plan == null) {
            appendRecord(
                title = getString(R.string.voice_record_agent_plan_failed),
                detail = planResult.providerError
                    ?: getString(R.string.voice_record_agent_plan_failed_detail),
                result = VoiceCommandRecordResult.FAILURE,
                prompt = planResult.prompt,
                rawResponse = planResult.rawResponse
            )
            return false
        }
        if (!plan.isExecutable) {
            // AI 判断这事儿不需要 agent，让上层回退到文字草稿弹窗
            appendRecord(
                title = getString(R.string.voice_record_agent_skipped),
                detail = plan.summary,
                prompt = planResult.prompt,
                rawResponse = planResult.rawResponse
            )
            return false
        }

        val sessionId = UUID.randomUUID().toString()
        val targetApps = plan.targetAppPackage?.let { setOf(it) } ?: emptySet()
        val maxSteps = Preferences.aiAgentMaxSteps.coerceIn(1, 100)
        val maxSeconds = Preferences.aiAgentMaxSeconds.coerceIn(10, 600)

        appendRecord(
            title = getString(R.string.voice_record_agent_planned),
            detail = getString(
                R.string.format_voice_record_agent_planned,
                plan.targetAppLabel ?: plan.targetAppPackage ?: "—",
                plan.estimatedSteps
            ),
            prompt = planResult.prompt,
            rawResponse = planResult.rawResponse
        )

        // 暴露给 UI，等待用户授权
        val payload = AgentRequestPayload(
            sessionId = sessionId,
            userGoal = text,
            plan = plan,
            targetApps = targetApps,
            maxSteps = maxSteps,
            maxSeconds = maxSeconds
        )
        val decision = CompletableDeferred<Boolean>()
        pendingAgentDecisions[sessionId] = decision
        updateUiState { it.copy(pendingAgent = payload) }

        // 60 秒内用户没决策视为拒绝，避免 service 一直挂着。
        val granted = withTimeoutOrNull(60_000L) { decision.await() } ?: false
        pendingAgentDecisions.remove(sessionId)
        if (uiState.value?.pendingAgent?.sessionId == sessionId) {
            updateUiState { it.copy(pendingAgent = null) }
        }
        if (!granted) {
            appendRecord(
                title = getString(R.string.voice_record_agent_denied),
                detail = getString(R.string.voice_record_agent_denied_detail),
                result = VoiceCommandRecordResult.FAILURE
            )
            return true
        }

        // 用户允许：构造 scope，启动 session
        val scope = AiTaskScope(
            sessionId = sessionId,
            userGoal = text,
            targetApps = targetApps,
            // 默认补齐 agent 必备能力，避免 AI 临时输出 launch_app 时被自家 scope 拦下
            capabilities = plan.capabilities + setOf(
                AiCapability.ReadNodeTree,
                AiCapability.ClickUi,
                AiCapability.InputText,
                AiCapability.LaunchIntent
            ),
            maxSteps = maxSteps,
            maxDurationSeconds = maxSeconds
        )
        AiTaskScopeStore.grant(scope)
        updateUiState { it.copy(activeAgentSessionId = sessionId) }
        appendRecord(
            title = getString(R.string.voice_record_agent_started),
            detail = getString(
                R.string.format_voice_record_agent_started,
                plan.targetAppLabel ?: plan.targetAppPackage ?: "—",
                maxSteps,
                maxSeconds
            )
        )

        // 决策面板：每步在执行前征询用户同意 / 拒绝 / 换一个。
        // 没授 SYSTEM_ALERT_WINDOW / 用户在 Preferences 里关了模式，overlay 自动降级 noop。
        val overlay = AiAgentOverlayController(this)
        overlay.show()
        val session = AiAgentSession(
            scope = scope,
            plan = plan,
            overlay = overlay,
            picker = InspectorPickerStub(),
            callbacks = object : AiAgentSession.Callbacks {
                override fun onStep(record: AiAgentStepRecord) {
                    appendStepRecord(record)
                }

                override fun onComplete(outcome: AiAgentSessionOutcome) {
                    // 这里只做兜底，外层 run() 返回后会写一次完整结果
                }
            }
        )
        val outcome = runCatching { session.run() }.getOrElse {
            AiAgentSessionOutcome.AiError(
                reason = "agent 协程异常: ${it.message ?: it::class.simpleName}",
                history = emptyList(),
                lastRecord = AiAgentStepRecord(
                    index = 0,
                    action = AiAgentAction.Unknown(it.message.orEmpty()),
                    result = top.xjunz.tasker.ai.agent.AiAgentStepResult(false, it.message)
                )
            )
        }
        AiTaskScopeStore.revoke(sessionId)
        overlay.dismiss()
        updateUiState { it.copy(activeAgentSessionId = null) }
        appendOutcomeRecord(outcome, plan)
        notifyAgentOutcome(outcome, plan, userGoal = text)
        return true
    }

    private fun appendStepRecord(record: AiAgentStepRecord) {
        val planStatus = top.xjunz.tasker.ai.agent.AiAgentPlanStatus.parse(record.action.planStatus)
        val planLabel = planStatusLabel(planStatus)
        val title = getString(
            R.string.format_voice_record_agent_step_with_status,
            record.index + 1,
            planLabel,
            describeAgentAction(record.action)
        )
        val detail = buildString {
            record.action.thought?.takeIf { it.isNotBlank() }?.let {
                append("思考：")
                append(it)
                append('\n')
            }
            append(if (record.result.ok) "OK" else "FAIL")
            record.result.matchedNodeSummary?.let {
                append(" · ")
                append(it)
            }
            record.result.message?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        // off_track 视觉上显眼一点（即便是 OK），便于审计时一眼挑出来
        val resultColor = when {
            !record.result.ok -> VoiceCommandRecordResult.FAILURE
            planStatus == top.xjunz.tasker.ai.agent.AiAgentPlanStatus.OffTrack ->
                VoiceCommandRecordResult.FAILURE
            else -> VoiceCommandRecordResult.INFO
        }
        appendRecord(
            title = title,
            detail = detail,
            result = resultColor
        )
    }

    private fun planStatusLabel(status: top.xjunz.tasker.ai.agent.AiAgentPlanStatus): String {
        return when (status) {
            top.xjunz.tasker.ai.agent.AiAgentPlanStatus.OnTrack -> getString(R.string.ai_plan_status_on_track)
            top.xjunz.tasker.ai.agent.AiAgentPlanStatus.Adjusted -> getString(R.string.ai_plan_status_adjusted)
            top.xjunz.tasker.ai.agent.AiAgentPlanStatus.OffTrack -> getString(R.string.ai_plan_status_off_track)
            top.xjunz.tasker.ai.agent.AiAgentPlanStatus.Unknown -> getString(R.string.ai_plan_status_unknown)
        }
    }

    private fun appendOutcomeRecord(
        outcome: AiAgentSessionOutcome,
        plan: AiAgentSessionPlan? = null
    ) {
        val (title, result) = when (outcome) {
            is AiAgentSessionOutcome.Completed ->
                getString(R.string.voice_record_agent_completed) to VoiceCommandRecordResult.SUCCESS
            is AiAgentSessionOutcome.GivenUp ->
                getString(R.string.voice_record_agent_given_up) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.LimitExceeded ->
                getString(R.string.voice_record_agent_limit) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.OutOfScope ->
                getString(R.string.voice_record_agent_out_of_scope) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.PermissionDenied ->
                getString(R.string.voice_record_agent_permission_denied) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.AiError ->
                getString(R.string.voice_record_agent_ai_error) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.Cancelled ->
                getString(R.string.voice_record_agent_cancelled) to VoiceCommandRecordResult.FAILURE
            is AiAgentSessionOutcome.ServiceNotConnected ->
                getString(R.string.voice_record_agent_service_not_connected) to VoiceCommandRecordResult.FAILURE
        }
        val baseDetail = when (outcome) {
            is AiAgentSessionOutcome.Completed -> outcome.summary
            is AiAgentSessionOutcome.GivenUp -> outcome.reason
            is AiAgentSessionOutcome.LimitExceeded -> outcome.reason
            is AiAgentSessionOutcome.OutOfScope -> getString(
                R.string.format_voice_record_agent_out_of_scope_detail, outcome.currentPackage
            )
            is AiAgentSessionOutcome.PermissionDenied -> getString(
                R.string.format_voice_record_agent_permission_denied_detail,
                outcome.capability.name
            )
            is AiAgentSessionOutcome.AiError -> outcome.reason
            is AiAgentSessionOutcome.Cancelled -> getString(R.string.voice_record_agent_cancelled_detail)
            is AiAgentSessionOutcome.ServiceNotConnected ->
                getString(R.string.voice_record_agent_service_not_connected_detail)
        }
        val detail = buildString {
            append(baseDetail)
            val statsLine = buildPlanStatsLine(outcome.history, plan)
            if (statsLine.isNotEmpty()) {
                append('\n')
                append(statsLine)
            }
        }
        appendRecord(title = title, detail = detail, result = result)
    }

    /**
     * 拼一行"实际 N 步 / 估计 M 步 · on_track A · adjusted B · off_track C"统计，便于审计快速看
     * AI 是否在正轨上、跑偏了多少。`plan == null` 时只展示步数和分布，不带"估计"。
     */
    private fun buildPlanStatsLine(
        history: List<AiAgentStepRecord>,
        plan: AiAgentSessionPlan?
    ): String {
        if (history.isEmpty()) return ""
        var onTrack = 0
        var adjusted = 0
        var offTrack = 0
        var unknown = 0
        history.forEach { rec ->
            when (top.xjunz.tasker.ai.agent.AiAgentPlanStatus.parse(rec.action.planStatus)) {
                top.xjunz.tasker.ai.agent.AiAgentPlanStatus.OnTrack -> onTrack++
                top.xjunz.tasker.ai.agent.AiAgentPlanStatus.Adjusted -> adjusted++
                top.xjunz.tasker.ai.agent.AiAgentPlanStatus.OffTrack -> offTrack++
                top.xjunz.tasker.ai.agent.AiAgentPlanStatus.Unknown -> unknown++
            }
        }
        return if (plan != null) {
            getString(
                R.string.format_voice_record_agent_plan_stats,
                history.size,
                plan.estimatedSteps,
                onTrack,
                adjusted,
                offTrack,
                unknown
            )
        } else {
            getString(
                R.string.format_voice_record_agent_plan_stats_no_plan,
                history.size,
                onTrack,
                adjusted,
                offTrack,
                unknown
            )
        }
    }

    private fun describeAgentAction(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch ${action.packageName}"
        is AiAgentAction.Click -> "click ${formatAgentTarget(action.target)}"
        is AiAgentAction.LongClick -> "long_click ${formatAgentTarget(action.target)}"
        is AiAgentAction.SetText -> "set_text \"${action.text.take(30)}\" → ${formatAgentTarget(action.target)}"
        is AiAgentAction.Wait -> "wait ${action.seconds}s"
        is AiAgentAction.Scroll -> "scroll ${action.direction}"
        is AiAgentAction.GlobalBack -> "back"
        is AiAgentAction.GlobalHome -> "home"
        is AiAgentAction.Done -> "done"
        is AiAgentAction.GiveUp -> "give_up"
        is AiAgentAction.Unknown -> "unknown"
    }

    private fun formatAgentTarget(t: top.xjunz.tasker.ai.agent.AiUiTarget): String {
        val parts = mutableListOf<String>()
        t.viewId?.let { parts.add("id=$it") }
        t.textEquals?.let { parts.add("text=$it") }
        t.textContains?.let { parts.add("text~$it") }
        t.contentDescEquals?.let { parts.add("desc=$it") }
        t.contentDescContains?.let { parts.add("desc~$it") }
        t.className?.let { parts.add("cls=$it") }
        return parts.joinToString(",").take(80).ifEmpty { "(?)" }
    }

    /**
     * 当 AI 判断是 RunExistingTask 但本地任务库实际找不到时，再让 AI 把用户原话
     * 转换成一份新任务草稿，复用 [handleCreateTaskDraft] 走同一条门禁/审计/草稿弹窗流程。
     */
    private suspend fun handleMissingTaskWithDraftFallback(
        text: String,
        missingQuery: String
    ) {
        appendRecord(
            title = getString(R.string.voice_record_ai_draft_fallback),
            detail = getString(R.string.format_voice_command_ai_draft_fallback, missingQuery)
        )
        val fallback = VoiceAiInterpreter.generateDraftWhenTaskMissing(text, missingQuery)
        if (fallback == null) {
            appendRecord(
                title = getString(R.string.voice_record_ai_draft_fallback_failed),
                detail = getString(R.string.voice_command_ai_draft_fallback_failed),
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            return
        }
        val draft = fallback.draft
        val plan = buildActionPlan(text, draft)
        val gateResult = AiCenter.actionGate.review(plan)
        appendAiPlanRecord(
            interpretation = draft,
            plan = plan,
            gateResult = gateResult,
            prompt = fallback.prompt,
            rawResponse = fallback.rawResponse
        )
        if (gateResult.status != AiGateStatus.Allowed) {
            recordAiDecision(
                source = AiDecisionSource.Voice,
                userGoal = text,
                intent = plan.intent,
                actionPlan = plan,
                riskAssessment = gateResult.assessment,
                matchedGrantIds = gateResult.matchedGrants.map { it.id },
                userDecision = AiUserDecision.Rejected,
                executionResult = AiExecutionResult(
                    status = AiExecutionStatus.RejectedByPolicy,
                    message = gateResult.status.name
                )
            )
            return
        }
        handleCreateTaskDraft(
            text = text,
            interpretation = draft,
            plan = plan,
            gateAssessment = gateResult.assessment,
            matchedGrantIds = gateResult.matchedGrants.map { it.id }
        )
    }

    /**
     * AI 不可用、未启用、超时、欠费等情况下回退到现有规则解析，保证原始功能不被影响。
     */
    private fun runRuleFallback(text: String) {
        if (Preferences.aiEnabled) {
            appendRecord(
                title = getString(R.string.voice_record_ai_fallback),
                detail = getString(R.string.voice_command_ai_fallback)
            )
        }
        val query = VoiceCommandParser.parseRunTaskQuery(text)
        if (query == null) {
            appendRecord(
                title = getString(R.string.voice_record_parse_failed),
                detail = getString(R.string.format_voice_command_heard, text),
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            return
        }
        updateUiState {
            it.copy(
                latestCommand = query,
                status = VoiceCommandStatus.RECOGNIZING
            )
        }
        executeMatchedTask(query)
    }

    private suspend fun handleRunExistingTask(
        text: String,
        interpretation: VoiceAiInterpretation.RunExistingTask,
        plan: AiActionPlan,
        gateAssessment: AiRiskAssessment,
        matchedGrantIds: List<String>
    ) {
        updateUiState {
            it.copy(
                latestCommand = interpretation.query,
                status = VoiceCommandStatus.RECOGNIZING
            )
        }
        val matchResult = executeMatchedTask(interpretation.query)
        val executionStatus = when (matchResult) {
            is MatchResult.Found -> AiExecutionStatus.Succeeded
            else -> AiExecutionStatus.Cancelled
        }
        recordAiDecision(
            source = AiDecisionSource.Voice,
            userGoal = text,
            intent = plan.intent,
            actionPlan = plan,
            riskAssessment = gateAssessment,
            matchedGrantIds = matchedGrantIds,
            userDecision = if (matchResult is MatchResult.Found) {
                AiUserDecision.Accepted
            } else {
                AiUserDecision.Cancelled
            },
            executionResult = AiExecutionResult(
                status = executionStatus,
                message = uiState.value?.latestTaskTitle
            )
        )
        if (matchResult is MatchResult.NotFound) {
            handleMissingTaskWithDraftFallback(text, interpretation.query)
        }
    }

    private fun handleCreateTaskDraft(
        text: String,
        interpretation: VoiceAiInterpretation.CreateTaskDraft,
        plan: AiActionPlan,
        gateAssessment: AiRiskAssessment,
        matchedGrantIds: List<String>
    ) {
        val draft = VoiceCommandDraftPayload(
            id = UUID.randomUUID().toString(),
            title = interpretation.title,
            summary = interpretation.summary,
            steps = interpretation.steps,
            confidence = interpretation.confidence
        )
        appendRecord(
            title = getString(R.string.voice_record_ai_draft_ready),
            detail = getString(R.string.format_voice_command_ai_draft, draft.title)
        )
        updateUiState {
            it.copy(
                latestCommand = interpretation.title,
                latestTaskTitle = null,
                status = VoiceCommandStatus.RECOGNIZING,
                pendingDraft = draft,
                latestResult = VoiceCommandRecordResult.INFO
            )
        }
        recordAiDecision(
            source = AiDecisionSource.Voice,
            userGoal = text,
            intent = plan.intent,
            actionPlan = plan,
            riskAssessment = gateAssessment,
            matchedGrantIds = matchedGrantIds,
            userDecision = AiUserDecision.GrantedOnce,
            executionResult = AiExecutionResult(
                status = AiExecutionStatus.NotStarted,
                message = draft.title
            )
        )
    }

    private fun buildActionPlan(text: String, interpretation: VoiceAiInterpretation): AiActionPlan {
        val intentType = when (interpretation) {
            is VoiceAiInterpretation.RunExistingTask -> AiIntentType.RunExistingTask
            is VoiceAiInterpretation.CreateTaskDraft -> AiIntentType.CreateTaskDraft
            is VoiceAiInterpretation.Unknown -> AiIntentType.Unknown
        }
        val intent = AiIntent(
            type = intentType,
            rawText = text,
            confidence = interpretation.confidence,
            slots = when (interpretation) {
                is VoiceAiInterpretation.RunExistingTask -> mapOf("query" to interpretation.query)
                is VoiceAiInterpretation.CreateTaskDraft -> mapOf("title" to interpretation.title)
                else -> emptyMap()
            }
        )
        val steps = when (interpretation) {
            is VoiceAiInterpretation.RunExistingTask -> listOf(
                AiActionStep(
                    id = "match_task",
                    title = "匹配现有任务",
                    description = "在已有任务中查找：${interpretation.query}",
                    requiredCapabilities = setOf(AiCapability.MatchExistingTask),
                    riskLevel = AiRiskLevel.Low
                ),
                AiActionStep(
                    id = "execute_task",
                    title = "执行已有任务",
                    description = "执行匹配到的一次性任务",
                    requiredCapabilities = setOf(AiCapability.ExecuteExistingTask),
                    riskLevel = AiRiskLevel.Medium
                )
            )
            is VoiceAiInterpretation.CreateTaskDraft -> listOf(
                AiActionStep(
                    id = "generate_draft",
                    title = "生成任务草稿",
                    description = "为「${interpretation.title}」生成任务草稿",
                    requiredCapabilities = setOf(
                        AiCapability.UnderstandText,
                        AiCapability.CreateTaskDraft
                    ),
                    riskLevel = AiRiskLevel.Low
                )
            )
            is VoiceAiInterpretation.Unknown -> listOf(
                AiActionStep(
                    id = "understand",
                    title = "解析意图",
                    description = "尝试理解用户输入",
                    requiredCapabilities = setOf(AiCapability.UnderstandText),
                    riskLevel = AiRiskLevel.Low
                )
            )
        }
        return AiActionPlan(
            id = UUID.randomUUID().toString(),
            userGoal = text,
            intent = intent,
            steps = steps,
            scope = AiScope.Any,
            summary = interpretation.summary.ifBlank { text }
        )
    }

    private fun appendAiPlanRecord(
        interpretation: VoiceAiInterpretation,
        plan: AiActionPlan,
        gateResult: AiGateResult,
        prompt: String? = null,
        rawResponse: String? = null
    ) {
        val confidencePct = (interpretation.confidence * 100).toInt()
        val title = when (interpretation) {
            is VoiceAiInterpretation.RunExistingTask -> getString(
                R.string.format_voice_command_ai_interpreted,
                interpretation.query,
                confidencePct
            )
            is VoiceAiInterpretation.CreateTaskDraft -> getString(
                R.string.format_voice_command_ai_draft,
                interpretation.title
            )
            is VoiceAiInterpretation.Unknown -> getString(R.string.voice_record_ai_unknown)
        }
        val risk = when (gateResult.assessment.riskLevel) {
            AiRiskLevel.Low -> "低"
            AiRiskLevel.Medium -> "中"
            AiRiskLevel.High -> "高"
            AiRiskLevel.Critical -> "极高"
        }
        val grantHint = when (gateResult.status) {
            AiGateStatus.Allowed -> "已授权（${gateResult.matchedGrants.size} 条）"
            AiGateStatus.RequiresConfirmation -> "需要用户确认"
            AiGateStatus.RequiresGrant -> "缺少授权"
        }
        appendRecord(
            title = title,
            detail = "风险：$risk · $grantHint · ${plan.summary}",
            result = if (gateResult.status == AiGateStatus.Allowed) {
                VoiceCommandRecordResult.INFO
            } else {
                VoiceCommandRecordResult.FAILURE
            },
            prompt = prompt,
            rawResponse = rawResponse
        )
    }

    private fun recordAiDecision(
        source: AiDecisionSource,
        userGoal: String,
        intent: AiIntent? = null,
        actionPlan: AiActionPlan? = null,
        riskAssessment: AiRiskAssessment? = null,
        matchedGrantIds: List<String> = emptyList(),
        userDecision: AiUserDecision? = null,
        executionResult: AiExecutionResult? = null
    ) {
        val assessment = riskAssessment ?: AiRiskAssessment(
            riskLevel = AiRiskLevel.Low,
            requiredCapabilities = emptySet(),
            sensitiveDataTypes = emptySet(),
            reasons = emptyList(),
            requiresConfirmation = false
        )
        val record = AiDecisionRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = source,
            userGoal = userGoal,
            modelName = Preferences.aiProviderModel,
            intent = intent,
            actionPlan = actionPlan,
            riskAssessment = assessment,
            matchedGrantIds = matchedGrantIds,
            userDecision = userDecision,
            executionResult = executionResult
        )
        scope.launch {
            runCatching { AiCenter.auditStore.append(record) }
        }
    }

    private fun finishTextProcessing(restartAfterProcessing: Boolean, stopAfterProcessing: Boolean) {
        when {
            restartAfterProcessing -> restartListeningDelayed()
            stopAfterProcessing -> stopSelf()
        }
    }

    private fun executeMatchedTask(query: String): MatchResult {
        val result = findTask(query)
        when (result) {
            is MatchResult.NotFound -> {
                val message = getString(R.string.format_voice_command_not_found, query)
                toast(message)
                updateNotification(message)
                appendRecord(
                    title = getString(R.string.voice_record_match_failed),
                    detail = message,
                    result = VoiceCommandRecordResult.FAILURE,
                    status = VoiceCommandStatus.ERROR
                )
            }

            is MatchResult.Ambiguous -> {
                val names = result.tasks.take(5).joinToString("、") { it.title }
                val message = getString(R.string.format_voice_command_ambiguous, names)
                toast(message)
                updateNotification(message)
                appendRecord(
                    title = getString(R.string.voice_record_match_ambiguous),
                    detail = message,
                    result = VoiceCommandRecordResult.FAILURE,
                    status = VoiceCommandStatus.ERROR
                )
            }

            is MatchResult.Found -> launchTask(result.task)
        }
        return result
    }

    private fun launchTask(task: XTask) {
        toast(getString(R.string.format_voice_command_matched, task.title))
        updateNotification(getString(R.string.format_voice_command_matched, task.title))
        updateUiState {
            it.copy(
                latestTaskTitle = task.title,
                status = VoiceCommandStatus.EXECUTING,
                latestResult = null
            )
        }
        if (task.isResident) {
            val message = getString(R.string.format_voice_command_unsupported_task, task.title)
            toast(message)
            updateNotification(message)
            appendRecord(
                title = getString(R.string.voice_record_unsupported_task),
                detail = message,
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            return
        }
        if (!serviceController.isServiceRunning) {
            toast(R.string.service_not_started)
            updateNotification(getString(R.string.service_not_started))
            appendRecord(
                title = getString(R.string.voice_record_service_not_started),
                detail = getString(R.string.service_not_started),
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            return
        }
        LocalTaskManager.addOneshotTaskIfAbsent(task)
        currentService.scheduleOneshotTask(
            task,
            object : ITaskCompletionCallback.Stub() {
                override fun onTaskCompleted(isSuccessful: Boolean) {
                    mainHandler.post {
                        val message = getString(
                            if (isSuccessful) R.string.format_voice_command_finished
                            else R.string.format_voice_command_failed,
                            task.title
                        )
                        toast(message)
                        updateNotification(message)
                        appendRecord(
                            title = if (isSuccessful) {
                                getString(R.string.voice_record_task_succeeded)
                            } else {
                                getString(R.string.voice_record_task_failed)
                            },
                            detail = message,
                            result = if (isSuccessful) {
                                VoiceCommandRecordResult.SUCCESS
                            } else {
                                VoiceCommandRecordResult.FAILURE
                            },
                            status = if (isSuccessful) {
                                VoiceCommandStatus.LISTENING
                            } else {
                                VoiceCommandStatus.ERROR
                            }
                        )
                    }
                }
            }
        )
        toast(getString(R.string.format_voice_command_launching, task.title))
        appendRecord(
            title = getString(R.string.voice_record_task_launching),
            detail = getString(R.string.format_voice_command_launching, task.title)
        )
    }

    private fun findTask(query: String): MatchResult {
        val tasks = TaskStorage.getAllTasks()
        val exactMatches = tasks.filter { it.title == query }
        if (exactMatches.size == 1) return MatchResult.Found(exactMatches.single())
        if (exactMatches.size > 1) return MatchResult.Ambiguous(exactMatches)

        val normalizedQuery = query.normalizedForVoiceMatch()
        // **只保留正向匹配**：title 完整包含 query。
        // 反向匹配（query 包含 title）历史上引起过严重 bug：用户输入"打开 deepseek 问问我为什么这么帅"，
        // 本地任意一个名为"打开 deepseek"或"deepseek"的旧 task 都会被 normalizedQuery.contains(title)
        // 命中，触发"直接匹配"分支直接执行老 task，AI agent 永远不启动。
        // 正向匹配符合"短命令命中长 task 名"的合理直觉（比如"打开微信"命中"打开微信发朋友圈"），
        // 反向是反人类的——用户长复杂命令里碰巧含某个 task 标题就直接执行，应该让 AI 决策。
        val fuzzyMatches = tasks.filter {
            val title = it.title.normalizedForVoiceMatch()
            title.contains(normalizedQuery)
        }
        return when (fuzzyMatches.size) {
            0 -> MatchResult.NotFound
            1 -> MatchResult.Found(fuzzyMatches.single())
            else -> MatchResult.Ambiguous(fuzzyMatches)
        }
    }

    private fun String.normalizedForVoiceMatch(): String {
        return lowercase(Locale.getDefault()).replace(Regex("[\\s，。,.！!？?：:；;“”\"'、]"), "")
    }

    /**
     * 去除"整段由两段相同子串前后拼接"的脏数据，常见于：
     * - ASR 引擎在用户卡顿时把同一段话识别两遍并拼接；
     * - 触摸事件双触发让同一文本被两次入队。
     *
     * 仅在偶数长度 + 上下半完全一致 + 长度 ≥ 6 字符时去重，避免误伤"哈哈"等正常重叠词。
     * 也兼顾"中间多个空格"的场景：先 normalize 空白再比较。
     */
    private fun deduplicateRepeatedText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < 6) return raw
        val normalized = trimmed.replace(Regex("\\s+"), "")
        val len = normalized.length
        if (len % 2 != 0) return raw
        val half = len / 2
        val first = normalized.substring(0, half)
        val second = normalized.substring(half)
        return if (first == second) first else raw
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_status_control)
        .setContentTitle(getString(R.string.voice_command))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(
            R.drawable.ic_baseline_stop_24,
            getString(R.string.voice_command_stop),
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceCommandService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_command),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createAgentOutcomeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OUTCOME_CHANNEL_ID,
                getString(R.string.ai_agent_outcome_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.ai_agent_outcome_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Agent 一次会话结束后发条独立通知告知用户：成功/失败、跑了多少步、AI 给的总结。
     * 跟前台常驻通知（[CHANNEL_ID]）完全独立，不会覆盖、不影响监听状态显示。
     *
     * 通知 ID 在 [OUTCOME_NOTIFICATION_ID_BASE, OUTCOME_NOTIFICATION_ID_BASE + RANGE) 内循环，
     * 既保证多条通知不互相覆盖（用户能往上翻历史），又不会无限堆积占满系统抽屉。
     */
    private fun notifyAgentOutcome(
        outcome: AiAgentSessionOutcome,
        plan: AiAgentSessionPlan?,
        userGoal: String
    ) {
        val (titleRes, isSuccess) = when (outcome) {
            is AiAgentSessionOutcome.Completed ->
                R.string.voice_record_agent_completed to true
            is AiAgentSessionOutcome.GivenUp ->
                R.string.voice_record_agent_given_up to false
            is AiAgentSessionOutcome.LimitExceeded ->
                R.string.voice_record_agent_limit to false
            is AiAgentSessionOutcome.OutOfScope ->
                R.string.voice_record_agent_out_of_scope to false
            is AiAgentSessionOutcome.PermissionDenied ->
                R.string.voice_record_agent_permission_denied to false
            is AiAgentSessionOutcome.AiError ->
                R.string.voice_record_agent_ai_error to false
            is AiAgentSessionOutcome.Cancelled ->
                R.string.voice_record_agent_cancelled to false
            is AiAgentSessionOutcome.ServiceNotConnected ->
                R.string.voice_record_agent_service_not_connected to false
        }
        val baseDetail = when (outcome) {
            is AiAgentSessionOutcome.Completed -> outcome.summary
            is AiAgentSessionOutcome.GivenUp -> outcome.reason
            is AiAgentSessionOutcome.LimitExceeded -> outcome.reason
            is AiAgentSessionOutcome.OutOfScope -> getString(
                R.string.format_voice_record_agent_out_of_scope_detail, outcome.currentPackage
            )
            is AiAgentSessionOutcome.PermissionDenied -> getString(
                R.string.format_voice_record_agent_permission_denied_detail,
                outcome.capability.name
            )
            is AiAgentSessionOutcome.AiError -> outcome.reason
            is AiAgentSessionOutcome.Cancelled -> getString(R.string.voice_record_agent_cancelled_detail)
            is AiAgentSessionOutcome.ServiceNotConnected ->
                getString(R.string.voice_record_agent_service_not_connected_detail)
        }
        val statsLine = buildPlanStatsLine(outcome.history, plan)
        val title = getString(titleRes)
        val text = getString(
            R.string.format_ai_agent_outcome_notification_text,
            userGoal.take(40),
            baseDetail.take(80)
        )
        val bigText = buildString {
            append("目标：")
            append(userGoal)
            append('\n')
            append(baseDetail)
            if (statsLine.isNotEmpty()) {
                append('\n')
                append(statsLine)
            }
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isSuccess) R.drawable.ic_check_circle_24px else R.drawable.ic_cancel_24px
        val notification = NotificationCompat.Builder(this, OUTCOME_CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        val id = OUTCOME_NOTIFICATION_ID_BASE +
                (outcomeNotificationCounter.getAndIncrement() % OUTCOME_NOTIFICATION_ID_RANGE)
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun createSpeechRecognizerIfAvailable(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            return SpeechRecognizer.createSpeechRecognizer(this)
        }
        return null
    }

    override fun onReadyForSpeech(params: Bundle?) {
        updateUiState {
            it.copy(status = VoiceCommandStatus.LISTENING, latestResult = null)
        }
    }

    override fun onBeginningOfSpeech() {
        updateUiState {
            it.copy(status = VoiceCommandStatus.RECOGNIZING, latestResult = null)
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
    }

    override fun onBufferReceived(buffer: ByteArray?) {
    }

    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        if (!isStopping && error != SpeechRecognizer.ERROR_CLIENT) {
            updateUiState {
                it.copy(status = VoiceCommandStatus.LISTENING, latestResult = null)
            }
            restartListeningDelayed()
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (text.isNullOrBlank()) {
            appendRecord(
                title = getString(R.string.voice_record_no_result),
                detail = getString(R.string.voice_record_no_result_detail),
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            restartListeningDelayed()
        } else {
            handleRecognizedText(text)
        }
    }

    private fun appendRecord(
        title: String,
        detail: String? = null,
        result: VoiceCommandRecordResult = VoiceCommandRecordResult.INFO,
        status: VoiceCommandStatus? = null,
        prompt: String? = null,
        rawResponse: String? = null,
        diagnostic: String? = null
    ) {
        updateUiState { current ->
            val record = VoiceCommandRecord(
                timestamp = System.currentTimeMillis(),
                title = title,
                detail = detail,
                result = result,
                prompt = prompt,
                rawResponse = rawResponse,
                diagnostic = diagnostic
            )
            current.copy(
                status = status ?: current.status,
                latestResult = result,
                records = (listOf(record) + current.records).take(MAX_RECORD_COUNT)
            )
        }
    }

    private fun updateUiState(block: (VoiceCommandUiState) -> VoiceCommandUiState) {
        val next = block(uiState.value ?: VoiceCommandUiState())
        uiState.value = next
        isRunning.value = next.isRunning
    }

    override fun onPartialResults(partialResults: Bundle?) {
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
    }

    private sealed interface MatchResult {
        data object NotFound : MatchResult
        data class Found(val task: XTask) : MatchResult
        data class Ambiguous(val tasks: List<XTask>) : MatchResult
    }
}

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
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.AiCenter
import top.xjunz.tasker.ai.agent.AiDraftStep
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
import java.util.Locale
import java.util.UUID

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
    val pendingDraft: VoiceCommandDraftPayload? = null
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
         * Fragment 处理完草稿（保存或忽略）后调用，清空 [VoiceCommandUiState.pendingDraft]，
         * 防止下次回到语音页又重新弹出。
         */
        fun consumeDraft(draftId: String) {
            val current = uiState.value ?: return
            val draft = current.pendingDraft ?: return
            if (draft.id != draftId) return
            uiState.postValue(current.copy(pendingDraft = null))
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
                    text = text,
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
        text: String,
        restartAfterProcessing: Boolean = true,
        stopAfterProcessing: Boolean = false
    ) {
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
            is VoiceAiInterpretation.CreateTaskDraft -> handleCreateTaskDraft(
                text = text,
                interpretation = interpretation,
                plan = plan,
                gateAssessment = gateResult.assessment,
                matchedGrantIds = gateResult.matchedGrants.map { it.id }
            )
            is VoiceAiInterpretation.Unknown -> Unit // already returned above
        }
        return Unit
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
        val fuzzyMatches = tasks.filter {
            val title = it.title.normalizedForVoiceMatch()
            title.contains(normalizedQuery) || normalizedQuery.contains(title)
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

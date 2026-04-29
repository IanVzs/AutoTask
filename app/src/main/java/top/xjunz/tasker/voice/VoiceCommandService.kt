/*
 * Copyright (c) 2026 xjunz. All rights reserved.
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
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager
import top.xjunz.tasker.task.storage.TaskStorage
import java.util.Locale

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
    val result: VoiceCommandRecordResult = VoiceCommandRecordResult.INFO
)

data class VoiceCommandUiState(
    val isRunning: Boolean = false,
    val status: VoiceCommandStatus = VoiceCommandStatus.IDLE,
    val latestText: String? = null,
    val latestCommand: String? = null,
    val latestTaskTitle: String? = null,
    val latestResult: VoiceCommandRecordResult? = null,
    val records: List<VoiceCommandRecord> = emptyList()
)

class VoiceCommandService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "top.xjunz.tasker.voice.action.START"
        const val ACTION_STOP = "top.xjunz.tasker.voice.action.STOP"

        val isRunning = MutableLiveData(false)
        val uiState = MutableLiveData(VoiceCommandUiState())

        private const val CHANNEL_ID = "voice_command"
        private const val NOTIFICATION_ID = 0x610
        private const val RESTART_DELAY_MILLIS = 800L
        private const val MAX_RECORD_COUNT = 30
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
        isStopping = false
        startListening()
        return START_STICKY
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
        when (Preferences.speechRecognitionService) {
            AsrServiceType.SYSTEM -> {
                if (!startSystemListening()) {
                    notifyAndStop(getString(R.string.voice_command_no_recognizer))
                }
            }

            AsrServiceType.ALIBABA -> startAlibabaListeningOrStop()
            else -> {
                if (!startSystemListening()) {
                    if (AsrServiceType.isAlibabaConfigured()) {
                        startAlibabaListeningOrStop()
                    } else {
                        notifyAndStop(getString(R.string.voice_command_alibaba_config_missing))
                    }
                }
            }
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

    private fun startAlibabaListeningOrStop() {
        if (!AsrServiceType.isAlibabaConfigured()) {
            notifyAndStop(getString(R.string.voice_command_alibaba_config_missing))
            return
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

    private fun handleRecognizedText(text: String) {
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
        val query = VoiceCommandParser.parseRunTaskQuery(text)
        if (query == null) {
            appendRecord(
                title = getString(R.string.voice_record_parse_failed),
                detail = getString(R.string.format_voice_command_heard, text),
                result = VoiceCommandRecordResult.FAILURE,
                status = VoiceCommandStatus.ERROR
            )
            restartListeningDelayed()
            return
        }
        updateUiState {
            it.copy(
                latestCommand = query,
                status = VoiceCommandStatus.RECOGNIZING
            )
        }
        scope.launch {
            if (!TaskStorage.storageTaskLoaded) {
                TaskStorage.loadAllTasks()
            }
            executeMatchedTask(query)
            restartListeningDelayed()
        }
    }

    private fun executeMatchedTask(query: String) {
        when (val result = findTask(query)) {
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
        status: VoiceCommandStatus? = null
    ) {
        updateUiState { current ->
            val record = VoiceCommandRecord(
                timestamp = System.currentTimeMillis(),
                title = title,
                detail = detail,
                result = result
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

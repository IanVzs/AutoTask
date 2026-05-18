/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook
import top.xjunz.tasker.ai.draft.AiConvertedStepStatus
import top.xjunz.tasker.ai.draft.AiTaskDraftConversionResult
import top.xjunz.tasker.ai.draft.AiTaskDraftConverter
import top.xjunz.tasker.databinding.FragmentVoiceCommandBinding
import top.xjunz.tasker.databinding.ItemVoiceCommandRecordBinding
import top.xjunz.tasker.ktx.observe
import top.xjunz.tasker.ktx.show
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.ui.base.BaseFragment
import top.xjunz.tasker.ui.main.MainViewModel.Companion.peekMainViewModel
import top.xjunz.tasker.ui.main.ScrollTarget
import top.xjunz.tasker.ui.task.editor.FlowEditorDialog
import top.xjunz.tasker.ui.task.showcase.TaskShowcaseViewModel
import top.xjunz.tasker.ui.voice.experience.AiExperienceBookDialog
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import top.xjunz.tasker.util.formatTime
import top.xjunz.tasker.voice.AgentRequestPayload
import top.xjunz.tasker.voice.VoiceCommandDraftPayload
import top.xjunz.tasker.voice.VoiceCommandRecord
import top.xjunz.tasker.voice.VoiceCommandRecordResult
import top.xjunz.tasker.voice.VoiceCommandService
import top.xjunz.tasker.voice.VoiceCommandStatus
import top.xjunz.tasker.voice.VoiceCommandUiState

class VoiceCommandFragment : BaseFragment<FragmentVoiceCommandBinding>(), ScrollTarget {

    private val recordAdapter = VoiceCommandRecordAdapter()

    private val taskShowcaseViewModel by activityViewModels<TaskShowcaseViewModel>()

    private var lastShownDraftId: String? = null
    private var lastShownAgentRequestId: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (requiredVoiceCommandPermissions().all { permission ->
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        permission
                    ) == PackageManager.PERMISSION_GRANTED
                }) {
                startVoiceCommandService()
            } else {
                toast(R.string.voice_command_permission_denied)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvRecords.adapter = recordAdapter
        binding.btnVoiceControl.setNoDoubleClickListener {
            if (VoiceCommandService.uiState.value?.isRunning == true) {
                stopVoiceCommandService()
            } else {
                requestStartVoiceCommandService()
            }
        }
        binding.btnSendTextCommand.setNoDoubleClickListener {
            submitTextCommand()
        }
        binding.cardExperienceBook.setNoDoubleClickListener {
            AiExperienceBookDialog().show(parentFragmentManager)
        }
        binding.inputTextCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTextCommand()
                true
            } else {
                false
            }
        }
        val mainViewModel = peekMainViewModel()
        observe(mainViewModel.appbarHeight) {
            binding.scrollContent.updatePadding(top = it)
        }
        observe(mainViewModel.paddingBottom) {
            binding.scrollContent.updatePadding(bottom = it)
        }
        observe(VoiceCommandService.uiState) {
            renderState(it)
            // 每次 service 状态变化（包括 session 结束写完经验本后）顺便刷一次卡片摘要
            refreshExperienceUsage()
        }
        refreshExperienceUsage()
    }

    override fun onResume() {
        super.onResume()
        refreshExperienceUsage()
    }

    private fun refreshExperienceUsage() {
        if (!isAdded) return
        val ctx = context ?: return
        // suspend API：经验本 IO 走 Dispatchers.IO，结果在主线程更新 TextView
        viewLifecycleOwner.lifecycleScope.launch {
            val count = AiAgentExperienceBook.queryAll(ctx).size
            if (!isAdded) return@launch
            if (count == 0) {
                binding.tvExperienceUsage.setText(R.string.ai_experience_book_card_usage_empty)
            } else {
                val bytes = AiAgentExperienceBook.usageBytes(ctx)
                if (!isAdded) return@launch
                binding.tvExperienceUsage.text = getString(
                    R.string.format_ai_experience_book_card_usage,
                    count,
                    formatBytes(bytes)
                )
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
    }

    override fun getScrollTarget(): View? {
        return if (isAdded) binding.scrollContent else null
    }

    private fun renderState(state: VoiceCommandUiState) {
        binding.tvStatus.setText(state.status.labelRes(state.isRunning))
        binding.tvStatusDetail.setText(state.status.detailRes(state.isRunning))
        binding.ivStatusIcon.setImageResource(state.status.iconRes(state.isRunning))
        binding.btnVoiceControl.isActivated = state.isRunning
        if (state.isRunning) {
            binding.btnVoiceControl.setText(R.string.stop_voice_command)
            binding.btnVoiceControl.setIconResource(R.drawable.ic_baseline_stop_24)
        } else {
            binding.btnVoiceControl.setText(R.string.start_voice_command)
            binding.btnVoiceControl.setIconResource(R.drawable.ic_mic_24px)
        }
        binding.tvLatestText.text = if (state.latestText.isNullOrBlank()) {
            getString(R.string.voice_latest_text_empty)
        } else {
            getString(R.string.format_voice_latest_text, state.latestText)
        }
        binding.tvLatestCommand.isVisible = !state.latestCommand.isNullOrBlank()
        binding.tvLatestCommand.text = getString(R.string.format_voice_latest_command, state.latestCommand)
        binding.tvLatestTask.isVisible = !state.latestTaskTitle.isNullOrBlank()
        binding.tvLatestTask.text = getString(R.string.format_voice_latest_task, state.latestTaskTitle)
        binding.tvRecordsEmpty.isVisible = state.records.isEmpty()
        recordAdapter.submitList(state.records)
        val draft = state.pendingDraft
        if (draft != null && draft.id != lastShownDraftId) {
            lastShownDraftId = draft.id
            showAiDraftDialog(draft)
        }
        val agent = state.pendingAgent
        if (agent != null && agent.sessionId != lastShownAgentRequestId) {
            lastShownAgentRequestId = agent.sessionId
            showAgentAuthorizationDialog(agent)
        }
        if (agent == null) {
            lastShownAgentRequestId = null
        }
    }

    private fun showAgentAuthorizationDialog(payload: AgentRequestPayload) {
        val plan = payload.plan
        val targetAppLabel = plan.targetAppLabel
            ?: plan.targetAppPackage
            ?: getString(R.string.agent_capability_unspecified)
        val capabilitiesText = if (plan.capabilities.isEmpty()) {
            getString(R.string.agent_capability_unspecified)
        } else {
            plan.capabilities.joinToString("\n") { "• ${it.name}" }
        }
        val message = getString(
            R.string.format_agent_authorize_message,
            plan.summary,
            targetAppLabel,
            payload.maxSteps,
            payload.maxSeconds,
            capabilitiesText
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.agent_authorize_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.agent_authorize_allow) { _, _ ->
                VoiceCommandService.grantAgent(payload.sessionId)
            }
            .setNegativeButton(R.string.agent_authorize_deny) { _, _ ->
                VoiceCommandService.denyAgent(payload.sessionId)
            }
            .setOnCancelListener {
                VoiceCommandService.denyAgent(payload.sessionId)
            }
            .show()
    }

    private fun showAiDraftDialog(draft: VoiceCommandDraftPayload) {
        val conversion = runCatching { AiTaskDraftConverter.convert(toInterpretation(draft)) }
            .onFailure { toast(R.string.ai_draft_convert_failed) }
            .getOrNull()
        val message = buildString {
            if (draft.summary.isNotBlank()) {
                appendLine(getString(R.string.format_ai_draft_summary, draft.summary))
                appendLine()
            }
            draft.steps.forEachIndexed { index, step ->
                append(index + 1)
                append(". ")
                appendLine(step.description)
            }
            if (conversion != null) {
                appendLine()
                append(
                    getString(
                        R.string.format_ai_draft_conversion_summary,
                        conversion.convertedCount,
                        conversion.unsupportedCount
                    )
                )
            }
            appendLine()
            append(getString(R.string.ai_draft_dialog_caption))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_draft_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.ai_draft_open_editor) { _, _ ->
                openDraftInEditor(draft, conversion)
                VoiceCommandService.consumeDraft(draft.id)
            }
            .setNegativeButton(R.string.ai_draft_dismiss) { _, _ ->
                VoiceCommandService.consumeDraft(draft.id)
            }
            .setOnCancelListener {
                VoiceCommandService.consumeDraft(draft.id)
            }
            .show()
    }

    private fun openDraftInEditor(
        draft: VoiceCommandDraftPayload,
        conversion: AiTaskDraftConversionResult?
    ) {
        val task = conversion?.task
            ?: AiTaskDraftConverter.convert(toInterpretation(draft)).task
        FlowEditorDialog().initBase(task, false).doOnTaskEdited {
            taskShowcaseViewModel.requestAddNewTasks.value = listOf(task)
        }.show(parentFragmentManager)
        val unsupported = conversion?.unsupportedSteps.orEmpty()
        if (unsupported.isNotEmpty()) {
            val firstReason = unsupported.firstNotNullOfOrNull { it.detail } ?: ""
            toast(
                getString(
                    R.string.format_ai_draft_unsupported_steps,
                    unsupported.size,
                    firstReason
                )
            )
        }
        val converted = conversion?.convertedSteps.orEmpty()
        converted.filter { it.status == AiConvertedStepStatus.Converted }.forEach { /* no-op, log placeholder */ }
    }

    private fun toInterpretation(
        draft: VoiceCommandDraftPayload
    ): top.xjunz.tasker.ai.agent.VoiceAiInterpretation.CreateTaskDraft {
        return top.xjunz.tasker.ai.agent.VoiceAiInterpretation.CreateTaskDraft(
            title = draft.title,
            steps = draft.steps,
            summary = draft.summary,
            confidence = draft.confidence
        )
    }

    private fun requestStartVoiceCommandService() {
        val missing = requiredVoiceCommandPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startVoiceCommandService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredVoiceCommandPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startVoiceCommandService() {
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), VoiceCommandService::class.java).setAction(
                VoiceCommandService.ACTION_START
            )
        )
    }

    private fun submitTextCommand() {
        val text = binding.inputTextCommand.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            binding.tilTextCommand.error = getString(R.string.error_empty_input)
            return
        }
        binding.tilTextCommand.error = null
        binding.inputTextCommand.text = null
        handleTextCommand(text)
    }

    private fun handleTextCommand(text: String) {
        val keepRunning = VoiceCommandService.uiState.value?.isRunning == true
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), VoiceCommandService::class.java)
                .setAction(VoiceCommandService.ACTION_HANDLE_TEXT)
                .putExtra(VoiceCommandService.EXTRA_TEXT, text)
                .putExtra(VoiceCommandService.EXTRA_KEEP_RUNNING, keepRunning)
        )
    }

    private fun stopVoiceCommandService() {
        requireContext().stopService(Intent(requireContext(), VoiceCommandService::class.java))
    }

    private fun VoiceCommandStatus.labelRes(isRunning: Boolean): Int {
        return if (!isRunning) R.string.voice_status_idle else when (this) {
            VoiceCommandStatus.IDLE -> R.string.voice_status_idle
            VoiceCommandStatus.LISTENING -> R.string.voice_status_listening
            VoiceCommandStatus.RECOGNIZING -> R.string.voice_status_recognizing
            VoiceCommandStatus.EXECUTING -> R.string.voice_status_executing
            VoiceCommandStatus.ERROR -> R.string.voice_status_error
        }
    }

    private fun VoiceCommandStatus.detailRes(isRunning: Boolean): Int {
        return if (!isRunning) R.string.voice_status_idle_detail else when (this) {
            VoiceCommandStatus.IDLE -> R.string.voice_status_idle_detail
            VoiceCommandStatus.LISTENING -> R.string.voice_status_listening_detail
            VoiceCommandStatus.RECOGNIZING -> R.string.voice_status_recognizing_detail
            VoiceCommandStatus.EXECUTING -> R.string.voice_status_executing_detail
            VoiceCommandStatus.ERROR -> R.string.voice_status_error_detail
        }
    }

    private fun VoiceCommandStatus.iconRes(isRunning: Boolean): Int {
        return if (!isRunning) R.drawable.ic_baseline_auto_awesome_24 else when (this) {
            VoiceCommandStatus.IDLE -> R.drawable.ic_baseline_auto_awesome_24
            VoiceCommandStatus.LISTENING -> R.drawable.ic_mic_24px
            VoiceCommandStatus.RECOGNIZING -> R.drawable.ic_baseline_auto_awesome_24
            VoiceCommandStatus.EXECUTING -> R.drawable.ic_baseline_send_24
            VoiceCommandStatus.ERROR -> R.drawable.ic_do_not_disturb_24px
        }
    }

    private fun showAiTraceDialog(record: VoiceCommandRecord) {
        val message = buildString {
            append("[")
            append(getString(R.string.ai_record_detail_prompt_section))
            appendLine("]")
            appendLine(record.prompt?.takeIf { it.isNotBlank() }
                ?: getString(R.string.ai_record_detail_empty_prompt))
            appendLine()
            append("[")
            append(getString(R.string.ai_record_detail_response_section))
            appendLine("]")
            appendLine(record.rawResponse?.takeIf { it.isNotBlank() }
                ?: getString(R.string.ai_record_detail_empty_response))
            if (!record.diagnostic.isNullOrBlank()) {
                appendLine()
                append("[")
                append(getString(R.string.ai_record_detail_diagnostic_section))
                appendLine("]")
                appendLine(record.diagnostic)
            }
        }.trimEnd()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_record_detail_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.ai_record_detail_close, null)
            .setNeutralButton(R.string.ai_record_detail_copy_all) { _, _ ->
                copyToClipboard(
                    label = getString(R.string.ai_record_detail_dialog_title),
                    text = buildAiTraceCopyText(record)
                )
            }
            .setNegativeButton(R.string.ai_record_detail_copy_response) { _, _ ->
                val payload = record.rawResponse?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.ai_record_detail_empty_response)
                copyToClipboard(
                    label = getString(R.string.ai_record_detail_response_section),
                    text = payload
                )
            }
            .show()
    }

    /**
     * 把记录的全部上下文（含标题、时间戳、prompt、response、诊断、记录简述）拼成一段方便
     * 用户直接发回反馈给开发者排查的纯文本。**带 markdown 三反引号块**，让粘贴到聊天里可读。
     */
    private fun buildAiTraceCopyText(record: VoiceCommandRecord): String {
        return buildString {
            appendLine("== AutoTask AI 调用详情 ==")
            appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(record.timestamp))}")
            appendLine("标题: ${record.title}")
            record.detail?.takeIf { it.isNotBlank() }?.let {
                appendLine("简述: $it")
            }
            appendLine()
            appendLine("--- prompt ---")
            appendLine(record.prompt?.takeIf { it.isNotBlank() } ?: "(无)")
            appendLine()
            appendLine("--- raw response ---")
            appendLine(record.rawResponse?.takeIf { it.isNotBlank() } ?: "(无)")
            if (!record.diagnostic.isNullOrBlank()) {
                appendLine()
                appendLine("--- diagnostic ---")
                appendLine(record.diagnostic)
            }
        }.trimEnd()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast(R.string.ai_record_detail_copied)
    }

    private inner class VoiceCommandRecordAdapter :
        RecyclerView.Adapter<VoiceCommandRecordAdapter.ViewHolder>() {

        private var records: List<VoiceCommandRecord> = emptyList()

        fun submitList(newRecords: List<VoiceCommandRecord>) {
            val oldRecords = records
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRecords.size

                override fun getNewListSize(): Int = newRecords.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldRecords[oldItemPosition].timestamp == newRecords[newItemPosition].timestamp
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldRecords[oldItemPosition] == newRecords[newItemPosition]
                }
            })
            records = newRecords
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemVoiceCommandRecordBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = records.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(records[position])
        }

        private inner class ViewHolder(
            private val binding: ItemVoiceCommandRecordBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(record: VoiceCommandRecord) {
                binding.tvTitle.text = record.title
                binding.tvDetail.isVisible = !record.detail.isNullOrBlank()
                binding.tvDetail.text = record.detail
                binding.tvTime.text = record.timestamp.formatTime()
                binding.tvResult.setText(record.result.labelRes())
                binding.ivResult.setImageResource(record.result.iconRes())
                if (record.hasInspectableAiTrace) {
                    binding.root.setOnClickListener { showAiTraceDialog(record) }
                    binding.root.isClickable = true
                    binding.root.isFocusable = true
                } else {
                    binding.root.setOnClickListener(null)
                    binding.root.isClickable = false
                    binding.root.isFocusable = false
                }
            }

            private fun VoiceCommandRecordResult.labelRes(): Int {
                return when (this) {
                    VoiceCommandRecordResult.INFO -> R.string.voice_record_info
                    VoiceCommandRecordResult.SUCCESS -> R.string.voice_record_success
                    VoiceCommandRecordResult.FAILURE -> R.string.voice_record_failure
                }
            }

            private fun VoiceCommandRecordResult.iconRes(): Int {
                return when (this) {
                    VoiceCommandRecordResult.INFO -> R.drawable.ic_history_24px
                    VoiceCommandRecordResult.SUCCESS -> R.drawable.ic_check_circle_24px
                    VoiceCommandRecordResult.FAILURE -> R.drawable.ic_do_not_disturb_24px
                }
            }
        }
    }
}

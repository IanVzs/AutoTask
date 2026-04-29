/*
 * Copyright (c) 2026 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import top.xjunz.tasker.R
import top.xjunz.tasker.databinding.FragmentVoiceCommandBinding
import top.xjunz.tasker.databinding.ItemVoiceCommandRecordBinding
import top.xjunz.tasker.ktx.observe
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.ui.base.BaseFragment
import top.xjunz.tasker.ui.main.MainViewModel.Companion.peekMainViewModel
import top.xjunz.tasker.ui.main.ScrollTarget
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import top.xjunz.tasker.util.formatTime
import top.xjunz.tasker.voice.VoiceCommandRecord
import top.xjunz.tasker.voice.VoiceCommandRecordResult
import top.xjunz.tasker.voice.VoiceCommandService
import top.xjunz.tasker.voice.VoiceCommandStatus
import top.xjunz.tasker.voice.VoiceCommandUiState

class VoiceCommandFragment : BaseFragment<FragmentVoiceCommandBinding>(), ScrollTarget {

    private val recordAdapter = VoiceCommandRecordAdapter()

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
        val mainViewModel = peekMainViewModel()
        observe(mainViewModel.appbarHeight) {
            binding.scrollContent.updatePadding(top = it)
        }
        observe(mainViewModel.paddingBottom) {
            binding.scrollContent.updatePadding(bottom = it)
        }
        observe(VoiceCommandService.uiState) {
            renderState(it)
        }
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
        return if (!isRunning) R.drawable.ic_mic_24px else when (this) {
            VoiceCommandStatus.IDLE -> R.drawable.ic_mic_24px
            VoiceCommandStatus.LISTENING -> R.drawable.ic_mic_24px
            VoiceCommandStatus.RECOGNIZING -> R.drawable.ic_baseline_auto_awesome_24
            VoiceCommandStatus.EXECUTING -> R.drawable.ic_baseline_send_24
            VoiceCommandStatus.ERROR -> R.drawable.ic_do_not_disturb_24px
        }
    }

    private class VoiceCommandRecordAdapter :
        RecyclerView.Adapter<VoiceCommandRecordAdapter.ViewHolder>() {

        private var records: List<VoiceCommandRecord> = emptyList()

        fun submitList(newRecords: List<VoiceCommandRecord>) {
            records = newRecords
            notifyDataSetChanged()
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

        private class ViewHolder(
            private val binding: ItemVoiceCommandRecordBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(record: VoiceCommandRecord) {
                binding.tvTitle.text = record.title
                binding.tvDetail.isVisible = !record.detail.isNullOrBlank()
                binding.tvDetail.text = record.detail
                binding.tvTime.text = record.timestamp.formatTime()
                binding.tvResult.setText(record.result.labelRes())
                binding.ivResult.setImageResource(record.result.iconRes())
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

/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ui.voice.experience

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook
import top.xjunz.tasker.ai.agent.experience.ExperienceIndexEntry
import top.xjunz.tasker.databinding.DialogAiExperienceBookBinding
import top.xjunz.tasker.databinding.ItemAiExperienceEntryBinding
import top.xjunz.tasker.ktx.applySystemInsets
import top.xjunz.tasker.ktx.show
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.ui.base.BaseBottomSheetDialog
import top.xjunz.tasker.ui.base.inlineAdapter
import top.xjunz.tasker.ui.task.editor.FlowEditorDialog
import top.xjunz.tasker.ui.task.showcase.TaskShowcaseViewModel
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 经验本浏览/操作主入口（BottomSheetDialog）。
 *
 * - 列出所有历史经验，按时间倒序
 * - 每条卡片提供 "转草稿" + "查看完整记录" 两个动作
 * - 顶部 "清空" 按钮一键清空整个经验本
 * - 数据从 [AiAgentExperienceBook.queryAll] 来，转草稿走 [AiAgentExperienceBook.convertToDraft]
 *   → [FlowEditorDialog].initBase(task, false) → 用户确认后通过 [TaskShowcaseViewModel.requestAddNewTasks]
 *   走主流程 import
 */
class AiExperienceBookDialog : BaseBottomSheetDialog<DialogAiExperienceBookBinding>() {

    private val taskShowcaseViewModel by activityViewModels<TaskShowcaseViewModel>()

    /**
     * 当前展示的索引条目快照。**注意**：这是个不可变 List 的引用，每次 [refresh] 都会
     * 把 `entries` 指到新对象，因此 adapter 必须在 refresh 时**重建**而不是复用——
     * 之前用 `by lazy` 缓存 adapter 时 inlineAdapter 内部 closure 把 emptyList 永久
     * capture，导致第一次打开 dialog 就一直显示空（即使 entries 已经被 queryAll 填好）。
     */
    private var entries: List<ExperienceIndexEntry> = emptyList()

    private val readableTime by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    private fun buildAdapter(): RecyclerView.Adapter<*> {
        return inlineAdapter(entries, ItemAiExperienceEntryBinding::class.java, {
            binding.btnView.setNoDoubleClickListener {
                val entry = entries.getOrNull(adapterPosition) ?: return@setNoDoubleClickListener
                showDetail(entry)
            }
            binding.btnToDraft.setNoDoubleClickListener {
                val entry = entries.getOrNull(adapterPosition) ?: return@setNoDoubleClickListener
                handleConvertToDraft(entry)
            }
            binding.root.setOnLongClickListener {
                val entry = entries.getOrNull(adapterPosition) ?: return@setOnLongClickListener true
                confirmDelete(entry)
                true
            }
        }) { binding, _, entry ->
            binding.tvGoal.text = entry.userGoal.ifBlank { entry.outcomeLabel }
            val pkgPart = entry.targetAppLabel
                ?: entry.targetAppPackage
                ?: getString(R.string.ai_experience_book_no_app)
            val durationText = formatDuration(entry.durationMs)
            binding.tvMeta.text = getString(
                R.string.format_ai_experience_book_meta,
                "$pkgPart · ${readableTime.format(Date(entry.finishedAtMillis))}",
                entry.stepCount,
                durationText
            )
            binding.tvOutcomeLabel.text = entry.outcomeLabel
            val isCompleted = entry.outcome == "Completed"
            val attr = if (isCompleted) {
                com.google.android.material.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorError
            }
            binding.tvOutcomeLabel.setTextColor(
                MaterialColors.getColor(binding.tvOutcomeLabel, attr)
            )
            binding.ivOutcome.setImageResource(
                if (isCompleted) R.drawable.ic_check_circle_24px else R.drawable.ic_cancel_24px
            )
            binding.btnToDraft.isEnabled = entry.convertibleToTask
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvEntries.applySystemInsets { v, insets ->
            v.updatePadding(bottom = insets.bottom)
        }
        binding.btnClear.setNoDoubleClickListener {
            confirmClearAll()
        }
        refresh()
    }

    private fun refresh() {
        val ctx = context ?: return
        entries = AiAgentExperienceBook.queryAll(ctx)
        binding.rvEntries.adapter = buildAdapter()
        binding.tvEmpty.isVisible = entries.isEmpty()
        binding.rvEntries.isVisible = entries.isNotEmpty()
        val usage = AiAgentExperienceBook.usageBytes(ctx)
        binding.tvUsage.text = if (entries.isEmpty()) {
            getString(R.string.ai_experience_book_card_usage_empty)
        } else {
            getString(
                R.string.format_ai_experience_book_card_usage,
                entries.size,
                formatBytes(usage)
            )
        }
        binding.btnClear.isEnabled = entries.isNotEmpty()
    }

    private fun showDetail(entry: ExperienceIndexEntry) {
        AiExperienceDetailDialog.newInstance(entry.filename)
            .show(parentFragmentManager)
    }

    private fun handleConvertToDraft(entry: ExperienceIndexEntry) {
        if (!entry.convertibleToTask) {
            toast(R.string.ai_experience_book_to_draft_unsupported)
            return
        }
        val ctx = context ?: return
        val task = AiAgentExperienceBook.convertToDraft(ctx, entry.filename)
        if (task == null) {
            toast(R.string.ai_experience_book_to_draft_empty)
            return
        }
        FlowEditorDialog().initBase(task, false).doOnTaskEdited {
            taskShowcaseViewModel.requestAddNewTasks.value = listOf(task)
            toast(R.string.ai_experience_book_to_draft_success)
        }.show(parentFragmentManager)
    }

    private fun confirmDelete(entry: ExperienceIndexEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.ai_experience_book_delete_confirm)
            .setPositiveButton(R.string.ai_experience_book_delete) { _, _ ->
                AiAgentExperienceBook.delete(requireContext(), entry.filename)
                refresh()
            }
            .setNegativeButton(R.string.ai_experience_book_close, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.ai_experience_book_clear_confirm)
            .setPositiveButton(R.string.ai_experience_book_clear) { _, _ ->
                AiAgentExperienceBook.clearAll(requireContext())
                refresh()
            }
            .setNegativeButton(R.string.ai_experience_book_close, null)
            .show()
    }

    private fun formatDuration(ms: Long): String = when {
        ms <= 0 -> "—"
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}秒"
        else -> "${ms / 60_000}分${(ms / 1000) % 60}秒"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.2fMB".format(bytes / 1024.0 / 1024.0)
    }
}

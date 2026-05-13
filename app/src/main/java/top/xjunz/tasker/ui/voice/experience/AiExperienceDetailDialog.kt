/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ui.voice.experience

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import com.google.android.material.color.MaterialColors
import top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook
import top.xjunz.tasker.ai.agent.experience.ExperienceFile
import top.xjunz.tasker.databinding.DialogAiExperienceDetailBinding
import top.xjunz.tasker.ui.base.BaseDialogFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单条经验详情对话框：把整段 markdown + JSON 嵌块原文渲染出来给用户看。
 *
 * 全屏 + 可滚动 + monospace，让 step trace 对齐显示。从磁盘读原 txt 文件比格式化结构化字段
 * 更"可信" —— 用户能确认 AI prompt 实际看到的是什么。
 */
class AiExperienceDetailDialog : BaseDialogFragment<DialogAiExperienceDetailBinding>() {

    override val isFullScreen: Boolean = false

    private val readableTime by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    private val filename: String
        get() = arguments?.getString(ARG_FILENAME).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = context ?: return
        val exp = AiAgentExperienceBook.loadEntry(ctx, filename)
        if (exp == null) {
            binding.tvTitle.text = "—"
            binding.tvMeta.text = "经验文件不存在或已损坏"
            binding.tvOutcomeLabel.text = ""
            binding.tvBody.text = ""
            return
        }
        renderHeader(exp)
        // 原 txt 完整文本，让用户看到 AI 实际拿到的笔记
        val ctxFiles = ctx.filesDir
        val rawFile = File(File(ctxFiles, "ai_agent_experience"), filename)
        val content = runCatching { rawFile.readText(Charsets.UTF_8) }.getOrNull()
        binding.tvBody.text = content ?: "<无法读取原文>"
    }

    private fun renderHeader(exp: ExperienceFile) {
        binding.tvTitle.text = exp.userGoal.ifBlank { exp.outcomeLabel }
        val pkg = exp.targetAppLabel ?: exp.targetAppPackage ?: "未指定 App"
        val durationText = when {
            exp.durationMs <= 0 -> "—"
            exp.durationMs < 60_000 -> "${exp.durationMs / 1000}秒"
            else -> "${exp.durationMs / 60_000}分${(exp.durationMs / 1000) % 60}秒"
        }
        binding.tvMeta.text =
            "$pkg · ${readableTime.format(Date(exp.finishedAtMillis))} · ${exp.steps.size} 步 · $durationText"
        binding.tvOutcomeLabel.text = exp.outcomeLabel
        val attr = if (exp.outcome == "Completed") {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorError
        }
        binding.tvOutcomeLabel.setTextColor(
            MaterialColors.getColor(binding.tvOutcomeLabel, attr)
        )
    }

    companion object {
        private const val ARG_FILENAME = "filename"

        fun newInstance(filename: String): AiExperienceDetailDialog {
            return AiExperienceDetailDialog().apply {
                arguments = bundleOf(ARG_FILENAME to filename)
            }
        }
    }
}

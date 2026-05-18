/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.task.editor

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import top.xjunz.shared.ktx.casted
import top.xjunz.tasker.R
import top.xjunz.tasker.databinding.DialogSnapshotSelectorBinding
import top.xjunz.tasker.databinding.ItemTaskSnapshotBinding
import top.xjunz.tasker.ktx.*
import top.xjunz.tasker.ui.base.BaseBottomSheetDialog
import top.xjunz.tasker.ui.base.inlineAdapter
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import top.xjunz.tasker.util.formatMinSecMills
import top.xjunz.tasker.util.formatTime

/**
 * @author xjunz 2023/01/26
 */

@SuppressLint("SetTextI18n")
class TaskSnapshotSelectorDialog : BaseBottomSheetDialog<DialogSnapshotSelectorBinding>() {

    private val gvm by lazy {
        requireParentFragment().casted<FlowEditorDialog>().gvm
    }

    /**
     * **不能用 `by lazy` 缓存 adapter**：[inlineAdapter] 内部 closure 会把当时传入的
     * `gvm.allSnapshots.require()` 数组引用永久 capture，后续 [gvm.allSnapshots]
     * 通过 `postValue(newArray)` 替换成新数组（详见 [FlowEditorDialog.setTrackMode]
     * 以及 SnapshotObserver 写回时机）后，adapter 内部 `data.size` / `data[i]` 仍
     * 指向旧数组，UI 看不到更新。
     *
     * 改为每次 observe LiveData 触发都重建 adapter 实例，配合 RecyclerView 自身的
     * 复用，开销可忽略。
     */
    private fun buildAdapter() = inlineAdapter(
        gvm.allSnapshots.require(), ItemTaskSnapshotBinding::class.java, {
            binding.root.setNoDoubleClickListener {
                gvm.currentSnapshotIndex.value = adapterPosition
                dismiss()
            }
        }) { binding, index, snapshot ->
        binding.root.isSelected = gvm.currentSnapshotIndex eq index
        binding.tvInfo.text = if (snapshot.isSuccessful && snapshot.duration != 0) {
            R.string.format_task_snapshot_info_2.formatAsHtml(
                snapshot.startTimestamp.formatTime(), formatMinSecMills(snapshot.duration)
            )
        } else {
            snapshot.startTimestamp.formatTime()
        }

        if (snapshot.isRunning) {
            binding.ivResult.contentDescription = R.string.running.str
            binding.ivResult.setImageResource(R.drawable.ic_help_24px)
        } else if (snapshot.isSuccessful) {
            binding.ivResult.contentDescription = R.string.succeeded.str
            binding.ivResult.setImageResource(R.drawable.ic_check_circle_24px)
        } else {
            binding.ivResult.contentDescription = R.string.failed.str
            binding.ivResult.setImageResource(R.drawable.ic_cancel_24px)
        }
        binding.tvNumber.text = (index + 1).toString()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observe(gvm.allSnapshots) {
            binding.rvSnapshot.adapter = buildAdapter()
        }
        observeNotNull(gvm.currentSnapshotIndex) {
            binding.rvSnapshot.scrollPositionToCenterVertically(it, true)
        }
    }
}
/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.base

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import top.xjunz.shared.ktx.casted

/**
 * @author xjunz 2022/04/23
 */
class GenericViewHolder<T : ViewBinding>(
    val binding: T, initializer: (GenericViewHolder<T>) -> Unit
) : RecyclerView.ViewHolder(binding.root) {
    init {
        initializer(this)
    }
}

/**
 * **重要：[data] 引用陷阱**。
 *
 * 本函数返回的 adapter 内部 `getItemCount() = data.size` / `data[i]` 都是按
 * **传入瞬间的 [data] 引用** capture 的（Kotlin closure 捕获的是函数参数的值）。
 *
 * 如果调用方写法是：
 * ```kotlin
 * private var entries: List<X> = emptyList()                 // ← var
 * private val adapter by lazy { inlineAdapter(entries, ...) } // ← lazy 触发时只 capture 一次
 * fun refresh() { entries = newList; adapter.notifyDataSetChanged() }  // ❌ adapter 内部还是老 list
 * ```
 * 那么 `entries = newList` **永远不会**被 adapter 看到，UI 看上去不刷新。
 *
 * 安全的两种写法：
 *
 * 1. **list 引用稳定**（最常见）—— `data` 用 `mutableListOf()` 字段 / lateinit val /
 *    ViewModel 里的 `val list = mutableListOf<X>()`，外部只通过 `add` / `removeAt` /
 *    `clear` + `addAll` 修改内容，引用始终不变。配 `notifyXxxChanged` 即可：
 *    ```kotlin
 *    private val items = mutableListOf<X>()
 *    private val adapter by lazy { inlineAdapter(items, ...) }
 *    fun refresh() { items.clear(); items.addAll(newItems); adapter.notifyDataSetChanged() }
 *    ```
 *
 * 2. **list 引用会变**（LiveData postValue 替换、外部 var 重新赋值）—— 别 `by lazy`，
 *    改成 `private fun buildAdapter() = inlineAdapter(currentList, ...)`，每次 list
 *    引用变化时重建 adapter 实例：
 *    ```kotlin
 *    observe(viewModel.taskList) { binding.rv.adapter = buildAdapter() }
 *    ```
 *
 * 历史踩坑：`AiExperienceBookDialog` / `TaskSnapshotSelectorDialog` / `TaskListDialog`
 * 之前都中过这个陷阱（symptom：第一次打开 dialog 显示空 / 数据更新后 UI 不刷新），
 * 已统一改成方案 2。
 */
fun <Data, Binding : ViewDataBinding> inlineAdapter(
    data: Array<Data>, itemViewBinding: Class<Binding>,
    initializer: GenericViewHolder<Binding>.() -> Unit,
    onBindViewHolder: (binding: Binding, index: Int, data: Data) -> Unit
): RecyclerView.Adapter<*> {
    return inlineAdapter(data.toList(), itemViewBinding, initializer, onBindViewHolder)
}

/**
 * 见 [inlineAdapter] 数组重载头注释里的 `data` 引用陷阱说明。
 */
fun <Data, Binding : ViewDataBinding> inlineAdapter(
    data: List<Data>, itemViewBinding: Class<Binding>,
    initializer: GenericViewHolder<Binding>.() -> Unit,
    onBindViewHolder: (binding: Binding, index: Int, data: Data) -> Unit
): RecyclerView.Adapter<*> {

    return object : RecyclerView.Adapter<GenericViewHolder<Binding>>() {

        private lateinit var layoutInflater: LayoutInflater

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            layoutInflater = LayoutInflater.from(recyclerView.context)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): GenericViewHolder<Binding> {
            val binding: Binding = itemViewBinding.getDeclaredMethod(
                "inflate", LayoutInflater::class.java, ViewGroup::class.java, Boolean::class.java
            ).invoke(null, layoutInflater, parent, false)!!.casted()
            return GenericViewHolder(binding, initializer)
        }

        override fun onBindViewHolder(holder: GenericViewHolder<Binding>, position: Int) {
            onBindViewHolder(holder.binding, position, data[position])
        }

        override fun getItemCount() = data.size

    }
}
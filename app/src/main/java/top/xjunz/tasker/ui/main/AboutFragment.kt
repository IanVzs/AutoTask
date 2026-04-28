/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.main

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.xjunz.shared.utils.illegalArgument
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.R
import top.xjunz.tasker.app
import top.xjunz.tasker.autostart.AutoStartUtil
import top.xjunz.tasker.databinding.FragmentAboutBinding
import top.xjunz.tasker.databinding.ItemMainOptionBinding
import top.xjunz.tasker.ktx.compress
import top.xjunz.tasker.ktx.format
import top.xjunz.tasker.ktx.observe
import top.xjunz.tasker.ktx.observeError
import top.xjunz.tasker.ktx.show
import top.xjunz.tasker.ktx.str
import top.xjunz.tasker.ktx.text
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.premium.PremiumMixin
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.floatingInspector
import top.xjunz.tasker.service.isFloatingInspectorShown
import top.xjunz.tasker.service.isPremium
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.storage.TaskStorage
import top.xjunz.tasker.task.storage.TaskStorage.X_TASK_FILE_ARCHIVE_SUFFIX
import top.xjunz.tasker.task.storage.TaskStorage.fileOnStorage
import top.xjunz.tasker.task.storage.TaskStorage.getFileName
import top.xjunz.tasker.ui.base.BaseFragment
import top.xjunz.tasker.ui.base.inlineAdapter
import top.xjunz.tasker.ui.main.MainViewModel.Companion.peekMainViewModel
import top.xjunz.tasker.ui.purchase.PurchaseDialog
import top.xjunz.tasker.ui.purchase.PurchaseDialog.Companion.showPurchaseDialog
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import top.xjunz.tasker.util.Feedbacks
import top.xjunz.tasker.util.formatCurrentTime
import top.xjunz.tasker.voice.AsrServiceType
import java.util.zip.ZipOutputStream

/**
 * @author xjunz 2023/03/01
 */
class AboutFragment : BaseFragment<FragmentAboutBinding>(), ScrollTarget,
    ActivityResultCallback<Uri?> {

    private class InnerViewModel : ViewModel() {

        val onSaveToStorageError = MutableLiveData<Throwable>()

        fun saveTasksArchiveToStorage(contentResolver: ContentResolver, uri: Uri) {
            viewModelScope.async {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        ZipOutputStream(it).use { zip ->
                            TaskStorage.getAllTasks().forEach { task ->
                                zip.compress(task.fileOnStorage, task.getFileName(false))
                            }
                        }
                    }
                }
                toast(R.string.saved_to_storage)
            }.invokeOnCompletion {
                if (it != null && it !is CancellationException) {
                    onSaveToStorageError.postValue(it)
                }
            }
        }
    }

    private val viewModel by viewModels<InnerViewModel>()

    private lateinit var saveToSAFLauncher: ActivityResultLauncher<String>

    private val adapter by lazy {
        inlineAdapter(MainOption.ALL_OPTIONS, ItemMainOptionBinding::class.java, {
            binding.root.setNoDoubleClickListener {
                onOptionClicked(it, MainOption.ALL_OPTIONS[adapterPosition])
            }
        }) { binding, _, option ->
            binding.tvTitle.text = option.title.text
            val desc = option.desc.invoke()
            binding.tvDesc.isVisible = desc != null
            if (desc != null) {
                binding.tvDesc.text = when (desc) {
                    is Int -> desc.text
                    is CharSequence -> desc
                    else -> desc.toString()
                }
            }
            binding.ivIcon.setImageResource(option.icon)
            binding.tvDesc2.isVisible = option.longDesc != -1
            if (option.longDesc != -1) {
                binding.tvDesc2.text = option.longDesc.text
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveToSAFLauncher =
            registerForActivityResult(object : ActivityResultContract<String, Uri?>() {
                override fun createIntent(context: Context, input: String): Intent {
                    return Intent.createChooser(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_TITLE, input),
                        R.string.select_export_path.str
                    )
                }

                override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
                    if (resultCode == Activity.RESULT_CANCELED) toast(R.string.cancelled)
                    return intent?.data
                }
            }, this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvOption.adapter = adapter
        val mvm = peekMainViewModel()
        observe(mvm.appbarHeight) {
            binding.rvOption.updatePadding(top = it)
        }
        observe(mvm.paddingBottom) {
            binding.rvOption.updatePadding(bottom = it)
        }
        observe(PremiumMixin.premiumStatusLiveData) {
            adapter.notifyItemChanged(
                MainOption.ALL_OPTIONS.indexOf(MainOption.PremiumStatus), true
            )
            adapter.notifyItemChanged(
                MainOption.ALL_OPTIONS.indexOf(MainOption.AutoStart), true
            )
        }
        observe(app.updateInfo) {
            adapter.notifyItemChanged(
                MainOption.ALL_OPTIONS.indexOf(MainOption.VersionInfo), true
            )
        }
        observeError(viewModel.onSaveToStorageError)
    }

    private fun onOptionClicked(view: View, option: MainOption) {
        fun updateOption() {
            adapter.notifyItemChanged(MainOption.ALL_OPTIONS.indexOf(option))
        }
        when (option) {
            MainOption.Feedback -> {
                val menu = PopupMenu(requireContext(), view, Gravity.END)
                menu.inflate(R.menu.feedbacks)
                menu.setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.item_feedback_email -> Feedbacks.feedbackByEmail(null)

                        R.id.item_feedback_group -> Feedbacks.addGroup()
                    }
                    return@setOnMenuItemClickListener true
                }
                menu.show()
            }

            MainOption.About -> {
                /* no-op */
            }

            MainOption.NightMode -> {
                val popupMenu = PopupMenu(requireContext(), view, Gravity.END)
                popupMenu.inflate(R.menu.night_modes)
                popupMenu.setOnMenuItemClickListener {
                    val mode = when (it.itemId) {
                        R.id.item_turn_on -> {
                            AppCompatDelegate.MODE_NIGHT_YES
                        }

                        R.id.item_turn_off -> {
                            AppCompatDelegate.MODE_NIGHT_NO
                        }

                        R.id.item_follow_system -> {
                            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }

                        else -> illegalArgument()
                    }
                    if (Preferences.nightMode != mode) {
                        AppCompatDelegate.setDefaultNightMode(mode)
                        if (isFloatingInspectorShown) {
                            floatingInspector.viewModel.onConfigurationChanged()
                        }
                        Preferences.nightMode = mode
                        updateOption()
                    }
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            }

            MainOption.PremiumStatus -> PurchaseDialog().show(childFragmentManager)
            MainOption.VersionInfo -> VersionInfoDialog().show(childFragmentManager)
            MainOption.AutoStart -> {
                if (!isPremium) {
                    showPurchaseDialog(R.string.tip_auto_start_need_premium)
                } else {
                    AutoStartUtil.toggleAutoStart(!AutoStartUtil.isAutoStartEnabled)
                    updateOption()
                }
            }

            MainOption.WakeLock -> {
                Preferences.enableWakeLock = !Preferences.enableWakeLock
                if (serviceController.isServiceRunning) {
                    if (Preferences.enableWakeLock) {
                        currentService.acquireWakeLock()
                    } else {
                        currentService.releaseWakeLock()
                    }
                }
                updateOption()
            }

            MainOption.VoiceRecognitionConfig -> showVoiceRecognitionConfigDialog { updateOption() }

            MainOption.ExportTasks -> {
                toast(R.string.select_export_path)
                saveToSAFLauncher.launch(
                    R.string.format_task_archive_name.format(
                        formatCurrentTime(), X_TASK_FILE_ARCHIVE_SUFFIX
                    )
                )
            }
        }
    }

    private fun showVoiceRecognitionConfigDialog(onSaved: () -> Unit) {
        val context = requireContext()
        val marginTop = 12.dp
        var selectedService = Preferences.speechRecognitionService

        fun newInputLayout(title: CharSequence, text: CharSequence?, maxLines: Int): Pair<TextInputLayout, TextInputEditText> {
            val input = TextInputEditText(context).apply {
                setText(text)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                this.maxLines = maxLines
            }
            val layout = TextInputLayout(context).apply {
                hint = title
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                addView(input)
            }
            return layout to input
        }

        val serviceNames = AsrServiceType.all.map { AsrServiceType.titleOf(it).text }
        val serviceInput = MaterialAutoCompleteTextView(context).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    serviceNames
                )
            )
            setText(serviceNames[AsrServiceType.indexOf(selectedService)], false)
        }
        val serviceLayout = TextInputLayout(context).apply {
            hint = R.string.asr_service.text
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(serviceInput)
        }

        val (appKeyLayout, appKeyInput) = newInputLayout(
            R.string.voice_recognition_app_key.text,
            Preferences.speechRecognitionAppKey.orEmpty(),
            2
        )
        val (accessKeyIdLayout, accessKeyIdInput) = newInputLayout(
            R.string.voice_recognition_access_key_id.text,
            Preferences.speechRecognitionAccessKeyId.orEmpty(),
            2
        )
        val (accessKeySecretLayout, accessKeySecretInput) = newInputLayout(
            R.string.voice_recognition_access_key_secret.text,
            Preferences.speechRecognitionAccessKeySecret.orEmpty(),
            3
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(serviceLayout)
            addView(appKeyLayout, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(accessKeyIdLayout, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(accessKeySecretLayout, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        listOf(appKeyLayout, accessKeyIdLayout, accessKeySecretLayout).forEach {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = marginTop
        }

        fun updateAlibabaFields() {
            val showAlibabaFields = selectedService != AsrServiceType.SYSTEM
            appKeyLayout.isVisible = showAlibabaFields
            accessKeyIdLayout.isVisible = showAlibabaFields
            accessKeySecretLayout.isVisible = showAlibabaFields
        }

        serviceInput.setOnItemClickListener { _, _, position, _ ->
            selectedService = AsrServiceType.all[position]
            updateAlibabaFields()
        }
        updateAlibabaFields()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.voice_recognition_config)
            .setMessage(R.string.voice_recognition_config_caption)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!saveVoiceRecognitionConfig(
                        selectedService,
                        appKeyInput,
                        appKeyLayout,
                        accessKeyIdInput,
                        accessKeyIdLayout,
                        accessKeySecretInput,
                        accessKeySecretLayout
                    )
                ) {
                    return@setOnClickListener
                }
                toast(R.string.voice_recognition_config_saved)
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveVoiceRecognitionConfig(
        selectedService: Int,
        appKeyInput: TextInputEditText,
        appKeyLayout: TextInputLayout,
        accessKeyIdInput: TextInputEditText,
        accessKeyIdLayout: TextInputLayout,
        accessKeySecretInput: TextInputEditText,
        accessKeySecretLayout: TextInputLayout
    ): Boolean {
        appKeyLayout.error = null
        accessKeyIdLayout.error = null
        accessKeySecretLayout.error = null
        val appKey = appKeyInput.text?.toString()?.trim().orEmpty()
        val accessKeyId = accessKeyIdInput.text?.toString()?.trim().orEmpty()
        val accessKeySecret = accessKeySecretInput.text?.toString()?.trim().orEmpty()
        if (selectedService == AsrServiceType.ALIBABA) {
            var valid = true
            if (appKey.isEmpty()) {
                appKeyLayout.error = R.string.error_empty_input.text
                valid = false
            }
            if (accessKeyId.isEmpty()) {
                accessKeyIdLayout.error = R.string.error_empty_input.text
                valid = false
            }
            if (accessKeySecret.isEmpty()) {
                accessKeySecretLayout.error = R.string.error_empty_input.text
                valid = false
            }
            if (!valid) return false
        }
        Preferences.speechRecognitionService = selectedService
        Preferences.speechRecognitionAppKey = appKey.takeIf { it.isNotEmpty() }
        Preferences.speechRecognitionAccessKeyId = accessKeyId.takeIf { it.isNotEmpty() }
        Preferences.speechRecognitionAccessKeySecret = accessKeySecret.takeIf { it.isNotEmpty() }
        Preferences.speechRecognitionToken = null
        Preferences.speechRecognitionTokenExpireTime = 0L
        return true
    }

    private val Int.dp: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun getScrollTarget(): RecyclerView? {
        return if (isAdded) binding.rvOption else null
    }

    override fun onActivityResult(result: Uri?) {
        if (result == null) return
        viewModel.saveTasksArchiveToStorage(requireActivity().contentResolver, result)
    }
}
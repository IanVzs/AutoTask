/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.draft

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.ai.agent.AiDraftStep
import top.xjunz.tasker.ai.agent.VoiceAiInterpretation
import top.xjunz.tasker.ai.capability.AiTaskCapabilityCatalog
import top.xjunz.tasker.bridge.PackageManagerBridge
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Do
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.ApplicationActionRegistry
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.TextActionRegistry

/**
 * 把 AI 输出的 [VoiceAiInterpretation.CreateTaskDraft] 转换成可以直接进入 `FlowEditor`
 * 的真实 [XTask]：包含 RootFlow + preload + Do 容器，以及若干 capability 对应的 [Applet]。
 *
 * 第一阶段只识别 [AiTaskCapabilityCatalog] 中的 3 类能力，未识别的步骤会落入
 * [AiTaskDraftConversionResult.unsupportedSteps]，由 UI 提示用户在编辑器里手动补齐。
 */
object AiTaskDraftConverter {

    fun convert(draft: VoiceAiInterpretation.CreateTaskDraft): AiTaskDraftConversionResult {
        AppletOptionFactory.preloadIfNeeded()
        val factory = AppletOptionFactory
        val root = factory.flowRegistry.rootFlow.yield() as RootFlow
        root.add(factory.flowRegistry.preloadFlow.yield())
        val doFlow = factory.flowRegistry.doFlow.yield() as Do
        root.add(doFlow)

        val converted = mutableListOf<AiConvertedStep>()
        draft.steps.forEach { step ->
            when (val result = convertStep(step, doFlow, factory)) {
                is StepConversion.Converted -> converted += AiConvertedStep(
                    description = step.description,
                    status = AiConvertedStepStatus.Converted,
                    capabilityId = result.capabilityId,
                    detail = result.detail
                )

                is StepConversion.Unsupported -> converted += AiConvertedStep(
                    description = step.description,
                    status = AiConvertedStepStatus.Unsupported,
                    capabilityId = result.capabilityId,
                    detail = result.reason
                )
            }
        }

        val task = XTask().apply {
            metadata = XTask.Metadata(
                title = draft.title,
                taskType = XTask.TYPE_ONESHOT,
                description = buildDescription(draft, converted)
            ).apply { version = BuildConfig.VERSION_CODE }
            flow = root
        }

        return AiTaskDraftConversionResult(
            task = task,
            convertedSteps = converted
        )
    }

    private fun convertStep(
        step: AiDraftStep,
        doFlow: Flow,
        factory: AppletOptionFactory
    ): StepConversion {
        return when (step) {
            is AiDraftStep.FreeText -> StepConversion.Unsupported(
                capabilityId = null,
                reason = "AI 仅给出文字描述，没有命中能力清单。"
            )

            is AiDraftStep.Action -> when (step.capabilityId) {
                AiTaskCapabilityCatalog.CAPABILITY_LAUNCH_APP -> convertLaunchApp(step, doFlow, factory)
                AiTaskCapabilityCatalog.CAPABILITY_WAIT_SECONDS -> convertWaitSeconds(step, doFlow, factory)
                AiTaskCapabilityCatalog.CAPABILITY_TOAST -> convertToast(step, doFlow, factory)
                else -> StepConversion.Unsupported(
                    capabilityId = step.capabilityId,
                    reason = "未支持的能力：${step.capabilityId}"
                )
            }
        }
    }

    private fun convertLaunchApp(
        step: AiDraftStep.Action,
        doFlow: Flow,
        factory: AppletOptionFactory
    ): StepConversion {
        val rawPackage = step.params["package"]?.contentOrNull?.trim()
        val appName = step.params["app_name"]?.contentOrNull?.trim()
        val resolvedPackage = resolvePackage(rawPackage, appName)
            ?: return StepConversion.Unsupported(
                capabilityId = step.capabilityId,
                reason = "找不到对应的 App，请在编辑器里手动选择。"
            )
        val registry = factory.requireRegistryById(BootstrapOptionRegistry.ID_APP_ACTION_REGISTRY)
                as ApplicationActionRegistry
        val applet = registry.launchApp.yieldWithFirstValue(resolvedPackage)
        doFlow.add(applet)
        return StepConversion.Converted(
            capabilityId = step.capabilityId,
            detail = "打开 $resolvedPackage"
        )
    }

    private fun convertWaitSeconds(
        step: AiDraftStep.Action,
        doFlow: Flow,
        factory: AppletOptionFactory
    ): StepConversion {
        val seconds = step.params["seconds"]?.intOrNull
            ?: return StepConversion.Unsupported(
                capabilityId = step.capabilityId,
                reason = "AI 没给出有效秒数。"
            )
        if (seconds !in 1..MAX_WAIT_SECONDS) {
            return StepConversion.Unsupported(
                capabilityId = step.capabilityId,
                reason = "等待秒数 $seconds 不在 1..$MAX_WAIT_SECONDS 范围内。"
            )
        }
        val applet = factory.controlActionRegistry.suspension.yield(0 to seconds * 1000)
        doFlow.add(applet)
        return StepConversion.Converted(
            capabilityId = step.capabilityId,
            detail = "等待 ${seconds} 秒"
        )
    }

    private fun convertToast(
        step: AiDraftStep.Action,
        doFlow: Flow,
        factory: AppletOptionFactory
    ): StepConversion {
        val text = step.params["text"]?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) {
            return StepConversion.Unsupported(
                capabilityId = step.capabilityId,
                reason = "AI 没给出 Toast 文本。"
            )
        }
        val registry = factory.requireRegistryById(BootstrapOptionRegistry.ID_TEXT_ACTION_REGISTRY)
                as TextActionRegistry
        val applet = registry.makeToast.yield(0 to text)
        doFlow.add(applet)
        return StepConversion.Converted(
            capabilityId = step.capabilityId,
            detail = "弹出文本：$text"
        )
    }

    /**
     * 包名优先；若 AI 只给了 [appName]，再尝试用 PackageManager 模糊匹配 label。
     * 第一版保持简单：从已安装应用里挑 label 完全相同（忽略大小写）的第一个。
     */
    private fun resolvePackage(packageName: String?, appName: String?): String? {
        if (!packageName.isNullOrEmpty() &&
            PackageManagerBridge.getLaunchIntentFor(packageName) != null
        ) {
            return packageName
        }
        if (appName.isNullOrEmpty()) return packageName
        val target = appName.lowercase()
        return runCatching {
            PackageManagerBridge.loadAllPackages()
                .firstOrNull { info ->
                    val label = PackageManagerBridge.loadLabelOfPackage(info.packageName)
                        .toString()
                        .trim()
                    label.equals(appName, ignoreCase = true) ||
                            label.lowercase() == target
                }
                ?.packageName
        }.getOrNull()
            ?: packageName.takeIf { !it.isNullOrEmpty() }
    }

    private fun buildDescription(
        draft: VoiceAiInterpretation.CreateTaskDraft,
        converted: List<AiConvertedStep>
    ): String = buildString {
        if (draft.summary.isNotBlank()) {
            appendLine(draft.summary)
            appendLine()
        }
        converted.forEachIndexed { index, step ->
            append(index + 1)
            append(". ")
            append(step.description)
            when (step.status) {
                AiConvertedStepStatus.Converted -> step.detail?.let {
                    append("  ✓ ")
                    append(it)
                }

                AiConvertedStepStatus.Unsupported -> step.detail?.let {
                    append("  ⚠ ")
                    append(it)
                }
            }
            appendLine()
        }
    }.trimEnd()

    private const val MAX_WAIT_SECONDS = 600

    private sealed interface StepConversion {
        data class Converted(val capabilityId: String, val detail: String) : StepConversion
        data class Unsupported(val capabilityId: String?, val reason: String) : StepConversion
    }
}

data class AiTaskDraftConversionResult(
    val task: XTask,
    val convertedSteps: List<AiConvertedStep>
) {
    val convertedCount: Int get() = convertedSteps.count { it.status == AiConvertedStepStatus.Converted }
    val unsupportedCount: Int get() = convertedSteps.count { it.status == AiConvertedStepStatus.Unsupported }
    val unsupportedSteps: List<AiConvertedStep> get() = convertedSteps.filter { it.status == AiConvertedStepStatus.Unsupported }
}

data class AiConvertedStep(
    val description: String,
    val status: AiConvertedStepStatus,
    val capabilityId: String?,
    val detail: String?
)

enum class AiConvertedStepStatus {
    Converted,
    Unsupported
}

/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.capability

/**
 * 描述 AI 在生成任务草稿时可以使用的某一种"能力"（动作）。
 *
 * 第一阶段只暴露最少的几种低风险动作。capability 定义同时供：
 * - [top.xjunz.tasker.ai.agent.VoiceAiInterpreter] 装配进 Prompt，让模型按结构化 schema 输出。
 * - [top.xjunz.tasker.ai.draft.AiTaskDraftConverter] 把模型输出的 step 转成真实 [top.xjunz.tasker.engine.applet.base.Applet]。
 */
data class AiTaskCapability(
    val id: String,
    val label: String,
    val description: String,
    val parameters: List<AiTaskCapabilityParam>
)

/**
 * 单个能力参数的定义。第一版只支持基础类型，后续可扩展枚举/联合类型。
 */
data class AiTaskCapabilityParam(
    val name: String,
    val label: String,
    val description: String,
    val type: AiTaskCapabilityParamType,
    val required: Boolean = true
)

enum class AiTaskCapabilityParamType {
    String,
    Int,
    /** 中文/英文 App 名，由本地用 PackageManager 查包名后再传给 [top.xjunz.tasker.task.applet.option.registry.ApplicationActionRegistry.launchApp]。 */
    AppName,
    PackageName
}

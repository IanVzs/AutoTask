/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.capability

/**
 * 第一阶段开放给 AI 草稿生成器的能力清单。
 *
 * 选用原则：
 * - 风险尽量低（无写入文件、无 Shell、无强停 App）。
 * - 参数简单（基础类型 + App 名/包名），方便模型输出可解析的结构。
 * - 在 [top.xjunz.tasker.task.applet.option.AppletOptionFactory] 已注册的现成 Action 上，避免新建 Applet 类型。
 */
object AiTaskCapabilityCatalog {

    const val CAPABILITY_LAUNCH_APP = "launch_app"
    const val CAPABILITY_WAIT_SECONDS = "wait_seconds"
    const val CAPABILITY_TOAST = "toast"

    val capabilities: List<AiTaskCapability> = listOf(
        AiTaskCapability(
            id = CAPABILITY_LAUNCH_APP,
            label = "打开 App",
            description = "通过包名启动手机上已安装的 App。",
            parameters = listOf(
                AiTaskCapabilityParam(
                    name = "package",
                    label = "App 包名",
                    description = "Android 应用包名，例如 com.tencent.mm；如果不确定可用 app_name 字段填中文名。",
                    type = AiTaskCapabilityParamType.PackageName,
                    required = false
                ),
                AiTaskCapabilityParam(
                    name = "app_name",
                    label = "App 名称",
                    description = "App 的显示名称（中文优先），用于无法给出包名时由本机查找。",
                    type = AiTaskCapabilityParamType.AppName,
                    required = false
                )
            )
        ),
        AiTaskCapability(
            id = CAPABILITY_WAIT_SECONDS,
            label = "等待几秒",
            description = "在执行流程里等待指定秒数（最大 600 秒），常用于上一步操作之后稍作停顿。",
            parameters = listOf(
                AiTaskCapabilityParam(
                    name = "seconds",
                    label = "秒数",
                    description = "等待的秒数，整数，1 到 600 之间。",
                    type = AiTaskCapabilityParamType.Int
                )
            )
        ),
        AiTaskCapability(
            id = CAPABILITY_TOAST,
            label = "弹出文本提示",
            description = "在屏幕上弹出一段文本提示（Toast）。",
            parameters = listOf(
                AiTaskCapabilityParam(
                    name = "text",
                    label = "提示文本",
                    description = "要显示的提示内容，可包含 emoji。",
                    type = AiTaskCapabilityParamType.String
                )
            )
        )
    )

    fun findById(id: String): AiTaskCapability? {
        return capabilities.firstOrNull { it.id == id }
    }
}

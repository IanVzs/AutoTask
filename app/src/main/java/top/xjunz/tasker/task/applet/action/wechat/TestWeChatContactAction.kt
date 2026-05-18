/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.applet.action.wechat

import kotlinx.coroutines.delay
import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.AiAgentLog
import top.xjunz.tasker.ai.agent.AiAgentSession
import top.xjunz.tasker.ai.agent.AiAgentStepRecord
import top.xjunz.tasker.ai.agent.AiAgentSessionOutcome
import top.xjunz.tasker.ai.agent.AiTaskScope
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.provider.AiProviderFactory
import top.xjunz.tasker.engine.applet.base.AppletResult
import top.xjunz.tasker.engine.applet.action.Action
import top.xjunz.tasker.engine.task.TaskRuntime

/**
 * 微信联系人可达性测试 Applet。
 *
 * 通过 AI Agent 动态导航微信，搜索指定联系人并验证是否可达。
 * 支持两种模式：
 * - check_only：仅检查联系人是否存在并进入聊天页
 * - check_and_send：检查后发送一条测试消息
 *
 * A11y / Shizuku 模式均可用。
 */
class TestWeChatContactAction : Action() {

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MAX_STEPS = 25
        private const val MAX_DURATION_SECONDS = 240
        private const val ACTION_CHECK_ONLY = "check_only"
        private const val ACTION_CHECK_AND_SEND = "check_and_send"
    }

    override suspend fun apply(runtime: TaskRuntime): AppletResult {
        // ① AI Provider 检查
        val provider = AiProviderFactory.createConfiguredProvider()
        if (provider == null) {
            return AppletResult(false, "AI 服务未配置或未启用。请在「关于 → AI 驱动」中配置 Provider 和模型后再试")
        }

        // ② 解析参数
        val contactName = getArgument<String>(0, runtime)?.toString()?.trim()
        if (contactName.isNullOrBlank()) {
            return AppletResult(false, "请指定微信昵称或微信号")
        }

        val actionType = getArgument<String>(1, runtime)?.toString() ?: ACTION_CHECK_ONLY
        val message = getArgument<String>(2, runtime)?.toString()?.trim()

        if (actionType == ACTION_CHECK_AND_SEND && message.isNullOrBlank()) {
            return AppletResult(false, "「检查并发送消息」模式需要填写消息内容")
        }

        // ③ 构造精确 Prompt
        val goal = buildGoal(contactName, actionType, message)

        AiAgentLog.i("wechat.test", "开始测试微信联系人可达性：contact=$contactName, action=$actionType")

        // ④ 创建 AiTaskScope
        val scope = AiTaskScope(
            sessionId = "wechat_test_${System.currentTimeMillis()}",
            userGoal = goal,
            targetApps = setOf(WECHAT_PACKAGE),
            capabilities = setOf(
                AiCapability.LaunchIntent,
                AiCapability.ClickUi,
                AiCapability.InputText,
                AiCapability.ReadNodeTree
            ),
            maxSteps = MAX_STEPS,
            maxDurationSeconds = MAX_DURATION_SECONDS
        )

        // ⑤ 创建并运行 AiAgentSession（overlay=null → 自动批准所有动作）
        val session = AiAgentSession(
            scope = scope,
            plan = null,
            overlay = null,
            callbacks = object : AiAgentSession.Callbacks {
                override fun onStep(record: AiAgentStepRecord) {
                    AiAgentLog.d("wechat.test.step", "Step #${record.index}: ${describeActionShort(record.action)} → ${if (record.result.ok) "OK" else "FAIL"}")
                }

                override fun onThinking(stepIndex: Int) {
                    AiAgentLog.d("wechat.test.thinking", "AI 正在规划第 ${stepIndex + 1} 步...")
                }

                override fun onComplete(outcome: AiAgentSessionOutcome) {
                    AiAgentLog.i("wechat.test.outcome", "Session 结束：${outcome::class.simpleName}")
                }
            },
            experiences = emptyList()
        )

        // ⑥ 执行 session
        val outcome = try {
            session.run()
        } catch (e: Exception) {
            AiAgentLog.e("wechat.test", "Session 执行异常：${e.message}", e)
            return AppletResult(false, "微信测试执行异常：${e.message}")
        }

        // ⑦ 映射 outcome → AppletResult
        return mapOutcomeToResult(outcome, contactName, actionType)
    }

    private fun buildGoal(contactName: String, actionType: String, message: String?): String {
        return buildString {
            appendLine("你是 AutoTask 的微信可达性测试 agent。请严格按以下步骤操作：")
            appendLine()
            appendLine("1. 打开微信（包名：com.tencent.mm），等待主界面加载完成")
            appendLine("2. 在微信主界面找到搜索入口（通常在顶部，搜索图标或搜索框），点击进入搜索页")
            appendLine("3. 在搜索框输入「$contactName」，等待搜索结果加载")
            appendLine("4. 在搜索结果中找到该联系人，点击进入其聊天页面")
            if (actionType == ACTION_CHECK_AND_SEND && !message.isNullOrBlank()) {
                appendLine("5. 在聊天页面的输入框输入「$message」，然后点击发送按钮")
                appendLine("6. 确认消息已发送（看到消息气泡出现在聊天窗口中）")
            }
            appendLine()
            appendLine("成功标准：")
            appendLine("- 当你看到该联系人的聊天窗口（底部有输入框和发送按钮），输出 done")
            if (actionType == ACTION_CHECK_AND_SEND && !message.isNullOrBlank()) {
                appendLine("- 并且确认消息已发送成功")
            }
            appendLine("- done 的 summary 中说明「已成功验证联系人 $contactName 可达」")
            appendLine()
            appendLine("失败标准：")
            appendLine("- 搜索后找不到该联系人 → give_up，reason 说明「未找到联系人 $contactName」")
            appendLine("- 微信无法启动或界面加载超时 → give_up，reason 说明具体原因")
            appendLine()
            appendLine("严格约束：")
            appendLine("- 只操作微信，不操作其他 App")
            appendLine("- 不读取任何聊天内容，仅执行导航和（可选）发送操作")
            appendLine("- 如果微信不在前台，先 global_home 回桌面再 launch_app")
        }
    }

    private fun mapOutcomeToResult(
        outcome: AiAgentSessionOutcome,
        contactName: String,
        actionType: String
    ): AppletResult {
        return when (outcome) {
            is AiAgentSessionOutcome.Completed -> {
                val summary = outcome.summary.take(200)
                AppletResult(true, "微信可达性检查通过：$summary")
            }
            is AiAgentSessionOutcome.GivenUp -> {
                AppletResult(false, "微信可达性检查失败：${outcome.reason.take(200)}")
            }
            is AiAgentSessionOutcome.LimitExceeded -> {
                AppletResult(false, "微信测试超时：${outcome.reason}")
            }
            is AiAgentSessionOutcome.OutOfScope -> {
                AppletResult(false, "微信测试偏离目标：AI 离开了微信（当前包名：${outcome.currentPackage}）")
            }
            is AiAgentSessionOutcome.PermissionDenied -> {
                AppletResult(false, "微信测试被拒：使用了未授权的能力 ${outcome.capability}")
            }
            is AiAgentSessionOutcome.AiError -> {
                AppletResult(false, "微信测试 AI 异常：${outcome.reason.take(200)}")
            }
            is AiAgentSessionOutcome.Cancelled -> {
                AppletResult(false, "微信测试被取消")
            }
            is AiAgentSessionOutcome.ServiceNotConnected -> {
                AppletResult(false, "微信测试失败：AutoTask 服务未启动，请先启动服务")
            }
        }
    }

    private fun describeActionShort(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch_app(${action.packageName})"
        is AiAgentAction.Click -> "click"
        is AiAgentAction.LongClick -> "long_click"
        is AiAgentAction.SetText -> "set_text"
        is AiAgentAction.Wait -> "wait(${action.seconds}s)"
        is AiAgentAction.Scroll -> "scroll(${action.direction})"
        is AiAgentAction.GlobalBack -> "global_back"
        is AiAgentAction.GlobalHome -> "global_home"
        is AiAgentAction.Done -> "done"
        is AiAgentAction.GiveUp -> "give_up"
        is AiAgentAction.Unknown -> "unknown"
    }
}

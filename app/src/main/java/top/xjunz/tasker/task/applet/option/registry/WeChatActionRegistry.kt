/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.task.applet.option.registry

import top.xjunz.tasker.R
import top.xjunz.tasker.task.applet.action.wechat.TestWeChatContactAction
import top.xjunz.tasker.task.applet.anno.AppletOrdinal

/**
 * 微信专用动作注册表。
 *
 * 当前提供：
 * - 微信联系人可达性测试（通过 AI Agent 动态导航，非硬编码 UI）
 */
class WeChatActionRegistry(id: Int) : AppletOptionRegistry(id) {

    @AppletOrdinal(0x0001)
    val testWeChatContact = appletOption(R.string.test_wechat_contact) {
        TestWeChatContactAction()
    }.withUnaryArgument<String>(R.string.wechat_contact_name)
        .withValueArgument<String>(R.string.wechat_action_type, R.array.wechat_action_types, R.array.wechat_action_type_values)
        .withUnaryArgument<String>(R.string.wechat_message_text)
        .hasCompositeTitle()
}

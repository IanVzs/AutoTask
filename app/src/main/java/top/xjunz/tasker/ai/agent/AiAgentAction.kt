/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.serialization.Serializable

/**
 * AI 在 agent loop 每一步的输出结构。
 *
 * 不使用 sealed class + polymorphic JSON：模型偶尔会在 type 字段里返回大小写或多余空格，
 * 用统一的 DTO + 本地 dispatch 比 kotlinx.serialization 的多态解析鲁棒得多。
 *
 * 字段语义：
 * - [type]：必填，区分动作种类，详见 [AiAgentActionType]。
 * - [packageName]：仅 `launch_app` 使用；其他动作忽略。
 * - [target]：操作对象的定位条件，`click / long_click / set_text / scroll` 必填。
 * - [text]：`set_text` 必填，要写入的文本；`scroll` 借用此字段表示方向（up/down/left/right）。
 * - [seconds]：`wait` 用，限制在 1-30 秒之间。
 * - [summary]：`done` 用，告诉用户和后续审计这一会话最终做了什么。
 * - [reason]：`give_up` 用，解释为何放弃。
 * - [thought]：可选的"简短思考"，每步保留一句模型自述，便于复盘和 prompt 续接。
 * - [planStatus]：AI 自评本步相对于原会话规划的"在轨度"，详见 [AiAgentPlanStatus]。
 *   有了它，agent loop 不再只靠"下一步该做什么"决策，还可以**让 AI 显式声明自己有没有走偏、
 *   是不是已经偏离了原 plan**，便于上层审计和触发回轨提示。
 */
@Serializable
data class AiAgentActionDto(
    val type: String,
    val packageName: String? = null,
    val target: AiUiTarget? = null,
    val text: String? = null,
    val seconds: Int? = null,
    val summary: String? = null,
    val reason: String? = null,
    val thought: String? = null,
    @kotlinx.serialization.SerialName("plan_status")
    val planStatus: String? = null
)

/**
 * **Structured ReAct 响应**（aidoc/18 §3.A）。
 *
 * 强制 AI 按 Observation → Reflection → Action 三段输出，让"读快照 + 反思"成为
 * **生成 action 的前置硬步骤**，而不是事后写在 thought 里的解释。
 *
 * 字段顺序在 prompt 里就明确写好——LLM 在自然推理时按字段顺序生成，先写的内容
 * 比后写的更"想清楚"。比堆 prompt 行为准则有效得多。
 *
 * 兼容退化：如果 AI 没输出新结构（直接输出老的 ActionDto），parser 会兜底解析成
 * `observation/lastActionReview/reflection = null` + 老 action，session 仍能跑。
 */
@Serializable
data class AiAgentReactResponseDto(
    /**
     * 必填。AI 看完当前 snapshot 后写下"我看到什么"——activity / 关键节点 / 上一步执行后 UI 变化等。
     * 强制 AI 在写 action 之前**先读快照**——这是治"AI 不读现场" 病火的核心。
     */
    val observation: String? = null,
    /**
     * 必填。对照上一步 action 实际结果与你当时的预期，回答"上一步真的做到我以为的事了吗"。
     * 强制 AI 反思——治"AI 死循环不反思"病火。
     */
    @kotlinx.serialization.SerialName("last_action_review")
    val lastActionReview: String? = null,
    /**
     * 必填。基于 observation + last_action_review 推理出"下一步该往哪个方向走"，2-4 句。
     * 跟 thought 的差别：thought 只是 action 的事后解释；reflection 是 action 的依据。
     */
    val reflection: String? = null,
    /** 真正要执行的动作。schema 跟旧 [AiAgentActionDto] 完全一样。 */
    val action: AiAgentActionDto? = null,
    /** 兼容退化：AI 把字段直接塞顶层（type/target 等）时这些字段非空，按老格式解析。 */
    val type: String? = null,
    val packageName: String? = null,
    val target: AiUiTarget? = null,
    val text: String? = null,
    val seconds: Int? = null,
    val summary: String? = null,
    val reason: String? = null,
    val thought: String? = null,
    @kotlinx.serialization.SerialName("plan_status")
    val planStatus: String? = null
)

object AiAgentActionType {
    const val LAUNCH_APP = "launch_app"
    const val CLICK = "click"
    const val LONG_CLICK = "long_click"
    const val SET_TEXT = "set_text"
    const val WAIT = "wait"
    const val SCROLL = "scroll"
    const val GLOBAL_BACK = "global_back"
    const val GLOBAL_HOME = "global_home"
    const val DONE = "done"
    const val GIVE_UP = "give_up"
}

/**
 * 强类型版本，业务代码只看这个；DTO 仅在解析模型输出时短暂存在。
 */
sealed class AiAgentAction {
    abstract val thought: String?
    /**
     * AI 自评的"本步相对原 plan 的在轨度"。在 [AiAgentPlanner.nextAction] 的 prompt 里被显式要求填写，
     * 但允许为 null（兼容旧 prompt / 模型偶尔忘记）。可通过 [AiAgentPlanStatus.parse] 解析为枚举。
     */
    abstract val planStatus: String?

    data class LaunchApp(
        val packageName: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class Click(
        val target: AiUiTarget,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class LongClick(
        val target: AiUiTarget,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class SetText(
        val target: AiUiTarget,
        val text: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class Wait(
        val seconds: Int,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    /**
     * `direction` 取值：`up` / `down` / `left` / `right`。`target` 可为空表示"在当前焦点的可滚动容器上滚"。
     */
    data class Scroll(
        val target: AiUiTarget?,
        val direction: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class GlobalBack(
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    data class GlobalHome(
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    /** AI 主动判定整个会话已经达成目标。 */
    data class Done(
        val summary: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    /** AI 主动判定当前情况无法继续，请求人工接管。 */
    data class GiveUp(
        val reason: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    /** 解析失败的兜底；executor 看到这种动作不会执行任何系统操作，仅写一条记录。 */
    data class Unknown(
        val raw: String,
        override val thought: String? = null,
        override val planStatus: String? = null
    ) : AiAgentAction()

    companion object {
        fun fromDto(dto: AiAgentActionDto): AiAgentAction {
            val rawType = dto.type.trim()
            val ps = dto.planStatus?.trim()?.takeIf { it.isNotBlank() }
            val th = dto.thought
            return when (rawType.lowercase()) {
                AiAgentActionType.LAUNCH_APP -> {
                    val pkg = dto.packageName?.trim()?.takeIf { it.isNotBlank() }
                    if (pkg == null) Unknown("launch_app 缺少 packageName", th, ps)
                    else LaunchApp(pkg, th, ps)
                }

                AiAgentActionType.CLICK -> {
                    val target = dto.target
                    if (target == null || target.isEmpty) Unknown("click 缺少有效 target", th, ps)
                    else Click(target, th, ps)
                }

                AiAgentActionType.LONG_CLICK -> {
                    val target = dto.target
                    if (target == null || target.isEmpty) Unknown("long_click 缺少有效 target", th, ps)
                    else LongClick(target, th, ps)
                }

                AiAgentActionType.SET_TEXT -> {
                    val target = dto.target
                    val text = dto.text
                    when {
                        target == null || target.isEmpty -> Unknown("set_text 缺少 target", th, ps)
                        text == null -> Unknown("set_text 缺少 text", th, ps)
                        else -> SetText(target, text, th, ps)
                    }
                }

                AiAgentActionType.WAIT -> Wait(
                    seconds = (dto.seconds ?: 2).coerceIn(1, 30),
                    thought = th,
                    planStatus = ps
                )

                AiAgentActionType.SCROLL -> Scroll(
                    target = dto.target?.takeIf { !it.isEmpty },
                    direction = (dto.text ?: "down").trim().lowercase().ifBlank { "down" },
                    thought = th,
                    planStatus = ps
                )

                AiAgentActionType.GLOBAL_BACK -> GlobalBack(th, ps)
                AiAgentActionType.GLOBAL_HOME -> GlobalHome(th, ps)

                AiAgentActionType.DONE -> Done(
                    summary = dto.summary?.trim()?.takeIf { it.isNotBlank() } ?: "已完成",
                    thought = th,
                    planStatus = ps
                )

                AiAgentActionType.GIVE_UP -> GiveUp(
                    reason = dto.reason?.trim()?.takeIf { it.isNotBlank() } ?: "无法继续",
                    thought = th,
                    planStatus = ps
                )

                else -> Unknown("未知的动作类型: $rawType", th, ps)
            }
        }
    }
}

/**
 * AI 自评的"本步相对原 plan 的在轨度"。
 * 设计目的不是阻断执行（执行边界由 [AiTaskScope] 负责），而是给上层提供"AI 是否还在轨道上"的
 * 显式信号，便于审计、告警、未来加"主动询问用户"等扩展。
 */
enum class AiAgentPlanStatus(val rawValues: Set<String>) {
    OnTrack(setOf("on_track", "ontrack", "on-track")),
    Adjusted(setOf("adjusted", "adjust", "adjustment")),
    OffTrack(setOf("off_track", "offtrack", "off-track")),
    Unknown(setOf("unknown", "")) ;

    companion object {
        /**
         * 把 AI 返回的字符串归一化成枚举。无法识别时返回 [Unknown]。
         */
        fun parse(raw: String?): AiAgentPlanStatus {
            if (raw.isNullOrBlank()) return Unknown
            val key = raw.trim().lowercase()
            return entries.firstOrNull { key in it.rawValues } ?: Unknown
        }
    }
}

/**
 * 单步执行结果。`ok=true` 表示动作真正在系统层面发生了；`matchedNodeSummary` 在涉及节点
 * 的动作上提供"实际作用到了哪个节点"，让 AI 在下一轮看到反馈、避免重复点错。
 */
data class AiAgentStepResult(
    val ok: Boolean,
    val message: String? = null,
    val matchedNodeSummary: String? = null
)

/**
 * 进入 [AiAgentSession] 历史的一条记录。在 session 内做三件事：
 * 1. 喂回 prompt 给下一轮 AI 看
 * 2. 语音页 records 卡片实时审计
 * 3. session 跑完后由经验本（[top.xjunz.tasker.ai.agent.experience.AiAgentExperienceBook]）
 *    序列化为 [top.xjunz.tasker.ai.agent.experience.ExperienceStep] 落盘；成功经验在用户
 *    主动点击 UI 时还会被翻译成可编辑 XTask 草稿弹 FlowEditor
 *
 * Agent 运行期本身**不**自动把 history 落成任何持久化任务——上面 #3 的草稿转换是用户主动行为。
 *
 * [userIntervention] 记录"本步用户在决策面板做了什么"。null = 未走决策面板（被禁用 / overlay 不可用 /
 * 此 action 类型不需要确认）。AI 在下一轮会通过 prompt 看到这个标记，进行自我学习。
 */
data class AiAgentStepRecord(
    val index: Int,
    val action: AiAgentAction,
    val result: AiAgentStepResult,
    val snapshotPackage: String? = null,
    val userIntervention: top.xjunz.tasker.ai.agent.overlay.AiAgentDecision? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    /**
     * 本步动作执行**之前**抓到的屏幕签名（包名 + activity + 节点统计 + 关键 viewId）。
     * 下一轮 loop 抓到新 snapshot 后跟这个值比对——一致 = 本步动作没让屏幕产生可见变化，
     * 大概率是"节点选错了 / 节点不响应 / 命中了占位文本"。Session 会自动把这条
     * silent-fail 提示追加到 [result.message]，下一轮 prompt 让 AI 看见并改换策略。
     * `null` 表示当时拿不到 snapshot（极少数情况），不参与比对。
     */
    val preActionSignature: String? = null,
    /**
     * Structured ReAct 三段（aidoc/18 §3.A）。AI 在生成 action 之前**强制**先写下的思考。
     * 写进 history 喂回下一轮 prompt 让 AI 看见自己的思维链路（链式 self-consistency）。
     * null = AI 没遵守新格式（兼容退化），上层不影响。
     */
    val observation: String? = null,
    val lastActionReview: String? = null,
    val reflection: String? = null
)

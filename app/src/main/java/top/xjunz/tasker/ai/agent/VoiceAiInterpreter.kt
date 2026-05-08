/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.capability.AiTaskCapability
import top.xjunz.tasker.ai.capability.AiTaskCapabilityCatalog
import top.xjunz.tasker.ai.model.AiIntentType
import top.xjunz.tasker.ai.provider.AiProviderFactory

/**
 * 语音/文本 → 结构化意图。当前支持三种结果：
 * - [VoiceAiInterpretation.RunExistingTask] 用户希望执行某个已有任务。
 * - [VoiceAiInterpretation.CreateTaskDraft] 用户希望创建新任务，AI 给出结构化草稿步骤。
 * - [VoiceAiInterpretation.Unknown] AI 无法判断意图，调用方应回退到规则解析。
 */
object VoiceAiInterpreter {

    /**
     * 任务清单进入 prompt 时的硬上限。超过则按"包含/被包含"做一次本地预筛，
     * 只把最可能相关的前 N 条塞给模型，避免 token 浪费。
     */
    private const val MAX_TASK_TITLES_IN_PROMPT = 60

    /**
     * @param knownTaskTitles 当前用户已经保存的任务标题列表。AI 在判定 RunExistingTask 时
     *   被要求把 query 严格设置为清单中的任务名，能显著降低"AI 猜个名字本地匹配不到"的概率。
     */
    suspend fun interpret(
        text: String,
        knownTaskTitles: List<String> = emptyList()
    ): VoiceAiInterpretationResult? {
        return runProviderRequest(buildPrompt(text, knownTaskTitles)) { dto, confidence ->
            when (dto.intent) {
                AiIntentType.RunExistingTask -> {
                    val query = dto.query?.trim().orEmpty()
                    if (query.isEmpty()) null
                    else VoiceAiInterpretation.RunExistingTask(
                        query = query,
                        summary = dto.summary?.trim().orEmpty(),
                        confidence = confidence
                    )
                }

                AiIntentType.CreateTaskDraft -> dto.toCreateTaskDraft(confidence)

                else -> VoiceAiInterpretation.Unknown(
                    summary = dto.summary?.trim().orEmpty(),
                    confidence = confidence
                )
            }
        }
    }

    /**
     * 当本地任务库匹配不到 AI 给出的查询时，让 AI 改换思路，把同一段用户输入
     * 转成可创建的任务草稿；如果 AI 仍然无法给出可用草稿，返回 null。
     */
    suspend fun generateDraftWhenTaskMissing(
        text: String,
        missingQuery: String
    ): VoiceAiDraftResult? {
        val result = runProviderRequest(buildDraftFallbackPrompt(text, missingQuery)) { dto, confidence ->
            dto.toCreateTaskDraft(confidence)
        } ?: return null
        val draft = result.interpretation as? VoiceAiInterpretation.CreateTaskDraft ?: return null
        return VoiceAiDraftResult(
            draft = draft,
            prompt = result.prompt,
            rawResponse = result.rawResponse,
            providerError = result.providerError
        )
    }

    private suspend fun runProviderRequest(
        prompt: String,
        transform: (VoiceAiInterpretationDto, Float) -> VoiceAiInterpretation?
    ): VoiceAiInterpretationResult? {
        val provider = AiProviderFactory.createConfiguredProvider() ?: return null
        var rawResponse: String? = null
        return runCatching {
            withTimeout(Preferences.aiRequestTimeoutMillis.toLong()) {
                val response = provider.complete(prompt)
                rawResponse = response.text
                val dto = AiJson.decodeFromString<VoiceAiInterpretationDto>(extractJson(response.text))
                val confidence = dto.confidence.coerceIn(0f, 1f)
                if (confidence < Preferences.aiVoiceMinConfidence) {
                    VoiceAiInterpretationResult(
                        interpretation = null,
                        prompt = prompt,
                        rawResponse = response.text,
                        rejectionReason = "confidence ${"%.2f".format(confidence)} < threshold ${Preferences.aiVoiceMinConfidence}"
                    )
                } else {
                    val interpretation = transform(dto, confidence)
                    VoiceAiInterpretationResult(
                        interpretation = interpretation,
                        prompt = prompt,
                        rawResponse = response.text,
                        rejectionReason = if (interpretation == null) "schema mismatch / empty result" else null
                    )
                }
            }
        }.getOrElse { error ->
            VoiceAiInterpretationResult(
                interpretation = null,
                prompt = prompt,
                rawResponse = rawResponse,
                providerError = error.message ?: error::class.simpleName
            )
        }
    }

    private fun VoiceAiInterpretationDto.toCreateTaskDraft(
        confidence: Float
    ): VoiceAiInterpretation.CreateTaskDraft? {
        val title = draftTitle?.trim().orEmpty()
        val parsedSteps = parseDraftSteps(draftSteps)
        if (title.isEmpty() || parsedSteps.isEmpty()) return null
        return VoiceAiInterpretation.CreateTaskDraft(
            title = title,
            summary = summary?.trim().orEmpty(),
            steps = parsedSteps,
            confidence = confidence
        )
    }

    private fun parseDraftSteps(rawSteps: List<JsonElement>?): List<AiDraftStep> {
        if (rawSteps.isNullOrEmpty()) return emptyList()
        return rawSteps.mapNotNull(::parseDraftStep)
    }

    private fun parseDraftStep(element: JsonElement): AiDraftStep? {
        return when (element) {
            is JsonPrimitive -> {
                val text = element.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) null else AiDraftStep.FreeText(text)
            }

            is JsonObject -> parseStructuredStep(element)
            else -> null
        }
    }

    private fun parseStructuredStep(obj: JsonObject): AiDraftStep? {
        val rawId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
        val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawId.isNullOrEmpty()) {
            if (description.isEmpty()) return null
            return AiDraftStep.FreeText(description)
        }
        val params = obj["params"]?.let { paramsNode ->
            (paramsNode as? JsonObject)
                ?.mapValues { (_, value) -> value as? JsonPrimitive }
                ?.filterValues { it != null }
                ?.mapValues { (_, value) -> value!! }
                .orEmpty()
        }.orEmpty()
        val descriptionFromParams = description.ifEmpty { obj["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
        return AiDraftStep.Action(
            capabilityId = rawId,
            params = params,
            description = descriptionFromParams.ifEmpty { rawId }
        )
    }

    private fun buildPrompt(text: String, knownTaskTitles: List<String>): String {
        return """
            你是 Android 自动化应用「AutoTask」的自然语言意图解析器。
            请分析用户输入，并严格输出 JSON（不要 markdown、不要解释）。

            支持的 intent：
            - RunExistingTask：用户希望立即执行一个已有任务，需要给出 query 字段（任务名或搜索关键词）。
            - CreateTaskDraft：用户描述了一个新任务的需求，需要给出 draft_title（任务标题）和 draft_steps（步骤数组）。
            - Unknown：无法判断或与自动化无关。

            ${buildKnownTaskSection(knownTaskTitles, text)}

            ${buildCapabilitySection()}

            schema：
            {
              "intent": "RunExistingTask|CreateTaskDraft|Unknown",
              "query": "<任务名搜索词，仅 RunExistingTask 必填>",
              "draft_title": "<新任务标题>",
              "draft_steps": [
                { "id": "<上面能力清单里的 id>", "params": { ... }, "description": "<人话说明这一步>" }
              ],
              "confidence": 0.0,
              "summary": "<一句话中文解释你的判断>"
            }

            约束：
            - 选择 intent 时**优先**判断是否能对应"用户当前已保存的任务"。
              如果用户描述明显指向清单中某个任务（包括同义、缩写、漏字等），必须返回 RunExistingTask，
              且 query **必须严格等于**该任务的原始标题（保持空格、标点、大小写完全一致），不要发挥也不要简写。
            - 只有清单里确实没有合适项时，才使用 CreateTaskDraft 生成新任务草稿。
            - draft_steps 数组里**优先**使用上面能力清单里的 id；params 必须按对应能力的字段名填写。
            - 如果某一步没有合适的能力，可以用纯字符串描述这一步，App 端会标记为"暂无对应能力"。
            - draft_steps 控制在 3-6 步，每步 description 用一句简洁中文。

            用户输入：
            $text
        """.trimIndent()
    }

    private fun buildDraftFallbackPrompt(text: String, missingQuery: String): String {
        return """
            你是 Android 自动化应用「AutoTask」的草稿生成器。
            刚才你判断用户希望执行任务「$missingQuery」，但本地任务库里找不到这个任务。
            现在请把用户原始输入转换成一个可执行的「新建任务草稿」，输出严格 JSON（不要 markdown）：

            {
              "intent": "CreateTaskDraft",
              "draft_title": "<新任务标题>",
              "draft_steps": [
                { "id": "<能力 id>", "params": { ... }, "description": "<人话>" }
              ],
              "confidence": 0.0,
              "summary": "<一句话中文解释>"
            }

            ${buildCapabilitySection()}

            约束：
            - draft_steps 控制在 3-6 步，每步 description 是一句简洁中文短句。
            - **优先**使用能力清单里的 id；找不到合适能力的步骤可以使用纯字符串描述，但请尽量用结构化 step。
            - 不要尝试再返回 RunExistingTask；本地确实没有匹配项。
            - 如果用户输入与自动化无关，可以输出 intent=Unknown。

            用户原始输入：
            $text
        """.trimIndent()
    }

    private fun buildCapabilitySection(): String {
        return buildString {
            appendLine("AI 草稿当前可用的能力清单（capability map）：")
            AiTaskCapabilityCatalog.capabilities.forEach { capability ->
                appendLine(formatCapability(capability))
            }
        }.trimEnd()
    }

    /**
     * 把当前用户已经保存的任务标题做成 prompt 段落。
     *
     * 如果清单超过 [MAX_TASK_TITLES_IN_PROMPT]，先用一次轻量本地预筛（按标题与用户输入
     * 之间的字符共现度排序），只把最可能相关的前 N 条塞进 prompt，避免 token 浪费。
     */
    private fun buildKnownTaskSection(titles: List<String>, userInput: String): String {
        if (titles.isEmpty()) {
            return "用户当前没有任何已保存的任务（清单为空）。如果用户希望「执行某任务」，请直接选 CreateTaskDraft 或 Unknown。"
        }
        val totalCount = titles.size
        val limited = if (totalCount <= MAX_TASK_TITLES_IN_PROMPT) {
            titles
        } else {
            rankByRelevance(titles, userInput).take(MAX_TASK_TITLES_IN_PROMPT)
        }
        val truncatedHint = if (totalCount > MAX_TASK_TITLES_IN_PROMPT) {
            "（共 $totalCount 条，仅展示与输入最相关的前 ${MAX_TASK_TITLES_IN_PROMPT} 条）"
        } else {
            "（共 $totalCount 条，已全部列出）"
        }
        return buildString {
            appendLine("用户当前已保存的任务清单 $truncatedHint：")
            limited.forEach { appendLine("- $it") }
        }.trimEnd()
    }

    /**
     * 极简相关度排序：标题与用户输入的字符集合交集大小越大越靠前，相同得分按原顺序。
     * 不引入第三方分词，避免在第一阶段过度工程化。
     */
    private fun rankByRelevance(titles: List<String>, userInput: String): List<String> {
        val inputChars = userInput.lowercase().toSet()
        if (inputChars.isEmpty()) return titles
        return titles
            .mapIndexed { index, title ->
                val titleChars = title.lowercase().toSet()
                val score = titleChars.count { it in inputChars }
                Triple(score, index, title)
            }
            .sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    private fun formatCapability(capability: AiTaskCapability): String {
        val params = capability.parameters.joinToString(", ") { param ->
            buildString {
                append(param.name)
                append(":")
                append(param.type.name)
                if (!param.required) append("?")
            }
        }
        val paramsHint = capability.parameters.joinToString("; ") { param ->
            "${param.name}=${param.description}"
        }
        return "- id=${capability.id} (${capability.label}) params={$params} | ${capability.description} | ${paramsHint.ifEmpty { "无参数" }}"
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end >= start) text.substring(start, end + 1) else text
    }
}

sealed interface VoiceAiInterpretation {

    val confidence: Float
    val summary: String

    data class RunExistingTask(
        val query: String,
        override val summary: String,
        override val confidence: Float
    ) : VoiceAiInterpretation

    data class CreateTaskDraft(
        val title: String,
        val steps: List<AiDraftStep>,
        override val summary: String,
        override val confidence: Float
    ) : VoiceAiInterpretation

    data class Unknown(
        override val summary: String,
        override val confidence: Float
    ) : VoiceAiInterpretation
}

/**
 * AI 输出的草稿步骤。第一阶段允许两种形态：
 *
 * - [Action] 命中能力清单，App 端可以直接转换成对应 [top.xjunz.tasker.engine.applet.base.Applet]。
 * - [FreeText] 仅人话描述，本地无法直接转换，转成草稿时会标注"暂无对应能力"。
 */
sealed interface AiDraftStep {

    val description: String

    data class Action(
        val capabilityId: String,
        val params: Map<String, JsonPrimitive>,
        override val description: String
    ) : AiDraftStep

    data class FreeText(
        override val description: String
    ) : AiDraftStep
}

@Serializable
private data class VoiceAiInterpretationDto(
    val intent: AiIntentType = AiIntentType.Unknown,
    val query: String? = null,
    @SerialName("draft_title") val draftTitle: String? = null,
    @SerialName("draft_steps") val draftSteps: List<JsonElement>? = null,
    val confidence: Float = 0f,
    val summary: String? = null
)

/**
 * 通用解释结果，包含 AI 实际看到的 prompt 与 raw response，便于在记录卡片里展开查看。
 */
data class VoiceAiInterpretationResult(
    val interpretation: VoiceAiInterpretation?,
    val prompt: String,
    val rawResponse: String?,
    val rejectionReason: String? = null,
    val providerError: String? = null
)

/**
 * 草稿 fallback 专用结果，方便上层只关心 [draft] 不再做强转。
 */
data class VoiceAiDraftResult(
    val draft: VoiceAiInterpretation.CreateTaskDraft,
    val prompt: String,
    val rawResponse: String?,
    val providerError: String? = null
)

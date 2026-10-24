package com.androidagent.data.memory

import com.androidagent.data.api.DeepSeekClient.ChatMessage
import com.androidagent.data.api.DeepSeekClient.ToolCall
import com.androidagent.data.model.Message
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * OpenViking 自动记忆的纯策略实现（对齐 shell-tool/core/recall.py）：
 *  - 召回 query 构造：跳过注入块，拼接问题 / 助手正文 / 工具名 / 工具结果
 *  - 抓取消息转换：对话 → OV session 批次消息（tool parts）
 *  - 噪声过滤：确认语、斜杠命令、纯标点、信号不足
 *  - skill 名单消息：增量追加、可解析已有名称
 */
object Recall {

    const val REMINDER_OPEN = "<system-reminder>"
    const val REMINDER_CLOSE = "</system-reminder>"
    const val SKILL_MARK = "<available_skills>"
    const val SKILL_MARK_CLOSE = "</available_skills>"
    const val RECALL_MARKER = "## 相关记忆"
    const val PROFILE_MARKER = "<openviking-context source=\"profile\">"
    const val OV_BLOCK_OPEN = "<available_openviking>"
    const val OV_BLOCK_CLOSE = "</available_openviking>"
    const val USER_TURN_OPEN = "<user_turn>"
    const val USER_TURN_CLOSE = "</user_turn>"
    const val USER_TIPS_OPEN = "<user_tips>"
    const val USER_TIPS_CLOSE = "</user_tips>"
    const val USER_WARNING_OPEN = "<user_warning>"
    const val USER_WARNING_CLOSE = "</user_warning>"
    const val LEGACY_RECALL_PREFIX = "[自动检索的候选记忆"
    const val MIN_QUERY_LENGTH = 3

    private const val CAPTURE_MAX_LENGTH = 24_000
    private const val CAPTURE_TOOL_MAX_CHARS = 1_000_000

    private val gson = Gson()
    private val ackRe = Regex(
        "^(?:ok|okay|k|yes|yep|no|nope|thanks|thank you|thx|done|收到|好的|好|嗯|可以|继续|" +
            "不用|不需要|没了|好了)[.!?。！？\\s]*$",
        RegexOption.IGNORE_CASE
    )
    private val slashRe = Regex("^/[a-z0-9_-]{1,64}\\b", RegexOption.IGNORE_CASE)
    private val cjkRe = Regex("[\\u3400-\\u9fff]")
    private val alnumRe = Regex("[a-z0-9]", RegexOption.IGNORE_CASE)
    private val signalRe = Regex("[a-z0-9\\u3400-\\u9fff]", RegexOption.IGNORE_CASE)
    private val skillNameRe = Regex("`([A-Za-z0-9_\\-]{1,64})`")
    private val recallUriRe = Regex("^-\\s*\\[([^\\]]+)]\\s*\\(score=", RegexOption.MULTILINE)

    /** ChatMessage.content 可能是纯文本，也可能是 vision 的 parts 数组。 */
    fun contentText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is List<*> -> content.joinToString("\n") { part ->
            (part as? Map<*, *>)?.get("text") as? String ?: ""
        }.trim()
        else -> content.toString()
    }

    private fun queryText(m: ChatMessage): String {
        val text = contentText(m.content)
        return when (m.role) {
            Message.ROLE_INJECT -> ""
            "user" -> if (isInjectedContent(text)) "" else text
            "tool" -> text
            "assistant" -> buildString {
                append(text)
                m.toolCalls?.forEach { tc ->
                    if (tc.function.name.isNotBlank()) append('\n').append(tc.function.name)
                }
            }.trim()
            else -> ""
        }
    }

    /** 是否为注入内容（skill 名单 / 召回 / profile / user_tips，含旧版 user 前缀格式）。 */
    fun isInjectedContent(text: String): Boolean =
        text.startsWith(REMINDER_OPEN) || text.startsWith(LEGACY_RECALL_PREFIX) ||
            RECALL_MARKER in text || PROFILE_MARKER in text || SKILL_MARK in text ||
            OV_BLOCK_OPEN in text || USER_TIPS_OPEN in text || USER_WARNING_OPEN in text

    /** 由完整消息批次构造检索 query（含工具结果，避免只看首轮问题）。 */
    fun buildQuery(messages: List<ChatMessage>): String =
        messages.mapNotNull { queryText(it).takeIf { t -> t.isNotBlank() } }
            .joinToString("\n")
            .trim()

    /** 只包 `<available_openviking>`，供合并进本轮统一 system-reminder 时使用。 */
    fun openvikingBlock(block: String): String = "$OV_BLOCK_OPEN\n$block\n$OV_BLOCK_CLOSE"

    /** 独立注入的召回块（如工具轮 step 召回）：整条 system-reminder 包裹。 */
    fun wrapRecallBlock(block: String): String =
        "$REMINDER_OPEN\n${openvikingBlock(block)}\n$REMINDER_CLOSE"

    /** skill 名单块（不含外层 system-reminder，由本轮统一包裹）。 */
    fun buildSkillBlock(skills: List<Pair<String, String>>, delta: Boolean): String {
        val header = if (delta) "以下为新增可用 skill：\n" else ""
        val items = skills.joinToString("\n") { "- `${it.first}`: ${it.second}" }
        return "$SKILL_MARK\n$header$items\n$SKILL_MARK_CLOSE"
    }

    /** 轮次提示块。 */
    fun buildUserTurnBlock(turnNo: Int): String =
        "$USER_TURN_OPEN\n这是第 $turnNo 轮对话。\n$USER_TURN_CLOSE"

    /** 用户自定义提示块。 */
    fun buildUserTipsBlock(tips: String): String =
        "$USER_TIPS_OPEN\n${tips.trim()}\n$USER_TIPS_CLOSE"

    /** 运行时警告块（工具轮耗尽等）。 */
    fun buildUserWarning(text: String): String =
        "$REMINDER_OPEN\n$USER_WARNING_OPEN\n${text.trim()}\n$USER_WARNING_CLOSE\n$REMINDER_CLOSE"

    /** 把本轮所有注入块合并成一条 system-reminder 消息。 */
    fun buildTurnContext(turnNo: Int, blocks: List<String>): String {
        val parts = mutableListOf(buildUserTurnBlock(turnNo))
        parts.addAll(blocks.filter { it.isNotBlank() })
        return "$REMINDER_OPEN\n" + parts.joinToString("\n") + "\n$REMINDER_CLOSE"
    }

    /** 从已落库的名单消息中解析 skill 名称（只看 available_skills 区块）。 */
    fun parseSkillNames(content: String): List<String> {
        val start = content.indexOf(SKILL_MARK)
        if (start < 0) return emptyList()
        val end = content.indexOf(SKILL_MARK_CLOSE, start).let { if (it < 0) content.length else it }
        return skillNameRe.findAll(content.substring(start, end)).map { it.groupValues[1] }.toList()
    }

    /** 从已落库的召回块中解析记忆 URI，用于恢复会话内的去重记录。 */
    fun parseRecallUris(content: String): List<String> =
        recallUriRe.findAll(content).map { it.groupValues[1] }.toList()

    /** 对齐官方 capture-utils.shouldCaptureText 的轻量噪音过滤。 */
    fun shouldCapture(text: String, role: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (role == "user" && slashRe.containsMatchIn(t)) return false
        if (ackRe.matches(t)) return false
        if (!signalRe.containsMatchIn(t)) return false
        val cjk = cjkRe.findAll(t).count()
        val alnum = alnumRe.findAll(t).count()
        return cjk >= 4 || alnum >= 6 || t.length >= 12
    }

    /** 把对话消息转成 OV session 批次消息（跳过注入块，工具调用与结果合并为 parts）。 */
    fun toOvMessages(messages: List<ChatMessage>): List<Map<String, Any>> {
        val toolNames = mutableMapOf<String, String>()
        for (m in messages) {
            m.toolCalls?.forEach { tc ->
                if (tc.id.isNotBlank() && tc.function.name.isNotBlank()) toolNames[tc.id] = tc.function.name
            }
        }

        val out = mutableListOf<Map<String, Any>>()
        val index = mutableMapOf<String, MutableMap<String, Any>>()

        for (m in messages) {
            val text = contentText(m.content)
            if (m.role == Message.ROLE_INJECT) continue
            if (isInjectedContent(text)) continue

            when (m.role) {
                "assistant" -> {
                    val keepText = shouldCapture(text, "assistant")
                    val parts = mutableListOf<Map<String, Any>>()
                    if (keepText) parts.add(mapOf("type" to "text", "text" to text.take(CAPTURE_MAX_LENGTH)))
                    m.toolCalls?.forEach { tc ->
                        val part = toolCallPart(tc).toMutableMap()
                        parts.add(part)
                        if (tc.id.isNotBlank()) index[tc.id] = part
                    }
                    when {
                        parts.isNotEmpty() -> out.add(mapOf("role" to "assistant", "parts" to parts))
                        keepText -> out.add(
                            mapOf("role" to "assistant", "content" to text.take(CAPTURE_MAX_LENGTH))
                        )
                    }
                }

                "tool" -> {
                    val content = text.take(CAPTURE_TOOL_MAX_CHARS)
                    if (content.isBlank()) continue
                    val tid = m.toolCallId.orEmpty()
                    val part = if (tid.isNotBlank()) index[tid] else null
                    if (part != null) {
                        part["tool_status"] = toolResultStatus(content)
                        part["tool_output"] = content
                        if ((part["tool_name"] as? String).isNullOrBlank()) {
                            part["tool_name"] = m.name ?: toolNames[tid] ?: ""
                        }
                    } else {
                        out.add(
                            mapOf(
                                "role" to "assistant",
                                "parts" to listOf(toolResultPart(content, tid, m.name ?: toolNames[tid]))
                            )
                        )
                    }
                }

                "user" -> {
                    if (!shouldCapture(text, "user")) continue
                    out.add(mapOf("role" to "user", "content" to text.take(CAPTURE_MAX_LENGTH)))
                }
            }
        }
        return out
    }

    private fun toolCallPart(tc: ToolCall): Map<String, Any> {
        val part = mutableMapOf<String, Any>("type" to "tool", "tool_status" to "running")
        if (tc.id.isNotBlank()) part["tool_id"] = tc.id
        if (tc.function.name.isNotBlank()) part["tool_name"] = tc.function.name
        toolInput(tc.function.arguments)?.let { part["tool_input"] = it }
        return part
    }

    private fun toolInput(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return mapOf("value" to raw)
        val value = runCatching { gson.fromJson<Any>(parsed, Any::class.java) }.getOrNull()
        return when (value) {
            is Map<*, *> -> value
            null -> mapOf("value" to raw)
            else -> mapOf("value" to value)
        }
    }

    private fun toolResultPart(content: String, toolId: String, toolName: String?): Map<String, Any> {
        val part = mutableMapOf<String, Any>(
            "type" to "tool",
            "tool_status" to toolResultStatus(content),
            "tool_output" to content
        )
        if (toolId.isNotBlank()) part["tool_id"] = toolId
        if (!toolName.isNullOrBlank()) part["tool_name"] = toolName
        return part
    }

    private fun toolResultStatus(content: String): String {
        val t = content.trimStart()
        return if (t.startsWith("Error:") || t.startsWith("{\"status\":\"error\"") ||
            t.startsWith("{\"error\"")
        ) "error" else "completed"
    }
}

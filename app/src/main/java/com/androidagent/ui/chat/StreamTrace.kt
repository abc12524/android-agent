package com.androidagent.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.data.model.Message
import org.json.JSONArray
import org.json.JSONObject

// ==================== 思考流：数据分组 ====================

internal enum class StepKind { THINK, TOOL }

/** script skill 的一次写操作（create / add / edit / delete）结果 */
internal data class ScriptEdit(
    val file: String,
    val action: String,
    val diff: String,
    val added: Int,
    val removed: Int,
    val dryRun: Boolean,
)

/** 思考流中的一步：思维链文本，或一次工具调用 */
internal data class TraceStep(
    val kind: StepKind,
    val text: String = "",
    val callId: String? = null,
    val toolName: String? = null,
    val title: String = "",
    val command: String = "",
    val body: String = "",
    val argsJson: String = "",
    val resultJson: String = "",
    val running: Boolean = false,
    val failed: Boolean = false,
    val edit: ScriptEdit? = null,
)

internal sealed class StreamItem {
    data class Bubble(val msg: Message) : StreamItem()
    data class Trace(
        val key: Long,
        val steps: List<TraceStep>,
        val media: List<Message>,
    ) : StreamItem()
}

private data class PendingCall(val id: String?, val name: String, val args: String)

/**
 * 把消息序列折叠成「气泡 + 思考流」：
 * 一次回答里的思维链、工具调用与工具结果都并入同一条思考流，正文单独成气泡。
 */
internal fun groupStreamItems(messages: List<Message>): List<StreamItem> {
    val out = mutableListOf<StreamItem>()
    var steps = mutableListOf<TraceStep>()
    var media = mutableListOf<Message>()
    var traceKey = 0L

    fun flush() {
        if (steps.isNotEmpty()) {
            // 未落到结果的步骤（异常中断）标记为结束，避免一直显示「执行中」
            val sealed = steps.map { s ->
                if (s.running) s.copy(running = false, body = s.body.ifBlank { "(未执行)" }) else s
            }
            out.add(StreamItem.Trace(traceKey, sealed, media.toList()))
        }
        steps = mutableListOf()
        media = mutableListOf()
        traceKey = 0L
    }

    fun begin(id: Long) {
        if (traceKey == 0L) traceKey = id
    }

    for (m in messages) {
        when (m.role) {
            "assistant" -> {
                val calls = parseToolCalls(m.toolCalls)
                val think = m.reasoningContent.orEmpty().trim()
                val content = m.content.trim()

                if (think.isNotBlank()) {
                    begin(m.id)
                    steps += TraceStep(kind = StepKind.THINK, text = think)
                }
                if (calls.isNotEmpty()) {
                    begin(m.id)
                    // 工具调用轮里的旁白也算思考内容
                    if (content.isNotBlank()) {
                        steps += TraceStep(kind = StepKind.THINK, text = content)
                    }
                    calls.forEach { steps += pendingStep(it) }
                } else if (content.isNotBlank()) {
                    flush()
                    out += StreamItem.Bubble(m)
                }
            }

            "tool" -> {
                begin(m.id)
                applyToolResult(steps, m)
                media += m
            }

            else -> {
                flush()
                out += StreamItem.Bubble(m)
            }
        }
    }
    flush()
    return out
}

private fun parseToolCalls(json: String?): List<PendingCall> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val fn = o.optJSONObject("function") ?: return@mapNotNull null
            val name = fn.optString("name")
            if (name.isBlank()) null else PendingCall(
                id = o.optString("id").takeIf { it.isNotBlank() },
                name = name,
                args = fn.optString("arguments", ""),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseJson(json: String?): JSONObject? = try {
    if (json.isNullOrBlank()) null else JSONObject(json)
} catch (_: Exception) {
    null
}

private fun argsOf(json: String?): JSONObject? = parseJson(json)

private fun paramsOf(args: JSONObject?): JSONObject? = args?.optJSONObject("params") ?: args

private fun actionOf(args: JSONObject?): String = args?.optString("action").orEmpty()

private val SCRIPT_WRITE_ACTIONS = setOf("create", "add", "edit", "delete")

private fun isScriptCall(args: JSONObject?): Boolean = args?.optString("action") == "script"

private fun scriptAction(args: JSONObject?): String =
    args?.optJSONObject("params")?.optString("action").orEmpty()

private fun isScriptEdit(args: JSONObject?): Boolean =
    isScriptCall(args) && scriptAction(args) in SCRIPT_WRITE_ACTIONS

private fun scriptFile(args: JSONObject?): String {
    val p = args?.optJSONObject("params") ?: return ""
    return p.optString("file_path").ifBlank { p.optString("url") }
}

private fun toolTitle(name: String, args: JSONObject?, isEdit: Boolean = false): String {
    val action = actionOf(args)
    if (isEdit || isScriptCall(args)) {
        val act = scriptAction(args)
        return if (act.isBlank()) "Script" else "Script · $act"
    }
    return when (name) {
        "system_tool" -> when (action) {
            "shell" -> "Shell"
            "python" -> "Python"
            "ssh" -> "SSH"
            else -> if (action.isBlank()) name else "System · $action"
        }
        "phone_tool" -> if (action.isBlank()) "Phone" else "Phone · $action"
        "skill" -> if (action.isBlank()) "Skill" else "Skill · $action"
        "skill_hub" -> if (action.isBlank()) "Skill Hub" else "Skill Hub · $action"
        else -> if (action.isBlank()) name else "$name · $action"
    }
}

private fun commandOf(name: String, args: JSONObject?): String {
    if (name != "system_tool") return ""
    val action = actionOf(args)
    val p = paramsOf(args) ?: return ""
    return when (action) {
        "shell" -> p.optString("command")
        "ssh" -> if (p.optString("action", "exec") == "exec" || p.optString("action").isBlank()) {
            p.optString("command")
        } else ""
        "python" -> when (p.optString("mode")) {
            "script" -> "python ${p.optString("script_path")}"
            "pip" -> "pip install ${p.optString("packages")}"
            "code" -> p.optString("code")
            else -> ""
        }
        else -> ""
    }
}

private fun pendingStep(c: PendingCall): TraceStep {
    val args = argsOf(c.args)
    val script = isScriptCall(args)
    return TraceStep(
        kind = StepKind.TOOL,
        callId = c.id,
        toolName = c.name,
        title = toolTitle(c.name, args, script),
        command = when {
            isScriptEdit(args) -> scriptFile(args)
            script -> ""
            else -> commandOf(c.name, args)
        },
        argsJson = c.args,
        running = true,
    )
}

private fun applyToolResult(steps: MutableList<TraceStep>, m: Message) {
    val argsJson = m.toolArgs
        ?.takeIf { it.isNotBlank() }
        ?: steps.firstOrNull { it.running && it.toolName == m.toolName }?.argsJson
        ?: ""
    val args = argsOf(argsJson)
    val script = isScriptCall(args)
    val edit = if (script) buildScriptEdit(args, m.content) else null
    val failed = resultFailed(m.content)
    val step = TraceStep(
        kind = StepKind.TOOL,
        callId = m.toolCallId,
        toolName = m.toolName,
        title = toolTitle(m.toolName.orEmpty(), args, script),
        command = when {
            isScriptEdit(args) -> scriptFile(args)
            script -> ""
            else -> commandOf(m.toolName.orEmpty(), args)
        },
        body = resultBody(m.content),
        argsJson = argsJson,
        resultJson = m.content,
        running = false,
        failed = failed,
        edit = edit,
    )
    val byCall = m.toolCallId?.let { id -> steps.indexOfFirst { it.callId == id } } ?: -1
    val byName = steps.indexOfFirst { it.running && it.toolName == m.toolName }
    val idx = when {
        byCall >= 0 -> byCall
        byName >= 0 -> byName
        else -> -1
    }
    if (idx >= 0) steps[idx] = step else steps += step
}

private fun resultFailed(content: String): Boolean {
    val o = parseJson(content) ?: return false
    if (o.has("error")) return true
    if (o.optString("status") == "error") return true
    if (o.has("success") && !o.optBoolean("success")) return true
    if (o.has("exit_code") && o.optInt("exit_code") != 0) return true
    return false
}

/** 工具结果 → 展示文本：解包 shell/python 的 output、skill 信封的 result 等 */
private fun resultBody(content: String): String {
    val o = parseJson(content) ?: return content
    if (o.has("error")) {
        return o.optString("error").ifBlank { content }
    }
    if (o.optString("status") == "error") {
        val msg = o.optString("message").ifBlank { o.optString("error") }
        return msg.ifBlank { content }
    }
    if (o.has("output")) {
        val out = o.optString("output")
        val code = if (o.has("exit_code")) o.optInt("exit_code") else 0
        return if (code != 0) "$out\n(exit $code)" else out
    }
    if (o.has("result")) {
        val r = o.opt("result")
        return when {
            r is String -> r
            r is JSONObject -> r.toString(2)
            r is JSONArray -> r.toString(2)
            r != null -> r.toString()
            else -> ""
        }
    }
    return if (o.length() > 0) o.toString(2) else content
}

private fun buildScriptEdit(args: JSONObject?, content: String): ScriptEdit? {
    val p = args?.optJSONObject("params") ?: return null
    val act = p.optString("action")
    if (act !in SCRIPT_WRITE_ACTIONS) return null
    val env = parseJson(content) ?: return null
    val res = env.optJSONObject("result") ?: env
    val file = res.optString("file").ifBlank {
        p.optString("file_path").ifBlank { p.optString("url") }
    }
    val diff = res.optString("diff")
    if (diff.isBlank() && act != "create") return null
    var added = 0
    var removed = 0
    diff.lineSequence().forEach { line ->
        if (line.startsWith("+") && !line.startsWith("+++")) added++
        if (line.startsWith("-") && !line.startsWith("---")) removed++
    }
    return ScriptEdit(
        file = file,
        action = res.optString("action").ifBlank { act },
        diff = diff,
        added = added,
        removed = removed,
        dryRun = res.optBoolean("dry_run"),
    )
}

// ==================== 思考流：渲染 ====================

/** 新版会话列表：思考流 + 正文 + 流式占位 + token 统计 */
@Composable
internal fun StreamChatList(
    state: ChatUiState,
    listState: LazyListState,
) {
    val display = state.messages.filter { msg ->
        val injected = msg.role == Message.ROLE_INJECT ||
            (msg.role == "user" && msg.content.startsWith("[自动检索的候选记忆"))
        msg.role != "system" && (!injected || state.showInjected)
    }
    val streamItems = remember(display) { groupStreamItems(display) }

    LaunchedEffect(streamItems.size, state.isLoading) {
        if (!state.isLoading && streamItems.isNotEmpty()) {
            listState.animateScrollToItem(streamItems.lastIndex)
        }
    }
    LaunchedEffect(state.streamingContent.length) {
        if (state.isLoading) {
            listState.scrollToItem(streamItems.size)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 2.dp),
    ) {
        items(streamItems.size, key = { index ->
            when (val it = streamItems[index]) {
                is StreamItem.Bubble -> it.msg.id
                // 思考流起始消息可能与某条正文同 id（如只有思维链、无工具调用的回复），
                // 加前缀避免 LazyColumn key 重复导致闪退。
                is StreamItem.Trace -> "trace-${it.key}"
            }
        }) { index ->
            when (val it = streamItems[index]) {
                is StreamItem.Bubble -> SelectionContainer {
                    MessageBubble(it.msg, suppressReasoning = true)
                }
                is StreamItem.Trace -> ThinkingTrace(
                    steps = it.steps,
                    media = it.media,
                    showReasoning = state.showReasoning,
                    showToolCalls = state.showToolCalls,
                    showToolEdit = state.showToolEdit,
                )
            }
        }

        if (state.isLoading) {
            item(key = "streaming") {
                StreamingTraceBlock(
                    content = state.streamingContent,
                    reasoning = state.streamingReasoning,
                    showReasoning = state.showReasoning,
                )
            }
        }

        if (state.lastUsage != null) {
            item(key = "token_footer") {
                TokenFooter(
                    cacheHit = state.cacheHitTokens,
                    cacheMiss = state.cacheMissTokens,
                    sessionOut = state.completionTokens,
                    costHit = state.costHit,
                    costMiss = state.costMiss,
                    costOut = state.costOut,
                    family = state.modelFamily,
                    balance = state.balance,
                )
            }
        }
    }
}

/**
 * 思考流：思维链为浅灰正文（无框），工具结果为可折叠的终端/代码块，
 * script 写操作在有开关时渲染成 diff 编辑视图。
 */
@Composable
internal fun ThinkingTrace(
    steps: List<TraceStep>,
    media: List<Message> = emptyList(),
    showReasoning: Boolean = true,
    showToolCalls: Boolean = true,
    showToolEdit: Boolean = true,
) {
    val visible = steps.filter { step ->
        when (step.kind) {
            StepKind.THINK -> showReasoning
            StepKind.TOOL -> showToolCalls
        }
    }
    if (visible.isEmpty()) return

    val cs = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(true) }
    val toolCount = visible.count { it.kind == StepKind.TOOL }

    Column(Modifier.fillMaxWidth()) {
        if (!expanded) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (toolCount > 0) "思考过程 · $toolCount 次工具调用" else "思考过程",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "展开",
                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
            return@Column
        }

        // 循环内必须给每个工具块独立的 composition key，否则 rememberSaveable
        // 会生成重复 key，多步工具调用时直接抛异常闪退。
        visible.forEachIndexed { index, step ->
            when (step.kind) {
                StepKind.THINK -> ThinkText(step.text)
                StepKind.TOOL -> key(step.callId ?: "tool-$index") {
                    ToolBlock(step, showToolEdit)
                }
            }
        }

        if (visible.size >= 3) {
            Text(
                "收起思考",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = false }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (showToolCalls && media.isNotEmpty()) {
            val parsed = media.map { MediaParser.parse(it.content) }
            val images = parsed.flatMap { it.images }
            val audios = parsed.flatMap { it.audios }
            if (images.isNotEmpty() || audios.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    images.forEach { item ->
                        MediaImage(
                            source = item.source,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    audios.forEach { item ->
                        AudioAttachmentPlayer(source = item.source, label = item.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkText(text: String) {
    if (text.isBlank()) return
    val cs = MaterialTheme.colorScheme
    SelectionContainer {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = cs.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun ToolBlock(step: TraceStep, showToolEdit: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    var expanded by rememberSaveable(step.callId ?: step.title) { mutableStateOf(true) }
    var detail by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(step)
            if (step.running || step.failed) Spacer(Modifier.width(6.dp))
            Text(
                step.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = cs.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                "详情",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { detail = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .animateContentSize(tween(180)),
                shape = RoundedCornerShape(10.dp),
                color = if (dark) cs.surfaceVariant.copy(alpha = 0.35f) else Color.White,
                border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val edit = step.edit
                    if (edit != null && showToolEdit) {
                        ScriptDiffView(edit, step.body)
                    } else {
                        TerminalView(step)
                    }
                }
            }
        }
    }

    if (detail) {
        val title = step.toolName
            ?.takeIf { it.isNotBlank() }
            ?.let { "工具调用: $it" }
            ?: step.title
        val body = buildString {
            if (step.argsJson.isNotBlank()) {
                append("参数:\n").append(step.argsJson).append("\n\n")
            }
            append("结果:\n").append(step.resultJson.ifBlank { step.body })
        }
        DetailSheet(title = title, body = body, onDismiss = { detail = false })
    }
}

@Composable
private fun StatusDot(step: TraceStep) {
    val cs = MaterialTheme.colorScheme
    when {
        step.running -> {
            val transition = rememberInfiniteTransition(label = "toolRunning")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "dotAlpha",
            )
            Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary.copy(alpha = alpha)))
        }
        step.failed -> Box(Modifier.size(7.dp).clip(CircleShape).background(cs.error))
        else -> Unit
    }
}

@Composable
private fun TerminalView(step: TraceStep) {
    val cs = MaterialTheme.colorScheme
    var full by rememberSaveable(step.callId ?: step.title) { mutableStateOf(false) }
    val lines = remember(step.body) { step.body.lines() }
    val limit = 26
    val shown = if (full || lines.size <= limit) step.body else lines.take(limit).joinToString("\n")

    SelectionContainer {
        Column {
            if (step.command.isNotBlank()) {
                Text(
                    "$ " + step.command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                step.running && step.body.isBlank() -> Text(
                    "执行中…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
                step.body.isBlank() -> Text(
                    "(无输出)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
                else -> Text(
                    shown,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = cs.onSurface.copy(alpha = 0.88f),
                )
            }
            if (!full && lines.size > limit) {
                Text(
                    "展开全部（${lines.size} 行）",
                    fontSize = 11.sp,
                    color = cs.primary.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { full = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScriptDiffView(edit: ScriptEdit, fallbackBody: String) {
    val cs = MaterialTheme.colorScheme
    var full by rememberSaveable(edit.file + edit.action) { mutableStateOf(false) }
    val lines = remember(edit.diff) { edit.diff.lines().filter { it.isNotBlank() } }
    val limit = 60

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                edit.file.ifBlank { "(未命名文件)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (edit.added > 0) {
                Text("+${edit.added}", fontSize = 11.sp, color = diffAddColor())
            }
            if (edit.removed > 0) {
                Spacer(Modifier.width(4.dp))
                Text("−${edit.removed}", fontSize = 11.sp, color = diffRemoveColor())
            }
            if (edit.dryRun) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "预览",
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        if (lines.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (edit.action == "create") {
                    (if (edit.dryRun) "将创建 " else "已创建 ") + edit.file
                } else {
                    fallbackBody.ifBlank { "已${edit.action}" }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = cs.onSurface.copy(alpha = 0.85f),
            )
        } else {
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    lines.take(if (full) lines.size else limit).forEach { line ->
                        DiffLine(line)
                    }
                }
            }
            if (!full && lines.size > limit) {
                Text(
                    "展开全部 diff（${lines.size} 行）",
                    fontSize = 11.sp,
                    color = cs.primary.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { full = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val added = line.startsWith("+") && !line.startsWith("+++")
    val removed = line.startsWith("-") && !line.startsWith("---")
    val hunk = line.startsWith("@@")
    val meta = line.startsWith("+++") || line.startsWith("---") ||
        line.startsWith("diff ") || line.startsWith("index ")

    val fg = when {
        added -> diffAddColor()
        removed -> diffRemoveColor()
        meta -> cs.onSurfaceVariant.copy(alpha = 0.45f)
        hunk -> cs.onSurfaceVariant.copy(alpha = 0.7f)
        else -> cs.onSurface.copy(alpha = 0.82f)
    }
    val bg = when {
        added -> if (dark) Color(0x3381C784) else Color(0x122E7D32)
        removed -> if (dark) Color(0x33E57373) else Color(0x12C62828)
        hunk -> cs.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Text(
        line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun diffAddColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
}

@Composable
private fun diffRemoveColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(0xFFE57373) else Color(0xFFC62828)
}

/** 流式中的思考流：灰色推理文本 + 正文，无卡片 */
@Composable
internal fun StreamingTraceBlock(
    content: String,
    reasoning: String,
    showReasoning: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank() && showReasoning) {
            SelectionContainer {
                Text(
                    reasoning,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (content.isBlank()) {
            Row(
                Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StreamingDots()
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在思考…",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            MarkdownBody(content)
            BlinkingCursor(color = cs.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

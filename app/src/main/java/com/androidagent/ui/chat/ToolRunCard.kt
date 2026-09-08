package com.androidagent.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.data.model.Message
import com.androidagent.ui.theme.AppElevation
import com.androidagent.ui.theme.AppRadii
import com.androidagent.ui.theme.BubbleAssistant

/** 一条「工具调用」：名称 + 弹出详情正文 */
data class ToolCallEntry(val name: String, val detail: String)

/**
 * 「工具执行 / ov-search」卡片 — 单个圆角矩形、底色统一，行内每行「工具调用: 名称」。
 * 卡片不内联展开；点击某一行 → 弹出 [DetailSheet] 查看该工具的参数与完整结果。
 */
@Composable
internal fun ToolCallCard(entries: List<ToolCallEntry>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    if (entries.isEmpty()) return
    var detail by remember { mutableStateOf<ToolCallEntry?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(200)),
        shape = AppRadii.timelineCard,
        color = if (cs.surface.luminance() < 0.5f) Color(0xFF2B2B2E) else BubbleAssistant,
        shadowElevation = AppElevation.soft,
        border = BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            entries.forEachIndexed { i, e ->
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = if (i == 0) 8.dp else 6.dp)
                        .clickable { detail = e },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Build, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "工具调用: ${e.name}",
                        fontSize = 13.sp,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "查看详情",
                        tint = cs.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    detail?.let { e ->
        DetailSheet(title = "工具调用: ${e.name}", body = e.detail, onDismiss = { detail = null })
    }
}

/** 从工具结果消息提取「工具调用」条目（名称 + 参数/结果详情） */
@Composable
fun ToolRunCard(steps: List<Message>, modifier: Modifier = Modifier) {
    val entries = steps
        .filter { it.role == "tool" && it.toolName != null }
        .map { ToolCallEntry(it.toolName!!, buildToolDetail(it)) }
    if (entries.isEmpty()) return
    ToolCallCard(entries, modifier)
}

private fun buildToolDetail(step: Message): String {
    val args = step.toolArgs ?: ""
    val result = step.content.ifBlank { "(空)" }
    return if (args.isNotBlank()) {
        "参数:\n$args\n\n结果:\n$result"
    } else {
        "结果:\n$result"
    }
}

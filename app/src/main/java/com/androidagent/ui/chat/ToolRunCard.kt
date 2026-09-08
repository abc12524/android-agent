package com.androidagent.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.androidagent.ui.theme.AppElevation
import com.androidagent.ui.theme.AppRadii
import com.androidagent.ui.theme.HapticsType
import com.androidagent.ui.theme.rememberHaptics

/**
 * 「工具执行」卡片 — 对标 kelivo 的 _ChainOfThoughtToolStep 并行列表：
 * 浅色圆角卡内，每行「调用工具: name >」之间用左侧竖线连接成时间轴；
 * 点某行 → 弹出 [DetailSheet]（参数 + 完整结果）。推理独立在 [ReasoningCard]，此处仅工具。
 */
@Composable
fun ToolRunCard(steps: List<Message>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val tools = steps.filter { it.role == "tool" && it.toolName != null }
    if (tools.isEmpty()) return

    var detailTitle by remember { mutableStateOf<String?>(null) }
    var detailBody by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(200)),
        shape = AppRadii.timelineCard,
        color = cs.surface.copy(alpha = if (dark) 0.05f else 0.7f),
        shadowElevation = AppElevation.soft,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            // 顶部小标
            Text(
                "工具执行",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
            )

            tools.forEachIndexed { i, step ->
                ToolParallelRow(
                    title = step.toolName ?: "工具调用",
                    argsText = remember(step.toolArgs) {
                        val json = step.toolArgs ?: ""
                        try {
                            val obj = com.google.gson.Gson().fromJson(json, Map::class.java)
                            obj.entries.joinToString("\n") { (k, v) -> "• $k: $v" }
                        } catch (_: Exception) {
                            json
                        }
                    },
                    resultText = step.content.ifBlank { "(空)" },
                    isLast = i == tools.lastIndex,
                    onClick = {
                        detailTitle = step.toolName ?: "工具结果"
                        detailBody = buildToolDetail(step)
                    },
                )
                if (i != tools.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }

    detailTitle?.let { title ->
        DetailSheet(
            title = title,
            body = detailBody.orEmpty(),
            onDismiss = {
                detailTitle = null
                detailBody = null
            },
        )
    }
}

/** 并行行：左侧竖线连接（kelivo 时间轴）+ 名称 + 预览 + chevron */
@Composable
private fun ToolParallelRow(
    title: String,
    argsText: String,
    resultText: String,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = cs.primary
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rowBg by animateColorAsState(
        if (pressed) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent,
        label = "toolRow",
    )

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左轨
        Column(
            Modifier.width(26.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(tint.copy(alpha = 0.8f)))
            if (!isLast) {
                Box(
                    Modifier.width(2.dp).fillMaxHeight()
                        .background(cs.outlineVariant.copy(alpha = 0.6f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))

        Column(
            Modifier.weight(1f).clip(AppRadii.card).background(rowBg)
                .clickable(interactionSource = interaction, indication = null) {
                    haptics(HapticsType.Light); onClick()
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BuildCircle, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
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
            if (argsText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MonoPreview("参数", argsText, cs)
            }
            Spacer(Modifier.height(4.dp))
            MonoPreview("结果", resultText, cs)
        }
    }
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

// ---- 标签 + 等宽正文预览（详情见底部弹出的面板） ----
@Composable
private fun MonoPreview(label: String, text: String, cs: ColorScheme) {
    Column {
        if (label.isNotBlank()) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

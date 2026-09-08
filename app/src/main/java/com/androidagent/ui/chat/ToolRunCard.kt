package com.androidagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 「工具执行」时间线折叠卡 — 对标 kelivo-agent 的 _ChainOfThoughtCard。
 * 将一次 assistant 回复里的推理步骤 + 若干工具调用合并为一张可展开卡片，
 * 按竖直时间线呈现（左侧图标列 + 右侧内容）。
 *
 * 交互对齐 kelivo：点卡片整体展开/收起时间线；点某个步骤行 → 弹出
 * [DetailSheet]（底部详情面板，可滚动/选中全文），对应 kelivo 的 _showDetail
 * → showModalBottomSheet。默认收起，点击展开。
 */
@Composable
fun ToolRunCard(steps: List<Message>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val haptics = rememberHaptics()
    var expanded by remember { mutableStateOf(false) }
    var detailTitle by remember { mutableStateOf<String?>(null) }
    var detailBody by remember { mutableStateOf<String?>(null) }

    val reasoning = steps.filter {
        it.role == "assistant" && !it.reasoningContent.isNullOrBlank()
    }
    val tools = steps.filter { it.role == "tool" && it.toolName != null }
    val totalSteps = reasoning.size + tools.size
    if (totalSteps == 0) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(260))
            .clickable {
                haptics(HapticsType.Medium)
                expanded = !expanded
            },
        shape = AppRadii.timelineCard,
        color = cs.primaryContainer.copy(alpha = if (dark) 0.25f else 0.30f),
        shadowElevation = AppElevation.soft,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(Icons.Outlined.BuildCircle, tint = cs.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "工具执行",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                    )
                    Text(
                        "包含 $totalSteps 个步骤 · 点击步骤查看详情",
                        fontSize = 11.sp,
                        color = cs.onSurface.copy(alpha = 0.55f),
                    )
                }
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "chevron",
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = cs.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rotation),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(tween(200)),
                exit = shrinkVertically() + fadeOut(tween(150)),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    val stepsAll = buildList {
                        reasoning.forEach { add(it) }
                        tools.forEach { add(it) }
                    }
                    stepsAll.forEachIndexed { i, step ->
                        val isLast = i == stepsAll.lastIndex
                        if (step.role == "assistant") {
                            ReasoningStep(
                                step.reasoningContent ?: "",
                                isLast = isLast,
                                onClick = {
                                    detailTitle = "深度思考"
                                    detailBody = step.reasoningContent ?: ""
                                },
                            )
                        } else {
                            ToolStep(
                                step,
                                isLast = isLast,
                                onClick = {
                                    detailTitle = step.toolName ?: "工具结果"
                                    detailBody = buildToolDetail(step)
                                },
                            )
                        }
                        if (!isLast) Spacer(Modifier.height(8.dp))
                    }
                }
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

private fun buildToolDetail(step: Message): String {
    val args = step.toolArgs ?: ""
    val result = step.content.ifBlank { "(空)" }
    return if (args.isNotBlank()) {
        "参数:\n$args\n\n结果:\n$result"
    } else {
        "结果:\n$result"
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ---- 时间线步骤外壳：左侧图标列 + 右侧内容，整行可点 ----
@Composable
private fun TimelineStep(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    isLast: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent,
        label = "stepPress",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppRadii.card)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(HapticsType.Light)
                onClick()
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(34.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(cs.surface.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp))
            }
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.5.dp)
                        .height(48.dp)
                        .background(cs.outlineVariant.copy(alpha = 0.35f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    tint = cs.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ---- 工具步骤：名称 + 参数 + 结果（点击弹出详情） ----
@Composable
private fun ToolStep(step: Message, isLast: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    val argsText = remember(step.toolArgs) {
        val json = step.toolArgs ?: ""
        try {
            val obj = com.google.gson.Gson().fromJson(json, Map::class.java)
            obj.entries.joinToString("\n") { (k, v) -> "• $k: $v" }
        } catch (_: Exception) {
            json
        }
    }

    TimelineStep(
        icon = Icons.Outlined.BuildCircle,
        iconTint = cs.primary,
        title = step.toolName ?: "工具调用",
        isLast = isLast,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (argsText.isNotBlank()) {
                MonoPreview("参数", argsText, cs)
            }
            MonoPreview("结果", step.content.ifBlank { "(空)" }, cs)
        }
    }
}

// ---- 推理步骤：深度思考（点击弹出详情） ----
@Composable
private fun ReasoningStep(reasoning: String, isLast: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TimelineStep(
        icon = Icons.Outlined.Psychology,
        iconTint = cs.tertiary,
        title = "深度思考",
        isLast = isLast,
        onClick = onClick,
    ) {
        MonoPreview("", reasoning, cs)
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
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

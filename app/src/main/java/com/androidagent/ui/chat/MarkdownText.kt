package com.androidagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 简单的 Markdown 文本渲染组件
 * 支持：**加粗** *斜体* `行内代码` ```代码块``` [链接](url)
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 22.sp
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.tertiary
    val linkColor = MaterialTheme.colorScheme.primary

    // 按代码块分段处理（代码块内不解析其他格式）
    val segments = splitByCodeFences(markdown)

    Column(modifier = modifier) {
        for (segment in segments) {
            when (segment.type) {
                SegmentType.CODE_BLOCK -> {
                    // 代码块：等宽字体 + 背景色
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBackground)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = segment.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = codeColor
                        )
                    }
                }
                SegmentType.TEXT -> {
                    // 内联格式解析
                    val lines = segment.content.split("\n")
                    for ((i, line) in lines.withIndex()) {
                        if (line.isBlank()) {
                            if (i < lines.size - 1) Spacer(Modifier.height(4.dp))
                            continue
                        }
                        renderLine(
                            line = line,
                            baseColor = baseColor,
                            codeBackground = codeBackground,
                            codeColor = codeColor,
                            linkColor = linkColor,
                            fontSize = fontSize,
                            lineHeight = lineHeight
                        )
                    }
                }
            }
        }
    }
}

private enum class SegmentType { TEXT, CODE_BLOCK }

private data class Segment(val type: SegmentType, val content: String)

/**
 * 按 ```代码块``` 分割文本
 */
private fun splitByCodeFences(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    val fence = "```"
    var pos = 0
    var inCode = false
    val sb = StringBuilder()

    while (pos < text.length) {
        val idx = text.indexOf(fence, pos)
        if (idx == -1) {
            sb.append(text.substring(pos))
            break
        }

        if (!inCode) {
            // fence 前是普通文本
            sb.append(text.substring(pos, idx))
            pos = idx + 3
            inCode = true
        } else {
            // fence 后是代码块内容
            sb.append(text.substring(pos, idx))
            pos = idx + 3
            inCode = false
        }
    }

    // 解析交替的 TEXT / CODE_BLOCK
    val raw = sb.toString()
    val parts = raw.split("\u0000")  // 用空字符作为占位
    // 实际上我们需要重新解析

    // 更简单的方法：直接遍历
    segments.clear()
    var i = 0
    var isCode = false
    val currentText = StringBuilder()

    while (i < text.length) {
        val fIdx = text.indexOf(fence, i)
        if (fIdx == -1) {
            if (currentText.isNotEmpty() || !isCode) {
                segments.add(Segment(if (isCode) SegmentType.CODE_BLOCK else SegmentType.TEXT, text.substring(i)))
            } else if (isCode) {
                segments.add(Segment(SegmentType.CODE_BLOCK, text.substring(i)))
            }
            break
        }

        if (!isCode) {
            // 普通文本
            if (fIdx > i) {
                segments.add(Segment(SegmentType.TEXT, text.substring(i, fIdx)))
            }
            i = fIdx + 3
            isCode = true
        } else {
            // 代码块内容
            segments.add(Segment(SegmentType.CODE_BLOCK, text.substring(i, fIdx)))
            i = fIdx + 3
            isCode = false
        }
    }

    // 如果以代码块结束
    if (isCode && i < text.length) {
        segments.add(Segment(SegmentType.CODE_BLOCK, text.substring(i)))
    }

    return segments
}

/**
 * 渲染一行文本，解析内联格式
 */
@Composable
private fun renderLine(
    line: String,
    baseColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit
) {
    val trimmed = line.trimStart()

    when {
        // 标题 # ~ ######
        trimmed.startsWith("###### ") -> {
            HeadingText(trimmed.removePrefix("###### "), 5, baseColor)
        }
        trimmed.startsWith("##### ") -> {
            HeadingText(trimmed.removePrefix("##### "), 4, baseColor)
        }
        trimmed.startsWith("#### ") -> {
            HeadingText(trimmed.removePrefix("#### "), 3, baseColor)
        }
        trimmed.startsWith("### ") -> {
            HeadingText(trimmed.removePrefix("### "), 2, baseColor)
        }
        trimmed.startsWith("## ") -> {
            HeadingText(trimmed.removePrefix("## "), 1, baseColor)
        }
        trimmed.startsWith("# ") -> {
            HeadingText(trimmed.removePrefix("# "), 0, baseColor)
        }
        // 无序列表 - 或 *
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text("•  ", fontSize = fontSize, lineHeight = lineHeight, color = baseColor)
                InlineFormattedText(
                    text = trimmed.drop(2),
                    baseColor = baseColor,
                    codeColor = codeColor,
                    codeBackground = codeBackground,
                    linkColor = linkColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
            }
        }
        // 有序列表 1. 2. 3.
        trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
            val num = trimmed.substringBefore(".")
            val rest = trimmed.substringAfter(". ").trimStart()
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text("$num. ", fontSize = fontSize, lineHeight = lineHeight, color = baseColor)
                InlineFormattedText(
                    text = rest,
                    baseColor = baseColor,
                    codeColor = codeColor,
                    codeBackground = codeBackground,
                    linkColor = linkColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
            }
        }
        // 分隔线 ---
        trimmed.matches(Regex("^-{3,}$")) -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(baseColor.copy(alpha = 0.2f))
            )
        }
        // 普通文本
        else -> {
            InlineFormattedText(
                text = line,
                baseColor = baseColor,
                codeColor = codeColor,
                codeBackground = codeBackground,
                linkColor = linkColor,
                fontSize = fontSize,
                lineHeight = lineHeight
            )
        }
    }
}

/**
 * 标题文本
 */
@Composable
private fun HeadingText(
    text: String,
    level: Int,  // 0=h1, 1=h2, ...
    baseColor: androidx.compose.ui.graphics.Color
) {
    val sizes = listOf(22.sp, 20.sp, 18.sp, 17.sp, 16.sp, 15.sp)
    val size = sizes.getOrElse(level) { 15.sp }
    Text(
        text = text,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        lineHeight = size * 1.4f,
        color = baseColor,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/**
 * 内联格式文本（**粗体** *斜体* `代码` [链接](url)）
 */
@Composable
private fun InlineFormattedText(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit
) {
    val annotated = parseInline(text, baseColor, codeColor, linkColor)
    Text(
        text = annotated,
        fontSize = fontSize,
        lineHeight = lineHeight
    )
}

/**
 * 解析内联格式，返回 AnnotatedString
 */
private fun parseInline(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // 行内代码 `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = codeColor,
                        fontSize = 13.sp
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // 加粗 **text**
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // 斜体 *text*
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // 链接 [text](url)
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val linkText = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        withStyle(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        append(" 🔗")
                        i = closeParen + 1
                    } else {
                        append(text[i])
                        i++
                    }
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

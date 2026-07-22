package com.androidagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 简单的 Markdown 文本渲染组件
 * 支持：**加粗** *斜体* `行内代码` ```代码块``` [链接](url) | 表格 |
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.tertiary
    val linkColor = MaterialTheme.colorScheme.primary

    // 先按代码块分割
    val codeBlocks = splitCodeBlocks(markdown)

    Column(modifier = modifier) {
        for (codeBlock in codeBlocks) {
            if (codeBlock.isCode) {
                // 代码块：等宽 + 背景
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(codeBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = codeBlock.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = codeColor
                    )
                }
            } else {
                // 普通文本：先提取表格，再逐行渲染
                val segments = splitTableBlocks(codeBlock.text)
                for (segment in segments) {
                    when (segment) {
                        is Segment.Table -> {
                            renderTable(segment.rows, baseColor, codeColor, linkColor, fontSize)
                        }
                        is Segment.Text -> {
                            val lines = segment.text.split("\n")
                            for ((idx, line) in lines.withIndex()) {
                                if (line.isBlank()) {
                                    if (idx < lines.lastIndex) Spacer(Modifier.height(4.dp))
                                    continue
                                }
                                renderLine(line.trimStart(), baseColor, codeColor, linkColor, fontSize, lineHeight)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Block(val text: String, val isCode: Boolean)

/** 按 ``` 分割文本，交替返回 TEXT / CODE_BLOCK */
private fun splitCodeBlocks(text: String): List<Block> {
    val result = mutableListOf<Block>()
    val fence = "```"
    var start = 0
    var lookingForCode = false

    while (true) {
        val idx = text.indexOf(fence, start)
        if (idx == -1) {
            result.add(Block(text.substring(start), lookingForCode))
            break
        }
        if (!lookingForCode) {
            if (idx > start) result.add(Block(text.substring(start, idx), false))
            start = idx + 3
            lookingForCode = true
        } else {
            result.add(Block(text.substring(start, idx), true))
            start = idx + 3
            lookingForCode = false
        }
    }
    return result
}

// ==================== 表格支持 ====================

private sealed class Segment {
    data class Text(val text: String) : Segment()
    data class Table(val rows: List<List<String>>) : Segment()
}

/** 从普通文本中提取连续的 Markdown 表格行 */
private fun splitTableBlocks(text: String): List<Segment> {
    val result = mutableListOf<Segment>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("|") && line.endsWith("|") &&
            i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|[\\s-:|]+\\|$"))) {
            // 发现表格：收集所有连续表格行
            val tableRows = mutableListOf<List<String>>()
            // 第一行：表头
            tableRows.add(parseTableRow(line))
            i++
            // 第二行：分隔符（跳过）
            i++
            // 后续行：数据行
            while (i < lines.size) {
                val dataLine = lines[i].trim()
                if (dataLine.startsWith("|") && dataLine.endsWith("|")) {
                    tableRows.add(parseTableRow(dataLine))
                    i++
                } else break
            }
            result.add(Segment.Table(tableRows))
        } else {
            val textLines = mutableListOf<String>()
            textLines.add(lines[i])
            i++
            // 收集非表格行
            while (i < lines.size) {
                val next = lines[i].trim()
                val isTableStart = next.startsWith("|") && next.endsWith("|") &&
                    i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|[\\s-:|]+\\|$"))
                if (isTableStart) break
                textLines.add(lines[i])
                i++
            }
            result.add(Segment.Text(textLines.joinToString("\n")))
        }
    }
    return result
}

/** 解析一行表格数据：| a | b | c | → ["a", "b", "c"] */
private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    val content = if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
    val endTrimmed = if (content.endsWith("|")) content.substring(0, content.length - 1) else content
    return endTrimmed.split("|").map { it.trim() }
}

/** 渲染表格 */
@Composable
private fun renderTable(
    rows: List<List<String>>,
    baseColor: Color,
    codeColor: Color,
    linkColor: Color,
    fontSize: TextUnit
) {
    if (rows.isEmpty()) return
    val headerBg = baseColor.copy(alpha = 0.1f)
    val borderColor = baseColor.copy(alpha = 0.25f)
    val maxCols = rows.maxOfOrNull { it.size } ?: 1
    val colWidths = computeColWidths(rows, fontSize)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(borderColor)
            .padding(1.dp) // 细边框效果
    ) {
        rows.forEachIndexed { rowIdx, row ->
            val isHeader = rowIdx == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isHeader) headerBg else Color.Transparent)
            ) {
                for (colIdx in 0 until maxCols) {
                    val cellText = row.getOrElse(colIdx) { "" }
                    val width = colWidths.getOrElse(colIdx) { 80.dp }
                    Box(
                        modifier = Modifier
                            .width(width)
                            .padding(horizontal = 4.dp)
                            .then(
                                if (colIdx < maxCols - 1) Modifier.border(
                                    width = 0.5.dp,
                                    color = borderColor,
                                    shape = RoundedCornerShape(0.dp)
                                ) else Modifier
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cellText,
                            fontSize = if (isHeader) (fontSize.value - 1).sp else (fontSize.value - 1).sp,
                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                            color = baseColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 计算每列的最大宽度（基于字符数） */
@Composable
private fun computeColWidths(rows: List<List<String>>, fontSize: TextUnit): List<androidx.compose.ui.unit.Dp> {
    val maxCols = rows.maxOfOrNull { it.size } ?: 1
    val charWidth = fontSize.value * 0.6f // 近似字符宽度
    return (0 until maxCols).map { col ->
        val maxLen = rows.mapNotNull { it.getOrNull(col) }.maxOfOrNull { it.length } ?: 4
        (maxLen.coerceAtLeast(3) * charWidth + 16).dp
    }
}

/** 渲染一行文本（标题/列表/普通） */
@Composable
private fun renderLine(
    line: String,
    baseColor: Color,
    codeColor: Color,
    linkColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val trimmed = line.trimStart()

    when {
        // 标题
        trimmed.startsWith("###### ") -> heading(trimmed.drop(7), 5, baseColor)
        trimmed.startsWith("##### ")  -> heading(trimmed.drop(6), 4, baseColor)
        trimmed.startsWith("#### ")   -> heading(trimmed.drop(5), 3, baseColor)
        trimmed.startsWith("### ")    -> heading(trimmed.drop(4), 2, baseColor)
        trimmed.startsWith("## ")     -> heading(trimmed.drop(3), 1, baseColor)
        trimmed.startsWith("# ")      -> heading(trimmed.drop(2), 0, baseColor)
        // 无序列表
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
            Row(Modifier.padding(start = 8.dp)) {
                Text("• ", color = baseColor, fontSize = fontSize, lineHeight = lineHeight)
                inlineText(trimmed.drop(2), baseColor, codeColor, linkColor, fontSize, lineHeight)
            }
        }
        // 有序列表
        trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
            val num = trimmed.substringBefore(".")
            Row(Modifier.padding(start = 8.dp)) {
                Text("$num. ", color = baseColor, fontSize = fontSize, lineHeight = lineHeight)
                inlineText(trimmed.substringAfter(". "), baseColor, codeColor, linkColor, fontSize, lineHeight)
            }
        }
        // 分割线
        trimmed.matches(Regex("^-{3,}$")) -> {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(baseColor.copy(alpha = 0.2f)))
        }
        // 普通文本
        else -> inlineText(line, baseColor, codeColor, linkColor, fontSize, lineHeight)
    }
}

@Composable
private fun heading(text: String, level: Int, baseColor: Color) {
    val sizes = listOf(22.sp, 20.sp, 18.sp, 17.sp, 16.sp, 15.sp)
    Text(
        text = text,
        fontSize = sizes.getOrElse(level) { 15.sp },
        fontWeight = FontWeight.Bold,
        lineHeight = sizes.getOrElse(level) { 15.sp } * 1.4f,
        color = baseColor,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun inlineText(
    text: String,
    baseColor: Color,
    codeColor: Color,
    linkColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    Text(
        text = parseInline(text, baseColor, codeColor, linkColor),
        fontSize = fontSize,
        lineHeight = lineHeight
    )
}

/** 解析内联格式：**bold** *italic* `code` [text](url) */
private fun parseInline(
    text: String,
    baseColor: Color,
    codeColor: Color,
    linkColor: Color
) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // 行内代码 `code`
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, fontSize = 13.sp)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // **bold**
            i + 1 < text.length && c == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            // *italic*
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            // [text](url)
            c == '[' -> {
                val closeB = text.indexOf(']', i + 1)
                if (closeB != -1 && closeB + 1 < text.length && text[closeB + 1] == '(') {
                    val closeP = text.indexOf(')', closeB + 2)
                    if (closeP != -1) {
                        val linkText = text.substring(i + 1, closeB)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(linkText)
                        }
                        append(" 🔗")
                        i = closeP + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

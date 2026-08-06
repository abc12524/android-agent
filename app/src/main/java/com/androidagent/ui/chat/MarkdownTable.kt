package com.androidagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 将一条 Markdown 消息拆分为「表格块」和「普通文本块」。
 * 表格由自研组件按内容自适应列宽渲染（超出气泡宽度时可左右滑动），
 * 其余内容仍交由 MarkdownText 正常折行。
 */
internal sealed class MdBlock {
    data class Text(val text: String) : MdBlock()
    data class Table(val table: MdTable) : MdBlock()
}

internal data class MdTable(
    val headers: List<String>,
    val alignments: List<TextAlign>,
    val rows: List<List<String>>
) {
    val columnCount: Int = headers.size
}

internal fun markdownBlocks(content: String): List<MdBlock> {
    val lines = content.lines()
    val blocks = mutableListOf<MdBlock>()
    val textBuf = StringBuilder()
    var inCodeFence = false

    fun flushText() {
        if (textBuf.isNotBlank()) {
            blocks.add(MdBlock.Text(textBuf.toString().trimEnd()))
            textBuf.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            inCodeFence = !inCodeFence
            textBuf.appendLine(line)
            i++
            continue
        }
        if (inCodeFence) {
            textBuf.appendLine(line)
            i++
            continue
        }
        val table = parseMdTable(lines, i)
        if (table != null) {
            flushText()
            blocks.add(MdBlock.Table(table))
            i = table.nextIndex
        } else {
            textBuf.appendLine(line)
            i++
        }
    }
    flushText()
    return blocks
}

private data class ParsedTable(
    val headers: List<String>,
    val alignments: List<TextAlign>,
    val rows: List<List<String>>,
    val nextIndex: Int
)

/**
 * 解析 GFM 表格：表头行 + 分隔行(纯 - / : ) + 连续正文行。
 * 返回 null 表示当前行不是表格。
 */
private fun parseMdTable(lines: List<String>, start: Int): ParsedTable? {
    if (start + 1 >= lines.size) return null
    val header = splitRow(lines[start]) ?: return null
    val delim = splitRow(lines[start + 1]) ?: return null
    if (header.size < 2 || delim.size < 2) return null
    if (delim.any { !it.matches(Regex(":?-+:?")) }) return null

    val alignments = delim.map { d ->
        when {
            d.startsWith(":") && d.endsWith(":") -> TextAlign.Center
            d.endsWith(":") -> TextAlign.Right
            else -> TextAlign.Left
        }
    }

    val maxCols = maxOf(header.size, delim.size)
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size) {
        val cells = splitRow(lines[i])
        if (cells == null) {
            if (lines[i].isBlank()) { i++; continue }
            break
        }
        rows.add(cells)
        i++
    }
    if (rows.isEmpty()) return null

    val normalized: (List<String>) -> List<String> = { row ->
        List(maxCols) { idx -> if (idx < row.size) row[idx] else "" }
    }

    return ParsedTable(
        headers = normalized(header),
        alignments = alignments,
        rows = rows.map(normalized),
        nextIndex = i
    )
}

private fun splitRow(line: String): List<String>? {
    val t = line.trim()
    if (!t.contains('|')) return null
    val cells = t.split('|').map { it.trim() }.toMutableList()
    while (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
    while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
    if (cells.size < 2) return null
    return cells
}

/** 去掉单元格内的行内 Markdown 标记，仅保留纯文本用于展示与测量。 */
private fun stripInlineMarkdown(text: String): String {
    var s = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("`([^`]*)`"), "$1")
    s = s.replace("**", "").replace("__", "").replace("~~", "")
    return s.replace("`", "").replace("*", "").replace("_", "")
}

/**
 * 按内容自适应列宽渲染表格。
 * 列宽 = 该列所有单元格单行自然宽度 + 内边距，超出气泡宽度时整体可左右滑动。
 */
@Composable
internal fun MarkdownTable(
    table: MdTable,
    textStyle: TextStyle
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPadding = 8.dp
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    val columnWidths = remember(table, textMeasurer, textStyle, density, cellPadding) {
        val boldStyle = textStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val px = FloatArray(table.columnCount)
        fun consider(text: String, col: Int, style: TextStyle) {
            if (col >= px.size) return
            val width = textMeasurer.measure(
                text = text,
                style = style
            ).size.width
            if (width > px[col]) px[col] = width.toFloat()
        }
        table.headers.forEachIndexed { i, h -> consider(stripInlineMarkdown(h), i, boldStyle) }
        table.rows.forEach { row -> row.forEachIndexed { i, c -> consider(stripInlineMarkdown(c), i, textStyle) } }
        px.map { with(density) { (it / density.density).dp + cellPadding * 2 + 1.dp } }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            Row {
                table.headers.forEachIndexed { i, cell ->
                    Cell(
                        text = stripInlineMarkdown(cell),
                        columnWidth = columnWidths[i],
                        textStyle = textStyle,
                        fontWeight = FontWeight.Bold,
                        textAlign = table.alignments.getOrElse(i) { TextAlign.Left },
                        background = headerBg,
                        borderColor = borderColor,
                        cellPadding = cellPadding
                    )
                }
            }
            table.rows.forEach { row ->
                Row {
                    row.forEachIndexed { i, cell ->
                        Cell(
                            text = stripInlineMarkdown(cell),
                            columnWidth = columnWidths[i],
                            textStyle = textStyle,
                            fontWeight = FontWeight.Normal,
                            textAlign = table.alignments.getOrElse(i) { TextAlign.Left },
                            background = null,
                            borderColor = borderColor,
                            cellPadding = cellPadding
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    columnWidth: Dp,
    textStyle: TextStyle,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    background: Color?,
    borderColor: Color,
    cellPadding: Dp
) {
    Box(
        modifier = Modifier
            .width(columnWidth)
            .border(0.5.dp, borderColor)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .padding(horizontal = cellPadding, vertical = 4.dp),
        contentAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.Center
            TextAlign.Right -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        Text(
            text = text,
            style = textStyle.copy(fontSize = 15.sp, fontWeight = fontWeight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

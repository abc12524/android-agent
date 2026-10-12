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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

// ==================== Markdown 渲染 ====================

@Composable
internal fun MarkdownBody(content: String, fillWidth: Boolean = true) {
    val components = remember {
        markdownComponents(table = { model -> AutoSizedTable(model) })
    }
    val state = rememberMarkdownState(content = content, retainState = true)
    val typography = chatMarkdownTypography()
    SelectionContainer {
        Markdown(
            markdownState = state,
            components = components,
            typography = typography,
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/** 收窄 m3 默认的标题字号（默认 h1=displayLarge=57sp，正文里过大）。 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography {
    val base = MaterialTheme.typography.bodyLarge
    return markdownTypography(
        h1 = base.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        h2 = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        h3 = base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        h4 = base.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        h5 = base.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        h6 = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** 内容自适应的 markdown 表格：外边框 + 行分隔线，列宽按单元格文本估算。 */
@Composable
private fun AutoSizedTable(model: MarkdownComponentModel) {
    val cs = MaterialTheme.colorScheme
    val line = cs.outline.copy(alpha = 0.6f)
    val headerBg = cs.surfaceVariant.copy(alpha = 0.55f)
    val bodyBg = cs.surfaceVariant.copy(alpha = 0.22f)
    val shape = RoundedCornerShape(10.dp)
    val src = model.content
    val style = model.typography.table
    val density = LocalDensity.current

    val rows = remember(model.node) {
        model.node.children
            .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } }
    }
    val colCount = rows.maxOfOrNull { it.size } ?: 0

    val colWidths = remember(model.node, src, density) {
        val em = with(density) { 14.sp.toPx() }
        val pad = with(density) { 26.dp.toPx() }
        val minW = with(density) { 44.dp.toPx() }
        val maxW = with(density) { 260.dp.toPx() }
        Array(colCount) { c ->
            var w = em * 2f
            rows.forEach { r ->
                r.getOrNull(c)?.let { cell ->
                    var cw = 0f
                    for (ch in src.substring(cell.startOffset, cell.endOffset)) {
                        cw += if (ch.code > 0x2E80) em else em * 0.55f
                    }
                    if (cw > w) w = cw
                }
            }
            (w + pad).coerceIn(minW, maxW)
        }
    }

    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(
            Modifier
                .border(1.dp, line, shape)
                .clip(shape)
                .background(bodyBg),
        ) {
            rows.forEachIndexed { ri, cells ->
                Row(
                    Modifier
                        .background(if (ri == 0) headerBg else Color.Transparent)
                        .drawBehind {
                            val stroke = 0.8f.dp.toPx()
                            var x = 0f
                            for (i in 0 until cells.size - 1) {
                                x += colWidths.getOrElse(i) { 0f }
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
                            }
                            if (ri != rows.lastIndex) {
                                val y = size.height - stroke / 2f
                                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                            }
                        },
                ) {
                    cells.forEachIndexed { ci, cell ->
                        Box(
                            Modifier
                                .width(with(density) { colWidths.getOrElse(ci) { 0f }.toDp() })
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            MarkdownTableBasicText(
                                content = src,
                                cell = cell,
                                style = if (ri == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}

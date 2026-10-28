package com.androidagent.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAlertColors
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

// ==================== Markdown 渲染 ====================
//
// 视觉对齐 DeepSeek 2.7.1 官方客户端：
//   正文 16sp / 行高 1.75 / 段间距 12dp
//   代码块 底 #F7F7F8、圆角 12dp、无边框；头部高 48dp（左语言标签 13sp、右「复制」）+ 1dp 内缩分隔线
//   代码正文 等宽 13sp / 行高 22sp，语法高亮
//   行内代码 浅灰 chip；分隔线 90% 居中；表格自定义列宽

@Composable
internal fun MarkdownBody(content: String, fillWidth: Boolean = true) {
    val media = remember(content) { MediaParser.parse(content) }
    val components = remember {
        markdownComponents(
            codeFence = { model -> ChatCodeBlock(model, fence = true) },
            codeBlock = { model -> ChatCodeBlock(model, fence = false) },
            horizontalRule = { RuleDivider() },
            table = { model -> AutoSizedTable(model) },
        )
    }
    val state = rememberMarkdownState(content = media.text, retainState = true)
    val typography = chatMarkdownTypography()
    val colors = chatMarkdownColors()
    val padding = chatMarkdownPadding()
    val dimens = markdownDimens(
        dividerThickness = 1.dp,
        codeBackgroundCornerSize = 12.dp,
        blockQuoteThickness = 2.dp,
    )
    Column(
        modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (media.text.isNotBlank()) {
            SelectionContainer {
                Markdown(
                    markdownState = state,
                    components = components,
                    typography = typography,
                    colors = colors,
                    padding = padding,
                    dimens = dimens,
                    modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
                )
            }
        }
        media.images.forEach { item ->
            MediaImage(
                source = item.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
        media.audios.forEach { item ->
            AudioAttachmentPlayer(source = item.source, label = item.label)
        }
    }
}

/** 正文/列表 16sp·行高 1.75；代码等宽 13/22；标题收窄到 22 以下，避免 m3 默认 57sp 撑爆正文 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography {
    val cs = MaterialTheme.colorScheme
    val body = TextStyle(
        fontSize = 16.sp,
        lineHeight = 28.sp,
        color = cs.onSurface,
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 22.sp,
        color = cs.onSurface,
    )
    return markdownTypography(
        text = body,
        paragraph = body,
        list = body,
        bullet = body.copy(color = cs.onSurface.copy(alpha = 0.75f)),
        ordered = body.copy(color = cs.onSurface.copy(alpha = 0.75f)),
        quote = body.copy(color = cs.onSurfaceVariant),
        code = mono,
        inlineCode = mono.copy(fontSize = 14.5.sp, lineHeight = 22.sp),
        h1 = body.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        h2 = body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        h3 = body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        h4 = body.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        h5 = body.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        h6 = body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        table = body.copy(fontSize = 14.sp, lineHeight = 21.sp),
    )
}

/** 代码块底色 / 行内 chip 底色 / 分隔线颜色 */
@Composable
private fun chatMarkdownColors(): MarkdownColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return DefaultMarkdownColors(
        text = cs.onSurface,
        codeBackground = if (dark) Color(0xFF1E1E20) else Color(0xFFF7F7F8),
        inlineCodeBackground = if (dark) Color(0xFF26262A) else Color(0xFFF2F2F3),
        dividerColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFEAEAEC),
        tableBackground = Color.Transparent,
        alert = markdownAlertColors(),
    )
}

/** 块间距 12dp（官方段落间距），列表缩进 18dp */
@Composable
private fun chatMarkdownPadding(): MarkdownPadding = markdownPadding(
    block = 12.dp,
    list = 6.dp,
    listItemTop = 4.dp,
    listItemBottom = 4.dp,
    listIndent = 18.dp,
    codeBlock = PaddingValues(0.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 2.dp),
)

// ==================== 代码块 ====================

@Composable
private fun ChatCodeBlock(model: MarkdownComponentModel, fence: Boolean) {
    val style = model.typography.code
    if (fence) {
        MarkdownCodeFence(model.content, model.node, style) { code, language, st ->
            CodeSurface(code, language, st)
        }
    } else {
        MarkdownCodeBlock(model.content, model.node, style) { code, language, st ->
            CodeSurface(code, language, st)
        }
    }
}

/** 代码块外壳：头部（语言 + 复制）+ 分隔线 + 高亮正文 */
@Composable
private fun CodeSurface(code: String, language: String?, style: TextStyle) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val bg = if (dark) Color(0xFF1E1E20) else Color(0xFFF7F7F8)
    val labelColor = cs.onSurface.copy(alpha = 0.45f)
    val hairline = cs.onSurface.copy(alpha = if (dark) 0.10f else 0.07f)

    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val highlighted = remember(code, language, dark) { highlightCode(code, language, dark) }
    val label = language?.trim()?.takeIf { it.isNotBlank() } ?: "代码"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
    ) {
        // 头部：语言标签（官方小写、13sp、灰）+ 右侧复制
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                        scope.launch {
                            delay(1500)
                            copied = false
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CopyGlyph(tint = labelColor, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (copied) "已复制" else "复制",
                    fontSize = 13.sp,
                    color = labelColor,
                )
            }
        }

        // 头身分隔线：左右内缩，与正文内边距对齐
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(1.dp)
                .background(hairline),
        )

        Text(
            text = highlighted,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** 复制图标（两个圆角矩形叠角），不引 material-icons-extended */
@Composable
private fun CopyGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        val r = w * 0.18f
        // 后面那张纸（右上）
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.34f, h * 0.10f),
            size = Size(w * 0.56f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // 前面那张纸（左下）
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.34f),
            size = Size(w * 0.56f, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** snipme/highlights：按语言着色，未知语言按纯文本处理 */
private fun highlightCode(code: String, language: String?, dark: Boolean): AnnotatedString {
    val syntax = language
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { SyntaxLanguage.getByName(it) }.getOrNull() }
    return runCatching {
        val builder = Highlights.Builder().theme(SyntaxThemes.default(darkMode = dark))
        val spans = builder.code(code)
            .let { if (syntax != null) it.language(syntax) else it }
            .build()
            .getHighlights()
        buildAnnotatedString {
            append(code)
            spans.forEach { span ->
                val style = when (span) {
                    is ColorHighlight -> SpanStyle(color = Color(span.rgb))
                    is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                val start = span.location.start.coerceIn(0, code.length)
                val end = span.location.end.coerceIn(start, code.length)
                if (start < end) addStyle(style, start, end)
            }
        }
    }.getOrElse { AnnotatedString(code) }
}

/** 分隔线：官方是 90% 宽、居中、1dp */
@Composable
private fun RuleDivider() {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(0.9f)
                .height(1.dp)
                .background(color),
        )
    }
}

// ==================== 表格 ====================

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

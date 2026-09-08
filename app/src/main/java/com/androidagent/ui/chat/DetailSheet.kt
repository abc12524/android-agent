package com.androidagent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通用「完整详情」底部面板 — 对标 kelivo 的 showModalBottomSheet
 * （约 60% 高、可滚动/选中全文、顶部拖拽把手）。
 *
 * 供工具执行卡、思考卡、OV 自动注入卡等复用：点击对应卡片 → 弹出完整正文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailSheet(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SelectionContainer {
                Text(
                    body,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

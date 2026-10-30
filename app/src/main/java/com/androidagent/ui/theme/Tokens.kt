package com.androidagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌 — 对标 kelivo-agent 的 design_tokens.dart
 * 统一管理圆角 / 间距 / 阴影 / 触觉，让全局观感保持一致。
 */
object AppRadii {
    val card = RoundedCornerShape(14.dp)
    val capsule = RoundedCornerShape(28.dp)
    val bubble = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )
    val timelineCard = RoundedCornerShape(16.dp)

    // ---- 新增：设置页结构件 ----
    /** 设置分组卡（比聊天卡更圆润，配合页面灰底更立体） */
    val group = RoundedCornerShape(18.dp)
    /** 行内图标瓦片 */
    val tile = RoundedCornerShape(10.dp)
    /** 表单输入容器 */
    val field = RoundedCornerShape(12.dp)
    /** 小标签 / 徽章 */
    val tag = RoundedCornerShape(6.dp)
}

object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object AppElevation {
    /** 柔和浮层高度（对标 Flutter BoxShadow alpha 0.05 / blur 18 / offset 0,6） */
    val soft = 2.dp
    val card = 1.dp
    /** 设置分组卡：仅在浅色下可见的极浅浮起，深色下靠描边区分 */
    val group = 1.dp
}

/** 按压/浮层通用的柔和投影曲线（供 Surface / Card 使用） */
object AppShadows {
    val soft = Shadow(
        color = Color.Black.copy(alpha = 0.05f),
        blurRadius = 18f,
        offset = androidx.compose.ui.geometry.Offset(0f, 6f),
    )
}

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
}

object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
}

object AppElevation {
    /** 柔和浮层高度（对标 Flutter BoxShadow alpha 0.05 / blur 18 / offset 0,6） */
    val soft = 2.dp
    val card = 1.dp
}

/** 按压/浮层通用的柔和投影曲线（供 Surface / Card 使用） */
object AppShadows {
    val soft = Shadow(
        color = Color.Black.copy(alpha = 0.05f),
        blurRadius = 18f,
        offset = androidx.compose.ui.geometry.Offset(0f, 6f),
    )
}

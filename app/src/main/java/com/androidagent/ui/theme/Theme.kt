package com.androidagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 浅色：聊天底纯白，设置底 #F5F5F6，品牌蓝 #426EFE
 * 深色：聊天底 #151517，卡片 #1E1E21，主色提亮到 #7B93FF
 */
private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandInk,
    secondary = Ink3,
    onSecondary = Color.White,
    tertiary = Brand,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = FieldBg,
    onSurfaceVariant = Ink3,
    outline = Color(0xFFE3E3E6),
    outlineVariant = HairLine,
    error = Color(0xFFD93025),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF0B0B0D),
    primaryContainer = Color(0xFF262A3A),
    onPrimaryContainer = Color(0xFFC7D0FF),
    secondary = InkDark3,
    onSecondary = Color(0xFF0B0B0D),
    tertiary = BrandDark,
    background = DarkBg,
    onBackground = InkDark,
    surface = DarkSurface,
    onSurface = InkDark,
    surfaceVariant = DarkField,
    onSurfaceVariant = InkDark3,
    outline = Color(0xFF3A3A40),
    outlineVariant = DarkHair,
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF1A0A08),
)

/** 正文 16sp / 行高 26sp，与截图里的消息行距一致 */
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun AndroidAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 关闭 Material You 取色：品牌色必须固定为 #426EFE，否则壁纸会把它染掉
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

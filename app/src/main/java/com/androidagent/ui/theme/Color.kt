package com.androidagent.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DeepSeek 2.7.1 界面配色
 * 取色来源：APK `res/values/colors.xml`（primary = #426EFE）+ 真机截图
 * 命名按使用场景分组，避免"蓝 40 / 灰 80"这种看不出用途的名字。
 */

// ---------- 品牌 ----------
val Brand = Color(0xFF426EFE)
val BrandInk = Color(0xFF3D63F0)   // 浅蓝胶囊上的文字
val BrandSoft = Color(0xFFEDF1FF)  // 浅蓝胶囊底色
val BrandDark = Color(0xFF7B93FF)  // 深色模式主色

// ---------- 浅色灰阶 ----------
val Ink = Color(0xFF0F0F0F)        // 主文本
val Ink2 = Color(0xFF1A1A1A)       // 正文
val Ink3 = Color(0xFF8A8A8E)       // 次要文本 / 分组标题 / 右侧值
val Ink4 = Color(0xFFB0B0B5)       // 更弱（占位、页脚）
val HairLine = Color(0xFFEFEFF1)   // 发丝线
val SettingsBg = Color(0xFFF5F5F6) // 设置页底色
val FieldBg = Color(0xFFF4F4F5)    // 输入框 / 搜索框底
val CodeBg = Color(0xFFF7F7F8)     // 代码块底
val UserBubbleBg = Color(0xFFE8EEFC) // 用户消息气泡

// ---------- 深色灰阶 ----------
val InkDark = Color(0xFFECECEF)
val InkDark3 = Color(0xFF9A9AA1)
val DarkBg = Color(0xFF151517)
val DarkSurface = Color(0xFF1E1E21)
val DarkField = Color(0xFF2A2A2E)
val DarkHair = Color(0xFF2C2C31)
val UserBubbleBgDark = Color(0xFF253052)

// ---------- 兼容聊天页既有引用 ----------
val BubbleUser = UserBubbleBg
val BubbleUserText = Ink
val BubbleAssistant = CodeBg
val BubbleAssistantText = Ink2
val BubbleTool = Color(0xFFFFF3E0)
val BubbleToolText = Color(0xFFE65100)

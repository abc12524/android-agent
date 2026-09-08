package com.androidagent.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 柔和触感反馈 — 对标 kelivo-agent 的 core/services/haptics.dart。
 * 只在手势/按压发生时才触发，避免干扰；统一走系统 HapticFeedback。
 */
enum class HapticsType { Light, Medium }

@Composable
fun rememberHaptics(): (HapticsType) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { type -> view.performHapticFeedback(hapticConstant(type)) }
    }
}

private fun hapticConstant(type: HapticsType): Int = when (type) {
    HapticsType.Light -> HapticFeedbackConstants.KEYBOARD_TAP
    HapticsType.Medium -> HapticFeedbackConstants.VIRTUAL_KEY
}

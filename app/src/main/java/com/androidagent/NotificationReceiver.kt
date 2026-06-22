package com.androidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.androidagent.data.tools.NotificationTool

/**
 * 通知广播接收器
 * 接收 am broadcast 触发的通知显示请求
 *
 * 触发示例:
 *   am broadcast -a com.androidagent.action.SHOW_NOTIFICATION \
 *       --es title "提醒" --es content "该吃饭了" \
 *       --es priority high
 *
 * 支持参数（均为 extra）:
 *   title    - String  通知标题（必填）
 *   content  - String  通知内容（必填）
 *   priority - String  可选: low / default / high / max
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val content = intent.getStringExtra("content") ?: return
        val priorityStr = intent.getStringExtra("priority") ?: "default"

        val priority = when (priorityStr) {
            "low" -> NotificationManager.IMPORTANCE_LOW
            "high" -> NotificationManager.IMPORTANCE_HIGH
            "max" -> NotificationManager.IMPORTANCE_MAX
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }

        NotificationTool.postNotification(
            context = context,
            title = title,
            content = content,
            priority = priority
        )
    }
}

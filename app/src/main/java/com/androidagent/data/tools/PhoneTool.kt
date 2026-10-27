package com.androidagent.data.tools

import android.content.Context

/**
 * 工具族 2：手机功能
 * 对外仅暴露 {action, params}：不传 action 返回清单；仅传 action 返回用法；
 * 传 action + params 执行（无参子工具仅传 action 即执行）。
 */
class PhoneTool(context: Context, registry: ToolRegistry) : Tool {

    private val systemInfo = SystemInfoTool()
    private val gps = GPSTool(context)
    private val notification = NotificationTool()
    private val sound = SoundTool()
    private val renameSession = RenameSessionTool(registry, context)

    private val subs = listOf(
        SubTool("system_info", "设备系统信息", "system_info: 无参数", requiresParams = false),
        SubTool("gps", "获取当前定位", "gps: 无参数", requiresParams = false),
        SubTool("notify", "发送状态栏通知", "notify: 参数 title, content 必填；可选 priority = low|default|high|max"),
        SubTool("sound", "播放提示音/远程音频", "sound: 参数 mode = tone|audio_url 必填；tone 可选 frequency, duration_ms, volume, stream_type；audio_url 需 url"),
        SubTool("rename_session", "设置对话标题", "rename_session: 参数 title 必填（≤20字）")
    )

    override val name: String = "phone_tool"

    override val description: String =
        "手机功能。子工具：" + subs.joinToString(" / ") { it.name } + "。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return """{"error": "未知子工具: $action"}"""
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        return when (action) {
            "system_info" -> systemInfo.execute(emptyMap())
            "gps" -> gps.execute(emptyMap())
            "notify" -> notification.execute(p)
            "sound" -> sound.execute(p)
            "rename_session" -> renameSession.execute(p)
            else -> """{"error": "未知子工具: $action"}"""
        }
    }
}

package com.androidagent.data.tools

import com.androidagent.data.memory.OpenVikingClient

/**
 * 工具族 3：OpenViking 外置记忆
 * 对外仅暴露 {action, params}：不传 action 返回清单；仅传 action 返回用法；
 * 传 action + params 执行。
 */
class OpenVikingTool : Tool {

    private val client = OpenVikingClient()
    private val search = OpenVikingSearchTool(client)
    private val remember = OpenVikingRememberTool(client)
    private val forget = OpenVikingForgetTool(client)
    private val other = OtherOpenVikingTool(client)

    private val subs = listOf(
        SubTool("search", "语义搜索（带会话上下文）", "search: 参数 query 必填；可选 score_threshold（0-1）、limit（0-10）"),
        SubTool("find", "纯向量语义搜索", "find: 参数 query 必填；可选 target_uri、score_threshold、limit"),
        SubTool("remember", "保存记忆", "remember: 参数 category（preferences|entities|events|experiences）、name、content 必填"),
        SubTool("forget", "删除记忆文件/目录", "forget: 参数 uri 必填；可选 recursive（默认 false）"),
        SubTool("read", "读取记忆文件", "read: 参数 uri 必填（字符串或数组）"),
        SubTool("list_dir", "列出目录", "list_dir: 参数 uri 必填；可选 recursive（默认 false）"),
        SubTool("write_file", "写入记忆文件", "write_file: 参数 uri, content 必填；可选 mode（create|replace|append）")
    )

    override val name: String = "openviking_tool"

    override val description: String =
        "OpenViking 外置记忆。子工具：" + subs.joinToString(" / ") { it.name } + "。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return """{"error": "未知子工具: $action"}"""
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        return when (action) {
            "search" -> search.execute(p)
            "find" -> other.execute(p + mapOf("action" to "find"))
            "read" -> other.execute(p + mapOf("action" to "read"))
            "list_dir" -> other.execute(p + mapOf("action" to "list_dir"))
            "write_file" -> other.execute(p + mapOf("action" to "write_file"))
            "remember" -> remember.execute(p)
            "forget" -> forget.execute(p)
            else -> """{"error": "未知子工具: $action"}"""
        }
    }
}

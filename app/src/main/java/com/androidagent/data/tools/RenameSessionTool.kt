package com.androidagent.data.tools

import android.content.Context
import com.androidagent.data.db.AppDatabase
import com.google.gson.Gson

/**
 * 重命名当前对话标题 — 让 AI 在明确主题后自动设置简洁标题
 */
class RenameSessionTool(
    private val registry: ToolRegistry,
    context: Context
) : Tool {

    private val gson = Gson()
    private val db = AppDatabase.getInstance(context)

    override val name: String = "rename_session"

    override val description: String =
        "为当前对话设置标题，标题应简洁概括对话主题（不超过20字）"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "对话标题，简洁概括本次对话的核心主题（最多20个字）"
            )
        ),
        "required" to listOf("title")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val title = (args["title"] as? String)?.trim() ?: return """{"error": "缺少 title 参数"}"""
        if (title.length > 50) {
            return """{"error": "标题不能超过50个字"}"""
        }

        val sessionId = registry.currentSessionId
        if (sessionId.isBlank()) {
            return """{"error": "当前会话ID为空"}"""
        }

        try {
            val session = db.sessionDao().getSession(sessionId)
            if (session == null) {
                return """{"error": "会话不存在"}"""
            }
            db.sessionDao().update(session.copy(title = title))
            return """{"ok": true, "title": "$title"}"""
        } catch (e: Exception) {
            return """{"error": "修改标题失败: ${e.message}"}"""
        }
    }
}

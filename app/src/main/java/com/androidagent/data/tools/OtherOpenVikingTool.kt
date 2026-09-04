package com.androidagent.data.tools

import com.androidagent.data.memory.OpenVikingClient
import com.google.gson.Gson

/**
 * OpenViking 综合工具 — 通过 action 分发
 * 合并 find / read / list_dir / write_file / create_session /
 * add_message / commit_session / add_messages_batch / get_session / list_sessions
 */
class OtherOpenVikingTool(private val ov: OpenVikingClient) : Tool {

    private val gson = Gson()

    override val name: String = "openviking_*"

    override val description: String =
        "OpenViking 外置记忆综合管理工具。支持：find（快速语义搜索）、read（读取文件）、" +
        "list_dir（列出目录）、write_file（写入文件）、create_session（创建会话）、" +
        "add_message（添加消息）、commit_session（提交会话）、add_messages_batch（批量添加消息）、" +
        "get_session（获取会话详情）、list_sessions（列出所有会话）"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作类型",
                "enum" to listOf(
                    "find", "read", "list_dir", "write_file",
                    "create_session", "add_message", "commit_session",
                    "add_messages_batch", "get_session", "list_sessions"
                )
            ),
            "query" to mapOf("type" to "string", "description" to "搜索关键词（find 时使用）"),
            "target_uri" to mapOf("type" to "string", "description" to "限定检索范围 URI（find 时可选）"),
            "uri" to mapOf("type" to "string", "description" to "文件/目录 URI（read/list_dir/write_file 时使用）"),
            "content" to mapOf("type" to "string", "description" to "要写入的内容（write_file 时使用）"),
            "mode" to mapOf(
                "type" to "string",
                "enum" to listOf("create", "replace", "append"),
                "description" to "写入模式（write_file 时使用）"
            ),
            "recursive" to mapOf("type" to "boolean", "description" to "是否递归列出子目录（list_dir 时使用，默认 false）"),
            "session_id" to mapOf("type" to "string", "description" to "Session ID（session 相关操作时使用）"),
            "role" to mapOf("type" to "string", "enum" to listOf("user", "assistant"), "description" to "消息角色（add_message 时使用）"),
            "keep_recent_count" to mapOf("type" to "integer", "description" to "保留最近 N 条消息（commit_session 时使用，默认 0）"),
            "messages" to mapOf(
                "type" to "array",
                "description" to "消息列表（add_messages_batch 时使用），每条含 role 和 content",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "role" to mapOf("type" to "string", "description" to "user 或 assistant"),
                        "content" to mapOf("type" to "string", "description" to "消息内容")
                    ),
                    "required" to listOf("role", "content")
                )
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
            ?: return """{"error": "缺少 action 参数"}"""

        return try {
            when (action) {
                "find" -> doFind(args)
                "read" -> doRead(args)
                "list_dir" -> doListDir(args)
                "write_file" -> doWriteFile(args)
                "create_session" -> doCreateSession(args)
                "add_message" -> doAddMessage(args)
                "commit_session" -> doCommitSession(args)
                "add_messages_batch" -> doAddMessagesBatch(args)
                "get_session" -> doGetSession(args)
                "list_sessions" -> doListSessions()
                else -> """{"error": "未知操作: $action"}"""
            }
        } catch (e: Exception) {
            """{"error": "OpenViking 操作失败: ${e.message}"}"""
        }
    }

    private suspend fun doFind(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return """{"error": "缺少 query 参数"}"""
        val targetUri = args["target_uri"] as? String ?: ""
        return ov.find(query, targetUri = targetUri)
    }

    private suspend fun doRead(args: Map<String, Any>): String {
        val raw = args["uri"]
        val uris = when (raw) {
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }
        if (uris.isEmpty()) return """{"error": "缺少 uri 参数"}"""
        return if (uris.size == 1) ov.readFile(uris.first()) else ov.readFiles(uris)
    }

    private suspend fun doListDir(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return """{"error": "缺少 uri 参数"}"""
        val recursive = args["recursive"] as? Boolean ?: false
        return ov.listDir(uri, recursive)
    }

    private suspend fun doWriteFile(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return """{"error": "缺少 uri 参数"}"""
        val content = args["content"] as? String ?: ""
        val mode = args["mode"] as? String ?: "replace"
        return ov.writeFile(uri, content, mode)
    }

    private suspend fun doCreateSession(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: ""
        return ov.createSession(sessionId)
    }

    private suspend fun doAddMessage(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return """{"error": "缺少 session_id 参数"}"""
        val role = args["role"] as? String ?: return """{"error": "缺少 role 参数"}"""
        val content = args["content"] as? String ?: ""
        return ov.addMessage(sessionId, role, content)
    }

    private suspend fun doCommitSession(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return """{"error": "缺少 session_id 参数"}"""
        val keepRecent = (args["keep_recent_count"] as? Double)?.toInt() ?: 0
        return ov.commitSession(sessionId, keepRecent)
    }

    private suspend fun doAddMessagesBatch(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return """{"error": "缺少 session_id 参数"}"""
        val raw = args["messages"]
        val list = raw as? List<*>
            ?: return """{"error": "messages 必须是对象数组"}"""
        val messages = list.filterIsInstance<Map<*, *>>().mapNotNull { m ->
            val role = m["role"] as? String
            val content = m["content"] as? String
            if (role != null && content != null) mapOf("role" to role, "content" to content) else null
        }
        return ov.addMessagesBatch(sessionId, messages)
    }

    private suspend fun doGetSession(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return """{"error": "缺少 session_id 参数"}"""
        return ov.getSession(sessionId)
    }

    private suspend fun doListSessions(): String {
        return ov.listSessions()
    }
}

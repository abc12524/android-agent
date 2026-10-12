package com.androidagent.data.tools

import com.androidagent.data.AppPreferences
import com.androidagent.data.memory.OpenVikingClient

/**
 * OpenViking 综合工具 — 通过 action 分发
 * 合并 find / read / list_dir / write_file
 */
class OtherOpenVikingTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_tool"

    override val description: String =
        "OpenViking 外置记忆综合管理工具。支持：find（快速语义搜索）、read（读取文件）、" +
        "list_dir（列出目录）、write_file（写入文件）"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作类型",
                "enum" to listOf(
                    "find", "read", "list_dir", "write_file"
                )
            ),
            "query" to mapOf("type" to "string", "description" to "搜索关键词（find 时使用）"),
            "target_uri" to mapOf("type" to "string", "description" to "限定检索范围 URI（find 时可选）"),
            "score_threshold" to mapOf("type" to "number", "description" to "相似度阈值 0-1（find 时可选，不传则用设置中的默认值）"),
            "limit" to mapOf("type" to "integer", "description" to "返回条数 0-10（find 时可选，不传则用设置中的默认值）"),
            "uri" to mapOf("type" to "string", "description" to "文件/目录 URI（read/list_dir/write_file 时使用）"),
            "content" to mapOf("type" to "string", "description" to "要写入的内容（write_file 时使用）"),
            "mode" to mapOf(
                "type" to "string",
                "enum" to listOf("create", "replace", "append"),
                "description" to "写入模式（write_file 时使用）"
            ),
            "recursive" to mapOf("type" to "boolean", "description" to "是否递归列出子目录（list_dir 时使用，默认 false）")
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
                else -> """{"error": "未知操作: $action"}"""
            }
        } catch (e: Exception) {
            """{"error": "OpenViking 操作失败: ${e.message}"}"""
        }
    }

    private suspend fun doFind(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return """{"error": "缺少 query 参数"}"""
        val targetUri = args["target_uri"] as? String ?: ""
        val threshold = (args["score_threshold"] as? Double)?.toFloat()?.coerceIn(0f, 1f)
            ?: AppPreferences.ovFindThreshold
        val limit = (args["limit"] as? Double)?.toInt()?.coerceIn(0, 10)
            ?: AppPreferences.ovFindLimit
        return ov.find(query, limit = limit, targetUri = targetUri, threshold = threshold)
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
}

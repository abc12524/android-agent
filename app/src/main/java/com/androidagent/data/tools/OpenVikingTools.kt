package com.androidagent.data.tools

import com.androidagent.data.AppPreferences
import com.androidagent.data.memory.OpenVikingClient

/**
 * OpenViking 语义搜索工具
 */
class OpenVikingSearchTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_search"

    override val description: String =
        "在 OpenViking 外置记忆中语义搜索，查找之前保存的知识、偏好、项目信息等"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf("type" to "string", "description" to "搜索关键词，描述要查找什么内容"),
            "score_threshold" to mapOf(
                "type" to "number",
                "description" to "相似度阈值 0-1，越高越严格。不传则使用设置中的默认值"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "返回条数 0-10。不传则使用设置中的默认值"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "{\"error\": \"缺少 query 参数\"}"
        val threshold = (args["score_threshold"] as? Double)?.toFloat()?.coerceIn(0f, 1f)
            ?: AppPreferences.ovScoreThreshold
        val limit = (args["limit"] as? Double)?.toInt()?.coerceIn(0, 10)
            ?: AppPreferences.ovSearchDisplayCount
        return ov.search(query, threshold, limit)
    }
}

/**
 * OpenViking 保存记忆工具
 */
class OpenVikingRememberTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_remember"

    override val description: String =
        "将重要信息保存到 OpenViking 外置记忆中，以便后续对话回忆。适合保存：用户偏好、项目配置、关键决策、有用的操作经验"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "category" to mapOf(
                "type" to "string",
                "enum" to listOf("preferences", "entities", "events", "experiences"),
                "description" to "记忆分类：preferences=用户偏好, entities=项目/概念/人物, events=决策/里程碑, experiences=操作经验"
            ),
            "name" to mapOf("type" to "string", "description" to "记忆名称/主题"),
            "content" to mapOf("type" to "string", "description" to "要保存的内容（Markdown 格式）")
        ),
        "required" to listOf("category", "name", "content")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val category = args["category"] as? String ?: "entities"
        val name = args["name"] as? String ?: "untitled"
        val content = args["content"] as? String ?: ""
        return ov.remember(category, name, content)
    }
}

/**
 * OpenViking 删除文件工具
 */
class OpenVikingDeleteFileTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_delete_file"

    override val description: String =
        "通过 URI 删除 OpenViking 记忆中的文件。注意：此操作不可撤销！"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "uri" to mapOf("type" to "string", "description" to "要删除的文件 URI")
        ),
        "required" to listOf("uri")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return "{\"error\": \"缺少 uri 参数\"}"
        return ov.deleteFile(uri)
    }
}

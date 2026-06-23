package com.androidagent.data.tools

import com.androidagent.data.memory.OpenVikingClient
import com.google.gson.Gson

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
            "query" to mapOf("type" to "string", "description" to "搜索关键词，描述要查找什么内容")
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "{\"error\": \"缺少 query 参数\"}"
        return ov.search(query)
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
 * OpenViking 读取文件工具
 */
class OpenVikingReadTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_read"

    override val description: String =
        "通过 URI 读取 OpenViking 记忆中的单个文件内容。URI 格式: viking://user/{user}/..."

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "uri" to mapOf("type" to "string", "description" to "文件的完整 URI")
        ),
        "required" to listOf("uri")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return "{\"error\": \"缺少 uri 参数\"}"
        return ov.readFile(uri)
    }
}

/**
 * OpenViking 列出目录工具
 */
class OpenVikingListDirTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_list_dir"

    override val description: String =
        "列出 OpenViking 指定目录下的所有文件和子目录，用于探索记忆结构或查找特定文件"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "uri" to mapOf("type" to "string", "description" to "目录 URI"),
            "recursive" to mapOf("type" to "boolean", "description" to "是否递归列出子目录（默认 false）")
        ),
        "required" to listOf("uri")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return "{\"error\": \"缺少 uri 参数\"}"
        val recursive = args["recursive"] as? Boolean ?: false
        return ov.listDir(uri, recursive)
    }
}

/**
 * OpenViking 写入文件工具
 */
class OpenVikingWriteFileTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_write_file"

    override val description: String =
        "写入内容到 OpenViking 记忆文件。支持三种模式：create=创建新文件, replace=覆盖已有文件, append=追加内容"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "uri" to mapOf("type" to "string", "description" to "文件 URI"),
            "content" to mapOf("type" to "string", "description" to "要写入的内容（Markdown 格式）"),
            "mode" to mapOf(
                "type" to "string",
                "enum" to listOf("create", "replace", "append"),
                "description" to "写入模式"
            )
        ),
        "required" to listOf("uri", "content", "mode")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val uri = args["uri"] as? String ?: return "{\"error\": \"缺少 uri 参数\"}"
        val content = args["content"] as? String ?: ""
        val mode = args["mode"] as? String ?: "replace"
        return ov.writeFile(uri, content, mode)
    }
}

/**
 * OpenViking 创建 Session 工具
 */
class OpenVikingCreateSessionTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_create_session"

    override val description: String =
        "在 OpenViking 中创建一个新的对话 Session，用于保存一段完整的对话历史"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf("type" to "string", "description" to "可选。自定义 session_id (UUID 格式)。不传则自动生成")
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: ""
        return ov.createSession(sessionId)
    }
}

/**
 * OpenViking 添加消息工具
 */
class OpenVikingAddMessageTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_add_message"

    override val description: String =
        "向 OpenViking Session 中添加一条消息（user 或 assistant）"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf("type" to "string", "description" to "Session ID"),
            "role" to mapOf("type" to "string", "enum" to listOf("user", "assistant"), "description" to "消息角色"),
            "content" to mapOf("type" to "string", "description" to "消息内容")
        ),
        "required" to listOf("session_id", "role", "content")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return "{\"error\": \"缺少 session_id 参数\"}"
        val role = args["role"] as? String ?: return "{\"error\": \"缺少 role 参数\"}"
        val content = args["content"] as? String ?: ""
        return ov.addMessage(sessionId, role, content)
    }
}

/**
 * OpenViking 提交 Session 工具
 */
class OpenVikingCommitSessionTool(private val ov: OpenVikingClient) : Tool {

    override val name: String = "openviking_commit_session"

    override val description: String =
        "提交/归档 OpenViking Session，触发从会话内容中提取结构化长期记忆。commit 之后不要再次 add_message"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf("type" to "string", "description" to "Session ID"),
            "keep_recent_count" to mapOf("type" to "integer", "description" to "保留最近 N 条消息在活跃 session 中。0=归档所有消息（默认）")
        ),
        "required" to listOf("session_id")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val sessionId = args["session_id"] as? String ?: return "{\"error\": \"缺少 session_id 参数\"}"
        val keepRecent = (args["keep_recent_count"] as? Double)?.toInt() ?: 0
        return ov.commitSession(sessionId, keepRecent)
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

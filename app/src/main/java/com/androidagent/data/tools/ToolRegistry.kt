package com.androidagent.data.tools

import android.content.Context
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.api.QianFanClient
import com.androidagent.data.memory.OpenVikingClient

/**
 * 工具接口 — 对应 Python 版中每个工具函数
 */
interface Tool {
    /** 工具名称（供 LLM 调用） */
    val name: String
    /** 工具描述 */
    val description: String
    /** 工具参数 JSON Schema */
    val parameters: Map<String, Any>

    /**
     * 执行工具
     * @param args 参数字典（已解析的 JSON）
     * @return 执行结果字符串
     */
    suspend fun execute(args: Map<String, Any>): String
}

/**
 * 工具注册中心 — 管理所有可用工具并生成 LLM 所需的 tool definition 列表
 */
class ToolRegistry(context: Context) {

    private val tools = mutableMapOf<String, Tool>()

    init {
        // 注册所有工具
        register(SystemInfoTool())
        register(ShellTool())
        register(GPSTool(context))
        register(BaiDuSearchTool(QianFanClient()))
        register(BaiKeTool(QianFanClient()))
        register(OpenVikingSearchTool(OpenVikingClient()))
        register(OpenVikingRememberTool(OpenVikingClient()))
        register(OpenVikingReadTool(OpenVikingClient()))
        register(OpenVikingListDirTool(OpenVikingClient()))
        register(OpenVikingWriteFileTool(OpenVikingClient()))
        register(OpenVikingCreateSessionTool(OpenVikingClient()))
        register(OpenVikingAddMessageTool(OpenVikingClient()))
        register(OpenVikingCommitSessionTool(OpenVikingClient()))
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    /**
     * 生成 DeepSeek API 所需的 tool definitions
     */
    fun toToolDefinitions(): List<DeepSeekClient.ToolDefinition> {
        return tools.map { (_, tool) ->
            DeepSeekClient.ToolDefinition(
                type = "function",
                function = DeepSeekClient.ToolFunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }

    /**
     * 执行工具调用
     */
    suspend fun executeToolCall(functionName: String, argumentsJson: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val args = gson.fromJson(argumentsJson, Map::class.java) as? Map<String, Any>
                ?: emptyMap()

            val tool = tools[functionName]
            if (tool == null) {
                "{\"error\": \"未知工具: $functionName\"}"
            } else {
                tool.execute(args)
            }
        } catch (e: Exception) {
            "{\"error\": \"工具调用失败: ${e.message}\"}"
        }
    }
}

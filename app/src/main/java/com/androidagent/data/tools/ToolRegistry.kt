package com.androidagent.data.tools

import android.content.Context
import com.androidagent.data.api.DeepSeekClient

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
    /** 当前会话 ID，供会话相关工具使用 */
    var currentSessionId: String = ""

    init {
        // 注册 4 个工具族（每个族通过 action 参数分发子操作）+ skill 已注册工具调用入口
        register(SystemTool())
        register(PhoneTool(context, this))
        register(OpenVikingTool())
        register(ObjectStorageTool())
        register(SkillTool(context))
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

/**
 * 工具族子工具元信息：名称、简短说明、用法、是否需要参数。
 * requiresParams=false 表示该子工具无需参数，仅传 action 即可执行（否则仅传 action 时返回用法）。
 */
internal data class SubTool(
    val name: String,
    val brief: String,
    val usage: String,
    val requiresParams: Boolean = true
)

/** 从调用参数中取出指定子对象（去空值）。 */
internal fun subArgs(args: Map<String, Any>, key: String): Map<String, Any> {
    val obj = args[key] as? Map<*, *> ?: return emptyMap()
    return obj.entries.mapNotNull { e ->
        val k = e.key as? String ?: return@mapNotNull null
        val v = e.value ?: return@mapNotNull null
        k to v
    }.toMap()
}

/** 取子工具参数：优先 params 子对象，回退到除 action/params 外的顶层键。 */
internal fun familyParams(args: Map<String, Any>): Map<String, Any> {
    val nested = subArgs(args, "params")
    if (nested.isNotEmpty()) return nested
    return args.filterKeys { it != "action" && it != "params" }
}

/** 工具族通用 parameters schema：{ action, params }。 */
internal fun familyParameters(subs: List<SubTool>): Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "action" to mapOf(
            "type" to "string",
            "description" to "子工具名：${subs.joinToString(" / ") { it.name }}；省略则返回全部子工具清单"
        ),
        "params" to mapOf(
            "type" to "object",
            "description" to "子工具参数（键值对）。仅传 action 不传 params 时返回该子工具的用法",
            "additionalProperties" to true
        )
    ),
    "required" to emptyList<String>()
)

/** 未指定 action 时返回的全部子工具清单（名称 + 简短说明）。 */
internal fun manifestOf(tool: String, subs: List<SubTool>): String =
    com.google.gson.Gson().toJson(mapOf(
        "status" to "ok",
        "tool" to tool,
        "subtools" to subs.map { mapOf("name" to it.name, "brief" to it.brief) },
        "note" to "仅传 action 返回该子工具用法；传 action + params 执行"
    ))

/** 指定 action 但未给参数时返回的该子工具用法。 */
internal fun usageOf(tool: String, sub: SubTool): String =
    com.google.gson.Gson().toJson(mapOf(
        "status" to "ok",
        "tool" to tool,
        "action" to sub.name,
        "usage" to sub.usage
    ))

package com.androidagent.data.tools

import com.androidagent.data.api.QianFanClient

/**
 * 百度搜索工具
 * 对应 Python 版 baidu_search() 的 raw/summary 模式
 */
class BaiDuSearchTool(private val qianFan: QianFanClient) : Tool {

    override val name: String = "baidu_search"

    override val description: String =
        "百度搜索。通过百度千帆引擎搜索互联网信息。适用于：搜索最新资讯、查询知识类问题"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "搜索关键词"
            ),
            "mode" to mapOf(
                "type" to "string",
                "enum" to listOf("raw", "summary"),
                "description" to "搜索模式：raw=原始搜索结果, summary=AI总结+来源"
            )
        ),
        "required" to listOf("query", "mode")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "{\"error\": \"缺少 query 参数\"}"
        return qianFan.webSearch(query).fold(
            onSuccess = { it },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }
}

/**
 * 百度百科工具
 */
class BaiKeTool(private val qianFan: QianFanClient) : Tool {

    override val name: String = "baidu_baike"

    override val description: String =
        "查询百度百科词条详情，获取词条的摘要、信息卡等内容"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "百科词条名，例如：'人工智能'、'Python'"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "{\"error\": \"缺少 query 参数\"}"
        return qianFan.baike(query).fold(
            onSuccess = { it },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }
}

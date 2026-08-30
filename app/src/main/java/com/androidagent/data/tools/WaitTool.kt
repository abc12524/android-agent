package com.androidagent.data.tools

import com.google.gson.Gson
import kotlinx.coroutines.delay

/**
 * 等待指定时间（0-30 分钟）后再返回。
 * 用于让 AI 在连续操作之间暂停、轮询结果或延后执行下一步。
 */
class WaitTool : Tool {

    private val gson = Gson()

    override val name: String = "wait"

    override val description: String =
        "等待指定的时间（0-30 分钟）后返回。可用于在连续操作之间暂停、轮询结果或延后执行下一步。" +
            "参数为分钟数（minutes，0-30）与可选的额外秒数（seconds，0-59）。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "minutes" to mapOf(
                "type" to "number",
                "description" to "等待的分钟数，范围 0-30"
            ),
            "seconds" to mapOf(
                "type" to "number",
                "description" to "额外的等待秒数，范围 0-59（可选）"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val minutes = (args["minutes"] as? Number)?.toDouble() ?: 0.0
        val seconds = (args["seconds"] as? Number)?.toDouble() ?: 0.0

        var totalMs = ((minutes * 60) + seconds) * 1000.0
        if (totalMs < 0.0) totalMs = 0.0
        if (totalMs > 1_800_000.0) totalMs = 1_800_000.0 // 上限 30 分钟

        val waitedSeconds = totalMs / 1000.0
        return try {
            delay(totalMs.toLong())
            gson.toJson(
                mapOf(
                    "success" to true,
                    "waited_seconds" to waitedSeconds,
                    "message" to "已等待 ${waitedSeconds} 秒"
                )
            )
        } catch (e: Exception) {
            "{\"error\": \"等待失败 - ${e.message}\"}"
        }
    }
}

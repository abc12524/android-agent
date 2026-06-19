package com.androidagent.data.api

import com.androidagent.data.AppPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 客户端
 * 支持 Chat Completion + Function Calling
 */
class DeepSeekClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    data class ChatMessage(
        val role: String,       // "system" / "user" / "assistant" / "tool"
        val content: String = "",
        @SerializedName("tool_call_id")
        val toolCallId: String? = null,
        @SerializedName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
        val name: String? = null
    )

    data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: ToolFunction
    )

    data class ToolFunction(
        val name: String,
        val arguments: String    // JSON string
    )

    data class ToolDefinition(
        val type: String = "function",
        val function: ToolFunctionDef
    )

    data class ToolFunctionDef(
        val name: String,
        val description: String,
        val parameters: Map<String, Any>
    )

    data class ChatRequest(
        val model: String = "deepseek-chat",
        val messages: List<ChatMessage>,
        val tools: List<ToolDefinition>? = null,
        val temperature: Double = 0.7,
        @SerializedName("max_tokens")
        val maxTokens: Int = 4096,
        val stream: Boolean = false
    )

    data class ChatResponse(
        val choices: List<Choice>,
        val usage: Usage? = null
    )

    data class Choice(
        val index: Int = 0,
        val message: ChatMessage,
        val finishReason: String? = null
    )

    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val promptCacheHitTokens: Int = 0,
        val promptCacheMissTokens: Int = 0
    )

    /**
     * 调用 DeepSeek Chat Completion API（非流式）
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先在设置中配置 DeepSeek API Key"))
        }

        val baseUrl = AppPreferences.deepSeekBaseUrl
        val requestBody = ChatRequest(
            messages = messages,
            tools = tools?.takeIf { it.isNotEmpty() }
        )
        val jsonBody = gson.toJson(requestBody)

        try {
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("API 错误 (${response.code}): $responseBody")
                )
            }

            val json = JsonParser.parseString(responseBody).asJsonObject

            val choices = json.getAsJsonArray("choices")?.map { choiceObj ->
                val obj = choiceObj.asJsonObject
                val msg = obj.getAsJsonObject("message")
                val finishReason = obj.get("finish_reason")?.asString
                val choiceIndex = obj.get("index")?.asInt ?: 0

                // 解析 tool_calls
                val toolCalls = msg.getAsJsonArray("tool_calls")?.map { tc ->
                    val tcObj = tc.asJsonObject
                    ToolCall(
                        id = tcObj.get("id").asString,
                        type = tcObj.get("type")?.asString ?: "function",
                        function = ToolFunction(
                            name = tcObj.getAsJsonObject("function").get("name").asString,
                            arguments = tcObj.getAsJsonObject("function").get("arguments").asString
                        )
                    )
                }

                // 提取 reasoning_content
                val reasoning = msg.get("reasoning_content")?.asString

                Choice(
                    index = choiceIndex,
                    message = ChatMessage(
                        role = msg.get("role").asString,
                        content = msg.get("content")?.asString ?: "",
                        toolCalls = toolCalls,
                        name = reasoning
                    ),
                    finishReason = finishReason
                )
            }?.toList() ?: emptyList()

            val usage = json.getAsJsonObject("usage")?.let { usageObj ->
                Usage(
                    promptTokens = usageObj.get("prompt_tokens")?.asInt ?: 0,
                    completionTokens = usageObj.get("completion_tokens")?.asInt ?: 0,
                    totalTokens = usageObj.get("total_tokens")?.asInt ?: 0,
                    promptCacheHitTokens = usageObj.get("prompt_cache_hit_tokens")?.asInt ?: 0,
                    promptCacheMissTokens = usageObj.get("prompt_cache_miss_tokens")?.asInt ?: 0
                )
            }

            Result.success(ChatResponse(choices = choices, usage = usage))

        } catch (e: IOException) {
            Result.failure(Exception("网络错误: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(Exception("请求失败: ${e.message}"))
        }
    }
}

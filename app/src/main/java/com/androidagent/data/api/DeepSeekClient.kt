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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
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

    /**
     * 流式调用 DeepSeek Chat Completion API（SSE 流）
     * 通过 Flow 逐 chunk 推送增量内容
     */
    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Flow<StreamEvent> = callbackFlow {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            trySend(StreamEvent.Error(Exception("请先在设置中配置 DeepSeek API Key")))
            close()
            return@callbackFlow
        }

        val baseUrl = AppPreferences.deepSeekBaseUrl
        val requestBody = ChatRequest(
            messages = messages,
            tools = tools?.takeIf { it.isNotEmpty() },
            stream = true
        )
        val jsonBody = gson.toJson(requestBody)

        try {
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val call = client.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                trySend(StreamEvent.Error(Exception("API 错误 (${response.code}): $errorBody")))
                close()
                return@callbackFlow
            }

            val body = response.body ?: run {
                trySend(StreamEvent.Error(Exception("响应体为空")))
                close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String?
            val currentToolCalls = mutableMapOf<Int, MutableMap<String, Any?>>()
            var contentBuffer = StringBuilder()
            var reasoningBuffer = StringBuilder()
            var finalUsage: Usage? = null

            while (reader.readLine().also { line = it } != null) {
                val ln = line ?: continue
                if (!ln.startsWith("data: ")) continue
                val data = ln.removePrefix("data: ")

                // [DONE] 标记
                if (data == "[DONE]") continue

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        // usage 可能在最后一个非 choices 块中
                        val usageObj = json.getAsJsonObject("usage")
                        if (usageObj != null) {
                            finalUsage = parseUsage(usageObj)
                        }
                        continue
                    }

                    for (choiceEl in choices) {
                        val choice = choiceEl.asJsonObject
                        val delta = choice.getAsJsonObject("delta") ?: continue
                        val finishReason = choice.get("finish_reason")?.asString
                        val index = choice.get("index")?.asInt ?: 0

                        // reasoning_content
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) {
                            val r = delta.get("reasoning_content").asString
                            reasoningBuffer.append(r)
                            trySend(StreamEvent.ReasoningDelta(r))
                        }

                        // content delta
                        if (delta.has("content") && !delta.get("content").isJsonNull) {
                            val c = delta.get("content").asString
                            contentBuffer.append(c)
                            trySend(StreamEvent.ContentDelta(c))
                        }

                        // tool_calls delta
                        if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull) {
                            val tcArr = delta.getAsJsonArray("tool_calls")
                            for (tcEl in tcArr) {
                                val tcObj = tcEl.asJsonObject
                                val tcIndex = tcObj.get("index")?.asInt ?: 0
                                val tcData = currentToolCalls.getOrPut(tcIndex) { mutableMapOf() }

                                if (tcObj.has("id") && !tcObj.get("id").isJsonNull) {
                                    tcData["id"] = tcObj.get("id").asString
                                }
                                if (tcObj.has("type") && !tcObj.get("type").isJsonNull) {
                                    tcData["type"] = tcObj.get("type").asString
                                }
                                val func = tcObj.getAsJsonObject("function")
                                if (func != null) {
                                    if (func.has("name") && !func.get("name").isJsonNull) {
                                        val existing = tcData.getOrPut("name") { "" } as String
                                        tcData["name"] = existing + func.get("name").asString
                                    }
                                    if (func.has("arguments") && !func.get("arguments").isJsonNull) {
                                        val existing = tcData.getOrPut("arguments") { "" } as String
                                        tcData["arguments"] = existing + func.get("arguments").asString
                                    }
                                }
                            }
                        }

                        // finish_reason — 该 choice 结束
                        if (finishReason != null) {
                            val fullContent = contentBuffer.toString()
                            val fullReasoning = reasoningBuffer.toString()

                            val toolCalls = if (currentToolCalls.isNotEmpty()) {
                                currentToolCalls.entries.sortedBy { it.key }.map { (_, tcData) ->
                                    ToolCall(
                                        id = tcData["id"] as? String ?: "",
                                        type = tcData["type"] as? String ?: "function",
                                        function = ToolFunction(
                                            name = tcData["name"] as? String ?: "",
                                            arguments = tcData["arguments"] as? String ?: ""
                                        )
                                    )
                                }
                            } else null

                            trySend(StreamEvent.Done(
                                content = fullContent,
                                reasoningContent = fullReasoning,
                                toolCalls = toolCalls,
                                finishReason = finishReason,
                                usage = finalUsage
                            ))
                        }
                    }

                    // 尝试解析 usage（可能在 choices 之后的行）
                    val usageObj = json.getAsJsonObject("usage")
                    if (usageObj != null) {
                        finalUsage = parseUsage(usageObj)
                    }

                } catch (_: Exception) {
                    // 跳过解析失败的 chunk
                }
            }

            // 流正常结束，检查是否已经发送过 Done
            if (contentBuffer.isNotEmpty() && !currentToolCalls.isNotEmpty()) {
                trySend(StreamEvent.Done(
                    content = contentBuffer.toString(),
                    reasoningContent = reasoningBuffer.toString().ifEmpty { null },
                    toolCalls = null,
                    finishReason = "stop",
                    usage = finalUsage
                ))
            }
            reader.close()
        } catch (e: IOException) {
            trySend(StreamEvent.Error(Exception("网络错误: ${e.message}")))
        } catch (e: Exception) {
            trySend(StreamEvent.Error(Exception("请求失败: ${e.message}")))
        }
        close()
        awaitClose { }
    }

    private fun parseUsage(usageObj: com.google.gson.JsonObject): Usage {
        return Usage(
            promptTokens = usageObj.get("prompt_tokens")?.asInt ?: 0,
            completionTokens = usageObj.get("completion_tokens")?.asInt ?: 0,
            totalTokens = usageObj.get("total_tokens")?.asInt ?: 0,
            promptCacheHitTokens = usageObj.get("prompt_cache_hit_tokens")?.asInt ?: 0,
            promptCacheMissTokens = usageObj.get("prompt_cache_miss_tokens")?.asInt ?: 0
        )
    }

    /**
     * 流式事件
     */
    sealed class StreamEvent {
        data class ContentDelta(val delta: String) : StreamEvent()
        data class ReasoningDelta(val delta: String) : StreamEvent()
        data class Done(
            val content: String,
            val reasoningContent: String?,
            val toolCalls: List<ToolCall>?,
            val finishReason: String,
            val usage: Usage?
        ) : StreamEvent()
        data class Error(val error: Exception) : StreamEvent()
    }

    /**
     * 查询账户余额
     */
    suspend fun checkBalance(): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先配置 DeepSeek API Key"))
        }
        try {
            val request = Request.Builder()
                .url("https://api.deepseek.com/user/balance")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("余额查询失败 (${response.code}): $body"))
            }

            val json = JsonParser.parseString(body).asJsonObject
            val isAvailable = json.get("is_available")?.asBoolean ?: false
            val balanceInfos = json.getAsJsonArray("balance_infos")
            val totalBalance = balanceInfos?.firstOrNull()?.asJsonObject?.get("total_balance")?.asString ?: "?"

            Result.success("¥${totalBalance}")
        } catch (e: Exception) {
            Result.failure(Exception("余额查询失败: ${e.message}"))
        }
    }
}

package com.androidagent.engine

import android.content.Context
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.api.DeepSeekClient.StreamEvent
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.data.memory.OpenVikingClient
import com.androidagent.data.tools.ToolRegistry
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 对话引擎 — 管理多轮对话 + Function Calling 工具调用
 */
class ChatEngine(private val context: Context) {

    private val gson = Gson()
    private val deepSeek = DeepSeekClient()
    private val toolRegistry = ToolRegistry(context)
    private val openViking = OpenVikingClient()
    private val db = AppDatabase.getInstance(context)

    data class ChatResult(
        val assistantContent: String,
        val toolCalls: List<DeepSeekClient.ToolCall>?,
        val reasoningContent: String?,
        val usage: DeepSeekClient.Usage?,
        val allMessages: List<Message>
    )

    /**
     * 发送用户消息并获取 AI 回复
     * 自动处理多轮工具调用，失败时回滚本轮写入的消息
     */
    suspend fun sendMessage(
        sessionId: String,
        userMessage: String
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先在设置中配置 DeepSeek API Key"))
        }

        // 记录本轮新增的消息 ID，用于失败回滚
        val insertedIds = mutableListOf<Long>()

        try {
            val allNewMessages = mutableListOf<Message>()

            // 设置当前会话 ID（供会话相关工具使用）
            toolRegistry.currentSessionId = sessionId

            // 1. 加载历史消息
            val history = db.messageDao().getMessagesBySessionSync(sessionId)
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 搜索 OpenViking 记忆（仅在显示条数 >0 时注入）
            if (AppPreferences.ovSearchDisplayCount > 0) {
                val ovContext = openViking.loadContext(userMessage)
                if (ovContext.isNotBlank()) {
                    val ovMsg = "系统提示：\n$ovContext"
                    // 注入到 API 调用
                    messages.add(DeepSeekClient.ChatMessage(role = "user", content = ovMsg))
                    // 保存到 DB（后续历史对话包含 OV 记忆）
                    val ovEntity = Message(sessionId = sessionId, role = "user", content = ovMsg)
                    insertedIds.add(db.messageDao().insert(ovEntity))
                    allNewMessages.add(ovEntity)
                }
            }

            // 3. 添加用户消息
            messages.add(DeepSeekClient.ChatMessage(role = "user", content = userMessage))

            // 4. 保存用户消息到数据库
            val userMsgEntity = Message(sessionId = sessionId, role = "user", content = userMessage)
            insertedIds.add(db.messageDao().insert(userMsgEntity))
            allNewMessages.add(userMsgEntity)

            // 5. 多轮工具调用循环
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val maxRounds = AppPreferences.maxToolRounds

            for (round in 0..maxRounds + 1) {
                val result = deepSeek.chat(
                    messages = messages,
                    tools = toolRegistry.toToolDefinitions()
                )

                if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    rollbackMessages(db, insertedIds)
                    return@withContext Result.failure(err ?: Exception("未知 API 错误"))
                }

                val response = result.getOrThrow()
                val choice = response.choices.firstOrNull() ?: run {
                    rollbackMessages(db, insertedIds)
                    return@withContext Result.failure(Exception("API 返回空响应"))
                }

                val assistantMsg = choice.message
                val finishReason = choice.finishReason ?: ""
                val usage = response.usage

                // 记录 Token 消耗
                if (usage != null) {
                    finalUsage = usage
                    db.sessionDao().addTokens(sessionId, usage.promptTokens, usage.completionTokens)
                    db.sessionDao().addCacheTokens(sessionId, usage.promptCacheHitTokens, usage.promptCacheMissTokens)
                }

                // 保存 assistant 消息
                val reasoning = assistantMsg.name
                val toolCallsJson = if (assistantMsg.toolCalls != null) {
                    gson.toJson(assistantMsg.toolCalls)
                } else null
                val assistantEntity = Message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = assistantMsg.content,
                    reasoningContent = reasoning,
                    toolCalls = toolCallsJson,
                    promptTokens = usage?.promptTokens ?: 0,
                    completionTokens = usage?.completionTokens ?: 0,
                    promptCacheHitTokens = usage?.promptCacheHitTokens ?: 0,
                    promptCacheMissTokens = usage?.promptCacheMissTokens ?: 0
                )
                val msgId = db.messageDao().insert(assistantEntity)
                insertedIds.add(msgId)
                allNewMessages.add(assistantEntity.copy(id = msgId))

                messages.add(assistantMsg)

                val toolCalls = assistantMsg.toolCalls
                if (toolCalls.isNullOrEmpty() || finishReason == "stop") {
                    finalContent = assistantMsg.content
                    finalReasoning = reasoning
                    break
                }

                // 执行工具调用
                for (tc in toolCalls) {
                    val toolName = tc.function.name
                    val toolArgs = tc.function.arguments

                    val toolResult = toolRegistry.executeToolCall(toolName, toolArgs)

                    // DB 保存完整结果
                    val toolEntity = Message(
                        sessionId = sessionId,
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        toolName = toolName,
                        toolArgs = toolArgs
                    )
                    insertedIds.add(db.messageDao().insert(toolEntity))
                    allNewMessages.add(toolEntity)

                    messages.add(DeepSeekClient.ChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        name = toolName
                    ))
                }

                // 最后一次工具调用：注入停止通知，让 LLM 多一轮来汇总
                if (round == maxRounds) {
                    messages.add(DeepSeekClient.ChatMessage(
                        role = "user",
                        content = "这是最后一次工具调用。请根据已有结果报告当前进度，总结完成情况，然后停止调用。"
                    ))
                }
            }

            // 6. 更新会话统计
            val msgCount = db.messageDao().getMessagesBySessionSync(sessionId).size
            db.sessionDao().updateStats(sessionId, System.currentTimeMillis(), msgCount)

            Result.success(ChatResult(
                assistantContent = finalContent,
                toolCalls = null,
                reasoningContent = finalReasoning,
                usage = finalUsage,
                allMessages = allNewMessages
            ))

        } catch (e: Exception) {
            rollbackMessages(db, insertedIds)
            Result.failure(Exception("对话失败: ${e.message}"))
        }
    }

    /**
     * 发送用户消息并获取 AI 回复（流式版）
     * 自动处理多轮工具调用，通过 onDelta 回调逐 chunk 输出内容
     */
    suspend fun sendMessageStream(
        sessionId: String,
        userMessage: String,
        onDelta: (content: String) -> Unit
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先在设置中配置 DeepSeek API Key"))
        }

        val insertedIds = mutableListOf<Long>()

        try {
            val allNewMessages = mutableListOf<Message>()
            toolRegistry.currentSessionId = sessionId

            // 1. 加载历史消息
            val history = db.messageDao().getMessagesBySessionSync(sessionId)
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 搜索 OpenViking 记忆
            if (AppPreferences.ovSearchDisplayCount > 0) {
                val ovContext = openViking.loadContext(userMessage)
                if (ovContext.isNotBlank()) {
                    val ovMsg = "系统提示：\n$ovContext"
                    messages.add(DeepSeekClient.ChatMessage(role = "user", content = ovMsg))
                    val ovEntity = Message(sessionId = sessionId, role = "user", content = ovMsg)
                    insertedIds.add(db.messageDao().insert(ovEntity))
                    allNewMessages.add(ovEntity)
                }
            }

            // 3. 添加用户消息
            messages.add(DeepSeekClient.ChatMessage(role = "user", content = userMessage))
            val userMsgEntity = Message(sessionId = sessionId, role = "user", content = userMessage)
            insertedIds.add(db.messageDao().insert(userMsgEntity))
            allNewMessages.add(userMsgEntity)

            // 4. 多轮工具调用循环（流式版）
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val maxRounds = AppPreferences.maxToolRounds

            for (round in 0..maxRounds + 1) {
                val contentBuilder = StringBuilder()
                val reasoningBuilder = StringBuilder()
                var streamError: Exception? = null
                var doneEvent: StreamEvent.Done? = null

                // 收集流式事件
                deepSeek.chatStream(messages, toolRegistry.toToolDefinitions()).collect { event ->
                    when (event) {
                        is StreamEvent.ContentDelta -> {
                            contentBuilder.append(event.delta)
                            onDelta(event.delta)
                        }
                        is StreamEvent.ReasoningDelta -> {
                            reasoningBuilder.append(event.delta)
                        }
                        is StreamEvent.Done -> {
                            doneEvent = event
                        }
                        is StreamEvent.Error -> {
                            streamError = event.error
                        }
                    }
                }

                if (streamError != null) {
                    rollbackMessages(db, insertedIds)
                    return@withContext Result.failure(streamError!!)
                }

                val de = doneEvent
                if (de == null) {
                    rollbackMessages(db, insertedIds)
                    return@withContext Result.failure(Exception("流式响应不完整"))
                }

                val fullContent = contentBuilder.toString().ifEmpty { de.content }
                val reasoning = de.reasoningContent ?: reasoningBuilder.toString().ifEmpty { null }
                val usage = de.usage
                val toolCalls = de.toolCalls

                // 记录 Token 消耗
                if (usage != null) {
                    finalUsage = usage
                    db.sessionDao().addTokens(sessionId, usage.promptTokens, usage.completionTokens)
                    db.sessionDao().addCacheTokens(sessionId, usage.promptCacheHitTokens, usage.promptCacheMissTokens)
                }

                // 保存 assistant 消息
                val toolCallsJson = if (toolCalls != null) {
                    gson.toJson(toolCalls)
                } else null
                val assistantEntity = Message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = fullContent,
                    reasoningContent = reasoning,
                    toolCalls = toolCallsJson,
                    promptTokens = usage?.promptTokens ?: 0,
                    completionTokens = usage?.completionTokens ?: 0,
                    promptCacheHitTokens = usage?.promptCacheHitTokens ?: 0,
                    promptCacheMissTokens = usage?.promptCacheMissTokens ?: 0
                )
                val msgId = db.messageDao().insert(assistantEntity)
                insertedIds.add(msgId)
                allNewMessages.add(assistantEntity.copy(id = msgId))

                // 添加到 API 消息列表
                messages.add(DeepSeekClient.ChatMessage(
                    role = "assistant",
                    content = fullContent,
                    toolCalls = toolCalls
                ))

                // 检查是否结束
                if (toolCalls.isNullOrEmpty() || de.finishReason == "stop") {
                    finalContent = fullContent
                    finalReasoning = reasoning
                    break
                }

                // 执行工具调用（非流式）
                for (tc in toolCalls) {
                    val toolName = tc.function.name
                    val toolArgs = tc.function.arguments
                    val toolResult = toolRegistry.executeToolCall(toolName, toolArgs)

                    val toolEntity = Message(
                        sessionId = sessionId,
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        toolName = toolName,
                        toolArgs = toolArgs
                    )
                    insertedIds.add(db.messageDao().insert(toolEntity))
                    allNewMessages.add(toolEntity)

                    messages.add(DeepSeekClient.ChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        name = toolName
                    ))
                }

                // 最后一次工具调用：注入停止通知
                if (round == maxRounds) {
                    messages.add(DeepSeekClient.ChatMessage(
                        role = "user",
                        content = "这是最后一次工具调用。请根据已有结果报告当前进度，总结完成情况，然后停止调用。"
                    ))
                }
            }

            // 5. 更新会话统计
            val msgCount = db.messageDao().getMessagesBySessionSync(sessionId).size
            db.sessionDao().updateStats(sessionId, System.currentTimeMillis(), msgCount)

            Result.success(ChatResult(
                assistantContent = finalContent,
                toolCalls = null,
                reasoningContent = finalReasoning,
                usage = finalUsage,
                allMessages = allNewMessages
            ))

        } catch (e: Exception) {
            rollbackMessages(db, insertedIds)
            Result.failure(Exception("对话失败: ${e.message}"))
        }
    }

    /**
     * 创建新会话
     */
    suspend fun createSession(): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString().take(8)

        val session = ChatSession(
            id = sessionId,
            title = "新对话",
            createdAt = System.currentTimeMillis()
        )
        db.sessionDao().insert(session)

        val systemMsg = AppPreferences.systemPrompt.let { prompt ->
            if (prompt.isNotBlank()) {
                "$prompt\n\n今天是 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
            } else {
                "今天是 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
            }
        }

        db.messageDao().insert(Message(
            sessionId = sessionId,
            role = "system",
            content = systemMsg
        ))

        sessionId
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        db.messageDao().deleteBySession(sessionId)
        db.sessionDao().deleteById(sessionId)
    }

    /**
     * 回滚本轮写入的消息
     */
    private suspend fun rollbackMessages(db: AppDatabase, ids: List<Long>) {
        if (ids.isEmpty()) return
        for (id in ids) {
            try {
                db.messageDao().delete(Message(id = id, sessionId = "", role = ""))
            } catch (_: Exception) { }
        }
    }

    /**
     * Message 转 API ChatMessage
     */
    private fun Message.toApiMessage(): DeepSeekClient.ChatMessage {
        return when (role) {
            "tool" -> DeepSeekClient.ChatMessage(
                role = "tool",
                content = content,
                toolCallId = toolCallId,
                name = toolName
            )
            "assistant" -> {
                val tc = if (toolCalls != null) {
                    try {
                        val type = object : com.google.gson.reflect.TypeToken<List<DeepSeekClient.ToolCall>>() {}.type
                        gson.fromJson<List<DeepSeekClient.ToolCall>>(toolCalls, type)
                    } catch (_: Exception) { null }
                } else null
                DeepSeekClient.ChatMessage(
                    role = role,
                    content = content,
                    toolCalls = tc
                )
            }
            else -> DeepSeekClient.ChatMessage(
                role = role,
                content = content
            )
        }
    }

    /** 查询 DeepSeek 余额 */
    suspend fun checkBalance(): Result<String> = deepSeek.checkBalance()
}

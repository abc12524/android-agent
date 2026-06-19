package com.androidagent.engine

import android.content.Context
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
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
 *
 * 对应 Python 版 deepseek.py 的 chat_completion_with_tools() 和 main() 主循环
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
        val allMessages: List<Message>   // 本轮新增的所有消息
    )

    /**
     * 发送用户消息并获取 AI 回复
     * 自动处理多轮工具调用（Function Calling 循环）
     */
    suspend fun sendMessage(
        sessionId: String,
        userMessage: String
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.deepSeekApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先在设置中配置 DeepSeek API Key"))
        }

        try {
            // 1. 加载历史消息
            val history = db.messageDao().getMessagesBySessionSync(sessionId)
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 加载 OpenViking 上下文（可选）
            val ovContext = openViking.loadContext(userMessage)
            if (ovContext.isNotBlank()) {
                // 把记忆上下文注入到 system prompt 附近
                val systemMsgIdx = messages.indexOfFirst { it.role == "system" }
                if (systemMsgIdx >= 0) {
                    val orig = messages[systemMsgIdx]
                    messages[systemMsgIdx] = orig.copy(content = "${orig.content}\n\n## 相关记忆\n$ovContext")
                }
            }

            // 3. 添加用户消息
            messages.add(DeepSeekClient.ChatMessage(role = "user", content = userMessage))

            // 4. 保存用户消息到数据库
            val userMsgEntity = Message(
                sessionId = sessionId,
                role = "user",
                content = userMessage
            )
            db.messageDao().insert(userMsgEntity)

            // 5. 多轮工具调用循环
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val allNewMessages = mutableListOf(userMsgEntity)
            val maxRounds = AppPreferences.maxToolRounds

            for (round in 0..maxRounds) {
                // 调用 DeepSeek API
                val result = deepSeek.chat(
                    messages = messages,
                    tools = toolRegistry.toToolDefinitions()
                )

                if (result.isFailure) {
                    return@withContext Result.failure(result.exceptionOrNull()!!)
                }

                val response = result.getOrThrow()
                val choice = response.choices.firstOrNull() ?: return@withContext Result.failure(
                    Exception("API 返回空响应")
                )

                val assistantMsg = choice.message
                val finishReason = choice.finishReason ?: ""
                val usage = response.usage

                // 记录 Token 消耗
                if (usage != null) {
                    finalUsage = usage
                    db.sessionDao().addTokens(sessionId, usage.promptTokens, usage.completionTokens)
                }

                // 保存 assistant 消息
                val reasoning = assistantMsg.name  // 暂存在 name 字段
                val assistantEntity = Message(
                    sessionId = sessionId,
                    role = "assistant",
                    content = assistantMsg.content,
                    reasoningContent = reasoning,
                    promptTokens = usage?.promptTokens ?: 0,
                    completionTokens = usage?.completionTokens ?: 0
                )
                val msgId = db.messageDao().insert(assistantEntity)
                allNewMessages.add(assistantEntity.copy(id = msgId))

                // 添加到 API 消息列表
                messages.add(assistantMsg)

                val toolCalls = assistantMsg.toolCalls
                if (toolCalls.isNullOrEmpty() || finishReason == "stop") {
                    // 没有工具调用，对话结束
                    finalContent = assistantMsg.content
                    finalReasoning = reasoning
                    break
                }

                // 处理工具调用
                for (tc in toolCalls) {
                    val toolName = tc.function.name
                    val toolArgs = tc.function.arguments

                    // 执行工具
                    val toolResult = toolRegistry.executeToolCall(toolName, toolArgs)

                    // 保存工具消息到数据库
                    val toolEntity = Message(
                        sessionId = sessionId,
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        toolName = toolName,
                        toolArgs = toolArgs
                    )
                    db.messageDao().insert(toolEntity)
                    allNewMessages.add(toolEntity)

                    // 添加到 API 消息列表
                    messages.add(DeepSeekClient.ChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        name = toolName
                    ))
                }

                // 最后再走一轮循环（LLM 根据工具结果生成回复）
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
            Result.failure(Exception("对话失败: ${e.message}"))
        }
    }

    /**
     * 创建新会话
     */
    suspend fun createSession(): String = withContext(Dispatchers.IO) {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val sessionId = sdf.format(Date())

        val session = ChatSession(
            id = sessionId,
            title = "新对话",
            createdAt = System.currentTimeMillis()
        )
        db.sessionDao().insert(session)

        // 添加系统提示词
        val systemMsg = """
            你是 Android Agent，一个运行在 Android 设备上的 AI 助手。
            你有以下能力：
            1. 获取 Android 设备系统信息
            2. 执行 Android Shell 命令
            3. 搜索百度互联网信息
            4. 通过 OpenViking 管理长期记忆
            请用中文回答用户的问题。
        """.trimIndent()

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
            else -> DeepSeekClient.ChatMessage(
                role = role,
                content = content
            )
        }
    }
}

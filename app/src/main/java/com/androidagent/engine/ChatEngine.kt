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
            // 1. 加载历史消息（超时后只保留 system prompt）
            val timeoutMs = AppPreferences.sessionTimeoutMinutes * 60 * 1000L
            val session = db.sessionDao().getSession(sessionId)
            val isExpired = session != null && (System.currentTimeMillis() - session.createdAt) > timeoutMs
            val history = if (isExpired) {
                db.messageDao().getMessagesBySessionSync(sessionId).filter { it.role == "system" }
            } else {
                db.messageDao().getMessagesBySessionSync(sessionId)
            }
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 加载 OpenViking 上下文（直接追加到 system prompt，保持前缀一致以命中缓存）
            val ovContext = openViking.loadContext(userMessage)
            if (ovContext.isNotBlank()) {
                val systemMsgIdx = messages.indexOfFirst { it.role == "system" }
                if (systemMsgIdx >= 0) {
                    val orig = messages[systemMsgIdx]
                    messages[systemMsgIdx] = orig.copy(content = "${orig.content}\n\n## 相关记忆\n$ovContext")
                }
            }

            // 3. 添加用户消息
            messages.add(DeepSeekClient.ChatMessage(role = "user", content = userMessage))

            // 4. 保存用户消息到数据库
            val userMsgEntity = Message(sessionId = sessionId, role = "user", content = userMessage)
            insertedIds.add(db.messageDao().insert(userMsgEntity))

            // 5. 多轮工具调用循环
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val allNewMessages = mutableListOf(userMsgEntity)
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
                    completionTokens = usage?.completionTokens ?: 0
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

            // 7. 导出对话 JSON（调试用）
            exportSessionToJson(sessionId)

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

        val systemMsg = """
            你是 Android Agent，一个运行在 Android 设备上的 AI 助手。
            你有以下能力：
            1. 获取 Android 设备系统信息
            2. 执行 Android Shell 命令
            3. 搜索百度互联网信息
            4. 通过 OpenViking 管理长期记忆
            5. 通过 SSH 连接远程服务器执行命令（ssh_execute）
            6. 通过 SCP 向远程服务器上传下载文件（ssh_scp）
            7. 在本地运行 Python 代码，支持 pip 安装包（execute_python）
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
     * 导出会话完整信息为 JSON 文件
     * 保存到 /storage/emulated/0/Android/data/com.androidagent/files/chat_logs/
     */
    private suspend fun exportSessionToJson(sessionId: String) = withContext(Dispatchers.IO) {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "chat_${sdf.format(Date())}_${sessionId}.json"

            val exportDir = java.io.File(context.getExternalFilesDir(null), "chat_logs")
            exportDir.mkdirs()
            val file = java.io.File(exportDir, fileName)

            val messages = db.messageDao().getMessagesBySessionSync(sessionId)

            val data = mapOf(
                "session_id" to sessionId,
                "timestamp" to System.currentTimeMillis() / 1000,
                "messages" to messages.map { msg ->
                    mutableMapOf<String, Any?>(
                        "role" to msg.role,
                        "content" to msg.content
                    ).apply {
                        if (!msg.reasoningContent.isNullOrBlank()) put("reasoning_content", msg.reasoningContent)
                        if (!msg.toolCalls.isNullOrBlank()) put("tool_calls", msg.toolCalls)
                        if (!msg.toolCallId.isNullOrBlank()) put("tool_call_id", msg.toolCallId)
                        if (!msg.toolName.isNullOrBlank()) put("tool_name", msg.toolName)
                    }
                }
            )

            file.writeText(gson.toJson(data))
        } catch (_: Exception) {
            // 导出失败不影响主流程
        }
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
}

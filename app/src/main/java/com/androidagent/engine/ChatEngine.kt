package com.androidagent.engine

import android.content.Context
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.api.DeepSeekClient.StreamEvent
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.data.memory.Recall
import com.androidagent.data.memory.RecallEngine
import com.androidagent.data.tools.ToolRegistry
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * 对话引擎 — 管理多轮对话 + Function Calling 工具调用
 */
class ChatEngine(private val context: Context) {

    private val gson = Gson()
    private val deepSeek = DeepSeekClient()
    private val toolRegistry = ToolRegistry(context)
    private val recallEngine = RecallEngine()
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
     * @param imageFilePath 可选。vision 模型下的本地图片路径，自动上传 Files API 后随消息发送
     */
    suspend fun sendMessage(
        sessionId: String,
        userMessage: String,
        imageFilePath: String? = null
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
            seedRecallHistory(sessionId, history)
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 添加用户消息（放在最前面），含图片时自动上传 Files API（vision 模型）
            val (userApiMsg, imageFileId) = buildUserMessage(userMessage, imageFilePath)
            messages.add(userApiMsg)
            if (imageFileId != null && !imageFilePath.isNullOrBlank()) renameLocalImage(imageFilePath, imageFileId)
            val userMsgEntity = Message(sessionId = sessionId, role = "user", content = userMessage, imageFileId = imageFileId)
            insertedIds.add(db.messageDao().insert(userMsgEntity))
            allNewMessages.add(userMsgEntity)

            // 3. 注入 skill 名单 + 自动召回（均为 inject_user，默认不在 UI 展示）
            prepareTurn(
                sessionId, userMessage, messages, insertedIds, allNewMessages,
                skipRecall = imageFilePath != null
            )

            // 5. 多轮工具调用循环
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val maxRounds = AppPreferences.maxToolRounds
            // 思考熔断：单轮思考耗时超过阈值后注入提示（不入库、不掐断连接），让模型直接作答
            val thinkingTimeoutMs = AppPreferences.thinkingTimeoutMinutes * 60_000L
            var thinkingBreakerFired = false

            for (round in 0..maxRounds + 1) {
                maybeStepRecall(sessionId, messages, insertedIds, allNewMessages)
                val roundStartMs = System.currentTimeMillis()
                val result = deepSeek.chat(
                    messages = messages,
                    tools = toolRegistry.toToolDefinitions()
                )
                val roundElapsedMs = System.currentTimeMillis() - roundStartMs

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
                    content = assistantMsg.content as? String ?: "",
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

                // 本轮是否触发思考熔断（仅触发一次）
                val overthinking = thinkingTimeoutMs > 0 && !thinkingBreakerFired &&
                    roundElapsedMs >= thinkingTimeoutMs

                val toolCalls = assistantMsg.toolCalls
                if (toolCalls.isNullOrEmpty() || finishReason == "stop") {
                    captureRound(sessionId, listOf(assistantMsg))
                    if (overthinking && round < maxRounds + 1) {
                        // 思考超时：注入提示（不入库）后再来一轮，让模型直接作答
                        thinkingBreakerFired = true
                        messages.add(DeepSeekClient.ChatMessage(role = "user", content = "请不要过度思考"))
                        continue
                    }
                    finalContent = assistantMsg.content as? String ?: ""
                    finalReasoning = reasoning
                    break
                }

                // 执行工具调用
                val roundMessages = mutableListOf<DeepSeekClient.ChatMessage>(assistantMsg)
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

                    val toolApiMsg = DeepSeekClient.ChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        name = toolName
                    )
                    messages.add(toolApiMsg)
                    roundMessages.add(toolApiMsg)
                }
                captureRound(sessionId, roundMessages)

                // 最后一次工具调用：注入停止通知，让 LLM 多一轮来汇总
                if (round == maxRounds) {
                    messages.add(DeepSeekClient.ChatMessage(
                        role = "user",
                        content = "这是最后一次工具调用。请根据已有结果报告当前进度，总结完成情况，然后停止调用。"
                    ))
                }

                // 思考熔断：本轮思考超时 → 在工具结果之后注入提示（不入库），下一轮直接作答
                if (overthinking) {
                    thinkingBreakerFired = true
                    messages.add(DeepSeekClient.ChatMessage(role = "user", content = "请不要过度思考"))
                }
            }

            // 本轮结束：提交 OV session（触发记忆提取）
            commitTurn(sessionId)

            // 更新会话统计
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
        imageFilePath: String? = null,
        onDelta: (content: String) -> Unit,
        onReasoningDelta: (content: String) -> Unit = {}
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
            seedRecallHistory(sessionId, history)
            val messages = history.map { it.toApiMessage() }.toMutableList()

            // 2. 添加用户消息（放在最前面），含图片时自动上传 Files API（vision 模型）
            val (userApiMsg, imageFileId) = buildUserMessage(userMessage, imageFilePath)
            messages.add(userApiMsg)
            if (imageFileId != null && !imageFilePath.isNullOrBlank()) renameLocalImage(imageFilePath, imageFileId)
            val userMsgEntity = Message(sessionId = sessionId, role = "user", content = userMessage, imageFileId = imageFileId)
            insertedIds.add(db.messageDao().insert(userMsgEntity))
            allNewMessages.add(userMsgEntity)

            // 3. 注入 skill 名单 + 自动召回（均为 inject_user，默认不在 UI 展示）
            prepareTurn(
                sessionId, userMessage, messages, insertedIds, allNewMessages,
                skipRecall = imageFilePath != null
            )

            // 4. 多轮工具调用循环（流式版）
            var finalContent = ""
            var finalReasoning: String? = null
            var finalUsage: DeepSeekClient.Usage? = null
            val maxRounds = AppPreferences.maxToolRounds
            // 思考熔断：单轮思考耗时超过阈值后注入提示（不入库、不掐断连接），让模型直接作答
            val thinkingTimeoutMs = AppPreferences.thinkingTimeoutMinutes * 60_000L
            var thinkingBreakerFired = false

            for (round in 0..maxRounds + 1) {
                maybeStepRecall(sessionId, messages, insertedIds, allNewMessages)
                val roundStartMs = System.currentTimeMillis()
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
                            onReasoningDelta(event.delta)
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

                val roundElapsedMs = System.currentTimeMillis() - roundStartMs

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
                val assistantApiMsg = DeepSeekClient.ChatMessage(
                    role = "assistant",
                    content = fullContent,
                    toolCalls = toolCalls
                )
                messages.add(assistantApiMsg)

                // 本轮是否触发思考熔断（仅触发一次）
                val overthinking = thinkingTimeoutMs > 0 && !thinkingBreakerFired &&
                    roundElapsedMs >= thinkingTimeoutMs

                // 检查是否结束
                if (toolCalls.isNullOrEmpty() || de.finishReason == "stop") {
                    captureRound(sessionId, listOf(assistantApiMsg))
                    if (overthinking && round < maxRounds + 1) {
                        // 思考超时：注入提示（不入库）后再来一轮，让模型直接作答
                        thinkingBreakerFired = true
                        messages.add(DeepSeekClient.ChatMessage(role = "user", content = "请不要过度思考"))
                        continue
                    }
                    finalContent = fullContent
                    finalReasoning = reasoning
                    break
                }

                // 执行工具调用（非流式）
                val roundMessages = mutableListOf<DeepSeekClient.ChatMessage>(assistantApiMsg)
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

                    val toolApiMsg = DeepSeekClient.ChatMessage(
                        role = "tool",
                        content = toolResult,
                        toolCallId = tc.id,
                        name = toolName
                    )
                    messages.add(toolApiMsg)
                    roundMessages.add(toolApiMsg)
                }
                captureRound(sessionId, roundMessages)

                // 最后一次工具调用：注入停止通知
                if (round == maxRounds) {
                    messages.add(DeepSeekClient.ChatMessage(
                        role = "user",
                        content = "这是最后一次工具调用。请根据已有结果报告当前进度，总结完成情况，然后停止调用。"
                    ))
                }

                // 思考熔断：本轮思考超时 → 在工具结果之后注入提示（不入库），下一轮直接作答
                if (overthinking) {
                    thinkingBreakerFired = true
                    messages.add(DeepSeekClient.ChatMessage(role = "user", content = "请不要过度思考"))
                }
            }

            // 本轮结束：提交 OV session（触发记忆提取）
            commitTurn(sessionId)

            // 更新会话统计
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

    // ==================== 注入（skill 名单 / OV 召回）与自动记忆 ====================

    /** 用会话历史中已注入的召回 URI 初始化去重记录（每个会话独立，切回旧会话也不重复注入）。 */
    private fun seedRecallHistory(sessionId: String, history: List<Message>) {
        val uris = history.asSequence()
            .filter { it.role == Message.ROLE_INJECT && Recall.RECALL_MARKER in it.content }
            .flatMap { Recall.parseRecallUris(it.content).asSequence() }
            .toList()
        recallEngine.seedSession(sessionId, uris)
    }

    /**
     * 一轮对话开始时的注入：
     *  1) skill 名单（inject_user，只追加新增的 skill）
     *  2) ovCommit 开启时建立 OV session 并抓取用户问题
     *  3) ovRecallQuestion 开启时以用户问题为 query 召回
     */
    private suspend fun prepareTurn(
        sessionId: String,
        userMessage: String,
        messages: MutableList<DeepSeekClient.ChatMessage>,
        insertedIds: MutableList<Long>,
        allNewMessages: MutableList<Message>,
        skipRecall: Boolean
    ) {
        appendSkillDelta(sessionId, messages, insertedIds, allNewMessages)

        if (AppPreferences.ovCommit) {
            val ovSessionId = ensureOvSession(sessionId)
            if (ovSessionId.isNotBlank()) {
                recallEngine.capture(
                    ovSessionId,
                    listOf(DeepSeekClient.ChatMessage(role = "user", content = userMessage))
                )
            }
        }

        if (!skipRecall && AppPreferences.ovRecallQuestion) {
            injectRecall(
                sessionId,
                listOf(DeepSeekClient.ChatMessage(role = "user", content = userMessage)),
                messages, insertedIds, allNewMessages
            )
        }
    }

    /** 追加 skill 名单：首次全量，之后只追加新增项（只追加不重排，保护前缀缓存）。 */
    private suspend fun appendSkillDelta(
        sessionId: String,
        messages: MutableList<DeepSeekClient.ChatMessage>,
        insertedIds: MutableList<Long>,
        allNewMessages: MutableList<Message>
    ) {
        val summaries = toolRegistry.skillSummaries()
        if (summaries.isEmpty()) return

        val known = db.messageDao().getMessagesBySessionSync(sessionId)
            .filter { it.role == Message.ROLE_INJECT && Recall.SKILL_MARK in it.content }
            .flatMap { Recall.parseSkillNames(it.content) }
            .toSet()

        val fresh = summaries.filter { it.name !in known }
        if (fresh.isEmpty()) return

        val content = Recall.buildSkillMessage(
            fresh.map { it.name to it.description },
            delta = known.isNotEmpty()
        )
        persistInjected(sessionId, content, messages, insertedIds, allNewMessages)
    }

    /** 每步召回：以整批消息（问题 / 助手信息 / 工具结果）为 query。 */
    private suspend fun maybeStepRecall(
        sessionId: String,
        messages: MutableList<DeepSeekClient.ChatMessage>,
        insertedIds: MutableList<Long>,
        allNewMessages: MutableList<Message>
    ) {
        if (!AppPreferences.ovRecallStep || AppPreferences.ovFindLimit <= 0) return
        injectRecall(sessionId, messages.toList(), messages, insertedIds, allNewMessages)
    }

    private suspend fun injectRecall(
        sessionId: String,
        query: List<DeepSeekClient.ChatMessage>,
        messages: MutableList<DeepSeekClient.ChatMessage>,
        insertedIds: MutableList<Long>,
        allNewMessages: MutableList<Message>
    ) {
        val block = recallEngine.recall(query, sessionId)
        if (block.isBlank()) return
        persistInjected(sessionId, Recall.wrapRecallBlock(block), messages, insertedIds, allNewMessages)
    }

    /** 把注入消息落库（role=inject_user）并追加到本轮 API 消息。 */
    private suspend fun persistInjected(
        sessionId: String,
        content: String,
        messages: MutableList<DeepSeekClient.ChatMessage>,
        insertedIds: MutableList<Long>,
        allNewMessages: MutableList<Message>
    ) {
        val entity = Message(sessionId = sessionId, role = Message.ROLE_INJECT, content = content)
        val id = db.messageDao().insert(entity)
        insertedIds.add(id)
        allNewMessages.add(entity.copy(id = id))
        messages.add(DeepSeekClient.ChatMessage(role = "user", content = content))
    }

    /** 取本地会话对应的 OV session；没有则新建并持久化（失败返回空串）。 */
    private suspend fun ensureOvSession(sessionId: String): String {
        val session = db.sessionDao().getSession(sessionId) ?: return ""
        if (session.ovSessionId.isNotBlank()) return session.ovSessionId
        val ovSessionId = recallEngine.ensureSession()
        if (ovSessionId.isNotBlank()) {
            db.sessionDao().updateOvSessionId(sessionId, ovSessionId)
        }
        return ovSessionId
    }

    /** 抓取本轮消息到 OV session（ovCommit 关闭时跳过）。 */
    private suspend fun captureRound(sessionId: String, round: List<DeepSeekClient.ChatMessage>) {
        if (!AppPreferences.ovCommit || round.isEmpty()) return
        val ovSessionId = db.sessionDao().getSession(sessionId)?.ovSessionId.orEmpty()
        if (ovSessionId.isNotBlank()) recallEngine.capture(ovSessionId, round)
    }

    /** 本轮结束提交 OV session，触发记忆提取。 */
    private suspend fun commitTurn(sessionId: String) {
        if (!AppPreferences.ovCommit) return
        val ovSessionId = db.sessionDao().getSession(sessionId)?.ovSessionId.orEmpty()
        if (ovSessionId.isNotBlank()) recallEngine.commit(ovSessionId)
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

        val systemMsg = AppPreferences.systemPrompt.trim()
        if (systemMsg.isNotBlank()) {
            db.messageDao().insert(Message(
                sessionId = sessionId,
                role = "system",
                content = systemMsg
            ))
        }

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
     * 构建用户消息。
     * 无图片时返回 (纯文本 content, null)；带图片时上传 Files API 获取 file_id，
     * 返回 (OpenAI 风格 content 数组（text + file 块）, file_id)。
     * 当前在线模型均支持识图，不再校验模型名；API 报错原样上报给用户。
     */
    private suspend fun buildUserMessage(
        userMessage: String,
        imageFilePath: String?
    ): Pair<DeepSeekClient.ChatMessage, String?> {
        if (imageFilePath.isNullOrBlank()) {
            return DeepSeekClient.ChatMessage(role = "user", content = userMessage) to null
        }

        val upload = deepSeek.uploadFile(File(imageFilePath)).getOrElse { throw it }
        val parts = mutableListOf<Map<String, String>>()
        if (userMessage.isNotBlank()) {
            parts.add(mapOf("type" to "text", "text" to userMessage))
        }
        parts.add(mapOf("type" to "file", "file_id" to upload.id))

        return DeepSeekClient.ChatMessage(role = "user", content = parts) to upload.id
    }

    /**
     * 把本地图片副本重命名为 DeepSeek file_id（filesDir/agent_files/<file_id>），
     * 便于回显时由 file_id 反推本地路径。
     */
    private fun renameLocalImage(srcPath: String, fileId: String) {
        try {
            val src = File(srcPath)
            if (!src.exists()) return
            val dst = File(File(context.filesDir, "agent_files").apply { mkdirs() }, fileId)
            if (src.absolutePath == dst.absolutePath) return
            if (dst.exists()) dst.delete()
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        } catch (_: Exception) { }
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
            Message.ROLE_INJECT -> DeepSeekClient.ChatMessage(
                role = "user",
                content = content
            )
            else -> DeepSeekClient.ChatMessage(
                role = role,
                content = content
            )
        }
    }

    /** 查询 DeepSeek 余额 */
    suspend fun checkBalance(): Result<String> = deepSeek.checkBalance()
}

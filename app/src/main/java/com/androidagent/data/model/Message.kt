package com.androidagent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单条对话消息
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String,          // "user" / "assistant" / "system" / "tool"
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallId: String? = null,   // Function Calling 的 call_id
    val toolName: String? = null,     // 工具名
    val toolArgs: String? = null,     // 工具参数 (JSON)
    val toolResult: String? = null,   // 工具执行结果
    val reasoningContent: String? = null, // DeepSeek 推理内容
    val toolCalls: String? = null,    // assistant 消息的 tool_calls (JSON)
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val promptCacheHitTokens: Int = 0,
    val promptCacheMissTokens: Int = 0
)

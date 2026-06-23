package com.androidagent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对话会话
 */
@Entity(tableName = "sessions")
data class ChatSession(
    @PrimaryKey
    val id: String,                 // 格式: "yyyyMMdd_HHmmss"
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalCacheHitTokens: Int = 0,
    val totalCacheMissTokens: Int = 0
)

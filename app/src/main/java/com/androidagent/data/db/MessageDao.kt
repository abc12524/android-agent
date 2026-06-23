package com.androidagent.data.db

import androidx.room.*
import com.androidagent.data.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionSync(sessionId: String): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COALESCE(SUM(promptTokens), 0) FROM messages WHERE sessionId = :sessionId AND timestamp >= :since AND promptTokens > 0")
    suspend fun getPromptTokensSince(sessionId: String, since: Long): Int

    @Query("SELECT COALESCE(SUM(completionTokens), 0) FROM messages WHERE sessionId = :sessionId AND timestamp >= :since AND completionTokens > 0")
    suspend fun getCompletionTokensSince(sessionId: String, since: Long): Int
}

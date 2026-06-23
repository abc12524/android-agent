package com.androidagent.data.db

import androidx.room.*
import com.androidagent.data.model.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChatSession)

    @Update
    suspend fun update(session: ChatSession)

    @Delete
    suspend fun delete(session: ChatSession)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sessions SET updatedAt = :updatedAt, messageCount = :count WHERE id = :id")
    suspend fun updateStats(id: String, updatedAt: Long, count: Int)

    @Query("UPDATE sessions SET totalPromptTokens = totalPromptTokens + :prompt, totalCompletionTokens = totalCompletionTokens + :completion WHERE id = :id")
    suspend fun addTokens(id: String, prompt: Int, completion: Int)

    @Query("UPDATE sessions SET totalCacheHitTokens = totalCacheHitTokens + :hit, totalCacheMissTokens = totalCacheMissTokens + :miss WHERE id = :id")
    suspend fun addCacheTokens(id: String, hit: Int, miss: Int)
}

package com.androidagent.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.model.Message
import com.androidagent.engine.ChatEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionTitle: String = "新对话",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ChatEngine(application)
    private val db = AppDatabase.getInstance(application)

    var uiState by mutableStateOf(ChatUiState())
        private set

    private var currentSessionId: String = ""
    private var loadMessagesJob: Job? = null

    /**
     * 初始化会话（加载已有或创建新会话）
     */
    fun initSession(sessionId: String) {
        currentSessionId = sessionId
        if (sessionId == "new") {
            viewModelScope.launch {
                currentSessionId = engine.createSession()
                loadMessages()
            }
        } else {
            loadMessages()
        }
    }

    /**
     * 开始新对话 — 创建新会话并清空当前消息
     */
    fun startNewSession() {
        if (uiState.isLoading) return
        viewModelScope.launch {
            val newId = engine.createSession()
            currentSessionId = newId
            uiState = ChatUiState()
            loadMessages()
        }
    }

    private fun loadMessages() {
        // 取消旧的收集协程
        loadMessagesJob?.cancel()
        loadMessagesJob = viewModelScope.launch {
            db.messageDao().getMessagesBySession(currentSessionId).collect { messages ->
                val session = db.sessionDao().getSession(currentSessionId)
                uiState = uiState.copy(
                    messages = messages,
                    sessionTitle = session?.title ?: "新对话",
                    promptTokens = session?.totalPromptTokens ?: 0,
                    completionTokens = session?.totalCompletionTokens ?: 0
                )
            }
        }
    }

    /**
     * 发送消息
     */
    fun sendMessage(text: String) {
        if (text.isBlank() || uiState.isLoading) return

        uiState = uiState.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val result = engine.sendMessage(currentSessionId, text)
                result.fold(
                    onSuccess = {
                        uiState = uiState.copy(isLoading = false, error = null)
                    },
                    onFailure = { e ->
                        uiState = uiState.copy(isLoading = false, error = e.message ?: "未知错误")
                    }
                )
            } catch (e: Throwable) {
                uiState = uiState.copy(isLoading = false, error = "发送失败: ${e.message}")
            }
        }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        uiState = uiState.copy(error = null)
    }

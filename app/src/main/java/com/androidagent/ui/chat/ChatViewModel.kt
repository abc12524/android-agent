package com.androidagent.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.engine.ChatEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionTitle: String = "新对话",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val allSessions: List<ChatSession> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ChatEngine(application)
    private val db = AppDatabase.getInstance(application)

    var uiState by mutableStateOf(ChatUiState())
        private set

    private var currentSessionId: String = ""
    private var loadJob: Job? = null
    private var isSessionReady = false

    init {
        // 收集所有会话列表
        viewModelScope.launch {
            db.sessionDao().getAllSessions().collect { sessions ->
                uiState = uiState.copy(allSessions = sessions)
            }
        }
    }

    fun initSession(sessionId: String) {
        currentSessionId = sessionId
        isSessionReady = false
        if (sessionId == "new") {
            viewModelScope.launch {
                currentSessionId = engine.createSession()
                isSessionReady = true
                loadMessages()
            }
        } else {
            isSessionReady = true
            loadMessages()
        }
    }

    /** 切换到指定会话 */
    fun switchToSession(sessionId: String) {
        if (sessionId == currentSessionId || uiState.isLoading) return
        currentSessionId = sessionId
        isSessionReady = true
        uiState = ChatUiState(allSessions = uiState.allSessions)
        loadMessages()
    }

    /** 创建新对话 */
    fun startNewSession() {
        if (uiState.isLoading) return
        viewModelScope.launch {
            val newId = engine.createSession()
            currentSessionId = newId
            isSessionReady = true
            uiState = ChatUiState(allSessions = uiState.allSessions)
            loadMessages()
        }
    }

    private fun loadMessages() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    fun getSessionId(): String = currentSessionId
}

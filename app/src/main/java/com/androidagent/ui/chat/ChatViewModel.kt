package com.androidagent.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.api.DeepSeekClient
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
    val todayPromptTokens: Int = 0,
    val todayCompletionTokens: Int = 0,
    val lastUsage: DeepSeekClient.Usage? = null,
    val allSessions: List<ChatSession> = emptyList(),
    val balance: String = ""
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
        // 初始加载余额
        refreshBalance()
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

                // 计算 UTC+8 今日凌晨时间戳
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis

                val todayPrompt = db.messageDao().getPromptTokensSince(currentSessionId, todayStart)
                val todayCompletion = db.messageDao().getCompletionTokensSince(currentSessionId, todayStart)

                uiState = uiState.copy(
                    messages = messages,
                    sessionTitle = session?.title ?: "新对话",
                    todayPromptTokens = todayPrompt,
                    todayCompletionTokens = todayCompletion
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
                    onSuccess = { chatResult ->
                        uiState = uiState.copy(
                            isLoading = false,
                            error = null,
                            lastUsage = chatResult.usage
                        )
                        // 每次发送后刷新余额
                        refreshBalance()
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

    /** 刷新 DeepSeek 余额 */
    fun refreshBalance() {
        viewModelScope.launch {
            val result = engine.checkBalance()
            uiState = uiState.copy(
                balance = result.getOrNull() ?: ""
            )
        }
    }
}

package com.androidagent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) { viewModel.initSession(sessionId) }

    val state = viewModel.uiState
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && !state.isLoading) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState, gesturesEnabled = true,
        drawerContent = {
            SessionDrawer(state.allSessions, viewModel.getSessionId(),
                onSelect = { scope.launch { drawerState.close() }; viewModel.switchToSession(it) },
                onNew = { scope.launch { drawerState.close() }; viewModel.startNewSession() },
                onSettings = { scope.launch { drawerState.close() }; onNavigateToSettings() })
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(state.sessionTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.History, contentDescription = "历史")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.startNewSession() }) {
                            Icon(Icons.Default.Add, contentDescription = "新对话")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f), placeholder = { Text("输入消息...") },
                            shape = RoundedCornerShape(24.dp), maxLines = 4,
                            enabled = !state.isLoading)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { if (inputText.isNotBlank()) { viewModel.sendMessage(inputText.trim()); inputText = "" } },
                            enabled = inputText.isNotBlank() && !state.isLoading,
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.Send, contentDescription = "发送") }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Android Agent", fontSize = 28.sp, fontWeight = FontWeight.Light,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            Spacer(Modifier.height(6.dp))
                            Text("开始一段新对话", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 72.dp)
                    ) {
                        // 过滤掉 system 角色和 OV 系统提示的用户消息
                        val displayMessages = state.messages.filter { msg ->
                            msg.role != "system" &&
                            !(msg.role == "user" && msg.content.startsWith("系统提示："))
                        }
                        items(displayMessages, key = { it.id }) { msg ->
                            MessageBubble(msg)
                        }
                        // 底部 token 统计
                        if (state.lastUsage != null) {
                            item(key = "token_footer") {
                                TokenFooter(state.lastUsage!!, state.promptTokens, state.completionTokens)
                            }
                        }
                    }
                }

                // loading overlay
                if (state.isLoading) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        tonalElevation = 4.dp
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            if (state.messages.isNotEmpty())
                                Text("AI 思考中...", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // error
                if (state.error != null) {
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action = { TextButton(onClick = { viewModel.clearError() }) { Text("关闭") } }
                    ) { Text(state.error) }
                }
            }
        }
    }
}

// ==================== Token 统计底部 ====================

@Composable
private fun TokenFooter(usage: DeepSeekClient.Usage, totalIn: Int, totalOut: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("📊 Token 消耗", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("  输入: ${usage.promptTokens}  |  输出: ${usage.completionTokens}  |  总计: ${usage.totalTokens}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            if (usage.promptCacheHitTokens > 0 || usage.promptCacheMissTokens > 0) {
                Text("  缓存命中: ${usage.promptCacheHitTokens}  |  缓存未命中: ${usage.promptCacheMissTokens}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Text("  会话累计: 输入 $totalIn  |  输出 $totalOut",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

// ==================== 侧边栏会话列表 ====================

@Composable
private fun SessionDrawer(
    sessions: List<ChatSession>, currentId: String,
    onSelect: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit
) {
    val validSessions = sessions.filter { it.messageCount > 0 }
    val groups = groupSessions(validSessions)
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Surface(Modifier.fillMaxWidth().clickable { onNew() },
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text("新对话", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            groups.forEach { (label, list) ->
                item {
                    Text(label, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.id }) { session ->
                    val active = session.id == currentId
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp)).clickable { onSelect(session.id) },
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(fmtSessionTime(session.createdAt), fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${session.messageCount} 条",
                                fontSize = 11.sp,
                                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
        // 底部：设置按钮
        HorizontalDivider()
        Surface(Modifier.fillMaxWidth().clickable { onSettings() }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("设置", fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== 消息气泡 ====================

@Composable
fun MessageBubble(msg: Message) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    val hasToolCalls = msg.role == "assistant" && msg.toolCalls != null
            && msg.content.isBlank()
    val bc = when { isUser -> BubbleUser; isTool -> BubbleTool; else -> BubbleAssistant }
    val tc = when { isUser -> BubbleUserText; isTool -> BubbleToolText; else -> BubbleAssistantText }

    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // ---- 工具调用过程展示 ----
        if (isTool && msg.toolName != null) {
            val argsText = if (!msg.toolArgs.isNullOrBlank()) {
                try {
                    // 格式化 JSON 为一行显示
                    val gson = com.google.gson.GsonBuilder().create()
                    val obj = gson.fromJson(msg.toolArgs, Map::class.java)
                    obj.entries.joinToString(", ") { (k, v) -> "$k=${v}" }
                } catch (_: Exception) {
                    msg.toolArgs
                }
            } else ""
            Text("🔧 ${msg.toolName}($argsText)", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        // 推理内容
        if (msg.reasoningContent != null && msg.reasoningContent.isNotBlank()) {
            Surface(Modifier.padding(bottom = 4.dp).widthIn(max = 320.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("💭 ${msg.reasoningContent}", Modifier.padding(8.dp), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp),
            color = bc
        ) {
            when {
                // 仅工具调用（无文本回复）
                hasToolCalls -> {
                    Text("调用工具中...", Modifier.padding(14.dp, 10.dp),
                        color = tc, fontSize = 13.sp)
                }
                // 工具执行结果
                isTool -> {
                    Text(msg.content.ifBlank { "(空)" }, Modifier.padding(14.dp, 10.dp),
                        color = tc, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                }
                // 普通消息 (user / assistant)
                else -> {
                    MarkdownText(msg.content.ifBlank { "(空)" },
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp), baseColor = tc)
                }
            }
        }
        Text(fmtTime(msg.timestamp), fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ==================== 工具函数 ====================

private fun fmtTime(ms: Long) =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun fmtSessionTime(ms: Long): String {
    val now = Calendar.getInstance()
    val d = Calendar.getInstance().also { it.timeInMillis = ms }
    return when {
        sameDay(now, d) -> "今天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
        isYest(now, d)  -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
    }
}

private fun sameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYest(now: Calendar, d: Calendar): Boolean {
    val y = Calendar.getInstance().also { it.timeInMillis = now.timeInMillis - 86400000L }
    return sameDay(y, d)
}

private data class SG(val label: String, val sessions: List<ChatSession>)

private fun groupSessions(all: List<ChatSession>): List<SG> {
    val now = Calendar.getInstance()
    val weekStart = Calendar.getInstance().also {
        it.add(Calendar.DAY_OF_WEEK, it.firstDayOfWeek - it.get(Calendar.DAY_OF_WEEK))
        it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0)
        it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
    }
    val t = mutableListOf<ChatSession>(); val y = mutableListOf<ChatSession>()
    val w = mutableListOf<ChatSession>(); val e = mutableListOf<ChatSession>()
    for (s in all.sortedByDescending { it.createdAt }) {
        val c = Calendar.getInstance().also { it.timeInMillis = s.createdAt }
        when {
            sameDay(now, c) -> t.add(s)
            isYest(now, c) -> y.add(s)
            c.timeInMillis >= weekStart.timeInMillis -> w.add(s)
            else -> e.add(s)
        }
    }
    val r = mutableListOf<SG>()
    if (t.isNotEmpty()) r.add(SG("今天", t))
    if (y.isNotEmpty()) r.add(SG("昨天", y))
    if (w.isNotEmpty()) r.add(SG("本周", w))
    if (e.isNotEmpty()) r.add(SG("更早", e))
    return r
}

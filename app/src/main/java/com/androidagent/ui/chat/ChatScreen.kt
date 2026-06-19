package com.androidagent.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androidagent.data.model.Message
import com.androidagent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    LaunchedEffect(sessionId) {
        viewModel.initSession(sessionId)
    }

    val state = viewModel.uiState
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 自动滚到底部
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.sessionTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (state.promptTokens > 0 || state.completionTokens > 0) {
                            Text(
                                text = "🔤 ${state.promptTokens} → ${state.completionTokens}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !state.isLoading
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !state.isLoading,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.messages.isEmpty() && state.isLoading) {
                // 初始加载
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.messages.isEmpty()) {
                // 空状态
                Text(
                    text = "开始新对话\n输入消息与 AI 助手交流",
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // 过滤掉 system 消息，只显示 user/assistant/tool
                    items(
                        items = state.messages.filter { it.role != "system" },
                        key = { it.id }
                    ) { message ->
                        MessageBubble(message = message)
                    }

                    // 加载指示器
                    if (state.isLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("AI 思考中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 错误提示 Snackbar
            if (state.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("关闭")
                        }
                    }
                ) {
                    Text(state.error)
                }
            }
        }
    }
}

/**
 * 消息气泡
 */
@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"

    val bubbleColor = when {
        isUser -> BubbleUser
        isTool -> BubbleTool
        else -> BubbleAssistant
    }

    val textColor = when {
        isUser -> BubbleUserText
        isTool -> BubbleToolText
        else -> BubbleAssistantText
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 工具名标签
        if (isTool && message.toolName != null) {
            Text(
                text = "🔧 ${message.toolName}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // 推理内容
        if (message.reasoningContent != null && message.reasoningContent.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 320.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "💭 ${message.reasoningContent}",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 消息气泡
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bubbleColor
        ) {
            if (isTool) {
                // 工具消息用等宽字体
                Text(
                    text = message.content.ifBlank { "(空)" },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            } else {
                // 用户/AI 消息渲染 Markdown
                MarkdownText(
                    markdown = message.content.ifBlank { "(空)" },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    baseColor = textColor
                )
            }
        }

        // 时间戳
        Text(
            text = formatTimestamp(message.timestamp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

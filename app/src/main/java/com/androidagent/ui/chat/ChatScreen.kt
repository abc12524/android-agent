package com.androidagent.ui.chat

import dev.jeziellago.compose.markdowntext.MarkdownText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var pendingImageName by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val haptics = rememberHaptics()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val info = saveFileToAppDir(context, it)
                val mime = context.contentResolver.getType(it)
                withContext(Dispatchers.Main) {
                    if (info != null) {
                        val (name, fullPath) = info
                        // 图片 → 作为 vision 附件；其它文件 → 沿用「读取文件」文本指令
                        if (mime?.startsWith("image/") == true) {
                            pendingImagePath = fullPath
                            pendingImageName = name
                        } else {
                            inputText = "读取文件：$fullPath"
                        }
                    }
                }
            }
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
                        Text(state.sessionTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() }; haptics(HapticsType.Light) }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "会话列表")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.startNewSession(); haptics(HapticsType.Light) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "新对话")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // 待发送图片附件提示
                        if (pendingImagePath != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Image,
                                        contentDescription = "图片",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(pendingImageName ?: "",
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(
                                        onClick = {
                                            pendingImagePath = null
                                            pendingImageName = null
                                        }
                                    ) { Text("移除", fontSize = 12.sp) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        IconButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.isLoading
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = "选择文件")
                        }
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f), placeholder = { Text("输入消息...") },
                            shape = RoundedCornerShape(24.dp), maxLines = 4,
                            enabled = !state.isLoading)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank() || pendingImagePath != null) {
                                    viewModel.sendMessage(inputText.trim(), pendingImagePath)
                                    inputText = ""
                                    pendingImagePath = null
                                    pendingImageName = null
                                }
                            },
                            enabled = (inputText.isNotBlank() || pendingImagePath != null) && !state.isLoading,
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.Send, contentDescription = "发送") }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SelectionContainer {
                                Text("Android Agent", fontSize = 28.sp, fontWeight = FontWeight.Light,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("开始一段新对话", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        }
                    }
                } else {
                    // 过滤掉 system 角色消息（OV 检索记忆作为用户侧消息显示）
                    val displayMessages = state.messages.filter { msg ->
                        msg.role != "system"
                    }
                    // 分组：把每次 assistant 的推理+工具调用聚合成「工具执行」时间线卡
                    val chatItems = remember(displayMessages) {
                        groupChatItems(displayMessages)
                    }
                    val lastRealIndex = (chatItems.size - 1).coerceAtLeast(0)

                    // 会话加载 / 回复完成：滚动到最近一条真实消息
                    LaunchedEffect(chatItems.size, state.isLoading) {
                        if (!state.isLoading && chatItems.isNotEmpty()) {
                            listState.animateScrollToItem(lastRealIndex)
                        }
                    }
                    // 流式输出：持续贴合最新内容（瞬间滚动，降低跳动感）
                    LaunchedEffect(state.streamingContent.length) {
                        if (state.isLoading) {
                            listState.scrollToItem(chatItems.size)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 72.dp)
                    ) {
                        items(chatItems.size, key = { index ->
                            when (val it = chatItems[index]) {
                                is ChatItem.Bubble -> it.msg.id
                                is ChatItem.Reason -> it.key
                                is ChatItem.ToolRun -> it.key
                            }
                        }) { index ->
                            when (val it = chatItems[index]) {
                                is ChatItem.Bubble -> SelectionContainer { MessageBubble(it.msg) }
                                is ChatItem.Reason -> ReasoningCard(
                                    reasoning = it.msg.reasoningContent.orEmpty(),
                                    title = "深度思考",
                                )
                                is ChatItem.ToolRun -> SelectionContainer { ToolRunCard(it.steps) }
                            }
                        }
                        // 流式输出（进行中的回复）
                        if (state.isLoading) {
                            item(key = "streaming") {
                                StreamingReply(
                                    content = state.streamingContent,
                                    reasoning = state.streamingReasoning,
                                )
                            }
                        }
                        // 底部 token 统计
                        if (state.lastUsage != null) {
                            item(key = "token_footer") {
                            TokenFooter(state.promptTokens, state.completionTokens, state.cacheHitTokens, state.cacheMissTokens, state.balance)
                            }
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
private fun TokenFooter(sessionIn: Int, sessionOut: Int,
                        cacheHit: Int, cacheMiss: Int, balance: String) {
    fun hitRate(hit: Int, miss: Int): String {
        val total = hit + miss
        if (total == 0) return "0%"
        return "${(hit * 100 / total)}%"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Token 消耗", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("  会话 token：${sessionIn + sessionOut}  hit：$cacheHit  |  命中率 ${hitRate(cacheHit, cacheMiss)}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Text("  当前余额：${balance.ifBlank { "无" }}",
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
    val cs = MaterialTheme.colorScheme
    val validSessions = sessions.filter { it.messageCount > 0 }
    val groups = groupSessions(validSessions)

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = cs.surface,
        drawerContentColor = cs.onSurface,
    ) {
        // 头部：品牌名 + 新对话
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Android Agent",
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = cs.onSurface)
            Text("AI 助手 · 本地工具",
                fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f))
            Spacer(Modifier.height(14.dp))
            NewChatPill(onClick = onNew)
        }

        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            groups.forEach { (label, list) ->
                item(key = "hdr_$label") {
                    Text(label,
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = cs.onSurfaceVariant.copy(alpha = 0.8f))
                }
                items(list, key = { it.id }) { session ->
                    SessionTile(
                        session = session,
                        active = session.id == currentId,
                        onClick = { onSelect(session.id) },
                    )
                }
                item(key = "gap_$label") { Spacer(Modifier.height(6.dp)) }
            }
        }

        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        DrawerFooterRow(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = onSettings,
        )
    }
}

// ---- 侧边栏「新对话」按钮 ----
@Composable
private fun NewChatPill(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) cs.primary.copy(alpha = 0.78f) else cs.primary,
        label = "newChat",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppRadii.card)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(HapticsType.Medium); onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = cs.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text("新对话", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = cs.onPrimary)
    }
}

// ---- 会话列表卡片（按压反馈 + 激活高亮） ----
@Composable
private fun SessionTile(session: ChatSession, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            active -> cs.primary.copy(alpha = 0.12f)
            pressed -> cs.onSurface.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        label = "sessionTile",
    )
    val title = if (session.title != "新对话") session.title else fmtSessionTime(session.createdAt)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(AppRadii.card)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(HapticsType.Light); onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${session.messageCount} 条", fontSize = 11.sp,
                    color = cs.onSurface.copy(alpha = 0.55f))
                if (session.title != "新对话") {
                    Text(" · ${fmtSessionTime(session.createdAt)}", fontSize = 11.sp,
                        color = cs.onSurface.copy(alpha = 0.45f))
                }
            }
        }
        if (active) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
        }
    }
}

// ---- 侧边栏底部设置行 ----
@Composable
private fun DrawerFooterRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent,
        label = "footer",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(HapticsType.Light); onClick()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = cs.onSurfaceVariant)
    }
}

// ==================== 流式回复（进行中的 AI 输出） ====================

@Composable
private fun StreamingReply(content: String, reasoning: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        // 流式推理（进行中的思考，实时滚动）
        if (reasoning.isNotBlank()) {
            StreamingReasoning(reasoning, modifier = Modifier.padding(bottom = 4.dp))
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .animateContentSize(tween(220)),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = 4.dp, bottomEnd = 16.dp,
            ),
            color = BubbleAssistant,
        ) {
            if (content.isBlank()) {
                // 首包前：思考中
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamingDots()
                    Spacer(Modifier.width(10.dp))
                    Text("正在思考…", fontSize = 13.sp, color = BubbleAssistantText.copy(alpha = 0.6f))
                }
            } else {
                val blocks = remember(content) {
                    try { markdownBlocks(content) }
                    catch (_: Exception) { listOf(MdBlock.Text(content)) }
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    blocks.forEachIndexed { index, block ->
                        when (block) {
                            is MdBlock.Text -> MarkdownText(
                                markdown = block.text,
                                style = TextStyle(color = BubbleAssistantText, fontSize = 15.sp),
                                syntaxHighlightColor = Color(0x80FFEB3B),
                            )
                            is MdBlock.Table -> MarkdownTable(
                                table = block.table,
                                textStyle = TextStyle(color = BubbleAssistantText, fontSize = 15.sp),
                            )
                        }
                        if (index != blocks.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                    // 光标脉冲
                    BlinkingCursor(color = cs.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// 微型「正在思考」三点动画
@Composable
private fun StreamingDots() {
    val transition = rememberInfiniteTransition(label = "streamDots")
    val alpha = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Row {
        repeat(3) { i ->
            if (i > 0) Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(BubbleAssistantText.copy(alpha = alpha.value)),
            )
        }
    }
}

// 光标闪烁指示
@Composable
private fun BlinkingCursor(color: Color) {
    val transition = rememberInfiniteTransition(label = "cursorBlink")
    val alpha = transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text(
        "▍",
        fontSize = 15.sp,
        color = color.copy(alpha = alpha.value),
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ==================== 消息气泡 ====================

@Composable
fun MessageBubble(msg: Message) {
    val isUser = msg.role == "user"
    val isOvContext = msg.role == "user" && msg.content.startsWith("[自动检索的候选记忆")
    val bc = if (isUser) BubbleUser else BubbleAssistant
    val tc = if (isUser) BubbleUserText else BubbleAssistantText

    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 推理内容（统一用「深度思考」折叠卡片展示）
        if (msg.reasoningContent != null && msg.reasoningContent.isNotBlank()) {
            ReasoningCard(msg.reasoningContent, Modifier.padding(bottom = 4.dp))
        }

        when {
            // ---- OV 检索记忆：复用「工具调用」样式，点击弹详情 ----
            isOvContext -> {
                val ovContent = remember(msg.content) {
                    val c = msg.content
                    val start = c.indexOf('\n')
                    val end = c.lastIndexOf('\n')
                    if (start >= 0 && end > start) c.substring(start + 1, end).trim() else c
                }
                ToolCallCard(listOf(ToolCallEntry("ov-search", ovContent)))
            }

            // ---- 普通消息 (user / assistant) ----
            else -> {
                Surface(
                    modifier = if (isUser)
                        // 问题框自适应内容宽度，最长 92% 屏宽
                        Modifier.fillMaxWidth(0.92f).wrapContentWidth(Alignment.End, unbounded = false)
                    else
                        Modifier.fillMaxWidth(0.92f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp),
                    color = bc
                ) {
                    val blocks = remember(msg.content) { markdownBlocks(msg.content) }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        blocks.forEachIndexed { index, block ->
                            when (block) {
                                is MdBlock.Text -> MarkdownText(
                                    markdown = block.text,
                                    style = TextStyle(color = tc, fontSize = 15.sp),
                                    syntaxHighlightColor = Color(0x80FFEB3B)
                                )
                                is MdBlock.Table -> MarkdownTable(
                                    table = block.table,
                                    textStyle = TextStyle(color = tc, fontSize = 15.sp)
                                )
                            }
                            if (index != blocks.lastIndex) {
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }

        // 仅正式回复（用户消息 / AI 正文回复）下方显示时间，思考、工具调用与 OV 检索不显示
        if (!isOvContext && (isUser || (msg.role == "assistant" && msg.content.isNotBlank()))) {
            Text(fmtTime(msg.timestamp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

// ==================== 深度思考折叠卡片 ====================

/**
 * 「深度思考」卡片：底色统一。最右侧是向下的箭头，点击即展开；
 * 展开后只有再点箭头才收起，点击卡片其它处不复原。
 */
@Composable
private fun ReasoningCard(
    reasoning: String,
    modifier: Modifier = Modifier,
    title: String = "深度思考",
    icon: ImageVector = Icons.Outlined.Psychology,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "reasonChevron")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clickable { if (!expanded) expanded = true },
        shape = AppRadii.card,
        color = if (cs.surface.luminance() < 0.5f) Color(0xFF2B2B2E) else BubbleAssistant,
        shadowElevation = AppElevation.soft,
        border = BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f))
                // 仅箭头可切换展开/收起（点击卡片其它处不再收起）
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = cs.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp).rotate(rotation),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    reasoning,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// 流式推理：内联实时显示（非点击弹层，边生成边看）
@Composable
private fun StreamingReasoning(reasoning: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(200)),
        shape = AppRadii.card,
        color = cs.primaryContainer.copy(alpha = if (dark) 0.25f else 0.30f),
        shadowElevation = AppElevation.soft,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("深度思考",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface)
                Spacer(Modifier.weight(1f))
                BlinkingCursor(color = cs.primary.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(6.dp))
            Text(reasoning,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                color = cs.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis)
        }
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

// ==================== 消息分组：气泡 / 工具执行时间线卡 ====================

/** 聊天项目：消息气泡 / 思考卡 / 工具执行卡，三者分开、并列呈现 */
private sealed class ChatItem {
    data class Bubble(val msg: Message) : ChatItem()
    data class Reason(val msg: Message) : ChatItem() {
        val key: Long get() = msg.id
    }
    data class ToolRun(val steps: List<Message>) : ChatItem() {
        val key: Long get() = steps.first().id
    }
}

/** 将按时间排序的消息聚合成渲染项：推理与工具结果拆为独立卡片，便于并列分隔 */
private fun groupChatItems(messages: List<Message>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        val isToolTurn = m.role == "assistant" && m.toolCalls != null && m.content.isBlank()
        if (isToolTurn) {
            // 推理独立成卡
            if (!m.reasoningContent.isNullOrBlank()) {
                out.add(ChatItem.Reason(m))
            }
            // 紧随其后的工具结果独立成卡
            val tools = mutableListOf<Message>()
            var j = i + 1
            while (j < messages.size && messages[j].role == "tool") {
                tools.add(messages[j]); j++
            }
            if (tools.any { it.toolName != null }) {
                out.add(ChatItem.ToolRun(tools))
            }
            i = j
            continue
        } else if (m.role == "tool") {
            out.add(ChatItem.ToolRun(listOf(m)))
            i++
            continue
        }
        out.add(ChatItem.Bubble(m))
        i++
    }
    return out
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

/**
 * 将用户选择的文件保存到应用内部存储的 agent_files/ 目录
 * 文件按 年-月-日-时-分-秒.{后缀} 格式重命名
 * @return Pair(时间戳文件名, 完整根路径) 或 null（失败时）
 */
private suspend fun saveFileToAppDir(context: android.content.Context, uri: Uri): Pair<String, String>? {
    return withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // 获取原始文件名，仅用于提取后缀
            val originalName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "unknown"

            // 提取后缀
            val dotIdx = originalName.lastIndexOf('.')
            val ext = if (dotIdx > 0) originalName.substring(dotIdx) else ""

            // 生成时间戳文件名：年-月-日-时-分-秒.{后缀}
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.getDefault())
            val timestampName = sdf.format(java.util.Date()) + ext

            // 创建 agent_files 目录
            val targetDir = File(context.filesDir, "agent_files")
            targetDir.mkdirs()

            // 写文件
            val targetFile = File(targetDir, timestampName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Pair(timestampName, targetFile.absolutePath)
        } catch (e: Exception) {
            null
        }
    }
}

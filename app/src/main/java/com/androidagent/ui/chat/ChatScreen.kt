package com.androidagent.ui.chat

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import com.androidagent.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.androidagent.data.model.ChatSession
import com.androidagent.data.model.Message
import com.androidagent.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
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
    var drawerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(sessionId) { viewModel.initSession(sessionId) }

    // 打开侧边栏时收起输入法，避免键盘压住抽屉底部（如「设置」）
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) keyboard?.hide()
    }

    // 抽屉打开时拦截返回键，先收起抽屉
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

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

    SwipeAwayDrawer(
        open = drawerOpen,
        onOpenChange = { drawerOpen = it },
        drawerContent = {
            SessionDrawer(state.allSessions, viewModel.getSessionId(),
                onSelect = { drawerOpen = false; viewModel.switchToSession(it) },
                onSettings = { drawerOpen = false; onNavigateToSettings() })
        }
    ) {
        Scaffold(
            topBar = {
                // 顶栏：☰ 会话列表 · 标题（左对齐、紧贴 ☰）· ⊕ 新对话
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                keyboard?.hide()
                                drawerOpen = true
                                haptics(HapticsType.Light)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        TwoLineMenuIcon(tint = MaterialTheme.colorScheme.onSurface)
                    }

                    // 空会话不显示标题，保持极简
                    if (state.messages.isNotEmpty()) {
                        Text(
                            state.sessionTitle,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                viewModel.startNewSession()
                                haptics(HapticsType.Light)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "新对话",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            bottomBar = {
                // 悬浮胶囊输入栏：左侧「+」选文件 · 中间输入 · 右侧圆形发送
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 键盘弹出时把输入栏顶上去；用 union 取较大值，避免与导航栏内边距叠加
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // 待发送图片附件提示
                    if (pendingImagePath != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Image, contentDescription = "图片",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(pendingImageName ?: "",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(
                                    onClick = {
                                        pendingImagePath = null
                                        pendingImageName = null
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "移除",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    val canSend = (inputText.isNotBlank() || pendingImagePath != null) && !state.isLoading
                    // 输入卡：白底 · 圆角 26 · 上下两段（上行输入、下行右侧 ⊕ / 发送）
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 9.dp)
                        ) {
                            // 上行：输入区（占位符「发消息或按住说话」）
                            Box(
                                Modifier.fillMaxWidth().padding(end = 6.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    enabled = !state.isLoading,
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                )
                                if (inputText.isEmpty()) {
                                    Text(
                                        "发消息或按住说话",
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // 下行：右侧「⊕ 添加」+「发送」（有内容才出现，蓝色圆形 ↑）
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !state.isLoading,
                                        ) { filePickerLauncher.launch(arrayOf("*/*")) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = "选择文件",
                                        modifier = Modifier.size(25.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (inputText.isNotBlank() || pendingImagePath != null) {
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (canSend) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                            )
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                enabled = canSend,
                                            ) {
                                                viewModel.sendMessage(inputText.trim(), pendingImagePath)
                                                inputText = ""
                                                pendingImagePath = null
                                                pendingImageName = null
                                                haptics(HapticsType.Light)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "发送",
                                            modifier = Modifier.size(20.dp),
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    // 空会话：鲸鱼 logo + 「你好，让我们开始聊天吧」
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(R.drawable.ic_deepseek_logo),
                                contentDescription = null,
                                modifier = Modifier.width(43.dp).height(32.dp),
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "你好，让我们开始聊天吧",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else if (state.newUi) {
                    StreamChatList(state, listState)
                } else {
                    // 过滤 system；注入消息（skill 名单 / OV 召回）默认隐藏，设置中可开启
                    val displayMessages = state.messages.filter { msg ->
                        val injected = msg.role == Message.ROLE_INJECT ||
                            (msg.role == "user" && msg.content.startsWith("[自动检索的候选记忆"))
                        msg.role != "system" && (!injected || state.showInjected)
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
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 72.dp)
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
                            TokenFooter(
                                cacheHit = state.cacheHitTokens,
                                cacheMiss = state.cacheMissTokens,
                                sessionOut = state.completionTokens,
                                costHit = state.costHit,
                                costMiss = state.costMiss,
                                costOut = state.costOut,
                                family = state.modelFamily,
                                balance = state.balance,
                            )
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
internal fun TokenFooter(
    cacheHit: Int, cacheMiss: Int, sessionOut: Int,
    costHit: Double, costMiss: Double, costOut: Double,
    family: String, balance: String,
) {
    val totalCost = costHit + costMiss + costOut

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("📊 Token 消耗统计 · $family · 合计 ¥${"%.4f".format(totalCost)}",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            TokenCostRow("输入(缓存命中)", cacheHit, costHit)
            TokenCostRow("输入(缓存未命中)", cacheMiss, costMiss)
            TokenCostRow("输出", sessionOut, costOut)
            Spacer(Modifier.height(4.dp))
            Text("  当前余额：${balance.ifBlank { "无" }}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun TokenCostRow(label: String, tokens: Int, cost: Double) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 11.sp, color = color)
        Text("${"%,d".format(tokens)} tokens  ¥${"%.4f".format(cost)}",
            fontSize = 11.sp, color = color)
    }
}

// ==================== 侧边栏会话列表 ====================

@Composable
private fun SessionDrawer(
    sessions: List<ChatSession>, currentId: String,
    onSelect: (String) -> Unit, onSettings: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val validSessions = sessions.filter { it.messageCount > 0 }
    val groups = groupSessions(validSessions)

    // 真机：抽屉约占 80%，纯白、无品牌头部、无「新对话」胶囊（新建走顶栏 ⊕）
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding(),
    ) {
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            groups.forEach { (label, list) ->
                item(key = "hdr_" + label) {
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp),
                        fontSize = 15.sp,
                        color = cs.onSurfaceVariant,
                    )
                }
                items(list, key = { it.id }) { session ->
                    SessionTile(
                        session = session,
                        active = session.id == currentId,
                        onClick = { onSelect(session.id) },
                    )
                }
            }
        }

        // 底部：只有「⋯」设置入口（真机位置在右下角）
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(end = 12.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSettings() },
                contentAlignment = Alignment.Center,
            ) {
                ThreeDotsIcon(tint = cs.onSurface.copy(alpha = 0.8f))
            }
        }
    }
}

/**
 * 推拉式抽屉：面板铺在下层（左 80%），主界面整体右移、变圆角卡片压在抽屉之上。
 * 关闭时右拉打开、打开时左推关闭（不限定起手位置，只认方向），松手按过半与否吸附。
 */
@Composable
private fun SwipeAwayDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0f) }
    val panelFraction = 0.80f
    val panelWidthPx = widthPx * panelFraction
    val cornerPx = with(density) { 24.dp.toPx() }
    val shadowPx = with(density) { 12.dp.toPx() }
    val ease = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    fun settleTo(target: Float) {
        onOpenChange(target == 1f)
        scope.launch { progress.animateTo(target, tween(240, easing = ease)) }
    }

    // 外部（☰ / 选中会话 / 设置）改变开关状态时做动画
    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, tween(280, easing = ease))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(widthPx) {
                if (widthPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val wasOpen = progress.value > 0.5f
                    // 累计进度用局部变量，避免每帧读 Animatable 拿到上一帧旧值而抖动
                    var latest = progress.value
                    val applyDelta: (Float) -> Unit = { deltaPx ->
                        if (deltaPx != 0f) {
                            latest = (latest + deltaPx / panelWidthPx).coerceIn(0f, 1f)
                            scope.launch { progress.snapTo(latest) }
                        }
                    }
                    // 不限定起手位置（手势导航把左边缘 20dp 划给系统返回，边缘判定必然失效），
                    // 改为只认「方向」：关闭时右拉才算打开、打开时左推才算关闭，
                    // 竖向滚动的 over.y 会更大，直接放过；代码块等横向可滚子项会先消费，这里拿到 null。
                    var accepted = false
                    val moved = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        val horizontal = abs(over.x) > abs(over.y)
                        val towardOpen = over.x > 0f
                        if (horizontal && towardOpen != wasOpen) {
                            accepted = true
                            change.consume()
                            applyDelta(over.x)
                        }
                    } ?: return@awaitEachGesture
                    if (!accepted) return@awaitEachGesture
                    // 注意：horizontalDrag 的回调只给 PointerInputChange，位移需自行用 positionChange()
                    horizontalDrag(moved.id) { change ->
                        val dx = change.positionChange().x
                        change.consume()
                        applyDelta(dx)
                    }
                    settleTo(if (latest > 0.5f) 1f else 0f)
                }
            },
    ) {
        // 下层：抽屉面板（80% 宽，关闭时整体移到屏幕左侧之外）
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(panelFraction)
                .graphicsLayer { translationX = progress.value * panelWidthPx - panelWidthPx },
        ) {
            drawerContent()
        }

        // 上层：主界面。右移 + 24dp 圆角 + 左侧投影，形成「压在抽屉上的卡片」
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = progress.value * panelWidthPx
                    shape = RoundedCornerShape(cornerPx * progress.value)
                    clip = true
                    shadowElevation = shadowPx * progress.value
                    ambientShadowColor = Color.Black.copy(alpha = 0.30f)
                    spotShadowColor = Color.Black.copy(alpha = 0.34f)
                },
        ) {
            content()

            // 打开后轻点右侧露出的一截主界面即可关闭
            val fullyOpen by remember { derivedStateOf { progress.value > 0.99f } }
            if (fullyOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenChange(false) },
                )
            }
        }
    }
}

// ---- 会话条目：纯文本行（真机没有副标题、没有卡片底） ----
@Composable
private fun SessionTile(session: ChatSession, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            active -> cs.onSurface.copy(alpha = 0.05f)
            pressed -> cs.onSurface.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        label = "sessionTile",
    )
    val title = if (session.title != "新对话") session.title else fmtSessionTime(session.createdAt)

    Text(
        title,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(HapticsType.Light)
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 15.dp),
        fontSize = 17.sp,
        color = cs.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 顶栏 ☰：真机是两横（上长下短），不是系统三横图标 */
@Composable
private fun TwoLineMenuIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(tint, Offset(size.width * 0.14f, size.height * 0.34f),
            Offset(size.width * 0.86f, size.height * 0.34f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.14f, size.height * 0.64f),
            Offset(size.width * 0.62f, size.height * 0.64f), stroke, StrokeCap.Round)
    }
}

/** 抽屉底部「⋯」设置入口 */
@Composable
private fun ThreeDotsIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val r = 1.9.dp.toPx()
        val y = size.height / 2f
        listOf(0.22f, 0.5f, 0.78f).forEach { fx ->
            drawCircle(tint, r, Offset(size.width * fx, y))
        }
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
        // 助手正文：与已完成的消息一致，整宽无气泡
        Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
            if (content.isBlank()) {
                Row(
                    Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamingDots()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在思考…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MarkdownBody(content)
                // 光标脉冲
                BlinkingCursor(color = cs.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

// 微型「正在思考」三点动画
@Composable
internal fun StreamingDots() {
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
internal fun BlinkingCursor(color: Color) {
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
fun MessageBubble(msg: Message, suppressReasoning: Boolean = false) {
    // 注入消息：skill 名单 / OV 召回（新版本 role=inject_user，旧数据以 user + 前缀标记兼容）
    val isInjected = msg.role == Message.ROLE_INJECT ||
        (msg.role == "user" && msg.content.startsWith("[自动检索的候选记忆"))
    val isUser = msg.role == "user" && !isInjected
    val bc = if (isUser) BubbleUser else BubbleAssistant
    val tc = if (isUser) BubbleUserText else BubbleAssistantText
    val imageId = msg.imageFileId
    val userImage = if (isUser && !imageId.isNullOrBlank()) {
        File(File(LocalContext.current.filesDir, "agent_files"), imageId)
            .takeIf { it.exists() }?.absolutePath
    } else null

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 用户气泡：自适应内容（不超过可用宽度的 86%）；助手气泡：自适应屏幕（占满可用宽度）
        val maxBubbleWidth = maxWidth * 0.86f

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // 推理内容（统一用「深度思考」折叠卡片展示）；新版思考流已内联，不再重复展示
            if (!suppressReasoning && msg.reasoningContent != null && msg.reasoningContent.isNotBlank()) {
                ReasoningCard(msg.reasoningContent, Modifier.padding(bottom = 4.dp))
            }

            when {
                // ---- 注入消息（skill 名单 / OV 召回）：复用「工具调用」样式，点击弹详情 ----
                isInjected -> {
                    val hasSkills = msg.content.contains("<available_skills>")
                    val hasOv = msg.content.contains("<available_openviking>")
                    val hasTips = msg.content.contains("<user_tips>")
                    val label = when {
                        hasSkills && (hasOv || hasTips) -> "context"
                        hasSkills -> "skill-list"
                        hasOv -> "ov-recall"
                        hasTips -> "user-tips"
                        else -> "context"
                    }
                    ToolCallCard(
                        listOf(ToolCallEntry(label, msg.content)),
                        containerColor = Color(250, 244, 224),
                    )
                }

                // ---- 普通消息 (user / assistant) ----
                else -> {
                    if (isUser) {
                        Surface(
                            modifier = Modifier.widthIn(max = maxBubbleWidth),   // 自适应内容
                            shape = RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = 18.dp, bottomEnd = 6.dp,
                            ),
                            color = bc,
                        ) {
                            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                                if (userImage != null) {
                                    MessageImage(
                                        path = userImage,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                    if (msg.content.isNotBlank()) Spacer(Modifier.height(8.dp))
                                }
                                MarkdownBody(msg.content, fillWidth = false)
                            }
                        }
                    } else {
                        // 助手消息：无气泡（透明无框），直接铺满屏宽
                        MarkdownBody(msg.content)
                    }
                }
            }

            // 真机消息区不显示发送时间，保持整屏干净
        }
    }
}

// ==================== 消息图片 ====================

/** 渲染用户消息附带的本地图片（按需下采样，避免大图 OOM） */
@Composable
private fun MessageImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { decodeSampledBitmap(path, 1080) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "图片",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

private fun decodeSampledBitmap(path: String, maxDim: Int): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    } catch (_: Exception) {
        null
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
            .animateContentSize(tween(200)),
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
                // 仅箭头可切换展开/收起（点击卡片其它处不触发）
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

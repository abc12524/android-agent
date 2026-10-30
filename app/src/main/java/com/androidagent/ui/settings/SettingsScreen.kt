package com.androidagent.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.skills.SkillRepoConfig
import com.androidagent.data.skills.SkillRepository
import com.androidagent.data.skills.RemoteSkill
import com.androidagent.data.skills.SkillStore
import com.androidagent.data.updater.AppUpdater
import com.androidagent.ui.theme.Accent
import com.androidagent.ui.theme.AppRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 设置页内部转场：进入二级页从右侧滑入，返回一级页时方向相反（跟着「返回」的语义走）。
 * 右上角退出设置同样会回到 root，因此这里覆盖了所有返回路径（含系统返回键）。
 */
private fun settingsPageTransition(): AnimatedContentTransitionScope<String>.() -> ContentTransform = {
    val dur = 240
    if (targetState == "root") {
        slideInHorizontally(animationSpec = tween(dur), initialOffsetX = { -it / 4 }) +
            fadeIn(tween(dur)) togetherWith
            slideOutHorizontally(animationSpec = tween(dur), targetOffsetX = { it / 4 }) +
            fadeOut(tween(dur))
    } else {
        slideInHorizontally(animationSpec = tween(dur), initialOffsetX = { it / 4 }) +
            fadeIn(tween(dur)) togetherWith
            slideOutHorizontally(animationSpec = tween(dur), targetOffsetX = { -it / 4 }) +
            fadeOut(tween(dur))
    }
}

/** 设置项自动保存：FormField 失焦时触发（由 SettingsScreen 注入，实际执行 savePrefs） */
private val LocalSettingsAutoSave = staticCompositionLocalOf<() -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    var deepSeekKey by remember { mutableStateOf(AppPreferences.deepSeekApiKey) }
    var deepSeekBaseUrl by remember { mutableStateOf(AppPreferences.deepSeekBaseUrl) }
    var deepSeekModel by remember { mutableStateOf(AppPreferences.deepSeekModel) }
    var modelOptions by remember { mutableStateOf(listOf<String>()) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var ovUrl by remember { mutableStateOf(AppPreferences.openVikingUrl) }
    var ovKey by remember { mutableStateOf(AppPreferences.openVikingKey) }
    var ovUser by remember { mutableStateOf(AppPreferences.openVikingUser) }
    var maxRounds by remember { mutableStateOf(AppPreferences.maxToolRounds.toString()) }
    var thinkingTimeout by remember { mutableStateOf(AppPreferences.thinkingTimeoutMinutes.toString()) }
    var ovFindThreshold by remember { mutableStateOf(AppPreferences.ovFindThreshold.toString()) }
    var ovFindLimit by remember { mutableStateOf(AppPreferences.ovFindLimit.toString()) }
    var ovPeerId by remember { mutableStateOf(AppPreferences.ovPeerId) }
    var ovWorkspacePeer by remember { mutableStateOf(AppPreferences.ovWorkspacePeer) }
    var ovRecallQuestion by remember { mutableStateOf(AppPreferences.ovRecallQuestion) }
    var ovRecallStep by remember { mutableStateOf(AppPreferences.ovRecallStep) }
    var ovCommit by remember { mutableStateOf(AppPreferences.ovCommit) }
    var showInjected by remember { mutableStateOf(AppPreferences.showInjectedMessages) }
    var chatUiStream by remember { mutableStateOf(AppPreferences.chatUiStream) }
    var chatShowReasoning by remember { mutableStateOf(AppPreferences.chatShowReasoning) }
    var chatShowToolCalls by remember { mutableStateOf(AppPreferences.chatShowToolCalls) }
    var chatShowToolEdit by remember { mutableStateOf(AppPreferences.chatShowToolEdit) }
    var backgroundEnabled by remember { mutableStateOf(AppPreferences.backgroundServiceEnabled) }
    var s3Endpoint by remember { mutableStateOf(AppPreferences.s3EndpointUrl) }
    var s3AccessKey by remember { mutableStateOf(AppPreferences.s3AccessKey) }
    var s3SecretKey by remember { mutableStateOf(AppPreferences.s3SecretKey) }
    var skipSsl by remember { mutableStateOf(AppPreferences.skipSslVerification) }
    var userTips by remember { mutableStateOf(AppPreferences.userTips) }
    var showKeys by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var configUrl by remember { mutableStateOf(AppPreferences.configUrl) }
    var skillRepo by remember { mutableStateOf(AppPreferences.skillHubRepo) }
    var skillBranch by remember { mutableStateOf(AppPreferences.skillHubBranch) }
    var skillType by remember { mutableStateOf(AppPreferences.skillHubType) }
    var skillBase by remember { mutableStateOf(AppPreferences.skillHubBase) }
    var skillToken by remember { mutableStateOf(AppPreferences.skillHubToken) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var remoteJson by remember { mutableStateOf<String?>(null) }
    var accountOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf<String?>(null) }
    var loadingAccounts by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var page by rememberSaveable { mutableStateOf("root") }

    // 二级菜单按系统返回键时回到一级菜单，而非退出设置
    BackHandler(enabled = page != "root") { page = "root" }

    fun refreshFromPrefs() {
        deepSeekKey = AppPreferences.deepSeekApiKey
        deepSeekBaseUrl = AppPreferences.deepSeekBaseUrl
        deepSeekModel = AppPreferences.deepSeekModel
        ovUrl = AppPreferences.openVikingUrl
        ovKey = AppPreferences.openVikingKey
        ovUser = AppPreferences.openVikingUser
        maxRounds = AppPreferences.maxToolRounds.toString()
        thinkingTimeout = AppPreferences.thinkingTimeoutMinutes.toString()
        ovFindThreshold = AppPreferences.ovFindThreshold.toString()
        ovFindLimit = AppPreferences.ovFindLimit.toString()
        ovPeerId = AppPreferences.ovPeerId
        ovWorkspacePeer = AppPreferences.ovWorkspacePeer
        ovRecallQuestion = AppPreferences.ovRecallQuestion
        ovRecallStep = AppPreferences.ovRecallStep
        ovCommit = AppPreferences.ovCommit
        showInjected = AppPreferences.showInjectedMessages
        chatUiStream = AppPreferences.chatUiStream
        chatShowReasoning = AppPreferences.chatShowReasoning
        chatShowToolCalls = AppPreferences.chatShowToolCalls
        chatShowToolEdit = AppPreferences.chatShowToolEdit
        backgroundEnabled = AppPreferences.backgroundServiceEnabled
        s3Endpoint = AppPreferences.s3EndpointUrl
        s3AccessKey = AppPreferences.s3AccessKey
        s3SecretKey = AppPreferences.s3SecretKey
        skipSsl = AppPreferences.skipSslVerification
        userTips = AppPreferences.userTips
        configUrl = AppPreferences.configUrl
        skillRepo = AppPreferences.skillHubRepo
        skillBranch = AppPreferences.skillHubBranch
        skillType = AppPreferences.skillHubType
        skillBase = AppPreferences.skillHubBase
        skillToken = AppPreferences.skillHubToken
    }

    fun savePrefs() {
        AppPreferences.deepSeekApiKey = deepSeekKey
        AppPreferences.deepSeekBaseUrl = deepSeekBaseUrl
        AppPreferences.deepSeekModel = deepSeekModel
        AppPreferences.openVikingUrl = ovUrl
        AppPreferences.openVikingKey = ovKey
        AppPreferences.openVikingUser = ovUser
        AppPreferences.ovFindThreshold = (ovFindThreshold.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
        AppPreferences.ovFindLimit = ovFindLimit.toIntOrNull() ?: 2
        AppPreferences.ovPeerId = ovPeerId
        AppPreferences.ovWorkspacePeer = ovWorkspacePeer
        AppPreferences.ovRecallQuestion = ovRecallQuestion
        AppPreferences.ovRecallStep = ovRecallStep
        AppPreferences.ovCommit = ovCommit
        AppPreferences.showInjectedMessages = showInjected
        AppPreferences.chatUiStream = chatUiStream
        AppPreferences.chatShowReasoning = chatShowReasoning
        AppPreferences.chatShowToolCalls = chatShowToolCalls
        AppPreferences.chatShowToolEdit = chatShowToolEdit
        AppPreferences.maxToolRounds = maxRounds.toIntOrNull() ?: 8
        AppPreferences.thinkingTimeoutMinutes = thinkingTimeout.toIntOrNull() ?: 0
        AppPreferences.userTips = userTips
        AppPreferences.s3EndpointUrl = s3Endpoint
        AppPreferences.s3AccessKey = s3AccessKey
        AppPreferences.s3SecretKey = s3SecretKey
        AppPreferences.skipSslVerification = skipSsl
        AppPreferences.skillHubRepo = skillRepo
        AppPreferences.skillHubBranch = skillBranch
        AppPreferences.skillHubType = skillType
        AppPreferences.skillHubBase = skillBase
        AppPreferences.skillHubToken = skillToken

        val wasEnabled = AppPreferences.backgroundServiceEnabled
        AppPreferences.backgroundServiceEnabled = backgroundEnabled
        if (backgroundEnabled && !wasEnabled) {
            com.androidagent.ForegroundService.start(context)
        } else if (!backgroundEnabled && wasEnabled) {
            com.androidagent.ForegroundService.stop(context)
        }
    }

    // 自动保存：输入框失焦、离开当前二级页、退出设置页时写入，不再需要右上角「保存」
    val autoSave = remember { { savePrefs() } }
    DisposableEffect(page) {
        onDispose { savePrefs() }
    }

    val titles = mapOf(
        "root" to "设置",
        "api" to "API 配置",
        "ov" to "OpenViking 记忆",
        "storage" to "对象存储",
        "skillhub" to "Skill 仓库",
        "features" to "功能与提示词",
        "import" to "配置导入",
        "about" to "关于",
    )

    Scaffold(
        // 页面底比卡片深一档（浅色 #F5F5F6 / 深色 #101012），让白色卡片浮起来
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            // 顶栏：左侧圆角方形返回键 · 标题居中 · 右侧仅留占位（保存已改为自动）
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cs = MaterialTheme.colorScheme
                val backInteraction = remember { MutableInteractionSource() }
                val backPressed by backInteraction.collectIsPressedAsState()
                // 按下缩到 88%，抬起弹回——返回动作有明确的手感反馈
                val backScale by animateFloatAsState(
                    targetValue = if (backPressed) 0.88f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "backScale",
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = backScale
                            scaleY = backScale
                        }
                        .clip(CircleShape)
                        .background(cs.surface)
                        .clickable(
                            interactionSource = backInteraction,
                            indication = ripple(bounded = false),
                        ) { if (page == "root") onBack() else page = "root" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface,
                    )
                }

                // 标题跟着页面一起动：返回时旧标题上滑淡出、新标题从下方淡入
                AnimatedContent(
                    targetState = titles[page] ?: "设置",
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        slideInVertically(animationSpec = tween(220), initialOffsetY = { it / 2 }) +
                            fadeIn(tween(220)) togetherWith
                            slideOutVertically(animationSpec = tween(220), targetOffsetY = { -it / 2 }) +
                            fadeOut(tween(220))
                    },
                    label = "title",
                ) { title ->
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 右侧占位保持标题绝对居中（不再放「保存」：改为失焦 / 离开页面自动保存）
                Box(Modifier.size(52.dp))
            }
        }
    ) { padding ->
        CompositionLocalProvider(LocalSettingsAutoSave provides autoSave) {
            // 页面转场：进二级页从右侧滑入，返回一级页时当前页向右滑出 + 一级页从左侧进入
            AnimatedContent(
                targetState = page,
                modifier = Modifier.padding(padding),
                transitionSpec = settingsPageTransition(),
                label = "settingsPage",
            ) { target ->
                if (target == "root") {
                    SettingsRootMenu(
                        showKeys = showKeys,
                        onToggleShowKeys = { showKeys = it },
                        onNavigate = { page = it },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 键盘弹出时把内容顶起，保证多行「自定义提示」始终可见可编辑
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        when (target) {
                        "api" -> ApiPage(
                            deepSeekKey, deepSeekBaseUrl, deepSeekModel,
                            modelOptions, modelLoading, modelMenuExpanded, modelError,
                            showKeys,
                            onKey = { deepSeekKey = it },
                            onBaseUrl = { deepSeekBaseUrl = it },
                            onModel = { deepSeekModel = it },
                            onModelMenuExpanded = { modelMenuExpanded = it },
                            onRefreshModels = {
                                modelLoading = true
                                modelError = null
                                scope.launch {
                                    try {
                                        DeepSeekClient().listModels().fold(
                                            onSuccess = { models ->
                                                modelOptions = models
                                                modelMenuExpanded = true
                                            },
                                            onFailure = { e ->
                                                modelError = e.message ?: "获取模型列表失败"
                                            }
                                        )
                                    } finally {
                                        modelLoading = false
                                    }
                                }
                            },
                        )
                        "ov" -> OvPage(
                            ovUrl, ovKey, ovUser,
                            ovFindThreshold, ovFindLimit,
                            ovPeerId, ovWorkspacePeer,
                            ovRecallQuestion, ovRecallStep, ovCommit,
                            showKeys,
                            onUrl = { ovUrl = it },
                            onKey = { ovKey = it },
                            onUser = { ovUser = it },
                            onFindThreshold = { ovFindThreshold = it },
                            onFindLimit = { ovFindLimit = it },
                            onPeerId = { ovPeerId = it },
                            onWorkspacePeer = { ovWorkspacePeer = it },
                            onRecallQuestion = { ovRecallQuestion = it },
                            onRecallStep = { ovRecallStep = it },
                            onCommit = { ovCommit = it },
                        )
                        "storage" -> StoragePage(
                            s3Endpoint, s3AccessKey, s3SecretKey, showKeys,
                            onEndpoint = { s3Endpoint = it },
                            onAccessKey = { s3AccessKey = it },
                            onSecretKey = { s3SecretKey = it },
                        )
                        "skillhub" -> SkillHubPage(
                            skillRepo, skillBranch, skillType, skillBase, skillToken, showKeys,
                            onRepo = { skillRepo = it },
                            onBranch = { skillBranch = it },
                            onType = { skillType = it },
                            onBase = { skillBase = it },
                            onToken = { skillToken = it },
                        )
                        "features" -> FeaturesPage(
                            skipSsl, maxRounds, thinkingTimeout, backgroundEnabled, userTips, showInjected,
                            chatUiStream, chatShowReasoning, chatShowToolCalls, chatShowToolEdit,
                            onSkipSsl = { skipSsl = it },
                            onMaxRounds = { maxRounds = it },
                            onThinkingTimeout = { thinkingTimeout = it },
                            onBackgroundEnabled = { backgroundEnabled = it },
                            onUserTips = { userTips = it },
                            onShowInjected = { showInjected = it },
                            onChatUiStream = { chatUiStream = it },
                            onShowReasoning = { chatShowReasoning = it },
                            onShowToolCalls = { chatShowToolCalls = it },
                            onShowToolEdit = { chatShowToolEdit = it },
                        )
                        "import" -> ImportPage(
                            configUrl, importing, importMsg, remoteJson,
                            accountOptions, accountMenuExpanded, selectedAccount, loadingAccounts,
                            onConfigUrl = { configUrl = it },
                            onImporting = { importing = it },
                            onImportMsg = { importMsg = it },
                            onRemoteJson = { remoteJson = it },
                            onAccountOptions = { accountOptions = it },
                            onAccountMenuExpanded = { accountMenuExpanded = it },
                            onSelectedAccount = { selectedAccount = it },
                            onLoadingAccounts = { loadingAccounts = it },
                            onRefreshFromPrefs = { refreshFromPrefs() },
                        )
                        "about" -> AboutPage(
                            updateState, onUpdateState = { updateState = it },
                        )
                    }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

// ==================== 一级菜单：灰底 + 白色卡片 ====================

@Composable
private fun SettingsRootMenu(
    modifier: Modifier = Modifier,
    showKeys: Boolean,
    onToggleShowKeys: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        SettingsGroup(title = "模型与服务") {
            SettingsNavRow(Icons.Outlined.Cloud, "API 配置") { onNavigate("api") }
            SettingsNavRow(Icons.Outlined.Psychology, "OpenViking 记忆") { onNavigate("ov") }
            SettingsNavRow(Icons.Outlined.Storage, "对象存储 / S3") { onNavigate("storage") }
            SettingsNavRow(Icons.Outlined.Extension, "Skill 仓库") { onNavigate("skillhub") }
            SettingsNavRow(Icons.Outlined.Download, "配置导入") { onNavigate("import") }
        }

        SettingsGroup(title = "通用") {
            SettingsNavRow(Icons.Outlined.Tune, "功能与提示词") { onNavigate("features") }
            FormSwitchRow("显示 API Key", showKeys, onToggleShowKeys)
            SettingsNavRow(Icons.Outlined.Info, "关于") { onNavigate("about") }
        }

        Spacer(Modifier.height(28.dp))

        // 底部说明：与官方一致的小字页脚
        Text(
            "内容由 AI 生成，请仔细甄别，并合法使用",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 分组：灰色小标题（在卡片外）+ 白色大圆角卡片，无描边无阴影，卡内不分隔线。
 */
@Composable
private fun SettingsGroup(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (title != null) {
        Column(Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                color = cs.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    Surface(
        color = cs.surface,
        shape = AppRadii.group,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

/** 卡内分隔线（仅长表单使用）：右侧留 16dp */
@Composable
private fun SettingsDivider(inset: Dp = 16.dp) {
    HorizontalDivider(
        Modifier.padding(start = inset, end = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 一级菜单行：线性图标 + 标题 + 右侧值 + ›（官方扁平样式） */
@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(15.dp))
        Text(label, fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 14.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 行内图标：官方风格的纯线性图标，无底色无瓦片。
 * dialog 的大号场景（size>=40）稍放大，其余统一 23dp。
 */
@Composable
private fun IconTile(icon: ImageVector, tint: Color, size: Dp = 32.dp) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(if (size >= 40.dp) 30.dp else 23.dp),
    )
}

// ==================== 各分页 ====================

@Composable
private fun ApiPage(
    deepSeekKey: String, deepSeekBaseUrl: String, deepSeekModel: String,
    modelOptions: List<String>, modelLoading: Boolean, modelMenuExpanded: Boolean, modelError: String?,
    showKeys: Boolean,
    onKey: (String) -> Unit, onBaseUrl: (String) -> Unit, onModel: (String) -> Unit,
    onModelMenuExpanded: (Boolean) -> Unit, onRefreshModels: (() -> Unit),
) {
    Spacer(Modifier.height(6.dp))
    SettingsGroup("DeepSeek API", subtitle = "Key 与接口地址改动后立即生效") {
        FormField("API Key", deepSeekKey, onKey, masked = !showKeys)
        SettingsDivider()
        FormField("Base URL", deepSeekBaseUrl, onBaseUrl)
        SettingsDivider()
        SelectField(
            label = "模型",
            value = deepSeekModel,
            placeholder = "点右侧刷新拉取可用模型",
            options = modelOptions,
            expanded = modelMenuExpanded,
            onExpandedChange = onModelMenuExpanded,
            onSelect = onModel,
            trailing = {
                IconButton(onClick = onRefreshModels, enabled = !modelLoading) {
                    if (modelLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新模型列表",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
    if (modelError != null) FormNote(modelError, isError = true)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun OvPage(
    ovUrl: String, ovKey: String, ovUser: String,
    ovFindThreshold: String, ovFindLimit: String,
    ovPeerId: String, ovWorkspacePeer: Boolean,
    ovRecallQuestion: Boolean, ovRecallStep: Boolean, ovCommit: Boolean,
    showKeys: Boolean,
    onUrl: (String) -> Unit, onKey: (String) -> Unit, onUser: (String) -> Unit,
    onFindThreshold: (String) -> Unit, onFindLimit: (String) -> Unit, onPeerId: (String) -> Unit,
    onWorkspacePeer: (Boolean) -> Unit,
    onRecallQuestion: (Boolean) -> Unit, onRecallStep: (Boolean) -> Unit, onCommit: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    SettingsGroup("连接") {
        FormField("服务器地址", ovUrl, onUrl)
        SettingsDivider()
        FormField("API Key", ovKey, onKey, masked = !showKeys)
        SettingsDivider()
        FormField("用户名", ovUser, onUser)
    }

    SettingsGroup("自动记忆", subtitle = "控制每轮对话去记忆库捞多少、捞多准") {
        FormSwitchRow("对问题检索（每轮）", ovRecallQuestion, onRecallQuestion,
            icon = Icons.Outlined.Textsms)
        SettingsDivider()
        FormSwitchRow("对问题/助手/工具检索（每步）", ovRecallStep, onRecallStep,
            icon = Icons.Outlined.Psychology)
        SettingsDivider()
        FormSwitchRow("会话自动抓取并提交（commit）", ovCommit, onCommit,
            icon = Icons.Outlined.Cloud)
        SettingsDivider()
        FormField("匹配阈值 (0-1)", ovFindThreshold, onValueChange = { v ->
            onFindThreshold(v.filter { c -> c.isDigit() || c == '.' })
        }, supporting = "相似度低于该值的记忆不会注入")
        SettingsDivider()
        FormField("注入条数 (0=关闭)", ovFindLimit, onValueChange = { v ->
            val filtered = v.filter { c -> c.isDigit() }
            if ((filtered.toIntOrNull() ?: 0) in 0..10) onFindLimit(filtered)
        }, supporting = "最多 10 条，0 表示完全关闭记忆注入")
    }

    SettingsGroup("记忆高级") {
        FormField("Peer ID（留空则按应用自动派生）", ovPeerId, onPeerId)
        SettingsDivider()
        FormSwitchRow("按应用派生 Peer", ovWorkspacePeer, onWorkspacePeer,
            icon = Icons.Outlined.Extension)
    }

    Spacer(Modifier.height(24.dp))
}

@Composable
private fun StoragePage(
    s3Endpoint: String, s3AccessKey: String, s3SecretKey: String, showKeys: Boolean,
    onEndpoint: (String) -> Unit, onAccessKey: (String) -> Unit, onSecretKey: (String) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    SettingsGroup("连接设置", subtitle = "用于 objstore 技能的 S3 兼容对象存储") {
        FormField("端点", s3Endpoint, onEndpoint)
        SettingsDivider()
        FormField("访问密钥 ID", s3AccessKey, onAccessKey, masked = !showKeys)
        SettingsDivider()
        FormField("秘密访问密钥", s3SecretKey, onSecretKey, masked = !showKeys)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SkillHubPage(
    repo: String, branch: String, type: String, base: String, token: String, showKeys: Boolean,
    onRepo: (String) -> Unit, onBranch: (String) -> Unit,
    onType: (String) -> Unit, onBase: (String) -> Unit, onToken: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember(repo, branch, type, base, token) {
        SkillRepoConfig(repo = repo, branch = branch, base = base, type = type, token = token)
    }

    var installed by remember { mutableStateOf(emptyList<SkillStore.InstalledSkill>()) }
    var localLoading by remember { mutableStateOf(true) }
    var localMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    var remote by remember { mutableStateOf(emptyList<RemoteSkill>()) }
    var remoteLoaded by remember { mutableStateOf(false) }
    var remoteLoading by remember { mutableStateOf(false) }
    var busySkill by remember { mutableStateOf<String?>(null) }
    var remoteMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    var pendingDelete by remember { mutableStateOf<SkillStore.InstalledSkill?>(null) }

    fun reloadLocal() {
        scope.launch {
            installed = withContext(Dispatchers.IO) { SkillStore.list(context) }
            localLoading = false
        }
    }

    LaunchedEffect(Unit) { reloadLocal() }

    // ---------------- 仓库设置 ----------------

    Spacer(Modifier.height(6.dp))
    SettingsGroup("远程技能仓库") {
        FormField("仓库 (owner/repo)", repo, onRepo)
        SettingsDivider()
        FormField("分支", branch, onBranch)
        SettingsDivider()
        FormField("类型 (github/gitea，留空自动)", type, onType)
        SettingsDivider()
        FormField("Gitea 基址 (如 http://192.168.30.181:3000)", base, onBase)
        SettingsDivider()
        FormField("访问令牌（私有仓库，可选）", token, onToken, masked = !showKeys)
        FormHint(
            "配置 Gitea 基址后默认按 gitea 处理；同名技能为覆盖安装（会保留本地 config.json）。"
                + "下载即在进程内执行其代码，仅从可信来源安装。"
        )
    }

    // ---------------- 已安装技能 ----------------

    SettingsHeaderRow(
        title = "已安装 Skills（${installed.size}）",
        subtitle = "关闭开关即对 LLM 隐藏（改写 # status: disable，文件保留）",
        trailing = {
            IconButton(onClick = { localLoading = true; reloadLocal() }) {
                if (localLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新已安装列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
    SettingsGroup {
        when {
            localLoading -> Box(Modifier.fillMaxWidth().padding(20.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            installed.isEmpty() -> FormHint("暂无已安装 skill，可从下方远程仓库下载。")
            else -> installed.forEachIndexed { index, skill ->
                InstalledSkillRow(
                    skill = skill,
                    onToggle = { enabled ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                SkillStore.setEnabled(context, skill.name, enabled)
                            }
                            if (ok) {
                                localMessage = (
                                    if (enabled) "已启用「${skill.name}」，LLM 可再次调用"
                                    else "已隐藏「${skill.name}」，LLM 不再可见"
                                    ) to false
                                installed = installed.map {
                                    if (it.name == skill.name) it.copy(enabled = enabled) else it
                                }
                                remote = remote.map {
                                    if (it.name == skill.name) it.copy(enabled = enabled) else it
                                }
                            } else {
                                localMessage = ("操作失败：无法写入 ${skill.path}") to true
                            }
                        }
                    },
                    onDelete = { pendingDelete = skill },
                )
                if (index < installed.lastIndex) SettingsDivider(inset = 16.dp)
            }
        }
    }
    localMessage?.let { (text, isError) -> FormNote(text, isError) }

    // ---------------- 远程技能 ----------------

    SettingsHeaderRow(
        title = "远程仓库",
        subtitle = config.label(),
    )
    PillButton(
        text = if (remoteLoaded) "刷新远程列表" else "获取远程技能列表",
        icon = Icons.Outlined.CloudDownload,
        loading = remoteLoading,
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        scope.launch {
            remoteLoading = true
            remoteMessage = null
            try {
                remote = withContext(Dispatchers.IO) {
                    SkillRepository(context, config).listRemote()
                }
                remoteLoaded = true
                remoteMessage = "已获取 ${remote.size} 个远程 skill" to false
            } catch (e: Exception) {
                remoteMessage = ("获取失败：" + (e.message ?: e.javaClass.simpleName)) to true
            } finally {
                remoteLoading = false
            }
        }
    }
    remoteMessage?.let { (text, isError) -> FormNote(text, isError) }

    if (remoteLoaded && !remoteLoading) {
        if (remote.isEmpty()) {
            FormNote("仓库索引为空")
        } else {
            SettingsGroup {
                remote.forEachIndexed { index, item ->
                    RemoteSkillRow(
                        skill = item,
                        busy = busySkill == item.name,
                        onInstall = {
                            scope.launch {
                                busySkill = item.name
                                remoteMessage = null
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        SkillRepository(context, config).install(item.name)
                                    }
                                    installed = withContext(Dispatchers.IO) { SkillStore.list(context) }
                                    remote = remote.map {
                                        if (it.name == item.name) it.copy(installed = true, enabled = true) else it
                                    }
                                    val extra = when {
                                        result.configKept && result.inherited > 0 ->
                                            "；已按 App 设置更新 ${result.inherited} 项配置"
                                        result.configKept -> "；保留本地 config.json"
                                        result.inherited > 0 ->
                                            "；已从 App 设置填入 ${result.inherited} 项配置"
                                        result.configCreated -> "；已生成 config.json，请按需填写"
                                        else -> ""
                                    }
                                    remoteMessage = (
                                        "已下载「${result.name}」（${result.files.size} 个文件）" + extra
                                        ) to false
                                } catch (e: Exception) {
                                    remoteMessage = ("下载失败：" + (e.message ?: e.javaClass.simpleName)) to true
                                } finally {
                                    busySkill = null
                                }
                            }
                        },
                    )
                    if (index < remote.lastIndex) SettingsDivider(inset = 16.dp)
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // ---------------- 删除确认 ----------------

    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            icon = {
                IconTile(
                    icon = Icons.Outlined.DeleteOutline,
                    tint = MaterialTheme.colorScheme.error,
                    size = 44.dp,
                )
            },
            title = {
                Text(
                    "删除 skill",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Text(
                    "确定删除「${skill.name}」？文件会立即移除，LLM 将无法再调用。",
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { SkillStore.delete(context, skill.name) }
                            if (ok) {
                                installed = installed.filterNot { it.name == skill.name }
                                remote = remote.map {
                                    if (it.name == skill.name) it.copy(installed = false, enabled = false) else it
                                }
                                localMessage = "已删除「${skill.name}」" to false
                            } else {
                                localMessage = ("删除失败：" + skill.path) to true
                            }
                        }
                    },
                    shape = AppRadii.capsule,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDelete = null },
                    shape = AppRadii.capsule,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstalledSkillRow(
    skill: SkillStore.InstalledSkill,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            tint = if (skill.enabled) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                SkillTag(
                    if (skill.enabled) "启用" else "已隐藏",
                    if (skill.enabled) Accent.green else cs.onSurfaceVariant,
                )
            }
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                "文件夹 · ${skill.name}/",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = skill.enabled, onCheckedChange = onToggle, colors = switchColors())
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "删除 ${skill.name}",
                modifier = Modifier.size(20.dp),
                tint = cs.error,
            )
        }
    }
}

/** 全站统一的开关配色：开启=品牌蓝 + 白滑块，关闭=灰轨道 + 描边 */
@Composable
private fun switchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/** 远程 skill 行：名称 / 说明 / 平台依赖 / 下载按钮。 */
@Composable
private fun RemoteSkillRow(
    skill: RemoteSkill,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = if (skill.installed) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                when {
                    skill.installed && skill.enabled -> SkillTag("已安装", Accent.green)
                    skill.installed -> SkillTag("已安装 · 已隐藏", cs.onSurfaceVariant)
                    else -> SkillTag("未安装", cs.onSurfaceVariant)
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            val meta = buildList {
                if (skill.platform.isNotEmpty()) add("平台：" + skill.platform.joinToString("/"))
                if (skill.requires.isNotEmpty()) add("依赖：" + skill.requires.joinToString("/"))
            }.joinToString("   ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (skill.installed) {
            OutlinedButton(
                onClick = onInstall,
                shape = AppRadii.capsule,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("重新下载", fontSize = 13.sp) }
        } else {
            Button(
                onClick = onInstall,
                shape = AppRadii.capsule,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) { Text("下载", fontSize = 13.sp) }
        }
    }
}

/** 状态徽章：底色取强调色 12%，文字用本色，保证浅/深色都读得清 */
@Composable
private fun SkillTag(text: String, color: Color) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    Box(
        Modifier
            .clip(AppRadii.tag)
            .background(color.copy(alpha = if (dark) 0.22f else 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (dark) color.copy(alpha = 0.95f) else color,
        )
    }
}

@Composable
private fun FeaturesPage(
    skipSsl: Boolean, maxRounds: String, thinkingTimeout: String, backgroundEnabled: Boolean,
    userTips: String, showInjected: Boolean,
    chatUiStream: Boolean, showReasoning: Boolean, showToolCalls: Boolean, showToolEdit: Boolean,
    onSkipSsl: (Boolean) -> Unit, onMaxRounds: (String) -> Unit, onThinkingTimeout: (String) -> Unit,
    onBackgroundEnabled: (Boolean) -> Unit, onUserTips: (String) -> Unit,
    onShowInjected: (Boolean) -> Unit,
    onChatUiStream: (Boolean) -> Unit, onShowReasoning: (Boolean) -> Unit,
    onShowToolCalls: (Boolean) -> Unit, onShowToolEdit: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    SettingsGroup("聊天界面", subtitle = "控制会话里展示哪些过程信息") {
        FormSwitchRow("新版聊天界面（思考流）", chatUiStream, onChatUiStream,
            icon = Icons.Outlined.Stars)
        SettingsDivider()
        FormSwitchRow("展示思维链内容", showReasoning, onShowReasoning,
            icon = Icons.Outlined.Psychology)
        SettingsDivider()
        FormSwitchRow("展示工具调用", showToolCalls, onShowToolCalls,
            icon = Icons.Outlined.Build)
        SettingsDivider()
        FormSwitchRow("展示编辑工具（script 代码 diff）", showToolEdit, onShowToolEdit,
            icon = Icons.Outlined.Edit)
    }

    SettingsGroup("功能") {
        FormField("最大工具调用轮次", maxRounds,
            onValueChange = { onMaxRounds(it.filter { c -> c.isDigit() }) },
            supporting = "单次回复最多循环调用工具的次数")
        SettingsDivider()
        FormField("思考熔断（分钟，0=关闭）", thinkingTimeout,
            onValueChange = { onThinkingTimeout(it.filter { c -> c.isDigit() }) },
            supporting = "超过时长自动中断，避免长任务卡死")
        SettingsDivider()
        FormSwitchRow("后台保活", backgroundEnabled, onBackgroundEnabled,
            icon = Icons.Outlined.Bolt)
        SettingsDivider()
        FormSwitchRow("显示注入信息（skill 名单 / 记忆召回）", showInjected, onShowInjected,
            icon = Icons.Outlined.Visibility)
        SettingsDivider()
        FormSwitchRow("跳过证书验证（自签名 HTTPS）", skipSsl, onSkipSsl,
            icon = Icons.Outlined.Lock,
            supporting = "仅内网自签名服务需要，公网友情提示关闭")
    }

    SettingsGroup("用户提示") {
        FormField("自定义提示", userTips, onUserTips, singleLine = false)
        FormHint("每个会话注入一次，新会话生效")
    }

    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ImportPage(
    configUrl: String, importing: Boolean, importMsg: String?, remoteJson: String?,
    accountOptions: List<String>, accountMenuExpanded: Boolean, selectedAccount: String?, loadingAccounts: Boolean,
    onConfigUrl: (String) -> Unit, onImporting: (Boolean) -> Unit, onImportMsg: (String?) -> Unit, onRemoteJson: (String?) -> Unit,
    onAccountOptions: (List<String>) -> Unit, onAccountMenuExpanded: (Boolean) -> Unit, onSelectedAccount: (String?) -> Unit, onLoadingAccounts: (Boolean) -> Unit,
    onRefreshFromPrefs: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(6.dp))
    SettingsGroup("配置导入", subtitle = "从远端 JSON 一次性拉齐 Key / 端点 / 记忆配置") {
        FormField("配置地址 (JSON)", configUrl, onConfigUrl)
        if (accountOptions.isNotEmpty()) {
            SettingsDivider()
            SelectField(
                label = "账号",
                value = selectedAccount ?: "",
                placeholder = "选择账号",
                options = accountOptions,
                expanded = accountMenuExpanded,
                onExpandedChange = onAccountMenuExpanded,
                onSelect = onSelectedAccount,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    PillButton(text = "加载", loading = loadingAccounts, enabled = configUrl.isNotBlank()) {
        onLoadingAccounts(true); onImportMsg(null)
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchConfigText(configUrl) }
                AppPreferences.configUrl = configUrl
                onRemoteJson(json)
                val accounts = AppPreferences.listConfigAccounts(json)
                onAccountOptions(accounts)
                onSelectedAccount(if (accounts.size == 1) accounts[0] else null)
                onImportMsg(if (accounts.isEmpty()) "已加载（扁平配置），可直接应用" else "找到 ${accounts.size} 个账号，请选择后应用")
            } catch (e: Exception) {
                onImportMsg("加载失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                onLoadingAccounts(false)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    PillButton(text = "应用配置", loading = importing, enabled = remoteJson != null) {
        onImporting(true); onImportMsg(null)
        scope.launch {
            try {
                val json = remoteJson ?: withContext(Dispatchers.IO) { fetchConfigText(configUrl) }
                val applied = AppPreferences.applyRemoteConfig(json, selectedAccount)
                onRefreshFromPrefs()
                onImportMsg("已应用 $applied 项配置" + if (selectedAccount != null) "（账号: $selectedAccount）" else "")
            } catch (e: Exception) {
                onImportMsg("应用失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                onImporting(false)
            }
        }
    }
    if (importMsg != null) FormNote(importMsg, isError = importMsg.startsWith("加载失败") || importMsg.startsWith("应用失败"))
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun AboutPage(updateState: UpdateState, onUpdateState: (UpdateState) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(6.dp))
    SettingsGroup("说明") {
        InfoRow(Icons.Outlined.Lock, "DeepSeek API Key 是必填项")
        InfoRow(Icons.Outlined.Psychology, "OpenViking 用于长期记忆（可选）")
        InfoRow(Icons.Outlined.Build, "Python 首次使用自动解压")
        InfoRow(Icons.Outlined.Bolt, "设置保存后立即生效")
    }

    SettingsGroup("版本") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.Outlined.Info, tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Build ${BuildConfig.VERSION_CODE}",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
            }
            when (val state = updateState) {
                is UpdateState.Checking ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

                is UpdateState.Latest -> SkillTag("已是最新", Accent.green)

                is UpdateState.Available -> Button(
                    onClick = { openInBrowser(context, state.url) },
                    shape = AppRadii.capsule,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("下载 ${state.version}", fontSize = 13.sp) }

                is UpdateState.Error -> Text(
                    state.message,
                    fontSize = 12.sp,
                    color = cs.error,
                    modifier = Modifier.widthIn(max = 120.dp),
                )

                is UpdateState.Idle -> OutlinedButton(
                    onClick = {
                        onUpdateState(UpdateState.Checking)
                        scope.launch {
                            val info = AppUpdater.checkUpdate()
                            onUpdateState(when {
                                info.error.isNotBlank() -> UpdateState.Error(info.error)
                                info.hasUpdate -> UpdateState.Available(
                                    info.latestVersion,
                                    info.downloadUrl,
                                    info.releaseNotes,
                                )
                                else -> UpdateState.Latest
                            })
                        }
                    },
                    shape = AppRadii.capsule,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("检查更新", fontSize = 13.sp) }
            }
        }
    }

    // 有新版本时展示发布说明（来自 Release 正文）
    val available = updateState as? UpdateState.Available
    if (available != null && available.notes.isNotBlank()) {
        SettingsGroup("更新内容") { CardText(available.notes) }
    }
    Spacer(Modifier.height(24.dp))
}

// ==================== 卡片内的行与控件 ====================

/** 带副标题与右侧动作的分组头（与 SettingsGroup 的小标题同一基线） */
@Composable
private fun SettingsHeaderRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 6.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant,
                letterSpacing = 0.2.sp,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** 卡片内的纯文本块 */
@Composable
private fun CardText(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

/** 说明行：线性图标 + 一句话（关于页的「说明」用） */
@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(15.dp))
        Text(
            text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 结果反馈条：成功/失败用色彩区分，比纯灰字更容易被看到 */
@Composable
private fun FormNote(text: String, isError: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val tint = if (isError) cs.error else Accent.green
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = AppRadii.field,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isError) Icons.Outlined.Info else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = cs.onSurface,
                modifier = Modifier.weight(1f))
        }
    }
}

/** 卡片内提示（紧贴字段下方，左对齐、留白小） */
@Composable
private fun FormHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp),
    )
}

/**
 * 卡片内可编辑字段：标签在上（12sp 灰），值包在浅灰圆角容器里，
 * 聚焦时描边转为品牌蓝（1.6dp）。masked=密码遮罩，右侧附「显/隐」按钮。
 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    // 失焦即保存：记录是否真正获得过焦点，避免首次组合时 isFocused=false 触发一次多余写入
    val autoSave = LocalSettingsAutoSave.current
    var everFocused by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.outlineVariant,
        label = "fieldBorder",
    )

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(AppRadii.field)
                .background(cs.surfaceVariant.copy(alpha = 0.55f))
                .border(
                    width = if (focused) 1.6.dp else 1.dp,
                    color = borderColor,
                    shape = AppRadii.field,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    visualTransformation = if (masked && !revealed) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                        .onFocusChanged { state ->
                            focused = state.isFocused
                            if (state.isFocused) {
                                everFocused = true
                            } else if (everFocused) {
                                everFocused = false
                                autoSave()
                            }
                        },
                )
                if (onClick != null) {
                    Box(Modifier.matchParentSize().clickable { onClick() })
                }
            }
            // 密文字段：就地切换显/隐，不必回一级页改全局开关
            if (masked) {
                IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) "隐藏" else "显示",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) trailing()
        }
        if (supporting != null) {
            Text(
                supporting,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            )
        }
    }
}

/**
 * 只读下拉字段：外观与 FormField 的容器一致，右侧加向下箭头；
 * 选项为空时不可点（模型列表需要先刷新）。
 */
@Composable
private fun SelectField(
    label: String,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(AppRadii.field)
                    .background(cs.surfaceVariant.copy(alpha = 0.55f))
                    .border(1.dp, cs.outlineVariant, AppRadii.field)
                    .clickable(enabled = options.isNotEmpty()) { onExpandedChange(true) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value.ifBlank { placeholder },
                    fontSize = 15.sp,
                    color = if (value.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trailing != null) trailing()
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    contentDescription = "展开",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 15.sp) },
                        onClick = { onSelect(option); onExpandedChange(false) },
                    )
                }
            }
        }
    }
}

/** 卡片内开关行：可选线性图标 + 标题（+ 说明）+ 右侧 Switch */
@Composable
private fun FormSwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val autoSave = LocalSettingsAutoSave.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (icon != null) 16.dp else 20.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(15.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = cs.onSurface)
            if (supporting != null) {
                Text(
                    supporting,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                onChecked(it)
                // 开关没有失焦事件，切换即写盘
                autoSave()
            },
            colors = switchColors(),
        )
    }
}

/** 主按钮：品牌蓝、胶囊形、通栏 */
@Composable
private fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = AppRadii.capsule,
        contentPadding = PaddingValues(vertical = 14.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 新版本跳浏览器下载：应用内不再下载/安装 APK */
private fun openInBrowser(context: Context, url: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "没有可用的下载地址", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "无法打开浏览器: " + (e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun fetchConfigText(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    if (conn is HttpsURLConnection) {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    return conn.inputStream.bufferedReader().readText()
}

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Latest : UpdateState()
    data class Available(val version: String, val url: String, val notes: String = "") : UpdateState()
    data class Error(val message: String) : UpdateState()
}

package com.androidagent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.updater.AppUpdater
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
    var ovScoreThreshold by remember { mutableStateOf(AppPreferences.ovScoreThreshold.toString()) }
    var ovSearchDisplayCount by remember { mutableStateOf(AppPreferences.ovSearchDisplayCount.toString()) }
    var ovFindThreshold by remember { mutableStateOf(AppPreferences.ovFindThreshold.toString()) }
    var ovFindLimit by remember { mutableStateOf(AppPreferences.ovFindLimit.toString()) }
    var ovPeerId by remember { mutableStateOf(AppPreferences.ovPeerId) }
    var ovWorkspacePeer by remember { mutableStateOf(AppPreferences.ovWorkspacePeer) }
    var ovRecallDedup by remember { mutableStateOf(AppPreferences.ovRecallDedup) }
    var ovProfileEnabled by remember { mutableStateOf(AppPreferences.ovProfileEnabled) }
    var ovAutoCapture by remember { mutableStateOf(AppPreferences.ovAutoCapture) }
    var backgroundEnabled by remember { mutableStateOf(AppPreferences.backgroundServiceEnabled) }
    var s3Endpoint by remember { mutableStateOf(AppPreferences.s3EndpointUrl) }
    var s3AccessKey by remember { mutableStateOf(AppPreferences.s3AccessKey) }
    var s3SecretKey by remember { mutableStateOf(AppPreferences.s3SecretKey) }
    var skipSsl by remember { mutableStateOf(AppPreferences.skipSslVerification) }
    var systemPrompt by remember { mutableStateOf(
        AppPreferences.systemPrompt.ifBlank {
            """你是 Android Agent，一个运行在 Android 设备上的 AI 助手。
可写应用空间：/data/user/0/com.androidagent/files/

请用中文回答用户的问题。

明确对话主题后，调用 rename_session 设置简洁的对话标题（≤20字）。

【记忆规则】
记忆是给未来的自己看的。善用 openviking_remember 记录：
- 有用的操作、配置、步骤、关键信息 → 必须记录
- 发现错误记忆 → 立即修正，不留错误"""
        }
    ) }
    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var configUrl by remember { mutableStateOf(AppPreferences.configUrl) }
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
        ovScoreThreshold = AppPreferences.ovScoreThreshold.toString()
        ovSearchDisplayCount = AppPreferences.ovSearchDisplayCount.toString()
        ovFindThreshold = AppPreferences.ovFindThreshold.toString()
        ovFindLimit = AppPreferences.ovFindLimit.toString()
        ovPeerId = AppPreferences.ovPeerId
        ovWorkspacePeer = AppPreferences.ovWorkspacePeer
        ovRecallDedup = AppPreferences.ovRecallDedup
        ovProfileEnabled = AppPreferences.ovProfileEnabled
        ovAutoCapture = AppPreferences.ovAutoCapture
        backgroundEnabled = AppPreferences.backgroundServiceEnabled
        s3Endpoint = AppPreferences.s3EndpointUrl
        s3AccessKey = AppPreferences.s3AccessKey
        s3SecretKey = AppPreferences.s3SecretKey
        skipSsl = AppPreferences.skipSslVerification
        systemPrompt = AppPreferences.systemPrompt
        configUrl = AppPreferences.configUrl
    }

    fun savePrefs() {
        AppPreferences.deepSeekApiKey = deepSeekKey
        AppPreferences.deepSeekBaseUrl = deepSeekBaseUrl
        AppPreferences.deepSeekModel = deepSeekModel
        AppPreferences.openVikingUrl = ovUrl
        AppPreferences.openVikingKey = ovKey
        AppPreferences.openVikingUser = ovUser
        AppPreferences.ovScoreThreshold = (ovScoreThreshold.toFloatOrNull() ?: 0.4f).coerceIn(0f, 1f)
        AppPreferences.ovSearchDisplayCount = ovSearchDisplayCount.toIntOrNull() ?: 2
        AppPreferences.ovFindThreshold = (ovFindThreshold.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
        AppPreferences.ovFindLimit = ovFindLimit.toIntOrNull() ?: 2
        AppPreferences.ovPeerId = ovPeerId
        AppPreferences.ovWorkspacePeer = ovWorkspacePeer
        AppPreferences.ovRecallDedup = ovRecallDedup
        AppPreferences.ovProfileEnabled = ovProfileEnabled
        AppPreferences.ovAutoCapture = ovAutoCapture
        AppPreferences.maxToolRounds = maxRounds.toIntOrNull() ?: 8
        AppPreferences.thinkingTimeoutMinutes = thinkingTimeout.toIntOrNull() ?: 0
        AppPreferences.systemPrompt = systemPrompt
        AppPreferences.s3EndpointUrl = s3Endpoint
        AppPreferences.s3AccessKey = s3AccessKey
        AppPreferences.s3SecretKey = s3SecretKey
        AppPreferences.skipSslVerification = skipSsl

        val wasEnabled = AppPreferences.backgroundServiceEnabled
        AppPreferences.backgroundServiceEnabled = backgroundEnabled
        if (backgroundEnabled && !wasEnabled) {
            com.androidagent.ForegroundService.start(context)
        } else if (!backgroundEnabled && wasEnabled) {
            com.androidagent.ForegroundService.stop(context)
        }
        saved = true
    }

    val titles = mapOf(
        "root" to "设置",
        "api" to "API 配置",
        "ov" to "OpenViking 记忆",
        "storage" to "对象存储",
        "features" to "功能与提示词",
        "import" to "配置导入",
        "about" to "关于",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[page] ?: "设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { if (page == "root") onBack() else page = "root" }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = {
                    if (page != "root") {
                        TextButton(onClick = { savePrefs() }, enabled = !saved) {
                            Text(if (saved) "已保存" else "保存")
                        }
                    }
                },
            )
        }
    ) { padding ->
        if (page == "root") {
            SettingsRootMenu(
                modifier = Modifier.padding(padding),
                showKeys = showKeys,
                saved = saved,
                onToggleShowKeys = { showKeys = it },
                onSave = { savePrefs() },
                onNavigate = { page = it },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    "api" -> ApiPage(
                        deepSeekKey, deepSeekBaseUrl, deepSeekModel,
                        modelOptions, modelLoading, modelMenuExpanded, modelError,
                        showKeys,
                        onKey = { deepSeekKey = it; saved = false },
                        onBaseUrl = { deepSeekBaseUrl = it; saved = false },
                        onModel = { deepSeekModel = it; saved = false },
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
                        ovUrl, ovKey, ovUser, ovScoreThreshold, ovSearchDisplayCount,
                        ovFindThreshold, ovFindLimit,
                        ovPeerId, ovWorkspacePeer, ovRecallDedup, ovProfileEnabled, ovAutoCapture,
                        showKeys,
                        onUrl = { ovUrl = it; saved = false },
                        onKey = { ovKey = it; saved = false },
                        onUser = { ovUser = it; saved = false },
                        onThreshold = { ovScoreThreshold = it; saved = false },
                        onDisplayCount = { ovSearchDisplayCount = it; saved = false },
                        onFindThreshold = { ovFindThreshold = it; saved = false },
                        onFindLimit = { ovFindLimit = it; saved = false },
                        onPeerId = { ovPeerId = it; saved = false },
                        onWorkspacePeer = { ovWorkspacePeer = it; saved = false },
                        onRecallDedup = { ovRecallDedup = it; saved = false },
                        onProfileEnabled = { ovProfileEnabled = it; saved = false },
                        onAutoCapture = { ovAutoCapture = it; saved = false },
                    )
                    "storage" -> StoragePage(
                        s3Endpoint, s3AccessKey, s3SecretKey, showKeys,
                        onEndpoint = { s3Endpoint = it; saved = false },
                        onAccessKey = { s3AccessKey = it; saved = false },
                        onSecretKey = { s3SecretKey = it; saved = false },
                    )
                    "features" -> FeaturesPage(
                        skipSsl, maxRounds, thinkingTimeout, backgroundEnabled, systemPrompt,
                        onSkipSsl = { skipSsl = it; saved = false },
                        onMaxRounds = { maxRounds = it; saved = false },
                        onThinkingTimeout = { thinkingTimeout = it; saved = false },
                        onBackgroundEnabled = { backgroundEnabled = it; saved = false },
                        onSystemPrompt = { systemPrompt = it; saved = false },
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

// ==================== 根菜单（kelivo 式分组菜单） ====================

@Composable
private fun SettingsRootMenu(
    modifier: Modifier = Modifier,
    showKeys: Boolean,
    saved: Boolean,
    onToggleShowKeys: (Boolean) -> Unit,
    onSave: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        MenuSectionTitle("通用设置")
        MenuSection {
            MenuNavRow(Icons.Outlined.Tune, "功能与提示词", "安全 · 后台保活 · 系统提示词") { onNavigate("features") }
            MenuDivider(cs)
            MenuNavRow(Icons.Outlined.Info, "关于", "说明 · 版本与更新") { onNavigate("about") }
        }

        Spacer(Modifier.height(16.dp))

        MenuSectionTitle("模型与服务")
        MenuSection {
            MenuNavRow(Icons.Outlined.Cloud, "API 配置", "DeepSeek 模型与接口") { onNavigate("api") }
            MenuDivider(cs)
            MenuNavRow(Icons.Outlined.Psychology, "OpenViking 记忆", "服务器 · 检索 · 记忆高级") { onNavigate("ov") }
            MenuDivider(cs)
            MenuNavRow(Icons.Outlined.Storage, "对象存储 / S3", "rustfs 兼容") { onNavigate("storage") }
            MenuDivider(cs)
            MenuNavRow(Icons.Outlined.Download, "配置导入", "远程 JSON 一键导入") { onNavigate("import") }
        }

        Spacer(Modifier.height(16.dp))

        // 显示密钥 + 保存
        MenuSection {
            MenuToggleRow(cs, "显示 API Key", "明文展示密钥输入框", showKeys, onToggleShowKeys)
            MenuDivider(cs)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSave, enabled = !saved, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saved) "已保存" else "保存")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MenuSectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp, top = 2.dp),
    )
}

@Composable
private fun MenuSection(content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppRadii.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.12f)),
    ) {
        Column(content = content)
    }
}

@Composable
private fun MenuDivider(cs: androidx.compose.material3.ColorScheme) {
    HorizontalDivider(
        color = cs.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun MenuNavRow(icon: ImageVector, label: String, subtitle: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuLeadingIcon(icon, cs)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = cs.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.5f))
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun MenuToggleRow(cs: androidx.compose.material3.ColorScheme, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface)
            Text(subtitle, fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.5f))
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MenuLeadingIcon(icon: ImageVector, cs: androidx.compose.material3.ColorScheme) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(cs.surface.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
    }
}

// ==================== 各分页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiPage(
    deepSeekKey: String, deepSeekBaseUrl: String, deepSeekModel: String,
    modelOptions: List<String>, modelLoading: Boolean, modelMenuExpanded: Boolean, modelError: String?,
    showKeys: Boolean,
    onKey: (String) -> Unit, onBaseUrl: (String) -> Unit, onModel: (String) -> Unit,
    onModelMenuExpanded: (Boolean) -> Unit, onRefreshModels: (() -> Unit),
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("DeepSeek API")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = deepSeekKey, onValueChange = onKey,
                label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = deepSeekBaseUrl, onValueChange = onBaseUrl,
                label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { if (modelOptions.isNotEmpty()) onModelMenuExpanded(it) },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = deepSeekModel, onValueChange = onModel,
                        label = { Text("模型") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }
                    )
                    ExposedDropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { onModelMenuExpanded(false) }) {
                        modelOptions.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { onModel(m); onModelMenuExpanded(false) })
                        }
                    }
                }
                IconButton(onClick = onRefreshModels, enabled = !modelLoading) {
                    if (modelLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表")
                }
            }
            if (modelError != null) {
                Spacer(Modifier.height(2.dp))
                Text(modelError!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun OvPage(
    ovUrl: String, ovKey: String, ovUser: String,
    ovScoreThreshold: String, ovSearchDisplayCount: String,
    ovFindThreshold: String, ovFindLimit: String,
    ovPeerId: String, ovWorkspacePeer: Boolean, ovRecallDedup: Boolean, ovProfileEnabled: Boolean, ovAutoCapture: Boolean,
    showKeys: Boolean,
    onUrl: (String) -> Unit, onKey: (String) -> Unit, onUser: (String) -> Unit,
    onThreshold: (String) -> Unit, onDisplayCount: (String) -> Unit,
    onFindThreshold: (String) -> Unit, onFindLimit: (String) -> Unit, onPeerId: (String) -> Unit,
    onWorkspacePeer: (Boolean) -> Unit, onRecallDedup: (Boolean) -> Unit, onProfileEnabled: (Boolean) -> Unit, onAutoCapture: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("OpenViking 记忆")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovUrl, onValueChange = onUrl, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = ovKey, onValueChange = onKey, label = { Text("API Key") }, modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation())
                OutlinedTextField(value = ovUser, onValueChange = onUser, label = { Text("用户名") }, modifier = Modifier.weight(1f), singleLine = true)
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            SubTitle("搜索工具")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovScoreThreshold, onValueChange = { v ->
                onThreshold(v.filter { c -> c.isDigit() || c == '.' })
            }, label = { Text("匹配阈值 (0-1，如 0.52)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("阈值越高召回越精准。LLM 调用搜索未指定时使用此值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovSearchDisplayCount, onValueChange = { v ->
                val filtered = v.filter { c -> c.isDigit() }
                val num = filtered.toIntOrNull() ?: 0
                if (num in 0..10) onDisplayCount(filtered)
            }, label = { Text("返回条数 (0=关闭)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            SubTitle("自动注入")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovFindThreshold, onValueChange = { v ->
                onFindThreshold(v.filter { c -> c.isDigit() || c == '.' })
            }, label = { Text("匹配阈值 (0-1，如 0.5)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("对话时自动检索并注入相关记忆的阈值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovFindLimit, onValueChange = { v ->
                val filtered = v.filter { c -> c.isDigit() }
                val num = filtered.toIntOrNull() ?: 0
                if (num in 0..10) onFindLimit(filtered)
            }, label = { Text("注入条数 (0=关闭)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            SubTitle("记忆高级")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = ovPeerId, onValueChange = onPeerId, label = { Text("Peer ID（留空则按应用自动派生）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))

            SwitchRow("按应用派生 Peer", "不同应用记忆互相隔离", onWorkspacePeer, ovWorkspacePeer)
            SwitchRow("召回去重", "跨轮不重复注入同一记忆", onRecallDedup, ovRecallDedup)
            SwitchRow("会话开始注入记忆索引", "新建会话时列出可用记忆主题", onProfileEnabled, ovProfileEnabled)
            SwitchRow("自动捕获对话", "对话后自动归档并提取长期记忆", onAutoCapture, ovAutoCapture)
        }
    }
}

@Composable
private fun StoragePage(
    s3Endpoint: String, s3AccessKey: String, s3SecretKey: String, showKeys: Boolean,
    onEndpoint: (String) -> Unit, onAccessKey: (String) -> Unit, onSecretKey: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("对象存储 (S3)")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = s3Endpoint, onValueChange = onEndpoint, label = { Text("服务地址 (如 http://192.168.1.100:9000)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = s3AccessKey, onValueChange = onAccessKey, label = { Text("Access Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = s3SecretKey, onValueChange = onSecretKey, label = { Text("Secret Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation())
        }
    }
}

@Composable
private fun FeaturesPage(
    skipSsl: Boolean, maxRounds: String, thinkingTimeout: String, backgroundEnabled: Boolean, systemPrompt: String,
    onSkipSsl: (Boolean) -> Unit, onMaxRounds: (String) -> Unit, onThinkingTimeout: (String) -> Unit, onBackgroundEnabled: (Boolean) -> Unit, onSystemPrompt: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = skipSsl, onCheckedChange = onSkipSsl)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("跳过证书验证", style = MaterialTheme.typography.bodyMedium)
                Text("适用于自签名 HTTPS（OpenViking / 对象存储等全局生效）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("功能")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最大工具调用轮次", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = maxRounds, onValueChange = { onMaxRounds(it.filter { c -> c.isDigit() }) }, modifier = Modifier.width(72.dp), singleLine = true)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("思考熔断", style = MaterialTheme.typography.bodyMedium)
                    Text("思维链超过该分钟数后注入提示直接作答 (0=关闭)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = thinkingTimeout, onValueChange = { onThinkingTimeout(it.filter { c -> c.isDigit() }) }, modifier = Modifier.width(72.dp), singleLine = true)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("后台保活", style = MaterialTheme.typography.bodyMedium)
                    Text("切到后台时保持运行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = backgroundEnabled, onCheckedChange = onBackgroundEnabled)
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("系统提示词")
            Spacer(Modifier.height(4.dp))
            Text("自定义系统提示词，留空则使用默认。修改后新对话生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = systemPrompt, onValueChange = onSystemPrompt, label = { Text("系统提示词") }, modifier = Modifier.fillMaxWidth().height(200.dp), maxLines = 10)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPage(
    configUrl: String, importing: Boolean, importMsg: String?, remoteJson: String?,
    accountOptions: List<String>, accountMenuExpanded: Boolean, selectedAccount: String?, loadingAccounts: Boolean,
    onConfigUrl: (String) -> Unit, onImporting: (Boolean) -> Unit, onImportMsg: (String?) -> Unit, onRemoteJson: (String?) -> Unit,
    onAccountOptions: (List<String>) -> Unit, onAccountMenuExpanded: (Boolean) -> Unit, onSelectedAccount: (String?) -> Unit, onLoadingAccounts: (Boolean) -> Unit,
    onRefreshFromPrefs: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("配置导入")
            Spacer(Modifier.height(4.dp))
            Text("填入返回 JSON 的配置地址，可包含多个账号（顶层 key 为账号名，其对象为设置）。加载后选择账号一键应用，免去逐项输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = configUrl, onValueChange = onConfigUrl, label = { Text("配置地址 (JSON)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
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
                }, enabled = !loadingAccounts && configUrl.isNotBlank()) {
                    if (loadingAccounts) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("加载")
                }
                if (accountOptions.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = onAccountMenuExpanded, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(value = selectedAccount ?: "选择账号", onValueChange = {}, label = { Text("账号") }, modifier = Modifier.fillMaxWidth().menuAnchor(), singleLine = true, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) })
                        ExposedDropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { onAccountMenuExpanded(false) }) {
                            accountOptions.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { onSelectedAccount(a); onAccountMenuExpanded(false) }) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
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
                }, enabled = !importing && remoteJson != null) {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("应用配置")
                }
                if (importMsg != null) {
                    Text(importMsg!!, style = MaterialTheme.typography.bodySmall,
                        color = if (importMsg!!.startsWith("加载失败") || importMsg!!.startsWith("应用失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AboutPage(updateState: UpdateState, onUpdateState: (UpdateState) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("说明")
            Spacer(Modifier.height(4.dp))
            Text("• DeepSeek API Key 是必填项\n• OpenViking 用于长期记忆存储（可选）\n• Python 首次使用自动解压\n• 设置保存后立即生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(12.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)))
                    Text("Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (val state = updateState) {
                    is UpdateState.Checking -> LinearProgressIndicator(Modifier.width(100.dp))
                    is UpdateState.Latest -> Text("已是最新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is UpdateState.Available -> Button(onClick = {
                        onUpdateState(UpdateState.Downloading)
                        scope.launch {
                            AppUpdater.downloadAndInstall(context, state.url, state.version)
                            onUpdateState(UpdateState.Idle)
                        }
                    }, enabled = updateState !is UpdateState.Downloading) { Text("下载 ${state.version}") }
                    is UpdateState.Downloading -> LinearProgressIndicator(Modifier.width(100.dp))
                    is UpdateState.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is UpdateState.Idle -> OutlinedButton(onClick = {
                        onUpdateState(UpdateState.Checking)
                        scope.launch {
                            val info = AppUpdater.checkUpdate()
                            onUpdateState(when {
                                info.error.isNotBlank() -> UpdateState.Error(info.error)
                                info.hasUpdate -> UpdateState.Available(info.latestVersion, info.downloadUrl)
                                else -> UpdateState.Latest
                            })
                        }
                    }) { Text("检查更新") }
                }
            }
        }
    }
}

// ==================== 小工具 ====================

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f))
}

@Composable
private fun SubTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, onChecked: (Boolean) -> Unit, checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
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
    data class Available(val version: String, val url: String) : UpdateState()
    data object Downloading : UpdateState()
    data class Error(val message: String) : UpdateState()
}

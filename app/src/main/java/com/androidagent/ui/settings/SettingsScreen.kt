package com.androidagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.androidagent.data.updater.AppUpdater
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
    var ovScoreThreshold by remember { mutableStateOf(AppPreferences.ovScoreThreshold) }
    var ovSearchDisplayCount by remember { mutableStateOf(AppPreferences.ovSearchDisplayCount.toString()) }
    var ovPeerId by remember { mutableStateOf(AppPreferences.ovPeerId) }
    var ovWorkspacePeer by remember { mutableStateOf(AppPreferences.ovWorkspacePeer) }
    var ovRecallDedup by remember { mutableStateOf(AppPreferences.ovRecallDedup) }
    var ovProfileEnabled by remember { mutableStateOf(AppPreferences.ovProfileEnabled) }
    var ovAutoCapture by remember { mutableStateOf(AppPreferences.ovAutoCapture) }
    var backgroundEnabled by remember { mutableStateOf(AppPreferences.backgroundServiceEnabled) }
    var s3Endpoint by remember { mutableStateOf(AppPreferences.s3EndpointUrl) }
    var s3AccessKey by remember { mutableStateOf(AppPreferences.s3AccessKey) }
    var s3SecretKey by remember { mutableStateOf(AppPreferences.s3SecretKey) }
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

    fun refreshFromPrefs() {
        deepSeekKey = AppPreferences.deepSeekApiKey
        deepSeekBaseUrl = AppPreferences.deepSeekBaseUrl
        deepSeekModel = AppPreferences.deepSeekModel
        ovUrl = AppPreferences.openVikingUrl
        ovKey = AppPreferences.openVikingKey
        ovUser = AppPreferences.openVikingUser
        maxRounds = AppPreferences.maxToolRounds.toString()
        ovScoreThreshold = AppPreferences.ovScoreThreshold
        ovSearchDisplayCount = AppPreferences.ovSearchDisplayCount.toString()
        ovPeerId = AppPreferences.ovPeerId
        ovWorkspacePeer = AppPreferences.ovWorkspacePeer
        ovRecallDedup = AppPreferences.ovRecallDedup
        ovProfileEnabled = AppPreferences.ovProfileEnabled
        ovAutoCapture = AppPreferences.ovAutoCapture
        backgroundEnabled = AppPreferences.backgroundServiceEnabled
        s3Endpoint = AppPreferences.s3EndpointUrl
        s3AccessKey = AppPreferences.s3AccessKey
        s3SecretKey = AppPreferences.s3SecretKey
        systemPrompt = AppPreferences.systemPrompt
        configUrl = AppPreferences.configUrl
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ========== API 配置 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🤖 DeepSeek API", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = deepSeekKey,
                        onValueChange = { deepSeekKey = it; saved = false },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )

                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = deepSeekBaseUrl,
                        onValueChange = { deepSeekBaseUrl = it; saved = false },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(
                            expanded = modelMenuExpanded,
                            onExpandedChange = { if (modelOptions.isNotEmpty()) modelMenuExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = deepSeekModel,
                                onValueChange = { deepSeekModel = it; saved = false },
                                label = { Text("模型") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }
                            )
                            ExposedDropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                modelOptions.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = {
                                            deepSeekModel = m
                                            saved = false
                                            modelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
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
                            enabled = !modelLoading
                        ) {
                            if (modelLoading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表")
                            }
                        }
                    }
                    if (modelError != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(modelError!!, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    Text("🧠 OpenViking 外置记忆", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ovUrl,
                        onValueChange = { ovUrl = it; saved = false },
                        label = { Text("服务器地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = ovKey,
                            onValueChange = { ovKey = it; saved = false },
                            label = { Text("API Key") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = ovUser,
                            onValueChange = { ovUser = it; saved = false },
                            label = { Text("用户名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }

            // ========== 对象存储 (S3) ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("☁️ 对象存储 (S3)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = s3Endpoint,
                        onValueChange = { s3Endpoint = it; saved = false },
                        label = { Text("服务地址 (如 http://192.168.1.100:9000)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = s3AccessKey,
                        onValueChange = { s3AccessKey = it; saved = false },
                        label = { Text("Access Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = s3SecretKey,
                        onValueChange = { s3SecretKey = it; saved = false },
                        label = { Text("Secret Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )
                }
            }

            // ========== 配置导入 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("⚙️ 配置导入", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "填入返回 JSON 的配置地址，可包含多个账号（顶层 key 为账号名，其对象为设置）。加载后选择账号一键应用，免去逐项输入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = configUrl,
                        onValueChange = { configUrl = it },
                        label = { Text("配置地址 (JSON)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                loadingAccounts = true
                                importMsg = null
                                scope.launch {
                                    try {
                                        val json = withContext(Dispatchers.IO) { fetchConfigText(configUrl) }
                                        AppPreferences.configUrl = configUrl
                                        remoteJson = json
                                        val accounts = AppPreferences.listConfigAccounts(json)
                                        accountOptions = accounts
                                        selectedAccount = if (accounts.size == 1) accounts[0] else null
                                        importMsg = if (accounts.isEmpty()) {
                                            "已加载（扁平配置），可直接应用"
                                        } else {
                                            "找到 ${accounts.size} 个账号，请选择后应用"
                                        }
                                    } catch (e: Exception) {
                                        importMsg = "加载失败: ${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        loadingAccounts = false
                                    }
                                }
                            },
                            enabled = !loadingAccounts && configUrl.isNotBlank()
                        ) {
                            if (loadingAccounts) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("加载")
                            }
                        }
                        if (accountOptions.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = accountMenuExpanded,
                                onExpandedChange = { accountMenuExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = selectedAccount ?: "选择账号",
                                    onValueChange = {},
                                    label = { Text("账号") },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    singleLine = true,
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = accountMenuExpanded,
                                    onDismissRequest = { accountMenuExpanded = false }
                                ) {
                                    accountOptions.forEach { a ->
                                        DropdownMenuItem(
                                            text = { Text(a) },
                                            onClick = {
                                                selectedAccount = a
                                                accountMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                importing = true
                                importMsg = null
                                scope.launch {
                                    try {
                                        val json = remoteJson
                                            ?: withContext(Dispatchers.IO) { fetchConfigText(configUrl) }
                                        val applied = AppPreferences.applyRemoteConfig(json, selectedAccount)
                                        refreshFromPrefs()
                                        importMsg = "已应用 $applied 项配置" +
                                            if (selectedAccount != null) "（账号: $selectedAccount）" else ""
                                    } catch (e: Exception) {
                                        importMsg = "应用失败: ${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        importing = false
                                    }
                                }
                            },
                            enabled = !importing && remoteJson != null
                        ) {
                            if (importing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("应用配置")
                            }
                        }
                        if (importMsg != null) {
                            Text(
                                importMsg!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (importMsg!!.startsWith("加载失败") || importMsg!!.startsWith("应用失败")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
            }

            // ========== 记忆检索 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🧠 记忆检索", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "匹配阈值: ${String.format("%.2f", ovScoreThreshold)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = ovScoreThreshold,
                        onValueChange = { ovScoreThreshold = (it * 20).toInt() / 20f; saved = false },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("阈值越高召回越精准。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ovSearchDisplayCount,
                        onValueChange = { v ->
                            val filtered = v.filter { c -> c.isDigit() }
                            val num = filtered.toIntOrNull() ?: 0
                            if (num in 0..10) {
                                ovSearchDisplayCount = filtered; saved = false
                            }
                        },
                        label = { Text("自动注入条目数 (0=关闭)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // ========== 记忆高级 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🧠 记忆高级", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ovPeerId,
                        onValueChange = { ovPeerId = it; saved = false },
                        label = { Text("Peer ID（留空则按应用自动派生）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("按应用派生 Peer", style = MaterialTheme.typography.bodyMedium)
                            Text("不同应用记忆互相隔离", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ovWorkspacePeer, onCheckedChange = { ovWorkspacePeer = it; saved = false })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("召回去重", style = MaterialTheme.typography.bodyMedium)
                            Text("跨轮不重复注入同一记忆", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ovRecallDedup, onCheckedChange = { ovRecallDedup = it; saved = false })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("会话开始注入记忆索引", style = MaterialTheme.typography.bodyMedium)
                            Text("新建会话时列出可用记忆主题", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ovProfileEnabled, onCheckedChange = { ovProfileEnabled = it; saved = false })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动捕获对话", style = MaterialTheme.typography.bodyMedium)
                            Text("对话后自动归档并提取长期记忆", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ovAutoCapture, onCheckedChange = { ovAutoCapture = it; saved = false })
                    }
                }
            }

            // ========== 功能设置 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("⚙️ 功能", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    // 工具轮次
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最大工具调用轮次", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = maxRounds,
                            onValueChange = { maxRounds = it.filter { c -> c.isDigit() }; saved = false },
                            modifier = Modifier.width(72.dp),
                            singleLine = true
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 6.dp))

                    // 后台保活
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台保活", style = MaterialTheme.typography.bodyMedium)
                            Text("切到后台时保持运行", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = backgroundEnabled,
                            onCheckedChange = { backgroundEnabled = it; saved = false }
                        )
                    }
                }
            }

            // ========== 系统提示词 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("💬 系统提示词", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "自定义系统提示词，留空则使用默认。修改后新对话生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it; saved = false },
                        label = { Text("系统提示词") },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        maxLines = 10
                    )
                }
            }

            // ========== 显示密钥 + 保存 ==========
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showKeys, onCheckedChange = { showKeys = it })
                    Spacer(Modifier.width(4.dp))
                    Text("显示 API Key", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        AppPreferences.deepSeekApiKey = deepSeekKey
                        AppPreferences.deepSeekBaseUrl = deepSeekBaseUrl
                        AppPreferences.deepSeekModel = deepSeekModel
                        AppPreferences.openVikingUrl = ovUrl
                        AppPreferences.openVikingKey = ovKey
                        AppPreferences.openVikingUser = ovUser
                        AppPreferences.ovScoreThreshold = ovScoreThreshold
                        AppPreferences.ovSearchDisplayCount = ovSearchDisplayCount.toIntOrNull() ?: 3
                        AppPreferences.ovPeerId = ovPeerId
                        AppPreferences.ovWorkspacePeer = ovWorkspacePeer
                        AppPreferences.ovRecallDedup = ovRecallDedup
                        AppPreferences.ovProfileEnabled = ovProfileEnabled
                        AppPreferences.ovAutoCapture = ovAutoCapture
                        AppPreferences.maxToolRounds = maxRounds.toIntOrNull() ?: 8
                        AppPreferences.systemPrompt = systemPrompt
                        AppPreferences.s3EndpointUrl = s3Endpoint
                        AppPreferences.s3AccessKey = s3AccessKey
                        AppPreferences.s3SecretKey = s3SecretKey

                        val wasEnabled = AppPreferences.backgroundServiceEnabled
                        AppPreferences.backgroundServiceEnabled = backgroundEnabled
                        if (backgroundEnabled && !wasEnabled) {
                            com.androidagent.ForegroundService.start(context)
                        } else if (!backgroundEnabled && wasEnabled) {
                            com.androidagent.ForegroundService.stop(context)
                        }
                        saved = true
                    },
                    enabled = !saved
                ) {
                    Text(if (saved) "✓ 已保存" else "保存")
                }
            }

            // ========== 说明 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📝 说明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "• DeepSeek API Key 是必填项\n" +
                                "• OpenViking 用于长期记忆存储（可选）\n" +
                                "• Python 首次使用自动解压\n" +
                                "• 设置保存后立即生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ========== 版本与更新 ==========
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("版本 ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Build ${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        when (val state = updateState) {
                            is UpdateState.Checking -> {
                                LinearProgressIndicator(Modifier.width(100.dp))
                            }
                            is UpdateState.Latest -> {
                                Text("已是最新", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            is UpdateState.Available -> {
                                Button(
                                    onClick = {
                                        updateState = UpdateState.Downloading
                                        scope.launch {
                                            AppUpdater.downloadAndInstall(
                                                context, state.url, state.version
                                            )
                                            updateState = UpdateState.Idle
                                        }
                                    },
                                    enabled = updateState !is UpdateState.Downloading
                                ) { Text("下载 ${state.version}") }
                            }
                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(Modifier.width(100.dp))
                            }
                            is UpdateState.Error -> {
                                Text(state.message, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            is UpdateState.Idle -> {
                                OutlinedButton(
                                    onClick = {
                                        updateState = UpdateState.Checking
                                        scope.launch {
                                            val info = AppUpdater.checkUpdate()
                                            updateState = when {
                                                info.error.isNotBlank() -> UpdateState.Error(info.error)
                                                info.hasUpdate -> UpdateState.Available(info.latestVersion, info.downloadUrl)
                                                else -> UpdateState.Latest
                                            }
                                        }
                                    }
                                ) { Text("检查更新") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 拉取远程配置文本。仅为「配置导入」这一个场景忽略 TLS 证书校验
 * （信任任意证书 + 跳过主机名校验），其余 HTTPS 连接（如 DeepSeek）不受影响。
 * 仅用于访问用户自托管的局域网配置服务器；配置非敏感，且可用 http:// 代替。
 */
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

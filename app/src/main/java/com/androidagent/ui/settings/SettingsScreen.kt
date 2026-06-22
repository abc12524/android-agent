package com.androidagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.updater.AppUpdater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    var deepSeekKey by remember { mutableStateOf(AppPreferences.deepSeekApiKey) }
    var qianFanKey by remember { mutableStateOf(AppPreferences.qianFanApiKey) }
    var ovUrl by remember { mutableStateOf(AppPreferences.openVikingUrl) }
    var ovKey by remember { mutableStateOf(AppPreferences.openVikingKey) }
    var ovUser by remember { mutableStateOf(AppPreferences.openVikingUser) }
    var maxRounds by remember { mutableStateOf(AppPreferences.maxToolRounds.toString()) }
    var ovScoreThreshold by remember { mutableStateOf(AppPreferences.ovScoreThreshold) }
    var ovSearchDisplayCount by remember { mutableStateOf(AppPreferences.ovSearchDisplayCount.toString()) }
    var backgroundEnabled by remember { mutableStateOf(AppPreferences.backgroundServiceEnabled) }
    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    Text("🔍 百度千帆 API", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = qianFanKey,
                        onValueChange = { qianFanKey = it; saved = false },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )

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
                        AppPreferences.qianFanApiKey = qianFanKey
                        AppPreferences.openVikingUrl = ovUrl
                        AppPreferences.openVikingKey = ovKey
                        AppPreferences.openVikingUser = ovUser
                        AppPreferences.ovScoreThreshold = ovScoreThreshold
                        AppPreferences.ovSearchDisplayCount = ovSearchDisplayCount.toIntOrNull() ?: 3
                        AppPreferences.maxToolRounds = maxRounds.toIntOrNull() ?: 8

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
                                "• 百度千帆 Key 用于搜索功能（可选）\n" +
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

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Latest : UpdateState()
    data class Available(val version: String, val url: String) : UpdateState()
    data object Downloading : UpdateState()
    data class Error(val message: String) : UpdateState()
}

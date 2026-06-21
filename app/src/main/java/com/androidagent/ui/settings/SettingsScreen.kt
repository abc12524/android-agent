package com.androidagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidagent.data.AppPreferences

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
    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // DeepSeek 配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🤖 DeepSeek API", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = deepSeekKey,
                        onValueChange = { deepSeekKey = it; saved = false },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )
                }
            }

            // 百度千帆配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔍 百度千帆 API", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = qianFanKey,
                        onValueChange = { qianFanKey = it; saved = false },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )
                }
            }

            // OpenViking 配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🧠 OpenViking 外置记忆", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ovUrl,
                        onValueChange = { ovUrl = it; saved = false },
                        label = { Text("服务器地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ovKey,
                        onValueChange = { ovKey = it; saved = false },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ovUser,
                        onValueChange = { ovUser = it; saved = false },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // 对话设置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ 对话设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = maxRounds,
                        onValueChange = { maxRounds = it.filter { c -> c.isDigit() }; saved = false },
                        label = { Text("最大工具调用轮次") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // 显示密钥切换
            Row(modifier = Modifier.fillMaxWidth()) {
                Switch(
                    checked = showKeys,
                    onCheckedChange = { showKeys = it }
                )
                Spacer(Modifier.width(8.dp))
                Text("显示 API Key", modifier = Modifier.padding(top = 12.dp))
            }

            // 保存按钮
            Button(
                onClick = {
                    AppPreferences.deepSeekApiKey = deepSeekKey
                    AppPreferences.qianFanApiKey = qianFanKey
                    AppPreferences.openVikingUrl = ovUrl
                    AppPreferences.openVikingKey = ovKey
                    AppPreferences.openVikingUser = ovUser
                    AppPreferences.maxToolRounds = maxRounds.toIntOrNull() ?: 8
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saved
            ) {
                Text(if (saved) "✓ 已保存" else "保存设置")
            }

            // 说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📝 说明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "• DeepSeek API Key 是必填项\n" +
                                "• 百度千帆 Key 用于搜索功能（可选）\n" +
                                "• OpenViking 用于长期记忆存储（可选）\n" +
                                "• Python 环境通过 Chaquopy 嵌入（无需额外配置）\n" +
                                "• 设置保存后立即生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

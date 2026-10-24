package com.androidagent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var ovFindThreshold by remember { mutableStateOf(AppPreferences.ovFindThreshold.toString()) }
    var ovFindLimit by remember { mutableStateOf(AppPreferences.ovFindLimit.toString()) }
    var ovPeerId by remember { mutableStateOf(AppPreferences.ovPeerId) }
    var ovWorkspacePeer by remember { mutableStateOf(AppPreferences.ovWorkspacePeer) }
    var ovRecallQuestion by remember { mutableStateOf(AppPreferences.ovRecallQuestion) }
    var ovRecallStep by remember { mutableStateOf(AppPreferences.ovRecallStep) }
    var ovCommit by remember { mutableStateOf(AppPreferences.ovCommit) }
    var showInjected by remember { mutableStateOf(AppPreferences.showInjectedMessages) }
    var backgroundEnabled by remember { mutableStateOf(AppPreferences.backgroundServiceEnabled) }
    var s3Endpoint by remember { mutableStateOf(AppPreferences.s3EndpointUrl) }
    var s3AccessKey by remember { mutableStateOf(AppPreferences.s3AccessKey) }
    var s3SecretKey by remember { mutableStateOf(AppPreferences.s3SecretKey) }
    var skipSsl by remember { mutableStateOf(AppPreferences.skipSslVerification) }
    var userTips by remember { mutableStateOf(AppPreferences.userTips) }
    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
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
        saved = true
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
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
                        ovUrl, ovKey, ovUser,
                        ovFindThreshold, ovFindLimit,
                        ovPeerId, ovWorkspacePeer,
                        ovRecallQuestion, ovRecallStep, ovCommit,
                        showKeys,
                        onUrl = { ovUrl = it; saved = false },
                        onKey = { ovKey = it; saved = false },
                        onUser = { ovUser = it; saved = false },
                        onFindThreshold = { ovFindThreshold = it; saved = false },
                        onFindLimit = { ovFindLimit = it; saved = false },
                        onPeerId = { ovPeerId = it; saved = false },
                        onWorkspacePeer = { ovWorkspacePeer = it; saved = false },
                        onRecallQuestion = { ovRecallQuestion = it; saved = false },
                        onRecallStep = { ovRecallStep = it; saved = false },
                        onCommit = { ovCommit = it; saved = false },
                    )
                    "storage" -> StoragePage(
                        s3Endpoint, s3AccessKey, s3SecretKey, showKeys,
                        onEndpoint = { s3Endpoint = it; saved = false },
                        onAccessKey = { s3AccessKey = it; saved = false },
                        onSecretKey = { s3SecretKey = it; saved = false },
                    )
                    "skillhub" -> SkillHubPage(
                        skillRepo, skillBranch, skillType, skillBase, skillToken, showKeys,
                        onRepo = { skillRepo = it; saved = false },
                        onBranch = { skillBranch = it; saved = false },
                        onType = { skillType = it; saved = false },
                        onBase = { skillBase = it; saved = false },
                        onToken = { skillToken = it; saved = false },
                    )
                    "features" -> FeaturesPage(
                        skipSsl, maxRounds, thinkingTimeout, backgroundEnabled, userTips, showInjected,
                        onSkipSsl = { skipSsl = it; saved = false },
                        onMaxRounds = { maxRounds = it; saved = false },
                        onThinkingTimeout = { thinkingTimeout = it; saved = false },
                        onBackgroundEnabled = { backgroundEnabled = it; saved = false },
                        onUserTips = { userTips = it; saved = false },
                        onShowInjected = { showInjected = it; saved = false },
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

// ==================== 根菜单（分组列表风格） ====================

@Composable
private fun SettingsRootMenu(
    modifier: Modifier = Modifier,
    showKeys: Boolean,
    saved: Boolean,
    onToggleShowKeys: (Boolean) -> Unit,
    onSave: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        MenuSectionTitle("通用设置")
        MenuNavRow(Icons.Outlined.Tune, "功能与提示词") { onNavigate("features") }
        MenuDivider()
        MenuNavRow(Icons.Outlined.Info, "关于") { onNavigate("about") }

        Spacer(Modifier.height(28.dp))

        MenuSectionTitle("模型与服务")
        MenuNavRow(Icons.Outlined.Cloud, "API 配置") { onNavigate("api") }
        MenuDivider()
        MenuNavRow(Icons.Outlined.Psychology, "OpenViking 记忆") { onNavigate("ov") }
        MenuDivider()
        MenuNavRow(Icons.Outlined.Storage, "对象存储 / S3") { onNavigate("storage") }
        MenuDivider()
        MenuNavRow(Icons.Outlined.Extension, "Skill 仓库") { onNavigate("skillhub") }
        MenuDivider()
        MenuNavRow(Icons.Outlined.Download, "配置导入") { onNavigate("import") }

        Spacer(Modifier.height(28.dp))

        MenuSectionTitle("其它")
        MenuNavRow(Icons.Outlined.Image, "显示 API Key", value = if (showKeys) "开" else "关") {
            onToggleShowKeys(!showKeys)
        }

        Spacer(Modifier.height(20.dp))
        PillButton(text = if (saved) "已保存" else "保存", enabled = !saved, onClick = onSave)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MenuSectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 40.dp),
    )
}

@Composable
private fun MenuNavRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 14.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
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
    FormTitle("DeepSeek API")
    Spacer(Modifier.height(2.dp))
    FormField("API Key", deepSeekKey, onKey, masked = !showKeys)
    FormField("Base URL", deepSeekBaseUrl, onBaseUrl)
    Box {
        FormField(
            label = "模型",
            value = deepSeekModel,
            onValueChange = {},
            readOnly = true,
            onClick = { if (modelOptions.isNotEmpty()) onModelMenuExpanded(true) },
            trailing = {
                IconButton(onClick = onRefreshModels, enabled = !modelLoading) {
                    if (modelLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { onModelMenuExpanded(false) }) {
            modelOptions.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { onModel(m); onModelMenuExpanded(false) })
            }
        }
    }
    if (modelError != null) FormNote(modelError, isError = true)
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
    FormTitle("连接")
    Spacer(Modifier.height(2.dp))
    FormField("服务器地址", ovUrl, onUrl)
    FormField("API Key", ovKey, onKey, masked = !showKeys)
    FormField("用户名", ovUser, onUser)

    Spacer(Modifier.height(24.dp))
    FormTitle("自动记忆")
    Spacer(Modifier.height(2.dp))
    FormSwitchRow("对问题检索（每轮）", ovRecallQuestion, onRecallQuestion)
    FormSwitchRow("对问题/助手/工具检索（每步）", ovRecallStep, onRecallStep)
    FormSwitchRow("会话自动抓取并提交（commit）", ovCommit, onCommit)
    FormField("匹配阈值 (0-1)", ovFindThreshold, onValueChange = { v ->
        onFindThreshold(v.filter { c -> c.isDigit() || c == '.' })
    })
    FormField("注入条数 (0=关闭)", ovFindLimit, onValueChange = { v ->
        val filtered = v.filter { c -> c.isDigit() }
        if ((filtered.toIntOrNull() ?: 0) in 0..10) onFindLimit(filtered)
    })

    Spacer(Modifier.height(24.dp))
    FormTitle("记忆高级")
    Spacer(Modifier.height(2.dp))
    FormField("Peer ID（留空则按应用自动派生）", ovPeerId, onPeerId)
    FormSwitchRow("按应用派生 Peer", ovWorkspacePeer, onWorkspacePeer)
}

@Composable
private fun StoragePage(
    s3Endpoint: String, s3AccessKey: String, s3SecretKey: String, showKeys: Boolean,
    onEndpoint: (String) -> Unit, onAccessKey: (String) -> Unit, onSecretKey: (String) -> Unit,
) {
    FormTitle("连接设置")
    Spacer(Modifier.height(2.dp))
    FormField("端点", s3Endpoint, onEndpoint)
    FormField("访问密钥 ID", s3AccessKey, onAccessKey, masked = !showKeys)
    FormField("秘密访问密钥", s3SecretKey, onSecretKey, masked = !showKeys)
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

    FormTitle("远程技能仓库")
    Spacer(Modifier.height(2.dp))
    FormField("仓库 (owner/repo)", repo, onRepo)
    FormField("分支", branch, onBranch)
    FormField("类型 (github/gitea，留空自动)", type, onType)
    FormField("Gitea 基址 (如 http://192.168.30.181:3000)", base, onBase)
    FormField("访问令牌（私有仓库，可选）", token, onToken, masked = !showKeys)
    Spacer(Modifier.height(8.dp))
    Text(
        "配置 Gitea 基址后默认按 gitea 处理；同名技能为覆盖安装（会保留本地 config.json）。" +
            "下载即在进程内执行其代码，仅从可信来源安装。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // ---------------- 已安装技能 ----------------

    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FormTitle("已安装 Skills（${installed.size}）")
            Text(
                "关闭开关即对 LLM 隐藏（改写 # status: disable，文件保留）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    }

    when {
        localLoading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        installed.isEmpty() -> FormNote("暂无已安装 skill，可从下方远程仓库下载。")
        else -> installed.forEach { skill ->
            InstalledSkillRow(
                skill = skill,
                bundled = SkillStore.isBundled(context, skill.name),
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
        }
    }
    localMessage?.let { (text, isError) -> FormNote(text, isError) }

    // ---------------- 远程技能 ----------------

    Spacer(Modifier.height(28.dp))
    FormTitle("远程仓库")
    Text(
        config.label(),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    PillButton(
        text = if (remoteLoaded) "刷新远程列表" else "获取远程技能列表",
        icon = Icons.Outlined.CloudDownload,
        loading = remoteLoading,
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
            remote.forEach { item ->
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
                                val extra = if (result.configCreated) "；已生成 config.json，请按需填写" else ""
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
            }
        }
    }

    // ---------------- 删除确认 ----------------

    pendingDelete?.let { skill ->
        val bundled = SkillStore.isBundled(context, skill.name)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 skill") },
            text = {
                Text(
                    "确定删除「${skill.name}」？文件会立即移除，LLM 将无法再调用。" +
                        if (bundled) "\n\n该 skill 随应用内置，重启应用后会被重新部署。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 已安装 skill 行：名称 / 说明 / 启用开关 / 删除。 */
@Composable
private fun InstalledSkillRow(
    skill: SkillStore.InstalledSkill,
    bundled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                SkillTag(
                    if (skill.enabled) "启用" else "已隐藏",
                    if (skill.enabled) cs.primary else cs.error,
                )
                if (bundled) {
                    Spacer(Modifier.width(4.dp))
                    SkillTag("内置", cs.onSurfaceVariant)
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
            Text(
                if (skill.folder) "文件夹 · ${skill.name}/" else "脚本 · ${skill.name}.py",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "删除 ${skill.name}",
                tint = cs.error,
            )
        }
    }
    HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.5f))
}

/** 远程 skill 行：名称 / 说明 / 平台依赖 / 下载按钮。 */
@Composable
private fun RemoteSkillRow(
    skill: RemoteSkill,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                when {
                    skill.installed && skill.enabled -> SkillTag("已安装", cs.primary)
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
        } else {
            TextButton(onClick = onInstall) {
                Text(if (skill.installed) "重新下载" else "下载", fontSize = 13.sp)
            }
        }
    }
    HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SkillTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 10.sp, color = color)
    }
}

@Composable
private fun FeaturesPage(
    skipSsl: Boolean, maxRounds: String, thinkingTimeout: String, backgroundEnabled: Boolean,
    userTips: String, showInjected: Boolean,
    onSkipSsl: (Boolean) -> Unit, onMaxRounds: (String) -> Unit, onThinkingTimeout: (String) -> Unit,
    onBackgroundEnabled: (Boolean) -> Unit, onUserTips: (String) -> Unit,
    onShowInjected: (Boolean) -> Unit,
) {
    FormTitle("功能")
    Spacer(Modifier.height(2.dp))
    FormField("最大工具调用轮次", maxRounds, onValueChange = { onMaxRounds(it.filter { c -> c.isDigit() }) })
    FormField("思考熔断（分钟，0=关闭）", thinkingTimeout, onValueChange = { onThinkingTimeout(it.filter { c -> c.isDigit() }) })
    FormSwitchRow("后台保活", backgroundEnabled, onBackgroundEnabled)
    FormSwitchRow("显示注入信息（skill 名单 / 记忆召回）", showInjected, onShowInjected)
    FormSwitchRow("跳过证书验证（自签名 HTTPS）", skipSsl, onSkipSsl)

    Spacer(Modifier.height(24.dp))
    FormTitle("用户提示")
    Spacer(Modifier.height(2.dp))
    FormField("自定义提示（每会话注入一次，新会话生效）", userTips, onUserTips, singleLine = false)
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
    FormTitle("配置导入")
    Spacer(Modifier.height(2.dp))
    FormField("配置地址 (JSON)", configUrl, onConfigUrl)
    if (accountOptions.isNotEmpty()) {
        Box {
            FormField(
                label = "账号",
                value = selectedAccount ?: "选择账号",
                onValueChange = {},
                readOnly = true,
                onClick = { onAccountMenuExpanded(true) },
            )
            DropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { onAccountMenuExpanded(false) }) {
                accountOptions.forEach { a ->
                    DropdownMenuItem(text = { Text(a) }, onClick = { onSelectedAccount(a); onAccountMenuExpanded(false) })
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
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
}

@Composable
private fun AboutPage(updateState: UpdateState, onUpdateState: (UpdateState) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    FormTitle("说明")
    Spacer(Modifier.height(6.dp))
    Text(
        "• DeepSeek API Key 是必填项\n• OpenViking 用于长期记忆（可选）\n• Python 首次使用自动解压\n• 设置保存后立即生效",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))
    FormTitle("版本")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Build ${BuildConfig.VERSION_CODE}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (val state = updateState) {
            is UpdateState.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is UpdateState.Latest -> Text("已是最新", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> Button(
                onClick = {
                    onUpdateState(UpdateState.Downloading)
                    scope.launch {
                        AppUpdater.downloadAndInstall(context, state.url, state.version)
                        onUpdateState(UpdateState.Idle)
                    }
                },
                enabled = updateState !is UpdateState.Downloading,
                shape = RoundedCornerShape(20.dp),
            ) { Text("下载 ${state.version}") }
            is UpdateState.Downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is UpdateState.Error -> Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            is UpdateState.Idle -> OutlinedButton(
                onClick = {
                    onUpdateState(UpdateState.Checking)
                    scope.launch {
                        val info = AppUpdater.checkUpdate()
                        onUpdateState(when {
                            info.error.isNotBlank() -> UpdateState.Error(info.error)
                            info.hasUpdate -> UpdateState.Available(info.latestVersion, info.downloadUrl)
                            else -> UpdateState.Latest
                        })
                    }
                },
                shape = RoundedCornerShape(20.dp),
            ) { Text("检查更新") }
        }
    }
}

// ==================== 小工具 ====================

@Composable
private fun FormTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 表单字段：标签在上、无边框输入值在下，字段底部一条发丝分隔线。
 * masked=密码遮罩；readOnly+onClick=只读可点击（用于选择/弹菜单）；trailing=右侧附加控件。
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
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(label, fontSize = 13.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    textStyle = TextStyle(fontSize = 17.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onClick != null) {
                    Box(Modifier.matchParentSize().clickable { onClick() })
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun FormSwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
    HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun FormNote(text: String, isError: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

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
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 16.sp)
        }
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
    data class Available(val version: String, val url: String) : UpdateState()
    data object Downloading : UpdateState()
    data class Error(val message: String) : UpdateState()
}

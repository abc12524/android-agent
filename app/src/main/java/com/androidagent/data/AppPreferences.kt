package com.androidagent.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * API Key 和配置的本地存储
 */
object AppPreferences {
    private const val PREFS_NAME = "android_agent_prefs"
    private lateinit var prefs: SharedPreferences

    private val _showInjected = MutableStateFlow(false)

    /** 是否在历史会话中展示注入消息（skill 名单 / OV 召回），供 UI 实时订阅 */
    val showInjectedFlow: StateFlow<Boolean> = _showInjected.asStateFlow()

    // 新版聊天界面（思考流）：思维链与工具结果浅灰内联，正文常规渲染
    private val _chatUiStream = MutableStateFlow(true)
    val chatUiStreamFlow: StateFlow<Boolean> = _chatUiStream.asStateFlow()

    // 思考流内各区块的展示开关
    private val _showReasoning = MutableStateFlow(true)
    val showReasoningFlow: StateFlow<Boolean> = _showReasoning.asStateFlow()

    private val _showToolCalls = MutableStateFlow(true)
    val showToolCallsFlow: StateFlow<Boolean> = _showToolCalls.asStateFlow()

    private val _showToolEdit = MutableStateFlow(true)
    val showToolEditFlow: StateFlow<Boolean> = _showToolEdit.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _showInjected.value = prefs.getBoolean("show_injected_messages", false)
        _chatUiStream.value = prefs.getBoolean("chat_ui_stream", true)
        _showReasoning.value = prefs.getBoolean("chat_show_reasoning", true)
        _showToolCalls.value = prefs.getBoolean("chat_show_tool_calls", true)
        _showToolEdit.value = prefs.getBoolean("chat_show_tool_edit", true)
    }

    // DeepSeek
    var deepSeekApiKey: String
        get() = prefs.getString("deepseek_api_key", "") ?: ""
        set(value) = prefs.edit().putString("deepseek_api_key", value).apply()

    var deepSeekBaseUrl: String
        get() = prefs.getString("deepseek_base_url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) = prefs.edit().putString("deepseek_base_url", value).apply()

    var deepSeekModel: String
        get() = prefs.getString("deepseek_model", "deepseek-v4-flash") ?: "deepseek-v4-flash"
        set(value) = prefs.edit().putString("deepseek_model", value).apply()

    // OpenViking
    var openVikingUrl: String
        get() = prefs.getString("openviking_url", "") ?: ""
        set(value) = prefs.edit().putString("openviking_url", value).apply()

    var openVikingKey: String
        get() = prefs.getString("openviking_key", "") ?: ""
        set(value) = prefs.edit().putString("openviking_key", value).apply()

    var openVikingUser: String
        get() = prefs.getString("openviking_user", "") ?: ""
        set(value) = prefs.edit().putString("openviking_user", value).apply()

    // OpenViking peer / 隔离设置
    var ovPeerId: String
        get() = prefs.getString("ov_peer_id", "") ?: ""
        set(value) = prefs.edit().putString("ov_peer_id", value).apply()

    var ovWorkspacePeer: Boolean
        get() = prefs.getBoolean("ov_workspace_peer", true)
        set(value) = prefs.edit().putBoolean("ov_workspace_peer", value).apply()

    var ovPeerScope: String
        get() = prefs.getString("ov_peer_scope", "actor") ?: "actor"
        set(value) = prefs.edit().putString("ov_peer_scope", value).apply()

    // OpenViking 自动记忆（三个开关）
    // 每轮以用户问题为 query 检索注入
    var ovRecallQuestion: Boolean
        get() = prefs.getBoolean("ov_recall_question", true)
        set(value) = prefs.edit().putBoolean("ov_recall_question", value).apply()

    // 每个工具轮以整批消息（问题 / 助手信息 / 工具结果）为 query 检索注入
    var ovRecallStep: Boolean
        get() = prefs.getBoolean("ov_recall_step", false)
        set(value) = prefs.edit().putBoolean("ov_recall_step", value).apply()

    // 把对话抓取到 OV session，并在每轮结束时 commit 触发记忆提取
    var ovCommit: Boolean
        get() = prefs.getBoolean("ov_commit", false)
        set(value) = prefs.edit().putBoolean("ov_commit", value).apply()

    // 在历史会话中展示注入消息（skill 名单 / OV 召回）
    var showInjectedMessages: Boolean
        get() = prefs.getBoolean("show_injected_messages", false)
        set(value) {
            prefs.edit().putBoolean("show_injected_messages", value).apply()
            _showInjected.value = value
        }

    // 新版聊天界面（思考流）：思维链 + 工具结果浅灰内联，正文常规渲染；false 回到旧界面
    var chatUiStream: Boolean
        get() = prefs.getBoolean("chat_ui_stream", true)
        set(value) {
            prefs.edit().putBoolean("chat_ui_stream", value).apply()
            _chatUiStream.value = value
        }

    // 思考流内是否展示思维链内容
    var chatShowReasoning: Boolean
        get() = prefs.getBoolean("chat_show_reasoning", true)
        set(value) {
            prefs.edit().putBoolean("chat_show_reasoning", value).apply()
            _showReasoning.value = value
        }

    // 思考流内是否展示工具调用与结果
    var chatShowToolCalls: Boolean
        get() = prefs.getBoolean("chat_show_tool_calls", true)
        set(value) {
            prefs.edit().putBoolean("chat_show_tool_calls", value).apply()
            _showToolCalls.value = value
        }

    // 思考流内是否把 script skill 的改动渲染成代码 diff 编辑界面
    var chatShowToolEdit: Boolean
        get() = prefs.getBoolean("chat_show_tool_edit", true)
        set(value) {
            prefs.edit().putBoolean("chat_show_tool_edit", value).apply()
            _showToolEdit.value = value
        }

    // 对话设置
    var maxToolRounds: Int
        get() = prefs.getInt("max_tool_rounds", 8)
        set(value) = prefs.edit().putInt("max_tool_rounds", value).apply()

    var sessionTimeoutMinutes: Int
        get() = prefs.getInt("session_timeout_minutes", 15)
        set(value) = prefs.edit().putInt("session_timeout_minutes", value).apply()

    // 思考熔断：单轮思维链超过该分钟数后注入提示让其直接作答（0=关闭）
    var thinkingTimeoutMinutes: Int
        get() = prefs.getInt("thinking_timeout_minutes", 0)
        set(value) = prefs.edit().putInt("thinking_timeout_minutes", value).apply()

    // 后台保活
    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean("background_service_enabled", false)
        set(value) = prefs.edit().putBoolean("background_service_enabled", value).apply()

    // 遗留：OpenViking 显式搜索阈值与条数（openviking_tool 已移除，保留供内部调用/远程配置兼容）
    var ovScoreThreshold: Float
        get() = prefs.getFloat("ov_score_threshold", 0.4f)
        set(value) = prefs.edit().putFloat("ov_score_threshold", value).apply()

    var ovSearchDisplayCount: Int
        get() = prefs.getInt("ov_search_display_count", 2)
        set(value) = prefs.edit().putInt("ov_search_display_count", value).apply()

    // 自动注入（find 接口，纯向量语义搜索）默认阈值与条数
    var ovFindThreshold: Float
        get() = prefs.getFloat("ov_find_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("ov_find_threshold", value).apply()

    var ovFindLimit: Int
        get() = prefs.getInt("ov_find_limit", 2)
        set(value) = prefs.edit().putInt("ov_find_limit", value).apply()

    // 用户自定义提示：每会话注入一次（<user_tips>），新会话生效
    var userTips: String
        get() = prefs.getString("user_tips", "") ?: ""
        set(value) = prefs.edit().putString("user_tips", value).apply()

    // 对象存储 (S3)
    var s3EndpointUrl: String
        get() = prefs.getString("s3_endpoint_url", "") ?: ""
        set(value) = prefs.edit().putString("s3_endpoint_url", value).apply()

    var s3AccessKey: String
        get() = prefs.getString("s3_access_key", "") ?: ""
        set(value) = prefs.edit().putString("s3_access_key", value).apply()

    var s3SecretKey: String
        get() = prefs.getString("s3_secret_key", "") ?: ""
        set(value) = prefs.edit().putString("s3_secret_key", value).apply()

    // 跳过证书验证（适用于自签名 HTTPS）
    var skipSslVerification: Boolean
        get() = prefs.getBoolean("skip_ssl_verification", false)
        set(value) = prefs.edit().putBoolean("skip_ssl_verification", value).apply()

    // 远程配置地址（一键导入设置用）
    var configUrl: String
        get() = prefs.getString("config_url", "") ?: ""
        set(value) = prefs.edit().putString("config_url", value).apply()

    // 远程技能仓库（skill_hub 下载源）：owner/repo 与分支，留空用默认
    var skillHubRepo: String
        get() = prefs.getString("skill_hub_repo", "abc12524/shell-skills") ?: "abc12524/shell-skills"
        set(value) = prefs.edit().putString("skill_hub_repo", value).apply()

    var skillHubBranch: String
        get() = prefs.getString("skill_hub_branch", "main") ?: "main"
        set(value) = prefs.edit().putString("skill_hub_branch", value).apply()

    // 仓库类型：github / gitea；留空时按是否配置 skillHubBase 自动推断
    var skillHubType: String
        get() = prefs.getString("skill_hub_type", "") ?: ""
        set(value) = prefs.edit().putString("skill_hub_type", value).apply()

    // Gitea 服务基址（如 http://192.168.30.181:3000）；配置后默认按 gitea 处理
    var skillHubBase: String
        get() = prefs.getString("skill_hub_base", "") ?: ""
        set(value) = prefs.edit().putString("skill_hub_base", value).apply()

    // 私有仓库访问令牌（GitHub / Gitea 通用）
    var skillHubToken: String
        get() = prefs.getString("skill_hub_token", "") ?: ""
        set(value) = prefs.edit().putString("skill_hub_token", value).apply()

    /**
     * 从远程配置 JSON 一次性应用所有已知设置项，避免逐项手动输入。
     * JSON 顶层 key 与下方 SharedPreferences key 一一对应：
     * deepseek_api_key / deepseek_base_url / deepseek_model /
     * openviking_url / openviking_key / openviking_user /
     * ov_peer_id / ov_peer_scope / ov_workspace_peer /
     * max_tool_rounds /
     * session_timeout_minutes / background_service_enabled /
     * ov_score_threshold / ov_search_display_count / user_tips
     * 返回成功应用的字段数量（不存在的 key 会被忽略）。
     */
    /**
     * 列出远程配置中的账号（顶层 key，其值为对象者视为账号）。
     * 扁平 JSON（无嵌套对象）返回空列表，此时直接整体导入即可。
     */
    fun listConfigAccounts(json: String): List<String> {
        val root = JSONObject(json)
        val accounts = mutableListOf<String>()
        root.keys().forEach { k ->
            if (root.get(k) is JSONObject) accounts.add(k)
        }
        return accounts
    }

    /**
     * 从远程配置 JSON 应用设置。`account` 为 null 时整体导入（扁平格式）；
     * 否则取 `account` 对应的子对象导入（多级格式，按账号隔离）。
     * 返回成功应用的字段数量（不存在的 key 忽略）。
     */
    fun applyRemoteConfig(json: String, account: String? = null): Int {
        val root = JSONObject(json)
        var obj: JSONObject = if (account != null && root.has(account) && root.get(account) is JSONObject) {
            root.getJSONObject(account)
        } else {
            root
        }
        var count = 0
        fun str(key: String): String? = if (obj.has(key)) obj.optString(key, "") else null
        fun bool(key: String): Boolean? = if (obj.has(key)) obj.optBoolean(key) else null
        fun int(key: String): Int? = if (obj.has(key)) obj.optInt(key) else null
        fun float(key: String): Float? = if (obj.has(key)) obj.optDouble(key).toFloat() else null

        str("deepseek_api_key")?.let { deepSeekApiKey = it; count++ }
        str("deepseek_base_url")?.let { deepSeekBaseUrl = it; count++ }
        str("deepseek_model")?.let { deepSeekModel = it; count++ }
        str("openviking_url")?.let { openVikingUrl = it; count++ }
        str("openviking_key")?.let { openVikingKey = it; count++ }
        str("openviking_user")?.let { openVikingUser = it; count++ }
        str("ov_peer_id")?.let { ovPeerId = it; count++ }
        str("ov_peer_scope")?.let { ovPeerScope = it; count++ }
        bool("ov_workspace_peer")?.let { ovWorkspacePeer = it; count++ }
        bool("ov_recall_question")?.let { ovRecallQuestion = it; count++ }
        bool("ov_recall_step")?.let { ovRecallStep = it; count++ }
        bool("ov_commit")?.let { ovCommit = it; count++ }
        bool("show_injected_messages")?.let { showInjectedMessages = it; count++ }
        bool("chat_ui_stream")?.let { chatUiStream = it; count++ }
        bool("chat_show_reasoning")?.let { chatShowReasoning = it; count++ }
        bool("chat_show_tool_calls")?.let { chatShowToolCalls = it; count++ }
        bool("chat_show_tool_edit")?.let { chatShowToolEdit = it; count++ }
        int("max_tool_rounds")?.let { maxToolRounds = it; count++ }
        int("session_timeout_minutes")?.let { sessionTimeoutMinutes = it; count++ }
        int("thinking_timeout_minutes")?.let { thinkingTimeoutMinutes = it; count++ }
        bool("background_service_enabled")?.let { backgroundServiceEnabled = it; count++ }
        float("ov_score_threshold")?.let { ovScoreThreshold = it; count++ }
        int("ov_search_display_count")?.let { ovSearchDisplayCount = it; count++ }
        float("ov_find_threshold")?.let { ovFindThreshold = it; count++ }
        int("ov_find_limit")?.let { ovFindLimit = it; count++ }
        str("user_tips")?.let { userTips = it; count++ }
        str("s3_endpoint_url")?.let { s3EndpointUrl = it; count++ }
        str("s3_access_key")?.let { s3AccessKey = it; count++ }
        str("s3_secret_key")?.let { s3SecretKey = it; count++ }
        bool("skip_ssl_verification")?.let { skipSslVerification = it; count++ }
        str("skill_hub_type")?.let { skillHubType = it; count++ }
        str("skill_hub_base")?.let { skillHubBase = it; count++ }
        str("skill_hub_token")?.let { skillHubToken = it; count++ }
        return count
    }
}

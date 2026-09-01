package com.androidagent.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * API Key 和配置的本地存储
 */
object AppPreferences {
    private const val PREFS_NAME = "android_agent_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    // OpenViking 召回去重（跨轮不重复注入同一记忆）
    var ovRecallDedup: Boolean
        get() = prefs.getBoolean("ov_recall_dedup", true)
        set(value) = prefs.edit().putBoolean("ov_recall_dedup", value).apply()

    // 会话开始注入记忆索引（profile）
    var ovProfileEnabled: Boolean
        get() = prefs.getBoolean("ov_profile_enabled", true)
        set(value) = prefs.edit().putBoolean("ov_profile_enabled", value).apply()

    // 自动把对话捕获到 OpenViking Session 并提取长期记忆
    var ovAutoCapture: Boolean
        get() = prefs.getBoolean("ov_auto_capture", true)
        set(value) = prefs.edit().putBoolean("ov_auto_capture", value).apply()

    // 对话设置
    var maxToolRounds: Int
        get() = prefs.getInt("max_tool_rounds", 8)
        set(value) = prefs.edit().putInt("max_tool_rounds", value).apply()

    var sessionTimeoutMinutes: Int
        get() = prefs.getInt("session_timeout_minutes", 15)
        set(value) = prefs.edit().putInt("session_timeout_minutes", value).apply()

    // 后台保活
    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean("background_service_enabled", false)
        set(value) = prefs.edit().putBoolean("background_service_enabled", value).apply()

    // OpenViking 记忆检索设置
    var ovScoreThreshold: Float
        get() = prefs.getFloat("ov_score_threshold", 0.4f)
        set(value) = prefs.edit().putFloat("ov_score_threshold", value).apply()

    var ovSearchDisplayCount: Int
        get() = prefs.getInt("ov_search_display_count", 3)
        set(value) = prefs.edit().putInt("ov_search_display_count", value).apply()

    // find 接口（纯向量语义搜索）专属阈值与条数
    var ovFindThreshold: Float
        get() = prefs.getFloat("ov_find_threshold", 0.4f)
        set(value) = prefs.edit().putFloat("ov_find_threshold", value).apply()

    var ovFindLimit: Int
        get() = prefs.getInt("ov_find_limit", 3)
        set(value) = prefs.edit().putInt("ov_find_limit", value).apply()

    // 系统提示词
    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    // 远程配置地址（一键导入设置用）
    var configUrl: String
        get() = prefs.getString("config_url", "") ?: ""
        set(value) = prefs.edit().putString("config_url", value).apply()

    /**
     * 从远程配置 JSON 一次性应用所有已知设置项，避免逐项手动输入。
     * JSON 顶层 key 与下方 SharedPreferences key 一一对应：
     * deepseek_api_key / deepseek_base_url / deepseek_model /
     * openviking_url / openviking_key / openviking_user /
     * ov_peer_id / ov_peer_scope / ov_workspace_peer / ov_recall_dedup /
     * ov_profile_enabled / ov_auto_capture / max_tool_rounds /
     * session_timeout_minutes / background_service_enabled /
     * ov_score_threshold / ov_search_display_count / system_prompt
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
        bool("ov_recall_dedup")?.let { ovRecallDedup = it; count++ }
        bool("ov_profile_enabled")?.let { ovProfileEnabled = it; count++ }
        bool("ov_auto_capture")?.let { ovAutoCapture = it; count++ }
        int("max_tool_rounds")?.let { maxToolRounds = it; count++ }
        int("session_timeout_minutes")?.let { sessionTimeoutMinutes = it; count++ }
        bool("background_service_enabled")?.let { backgroundServiceEnabled = it; count++ }
        float("ov_score_threshold")?.let { ovScoreThreshold = it; count++ }
        int("ov_search_display_count")?.let { ovSearchDisplayCount = it; count++ }
        float("ov_find_threshold")?.let { ovFindThreshold = it; count++ }
        int("ov_find_limit")?.let { ovFindLimit = it; count++ }
        str("system_prompt")?.let { systemPrompt = it; count++ }
        return count
    }
}

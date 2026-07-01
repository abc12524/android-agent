package com.androidagent.data

import android.content.Context
import android.content.SharedPreferences

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

    // 百度千帆
    var qianFanApiKey: String
        get() = prefs.getString("qianfan_api_key", "") ?: ""
        set(value) = prefs.edit().putString("qianfan_api_key", value).apply()

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
        get() = prefs.getFloat("ov_score_threshold", 0.30f)
        set(value) = prefs.edit().putFloat("ov_score_threshold", value).apply()

    var ovSearchDisplayCount: Int
        get() = prefs.getInt("ov_search_display_count", 3)
        set(value) = prefs.edit().putInt("ov_search_display_count", value).apply()
}

package com.androidagent.data.skills

import com.androidagent.data.AppPreferences
import com.androidagent.data.memory.OpenVikingClient

/**
 * 从 App 设置（[AppPreferences]）派生内置 skill 的默认 `config.json`。
 *
 * 目前覆盖：
 *  - `ov`（OpenViking）：地址 / 密钥 / 用户 / actor peer 继承「设置 → OpenViking」；
 *  - `objstore`（S3）：endpoint / AK / SK 继承「设置 → 对象存储」，跳过证书校验也一并继承。
 *
 * 技能里 `_load_config()` 只补「环境变量里没有」的键，所以这里写进 `config.json` 的值
 * 就是技能真正生效的配置；用户手工改过的键由 [SkillStore.syncConfig] 保护，不会被覆盖。
 */
object SkillConfigDefaults {

    fun forSkill(name: String): Map<String, String> = when (name) {
        "ov" -> openViking()
        "objstore" -> objStore()
        else -> emptyMap()
    }

    private fun openViking(): Map<String, String> = buildMap {
        putIfNotBlank("OPENVIKING_URL", AppPreferences.openVikingUrl)
        putIfNotBlank("OPENVIKING_KEY", AppPreferences.openVikingKey)
        putIfNotBlank("OPENVIKING_USER", AppPreferences.openVikingUser)
        // actor peer 直接继承 App 解析出的结果：技能侧若自己按 cwd 派生 ws-*，
        // 会得到与 App（按包名派生）不同的 peer，写进 OV 的记忆在 App 召回里就看不见了。
        putIfNotBlank("OV_PEER_ID", OpenVikingClient.resolvePeerId())
    }

    private fun objStore(): Map<String, String> = buildMap {
        putIfNotBlank("S3_ENDPOINT_URL", AppPreferences.s3EndpointUrl)
        putIfNotBlank("S3_ACCESS_KEY", AppPreferences.s3AccessKey)
        putIfNotBlank("S3_SECRET_KEY", AppPreferences.s3SecretKey)
        // 设置里勾了「跳过证书校验」时，技能侧也按不校验处理（自签名内网 MinIO 常见）
        put("S3_VERIFY", if (AppPreferences.skipSslVerification) "none" else "verify")
    }

    private fun MutableMap<String, String>.putIfNotBlank(key: String, value: String) {
        if (value.isNotBlank()) put(key, value.trim())
    }
}

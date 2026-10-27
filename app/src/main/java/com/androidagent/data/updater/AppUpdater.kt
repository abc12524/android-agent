package com.androidagent.data.updater

import com.androidagent.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 版本更新信息
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val error: String = ""
)

/**
 * 应用更新检查
 *
 * 只负责查 GitHub Releases 并给出 APK 下载地址；下载与安装交给浏览器，
 * 应用内不再下载/安装（旧的内置下载 + 通知进度 + FileProvider 安装已移除）。
 */
object AppUpdater {

    private const val GITHUB_API = "https://api.github.com/repos/abc12524/android-agent/releases?per_page=100"

    /**
     * 版本通道：tag 形如 v1.1.<CI run_number>。
     * 只取该通道的正式发布，避免历史 1.0.x / build-<日期>-<sha> 标签或预发布干扰。
     */
    private const val CHANNEL_PREFIX = "1.1."

    /** 关于页展示的更新内容上限，避免过长正文撑爆界面 */
    private const val MAX_NOTES_LENGTH = 2000

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 是否有新版本
     *
     * 不使用 `releases/latest`（预发布与历史标签都会干扰），改为拉取 Releases 列表，
     * 只保留 1.1.x 通道、带 .apk 产物的正式发布，按版本号取最高一条。
     */
    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext UpdateInfo(
                    hasUpdate = false,
                    error = "请求失败 (HTTP ${response.code})"
                )
            }

            val body = response.body?.string() ?: return@withContext UpdateInfo(
                hasUpdate = false, error = "响应为空"
            )

            val releases = JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray
                ?: return@withContext UpdateInfo(hasUpdate = false, error = "解析 Releases 列表失败")

            var bestVersion = ""
            var bestRelease: JsonObject? = null
            for (element in releases) {
                val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (release.get("draft")?.asBoolean == true) continue
                if (release.get("prerelease")?.asBoolean == true) continue

                val tag = release.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
                val version = tag.removePrefix("v").removePrefix("V")
                if (!version.startsWith(CHANNEL_PREFIX)) continue
                if (apkDownloadUrl(release) == null) continue

                if (bestRelease == null || compareVersions(version, bestVersion) > 0) {
                    bestVersion = version
                    bestRelease = release
                }
            }

            val release = bestRelease ?: return@withContext UpdateInfo(hasUpdate = false)
            val onlineVersion = bestVersion
            val downloadUrl = apkDownloadUrl(release).orEmpty()
            val releaseNotes = release.get("body")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()

            UpdateInfo(
                hasUpdate = compareVersions(onlineVersion, BuildConfig.VERSION_NAME) > 0,
                latestVersion = onlineVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.take(MAX_NOTES_LENGTH)
            )

        } catch (e: Exception) {
            UpdateInfo(hasUpdate = false, error = e.message ?: "检查失败")
        }
    }

    /** 取发布里第一个 .apk 资产的下载地址 */
    private fun apkDownloadUrl(release: JsonObject): String? {
        val assets = release.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        for (asset in assets) {
            val obj = asset.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = obj.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (url.isNotBlank()) return url
        }
        return null
    }

    /**
     * 版本号比较（语义化）
     * 返回 >0 表示 v1 > v2，<0 表示 v1 < v2，=0 相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}

package com.androidagent.data.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.androidagent.BuildConfig
import com.androidagent.MainActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
 * 应用更新管理器
 * 检查 GitHub Releases、下载 APK、安装
 */
object AppUpdater {

    private const val GITHUB_API = "https://api.github.com/repos/abc12524/android-agent/releases?per_page=100"

    /**
     * 版本通道：tag 形如 v1.1.<CI run_number>。
     * 只取该通道的正式发布，避免历史 1.0.x / build-<日期>-<sha> 标签或预发布干扰。
     */
    private const val CHANNEL_PREFIX = "1.1."

    private const val NOTIFICATION_CHANNEL_ID = "app_updater"
    private const val NOTIFICATION_ID = 2
    private const val APK_FILENAME = "android-agent-latest.apk"

    /** 关于页展示的更新内容上限，避免过长正文撑爆界面 */
    private const val MAX_NOTES_LENGTH = 2000

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 是否有新版本
     *
     * 不再使用 `releases/latest`（预发布与历史标签都会干扰），改为拉取 Releases 列表，
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
     * 下载 APK 并显示进度通知，完成后自动弹出安装
     */
    suspend fun downloadAndInstall(context: Context, url: String, version: String) = withContext(Dispatchers.IO) {
        createNotificationChannel(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 显示初始通知
        nm.notify(NOTIFICATION_ID, buildProgressNotification(context, "准备下载...", 0, 0))

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body ?: throw Exception("下载响应为空")

            val totalBytes = body.contentLength()
            val downloadDir = File(context.getExternalFilesDir(null), "updates")
            downloadDir.mkdirs()
            val apkFile = File(downloadDir, APK_FILENAME)

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)

            FileOutputStream(apkFile).use { output ->
                body.byteStream().use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // 每 500ms 刷新一次进度
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            nm.notify(NOTIFICATION_ID, buildProgressNotification(
                                context,
                                "正在下载 $version...",
                                progress,
                                totalBytes.toInt()
                            ))
                        }
                    }
                }
            }

            // 下载完成 -> 取消进度通知 -> 安装
            nm.cancel(NOTIFICATION_ID)
            installApk(context, apkFile)

        } catch (e: Exception) {
            // 下载失败通知
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("下载失败")
                .setContentText(e.message ?: "未知错误")
                .setAutoCancel(true)
                .build()
            )
        }
    }

    /**
     * 安装 APK
     */
    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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

    private fun createNotificationChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "应用更新",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        context: Context, text: String, progress: Int, total: Int
    ): android.app.Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("更新")
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (total > 0) {
                    setProgress(total, progress, false)
                } else {
                    setProgress(0, 0, true) // 不确定进度
                }
            }
            .build()
    }
}

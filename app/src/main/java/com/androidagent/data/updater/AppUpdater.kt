package com.androidagent.data.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.androidagent.BuildConfig
import com.androidagent.MainActivity
import com.google.gson.Gson
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

    private const val GITHUB_API = "https://api.github.com/repos/abc12524/android-agent/releases/latest"
    private const val NOTIFICATION_CHANNEL_ID = "app_updater"
    private const val NOTIFICATION_ID = 2
    private const val APK_FILENAME = "android-agent-latest.apk"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 是否有新版本
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

            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return@withContext UpdateInfo(
                hasUpdate = false, error = "解析 tag_name 失败"
            )
            val releaseNotes = json.get("body")?.asString ?: ""
            val assets = json.getAsJsonArray("assets")
            val downloadUrl = assets?.firstOrNull()?.asJsonObject?.get("browser_download_url")?.asString ?: ""

            // 比较版本号（去掉 tag 开头的 "v"）
            val remoteVersion = tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            val hasUpdate = compareVersions(remoteVersion, currentVersion) > 0

            UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = remoteVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.take(500)
            )

        } catch (e: Exception) {
            UpdateInfo(hasUpdate = false, error = e.message ?: "检查失败")
        }
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
            val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
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

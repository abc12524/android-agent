package com.androidagent

import android.app.Application
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase
import com.androidagent.data.tools.PythonManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class AndroidAgentApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppPreferences.init(this)
        database = AppDatabase.getInstance(this)

        setupCrashHandler()

        // 后台解压 Python 环境（首次启动耗时约 10-30 秒）
        PythonManager.initAsync(this)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(throwable)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashLog(throwable: Throwable) {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val report = buildCrashReport(throwable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 用 MediaStore 写入 Downloads
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "crash_$timestamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/android-agent-crashes")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(report.toByteArray(Charsets.UTF_8))
                }
            }
        } else {
            // Android 9- 用传统路径
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val crashDir = File(dir, "android-agent-crashes")
            crashDir.mkdirs()
            FileWriter(File(crashDir, "crash_$timestamp.txt")).use { it.write(report) }
        }
    }

    private fun buildCrashReport(throwable: Throwable): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("=== Crash Report ===")
        sb.appendLine("Time: ${sdf.format(Date())}")
        sb.appendLine("App: Android Agent")
        sb.appendLine("Version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
        sb.appendLine("Device: ${Build.MODEL} (${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("--- Exception ---")
        sb.appendLine("${throwable.javaClass.name}: ${throwable.message}")
        sb.appendLine()
        sb.appendLine("--- Stack Trace ---")
        throwable.stackTrace.forEach { sb.appendLine("\tat $it") }
        var cause = throwable.cause
        var level = 1
        while (cause != null && level < 10) {
            sb.appendLine()
            sb.appendLine("Caused by ($level): ${cause.javaClass.name}: ${cause.message}")
            cause.stackTrace.forEach { sb.appendLine("\tat $it") }
            cause = cause.cause
            level++
        }
        sb.appendLine()
        sb.appendLine("=== End ===")
        return sb.toString()
    }

    companion object {
        lateinit var instance: AndroidAgentApp
            private set
    }
}

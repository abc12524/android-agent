package com.androidagent

import android.app.Application
import android.os.Process
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase
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

        // 注册全局未捕获异常处理器
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(throwable)
            } catch (_: Exception) {
                // 日志保存失败也忽略
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashLog(throwable: Throwable) {
        try {
            val crashDir = File(filesDir, "crashes")
            crashDir.mkdirs()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val file = File(crashDir, "crash_${sdf.format(Date())}.txt")

            FileWriter(file).use { writer ->
                writer.write("=== Crash Report ===\n")
                writer.write("Time: ${sdf.format(Date())}\n")
                writer.write("App: Android Agent\n")
                writer.write("Version: ${packageManager.getPackageInfo(packageName, 0).versionName}\n")
                writer.write("Device: ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})\n")
                writer.write("\n--- Exception ---\n")
                writer.write("${throwable.javaClass.name}: ${throwable.message}\n\n")
                writer.write("--- Stack Trace ---\n")
                for (element in throwable.stackTrace) {
                    writer.write("\tat ${element.toString()}\n")
                }
                // 因果链
                var cause = throwable.cause
                var level = 1
                while (cause != null && level < 10) {
                    writer.write("\nCaused by ($level): ${cause.javaClass.name}: ${cause.message}\n")
                    for (element in cause.stackTrace) {
                        writer.write("\tat ${element.toString()}\n")
                    }
                    cause = cause.cause
                    level++
                }
                writer.write("\n=== End ===\n")
            }
        } catch (_: Exception) {
            // 忽略
        }
    }

    companion object {
        lateinit var instance: AndroidAgentApp
            private set
    }
}

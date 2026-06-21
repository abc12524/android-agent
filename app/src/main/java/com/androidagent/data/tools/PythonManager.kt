package com.androidagent.data.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 嵌入式 Python 管理器
 * 从 APK assets 中解压 Termux Python 二进制 + stdlib 到 filesDir，
 * 通过 ProcessBuilder 执行 Python 代码。
 *
 * assets 中的 python-arm64.tar.gz 结构：
 *   usr/bin/python3.13
 *   usr/lib/libpython3.13.so
 *   usr/lib/libpython3.so
 *   usr/lib/libandroid-support.so
 *   usr/lib/python3.13/... (stdlib + site-packages)
 *
 * 解压后目录结构：
 *   filesDir/python/usr/bin/python3.13
 *   filesDir/python/usr/lib/python3.13/...
 */
object PythonManager {

    private const val ASSET_TAR = "python-arm64.tar.gz"
    private const val PYTHON_DIR = "python"

    @Volatile
    var status: InitStatus = InitStatus.NOT_STARTED
        private set

    private var pythonHome: String = ""
    private var pythonBin: String = ""
    private var libPath: String = ""
    private var initError: String = ""

    enum class InitStatus {
        NOT_STARTED, INITIALIZING, READY, FAILED
    }

    /**
     * 异步启动初始化（在 App 启动时调用）
     */
    fun initAsync(context: Context) {
        if (status != InitStatus.NOT_STARTED) return
        status = InitStatus.INITIALIZING
        Thread {
            try {
                initialize(context)
            } catch (e: Exception) {
                status = InitStatus.FAILED
                initError = e.message ?: "未知错误"
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 阻塞等待初始化完成（工具调用时使用）
     * @return true 表示成功，false 表示失败
     */
    fun waitForInit(timeoutMs: Long = 60000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (status) {
                InitStatus.READY -> return true
                InitStatus.FAILED -> return false
                else -> Thread.sleep(100)
            }
        }
        status = InitStatus.FAILED
        initError = "初始化超时（${timeoutMs}ms）"
        return false
    }

    fun getInitError(): String = initError

    private fun initialize(context: Context) {
        val baseDir = File(context.filesDir, PYTHON_DIR)
        pythonHome = File(baseDir, "usr").absolutePath
        pythonBin = File(pythonHome, "bin/python3.13").absolutePath
        libPath = File(pythonHome, "lib").absolutePath

        // 已解压过，直接复用
        if (File(pythonBin).exists()) {
            status = InitStatus.READY
            return
        }

        try {
            baseDir.mkdirs()

            context.assets.open(ASSET_TAR).use { input ->
                extractTarGz(input, baseDir)
            }

            // 创建缺失的系统库软连接（如 libz.so.1 → /system/lib64/libz.so）
            fixMissingLibs()

            // 验证
            if (!File(pythonBin).exists()) {
                throw RuntimeException("解压后找不到 Python 二进制: $pythonBin")
            }

            status = InitStatus.READY
        } catch (e: Exception) {
            baseDir.deleteRecursively()
            status = InitStatus.FAILED
            initError = e.message ?: "未知错误"
            throw e
        }
    }

    /**
     * 纯 Kotlin 解压 tar.gz（不依赖任何第三方库）
     * 使用 Android 内置的 GZIPInputStream + 手写 tar 格式解析
     */
    private fun extractTarGz(inputStream: InputStream, destDir: File) {
        val buf = ByteArray(10240)
        GZIPInputStream(inputStream).use { gz ->
            // tar 块大小为 512 字节
            val header = ByteArray(512)
            while (true) {
                // 读取 512 字节头部
                var offset = 0
                while (offset < 512) {
                    val read = gz.read(header, offset, 512 - offset)
                    if (read == -1) return  // 文件结束
                    offset += read
                }

                // 检查是否为结束标记（全零块）
                var isZero = true
                for (i in 0 until 512) {
                    if (header[i] != 0.toByte()) {
                        isZero = false
                        break
                    }
                }
                if (isZero) {
                    // 跳过第二个全零块
                    var skip = 0
                    while (skip < 512) {
                        val s = gz.read(header, 0, 512 - skip).let { if (it == -1) 0 else it }
                        if (s == 0) return  // EOF
                        skip += s
                    }
                    return
                }

                // 解析 tar 头部字段
                // name: 100 字节 (offset 0)
                val name = readString(header, 0, 100)
                if (name.isEmpty()) continue

                // 检查是否有 prefix（处理长路径）
                val prefix = readString(header, 345, 155)
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                // size: 12 字节 (offset 124) — 八进制字符串
                val sizeStr = readString(header, 124, 12).trim()
                val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L

                // typeflag: 1 字节 (offset 156)
                val typeFlag = header[156].toInt().toChar()
                // '0' 或 '\0' = 普通文件, '5' = 目录

                val targetFile = File(destDir, fullName)

                when (typeFlag) {
                    '5' -> {
                        targetFile.mkdirs()
                    }
                    else -> {
                        targetFile.parentFile?.mkdirs()
                        if (size > 0) {
                            FileOutputStream(targetFile).use { out ->
                                var remaining = size
                                while (remaining > 0) {
                                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                    val read = gz.read(buf, 0, toRead)
                                    if (read == -1) break
                                    out.write(buf, 0, read)
                                    remaining -= read
                                }
                            }
                        } else {
                            targetFile.createNewFile()
                        }
                        // 可执行文件加权限（owner execute bit）
                        if (fullName.startsWith("usr/bin/")) {
                            targetFile.setExecutable(true, true)
                        }
                    }
                }

                // padding: 数据填充到 512 字节边界
                val padding = (512 - (size % 512)) % 512
                var skipped: Long = 0
                while (skipped < padding) {
                    val s = gz.read(buf, 0, minOf(buf.size.toLong(), padding - skipped).toInt())
                    if (s == -1) break
                    skipped += s
                }
            }
        }
    }

    private fun readString(data: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = minOf(offset + maxLen, data.size)
        while (end < limit && data[end] != 0.toByte()) end++
        return data.decodeToString(offset, end)
    }

    /**
     * 修复 Termux Python 所需但 Android 系统不提供的系统库。
     * 在 lib 目录下创建软连接，指向系统已有的同名 .so。
     */
    private fun fixMissingLibs() {
        val libDir = File(libPath)
        if (!libDir.exists()) return

        // 需要修复的库映射: 目标文件名 → 候选系统路径
        val missingLibs = mapOf(
            "libz.so.1" to listOf(
                "/system/lib64/libz.so",
                "/system/lib/libz.so",
                "/apex/com.android.runtime/lib64/bionic/libz.so",
                "/apex/com.android.runtime/lib/bionic/libz.so"
            )
        )

        for ((targetName, candidates) in missingLibs) {
            val targetFile = File(libDir, targetName)
            if (targetFile.exists()) continue  // 已存在

            for (candidate in candidates) {
                val systemLib = File(candidate)
                if (systemLib.exists()) {
                    // 创建软连接
                    java.lang.Runtime.getRuntime().exec(
                        arrayOf("ln", "-sf", systemLib.absolutePath, targetFile.absolutePath)
                    ).waitFor()
                    break
                }
            }
        }
    }

    // ========== Python 执行接口 ==========

    fun executeCode(code: String, timeoutSec: Int = 30): ProcessResult {
        return runPython(listOf("-c", code), timeoutSec)
    }

    fun executeScript(scriptPath: String, args: List<String> = emptyList(), timeoutSec: Int = 60): ProcessResult {
        return runPython(listOf(scriptPath) + args, timeoutSec)
    }

    fun pipInstall(packages: String, timeoutSec: Int = 180): ProcessResult {
        val pkgList = packages.trim().split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .map { it.trim() }
        val args = listOf("-m", "pip", "install", "-i",
            "https://pypi.tuna.tsinghua.edu.cn/simple",
            "--trusted-host", "pypi.tuna.tsinghua.edu.cn") + pkgList
        return runPython(args, timeoutSec)
    }

    fun systemInfo(): ProcessResult {
        val infoCode = """
import sys, json, subprocess
info = {"python_version": sys.version, "platform": sys.platform}
try:
    r = subprocess.run([sys.executable, "-m", "pip", "list", "--format=json"],
        capture_output=True, text=True, timeout=15)
    info["pip_packages"] = json.loads(r.stdout) if r.returncode == 0 else []
except: info["pip_packages"] = []
print(json.dumps(info, ensure_ascii=False))
""".trimIndent()
        return executeCode(infoCode, 20)
    }

    private fun runPython(args: List<String>, timeoutSec: Int): ProcessResult {
        val cmd = mutableListOf(pythonBin).also { it.addAll(args) }
        val env = mapOf(
            "LD_LIBRARY_PATH" to libPath,
            "PYTHONHOME" to pythonHome,
            "HOME" to File(pythonHome).parentFile.absolutePath,
            "TMPDIR" to File(pythonHome, "tmp").absolutePath.also {
                File(it).mkdirs()
            }
        )

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                ProcessResult(success = false, output = "执行超时（${timeoutSec}秒）", exitCode = -1)
            } else {
                val output = process.inputStream.bufferedReader().readText().trim()
                ProcessResult(
                    success = process.exitValue() == 0,
                    output = output,
                    exitCode = process.exitValue()
                )
            }
        } catch (e: Exception) {
            ProcessResult(success = false, output = "Python 执行失败: ${e.message}", exitCode = -1)
        }
    }

    data class ProcessResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int
    )

    /**
     * Python 是否已就绪
     */
    fun isReady(): Boolean = status == InitStatus.READY && File(pythonBin).exists()
}

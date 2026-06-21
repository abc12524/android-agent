package com.androidagent.data.tools

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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
    private var initialized = false
    private var pythonHome: String = ""
    private var pythonBin: String = ""
    private var libPath: String = ""

    /**
     * 初始化 Python 环境：确保 assets 中的 tar.gz 已解压到 filesDir
     */
    fun initialize(context: Context): Boolean {
        if (initialized) return true

        val baseDir = File(context.filesDir, PYTHON_DIR)
        pythonHome = File(baseDir, "usr").absolutePath
        pythonBin = File(pythonHome, "bin/python3.13").absolutePath
        libPath = File(pythonHome, "lib").absolutePath

        // 如果已解压过，直接复用
        if (File(pythonBin).exists()) {
            initialized = true
            return true
        }

        return try {
            baseDir.mkdirs()

            // 用 commons-compress 直接解压 tar.gz（不依赖系统 tar 命令）
            context.assets.open(ASSET_TAR).use { input ->
                GzipCompressorInputStream(input).use { gzInput ->
                    TarArchiveInputStream(gzInput).use { tarInput ->
                        var entry: TarArchiveEntry? = tarInput.nextEntry
                        while (entry != null) {
                            val targetFile = File(baseDir, entry.name)
                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else {
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (tarInput.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                                // 可执行文件加执行权限
                                if (entry.mode and 64 != 0) { // owner execute bit
                                    targetFile.setExecutable(true)
                                }
                            }
                            entry = tarInput.nextEntry
                        }
                    }
                }
            }

            // 验证 python3.13 存在
            if (!File(pythonBin).exists()) {
                throw RuntimeException("Python binary not found after extraction: $pythonBin")
            }

            initialized = true
            true
        } catch (e: Exception) {
            baseDir.deleteRecursively()
            throw e
        }
    }

    /**
     * 执行 Python 代码并返回 stdout
     */
    fun executeCode(code: String, timeoutSec: Int = 30): ProcessResult {
        return runPython(listOf("-c", code), timeoutSec)
    }

    /**
     * 运行 Python 脚本文件
     */
    fun executeScript(scriptPath: String, args: List<String> = emptyList(), timeoutSec: Int = 60): ProcessResult {
        return runPython(listOf(scriptPath) + args, timeoutSec)
    }

    /**
     * 运行 pip 命令
     */
    fun pipInstall(packages: String, timeoutSec: Int = 180): ProcessResult {
        val pkgList = packages.trim().split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .map { it.trim() }
        val args = listOf("-m", "pip", "install", "-i",
            "https://pypi.tuna.tsinghua.edu.cn/simple",
            "--trusted-host", "pypi.tuna.tsinghua.edu.cn") + pkgList
        return runPython(args, timeoutSec)
    }

    /**
     * 查询 Python 环境信息
     */
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
     * Python 是否已初始化
     */
    fun isReady(): Boolean = initialized && File(pythonBin).exists()
}

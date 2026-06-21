package com.androidagent.data.tools

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 嵌入式 Python 管理器
 * 从 APK assets 中解压 Termux Python 二进制 + stdlib 到 filesDir，
 * 通过 ProcessBuilder 执行 Python 代码。
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

        // 从 assets 解压 tar.gz
        return try {
            baseDir.mkdirs()
            val tarFile = File(baseDir, ASSET_TAR)

            // 将 assets 中的 tarball 复制到 filesDir
            context.assets.open(ASSET_TAR).use { input ->
                tarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 解压 tarball
            val process = ProcessBuilder(
                "tar", "xzf", tarFile.absolutePath, "-C", baseDir.absolutePath
            ).redirectErrorStream(true).start()

            val done = process.waitFor(60, TimeUnit.SECONDS)
            if (!done || process.exitValue() != 0) {
                val err = if (done) "tar exit code: ${process.exitValue()}" else "tar timed out"
                baseDir.deleteRecursively()
                throw RuntimeException(err)
            }

            // 删除 tarball，释放空间
            tarFile.delete()

            // 验证 python3.13 存在
            if (!File(pythonBin).exists()) {
                throw RuntimeException("Python binary not found after extraction: $pythonBin")
            }

            // 给 python3.13 加执行权限
            File(pythonBin).setExecutable(true)

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

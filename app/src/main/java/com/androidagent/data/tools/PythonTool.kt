package com.androidagent.data.tools

import android.content.Context
import com.androidagent.AndroidAgentApp
import com.google.gson.Gson

/**
 * Python 代码执行工具
 * 通过嵌入式 Termux Python 二进制在 Android 本地运行 Python 代码。
 *
 * 支持:
 * - code:  执行 Python 代码片段
 * - script: 运行 Python 脚本文件
 * - pip:   pip install 安装第三方包
 * - info:  查询 Python 环境信息
 */
class PythonTool : Tool {

    private val gson = Gson()

    override val name: String = "execute_python"

    override val description: String =
        "在 Android 设备本地执行 Python 代码。内置 Python 3.13 + pip（清华源），" +
        "支持标准库和 pip 安装的第三方包。可执行代码片段、运行脚本文件、pip 安装包。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作类型: 'code'（执行代码）、'script'（运行脚本文件）、'pip'（pip install 安装包）、'info'（查环境信息）",
                "enum" to listOf("code", "script", "pip", "info")
            ),
            "code" to mapOf(
                "type" to "string",
                "description" to "要执行的 Python 代码（action=code 时必填）"
            ),
            "script_path" to mapOf(
                "type" to "string",
                "description" to "Python 脚本文件路径（action=script 时必填，如 /sdcard/scripts/test.py）"
            ),
            "packages" to mapOf(
                "type" to "string",
                "description" to "pip install 的包名（action=pip 时必填，多个用空格隔开，如 'requests pandas'）"
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return """{"error": "缺少 action 参数"}"""

        // 首次使用需初始化 Python 环境（解压 tarball）
        val context = AndroidAgentApp.instance
        if (!PythonManager.isReady()) {
            try {
                PythonManager.initialize(context)
            } catch (e: Exception) {
                return """{"error": "Python 环境初始化失败: ${e.message}"}"""
            }
        }

        return try {
            when (action) {
                "code" -> {
                    val code = args["code"] as? String ?: return """{"error": "缺少 code 参数"}"""
                    val result = PythonManager.executeCode(code)
                    gson.toJson(mapOf(
                        "success" to result.success,
                        "output" to result.output,
                        "exit_code" to result.exitCode
                    ))
                }
                "script" -> {
                    val scriptPath = args["script_path"] as? String ?: return """{"error": "缺少 script_path 参数"}"""
                    val result = PythonManager.executeScript(scriptPath)
                    gson.toJson(mapOf(
                        "success" to result.success,
                        "output" to result.output,
                        "exit_code" to result.exitCode
                    ))
                }
                "pip" -> {
                    val packages = args["packages"] as? String ?: return """{"error": "缺少 packages 参数"}"""
                    val result = PythonManager.pipInstall(packages)
                    gson.toJson(mapOf(
                        "success" to result.success,
                        "output" to result.output,
                        "exit_code" to result.exitCode
                    ))
                }
                "info" -> {
                    val result = PythonManager.systemInfo()
                    gson.toJson(mapOf(
                        "success" to result.success,
                        "output" to result.output,
                        "exit_code" to result.exitCode
                    ))
                }
                else -> """{"error": "未知操作: $action"}"""
            }
        } catch (e: Exception) {
            """{"error":"Python 调用失败: ${e.message}"}"""
        }
    }
}

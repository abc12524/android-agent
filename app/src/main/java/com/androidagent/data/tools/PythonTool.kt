package com.androidagent.data.tools

import com.androidagent.AndroidAgentApp
import com.google.gson.Gson

/**
 * Python 代码执行工具
 * 通过 Chaquopy 嵌入的 CPython 在 Android 本地运行 Python 代码。
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
        "在 Android 设备本地执行 Python 代码。通过 Chaquopy 嵌入的 CPython 运行时，" +
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

        return try {
            val python = com.chaquo.python.Python.getInstance()
            val module = python.getModule("main")

            when (action) {
                "code" -> {
                    val code = args["code"] as? String ?: return """{"error": "缺少 code 参数"}"""
                    val result = module.callAttr("execute_python", code)
                    result?.toString() ?: """{"success":true,"result":"执行完成"}"""
                }
                "script" -> {
                    val scriptPath = args["script_path"] as? String ?: return """{"error": "缺少 script_path 参数"}"""
                    val result = module.callAttr("execute_script", scriptPath)
                    result?.toString() ?: """{"success":true,"result":"脚本执行完成"}"""
                }
                "pip" -> {
                    val packages = args["packages"] as? String ?: return """{"error": "缺少 packages 参数"}"""
                    val context = AndroidAgentApp.instance
                    val pipCacheDir = context.filesDir.absolutePath
                    val result = module.callAttr("pip_install", packages, pipCacheDir)
                    result?.toString() ?: """{"success":true,"result":"pip install 完成"}"""
                }
                "info" -> {
                    val result = module.callAttr("system_info")
                    result?.toString() ?: """{"success":true,"result":{}}"""
                }
                else -> """{"error": "未知操作: $action"}"""
            }
        } catch (e: com.chaquo.python.PythonException) {
            """{"error":"Python 错误: ${e.message}", "trace":"${e.cause?.message ?: ""}"}"""
        } catch (e: Exception) {
            """{"error":"Python 调用失败: ${e.message}"}"""
        }
    }
}

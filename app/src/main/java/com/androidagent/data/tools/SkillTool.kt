package com.androidagent.data.tools

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 工具族 5：skill 自建工具入口
 *
 * 复刻 shell-tool 的 skill 自动注册机制：扫描应用私有目录 skills/ 下的 *.py 脚本，
 * 按脚本头部（# skill / # description / # status / # usage）自动注册；脚本定义
 * run(arguments) 并返回统一信封。运行在本应用内置的 Python 3.13 环境中。
 *
 * 对外结构与其他工具族一致：不传 action 返回子工具清单；仅传 action 返回用法；
 * 传 action + params 执行。
 */
class SkillTool(private val context: Context) : Tool {

    private val gson = Gson()

    /** skills 脚本目录（绝对路径）；*.py 会被自动注册（`_` 开头的运行时文件除外） */
    private val skillsDir: File = File(context.filesDir, "skills")

    private val subs = listOf(
        SubTool("list", "列出全部 skill", "list: 无参数", requiresParams = false),
        SubTool("usage", "查看某个 skill 用法", "usage: 参数 skill = 要查询的 skill 名"),
        SubTool("run", "执行某个 skill", "run: 参数 skill = skill 名；arguments = 传给 run() 的参数字典"),
        SubTool("write", "新建/覆盖 skill 脚本", "write: 参数 skill（名称）、description、usage、code（Python 源码，需定义 run(arguments)）"),
        SubTool("delete", "删除 skill 脚本", "delete: 参数 skill = skill 名"),
        SubTool("guide", "返回 skill 编写指南", "guide: 无参数", requiresParams = false)
    )

    override val name: String = "skill"

    override val description: String =
        "自建 Python 工具（脚本目录 " + skillsDir.absolutePath + "）。子工具：" +
        subs.joinToString(" / ") { it.name } + "。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return err("未知子工具: $action")
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        // 等待 Python 环境初始化（最多 60 秒）
        if (!PythonManager.isReady()) {
            if (PythonManager.status == PythonManager.InitStatus.FAILED) {
                return err("Python 环境初始化失败: ${PythonManager.getInitError()}，请重启 App 重试")
            }
            if (!PythonManager.waitForInit(60000)) {
                return err("Python 环境初始化失败: ${PythonManager.getInitError()}")
            }
        }

        if (!skillsDir.exists()) skillsDir.mkdirs()
        deployAssets(skillsDir)

        val request = mutableMapOf<String, Any?>("action" to action)
        for (key in listOf("skill", "arguments", "description", "usage", "code")) {
            p[key]?.let { request[key] = it }
        }

        val requestFile = File(skillsDir, ".request.json")
        val runner = File(skillsDir, "_skill_runner.py")
        try {
            requestFile.writeText(gson.toJson(request), Charsets.UTF_8)
        } catch (e: Exception) {
            return err("写入请求失败: ${e.message}")
        }

        val result = PythonManager.executeScript(
            runner.absolutePath,
            listOf(requestFile.absolutePath),
            timeoutSec = 120
        )
        val output = result.output
        if (output.isBlank()) {
            return err("skill runner 无输出（exit=${result.exitCode}）")
        }
        return output
    }

    /**
     * 部署运行时文件：
     *  - _skill_runner.py / _envelope.py 每次覆盖，保持与应用版本一致；
     *  - README.md 仅在缺失时写入，避免覆盖使用者改动。
     */
    private fun deployAssets(dir: File) {
        writeAsset("skill_runner.py", File(dir, "_skill_runner.py"), overwrite = true)
        writeAsset("skill_envelope.py", File(dir, "_envelope.py"), overwrite = true)
        writeAsset("skill_README.md", File(dir, "README.md"), overwrite = false)
    }

    private fun writeAsset(asset: String, target: File, overwrite: Boolean) {
        if (!overwrite && target.exists()) return
        try {
            context.assets.open(asset).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (_: Exception) {
            // 资源缺失时静默跳过：runner 缺失会由执行阶段的报错暴露
        }
    }

    private fun err(msg: String): String =
        gson.toJson(mapOf("status" to "error", "error" to msg))
}

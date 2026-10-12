package com.androidagent.data.tools

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 工具族 5：skill —— 调用已注册的自建工具（不负责创建）。
 *
 * skills/ 目录下的 *.py 脚本按头部元信息（# skill / # description / # status / # usage）
 * 自动注册；新增脚本自动出现，删除脚本自动消失，`_` 开头的运行时文件不参与注册。
 * 与内置工具族一样按「两段式」逐步披露：
 *
 *   - 不传 action                   → 返回已注册 skill 清单（仅名称 + 简短说明）；
 *   - action = <skill 名>            → 返回该 skill 的用法；
 *   - action = <skill 名> + params   → 执行，params 即传给 run() 的参数字典；
 *   - action = delete + params{skill} → 删除（注销）某个 skill 脚本。
 *
 * 脚本的编写规范见 assets/skill_README.md（部署到 skills/README.md）与项目 README。
 */
class SkillTool(private val context: Context) : Tool {

    private val gson = Gson()

    /** skills 脚本目录（绝对路径）；*.py 会被自动注册（`_` 开头的运行时文件除外） */
    private val skillsDir: File = File(context.filesDir, "skills")

    /** 脚本注册缓存：用「文件名 + 修改时间 + 大小」做签名，签名未变则复用，不重新读取解析。 */
    @Volatile private var cachedSkills: List<SkillMeta> = emptyList()
    @Volatile private var cachedSignature: String = ""
    private val scanLock = Any()

    /** 运行时文件与内置默认 skill 是否已部署（每进程一次） */
    @Volatile private var assetsDeployed = false

    override val name: String = "skill"

    /** 已注册（启用）skill 名称，自动反映新增/删除。 */
    fun registeredSkillNames(): List<String> = scan().filter { it.enabled }.map { it.name }

    /** 已注册 skill 名称串，供工具定义动态披露。 */
    private fun registeredNames(): String =
        registeredSkillNames().joinToString(", ").ifEmpty { "(空)" }

    override val description: String
        get() = "调用已注册的自建 Python 工具（脚本目录 " + skillsDir.absolutePath + "）。" +
            "当前已注册: " + registeredNames() + "。" +
            "不传 action 返回清单；action=<skill 名> 查看用法；" +
            "action=<skill 名> + params 执行；action=delete 删除。"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "skill 名（当前已注册: " + registeredNames() + "），或管理动作 delete；省略则返回已注册清单"
                ),
                "params" to mapOf(
                    "type" to "object",
                    "description" to "执行时传给 skill run() 的参数字典；action=delete 时用 {skill: 名称}",
                    "additionalProperties" to true
                )
            ),
            "required" to emptyList<String>()
        )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim().orEmpty()
        val skills = scan()

        // 未指定 action：返回已注册 skill 清单（仅名称 + 简短说明，逐步披露）
        if (action.isEmpty()) return manifest(skills)

        // 管理动作：删除（注销）脚本
        if (action == "delete") {
            val skill = (familyParams(args)["skill"] as? String)?.trim().orEmpty()
            return delete(skill)
        }

        val meta = skills.firstOrNull { it.name == action }
            ?: return err(
                "未知 skill '$action'，可用: " +
                    (skills.filter { it.enabled }.joinToString(", ") { it.name }.ifEmpty { "(空)" })
            )

        // 指定了 skill 名但未给 params：返回用法
        val hasParams = args.containsKey("params") ||
            args.keys.any { it != "action" && it != "params" }
        if (!hasParams) {
            return gson.toJson(
                mapOf(
                    "status" to "ok",
                    "skill" to meta.name,
                    "description" to meta.description,
                    "enabled" to meta.enabled,
                    "usage" to meta.usage,
                    "path" to meta.path
                )
            )
        }

        // 执行 skill
        return run(action, familyParams(args))
    }

    // ---- 自动注册：扫描 skills/ 下的 *.py 并按头部解析元信息 ----

    private data class SkillMeta(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val usage: String,
        val path: String
    )

    private fun scan(): List<SkillMeta> {
        ensureDeployed()
        val files = skillsDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".py") && !it.name.startsWith("_")
        }?.sortedBy { it.name } ?: emptyList()

        // 签名 = 每个脚本的「文件名 + 修改时间 + 大小」。未变说明内容未改动，直接复用缓存，
        // 既不重新载入脚本，披露的脚本名也保持稳定；有变化才重新解析。
        val signature = files.joinToString(";") { "${it.name}:${it.lastModified()}:${it.length()}" }
        if (signature == cachedSignature) return cachedSkills

        synchronized(scanLock) {
            if (signature == cachedSignature) return cachedSkills
            val parsed = files.mapNotNull { parseHeader(it) }
            cachedSkills = parsed
            cachedSignature = signature
            return parsed
        }
    }

    private fun parseHeader(file: File): SkillMeta? {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return null
        }
        var name: String? = null
        var description = ""
        var status = "enable"
        var inUsage = false
        val usageLines = ArrayList<String>()

        for (raw in lines.take(300)) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) {
                if (name != null && inUsage) usageLines.add("")
                continue
            }
            if (!line.startsWith("#")) break

            // 重复的 # skill: 块：后者覆盖前者，重开一个干净的头部（避免头部重复时解析错乱）
            val mSkill = skillRe.find(line)
            if (mSkill != null) {
                name = mSkill.groupValues[1]
                description = ""
                status = "enable"
                inUsage = false
                usageLines.clear()
                continue
            }
            if (name == null) continue

            // status 在头部块内任意位置都生效（允许尾随内容）
            val mStatus = statusRe.find(line)
            if (mStatus != null) {
                status = mStatus.groupValues[1].lowercase()
                continue
            }
            if (!inUsage) {
                val mDesc = descRe.matchEntire(line)
                if (mDesc != null) {
                    description = mDesc.groupValues[1].trim()
                    continue
                }
            }
            val mUsage = usageRe.matchEntire(line)
            if (mUsage != null) {
                inUsage = true
                val rest = mUsage.groupValues[1].trim()
                if (rest.isNotEmpty()) usageLines.add(rest)
                continue
            }
            if (inUsage) {
                // 混入 usage 的元信息行不计入用法，避免污染
                if (skillRe.containsMatchIn(line) || statusRe.containsMatchIn(line) ||
                    descRe.containsMatchIn(line)
                ) continue
                usageLines.add(hashRe.replace(line, ""))
            }
        }
        val skillName = name ?: return null
        return SkillMeta(
            name = skillName,
            description = description,
            enabled = status.lowercase() !in DISABLED,
            usage = usageLines.joinToString("\n").trim(),
            path = file.absolutePath
        )
    }

    private fun manifest(skills: List<SkillMeta>): String {
        val visible = skills.filter { it.enabled }
        val items = linkedMapOf<String, Any>()
        for (s in visible) {
            items[s.name] = mapOf("description" to s.description, "status" to "enable")
        }
        return gson.toJson(
            mapOf(
                "status" to "ok",
                "tool" to name,
                "dir" to skillsDir.absolutePath,
                "count" to visible.size,
                "skills" to items,
                "note" to "传 action=<skill 名> 查看用法；action=<skill 名> + params 执行；action=delete + params{skill} 删除"
            )
        )
    }

    private fun delete(skill: String): String {
        if (!NAME_RE.matches(skill)) return err("非法 skill 名称")
        val target = File(skillsDir, "$skill.py")
        if (!target.isFile) return err("skill '$skill' 不存在")
        return if (target.delete()) {
            gson.toJson(mapOf("status" to "ok", "result" to mapOf("skill" to skill, "deleted" to true)))
        } else {
            err("删除失败")
        }
    }

    // ---- 执行（委托给内置 Python 3.13 的 skill runner） ----

    private suspend fun run(action: String, arguments: Map<String, Any>): String {
        // 等待 Python 环境初始化（最多 60 秒）
        if (!PythonManager.isReady()) {
            if (PythonManager.status == PythonManager.InitStatus.FAILED) {
                return err("Python 环境初始化失败: ${PythonManager.getInitError()}，请重启 App 重试")
            }
            if (!PythonManager.waitForInit(60000)) {
                return err("Python 环境初始化失败: ${PythonManager.getInitError()}")
            }
        }

        ensureDeployed()

        val request = mapOf("action" to "run", "skill" to action, "arguments" to arguments)
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

    /** 确保 skills/ 存在，并把运行时文件与内置默认 skill 部署一次（每进程一次）。 */
    private fun ensureDeployed() {
        if (assetsDeployed) return
        synchronized(scanLock) {
            if (assetsDeployed) return
            if (!skillsDir.exists()) skillsDir.mkdirs()
            deployAssets(skillsDir)
            assetsDeployed = true
        }
    }

    /**
     * 部署运行时文件：
     *  - _skill_runner.py / _envelope.py 每次覆盖，保持与应用版本一致；
     *  - README.md 仅在缺失时写入，避免覆盖使用者改动；
     *  - 再部署 assets/skills/ 下的内置默认 skill（见 deployBundledSkills）。
     */
    private fun deployAssets(dir: File) {
        writeAsset("skill_runner.py", File(dir, "_skill_runner.py"), overwrite = true)
        writeAsset("skill_envelope.py", File(dir, "_envelope.py"), overwrite = true)
        writeAsset("skill_README.md", File(dir, "README.md"), overwrite = false)
        deployBundledSkills(dir)
    }

    /**
     * 部署内置默认 skill（打包在 assets/skills/ 下）：
     *  - `_` 开头的辅助/运行时文件每次覆盖，保证与应用版本一致；
     *  - 普通 skill 脚本仅在缺失时写入，避免覆盖使用者的改动。
     *
     * 注：内置 skill 属于随应用发布的默认能力，被删除后会在下次运行时重新部署；
     * 如需临时下线，把脚本头部改为 `# status: disable` 即可。
     */
    private fun deployBundledSkills(dir: File) {
        val names = try {
            context.assets.list("skills") ?: emptyArray()
        } catch (_: Exception) {
            return
        }
        for (name in names) {
            if (!name.endsWith(".py")) continue
            writeAsset("skills/$name", File(dir, name), overwrite = name.startsWith("_"))
        }
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

    private companion object {
        val skillRe = Regex("^#\\s*skill\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        val descRe = Regex("^#\\s*description\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
        val statusRe = Regex("^#\\s*status\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        val usageRe = Regex("^#\\s*usage\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
        val hashRe = Regex("^#\\s?")
        val NAME_RE = Regex("^[A-Za-z0-9_\\-]{1,64}$")
        val DISABLED = setOf("disable", "disabled", "off", "false", "0")
    }
}

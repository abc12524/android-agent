package com.androidagent.data.tools

import android.content.Context
import com.androidagent.data.AppPreferences
import com.androidagent.data.HttpClientProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 工具族：skill_hub —— 从远程技能仓库（GitHub）查看 / 下载 / 卸载 skill。
 *
 * 远程仓库根放 `index.json`（技能名 + 说明 + 平台 + 依赖索引），每个技能一个文件夹
 * （入口 `skill.py` 与可选 `_` 助手 / `config.example.json` 同目录）。安装时按 GitHub
 * contents API 列出该文件夹文件并逐个下载到本地 `skills/<名>/`，随后由 SkillTool 按签名
 * 自动重新扫描注册（无需重启）。
 *
 * 仓库地址默认内置 `abc12524/shell-skills@main`，可在「设置 → Skill 仓库」修改。
 *
 * action 分发（两段式）：
 *   - 不传 action              → 返回子工具清单
 *   - action=list              → 列出远程技能（含平台 / 依赖 / 是否已装）
 *   - action=install + params{name} → 下载安装（覆盖安装，保留本地 config.json）
 *   - action=remove  + params{name} → 默认下线（# status: disable）；+ hard=true 才删除
 *
 * 安全：下载即等价于信任该仓库的代码（会在内置 Python 进程内 import 执行）。
 */
class SkillHubTool(private val context: Context) : Tool {

    private val gson = Gson()
    private val client = HttpClientProvider.get()

    /** skills 脚本目录（绝对路径） */
    private val skillsDir: File = File(context.filesDir, "skills")

    override val name: String = "skill_hub"

    private val subs = listOf(
        SubTool("list", "列出远程可装技能", "list: 无参数，列出远程仓库技能（含平台 / 依赖 / 是否已装）", requiresParams = false),
        SubTool("install", "下载并安装远程技能", "install: 参数 name 必填（远程技能名）；下载到 skills/<name>/ 并自动注册"),
        SubTool("remove", "下线或删除本地技能", "remove: 参数 name 必填；默认仅下线（改头部 # status: disable，保留文件）；hard=true 才真正删除")
    )

    override val description: String
        get() = "远程技能仓库（GitHub " + repo() + "@" + branch() + "）。子工具：" +
            subs.joinToString(" / ") { it.name } +
            "。list 查看可装技能；install + params{name} 下载安装；" +
            "remove + params{name} 默认下线（可恢复），remove + params{name,hard=true} 才删除。" +
            "下载即信任其代码（会在进程内执行）。仓库地址可在设置中修改。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return err("未知子工具: $action")
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "list" -> doList()
                    "install" -> doInstall((p["name"] as? String)?.trim().orEmpty())
                    "remove" -> doRemove((p["name"] as? String)?.trim().orEmpty(), asBool(p["hard"]))
                    else -> err("未知操作: $action")
                }
            } catch (e: Exception) {
                err("skill_hub $action 失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ==================== 配置 ====================

    private fun repo(): String =
        AppPreferences.skillHubRepo.trim().trim('/').ifBlank { "abc12524/shell-skills" }

    private fun branch(): String = AppPreferences.skillHubBranch.trim().ifBlank { "main" }

    private fun indexUrl(): String =
        "https://raw.githubusercontent.com/${repo()}/${branch()}/index.json"

    private fun contentsUrl(path: String): String =
        "https://api.github.com/repos/${repo()}/contents/$path?ref=${branch()}"

    // ==================== HTTP ====================

    private fun httpGetText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "android-agent")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            return resp.body?.string() ?: throw RuntimeException("响应为空")
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "android-agent")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchIndex(): com.google.gson.JsonObject {
        val body = httpGetText(indexUrl())
        val el = JsonParser.parseString(body)
        if (!el.isJsonObject) throw RuntimeException("index.json 不是 JSON 对象")
        return el.asJsonObject
    }

    // ==================== 动作实现 ====================

    private fun doList(): String {
        val index = fetchIndex()
        val arr = index.getAsJsonArray("skills") ?: return err("索引缺少 skills 列表")
        val items = arr.map { el ->
            val o = el.asJsonObject
            val n = o.get("name")?.asString ?: ""
            val entry = findEntryFile(n)
            linkedMapOf<String, Any?>(
                "name" to n,
                "description" to (o.get("description")?.asString ?: ""),
                "platform" to strList(o, "platform"),
                "requires" to strList(o, "requires"),
                "installed" to (entry != null),
                "enabled" to (entry?.let { isEnabled(it) } ?: false)
            )
        }
        return gson.toJson(
            mapOf(
                "status" to "ok",
                "repo" to repo(),
                "branch" to branch(),
                "count" to items.size,
                "skills" to items,
                "note" to "install + params{name} 安装；remove + params{name} 下线（hard=true 删除）"
            )
        )
    }

    private fun doInstall(name: String): String {
        if (!NAME_RE.matches(name)) return err("非法技能名 '$name'")

        val index = fetchIndex()
        val entry = index.getAsJsonArray("skills")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString == name }
            ?: return err("远程仓库没有技能 '$name'")

        val path = (entry.get("path")?.asString ?: name).trim().trim('/')
        if (path.isEmpty() || path.contains("..")) return err("技能 path 非法")

        val listBody = httpGetText(contentsUrl(path))
        val listEl = JsonParser.parseString(listBody)
        if (!listEl.isJsonArray) return err("技能 '$path' 不是文件夹")
        val items = listEl.asJsonArray

        if (!skillsDir.exists()) skillsDir.mkdirs()
        val tmp = File(skillsDir, "$name.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return err("创建临时目录失败")

        val written = ArrayList<String>()
        for (el in items) {
            val o = el.asJsonObject
            if ((o.get("type")?.asString ?: "") != "file") continue
            val fname = o.get("name")?.asString ?: continue
            // 目录内文件都在同一层，拒绝任何路径穿越
            if (fname.contains('/') || fname.contains('\\') || fname.contains("..")) continue
            val dl = o.get("download_url")?.asString ?: continue
            val bytes = httpGetBytes(dl)
            File(tmp, fname).writeBytes(bytes)
            written.add(fname)
        }

        if (written.isEmpty()) {
            tmp.deleteRecursively()
            return err("技能 '$path' 目录为空或未找到文件")
        }

        val dest = File(skillsDir, name)
        val overwrite = dest.isDirectory || File(skillsDir, "$name.py").isFile
        // 覆盖安装：备份本地 config.json（若有），避免被模板重置
        val savedConfig = File(dest, "config.json").takeIf { it.isFile }?.readBytes()
        dest.deleteRecursively()
        File(skillsDir, "$name.py").takeIf { it.isFile }?.delete() // 清掉同名扁平脚本，避免并存
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true)
            tmp.deleteRecursively()
        }

        // 配置：优先保留原有 config.json，否则用 config.example.json 生成待填
        var configCreated = false
        var configKept = false
        val real = File(dest, "config.json")
        if (savedConfig != null) {
            real.writeBytes(savedConfig)
            configKept = true
        } else {
            val example = File(dest, "config.example.json")
            if (example.isFile) {
                example.copyTo(real, overwrite = true)
                configCreated = true
            }
        }

        return gson.toJson(
            mapOf(
                "status" to "ok",
                "result" to mapOf(
                    "name" to name,
                    "path" to dest.absolutePath,
                    "files" to written,
                    "overwritten" to overwrite,
                    "config_created" to configCreated,
                    "config_kept" to configKept,
                    "requires" to strList(entry, "requires"),
                    "note" to "已安装并自动注册；如需配置请编辑 " + File(dest, "config.json").absolutePath
                )
            )
        )
    }

    private fun doRemove(name: String, hard: Boolean): String {
        if (!NAME_RE.matches(name)) return err("非法技能名 '$name'")
        val entry = findEntryFile(name) ?: return err("未安装技能 '$name'")

        if (hard) {
            val folder = File(skillsDir, name)
            val deleted = when {
                folder.isDirectory -> folder.deleteRecursively()
                File(skillsDir, "$name.py").isFile -> File(skillsDir, "$name.py").delete()
                else -> entry.delete()
            }
            return if (deleted) {
                gson.toJson(mapOf("status" to "ok", "result" to mapOf("skill" to name, "removed" to true, "hard" to true)))
            } else {
                err("删除失败: ${entry.absolutePath}")
            }
        }

        // 默认：软下线（改头部 # status: disable，保留文件，可重新安装恢复）
        return if (setStatus(entry, "disable")) {
            gson.toJson(
                mapOf(
                    "status" to "ok",
                    "result" to mapOf(
                        "skill" to name,
                        "disabled" to true,
                        "hard" to false,
                        "path" to entry.absolutePath,
                        "note" to "已下线（文件保留，不对 LLM 披露）；重新 install 可恢复"
                    )
                )
            )
        } else {
            err("下线失败（无法写入 ${entry.absolutePath}）")
        }
    }

    // ==================== 工具方法 ====================

    /** 定位技能的入口文件：文件夹式 <name>/skill.py → 扁平 <name>.py → 按头部名匹配。 */
    private fun findEntryFile(name: String): File? {
        File(skillsDir, "$name/skill.py").takeIf { it.isFile }?.let { return it }
        File(skillsDir, "$name.py").takeIf { it.isFile }?.let { return it }
        val children = skillsDir.listFiles() ?: return null
        val skillRe = Regex("^#\\s*skill\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        for (f in children) {
            val entry = when {
                f.isDirectory && !f.name.startsWith("_") && !f.name.startsWith(".") -> File(f, "skill.py")
                f.isFile && f.name.endsWith(".py") && !f.name.startsWith("_") -> f
                else -> null
            } ?: continue
            if (!entry.isFile) continue
            try {
                for (raw in entry.readLines().take(300)) {
                    val line = raw.trim().removePrefix("\uFEFF")
                    if (line.isEmpty()) continue
                    if (!line.startsWith("#")) break
                    val m = skillRe.find(line)
                    if (m != null) {
                        if (m.groupValues[1] == name) return entry
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 是否对 LLM 披露：头部 # status: 非 disable（缺省为启用）。 */
    private fun isEnabled(file: File): Boolean {
        val disabled = setOf("disable", "disabled", "off", "false", "0")
        val statusRe = Regex("^#\\s*status\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        return try {
            for (raw in file.readLines().take(300)) {
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty()) continue
                if (!line.startsWith("#")) break
                val m = statusRe.find(line)
                if (m != null) return m.groupValues[1].lowercase() !in disabled
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    /** 改写入口头部 `# status:`（无则插入到 description / skill 之后）。 */
    private fun setStatus(file: File, value: String): Boolean {
        val lines = try {
            file.readLines().toMutableList()
        } catch (_: Exception) {
            return false
        }
        // 头部块结束位置（首个非注释、非空行）
        var end = 0
        while (end < lines.size) {
            val s = lines[end].trim()
            if (s.isNotEmpty() && !s.startsWith("#")) break
            end++
        }
        val statusRe = Regex("^#\\s*status\\s*:", RegexOption.IGNORE_CASE)
        val descRe = Regex("^#\\s*description\\s*:", RegexOption.IGNORE_CASE)
        val skillRe = Regex("^#\\s*skill\\s*:", RegexOption.IGNORE_CASE)
        var insertAt = -1
        for (i in 0 until end) {
            val t = lines[i].trim()
            if (statusRe.containsMatchIn(t)) {
                lines[i] = "# status: $value"
                return writeLines(file, lines)
            }
            if (insertAt < 0 && descRe.containsMatchIn(t)) insertAt = i + 1
        }
        if (insertAt < 0) {
            for (i in 0 until end) {
                if (skillRe.containsMatchIn(lines[i].trim())) {
                    insertAt = i + 1
                    break
                }
            }
        }
        if (insertAt < 0) insertAt = end
        lines.add(insertAt, "# status: $value")
        return writeLines(file, lines)
    }

    private fun writeLines(file: File, lines: List<String>): Boolean = try {
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        true
    } catch (_: Exception) {
        false
    }

    private fun asBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().lowercase() in setOf("1", "true", "yes", "on")
        else -> false
    }

    private fun strList(o: com.google.gson.JsonObject, key: String): List<String> {
        val arr = o.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { it.asString }
    }
    private fun err(msg: String): String = gson.toJson(mapOf("status" to "error", "error" to msg))

    private companion object {
        val NAME_RE = Regex("^[A-Za-z0-9_.\\-]{1,64}$")
    }
}

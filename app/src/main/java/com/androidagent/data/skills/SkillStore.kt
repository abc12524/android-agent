package com.androidagent.data.skills

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 本地 skill 目录的统一读写入口（设置页与工具族共用）。
 *
 * 只支持文件夹布局：`skills/<名>/skill.py`（入口）。
 * skill 的头部元信息决定名称、说明与是否启用：
 *
 *   # skill: <name>
 *   # description: <一句话说明>
 *   # status: enable | disable
 *
 * `disable` 只是把脚本从 LLM 的工具清单中隐藏，文件仍保留，随时可再启用；
 * [delete] 才会真正删除文件。
 */
object SkillStore {

    data class InstalledSkill(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val entry: File,
    ) {
        val path: String get() = entry.absolutePath
    }

    fun dir(context: Context): File = File(context.filesDir, "skills")

    /** 扫描已安装 skill 并按名称排序（`_`/`.` 开头的运行时目录不参与）。 */
    fun list(context: Context): List<InstalledSkill> {
        val children = dir(context).listFiles()?.sortedBy { it.name } ?: return emptyList()
        return children.mapNotNull { f ->
            if (!f.isDirectory || f.name.startsWith("_") || f.name.startsWith(".")) return@mapNotNull null
            val entry = File(f, "skill.py").takeIf { it.isFile } ?: return@mapNotNull null
            parse(entry)
        }.sortedBy { it.name }
    }

    fun find(context: Context, name: String): InstalledSkill? =
        list(context).firstOrNull { it.name == name }

    /** 启用 / 禁用（改写入口头部 `# status:`），立即影响 LLM 工具清单。 */
    fun setEnabled(context: Context, name: String, enabled: Boolean): Boolean {
        if (!NAME_RE.matches(name)) return false
        val skill = find(context, name) ?: return false
        return writeStatus(skill.entry, if (enabled) "enable" else "disable")
    }

    /** 彻底删除 skill（删整个技能目录）。 */
    fun delete(context: Context, name: String): Boolean {
        if (!NAME_RE.matches(name)) return false
        val skill = find(context, name) ?: return false
        return try {
            skill.entry.parentFile?.deleteRecursively() ?: false
        } catch (_: Exception) {
            false
        }
    }

    // ---- 配置继承（AppPreferences → skills/<名>/config.json） ----

    /**
     * 把 App 设置派生的默认值合并进技能目录的 `config.json`。
     *
     * 规则（避免「改了 App 设置，技能还用旧值」与「覆盖用户手改的配置」两种坑）：
     *  - 键缺失 / 空值            → 写入当前设置值；
     *  - 键值仍等于上次继承的值    → 用新设置值覆盖（跟随设置变化）；
     *  - 键值被用户手工改过        → 保留，不再动。
     *
     * 上次继承的值记在隐藏文件 `.inherited.json`，不写进 `config.json`，
     * 免得技能把它当环境变量注入。
     *
     * @return 是否改动了 `config.json`
     */
    fun syncConfig(dir: File, defaults: Map<String, String>): Boolean {
        if (defaults.isEmpty() || !dir.isDirectory) return false
        val configFile = File(dir, "config.json")
        val markFile = inheritedMarkFile(dir)
        val config = readObject(configFile)
        val marks = readObject(markFile)

        var configChanged = false
        var markChanged = false
        for ((key, value) in defaults) {
            val current = config.stringOrNull(key)
            val inherited = marks.stringOrNull(key)
            // 用户改过（既非空、也不等于上次继承值）→ 跳过
            if (!current.isNullOrBlank() && current != inherited) continue
            if (current != value) {
                config.addProperty(key, value)
                configChanged = true
            }
            if (inherited != value) {
                marks.addProperty(key, value)
                markChanged = true
            }
        }

        if (configChanged) writeObject(configFile, config)
        if (markChanged) writeObject(markFile, marks)
        return configChanged
    }

    /** 继承记录文件：保存「上次从 App 设置写进 config.json 的值」，用于区分用户手改。 */
    fun inheritedMarkFile(dir: File): File = File(dir, INHERITED_MARK)

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun readObject(file: File): JsonObject = try {
        if (file.isFile) {
            JsonParser.parseString(file.readText(Charsets.UTF_8))
                .takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        } else {
            JsonObject()
        }
    } catch (_: Exception) {
        JsonObject()
    }

    private fun writeObject(file: File, obj: JsonObject) {
        try {
            file.writeText(GSON.toJson(obj), Charsets.UTF_8)
        } catch (_: Exception) {
            // 写失败不致命：技能仍会按原 config.json / 环境变量运行
        }
    }

    // ---- 头部解析 ----

    private fun parse(file: File): InstalledSkill? {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return null
        }
        var name: String? = null
        var description = ""
        var status = "enable"

        for (raw in lines.take(300)) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) break

            val mSkill = SKILL_RE.find(line)
            if (mSkill != null) {
                name = mSkill.groupValues[1]
                description = ""
                status = "enable"
                continue
            }
            if (name == null) continue

            val mStatus = STATUS_RE.find(line)
            if (mStatus != null) {
                status = mStatus.groupValues[1].lowercase()
                continue
            }
            val mDesc = DESC_RE.matchEntire(line)
            if (mDesc != null) {
                description = mDesc.groupValues[1].trim()
                continue
            }
        }
        val skillName = name ?: return null
        return InstalledSkill(
            name = skillName,
            description = description,
            enabled = status !in DISABLED,
            entry = file,
        )
    }

    /** 改写入口头部 `# status:`（存在则替换，否则插到 description / skill 之后）。 */
    private fun writeStatus(file: File, value: String): Boolean {
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

        var insertAt = -1
        var hasSkill = false
        for (i in 0 until end) {
            val t = lines[i].trim()
            if (STATUS_PREFIX_RE.containsMatchIn(t)) {
                lines[i] = "# status: $value"
                return writeLines(file, lines)
            }
            if (insertAt < 0 && DESC_PREFIX_RE.containsMatchIn(t)) insertAt = i + 1
            if (SKILL_PREFIX_RE.containsMatchIn(t)) hasSkill = true
        }
        if (insertAt < 0 && hasSkill) {
            for (i in 0 until end) {
                if (SKILL_PREFIX_RE.containsMatchIn(lines[i].trim())) {
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

    private const val INHERITED_MARK = ".inherited.json"
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val SKILL_RE = Regex("^#\\s*skill\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    private val SKILL_PREFIX_RE = Regex("^#\\s*skill\\s*:", RegexOption.IGNORE_CASE)
    private val DESC_RE = Regex("^#\\s*description\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val DESC_PREFIX_RE = Regex("^#\\s*description\\s*:", RegexOption.IGNORE_CASE)
    private val STATUS_RE = Regex("^#\\s*status\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    private val STATUS_PREFIX_RE = Regex("^#\\s*status\\s*:", RegexOption.IGNORE_CASE)
    private val NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val DISABLED = setOf("disable", "disabled", "off", "false", "0")
}

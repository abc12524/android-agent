package com.androidagent.data.skills

import android.content.Context
import java.io.File

/**
 * 本地 skill 目录的统一读写入口（设置页与工具族共用）。
 *
 * 支持两种布局：扁平 `skills/<名>.py`；文件夹 `skills/<名>/skill.py`。
 * skill 的头部元信息决定名称、说明与是否启用：
 *
 *   # skill: <name>
 *   # description: <一句话说明>
 *   # status: enable | disable
 *
 * `disable` 只是把脚本从 LLM 的工具清单中隐藏，文件仍保留，随时可再启用；
 * [delete] 才会真正删除文件（内置 skill 会在下次部署时恢复）。
 */
object SkillStore {

    data class InstalledSkill(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val entry: File,
        /** true = `skills/<name>/skill.py` 文件夹布局；false = 扁平 `<name>.py` */
        val folder: Boolean,
    ) {
        val path: String get() = entry.absolutePath
    }

    fun dir(context: Context): File = File(context.filesDir, "skills")

    /** 扫描已安装 skill 并按名称排序（`_`/`.` 开头的运行时文件不参与）。 */
    fun list(context: Context): List<InstalledSkill> {
        val children = dir(context).listFiles()?.sortedBy { it.name } ?: return emptyList()
        return children.mapNotNull { f ->
            val entry = when {
                f.isDirectory && !f.name.startsWith("_") && !f.name.startsWith(".") ->
                    File(f, "skill.py").takeIf { it.isFile }
                f.isFile && f.name.endsWith(".py") && !f.name.startsWith("_") -> f
                else -> null
            } ?: return@mapNotNull null
            parse(entry, f.isDirectory)
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

    /** 彻底删除 skill（文件夹布局删整个目录，扁平布局删脚本）。 */
    fun delete(context: Context, name: String): Boolean {
        if (!NAME_RE.matches(name)) return false
        val skill = find(context, name) ?: return false
        return try {
            if (skill.folder) {
                skill.entry.parentFile?.deleteRecursively() ?: false
            } else {
                skill.entry.delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 该 skill 是否随应用内置（被删后重启会重新部署）。 */
    fun isBundled(context: Context, name: String): Boolean = try {
        (context.assets.list("skills") ?: emptyArray()).any { it == "$name.py" }
    } catch (_: Exception) {
        false
    }

    // ---- 头部解析 ----

    private fun parse(file: File, folder: Boolean): InstalledSkill? {
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
            folder = folder,
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

    private val SKILL_RE = Regex("^#\\s*skill\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    private val SKILL_PREFIX_RE = Regex("^#\\s*skill\\s*:", RegexOption.IGNORE_CASE)
    private val DESC_RE = Regex("^#\\s*description\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val DESC_PREFIX_RE = Regex("^#\\s*description\\s*:", RegexOption.IGNORE_CASE)
    private val STATUS_RE = Regex("^#\\s*status\\s*:\\s*([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    private val STATUS_PREFIX_RE = Regex("^#\\s*status\\s*:", RegexOption.IGNORE_CASE)
    private val NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val DISABLED = setOf("disable", "disabled", "off", "false", "0")
}

package com.androidagent.data.skills

import android.content.Context
import com.androidagent.data.AppPreferences
import com.androidagent.data.HttpClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 远程技能仓库的配置。`type` 留空时：配了 Gitea 基址按 gitea，否则按 github。
 */
data class SkillRepoConfig(
    val repo: String = "",
    val branch: String = "",
    val base: String = "",
    val type: String = "",
    val token: String = "",
) {
    val kind: String
        get() = when (type.trim().lowercase()) {
            "github" -> "github"
            "gitea" -> "gitea"
            else -> if (baseUrl.isNotBlank()) "gitea" else "github"
        }

    val repoId: String get() = repo.trim().trim('/').ifBlank { DEFAULT_REPO }
    val branchName: String get() = branch.trim().ifBlank { "main" }
    val baseUrl: String get() = base.trim().trimEnd('/')

    fun label(): String = if (kind == "gitea") {
        "${baseUrl.ifBlank { "https://gitea.com" }}/$repoId@$branchName (gitea)"
    } else {
        "$repoId@$branchName (github)"
    }

    companion object {
        const val DEFAULT_REPO = "abc12524/shell-skills"

        fun fromPrefs(): SkillRepoConfig = SkillRepoConfig(
            repo = AppPreferences.skillHubRepo,
            branch = AppPreferences.skillHubBranch,
            base = AppPreferences.skillHubBase,
            type = AppPreferences.skillHubType,
            token = AppPreferences.skillHubToken,
        )
    }
}

data class RemoteSkill(
    val name: String,
    val description: String,
    val platform: List<String>,
    val requires: List<String>,
    val path: String,
    val installed: Boolean,
    val enabled: Boolean,
)

data class SkillInstallResult(
    val name: String,
    val path: String,
    val files: List<String>,
    val overwritten: Boolean,
    val configCreated: Boolean,
    val configKept: Boolean,
    /** 这次安装从 App 设置继承进 config.json 的键数（0 表示没有可继承的配置） */
    val inherited: Int,
    val requires: List<String>,
)

/**
 * 远程技能仓库客户端（GitHub / Gitea 的 contents API）。
 *
 * 仓库根放 `index.json`：
 *   { "skills": [ { "name": "...", "description": "...", "platform": [...],
 *                   "requires": [...], "path": "可选，默认同名" } ] }
 * 安装时列出技能文件夹并逐个下载到 `skills/<name>/`，随后由 SkillTool 按签名自动重扫。
 */
class SkillRepository(
    private val context: Context,
    private val config: SkillRepoConfig = SkillRepoConfig.fromPrefs(),
) {
    private val client = HttpClientProvider.get()

    // ==================== URL ====================

    private fun indexUrl(): String =
        if (config.kind == "gitea") "${config.baseUrl}/${config.repoId}/raw/branch/${config.branchName}/index.json"
        else "https://raw.githubusercontent.com/${config.repoId}/${config.branchName}/index.json"

    private fun contentsUrl(path: String): String =
        if (config.kind == "gitea") "${config.baseUrl}/api/v1/repos/${config.repoId}/contents/$path?ref=${config.branchName}"
        else "https://api.github.com/repos/${config.repoId}/contents/$path?ref=${config.branchName}"

    // ==================== HTTP ====================

    private fun authed(builder: Request.Builder): Request.Builder {
        val token = config.token.trim()
        if (token.isNotBlank()) {
            builder.header(
                "Authorization",
                if (config.kind == "gitea") "token $token" else "Bearer $token",
            )
        }
        return builder
    }

    private fun httpGetText(url: String): String {
        val req = authed(
            Request.Builder()
                .url(url)
                .header(
                    "Accept",
                    if (config.kind == "gitea") "application/json" else "application/vnd.github.v3+json",
                )
                .header("User-Agent", "android-agent"),
        ).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            return resp.body?.string() ?: throw RuntimeException("响应为空")
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val req = authed(
            Request.Builder()
                .url(url)
                .header("User-Agent", "android-agent"),
        ).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchIndex(): JsonObject {
        val body = httpGetText(indexUrl())
        val el = JsonParser.parseString(body)
        if (!el.isJsonObject) throw RuntimeException("index.json 不是 JSON 对象")
        return el.asJsonObject
    }

    // ==================== 动作 ====================

    /** 远程仓库可装技能列表，并标注本地是否已安装 / 启用。 */
    suspend fun listRemote(): List<RemoteSkill> = withContext(Dispatchers.IO) {
        val index = fetchIndex()
        val arr = index.getAsJsonArray("skills") ?: throw RuntimeException("索引缺少 skills 列表")
        val local = SkillStore.list(context).associateBy { it.name }
        arr.map { el ->
            val o = el.asJsonObject
            val name = o.get("name")?.asString ?: ""
            RemoteSkill(
                name = name,
                description = o.get("description")?.asString ?: "",
                platform = strList(o, "platform"),
                requires = strList(o, "requires"),
                path = (o.get("path")?.asString ?: name).trim().trim('/'),
                installed = local[name] != null,
                enabled = local[name]?.enabled ?: false,
            )
        }.filter { it.name.isNotBlank() }
    }

    /** 下载安装（覆盖安装，保留本地 config.json）。 */
    suspend fun install(name: String): SkillInstallResult = withContext(Dispatchers.IO) {
        if (!NAME_RE.matches(name)) throw RuntimeException("非法技能名 '$name'")

        val index = fetchIndex()
        val entry = index.getAsJsonArray("skills")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString == name }
            ?: throw RuntimeException("远程仓库没有技能 '$name'")

        val path = (entry.get("path")?.asString ?: name).trim().trim('/')
        if (path.isEmpty() || path.contains("..")) throw RuntimeException("技能 path 非法")

        val listBody = httpGetText(contentsUrl(path))
        val listEl = JsonParser.parseString(listBody)
        if (!listEl.isJsonArray) throw RuntimeException("技能 '$path' 不是文件夹")
        val items = listEl.asJsonArray

        val skillsDir = SkillStore.dir(context)
        if (!skillsDir.exists()) skillsDir.mkdirs()
        val tmp = File(skillsDir, "$name.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw RuntimeException("创建临时目录失败")

        val written = ArrayList<String>()
        for (el in items) {
            val o = el.asJsonObject
            if ((o.get("type")?.asString ?: "") != "file") continue
            val fname = o.get("name")?.asString ?: continue
            // 目录内文件都在同一层，拒绝任何路径穿越
            if (fname.contains('/') || fname.contains('\\') || fname.contains("..")) continue
            val dl = o.get("download_url")?.asString ?: continue
            File(tmp, fname).writeBytes(httpGetBytes(dl))
            written.add(fname)
        }

        if (written.isEmpty()) {
            tmp.deleteRecursively()
            throw RuntimeException("技能 '$path' 目录为空或未找到文件")
        }

        val dest = File(skillsDir, name)
        val overwritten = dest.isDirectory || File(skillsDir, "$name.py").isFile
        // 覆盖安装：备份本地 config.json（若有），避免被模板重置
        val savedConfig = File(dest, "config.json").takeIf { it.isFile }?.readBytes()
        val savedMark = SkillStore.inheritedMarkFile(dest).takeIf { it.isFile }?.readBytes()
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
        // 继承记录（区分「上次从 App 设置写入」与「用户手改」）随覆盖安装一起保留
        savedMark?.let { SkillStore.inheritedMarkFile(dest).writeBytes(it) }

        // 首次安装即用 App 设置填好 config.json（ov / objstore），省去手填同一份地址与密钥。
        // 模板里的占位值（your-xxx / 127.0.0.1）不算「用户手改」，会被设置值覆盖。
        val inherited = SkillStore.syncConfig(dest, SkillConfigDefaults.forSkill(name))
        if (inherited > 0 && !configKept) configCreated = true

        SkillInstallResult(
            name = name,
            path = dest.absolutePath,
            files = written,
            overwritten = overwritten,
            configCreated = configCreated,
            configKept = configKept,
            inherited = inherited,
            requires = strList(entry, "requires"),
        )
    }

    private fun strList(o: JsonObject, key: String): List<String> {
        val arr = o.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { it.asString }
    }

    private companion object {
        val NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}

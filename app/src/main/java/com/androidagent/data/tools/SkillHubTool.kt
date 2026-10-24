package com.androidagent.data.tools

import android.content.Context
import com.androidagent.data.skills.SkillRepoConfig
import com.androidagent.data.skills.SkillRepository
import com.androidagent.data.skills.SkillStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工具族：skill_hub —— 从远程技能仓库（GitHub / Gitea）查看 / 下载 / 启停 / 卸载 skill。
 *
 * 远程仓库根放 `index.json`（技能名 + 说明 + 平台 + 依赖索引），每个技能一个文件夹
 * （入口 `skill.py` 与可选 `_` 助手 / `config.example.json` 同目录）。安装时按仓库
 * contents API 列出该文件夹文件并逐个下载到本地 `skills/<名>/`，随后由 SkillTool 按签名
 * 自动重新扫描注册（无需重启）。仓库配置见「设置 → Skill 仓库」。
 *
 * action 分发（两段式）：
 *   - 不传 action                    → 返回子工具清单
 *   - action=list                    → 列出远程技能（含平台 / 依赖 / 是否已装）
 *   - action=install  + params{name} → 下载安装（覆盖安装，保留本地 config.json）
 *   - action=disable  + params{name} → 下线（# status: disable，对 LLM 隐藏）
 *   - action=enable   + params{name} → 重新启用
 *   - action=remove   + params{name} → 删除技能文件（卸载）
 *
 * 安全：下载即等价于信任该仓库的代码（会在内置 Python 进程内 import 执行）。
 */
class SkillHubTool(private val context: Context) : Tool {

    private val gson = Gson()

    override val name: String = "skill_hub"

    private val subs = listOf(
        SubTool("list", "列出远程可装技能", "list: 无参数，列出远程仓库技能（含平台 / 依赖 / 是否已装）", requiresParams = false),
        SubTool("install", "下载并安装远程技能", "install: 参数 name 必填（远程技能名）；下载到 skills/<name>/ 并自动注册"),
        SubTool("disable", "下线本地技能（对 LLM 隐藏）", "disable: 参数 name 必填；改写头部 # status: disable，文件保留"),
        SubTool("enable", "重新启用已下线技能", "enable: 参数 name 必填；改写头部 # status: enable"),
        SubTool("remove", "删除本地技能（卸载）", "remove: 参数 name 必填；直接删除技能文件"),
    )

    // 工具 schema 静态：不嵌入仓库地址等可配置内容。
    override val description: String
        get() = "远程技能仓库。子工具：" +
            subs.joinToString(" / ") { it.name } +
            "。list 查看可装技能；install + params{name} 下载安装；" +
            "disable 对 LLM 隐藏；enable 重新启用；remove 直接删除。" +
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
                    "disable" -> doSetEnabled((p["name"] as? String)?.trim().orEmpty(), false)
                    "enable" -> doSetEnabled((p["name"] as? String)?.trim().orEmpty(), true)
                    "remove" -> doRemove((p["name"] as? String)?.trim().orEmpty())
                    else -> err("未知操作: $action")
                }
            } catch (e: Exception) {
                err("skill_hub $action 失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ==================== 配置 ====================

    private fun config(): SkillRepoConfig = SkillRepoConfig.fromPrefs()

    private fun repository(): SkillRepository = SkillRepository(context, config())

    // ==================== 动作实现 ====================

    private suspend fun doList(): String {
        val items = repository().listRemote().map { s ->
            linkedMapOf<String, Any?>(
                "name" to s.name,
                "description" to s.description,
                "platform" to s.platform,
                "requires" to s.requires,
                "installed" to s.installed,
                "enabled" to s.enabled,
            )
        }
        val cfg = config()
        return gson.toJson(
            mapOf(
                "status" to "ok",
                "type" to cfg.kind,
                "base" to cfg.baseUrl,
                "repo" to cfg.repoId,
                "branch" to cfg.branchName,
                "count" to items.size,
                "skills" to items,
                "note" to "install 安装；disable 隐藏；enable 启用；remove 删除"
            )
        )
    }

    private suspend fun doInstall(name: String): String {
        val cfg = config()
        val result = try {
            repository().install(name)
        } catch (e: Exception) {
            return err(e.message ?: "安装失败")
        }
        return gson.toJson(
            mapOf(
                "status" to "ok",
                "result" to mapOf(
                    "name" to result.name,
                    "path" to result.path,
                    "files" to result.files,
                    "overwritten" to result.overwritten,
                    "config_created" to result.configCreated,
                    "config_kept" to result.configKept,
                    "requires" to result.requires,
                    "repo" to cfg.label(),
                    "note" to "已安装并自动注册；如需配置请编辑 " + result.path + "/config.json"
                )
            )
        )
    }

    private fun doSetEnabled(name: String, enabled: Boolean): String {
        if (!NAME_RE.matches(name)) return err("非法技能名 '$name'")
        val skill = SkillStore.find(context, name) ?: return err("未安装技能 '$name'")
        if (!SkillStore.setEnabled(context, name, enabled)) {
            return err("${if (enabled) "启用" else "禁用"}失败: ${skill.path}")
        }
        return gson.toJson(
            mapOf(
                "status" to "ok",
                "result" to mapOf(
                    "skill" to name,
                    "enabled" to enabled,
                    "disabled" to !enabled,
                    "path" to skill.path,
                    "note" to if (enabled) "已启用，重新对 LLM 披露" else "已下线（文件保留，不对 LLM 披露）"
                )
            )
        )
    }

    private fun doRemove(name: String): String {
        if (!NAME_RE.matches(name)) return err("非法技能名 '$name'")
        val skill = SkillStore.find(context, name) ?: return err("未安装技能 '$name'")

        return if (SkillStore.delete(context, name)) {
            gson.toJson(
                mapOf(
                    "status" to "ok",
                    "result" to mapOf(
                        "skill" to name,
                        "removed" to true,
                        "hard" to true,
                        "note" to "文件已删除"
                    )
                )
            )
        } else {
            err("删除失败: ${skill.path}")
        }
    }

    // ==================== 工具方法 ====================

    private fun err(msg: String): String = gson.toJson(mapOf("status" to "error", "error" to msg))

    private companion object {
        val NAME_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}

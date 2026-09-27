package com.androidagent.data.tools

import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient
import com.google.gson.Gson
import java.io.File

/**
 * DeepSeek Files API 工具集 — 使用 DeepSeek 提供的文件暂存空间
 * 通过 action 参数分发：upload / list / retrieve / delete / recognize
 *
 * 说明：
 *  - 文件暂存于 DeepSeek（单用户 25 GiB / 10000 个，单文件 ≤64 MiB，仅支持图片）
 *  - 默认有效期 7 天（604800 秒），可传 expires_seconds=0 表示永久有效
 *  - 密钥/地址从应用设置自动读取，无需 LLM 传入
 *  - recognize：上传设备本地图片后交由 vision 模型识别，返回识别文本
 */
class DeepSeekFileTool(private val deepSeek: DeepSeekClient = DeepSeekClient()) : Tool {

    private val gson = Gson()

    override val name: String = "deepseek_file_tool"

    override val description: String =
        "DeepSeek Files API 文件暂存空间工具（仅支持图片：JPEG/PNG/GIF/WebP，单文件≤64MiB）。" +
        "支持：upload（上传设备本地图片，返回 file_id）、list（列出已上传文件）、" +
        "retrieve（查询文件信息）、delete（删除文件）、" +
        "recognize（识别设备本地图片内容，内部自动上传并调用 vision 模型）。" +
        "所有操作从应用设置自动读取 DeepSeek API Key 与地址，无需手动传入。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作类型",
                "enum" to listOf("upload", "list", "retrieve", "delete", "recognize")
            ),
            "local_path" to mapOf("type" to "string", "description" to "设备本地文件路径（upload/recognize 时使用）"),
            "file_id" to mapOf("type" to "string", "description" to "文件 ID，形如 file-api-...（retrieve/delete 时使用）"),
            "expires_seconds" to mapOf(
                "type" to "integer",
                "description" to "有效期秒数 3600~2592000（1 小时~30 天），upload 时使用；默认 7 天；传 0 表示永久有效"
            ),
            "prompt" to mapOf("type" to "string", "description" to "识别图片时的提示词（recognize 时可选，默认“请详细描述这张图片的内容”）"),
            "after" to mapOf("type" to "string", "description" to "分页游标 file_id（list 时可选）"),
            "limit" to mapOf("type" to "integer", "description" to "返回数量 1~1000（list 时可选，默认 100）"),
            "order" to mapOf(
                "type" to "string",
                "description" to "按创建时间排序（list 时可选）",
                "enum" to listOf("asc", "desc")
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return err("缺少 action 参数")
        if (AppPreferences.deepSeekApiKey.isBlank()) return err("请先在设置中配置 DeepSeek API Key")

        return try {
            when (action) {
                "upload" -> doUpload(args)
                "list" -> doList(args)
                "retrieve" -> doRetrieve(args)
                "delete" -> doDelete(args)
                "recognize" -> doRecognize(args)
                else -> err("未知操作: $action")
            }
        } catch (e: Exception) {
            err("DeepSeek 文件操作失败: ${e.message}")
        }
    }

    private fun err(msg: String): String = gson.toJson(mapOf("error" to msg))

    /** 解析有效期：0 表示永久（null）；缺省 7 天 */
    private fun resolveExpires(args: Map<String, Any>): Int? {
        val raw = (args["expires_seconds"] as? Double)?.toInt() ?: return DEFAULT_EXPIRES
        if (raw == 0) return null
        return raw.coerceIn(3600, 2592000)
    }

    private suspend fun doUpload(args: Map<String, Any>): String {
        val path = args["local_path"] as? String
        if (path.isNullOrBlank()) return err("缺少 local_path 参数")
        val file = File(path)
        if (!file.exists() || !file.isFile) return err("本地文件不存在: $path")

        val expires = resolveExpires(args)
        return deepSeek.uploadFile(file, expires).fold(
            onSuccess = { r ->
                gson.toJson(
                    mapOf(
                        "success" to true,
                        "id" to r.id,
                        "filename" to r.filename,
                        "bytes" to r.bytes,
                        "purpose" to r.purpose,
                        "expires_at" to r.expiresAt,
                        "permanent" to (r.expiresAt == null)
                    )
                )
            },
            onFailure = { err(it.message ?: "上传失败") }
        )
    }

    private suspend fun doList(args: Map<String, Any>): String {
        val after = args["after"] as? String
        val limit = (args["limit"] as? Double)?.toInt() ?: 100
        val order = args["order"] as? String ?: "asc"
        return deepSeek.listFiles(after = after, limit = limit, order = order).fold(
            onSuccess = { r ->
                gson.toJson(
                    mapOf(
                        "success" to true,
                        "count" to r.files.size,
                        "has_more" to r.hasMore,
                        "last_id" to r.lastId,
                        "files" to r.files.map {
                            mapOf(
                                "id" to it.id,
                                "filename" to it.filename,
                                "bytes" to it.bytes,
                                "expires_at" to it.expiresAt
                            )
                        }
                    )
                )
            },
            onFailure = { err(it.message ?: "列出文件失败") }
        )
    }

    private suspend fun doRetrieve(args: Map<String, Any>): String {
        val fileId = args["file_id"] as? String
        if (fileId.isNullOrBlank()) return err("缺少 file_id 参数")
        return deepSeek.retrieveFile(fileId).fold(
            onSuccess = { r ->
                gson.toJson(
                    mapOf(
                        "success" to true,
                        "id" to r.id,
                        "filename" to r.filename,
                        "bytes" to r.bytes,
                        "purpose" to r.purpose,
                        "expires_at" to r.expiresAt
                    )
                )
            },
            onFailure = { err(it.message ?: "查询文件失败") }
        )
    }

    private suspend fun doDelete(args: Map<String, Any>): String {
        val fileId = args["file_id"] as? String
        if (fileId.isNullOrBlank()) return err("缺少 file_id 参数")
        return deepSeek.deleteFile(fileId).fold(
            onSuccess = { gson.toJson(mapOf("success" to true, "id" to fileId, "deleted" to it)) },
            onFailure = { err(it.message ?: "删除文件失败") }
        )
    }

    /**
     * 识别设备本地图片：上传到 DeepSeek 文件空间（默认 7 天），
     * 再以 file_id 引用调用 vision 模型，返回识别文本。
     */
    private suspend fun doRecognize(args: Map<String, Any>): String {
        val path = args["local_path"] as? String
        if (path.isNullOrBlank()) return err("缺少 local_path 参数")
        val file = File(path)
        if (!file.exists() || !file.isFile) return err("本地文件不存在: $path")

        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() }
            ?: "请详细描述这张图片的内容"
        val expires = resolveExpires(args)

        val uploaded = deepSeek.uploadFile(file, expires).getOrElse {
            return err(it.message ?: "上传失败")
        }

        val content = listOf(
            mapOf("type" to "text", "text" to prompt),
            mapOf("type" to "file", "file_id" to uploaded.id)
        )
        val messages = listOf(DeepSeekClient.ChatMessage(role = "user", content = content))

        return deepSeek.chat(messages, model = VISION_MODEL).fold(
            onSuccess = { resp ->
                val text = resp.choices.firstOrNull()?.message?.content?.toString() ?: ""
                gson.toJson(
                    mapOf(
                        "success" to true,
                        "file_id" to uploaded.id,
                        "expires_at" to uploaded.expiresAt,
                        "result" to text
                    )
                )
            },
            onFailure = { err(it.message ?: "图像识别失败") }
        )
    }

    companion object {
        /** 默认有效期 7 天 */
        private const val DEFAULT_EXPIRES = 604800
        /** 图像识别所用模型（当前在线模型均支持识图） */
        private const val VISION_MODEL = "deepseek-flash"
    }
}

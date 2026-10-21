package com.androidagent.data.memory

import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.HttpClientProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * OpenViking 外置记忆系统 HTTP 客户端
 * 对齐 shell-tool/core/tools/ov_tools.py 的接口与行为：
 *  - peer_id 派生（显式 > 按包名派生 ws-* > 默认 default）
 *  - 搜索阈值/条数严格遵守设置，不做兜底放宽（召回去重由 RecallEngine 负责）
 *  - 批量读取 / 记忆文件读写 / 语义搜索
 */
class OpenVikingClient {

    private val gson = Gson()
    private val client = HttpClientProvider.get()

    private val jsonMediaType = "application/json".toMediaType()

    companion object {
        private var peerCache: String? = null
    }

    private fun getBaseUrl(): String = AppPreferences.openVikingUrl.trimEnd('/')
    private fun getHeaders(): Map<String, String> {
        val key = AppPreferences.openVikingKey
        val user = AppPreferences.openVikingUser
        return mapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
            "X-OpenViking-Account" to "default",
            "X-OpenViking-User" to user,
            "X-OpenViking-Peer" to peerId()
        )
    }

    /** 解析当前 actor peer：显式 ovPeerId > 按包名派生 ws-* > 回退 default（对齐官方 DSH 插件） */
    fun peerId(): String {
        peerCache?.let { return it }
        val explicit = AppPreferences.ovPeerId.trim()
        val peer = if (explicit.isNotBlank()) {
            explicit
        } else if (AppPreferences.ovWorkspacePeer) {
            val digest = MessageDigest.getInstance("MD5")
                .digest(BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(12)
            "ws-$digest"
        } else {
            "default"
        }
        peerCache = peer
        return peer
    }

    private fun buildRequest(method: String, path: String, body: String? = null): Request {
        val baseUrl = getBaseUrl()
        if (baseUrl.isBlank()) throw IOException("请在设置中配置 OpenViking 服务器地址")
        val url = "$baseUrl$path"
        val builder = Request.Builder().url(url)
        getHeaders().forEach { (k, v) -> builder.addHeader(k, v) }

        return when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body?.toRequestBody(jsonMediaType) ?: "{}".toRequestBody(jsonMediaType)).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }
    }

    private suspend fun get(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest("GET", path)
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) Result.success(body)
            else Result.failure(Exception("HTTP ${response.code}: $body"))
        } catch (e: IOException) {
            Result.failure(Exception("网络错误: ${e.message}"))
        }
    }

    private suspend fun delete(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest("DELETE", path)
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) Result.success(body)
            else Result.failure(Exception("HTTP ${response.code}: $body"))
        } catch (e: IOException) {
            Result.failure(Exception("网络错误: ${e.message}"))
        }
    }

    private suspend fun post(path: String, body: Any): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = gson.toJson(body)
            val request = buildRequest("POST", path, jsonBody)
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            if (response.isSuccessful) Result.success(respBody)
            else Result.failure(Exception("HTTP ${response.code}: $respBody"))
        } catch (e: IOException) {
            Result.failure(Exception("网络错误: ${e.message}"))
        }
    }

    private fun buildSearchPayload(query: String, threshold: Float, limit: Int): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "query" to query,
            "score_threshold" to threshold,
            "limit" to limit
        )
        return payload
    }

    /** find 接口（纯向量语义搜索）请求体；target_uri 可限定检索范围（如 viking://user/.../memories/） */
    private fun buildFindPayload(query: String, threshold: Float, limit: Int, targetUri: String = ""): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "query" to query,
            "score_threshold" to threshold,
            "limit" to limit
        )
        if (targetUri.isNotBlank()) payload["target_uri"] = targetUri
        return payload
    }

    /**
     * 解析 /api/v1/search/search 返回，合并 memories / resources / skills 三段结果，
     * 按 uri 去重（保留首次出现），并按相关度（score）降序排序。
     * 兼容多种后端结构（result 直接列表 / result.data / 顶层 memories / hits 等）。
     */
    private fun parseSearchHits(body: String): List<JsonObject> {
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val items = mutableListOf<JsonObject>()

        val result = json.getAsJsonObject("result")
        if (result != null) {
            for ((ctype, key) in listOf("memory" to "memories", "resource" to "resources", "skill" to "skills")) {
                val seg = result.getAsJsonArray(key)
                seg?.forEach { el ->
                    val obj = el.asJsonObject
                    val ct = obj.get("context_type")
                    if (ct == null || ct.isJsonNull) {
                        obj.addProperty("context_type", ctype)
                    }
                    items.add(obj)
                }
            }
            if (items.isEmpty()) {
                for (key in listOf("hits", "items", "results", "memories")) {
                    val v = result.getAsJsonArray(key)
                    v?.forEach { if (it.isJsonObject) items.add(it.asJsonObject) }
                    if (items.isNotEmpty()) break
                }
            }
        } else {
            val res = json.get("result")
            if (res != null && res.isJsonArray) {
                json.getAsJsonArray("result").forEach { if (it.isJsonObject) items.add(it.asJsonObject) }
            } else {
                for (key in listOf("memories", "hits", "items", "results")) {
                    val v = json.getAsJsonArray(key)
                    v?.forEach { if (it.isJsonObject) items.add(it.asJsonObject) }
                    if (items.isNotEmpty()) break
                }
            }
        }

        val seen = mutableSetOf<String>()
        val hits = mutableListOf<JsonObject>()
        for (obj in items) {
            val uri = obj.get("uri")?.asString ?: ""
            if (uri.isNotBlank()) {
                if (!seen.add(uri)) continue
            }
            hits.add(obj)
        }
        hits.sortByDescending { it.get("score")?.asDouble ?: 0.0 }
        return hits
    }

    // ========== 语义搜索 ==========
    /** search 接口（带会话上下文）。threshold/limit 由调用方指定，未指定时用设置中的默认值，不做任何兜底放宽。 */
    suspend fun search(
        query: String,
        threshold: Float = AppPreferences.ovScoreThreshold,
        limit: Int = AppPreferences.ovSearchDisplayCount
    ): String {
        val result = post("/api/v1/search/search", buildSearchPayload(query, threshold, limit))
        return result.fold(
            onSuccess = { body ->
                try {
                    val top = parseSearchHits(body).take(limit.coerceAtLeast(0))
                    if (top.isEmpty()) return gson.toJson(
                        mapOf("success" to true, "results" to emptyList<Any>(), "message" to "未找到相关记忆")
                    )
                    val results = top.map { obj ->
                        mapOf(
                            "uri" to (obj.get("uri")?.asString ?: ""),
                            "score" to (obj.get("score")?.asDouble ?: 0.0),
                            "snippet" to (obj.get("abstract")?.asString ?: "").take(500),
                            "category" to (obj.get("category")?.asString ?: ""),
                            "context_type" to (obj.get("context_type")?.asString ?: "")
                        )
                    }
                    gson.toJson(mapOf("success" to true, "results" to results, "total" to top.size))
                } catch (e: Exception) {
                    "{\"error\": \"解析搜索结果失败: ${e.message}\"}"
                }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 语义搜索（find 接口：纯向量相似度，无会话上下文，低延迟） ==========
    /** find 接口。threshold/limit 由调用方指定，未指定时用设置中的默认值，不做任何兜底放宽。 */
    suspend fun find(
        query: String,
        limit: Int = AppPreferences.ovFindLimit,
        targetUri: String = "",
        threshold: Float = AppPreferences.ovFindThreshold
    ): String {
        val result = post("/api/v1/search/find", buildFindPayload(query, threshold, limit, targetUri))
        return result.fold(
            onSuccess = { body ->
                try {
                    val top = parseSearchHits(body).take(limit.coerceAtLeast(0))
                    if (top.isEmpty()) return gson.toJson(
                        mapOf("success" to true, "results" to emptyList<Any>(), "message" to "未找到相关记忆")
                    )
                    val results = top.map { obj ->
                        mapOf(
                            "uri" to (obj.get("uri")?.asString ?: ""),
                            "score" to (obj.get("score")?.asDouble ?: 0.0),
                            "snippet" to (obj.get("abstract")?.asString ?: "").take(500),
                            "category" to (obj.get("category")?.asString ?: ""),
                            "context_type" to (obj.get("context_type")?.asString ?: "")
                        )
                    }
                    gson.toJson(mapOf("success" to true, "results" to results, "total" to top.size))
                } catch (e: Exception) {
                    "{\"error\": \"解析搜索结果失败: ${e.message}\"}"
                }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 自动记忆：召回命中 / 会话建立 / 抓取 / 提交 ==========

    /** 自动召回用的 find 请求：返回解析后的命中列表（失败/未配置时为空）。 */
    suspend fun findHits(
        query: String,
        threshold: Float = AppPreferences.ovFindThreshold,
        limit: Int = AppPreferences.ovFindLimit
    ): List<JsonObject> {
        if (AppPreferences.openVikingUrl.isBlank()) return emptyList()
        return post("/api/v1/search/find", buildFindPayload(query, threshold, limit)).fold(
            onSuccess = { parseSearchHits(it) },
            onFailure = { emptyList() }
        )
    }

    /** 为本地会话建立 OV session，返回 OV session_id（失败返回空串）。 */
    suspend fun ensureSession(): String {
        if (AppPreferences.openVikingUrl.isBlank()) return ""
        return post("/api/v1/sessions", emptyMap<String, Any>()).fold(
            onSuccess = { body ->
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (json == null) "" else {
                    val res = json.objOrNull("result") ?: json
                    res.strOrEmpty("session_id")
                        .ifBlank { res.strOrEmpty("id") }
                        .ifBlank { res.objOrNull("session")?.strOrEmpty("id") ?: "" }
                }
            },
            onFailure = { "" }
        )
    }

    /** 向 OV session 批量追加消息（失败静默）。 */
    suspend fun capture(ovSessionId: String, messages: List<Map<String, Any>>): Boolean {
        if (ovSessionId.isBlank() || messages.isEmpty()) return false
        return post("/api/v1/sessions/$ovSessionId/messages/batch", mapOf("messages" to messages)).isSuccess
    }

    /** 提交/归档 OV session，触发记忆提取（失败静默）。 */
    suspend fun commitSession(ovSessionId: String, keepRecentCount: Int = 0): Boolean {
        if (ovSessionId.isBlank()) return false
        return post(
            "/api/v1/sessions/$ovSessionId/commit",
            mapOf("keep_recent_count" to keepRecentCount)
        ).isSuccess
    }

    private fun JsonObject.objOrNull(key: String): JsonObject? = try {
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) { null }

    private fun JsonObject.strOrEmpty(key: String): String = try {
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
    } catch (_: Exception) { "" }

    // ========== 保存记忆 ==========
    suspend fun remember(category: String, name: String, content: String): String {
        val user = AppPreferences.openVikingUser
        val peer = peerId()
        val uri = "viking://user/$user/peers/$peer/memories/$category/$name.md"

        // 新文件不存在时服务端返回 HTTP 404，不会进入 onSuccess
        // 所以在 onFailure 中检测 404/NOT_FOUND 后重试为 create
        val result = post("/api/v1/content/write", mapOf(
            "uri" to uri, "content" to content, "mode" to "replace", "wait" to true
        ))
        return result.fold(
            onSuccess = { body ->
                val json = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { null }
                val err = json?.get("error")?.asString ?: ""
                if (err.contains("NOT_FOUND", ignoreCase = true)) {
                    retryCreate(uri, content)
                } else if (err.isNotEmpty()) {
                    "{\"error\": \"$err\"}"
                } else {
                    "{\"success\": true, \"uri\": \"$uri\"}"
                }
            },
            onFailure = { e ->
                val msg = e.message ?: ""
                if (msg.contains("404") || msg.contains("NOT_FOUND", ignoreCase = true)) {
                    retryCreate(uri, content)
                } else {
                    "{\"error\": \"$msg\"}"
                }
            }
        )
    }

    /** 以 create 模式重试写入（新文件） */
    private suspend fun retryCreate(uri: String, content: String): String {
        val retry = post("/api/v1/content/write", mapOf(
            "uri" to uri, "content" to content, "mode" to "create", "wait" to false
        ))
        return retry.fold(
            onSuccess = { "{\"success\": true, \"uri\": \"$uri\"}" },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 读取文件（单 URI） ==========
    suspend fun readFile(uri: String): String {
        val result = get("/api/v1/content/read?uri=${URLEncoder.encode(uri, "UTF-8")}")
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.get("content")?.asString ?: body
                } catch (e: Exception) { body }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 读取文件（批量 URI 列表） ==========
    suspend fun readFiles(uris: List<String>): String {
        if (uris.isEmpty()) return "{\"error\": \"uris 列表为空\"}"
        val results = uris.map { uri ->
            get("/api/v1/content/read?uri=${URLEncoder.encode(uri, "UTF-8")}").fold(
                onSuccess = { body ->
                    val content = runCatching {
                        JsonParser.parseString(body).asJsonObject.get("content")?.asString ?: body
                    }.getOrDefault(body)
                    mapOf("uri" to uri, "success" to true, "content" to content)
                },
                onFailure = { mapOf("uri" to uri, "success" to false, "error" to (it.message ?: "未知错误")) }
            )
        }
        return gson.toJson(mapOf("success" to true, "count" to results.size, "results" to results))
    }

    // ========== 删除文件/目录（对齐 MCP forget：支持递归删除） ==========
    suspend fun deleteFile(uri: String, recursive: Boolean = false): String {
        val path = "/api/v1/fs?uri=${URLEncoder.encode(uri, "UTF-8")}" +
            if (recursive) "&recursive=true" else ""
        val result = delete(path)
        return result.fold(
            onSuccess = { """{"success":true,"uri":"$uri","recursive":$recursive}""" },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 列出目录 ==========
    suspend fun listDir(uri: String, recursive: Boolean = false): String {
        val path = "/api/v1/fs/tree?uri=${URLEncoder.encode(uri, "UTF-8")}${if (recursive) "&recursive=true" else ""}"
        val result = get(path)
        return result.fold(
            onSuccess = { it },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    // ========== 写入文件 ==========
    suspend fun writeFile(uri: String, content: String, mode: String = "replace"): String {
        val result = post("/api/v1/content/write", mapOf(
            "uri" to uri, "content" to content, "mode" to mode, "wait" to (mode != "create")
        ))
        return result.fold(
            onSuccess = { "{\"success\": true, \"uri\": \"$uri\", \"mode\": \"$mode\"}" },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

}

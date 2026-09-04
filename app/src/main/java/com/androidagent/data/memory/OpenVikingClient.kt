package com.androidagent.data.memory

import com.androidagent.BuildConfig
import com.androidagent.data.AppPreferences
import com.androidagent.data.model.Message
import com.google.gson.Gson
import com.google.gson.JsonElement
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
import java.util.concurrent.TimeUnit

/**
 * OpenViking 外置记忆系统 HTTP 客户端
 * 对齐 shell-tool/core/tools/ov_tools.py 的接口与行为：
 *  - peer_id 派生（显式 > 按包名派生 ws-* > 默认 default）
 *  - 搜索阈值兜底放宽、跨轮召回去重
 *  - 批量读取 / 批量写消息 / session 查询
 *  - 会话开始注入记忆索引（profile）、自动捕获对话到 OV Session
 */
class OpenVikingClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    // 跨实例/跨轮共享状态（对齐 shell-tool 的 _RECALL_SEEN / session 映射）
    companion object {
        private val recallSeen = mutableMapOf<String, MutableSet<String>>()
        private val ovSessions = mutableMapOf<String, String>() // androidSessionId -> ov session_id
        private var peerCache: String? = null

        private const val RECALL_MARKER = "## 📖 相关记忆"
        private const val RECALL_MARKER_ANDROID = "[自动检索的候选记忆"
        private const val PROFILE_MARKER = "<openviking-context source=\"profile\">"
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
    suspend fun search(query: String, limit: Int = AppPreferences.ovSearchDisplayCount): String {
        val threshold = AppPreferences.ovScoreThreshold
        val result = post("/api/v1/search/search", buildSearchPayload(query, threshold, limit))
        return result.fold(
            onSuccess = { body ->
                try {
                    var top = parseSearchHits(body).take(8)
                    // 兜底：阈值过高吞掉相关记忆 → 放宽到 0 再试一次，取结果更多的一次
                    if (threshold > 0 && (top.isEmpty() || (top.size <= 1 && threshold >= 0.3f))) {
                        post("/api/v1/search/search", buildSearchPayload(query, 0f, limit)).onSuccess { fbBody ->
                            val fbHits = parseSearchHits(fbBody)
                            if (fbHits.size > top.size) top = fbHits.take(8)
                        }
                    }
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
    suspend fun find(query: String, limit: Int = AppPreferences.ovFindLimit, targetUri: String = ""): String {
        val threshold = AppPreferences.ovFindThreshold
        val result = post("/api/v1/search/find", buildFindPayload(query, threshold, limit, targetUri))
        return result.fold(
            onSuccess = { body ->
                try {
                    var top = parseSearchHits(body).take(8)
                    // 兜底：阈值过高吞掉相关记忆 → 放宽到 0 再试一次，取结果更多的一次
                    if (threshold > 0 && (top.isEmpty() || (top.size <= 1 && threshold >= 0.3f))) {
                        post("/api/v1/search/find", buildFindPayload(query, 0f, limit, targetUri)).onSuccess { fbBody ->
                            val fbHits = parseSearchHits(fbBody)
                            if (fbHits.size > top.size) top = fbHits.take(8)
                        }
                    }
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

    // ========== 删除文件 ==========
    suspend fun deleteFile(uri: String): String {
        val result = delete("/api/v1/fs?uri=${URLEncoder.encode(uri, "UTF-8")}")
        return result.fold(
            onSuccess = { """{"success":true,"uri":"$uri"}""" },
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

    // ========== Session 管理 ==========
    suspend fun createSession(sessionId: String = ""): String {
        val payload = if (sessionId.isNotBlank()) mapOf("session_id" to sessionId) else emptyMap<String, String>()
        val result = post("/api/v1/sessions", payload)
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("result")?.toString() ?: body
                } catch (e: Exception) { body }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    suspend fun addMessage(sessionId: String, role: String, content: String, peerId: String = ""): String {
        if (sessionId.isBlank()) return "{\"error\": \"缺少 session_id\"}"
        val payload = mutableMapOf("role" to role, "content" to content)
        if (peerId.isNotBlank()) payload["peer_id"] = peerId
        val result = post("/api/v1/sessions/$sessionId/messages", payload)
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("result")?.toString() ?: body
                } catch (e: Exception) { body }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    /** 批量向 Session 添加消息（一次最多 100 条） */
    suspend fun addMessagesBatch(sessionId: String, messages: List<Map<String, String>>): String {
        if (sessionId.isBlank()) return "{\"error\": \"缺少 session_id\"}"
        if (messages.isEmpty()) return "{\"error\": \"messages 列表为空\"}"
        val result = post("/api/v1/sessions/$sessionId/messages/batch", mapOf("messages" to messages))
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("result")?.toString() ?: body
                } catch (e: Exception) { body }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    suspend fun commitSession(sessionId: String, keepRecentCount: Int = 0): String {
        if (sessionId.isBlank()) return "{\"error\": \"缺少 session_id\"}"
        val result = post("/api/v1/sessions/$sessionId/commit", mapOf("keep_recent_count" to keepRecentCount))
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("result")?.toString() ?: body
                } catch (e: Exception) { body }
            },
            onFailure = { "{\"error\": \"${it.message}\"}" }
        )
    }

    suspend fun getSession(sessionId: String): String {
        if (sessionId.isBlank()) return "{\"error\": \"缺少 session_id\"}"
        val result = get("/api/v1/sessions/$sessionId")
        return result.fold(onSuccess = { it }, onFailure = { "{\"error\": \"${it.message}\"}" })
    }

    suspend fun listSessions(): String {
        val result = get("/api/v1/sessions")
        return result.fold(onSuccess = { it }, onFailure = { "{\"error\": \"${it.message}\"}" })
    }

    /** 加载相关记忆作为上下文（带跨轮去重）；自动注入走 find 接口（纯向量低延迟） */
    suspend fun loadContext(query: String, sessionId: String = ""): String {
        val threshold = AppPreferences.ovFindThreshold
        val displayCount = AppPreferences.ovFindLimit
        if (displayCount <= 0) return ""
        if (query.isBlank()) return ""
        val result = post("/api/v1/search/find", buildFindPayload(query, threshold, displayCount))
        return result.fold(
            onSuccess = { body ->
                try {
                    var hits = parseSearchHits(body)
                    // 客户端跨轮去重：本 session 已注入过的 URI 不再重复注入
                    if (sessionId.isNotBlank() && AppPreferences.ovRecallDedup) {
                        val seen = recallSeen.getOrPut(sessionId) { mutableSetOf() }
                        hits = hits.filter { obj ->
                            val uri = obj.get("uri")?.asString ?: ""
                            uri.isBlank() || seen.add(uri)
                        }
                        if (seen.size > 500) recallSeen[sessionId] = seen.toList().takeLast(500).toMutableSet()
                    }
                    val top = hits.take(displayCount)
                    if (top.isEmpty()) return@fold ""
                    top.joinToString("\n") { obj ->
                        val uri = obj.get("uri")?.asString ?: ""
                        val snippet = (obj.get("abstract")?.asString ?: "").take(200)
                        "> 📖 [$uri] ${obj.get("score")?.asDouble?.let { "(${String.format("%.2f", it)})" } ?: ""}\n  $snippet"
                    }
                } catch (e: Exception) { "" }
            },
            onFailure = { "" }
        )
    }

    /** 会话开始时拉取可用记忆索引，返回 profile 块（仅新建会话调用一次） */
    suspend fun loadProfile(): String {
        if (AppPreferences.openVikingUrl.isBlank()) return ""
        val user = AppPreferences.openVikingUser
        val peer = peerId()
        val root = "viking://user/$user/peers/$peer/memories/"
        val result = get("/api/v1/fs/tree?uri=${URLEncoder.encode(root, "UTF-8")}")
        return result.fold(
            onSuccess = { body ->
                try {
                    val entries = extractTreeEntries(body)
                    if (entries.isEmpty()) return@fold ""
                    val text = entries.take(40).joinToString("\n") { "- $it" }
                    "$PROFILE_MARKER\n可用记忆索引（主题概览）：\n$text\n</openviking-context>"
                } catch (e: Exception) { "" }
            },
            onFailure = { "" }
        )
    }

    private fun extractTreeEntries(body: String): List<String> {
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun collect(raw: JsonElement?) {
            if (raw == null || !raw.isJsonObject) return
            val obj = raw.asJsonObject
            for (key in listOf("entries", "list", "children", "files", "nodes")) {
                val arr = obj.getAsJsonArray(key) ?: continue
                for (it in arr) {
                    if (it.isJsonObject) {
                        val o = it.asJsonObject
                        val n = o.get("name")?.asString ?: o.get("title")?.asString
                            ?: o.get("uri")?.asString?.trimEnd('/')?.substringAfterLast('/') ?: ""
                        if (n.isNotBlank() && seen.add(n)) names.add(n)
                    } else if (it.isJsonPrimitive) {
                        val s = it.asString
                        if (s.isNotBlank() && seen.add(s)) names.add(s)
                    }
                }
            }
        }
        val result = json.getAsJsonObject("result")
        if (result != null) collect(result)
        else if (json.getAsJsonArray("entries") != null) collect(json)
        return names
    }

    /**
     * 把一段对话捕获到 OpenViking Session 并提取长期记忆（对齐 shell-tool OV_AUTO_CAPTURE）。
     * 失败静默跳过，不影响主对话。
     */
    suspend fun captureSession(androidSessionId: String, messages: List<Message>) {
        if (AppPreferences.openVikingUrl.isBlank() || !AppPreferences.ovAutoCapture) return
        try {
            val ovSession = ovSessions[androidSessionId]
                ?: (createSessionRaw()?.also { ovSessions[androidSessionId] = it } ?: return)
            val ovMsgs = toOvMessages(messages)
            if (ovMsgs.isEmpty()) return
            post("/api/v1/sessions/$ovSession/messages/batch", mapOf("messages" to ovMsgs))
            post("/api/v1/sessions/$ovSession/commit", mapOf("keep_recent_count" to 0))
        } catch (_: Exception) { }
    }

    private suspend fun createSessionRaw(): String? {
        return post("/api/v1/sessions", emptyMap<String, String>()).fold(
            onSuccess = { body ->
                runCatching {
                    val json = JsonParser.parseString(body).asJsonObject
                    val res = json.getAsJsonObject("result")
                    res?.get("session_id")?.asString
                        ?: res?.get("id")?.asString
                        ?: res?.getAsJsonObject("session")?.get("id")?.asString
                }.getOrNull()
            },
            onFailure = { null }
        )
    }

    /** 把聊天消息转成 OV session 消息列表（对齐 opencode 插件 buildCapturePayload：
     *  纯文本发 {role, content}，含工具调用发 {role, parts}，assistant 消息带 peer_id，
     *  tool 消息转成 tool-result part）。噪音过滤对齐 capture-utils.shouldCaptureText。 */
    private fun toOvMessages(messages: List<Message>): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        for (m in messages) {
            val content = m.content
            // 跳过自动注入的召回 / profile 块，防止记忆回声
            if (content.contains(RECALL_MARKER, ignoreCase = true) ||
                content.contains(RECALL_MARKER_ANDROID, ignoreCase = true) ||
                content.contains(PROFILE_MARKER, ignoreCase = true)) continue

            if (m.role == "tool") {
                val result = content.take(2000) // OV_CAPTURE_TOOL_MAX_CHARS
                if (result.isBlank()) continue
                val part = mutableMapOf<String, Any>(
                    "type" to "tool",
                    "tool_id" to (m.toolCallId ?: ""),
                    "tool_name" to (m.toolName ?: ""),
                    "tool_status" to "completed",
                    "tool_output" to result,
                )
                val args = m.toolArgs
                if (!args.isNullOrBlank()) part["tool_input"] = parseJsonValue(args)
                out.add(mapOf("role" to "assistant", "parts" to listOf(part), "peer_id" to peerId()))
                continue
            }

            val c = content.take(4000) // OV_CAPTURE_MAX_LENGTH
            if (m.role == "assistant" && !m.toolCalls.isNullOrBlank()) {
                // 含工具调用：文本 + 工具调用 parts
                val textCaptured = shouldCapture(c, m.role)
                val parts = mutableListOf<Map<String, Any>>()
                if (textCaptured) parts.add(mapOf("type" to "text", "text" to c))
                parts.addAll(buildToolCallParts(m.toolCalls))
                if (parts.isEmpty()) continue
                out.add(mapOf("role" to "assistant", "parts" to parts, "peer_id" to peerId()))
                continue
            }

            if (m.role != "user" && m.role != "assistant") continue
            if (!shouldCapture(c, m.role)) continue
            val ovRole = if (m.role == "assistant") "assistant" else "user"
            val msg = mutableMapOf<String, Any>("role" to ovRole, "content" to c)
            if (ovRole == "assistant") msg["peer_id"] = peerId()
            out.add(msg)
        }
        return out
    }

    /** 把 assistant 消息的 tool_calls(JSON) 解析成 tool-call parts（对齐 hermes.json 的 tool 结构）。 */
    private fun buildToolCallParts(toolCallsJson: String): List<Map<String, Any>> {
        val parts = mutableListOf<Map<String, Any>>()
        runCatching {
            val arr = JsonParser.parseString(toolCallsJson).asJsonArray
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = runCatching { obj.get("id").asString }.getOrNull() ?: ""
                val name = runCatching { obj.getAsJsonObject("function").get("name").asString }.getOrNull() ?: ""
                if (name.isBlank()) continue
                val part = mutableMapOf<String, Any>(
                    "type" to "tool",
                    "tool_id" to id,
                    "tool_name" to name,
                    "tool_status" to "completed",
                )
                val args = runCatching { obj.getAsJsonObject("function").get("arguments").asString }.getOrNull()
                if (!args.isNullOrBlank()) part["tool_input"] = parseJsonValue(args)
                parts.add(part)
            }
        }
        return parts
    }

    /** 尽量把 JSON 字符串还原为对象/数组；失败则保留原始字符串。 */
    private fun parseJsonValue(text: String): Any {
        val el = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return text
        return if (el.isJsonObject || el.isJsonArray) el else el.asString
    }

    /** 对齐官方 capture-utils.shouldCaptureText 的轻量噪音过滤 */
    private fun shouldCapture(text: String, role: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (role == "user" && Regex("^/[a-z0-9_-]{1,64}\\b", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex(
                "^(?:ok|okay|k|yes|yep|no|nope|thanks|thank you|thx|done|收到|好的|好|嗯|可以|继续|不用|不需要|没了|好了)[.!?。！？\\s]*$",
                RegexOption.IGNORE_CASE
            ).matches(t)
        ) return false
        if (!Regex("[a-z0-9\u3400-\u9fff]", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        val cjk = Regex("[\\u3400-\\u9fff]").findAll(t).count()
        val alnum = Regex("[a-z0-9]", RegexOption.IGNORE_CASE).findAll(t).count()
        return cjk >= 4 || alnum >= 6 || t.length >= 12
    }
}

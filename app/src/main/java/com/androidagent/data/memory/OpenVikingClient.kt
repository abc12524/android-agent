package com.androidagent.data.memory

import com.androidagent.data.AppPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenViking 外置记忆系统 HTTP 客户端
 * 对应 Python 版的 _ov_get / _ov_post 函数
 */
class OpenVikingClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private fun getBaseUrl(): String = AppPreferences.openVikingUrl.trimEnd('/')
    private fun getHeaders(): Map<String, String> {
        val key = AppPreferences.openVikingKey
        val user = AppPreferences.openVikingUser
        return mapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
            "X-OpenViking-Account" to "default",
            "X-OpenViking-User" to user,
            "X-OpenViking-Peer" to "default"
        )
    }

    private fun buildRequest(method: String, path: String, body: String? = null): Request {
        val url = "${getBaseUrl()}$path"
        val builder = Request.Builder().url(url)
        getHeaders().forEach { (k, v) -> builder.addHeader(k, v) }

        return when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body?.toRequestBody(jsonMediaType) ?: "{}".toRequestBody(jsonMediaType)).build()
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

    // ========== 语义搜索 ==========
    suspend fun search(query: String, limit: Int = 5): String {
        val result = post("/api/v1/search/search", mapOf(
            "query" to query, "score_threshold" to 0.30, "limit" to limit
        ))
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    val mems = json.getAsJsonObject("result")?.getAsJsonArray("memories") ?: return body
                    val hits = mems.take(8)
                    if (hits.isEmpty()) return gson.toJson(mapOf("success" to true, "results" to emptyList<Any>(), "message" to "未找到相关记忆"))

                    val results = hits.map { h ->
                        val obj = h.asJsonObject
                        mapOf(
                            "uri" to (obj.get("uri")?.asString ?: ""),
                            "score" to (obj.get("score")?.asDouble ?: 0.0),
                            "snippet" to (obj.get("abstract")?.asString ?: "").take(500),
                            "category" to (obj.get("category")?.asString ?: "")
                        )
                    }
                    gson.toJson(mapOf("success" to true, "results" to results))
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
        val uri = "viking://user/$user/peers/default/memories/$category/$name.md"

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
                    // 文件不存在，用 create 模式
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
                    // HTTP 404 = 文件不存在，用 create 模式
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

    // ========== 读取文件 ==========
    suspend fun readFile(uri: String): String {
        val result = get("/api/v1/content/read?uri=${java.net.URLEncoder.encode(uri, "UTF-8")}")
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

    // ========== 列出目录 ==========
    suspend fun listDir(uri: String, recursive: Boolean = false): String {
        val path = "/api/v1/fs/tree?uri=${java.net.URLEncoder.encode(uri, "UTF-8")}${if (recursive) "&recursive=true" else ""}"
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

    /** 加载相关记忆作为上下文 */
    suspend fun loadContext(query: String): String {
        val result = post("/api/v1/search/search", mapOf(
            "query" to query, "score_threshold" to 0.30, "limit" to 3
        ))
        return result.fold(
            onSuccess = { body ->
                try {
                    val json = JsonParser.parseString(body).asJsonObject
                    val mems = json.getAsJsonObject("result")?.getAsJsonArray("memories") ?: return@fold ""
                    val hits = mems.take(3)
                    if (hits.isEmpty()) return@fold ""
                    hits.joinToString("\n") { h ->
                        val obj = h.asJsonObject
                        val uri = obj.get("uri")?.asString ?: ""
                        val snippet = (obj.get("abstract")?.asString ?: "").take(200)
                        "> 📖 [$uri] ${obj.get("score")?.asDouble?.let { "(${String.format("%.2f", it)})" } ?: ""}\n  $snippet"
                    }
                } catch (e: Exception) { "" }
            },
            onFailure = { "" }
        )
    }
}

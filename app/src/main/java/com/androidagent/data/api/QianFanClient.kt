package com.androidagent.data.api

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
 * 百度千帆 API 客户端
 * 提供百度搜索、百科查询、网页摘要等功能
 */
class QianFanClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 百度搜索（原始搜索结果）
     */
    suspend fun webSearch(query: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.qianFanApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先配置百度千帆 API Key"))
        }

        try {
            val jsonBody = gson.toJson(mapOf(
                "messages" to listOf(mapOf("role" to "user", "content" to query)),
                "search_source" to "baidu_search_v2",
                "resource_type_filter" to listOf(mapOf("type" to "web", "top_k" to 10))
            ))

            val request = Request.Builder()
                .url("https://qianfan.baidubce.com/v2/ai_search/web_search")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Appbuilder-Authorization", "Bearer $apiKey")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("搜索失败 (${response.code}): $body"))
            }

            val json = JsonParser.parseString(body).asJsonObject
            val refs = json.getAsJsonArray("references") ?: return@withContext Result.success("[]")

            val results = refs.map { r ->
                val obj = r.asJsonObject
                mapOf(
                    "title" to (obj.get("title")?.asString ?: ""),
                    "url" to (obj.get("url")?.asString ?: ""),
                    "site" to (obj.get("website")?.asString ?: ""),
                    "desc" to (obj.get("snippet")?.asString ?: "")
                )
            }

            Result.success(gson.toJson(mapOf(
                "success" to true,
                "type" to "raw_search",
                "results" to results
            )))

        } catch (e: IOException) {
            Result.failure(Exception("网络错误: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(Exception("搜索失败: ${e.message}"))
        }
    }

    /**
     * 百度百科词条查询
     */
    suspend fun baike(query: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = AppPreferences.qianFanApiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("请先配置百度千帆 API Key"))
        }

        try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("appbuilder.baidu.com")
                .encodedPath("/v2/baike/lemma/get_content")
                .addQueryParameter("search_type", "lemmaTitle")
                .addQueryParameter("search_key", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("百科查询失败 (${response.code})"))
            }

            val json = JsonParser.parseString(body).asJsonObject
            val result = json.getAsJsonObject("result") ?: return@withContext Result.success(
                gson.toJson(mapOf("error" to "未找到词条「$query」"))
            )

            val cards = mutableMapOf<String, String>()
            result.getAsJsonArray("card")?.forEach { c ->
                val card = c.asJsonObject
                if (card.has("name")) {
                    cards[card.get("name").asString] = card.get("value")?.asString ?: ""
                }
            }

            Result.success(gson.toJson(mapOf(
                "success" to true,
                "title" to (result.get("lemma_title")?.asString ?: ""),
                "desc" to (result.get("lemma_desc")?.asString ?: ""),
                "summary" to ((result.get("summary")?.asString ?: "").take(2000)),
                "info" to cards,
                "url" to (result.get("url")?.asString ?: "")
            )))

        } catch (e: Exception) {
            Result.failure(Exception("百科查询失败: ${e.message}"))
        }
    }
}

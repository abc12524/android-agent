package com.androidagent.data.tools

import com.androidagent.data.AppPreferences
import com.androidagent.data.HttpClientProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 对象存储工具（S3 兼容）— 基于 OkHttp + AWS Signature V4
 * 通过 action 参数分发：upload / download / delete / delete_batch / list / copy /
 * head / list_buckets / create_bucket / delete_bucket / presign / create_folder / delete_folder
 */
class ObjectStorageTool : Tool {

    private val gson = Gson()
    private val client = HttpClientProvider.get()

    override val name: String = "file_tool"

    private val subs = listOf(
        SubTool("upload", "上传本地文件", "upload: 参数 bucket, key, local_path 必填"),
        SubTool("download", "下载对象到本地", "download: 参数 bucket, key, local_path 必填"),
        SubTool("delete", "删除对象", "delete: 参数 bucket, key 必填"),
        SubTool("delete_batch", "批量删除对象", "delete_batch: 参数 bucket 必填, keys（字符串数组）必填"),
        SubTool("list", "列举对象", "list: 参数 bucket 必填；可选 prefix、max_keys（默认 100）"),
        SubTool("copy", "复制对象", "copy: 参数 bucket, key, source_key 必填；可选 source_bucket（默认同目标）"),
        SubTool("head", "对象元数据", "head: 参数 bucket, key 必填"),
        SubTool("list_buckets", "列出所有 bucket", "list_buckets: 无参数", requiresParams = false),
        SubTool("create_bucket", "创建 bucket", "create_bucket: 参数 bucket 必填"),
        SubTool("delete_bucket", "删除 bucket", "delete_bucket: 参数 bucket 必填"),
        SubTool("presign", "生成预签名 URL", "presign: 参数 bucket, key 必填；可选 method（GET|PUT，默认 GET）、expires（默认 3600）"),
        SubTool("create_folder", "创建文件夹", "create_folder: 参数 bucket, folder 必填"),
        SubTool("delete_folder", "删除文件夹及其对象", "delete_folder: 参数 bucket, folder 必填")
    )

    override val description: String =
        "对象存储（S3 兼容）。子工具：" + subs.joinToString(" / ") { it.name } +
        "。连接参数从应用设置自动读取，无需传入。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return err("未知子工具: $action")
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        val endpointUrl = AppPreferences.s3EndpointUrl
        val accessKey = AppPreferences.s3AccessKey
        val secretKey = AppPreferences.s3SecretKey

        if (endpointUrl.isBlank()) return err("未配置对象存储服务地址 (Endpoint URL)")
        if (accessKey.isBlank()) return err("未配置对象存储 Access Key")
        if (secretKey.isBlank()) return err("未配置对象存储 Secret Key")

        val endpoint = endpointUrl.trimEnd('/')
        val region = extractRegion(endpoint)

        return try {
            val result = when (action) {
                "upload" -> doUpload(endpoint, region, accessKey, secretKey, p)
                "download" -> doDownload(endpoint, region, accessKey, secretKey, p)
                "delete" -> doDelete(endpoint, region, accessKey, secretKey, p)
                "delete_batch" -> doDeleteBatch(endpoint, region, accessKey, secretKey, p)
                "list" -> doList(endpoint, region, accessKey, secretKey, p)
                "copy" -> doCopy(endpoint, region, accessKey, secretKey, p)
                "head" -> doHead(endpoint, region, accessKey, secretKey, p)
                "list_buckets" -> doListBuckets(endpoint, region, accessKey, secretKey)
                "create_bucket" -> doCreateBucket(endpoint, region, accessKey, secretKey, p)
                "delete_bucket" -> doDeleteBucket(endpoint, region, accessKey, secretKey, p)
                "presign" -> doPresign(endpoint, region, accessKey, secretKey, p)
                "create_folder" -> doCreateFolder(endpoint, region, accessKey, secretKey, p)
                "delete_folder" -> doDeleteFolder(endpoint, region, accessKey, secretKey, p)
                else -> err("未知操作: $action")
            }
            android.util.Log.d("ObjectStorage", "action=$action result=${result.take(500)}")
            result
        } catch (e: Exception) {
            err("对象存储操作失败: ${e.message}")
        }
    }

    private fun err(msg: String): String = gson.toJson(mapOf("error" to msg))

    private fun ok(vararg pairs: Pair<String, Any?>): String =
        gson.toJson(mapOf("success" to true) + pairs.toMap())

    private fun extractRegion(endpoint: String): String {
        val host = try { java.net.URI(endpoint).host } catch (_: Exception) { "" }
        if (host.contains("amazonaws.com")) {
            val parts = host.split(".")
            if (parts.size >= 3) return parts[1]
        }
        return "us-east-1"
    }

    // ==================== S3 请求签名 ====================

    private fun signRequest(
        method: String,
        endpoint: String,
        path: String,
        region: String,
        accessKey: String,
        secretKey: String,
        headers: MutableMap<String, String>,
        body: ByteArray = ByteArray(0),
        contentType: String = "",
        queryParams: Map<String, String> = emptyMap()
    ): Request {
        val url = java.net.URI(endpoint + path)
        val host = url.host + if (url.port > 0 && url.port != 80 && url.port != 443) ":${url.port}" else ""
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val amzDate = dateFormat.format(now)

        // Always include x-amz-content-sha256 (required by MinIO/rustfs)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(body)
        headers["x-amz-content-sha256"] = sha256.joinToString("") { "%02x".format(it) }

        headers["host"] = host
        headers["x-amz-date"] = amzDate

        if (contentType.isNotBlank()) {
            headers["content-type"] = contentType
        }

        // Build canonical query string (sorted + RFC3986 encoded, like Python urllib.parse.quote)
        val finalQuery = canonicalQuery(queryParams)

        // Build signed headers map (lowercase keys for signing, but keep original case for header names)
        val signedHeaderLowerKeys = headers.keys.map { it.lowercase() }.sorted()
        val signedHeaders = signedHeaderLowerKeys.joinToString(";") { it }

        // Build canonical headers: original-key: value\n (sorted by lowercase key)
        val canonicalHeaders = signedHeaderLowerKeys.joinToString("\n") { lowercaseKey ->
            // Find original key (case-insensitive match)
            val originalKey = headers.keys.find { it.lowercase() == lowercaseKey }
                ?: lowercaseKey
            "$originalKey:${headers[originalKey]}"
        } + "\n"

        val payloadHash = headers["x-amz-content-sha256"] ?: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val canonPath = if (path.startsWith("/")) path else "/" + path
        val canonicalRequest = buildString {
            append(method).append("\n")
            append(canonPath).append("\n")
            append(finalQuery).append("\n")
            // canonicalHeaders 已以 \n 结尾，sigv4 还需一个分隔空行（\n\n↔SignedHeaders）
            append(canonicalHeaders).append("\n")
            append(signedHeaders).append("\n")
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256").append("\n")
            append(amzDate).append("\n")
            append(credentialScope).append("\n")
            append(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.toByteArray()).joinToString("") { "%02x".format(it) })
        }

        val signingKey = hmacSha256(
            hmacSha256(
                hmacSha256(
                    hmacSha256("AWS4$secretKey".toByteArray(), dateStamp.toByteArray()),
                    region.toByteArray()
                ),
                "s3".toByteArray()
            ),
            "aws4_request".toByteArray()
        )
        val signature = hmacSha256(signingKey, stringToSign.toByteArray()).joinToString("") { "%02x".format(it) }

        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
        headers["authorization"] = authorization

        val requestUrl = "$endpoint$canonPath" + if (finalQuery.isNotEmpty()) "?$finalQuery" else ""
        // PUT/POST/PATCH 必须有 body：空 body 时用空字节数组 + 兜底 content-type（避免 OkHttp 抛 "must have a request body"）
        val requestBody: okhttp3.RequestBody? = when {
            body.isNotEmpty() -> body.toRequestBody(contentType.toMediaType())
            method == "PUT" || method == "POST" || method == "PATCH" ->
                ByteArray(0).toRequestBody(contentType.ifBlank { "application/octet-stream" }.toMediaType())
            else -> null
        }
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .method(method, requestBody)

        // 用 header() 覆盖（而非 addHeader() 追加），避免 Host/Content-Type 重复导致签名校验失败
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        return requestBuilder.build()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // RFC3986 percent-encoding (like urllib.parse.quote(s, safe="")) — 用于 S3 签名规范化
    private fun canonicalEncode(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(c))
            }
        }
        return out.toString()
    }

    // 构建规范化 query：按 key 排序。子资源/空值参数必须输出 "key="（AWS 规范，如 ?delete => delete=）
    private fun canonicalQuery(params: Map<String, String>): String {
        return params.entries.sortedBy { it.key }.joinToString("&") { (k, v) ->
            "${canonicalEncode(k)}=${canonicalEncode(v)}"
        }
    }

    // ==================== 工具方法 ====================

    private fun parseXmlTag(xml: String, tag: String): String {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1) ?: ""
    }

    private fun parseXmlTags(xml: String, tag: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        val itemRegex = Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(xml)) {
            val itemXml = match.groupValues[1]
            val map = mutableMapOf<String, String>()
            for (item in itemRegex.findAll(itemXml)) {
                map[item.groupValues[1]] = item.groupValues[2]
            }
            if (map.isNotEmpty()) results.add(map)
        }
        return results
    }

    // ==================== 工具实现 ====================

    private suspend fun doUpload(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val key = args["key"] as? String ?: return err("缺少 key 参数")
        val localPath = args["local_path"] as? String ?: return err("缺少 local_path 参数")

        val file = File(localPath)
        if (!file.exists()) return err("本地文件不存在: $localPath")

        return withContext(Dispatchers.IO) {
            val body = file.readBytes()
            val headers = mutableMapOf<String, String>()
            val req = signRequest("PUT", endpoint, "/$bucket/$key", region, ak, sk, headers, body, "application/octet-stream")
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                ok("message" to "已上传: $localPath -> s3://$bucket/$key (${body.size} 字节)")
            } else {
                err("上传失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doDownload(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val key = args["key"] as? String ?: return err("缺少 key 参数")
        val localPath = args["local_path"] as? String ?: return err("缺少 local_path 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("GET", endpoint, "/$bucket/$key", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val file = File(localPath)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                resp.close()
                ok("message" to "已下载: s3://$bucket/$key -> $localPath (${bytes.size} 字节)")
            } else {
                resp.close()
                err("下载失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doDelete(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val key = args["key"] as? String ?: return err("缺少 key 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("DELETE", endpoint, "/$bucket/$key", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful || resp.code == 204) {
                ok("message" to "已删除: s3://$bucket/$key")
            } else {
                err("删除失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doDeleteBatch(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val rawKeys = args["keys"] as? List<*> ?: return err("缺少 keys 参数（字符串数组）")
        val keys = rawKeys.filterIsInstance<String>()
        if (keys.isEmpty()) return err("keys 不能为空")

        return withContext(Dispatchers.IO) {
            val xmlBody = buildString {
                append("<Delete>")
                keys.forEach { append("<Object><Key>$it</Key></Object>") }
                append("</Delete>")
            }
            val headers = mutableMapOf<String, String>()
            val req = signRequest("POST", endpoint, "/$bucket", region, ak, sk, headers, xmlBody.toByteArray(), "application/xml", mapOf("delete" to ""))
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (resp.isSuccessful) {
                val deleted = parseXmlTag(body, "Key")
                ok("message" to "已批量删除 ${keys.size} 个对象", "deleted" to keys.size)
            } else {
                err("批量删除失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doList(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val prefix = args["prefix"] as? String ?: ""
        val maxKeys = (args["max_keys"] as? Double)?.toInt() ?: 100

        return withContext(Dispatchers.IO) {
            val queryParams = if (prefix.isNotBlank()) {
                mapOf("list-type" to "2", "max-keys" to maxKeys.toString(), "prefix" to prefix)
            } else {
                mapOf("list-type" to "2", "max-keys" to maxKeys.toString())
            }
            val headers = mutableMapOf<String, String>()
            val req = signRequest("GET", endpoint, "/$bucket", region, ak, sk, headers, queryParams = queryParams)
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: ""
            resp.close()

            if (!resp.isSuccessful) return@withContext err("列举失败: ${resp.code} ${resp.message}")

            val objects = parseXmlTags(xml, "Contents").map { m ->
                mapOf(
                    "key" to (m["Key"] ?: ""),
                    "size" to (m["Size"] ?: "0"),
                    "last_modified" to (m["LastModified"] ?: ""),
                    "etag" to (m["ETag"] ?: "")
                )
            }
            val truncated = parseXmlTag(xml, "IsTruncated") == "true"

            gson.toJson(mapOf(
                "success" to true,
                "bucket" to bucket,
                "prefix" to prefix,
                "count" to objects.size,
                "truncated" to truncated,
                "objects" to objects
            ))
        }
    }

    private suspend fun doCopy(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数（目标）")
        val key = args["key"] as? String ?: return err("缺少 key 参数（目标）")
        val sourceBucket = args["source_bucket"] as? String ?: bucket
        val sourceKey = args["source_key"] as? String ?: return err("缺少 source_key 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            headers["x-amz-copy-source"] = "/$sourceBucket/$sourceKey"
            val req = signRequest("PUT", endpoint, "/$bucket/$key", region, ak, sk, headers, contentType = "application/octet-stream")
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                ok("message" to "已复制: s3://$sourceBucket/$sourceKey -> s3://$bucket/$key")
            } else {
                err("复制失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doHead(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val key = args["key"] as? String ?: return err("缺少 key 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("HEAD", endpoint, "/$bucket/$key", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                val meta = mapOf(
                    "success" to true,
                    "key" to key,
                    "size" to (resp.headers["content-length"] ?: "0"),
                    "content_type" to (resp.headers["content-type"] ?: ""),
                    "etag" to (resp.headers["etag"] ?: "").removeSurrounding("\""),
                    "last_modified" to (resp.headers["last-modified"] ?: "")
                )
                gson.toJson(meta)
            } else {
                err("获取元数据失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doListBuckets(endpoint: String, region: String, ak: String, sk: String): String {
        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("GET", endpoint, "/", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: ""
            resp.close()

            if (!resp.isSuccessful) return@withContext err("列举 bucket 失败: ${resp.code} ${resp.message}")

            val buckets = parseXmlTags(xml, "Bucket").map { m ->
                mapOf("name" to (m["Name"] ?: ""), "created" to (m["CreationDate"] ?: ""))
            }
            ok("count" to buckets.size, "buckets" to buckets)
        }
    }

    private suspend fun doCreateBucket(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("PUT", endpoint, "/$bucket", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                ok("message" to "已创建 bucket: $bucket")
            } else {
                err("创建 bucket 失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doDeleteBucket(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("DELETE", endpoint, "/$bucket", region, ak, sk, headers)
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                ok("message" to "已删除 bucket: $bucket")
            } else {
                err("删除 bucket 失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doPresign(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val key = args["key"] as? String ?: return err("缺少 key 参数")
        val method = (args["method"] as? String ?: "GET").uppercase()
        val expiresSeconds = (args["expires"] as? Double)?.toInt() ?: 3600

        return withContext(Dispatchers.IO) {
            val url = java.net.URI(endpoint)
            val host = url.host + if (url.port > 0 && url.port != 80 && url.port != 443) ":${url.port}" else ""
            val now = Date()
            val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
            val amzDate = dateFormat.format(now)

            val credentialScope = "$dateStamp/$region/s3/aws4_request"
            // 预签名 URL 的 payload hash 必须是 UNSIGNED-PAYLOAD（AWS/MinIO/rustfs 约定）
            val presignQuery = listOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to "$ak/$credentialScope",
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expiresSeconds.toString(),
                "X-Amz-SignedHeaders" to "host"
            ).sortedBy { it.first }.joinToString("&") { (k, v) ->
                "${canonicalEncode(k)}=${canonicalEncode(v)}"
            }
            val canonicalRequest = buildString {
                append(method).append("\n")
                append("/$bucket/$key").append("\n")
                append(presignQuery).append("\n")
                // canonical headers 已以 \n 结尾，sigv4 还需一个分隔空行（\n\n↔SignedHeaders）
                append("host:$host").append("\n\n")
                append("host").append("\n")
                append("UNSIGNED-PAYLOAD")
            }

            val stringToSign = buildString {
                append("AWS4-HMAC-SHA256").append("\n")
                append(amzDate).append("\n")
                append(credentialScope).append("\n")
                append(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.toByteArray()).joinToString("") { "%02x".format(it) })
            }

            val signingKey = hmacSha256(
                hmacSha256(hmacSha256(hmacSha256("AWS4$sk".toByteArray(), dateStamp.toByteArray()), region.toByteArray()), "s3".toByteArray()),
                "aws4_request".toByteArray()
            )
            val signature = hmacSha256(signingKey, stringToSign.toByteArray()).joinToString("") { "%02x".format(it) }

            val presignUrl = buildString {
                append(endpoint).append("/$bucket/$key")
                append("?$presignQuery")
                append("&X-Amz-Signature=$signature")
            }

            ok("url" to presignUrl, "method" to method, "expires" to expiresSeconds)
        }
    }

    private suspend fun doCreateFolder(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val folder = args["folder"] as? String ?: return err("缺少 folder 参数")
        val folderKey = folder.trimEnd('/') + "/"

        return withContext(Dispatchers.IO) {
            val headers = mutableMapOf<String, String>()
            val req = signRequest("PUT", endpoint, "/$bucket/$folderKey", region, ak, sk, headers, ByteArray(0), "application/octet-stream")
            val resp = client.newCall(req).execute()
            resp.close()
            if (resp.isSuccessful) {
                ok("message" to "已创建文件夹: s3://$bucket/$folderKey")
            } else {
                err("创建文件夹失败: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun doDeleteFolder(endpoint: String, region: String, ak: String, sk: String, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return err("缺少 bucket 参数")
        val folder = args["folder"] as? String ?: return err("缺少 folder 参数")
        val prefix = folder.trimEnd('/') + "/"

        return withContext(Dispatchers.IO) {
            val allKeys = mutableListOf<String>()
            var continuationToken: String? = null

            do {
                val queryParams = buildMap<String, String> {
                    put("list-type", "2")
                    put("prefix", prefix)
                    if (continuationToken != null) put("continuation-token", continuationToken)
                }
                val headers = mutableMapOf<String, String>()
                val req = signRequest("GET", endpoint, "/$bucket", region, ak, sk, headers, queryParams = queryParams)
                val resp = client.newCall(req).execute()
                val xml = resp.body?.string() ?: ""
                resp.close()

                val items = parseXmlTags(xml, "Contents").mapNotNull { it["Key"] }
                allKeys.addAll(items)
                continuationToken = if (parseXmlTag(xml, "IsTruncated") == "true") parseXmlTag(xml, "NextContinuationToken") else null
            } while (continuationToken != null)

            if (allKeys.isEmpty()) return@withContext ok("message" to "文件夹为空，无需删除: s3://$bucket/$prefix")

            val batchSize = 1000
            allKeys.chunked(batchSize).forEach { batch ->
                val xmlBody = buildString {
                    append("<Delete>")
                    batch.forEach { append("<Object><Key>$it</Key></Object>") }
                    append("</Delete>")
                }
                val headers = mutableMapOf<String, String>()
                val req = signRequest("POST", endpoint, "/$bucket", region, ak, sk, headers, xmlBody.toByteArray(), "application/xml", mapOf("delete" to ""))
                val resp = client.newCall(req).execute()
                resp.close()
            }

            ok("message" to "已删除文件夹 s3://$bucket/$prefix 及其下 ${allKeys.size} 个对象")
        }
    }
}

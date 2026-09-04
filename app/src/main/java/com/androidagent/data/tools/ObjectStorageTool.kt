package com.androidagent.data.tools

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import com.androidagent.data.AppPreferences
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * 对象存储工具（S3 兼容）
 * 通过 action 参数分发：upload / download / delete / delete_batch / list / copy /
 * head / list_buckets / create_bucket / delete_bucket / presign / create_folder / delete_folder
 */
class ObjectStorageTool : Tool {

    private val gson = Gson()

    override val name: String = "file_*"

    override val description: String =
        "对象存储（S3 兼容）文件管理工具。支持上传、下载、删除、列举、复制、元数据查询、" +
        "bucket 管理、文件夹管理、预签名 URL 生成等操作。" +
        "所有连接参数从应用设置中自动读取（endpoint / accessKey / secretKey），" +
        "无需手动传入。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作类型",
                "enum" to listOf(
                    "upload", "download", "delete", "delete_batch", "list",
                    "copy", "head", "list_buckets", "create_bucket", "delete_bucket",
                    "presign", "create_folder", "delete_folder"
                )
            ),
            "bucket" to mapOf("type" to "string", "description" to "Bucket 名称"),
            "key" to mapOf("type" to "string", "description" to "对象 key（文件路径）"),
            "local_path" to mapOf("type" to "string", "description" to "本地文件路径（upload/download 时使用）"),
            "source_bucket" to mapOf("type" to "string", "description" to "源 Bucket（copy 时使用）"),
            "source_key" to mapOf("type" to "string", "description" to "源对象 key（copy 时使用）"),
            "keys" to mapOf(
                "type" to "array",
                "description" to "要删除的 key 列表（delete_batch 时使用）",
                "items" to mapOf("type" to "string")
            ),
            "prefix" to mapOf("type" to "string", "description" to "列举前缀（list 时使用）"),
            "max_keys" to mapOf("type" to "integer", "description" to "最大返回数量（list 时使用，默认 100）"),
            "folder" to mapOf("type" to "string", "description" to "文件夹名称（create_folder/delete_folder 时使用）"),
            "method" to mapOf(
                "type" to "string",
                "description" to "预签名 HTTP 方法（presign 时使用，默认 GET）",
                "enum" to listOf("GET", "PUT")
            ),
            "expires" to mapOf("type" to "integer", "description" to "预签名 URL 有效秒数（presign 时使用，默认 3600）")
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
            ?: return """{"error": "缺少 action 参数"}"""

        val endpointUrl = AppPreferences.s3EndpointUrl
        val accessKey = AppPreferences.s3AccessKey
        val secretKey = AppPreferences.s3SecretKey

        if (endpointUrl.isBlank()) return """{"error": "未配置对象存储服务地址 (Endpoint URL)"}"""
        if (accessKey.isBlank()) return """{"error": "未配置对象存储 Access Key"}"""
        if (secretKey.isBlank()) return """{"error": "未配置对象存储 Secret Key"}"""

        return try {
            val client = buildS3Client(endpointUrl, accessKey, secretKey)
            try {
                when (action) {
                    "upload" -> doUpload(client, args)
                    "download" -> doDownload(client, args)
                    "delete" -> doDelete(client, args)
                    "delete_batch" -> doDeleteBatch(client, args)
                    "list" -> doList(client, args)
                    "copy" -> doCopy(client, args)
                    "head" -> doHead(client, args)
                    "list_buckets" -> doListBuckets(client)
                    "create_bucket" -> doCreateBucket(client, args)
                    "delete_bucket" -> doDeleteBucket(client, args)
                    "presign" -> doPresign(client, args)
                    "create_folder" -> doCreateFolder(client, args)
                    "delete_folder" -> doDeleteFolder(client, args)
                    else -> """{"error": "未知操作: $action"}"""
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            """{"error": "对象存储操作失败: ${e.message}"}"""
        }
    }

    private fun buildS3Client(endpointUrl: String, accessKey: String, secretKey: String): S3Client {
        val endpoint = endpointUrl.trimEnd('/')
        val region = extractRegion(endpoint)

        return S3Client {
            credentialsProvider = StaticCredentialsProvider {
                this.accessKeyId = accessKey
                this.secretAccessKey = secretKey
            }
            this.region = region
            this.endpointUrl = aws.smithy.kotlin.runtime.net.url.Url.parse(endpoint)
            this.forcePathStyle = true
        }
    }

    private fun extractRegion(endpoint: String): String {
        val host = try {
            java.net.URI(endpoint).host
        } catch (_: Exception) {
            ""
        }
        if (host.contains("amazonaws.com")) {
            val parts = host.split(".")
            if (parts.size >= 3) return parts[1]
        }
        return "us-east-1"
    }

    private suspend fun doUpload(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数"}"""
        val localPath = args["local_path"] as? String ?: return """{"error": "缺少 local_path 参数"}"""

        val file = File(localPath)
        if (!file.exists()) return """{"error": "本地文件不存在: $localPath"}"""

        return withContext(Dispatchers.IO) {
            client.putObject {
                this.bucket = bucket
                this.key = key
                body = file.asByteStream()
            }
            val size = file.length()
            """{"success":true,"message":"已上传: $localPath -> s3://$bucket/$key ($size 字节)"}"""
        }
    }

    private suspend fun doDownload(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数"}"""
        val localPath = args["local_path"] as? String ?: return """{"error": "缺少 local_path 参数"}"""

        return withContext(Dispatchers.IO) {
            val response = client.getObject(GetObjectRequest {
                this.bucket = bucket
                this.key = key
            }) { resp ->
                val bytes = resp.body?.toByteArray() ?: ByteArray(0)
                val file = File(localPath)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                bytes.size.toLong()
            }
            """{"success":true,"message":"已下载: s3://$bucket/$key -> $localPath ($response 字节)"}"""
        }
    }

    private suspend fun doDelete(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数"}"""

        return withContext(Dispatchers.IO) {
            client.deleteObject {
                this.bucket = bucket
                this.key = key
            }
            """{"success":true,"message":"已删除: s3://$bucket/$key"}"""
        }
    }

    private suspend fun doDeleteBatch(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val rawKeys = args["keys"] as? List<*>
            ?: return """{"error": "缺少 keys 参数（字符串数组）"}"""
        val keys = rawKeys.filterIsInstance<String>()
        if (keys.isEmpty()) return """{"error": "keys 不能为空"}"""

        return withContext(Dispatchers.IO) {
            val response = client.deleteObjects {
                this.bucket = bucket
                delete = Delete {
                    objects = keys.map { ObjectIdentifier { this.key = it } }
                }
            }
            val deleted = response.deleted?.size ?: 0
            val errors = response.errors?.map { "${it.key}: ${it.message}" } ?: emptyList()
            if (errors.isNotEmpty()) {
                """{"success":false,"deleted":$deleted,"errors":${gson.toJson(errors)}}"""
            } else {
                """{"success":true,"message":"已批量删除 $deleted 个对象","deleted":$deleted}"""
            }
        }
    }

    private suspend fun doList(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val prefix = args["prefix"] as? String ?: ""
        val maxKeys = (args["max_keys"] as? Double)?.toInt() ?: 100

        return withContext(Dispatchers.IO) {
            val response = client.listObjectsV2 {
                this.bucket = bucket
                this.prefix = prefix
                this.maxKeys = maxKeys
            }

            val objects = response.contents?.map { obj ->
                mapOf(
                    "key" to (obj.key ?: ""),
                    "size" to (obj.size ?: 0),
                    "last_modified" to (obj.lastModified?.toString() ?: ""),
                    "etag" to (obj.eTag ?: "")
                )
            } ?: emptyList()

            val result = mapOf(
                "success" to true,
                "bucket" to bucket,
                "prefix" to prefix,
                "count" to objects.size,
                "truncated" to (response.isTruncated ?: false),
                "objects" to objects
            )
            gson.toJson(result)
        }
    }

    private suspend fun doCopy(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数（目标）"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数（目标）"}"""
        val sourceBucket = args["source_bucket"] as? String ?: bucket
        val sourceKey = args["source_key"] as? String ?: return """{"error": "缺少 source_key 参数"}"""

        return withContext(Dispatchers.IO) {
            client.copyObject {
                this.sourceBucket = sourceBucket
                this.sourceKey = sourceKey
                this.bucket = bucket
                this.key = key
            }
            """{"success":true,"message":"已复制: s3://$sourceBucket/$sourceKey -> s3://$bucket/$key"}"""
        }
    }

    private suspend fun doHead(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数"}"""

        return withContext(Dispatchers.IO) {
            val response = client.headObject {
                this.bucket = bucket
                this.key = key
            }
            val result = mapOf(
                "success" to true,
                "key" to key,
                "size" to (response.contentLength ?: 0),
                "content_type" to (response.contentType ?: ""),
                "etag" to (response.eTag ?: ""),
                "last_modified" to (response.lastModified?.toString() ?: ""),
                "metadata" to (response.metadata ?: emptyMap())
            )
            gson.toJson(result)
        }
    }

    private suspend fun doListBuckets(client: S3Client): String {
        return withContext(Dispatchers.IO) {
            val response = client.listBuckets()
            val buckets = response.buckets?.map { b ->
                mapOf(
                    "name" to (b.name ?: ""),
                    "created" to (b.creationDate?.toString() ?: "")
                )
            } ?: emptyList()
            val result = mapOf(
                "success" to true,
                "count" to buckets.size,
                "buckets" to buckets
            )
            gson.toJson(result)
        }
    }

    private suspend fun doCreateBucket(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""

        return withContext(Dispatchers.IO) {
            client.createBucket {
                this.bucket = bucket
            }
            """{"success":true,"message":"已创建 bucket: $bucket"}"""
        }
    }

    private suspend fun doDeleteBucket(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""

        return withContext(Dispatchers.IO) {
            client.deleteBucket {
                this.bucket = bucket
            }
            """{"success":true,"message":"已删除 bucket: $bucket"}"""
        }
    }

    private suspend fun doPresign(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val key = args["key"] as? String ?: return """{"error": "缺少 key 参数"}"""
        val method = (args["method"] as? String ?: "GET").uppercase()
        val expiresSeconds = (args["expires"] as? Double)?.toInt() ?: 3600

        return withContext(Dispatchers.IO) {
            val duration = aws.smithy.kotlin.runtime.time.Duration(expiresSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            val url = when (method) {
                "PUT" -> {
                    val req = PutObjectRequest {
                        this.bucket = bucket
                        this.key = key
                    }
                    client.presignPutObject(req, duration) { }
                }
                else -> {
                    val req = GetObjectRequest {
                        this.bucket = bucket
                        this.key = key
                    }
                    client.presignGetObject(req, duration) { }
                }
            }
            """{"success":true,"url":"${url.url.buildString()}","method":"$method","expires":$expiresSeconds}"""
        }
    }

    private suspend fun doCreateFolder(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val folder = args["folder"] as? String ?: return """{"error": "缺少 folder 参数"}"
        val folderKey = folder.trimEnd('/') + "/"

        return withContext(Dispatchers.IO) {
            client.putObject {
                this.bucket = bucket
                this.key = folderKey
                body = ByteStream.fromString("")
            }
            """{"success":true,"message":"已创建文件夹: s3://$bucket/$folderKey"}"""
        }
    }

    private suspend fun doDeleteFolder(client: S3Client, args: Map<String, Any>): String {
        val bucket = args["bucket"] as? String ?: return """{"error": "缺少 bucket 参数"}"""
        val folder = args["folder"] as? String ?: return """{"error": "缺少 folder 参数"}"
        val prefix = folder.trimEnd('/') + "/"

        return withContext(Dispatchers.IO) {
            var totalCount = 0
            var continuationToken: String? = null
            val allKeys = mutableListOf<String>()

            do {
                val response = client.listObjectsV2 {
                    this.bucket = bucket
                    this.prefix = prefix
                    this.continuationToken = continuationToken
                }
                response.contents?.forEach { obj ->
                    obj.key?.let { allKeys.add(it) }
                }
                continuationToken = if (response.isTruncated == true) response.nextContinuationToken else null
            } while (continuationToken != null)

            if (allKeys.isEmpty()) {
                return@withContext """{"success":true,"message":"文件夹为空，无需删除: s3://$bucket/$prefix"}"""
            }

            val batchSize = 1000
            allKeys.chunked(batchSize).forEach { batch ->
                client.deleteObjects {
                    this.bucket = bucket
                    delete = Delete {
                        objects = batch.map { ObjectIdentifier { key = it } }
                    }
                }
                totalCount += batch.size
            }

            """{"success":true,"message":"已删除文件夹 s3://$bucket/$prefix 及其下 $totalCount 个对象"}"""
        }
    }
}

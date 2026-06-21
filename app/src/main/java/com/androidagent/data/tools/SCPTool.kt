package com.androidagent.data.tools

import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * SCP 文件传输工具
 * AI 通过此工具向 SSH 服务器上传/下载文件。
 * 所有连接参数由 AI 调用时直接传入。
 */
class SCPTool : Tool {

    override val name: String = "ssh_scp"

    override val description: String =
        "通过 SCP 向远程服务器传输文件。支持上传（本地→服务器）和下载（服务器→本地）。" +
        "需提供 host/port/username/password 连接参数。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作: 'upload'（上传）或 'download'（下载）",
                "enum" to listOf("upload", "download")
            ),
            "host" to mapOf(
                "type" to "string",
                "description" to "SSH 服务器地址（必填）"
            ),
            "port" to mapOf(
                "type" to "integer",
                "description" to "SSH 端口（必填）"
            ),
            "username" to mapOf(
                "type" to "string",
                "description" to "SSH 用户名（必填）"
            ),
            "password" to mapOf(
                "type" to "string",
                "description" to "SSH 密码（必填）"
            ),
            "local_path" to mapOf(
                "type" to "string",
                "description" to "本地文件路径（upload=源文件，download=保存目标）"
            ),
            "remote_path" to mapOf(
                "type" to "string",
                "description" to "远程路径（upload=目标，download=源文件）"
            )
        ),
        "required" to listOf("action", "host", "port", "username", "password", "local_path", "remote_path")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return """{"error": "缺少 action 参数"}"""
        val host = args["host"] as? String ?: return """{"error": "缺少 host 参数"}"""
        val port = (args["port"] as? Double)?.toInt() ?: (args["port"] as? String)?.toIntOrNull() ?: return """{"error": "缺少 port 参数"}"""
        val username = args["username"] as? String ?: return """{"error": "缺少 username 参数"}"""
        val password = args["password"] as? String ?: return """{"error": "缺少 password 参数"}"""
        val localPath = args["local_path"] as? String ?: return """{"error": "缺少 local_path 参数"}"""
        val remotePath = args["remote_path"] as? String ?: return """{"error": "缺少 remote_path 参数"}"""

        return try {
            when (action) {
                "upload" -> scpUpload(host, port, username, password, localPath, remotePath)
                "download" -> scpDownload(host, port, username, password, localPath, remotePath)
                else -> """{"error": "未知操作: $action，仅支持 upload/download"}"""
            }
        } catch (e: Exception) {
            """{"error": "SCP 操作失败: ${e.message}"}"""
        }
    }

    private suspend fun scpUpload(host: String, port: Int, username: String, password: String,
                                   localPath: String, remotePath: String): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val localFile = File(localPath)
            if (!localFile.exists()) return@withContext """{"error": "本地文件不存在: $localPath"}"""

            val jsch = JSch()
            session = jsch.getSession(username, host, port).apply {
                setPassword(password)
                setConfig("StrictHostKeyChecking", "no")
                connect(10000)
            }

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("scp -p -d -t $remotePath")

            val outputStream = channel.outputStream
            val inputStream = channel.inputStream
            channel.connect()

            checkAck(inputStream)
            val cmdBytes = "C0644 ${localFile.length()} ${localFile.name}\n".toByteArray()
            outputStream.write(cmdBytes); outputStream.flush()
            checkAck(inputStream)

            FileInputStream(localFile).use { fis ->
                val buf = ByteArray(1024)
                while (true) { val n = fis.read(buf); if (n <= 0) break; outputStream.write(buf, 0, n) }
            }
            outputStream.write(0); outputStream.flush()
            checkAck(inputStream)

            """{"success":true,"message":"已上传: $localPath -> $remotePath (${localFile.length()} 字节)"}"""
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    private suspend fun scpDownload(host: String, port: Int, username: String, password: String,
                                     localPath: String, remotePath: String): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val jsch = JSch()
            session = jsch.getSession(username, host, port).apply {
                setPassword(password)
                setConfig("StrictHostKeyChecking", "no")
                connect(10000)
            }

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("scp -f $remotePath")

            val outputStream = channel.outputStream
            val inputStream = channel.inputStream
            channel.connect()

            val buf = ByteArray(1024)
            outputStream.write(0); outputStream.flush()

            var line = ""
            while (true) { val c = inputStream.read(); if (c < 0 || c.toChar() == '\n') break; line += c.toChar() }
            if (!line.startsWith("C")) return@withContext """{"error": "SCP 协议错误: $line"}"""

            val parts = line.substring(1).trim().split(" ")
            val fileSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L

            outputStream.write(0); outputStream.flush()

            val localFile = File(localPath)
            localFile.parentFile?.mkdirs()
            localFile.outputStream().use { fos ->
                var remaining = fileSize
                while (remaining > 0) {
                    val n = inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    fos.write(buf, 0, n); remaining -= n
                }
            }

            """{"success":true,"message":"已下载: $remotePath -> $localPath (${fileSize} 字节)"}"""
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun checkAck(inputStream: java.io.InputStream) {
        when (val b = inputStream.read()) {
            0 -> {}
            1, 2 -> {
                val sb = StringBuffer(); var c: Int
                while (inputStream.read().also { c = it } > 0) sb.append(c.toChar())
                throw Exception("SCP 错误: $sb")
            }
        }
    }
}

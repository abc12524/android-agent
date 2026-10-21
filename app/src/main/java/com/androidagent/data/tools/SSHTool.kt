package com.androidagent.data.tools

import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * SSH 工具（JSch）：远程命令执行 + SCP 文件传输。
 * 所有连接参数由 AI 调用时直接传入，支持密码认证和私钥认证。
 */
class SSHTool : Tool {

    override val name: String = "ssh"

    override val description: String =
        "SSH 远程执行与 SCP 文件传输。action=exec 执行命令，action=upload/download 传输文件。" +
            "需提供 host、port、username，以及 password 或 private_key / private_key_path。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "description" to "操作: exec（执行命令，默认）/ upload（上传）/ download（下载）",
                "enum" to listOf("exec", "upload", "download")
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
                "description" to "SSH 密码（password / private_key / private_key_path 三选一）"
            ),
            "private_key" to mapOf(
                "type" to "string",
                "description" to "SSH 私钥内容（PEM 格式）。password / private_key / private_key_path 三选一"
            ),
            "private_key_path" to mapOf(
                "type" to "string",
                "description" to "私钥文件路径（设备上的文件），程序自动读取。password / private_key / private_key_path 三选一"
            ),
            "passphrase" to mapOf(
                "type" to "string",
                "description" to "私钥的密码短语（加密私钥时需要，可选）"
            ),
            "command" to mapOf(
                "type" to "string",
                "description" to "action=exec 时要在远程服务器上执行的 Shell 命令（必填）"
            ),
            "local_path" to mapOf(
                "type" to "string",
                "description" to "action=upload/download 时的本机文件路径（必填）"
            ),
            "remote_path" to mapOf(
                "type" to "string",
                "description" to "action=upload/download 时的远端文件路径（必填）"
            )
        ),
        "required" to listOf("host", "port", "username")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()?.lowercase().orEmpty().ifBlank { "exec" }
        val host = args["host"] as? String ?: return """{"error": "缺少 host 参数"}"""
        val port = (args["port"] as? Double)?.toInt()
            ?: (args["port"] as? String)?.toIntOrNull()
            ?: return """{"error": "缺少 port 参数"}"""
        val username = args["username"] as? String ?: return """{"error": "缺少 username 参数"}"""
        val password = args["password"] as? String
        val privateKey = args["private_key"] as? String
        val privateKeyPath = args["private_key_path"] as? String
        val passphrase = args["passphrase"] as? String

        // 解析私钥：优先用 private_key 字符串，否则读 private_key_path 文件
        val resolvedKey = when {
            !privateKey.isNullOrBlank() -> privateKey
            !privateKeyPath.isNullOrBlank() -> try {
                File(privateKeyPath).readText()
            } catch (e: Exception) {
                return """{"error": "读取私钥文件失败: ${e.message}"}"""
            }
            else -> null
        }

        if (password.isNullOrBlank() && resolvedKey.isNullOrBlank()) {
            return """{"error": "缺少认证信息，请提供 password、private_key 或 private_key_path"}"""
        }

        return try {
            when (action) {
                "exec" -> {
                    val command = args["command"] as? String
                        ?: return """{"error": "action=exec 需要 command 参数"}"""
                    sshExec(host, port, username, password, resolvedKey, passphrase, command)
                }
                "upload", "download" -> {
                    val localPath = args["local_path"] as? String
                        ?: return """{"error": "action=$action 需要 local_path 参数"}"""
                    val remotePath = args["remote_path"] as? String
                        ?: return """{"error": "action=$action 需要 remote_path 参数"}"""
                    if (action == "upload") {
                        scpUpload(host, port, username, password, resolvedKey, passphrase, localPath, remotePath)
                    } else {
                        scpDownload(host, port, username, password, resolvedKey, passphrase, localPath, remotePath)
                    }
                }
                else -> """{"error": "未知 action: $action，仅支持 exec/upload/download"}"""
            }
        } catch (e: com.jcraft.jsch.JSchException) {
            val detail = when {
                e.message?.contains("Auth fail") == true ->
                    "认证失败：服务器拒绝了提供的凭据。请检查用户名是否正确，私钥/密码是否匹配"
                e.message?.contains("PRIVATE KEY") == true ||
                    e.message?.contains("invalid privatekey") == true ->
                    "私钥格式无效：JSch 仅支持 PEM 格式，不支持 OpenSSH 格式"
                else -> "SSH 错误: ${e.message}"
            }
            """{"error": "$detail"}"""
        } catch (e: Exception) {
            """{"error": "SSH 操作失败: ${e.message}"}"""
        }
    }

    private suspend fun sshExec(
        host: String, port: Int, username: String,
        password: String?, privateKey: String?, passphrase: String?, command: String
    ): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val sshSession = createSession(host, port, username, password, privateKey, passphrase)
            session = sshSession

            val chan = sshSession.openChannel("exec") as ChannelExec
            channel = chan
            chan.setCommand(command)

            val output = ByteArrayOutputStream()
            val inputStream = chan.inputStream
            chan.connect(60000)

            val buf = ByteArray(1024)
            while (true) {
                val len = inputStream?.read(buf, 0, buf.size) ?: -1
                if (len <= 0) break
                output.write(buf, 0, len)
            }

            val exitCode = chan.exitStatus
            val stdOut = output.toString("UTF-8")
            val out = if (stdOut.isNotBlank()) stdOut else "命令执行成功（无输出）"
            val maxLen = 8000
            val truncated = if (out.length > maxLen) {
                "${out.take(maxLen)}\n...（输出截断，原长 ${out.length} 字符）"
            } else out

            """{"success":true,"exit_code":$exitCode,"output":"${truncated.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}"}"""
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 创建 SSH 会话（exec / scp 共用） */
    private fun createSession(host: String, port: Int, username: String,
                              password: String?, privateKey: String?, passphrase: String?): Session {
        val jsch = JSch()
        if (!privateKey.isNullOrBlank()) {
            val passphraseBytes = if (!passphrase.isNullOrBlank()) passphrase.toByteArray() else null
            jsch.addIdentity("ssh_key_${host}_$port",
                normalizePem(privateKey).toByteArray(), null, passphraseBytes)
        }
        return jsch.getSession(username, host, port).apply {
            if (!password.isNullOrBlank()) setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            connect(10000)
        }
    }

    private suspend fun scpUpload(host: String, port: Int, username: String,
                                  password: String?, privateKey: String?, passphrase: String?,
                                  localPath: String, remotePath: String): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val localFile = File(localPath)
            if (!localFile.exists()) return@withContext """{"error": "本地文件不存在: $localPath"}"""

            session = createSession(host, port, username, password, privateKey, passphrase)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("scp -p -d -t ${shellQuote(remotePath)}")

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

    private suspend fun scpDownload(host: String, port: Int, username: String,
                                    password: String?, privateKey: String?, passphrase: String?,
                                    localPath: String, remotePath: String): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            session = createSession(host, port, username, password, privateKey, passphrase)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("scp -f ${shellQuote(remotePath)}")

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

    /** 单引号转义，避免远端路径被 shell 解释。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * 归一化 PEM 私钥文本
 * 处理 AI 可能传递的各种换行格式
 */
internal fun normalizePem(pem: String): String {
    // 1. 替换字面 \n 为真正换行（AI 可能传 JSON 转义后的字符串）
    var normalized = pem.replace("\\n", "\n")
    // 2. 统一换行符
    normalized = normalized.replace("\r\n", "\n").replace("\r", "\n")
    // 3. 去掉首尾空白
    normalized = normalized.trim()
    // 4. 确保有正确的尾部标记
    if (!normalized.endsWith("-----END")) {
        val lastLine = normalized.substringAfterLast("\n")
        if (lastLine.startsWith("-----END") || lastLine.startsWith("***--end")) {
            normalized = normalized.substringBeforeLast("\n") + "\n" +
                lastLine.replace("***--end", "-----END").let {
                    if (!it.endsWith("-----")) it + "-----" else it
                }
        }
    }
    return normalized
}

package com.androidagent.data.tools

import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SSH 远程命令执行工具
 * 基于 JSch 实现，支持密码认证和私钥认证。
 * 所有连接参数由 AI 调用时直接传入，无需预配置。
 */
class SSHTool : Tool {

    override val name: String = "ssh_execute"

    override val description: String =
        "通过 SSH 连接远程服务器并执行 Shell 命令。支持密码认证和私钥认证。" +
        "需提供 host、port、username，以及 password 或 private_key。"
        // RSA/DSA/ECDSA/ED25519 私钥均可

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
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
                "description" to "SSH 密码（private_key 和 password 二选一）"
            ),
            "private_key" to mapOf(
                "type" to "string",
                "description" to "SSH 私钥内容（PEM 格式，如 -----BEGIN RSA PRIVATE KEY-----...）。private_key 和 password 二选一"
            ),
            "passphrase" to mapOf(
                "type" to "string",
                "description" to "私钥的密码短语（仅 private_key 加密时需要，可选）"
            ),
            "command" to mapOf(
                "type" to "string",
                "description" to "要在远程服务器上执行的 Shell 命令（必填）"
            )
        ),
        "required" to listOf("host", "port", "username", "command")
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val host = args["host"] as? String ?: return """{"error": "缺少 host 参数"}"""
        val port = (args["port"] as? Double)?.toInt() ?: (args["port"] as? String)?.toIntOrNull() ?: return """{"error": "缺少 port 参数"}"""
        val username = args["username"] as? String ?: return """{"error": "缺少 username 参数"}"""
        val password = args["password"] as? String
        val privateKey = args["private_key"] as? String
        val passphrase = args["passphrase"] as? String
        val command = args["command"] as? String ?: return """{"error": "缺少 command 参数"}"""

        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return """{"error": "缺少认证信息，请提供 password 或 private_key"}"""
        }

        return try {
            sshExec(host, port, username, password, privateKey, passphrase, command)
        } catch (e: Exception) {
            """{"error": "SSH 执行失败: ${e.message}"}"""
        }
    }

    private suspend fun sshExec(
        host: String, port: Int, username: String,
        password: String?, privateKey: String?, passphrase: String?, command: String
    ): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            val jsch = JSch()

            // 私钥认证
            if (!privateKey.isNullOrBlank()) {
                val passphraseBytes = if (!passphrase.isNullOrBlank()) passphrase.toByteArray() else null
                jsch.addIdentity("ssh_key_${host}_$port",
                    privateKey.toByteArray(), null, passphraseBytes)
            }

            val sshSession = jsch.getSession(username, host, port).apply {
                // 密码认证（当没有私钥时）
                if (!password.isNullOrBlank()) {
                    setPassword(password)
                }
                setConfig("StrictHostKeyChecking", "no")
                connect(10000)
                session = this
            }

            val chan = sshSession.openChannel("exec") as ChannelExec
            channel = chan
            chan.setCommand(command)

            val output = ByteArrayOutputStream()
            val inputStream = chan.inputStream
            chan.connect(30000)

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
}

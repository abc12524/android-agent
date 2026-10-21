package com.androidagent.data.memory

import com.androidagent.data.AppPreferences
import com.androidagent.data.api.DeepSeekClient.ChatMessage
import com.google.gson.JsonObject

/**
 * OpenViking 自动记忆：召回（问题 / 每步）与抓取提交。
 * 所有失败静默降级为空，不打断对话。
 */
class RecallEngine(private val ov: OpenVikingClient = OpenVikingClient()) {

    /** 当前会话 ID；切换会话时清空已注入记录，保证每个会话都是全新的 */
    private var currentSessionId: String = ""

    /** 本会话已注入过的记忆标识（uri；uri 为空时用摘要） */
    private val seen = mutableSetOf<String>()

    private fun resetForSession(sessionId: String) {
        if (sessionId != currentSessionId) {
            currentSessionId = sessionId
            seen.clear()
        }
    }

    /** 以当前会话历史为准重建已注入记录：不跨会话继承，也不保留被回滚的注入。 */
    fun seedSession(sessionId: String, injectedUris: List<String>) {
        currentSessionId = sessionId
        seen.clear()
        injectedUris.forEach { uri -> if (uri.isNotBlank()) seen.add(uri) }
    }

    /**
     * 基于完整消息批次召回相关记忆，返回可注入的块（含标记）。
     * 同一会话内不重复注入相同记忆；未配置、条数为 0、query 过短或无命中时返回空串。
     */
    suspend fun recall(messages: List<ChatMessage>, sessionId: String): String {
        resetForSession(sessionId)
        if (AppPreferences.ovFindLimit <= 0) return ""
        if (AppPreferences.openVikingUrl.isBlank()) return ""

        val query = Recall.buildQuery(messages)
        if (query.length < Recall.MIN_QUERY_LENGTH) return ""

        val hits = ov.findHits(query, AppPreferences.ovFindThreshold, AppPreferences.ovFindLimit)
        if (hits.isEmpty()) return ""

        val top = hits.asSequence()
            .map { it to hitKey(it) }
            .filter { (_, key) -> key.isBlank() || key !in seen }
            .take(AppPreferences.ovFindLimit)
            .toList()
        if (top.isEmpty()) return ""

        val sb = StringBuilder(Recall.RECALL_MARKER)
        for ((obj, key) in top) {
            val abstract = obj.str("abstract").take(300)
            if (abstract.isBlank()) continue
            val uri = obj.str("uri")
            val ctype = obj.str("context_type")
            sb.append("\n- [").append(uri).append("] (score=")
                .append(String.format("%.2f", obj.num("score")))
            if (ctype.isNotBlank()) sb.append(", ").append(ctype)
            sb.append(")\n  ").append(abstract)
            if (key.isNotBlank()) seen.add(key)
        }
        return if (sb.length > Recall.RECALL_MARKER.length) sb.toString() else ""
    }

    private fun hitKey(obj: JsonObject): String {
        val uri = obj.str("uri").trim()
        return uri.ifBlank { obj.str("abstract").trim() }
    }

    /** 为本地会话建立 OV session（失败返回空串）。 */
    suspend fun ensureSession(): String = ov.ensureSession()

    /** 抓取一批对话消息到 OV session（失败静默）。 */
    suspend fun capture(ovSessionId: String, messages: List<ChatMessage>) {
        if (ovSessionId.isBlank() || messages.isEmpty()) return
        val ovMessages = Recall.toOvMessages(messages)
        if (ovMessages.isNotEmpty()) ov.capture(ovSessionId, ovMessages)
    }

    /** 提交/归档 OV session，触发记忆提取（失败静默）。 */
    suspend fun commit(ovSessionId: String) {
        if (ovSessionId.isNotBlank()) ov.commitSession(ovSessionId)
    }

    private fun JsonObject.str(key: String): String = try {
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun JsonObject.num(key: String): Double = try {
        get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
    } catch (_: Exception) {
        0.0
    }
}

package com.androidagent.ui.chat

/** 消息里可单独渲染的图片 / 音频。 */
data class MediaImageItem(val source: String, val alt: String? = null)
data class MediaAudioItem(val source: String, val label: String? = null)

/** 解析结果：去掉媒体语法后的正文 + 图片 + 音频。 */
data class ParsedMedia(
    val text: String,
    val images: List<MediaImageItem>,
    val audios: List<MediaAudioItem>,
)

/**
 * 从助手正文 / 工具结果里提取媒体：
 *  - `[image:path]` → 图片
 *  - `![alt](path)` → 图片
 *  - `[audio:path]` → 音频
 *  - `[说明](path.mp3)` → 音频
 * 代码块（``` / ~~~）内的标记保持原样。
 */
object MediaParser {
    private val fenceRe = Regex("""(```|~~~)[\s\S]*?\1""")
    private val imageMarkerRe = Regex("""\[image:(.+?)]""")
    private val imageMdRe = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val audioMarkerRe = Regex("""\[audio:(.+?)]""")
    private val audioLinkRe = Regex("""(?<!!)\[([^\]\n]*)]\(([^)\n]+)\)""")
    private val imageExts = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
    )
    private val audioExts = setOf(
        "mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac",
        "weba", "mpga", "caf", "amr", "mid", "midi", "wma",
    )

    fun parse(content: String): ParsedMedia {
        if (content.isEmpty()) return ParsedMedia("", emptyList(), emptyList())
        val images = mutableListOf<MediaImageItem>()
        val audios = mutableListOf<MediaAudioItem>()
        val buffer = StringBuilder()
        var last = 0
        for (match in fenceRe.findAll(content)) {
            buffer.append(parseSegment(content.substring(last, match.range.first), images, audios))
            buffer.append(match.value)
            last = match.range.last + 1
        }
        buffer.append(parseSegment(content.substring(last), images, audios))
        return ParsedMedia(buffer.toString(), images, audios)
    }

    private fun parseSegment(
        segment: String,
        images: MutableList<MediaImageItem>,
        audios: MutableList<MediaAudioItem>,
    ): String {
        if (segment.isEmpty()) return segment
        var text = segment

        text = imageMarkerRe.replace(text) { match ->
            val raw = match.groupValues[1].trim()
            if (raw.isEmpty() || raw == "generated") {
                ""
            } else {
                images.add(MediaImageItem(raw))
                ""
            }
        }

        text = audioMarkerRe.replace(text) { match ->
            val raw = match.groupValues[1].trim()
            if (raw.isEmpty()) {
                match.value
            } else {
                audios.add(MediaAudioItem(raw))
                ""
            }
        }

        text = imageMdRe.replace(text) { match ->
            val alt = match.groupValues[1].trim()
            val src = match.groupValues[2].trim()
            if (src.isEmpty()) {
                match.value
            } else {
                images.add(MediaImageItem(src, alt.ifBlank { null }))
                ""
            }
        }

        text = audioLinkRe.replace(text) { match ->
            val label = match.groupValues[1].trim()
            val src = match.groupValues[2].trim()
            if (!isAudioSource(src)) {
                match.value
            } else {
                audios.add(MediaAudioItem(src, label.ifBlank { null }))
                ""
            }
        }
        return text
    }

    fun isImageSource(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("data:image/", ignoreCase = true)) return true
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) return true
        val path = value.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.lastIndex) return false
        return imageExts.contains(path.substring(dot + 1).lowercase())
    }

    fun isAudioSource(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("data:audio/", ignoreCase = true)) return true
        val path = value.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.lastIndex) return false
        return audioExts.contains(path.substring(dot + 1).lowercase())
    }

    fun audioLabel(source: String, label: String?): String {
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val value = source.trim()
        if (value.startsWith("data:", ignoreCase = true)) return "音频"
        val name = value.substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        return name.ifBlank { "音频" }
    }
}

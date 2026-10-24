package com.androidagent.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 当前正在播放的音频（新音频开始播放时暂停旧的）。 */
private val activeAudioPlayer = mutableStateOf<MediaPlayer?>(null)

/** 渲染一条图片：支持 http(s) / data: / content:// / file:// / 本地沙盒路径。 */
@Composable
fun MediaImage(source: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = MediaImageLoader.cached(source), source) {
        if (value == null) value = MediaImageLoader.load(context, source)
    }
    val bmp = bitmap ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

private object MediaImageLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(source: String): Bitmap? = cache.get(source)

    suspend fun load(context: Context, source: String): Bitmap? {
        cache.get(source)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { decode(context, source) }.getOrNull()
        } ?: return null
        cache.put(source, bitmap)
        return bitmap
    }

    private fun decode(context: Context, source: String): Bitmap? {
        val raw = source.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("data:", ignoreCase = true)) return decodeDataUri(raw)
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            val request = Request.Builder().url(raw).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                return decodeSampled(bytes, 1080)
            }
        }
        if (raw.startsWith("content://", ignoreCase = true)) {
            val bytes = context.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
            return bytes?.let { decodeSampled(it, 1080) }
        }
        val file = resolveLocalFile(context, raw) ?: return null
        return decodeSampled(file, 1080)
    }

    private fun decodeDataUri(raw: String): Bitmap? {
        val marker = raw.indexOf("base64,")
        if (marker < 0) return null
        val bytes = runCatching {
            Base64.decode(raw.substring(marker + "base64,".length), Base64.DEFAULT)
        }.getOrNull() ?: return null
        return decodeSampled(bytes, 1080)
    }

    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > maxDim * 2) sample *= 2
        return sample
    }
}

/** 内联音频条：用户点击才播放；支持 http(s) / data: / content:// / 本地沙盒路径。 */
@Composable
fun AudioAttachmentPlayer(source: String, label: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displayLabel = remember(source, label) { MediaParser.audioLabel(source, label) }

    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(source) { mutableStateOf(false) }
    var preparing by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var completed by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    var durationMs by remember(source) { mutableLongStateOf(0L) }
    var positionMs by remember(source) { mutableLongStateOf(0L) }

    fun releasePlayer() {
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
        runCatching { current.release() }
        if (activeAudioPlayer.value === current) activeAudioPlayer.value = null
        player = null
    }

    fun toggle() {
        val current = player
        if (current != null && prepared) {
            when {
                current.isPlaying -> {
                    runCatching { current.pause() }
                    playing = false
                }
                completed -> {
                    runCatching {
                        current.seekTo(0)
                        current.start()
                    }
                    completed = false
                    playing = true
                    activeAudioPlayer.value = current
                }
                else -> {
                    runCatching { current.start() }
                    playing = true
                    activeAudioPlayer.value = current
                }
            }
            return
        }
        if (preparing) return
        preparing = true
        failed = false
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    prepareMediaPlayer(context, mp, source)
                    mp
                }.getOrNull()
            }
            preparing = false
            if (created == null) {
                failed = true
                return@launch
            }
            created.setOnCompletionListener {
                playing = false
                completed = true
                positionMs = durationMs
            }
            player = created
            prepared = true
            durationMs = runCatching { created.duration.toLong() }.getOrDefault(0L)
            activeAudioPlayer.value = created
            runCatching { created.start() }
            playing = true
        }
    }

    LaunchedEffect(playing, player) {
        while (playing) {
            player?.let { mp ->
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(positionMs)
            }
            delay(200)
        }
    }

    // 其它音频开始播放时暂停自己
    val active = activeAudioPlayer.value
    LaunchedEffect(active, player) {
        val current = player
        if (current != null && active !== current && playing) {
            runCatching { current.pause() }
            playing = false
        }
    }

    DisposableEffect(source) {
        onDispose { releasePlayer() }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { toggle() }, modifier = Modifier.size(36.dp)) {
                when {
                    preparing -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    failed -> Icon(Icons.Filled.Refresh, contentDescription = "重试")
                    playing -> Icon(Icons.Filled.Pause, contentDescription = "暂停")
                    completed -> Icon(Icons.Filled.Replay, contentDescription = "重播")
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                )
                Spacer(Modifier.height(4.dp))
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${formatAudioTime(positionMs)} / " +
                        if (durationMs > 0) formatAudioTime(durationMs) else "--:--",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

private fun prepareMediaPlayer(context: Context, player: MediaPlayer, source: String) {
    val raw = source.trim()
    when {
        raw.startsWith("data:", ignoreCase = true) -> {
            val bytes = decodeAudioDataUri(raw) ?: error("无效的音频数据")
            val file = File(context.cacheDir, "audio_${md5(raw)}.bin")
            if (!file.isFile || file.length() == 0L) file.writeBytes(bytes)
            player.setDataSource(file.absolutePath)
        }
        raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) -> player.setDataSource(raw)
        raw.startsWith("content://", ignoreCase = true) ->
            player.setDataSource(context, Uri.parse(raw))
        else -> {
            val file = resolveLocalFile(context, raw) ?: error("找不到音频文件")
            player.setDataSource(file.absolutePath)
        }
    }
    player.prepare()
}

private fun decodeAudioDataUri(raw: String): ByteArray? {
    val marker = raw.indexOf("base64,")
    if (marker < 0) return null
    return runCatching {
        Base64.decode(raw.substring(marker + "base64,".length), Base64.DEFAULT)
    }.getOrNull()
}

private fun resolveLocalFile(context: Context, raw: String): File? {
    var path = raw
    if (path.startsWith("file://", ignoreCase = true)) {
        path = runCatching { Uri.parse(path).path }.getOrNull() ?: path.substring(7)
    }
    if (path.startsWith("content://", ignoreCase = true)) return null
    path = Uri.decode(path)
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null

    val candidates = mutableListOf<File>()
    val direct = File(trimmed)
    if (direct.isAbsolute) candidates.add(direct)
    candidates.add(File(context.filesDir, trimmed))
    candidates.add(File(File(context.filesDir, "agent_files"), trimmed))

    val marker = "/files/"
    val idx = trimmed.indexOf(marker)
    if (idx >= 0) {
        candidates.add(File(context.filesDir, trimmed.substring(idx + marker.length)))
    }

    val base = trimmed.substringAfterLast('/')
    if (base.isNotBlank()) {
        candidates.add(File(context.filesDir, base))
        candidates.add(File(File(context.filesDir, "agent_files"), base))
    }
    return candidates.firstOrNull { it.isFile }
}

private fun md5(value: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun formatAudioTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

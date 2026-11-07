package mahjong.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import mahjong.app.PlatformContext
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors

private const val TAG = "MJSound"
private const val MAX_EFFECT_STREAMS = 8
private const val MAX_PENDING_VOICE = 2
private const val VOICE_GAP_MS = 55L
private const val FALLBACK_VOICE_MS = 420
private const val VOICE_SET_COUNT = 4

private val ACTION_VOICES = listOf("chi", "peng", "gang", "hu", "zimo", "liuju")

private val EFFECT_CLIPS = buildList {
    add("shuffle")
    add("deal")
    add("tile_pick")
    add("tile_draw")
    // 5 套真实落牌声，由 assets/audio/tools/generate_real_sfx.py 生成。
    for (i in 1..5) add("tile_discard_$i")
    add("tile_sort")
    for (i in 1..6) add("sfx_discard_$i")
    add("chi")
    add("peng")
    add("gang")
    add("hu")
    add("hu_multi")
    add("sfx_liuju")
    add("win_sting")
    add("score_tick")
    add("button_tap")
    add("invalid")
    add("countdown")
    // 旧版敲桌/动作资源仍保留在包内，避免历史版本缓存缺失时无声。
    add("sfx_start")
    add("sfx_draw")
    add("sfx_chi")
    add("sfx_peng")
    add("sfx_gang")
    add("sfx_hu")
    add("sfx_settle")
}

private val VOICE_CLIPS = buildList {
    // 4 套人声 × 34 种牌名 + 4 套动作语音，命名与 tools/sfx/generate_audio.py 一致。
    for (set in 1..VOICE_SET_COUNT) {
        for (i in 0..33) add("voice${set}_tile_" + i.toString().padStart(2, '0'))
        for (action in ACTION_VOICES) add("voice${set}_$action")
    }
}

private data class VoiceItem(
    val name: String,
    val soundId: Int,
    val volume: Float,
    val durationMs: Long,
    val delayMs: Long,
)

private object AndroidAudio {
    private val initLock = Any()
    private var effectPool: SoundPool? = null
    private var voicePool: SoundPool? = null
    private val effectIds = HashMap<String, Int>()
    private val voiceIds = HashMap<String, Int>()
    private val voiceDurations = HashMap<String, Int>()

    private val voiceLock = Any()
    private val voiceQueue = ArrayDeque<VoiceItem>()
    private var voiceWorkerRunning = false
    @Volatile
    private var currentVoiceStream = 0
    private val voiceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mj-voice")
    }

    fun init() {
        synchronized(initLock) {
            if (effectPool != null) return
            val context = runCatching { PlatformContext.app }.getOrNull() ?: return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val effects = SoundPool.Builder()
                .setMaxStreams(MAX_EFFECT_STREAMS)
                .setAudioAttributes(attributes)
                .build()
            val voices = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attributes)
                .build()

            effects.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "音效加载失败 sample=$sampleId status=$status")
            }
            voices.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "报牌语音加载失败 sample=$sampleId status=$status")
            }
            EFFECT_CLIPS.forEach { name ->
                val id = rawId(context, name)
                if (id == 0) {
                    Log.w(TAG, "缺少音效资源 $name")
                    return@forEach
                }
                val sample = effects.load(context, id, 1)
                if (sample == 0) Log.w(TAG, "音效加载返回 0：$name") else effectIds[name] = sample
            }
            VOICE_CLIPS.forEach { name ->
                val id = rawId(context, name)
                if (id == 0) {
                    Log.w(TAG, "缺少报牌资源 $name")
                    return@forEach
                }
                val sample = voices.load(context, id, 1)
                if (sample == 0) {
                    Log.w(TAG, "报牌语音加载返回 0：$name")
                } else {
                    voiceIds[name] = sample
                    voiceDurations[name] = wavDurationMs(context, id)
                }
            }

            effectPool = effects
            voicePool = voices
            val longest = voiceDurations.values.maxOrNull() ?: 0
            Log.i(
                TAG,
                "音频就绪：敲桌/动作 ${effectIds.size} 条，报牌语音 ${voiceIds.size} 条" +
                    "（${VOICE_SET_COUNT} 套人声，最长 ${longest}ms）",
            )
        }
    }

    fun playEffect(clip: String, volume: Float) {
        val pool = effectPool ?: return
        val id = effectIds[clip] ?: return
        val v = volume.coerceIn(0f, 1f)
        val stream = try {
            pool.play(id, v, v, 1, 0, 1f)
        } catch (error: Throwable) {
            Log.w(TAG, "音效播放异常 $clip", error)
            0
        }
        if (stream != 0) Log.d(TAG, "音效 $clip") else Log.w(TAG, "音效未就绪 $clip id=$id")
    }

    fun playVoice(clip: String, volume: Float, delayMs: Long, urgent: Boolean) {
        val pool = voicePool ?: return
        val id = voiceIds[clip] ?: return
        val duration = voiceDurations[clip] ?: FALLBACK_VOICE_MS
        val item = VoiceItem(
            name = clip,
            soundId = id,
            volume = volume.coerceIn(0f, 1f),
            durationMs = duration.toLong(),
            delayMs = delayMs.coerceIn(0L, 300L),
        )

        val startWorker: Boolean
        synchronized(voiceLock) {
            if (urgent) {
                if (voiceQueue.isNotEmpty()) {
                    Log.d(TAG, "紧急报牌 ${clip}，丢弃待播 ${voiceQueue.size} 条")
                    voiceQueue.clear()
                }
                voiceQueue.addFirst(item)
            } else {
                voiceQueue.addLast(item)
                while (voiceQueue.size > MAX_PENDING_VOICE) {
                    val dropped = voiceQueue.removeFirst()
                    Log.d(TAG, "报牌队列已满，丢弃较早的 ${dropped.name}")
                }
            }
            startWorker = !voiceWorkerRunning
            if (startWorker) voiceWorkerRunning = true
        }

        if (urgent) {
            val stream = currentVoiceStream
            if (stream != 0) {
                currentVoiceStream = 0
                runCatching { pool.stop(stream) }
            }
        }
        if (startWorker) {
            voiceExecutor.execute { voiceLoop(pool) }
        }
    }

    private fun voiceLoop(pool: SoundPool) {
        while (true) {
            val item = synchronized(voiceLock) {
                if (voiceQueue.isEmpty()) {
                    voiceWorkerRunning = false
                    return
                }
                voiceQueue.removeFirst()
            }
            if (item.delayMs > 0) {
                runCatching { Thread.sleep(item.delayMs) }
            }
            val stream = runCatching {
                pool.play(item.soundId, item.volume, item.volume, 1, 0, 1f)
            }.getOrDefault(0)
            currentVoiceStream = stream
            if (stream != 0) {
                Log.d(TAG, "报牌 ${item.name}（${item.durationMs}ms，待播 ${queueSize()}）")
            }
            // 单通道串行播放：等当前这条念完再放下一条，避免报牌被截断或糊在一起。
            runCatching { Thread.sleep(item.durationMs + VOICE_GAP_MS) }
        }
    }

    private fun queueSize(): Int = synchronized(voiceLock) { voiceQueue.size }

    private fun rawId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "raw", context.packageName)

    /** 从 WAV 头读出真实时长，用来控制报牌队列的节奏（无需额外生成时长表）。 */
    private fun wavDurationMs(context: Context, resId: Int): Int = runCatching {
        context.resources.openRawResource(resId).use { input ->
            val header = ByteArray(12)
            if (!readFully(input, header)) return@use FALLBACK_VOICE_MS
            if (header.decodeToString(0, 4) != "RIFF" || header.decodeToString(8, 12) != "WAVE") {
                return@use FALLBACK_VOICE_MS
            }
            var byteRate = 0
            var dataSize = 0
            val chunk = ByteArray(8)
            while (readFully(input, chunk)) {
                val id = chunk.decodeToString(0, 4)
                val size = leInt(chunk, 4)
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(minOf(size, 16))
                        if (!readFully(input, fmt)) return@use FALLBACK_VOICE_MS
                        byteRate = leInt(fmt, 8)
                        if (size > fmt.size) input.skip((size - fmt.size).toLong())
                    }
                    "data" -> {
                        dataSize = size
                        break
                    }
                    else -> input.skip(size.toLong())
                }
            }
            if (byteRate > 0 && dataSize > 0) {
                (dataSize.toLong() * 1000L / byteRate).toInt()
            } else {
                FALLBACK_VOICE_MS
            }
        }
    }.getOrDefault(FALLBACK_VOICE_MS)

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

actual fun platformInitAudio() {
    AndroidAudio.init()
}

actual fun platformPlaySound(clip: String, volume: Float) {
    AndroidAudio.playEffect(clip, volume)
}

actual fun platformPlayVoice(clip: String, volume: Float, delayMs: Long, urgent: Boolean) {
    AndroidAudio.playVoice(clip, volume, delayMs, urgent)
}

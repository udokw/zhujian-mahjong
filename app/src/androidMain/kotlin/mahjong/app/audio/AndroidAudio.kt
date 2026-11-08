package mahjong.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import mahjong.app.PlatformContext
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "MJSound"
private const val MAX_EFFECT_STREAMS = 8
// 保留自然语速后单条明显变长（约 0.5~0.8s），积压窗口放宽，避免连报时丢掉前一条。
private const val MAX_PENDING_VOICE = 6
private const val MAX_TRACKED_EFFECTS = 24
private const val VOICE_GAP_MS = 40L
private const val FALLBACK_VOICE_MS = 650

private val ACTION_VOICES = listOf("chi", "peng", "gang", "hu", "zimo", "liuju")

private val EFFECT_CLIPS = buildList {
    add("shuffle")
    add("deal")
    add("tile_pick")
    add("tile_draw")
    // 5 套真实落牌声，由 assets/audio/tools/generate_real_sfx.py 生成。
    for (i in 1..5) add("tile_discard_$i")
    add("tile_sort")
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
}

private val VOICE_CLIPS = buildList {
    // 9 套人声 × 34 种牌名 + 9 套动作语音，命名与 tools/sfx/voice_bank_cosyvoice3.py 一致。
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
    @Volatile
    private var initStarted = false
    @Volatile
    private var effectPool: SoundPool? = null
    @Volatile
    private var voicePool: SoundPool? = null
    private val effectIds = ConcurrentHashMap<String, Int>()
    private val voiceIds = ConcurrentHashMap<String, Int>()
    private val voiceDurations = ConcurrentHashMap<String, Int>()

    private val voiceLock = Any()
    private val voiceQueue = ArrayDeque<VoiceItem>()
    /** 最近播放的音效 streamId，静音时逐个 stop，避免开关关了还有尾音。 */
    private val recentEffectStreams = ArrayDeque<Int>()
    private var voiceWorkerRunning = false
    @Volatile
    private var currentVoiceStream = 0
    private val voiceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mj-voice")
    }

    fun init() {
        synchronized(initLock) {
            if (initStarted) return
            initStarted = true
        }
        // 人声按 9 套 360 条命名探测；资源缺失时只探测一次并静默，不阻塞启动。
        Thread({ loadAll() }, "mj-audio-init").apply { isDaemon = true }.start()
    }

    private fun loadAll() {
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
        // 先发布压力小的音效池：敲桌/洗牌/发牌在开局就要用，不能等 640 条人声加载完。
        effectPool = effects
        voicePool = voices
        EFFECT_CLIPS.forEach { name ->
            val id = rawId(context, name)
            if (id == 0) {
                Log.w(TAG, "缺少音效资源 $name")
                return@forEach
            }
            val sample = effects.load(context, id, 1)
            if (sample == 0) Log.w(TAG, "音效加载返回 0：$name") else effectIds[name] = sample
        }
        // 人声资源可能被整体清空：先探测是否存在，全部缺失时只记一条日志，避免逐条刷屏。
        val presentVoices = VOICE_CLIPS.filter { rawId(context, it) != 0 }
        if (presentVoices.isEmpty()) {
            Log.i(TAG, "报牌语音资源已清空（0 条），报牌与动作配音将静默；音效不受影响")
        } else {
            presentVoices.forEach { name ->
                val id = rawId(context, name)
                val sample = voices.load(context, id, 1)
                if (sample == 0) {
                    Log.w(TAG, "报牌语音加载返回 0：$name")
                } else {
                    voiceIds[name] = sample
                    voiceDurations[name] = wavDurationMs(context, id)
                }
            }
            val missing = VOICE_CLIPS.size - presentVoices.size
            if (missing > 0) {
                Log.i(TAG, "报牌语音缺少 $missing 条，按已存在的 ${presentVoices.size} 条加载")
            }
        }

        val longest = voiceDurations.values.maxOrNull() ?: 0
        if (voiceIds.isEmpty()) {
            Log.i(TAG, "音频就绪：敲桌/动作 ${effectIds.size} 条，报牌语音已清空（0 条），报牌静默")
        } else {
            Log.i(
                TAG,
                "音频就绪：敲桌/动作 ${effectIds.size} 条，报牌语音 ${voiceIds.size} 条" +
                    "（最长 ${longest}ms）",
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
        if (stream != 0) {
            rememberEffectStream(stream)
            Log.d(TAG, "音效 $clip")
        } else {
            Log.w(TAG, "音效未就绪 $clip id=$id")
        }
    }

    fun stopAll() {
        val streams: List<Int>
        val voice: Int
        synchronized(voiceLock) {
            voiceQueue.clear()
            streams = recentEffectStreams.toList()
            recentEffectStreams.clear()
            voice = currentVoiceStream
            currentVoiceStream = 0
        }
        streams.forEach { id -> runCatching { effectPool?.stop(id) } }
        if (voice != 0) runCatching { voicePool?.stop(voice) }
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

    private fun rememberEffectStream(stream: Int) {
        synchronized(voiceLock) {
            recentEffectStreams.addLast(stream)
            while (recentEffectStreams.size > MAX_TRACKED_EFFECTS) {
                recentEffectStreams.removeFirst()
            }
        }
    }

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

actual fun platformStopAllSounds() {
    AndroidAudio.stopAll()
}

package mahjong.app.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSLock
import platform.Foundation.NSURL

/**
 * iOS 端音频实现，行为与 Android 对齐：
 * - 效果音多通道：每次播放新建 AVAudioPlayer，保留强引用直到播完；
 * - 报牌语音单通道串行：独立后台线程 + 队列，最多积压 2 条，超出丢弃较早的；
 * - 胡牌紧急插播：清空待播并停止当前语音；
 * - 敲桌立即响、报牌延迟 60ms 由公共层 [GameAudio] 传入。
 *
 * 资源通过 Xcode 工程里的 `raw` 文件夹引用随 App bundle 打包，
 * 运行时用 NSBundle 的 pathForResource(..., inDirectory = "raw") 查找。
 */
private const val MAX_PENDING_VOICE = 6
private const val VOICE_GAP_MS = 40L
private const val MAX_ACTIVE_EFFECTS = 12

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object IosAudio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = NSLock()
    private var initialized = false

    private val activeEffects = mutableListOf<AVAudioPlayer>()
    private val urlCache = HashMap<String, NSURL>()

    private val voiceQueue = ArrayDeque<VoiceItem>()
    private var voiceWorkerRunning = false
    private var currentVoice: AVAudioPlayer? = null

    private data class VoiceItem(
        val name: String,
        val url: NSURL,
        val volume: Float,
        val delayMs: Long,
    )

    fun init() {
        lock.lock()
        if (initialized) {
            lock.unlock()
            return
        }
        initialized = true
        lock.unlock()

        // 用 playback 分类：即使手机拨到静音也有声音，App 内再用「音效」开关控制。
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            // setActive 在 Kotlin/Native 里是 AVAudioSession 的分类扩展，必须单独 import；
            // 且只有 setActive(_:withOptions:error:) 这个重载被导出。
            session.setActive(true, withOptions = 0uL, error = null)
        }
    }

    fun playEffect(clip: String, volume: Float) {
        val url = urlFor(clip) ?: return
        val player = createPlayer(url) ?: return
        player.volume = volume.coerceIn(0f, 1f)
        player.prepareToPlay()
        if (!player.play()) return

        // AVAudioPlayer 被释放会立刻断音，播放期间必须持有强引用。
        lock.lock()
        activeEffects.removeAll { !it.playing }
        activeEffects.add(player)
        while (activeEffects.size > MAX_ACTIVE_EFFECTS) {
            activeEffects.removeAt(0)
        }
        lock.unlock()
    }

    fun playVoice(clip: String, volume: Float, delayMs: Long, urgent: Boolean) {
        val url = urlFor(clip) ?: return
        val item = VoiceItem(clip, url, volume.coerceIn(0f, 1f), delayMs.coerceIn(0L, 300L))

        var startWorker = false
        lock.lock()
        if (urgent) {
            if (voiceQueue.isNotEmpty()) voiceQueue.clear()
            voiceQueue.addFirst(item)
        } else {
            voiceQueue.addLast(item)
            while (voiceQueue.size > MAX_PENDING_VOICE) {
                voiceQueue.removeFirst()
            }
        }
        if (!voiceWorkerRunning) {
            voiceWorkerRunning = true
            startWorker = true
        }
        lock.unlock()

        if (urgent) {
            lock.lock()
            val current = currentVoice
            currentVoice = null
            lock.unlock()
            runCatching { current?.stop() }
        }
        if (startWorker) {
            scope.launch { voiceLoop() }
        }
    }

    private suspend fun voiceLoop() {
        while (true) {
            lock.lock()
            if (voiceQueue.isEmpty()) {
                voiceWorkerRunning = false
                lock.unlock()
                return
            }
            val item = voiceQueue.removeFirst()
            lock.unlock()

            if (item.delayMs > 0) {
                delay(item.delayMs)
            }
            val player = createPlayer(item.url)
            if (player == null) continue
            player.volume = item.volume
            player.prepareToPlay()
            if (!player.play()) continue

            lock.lock()
            currentVoice = player
            lock.unlock()

            // 单通道串行：等当前这条念完再放下一条，避免报牌被截断或糊在一起。
            val durationMs = if (player.duration > 0.0) (player.duration * 1000).toLong() else 400L
            delay(durationMs + VOICE_GAP_MS)
            runCatching { player.stop() }

            lock.lock()
            if (currentVoice === player) currentVoice = null
            lock.unlock()
        }
    }

    /** 关闭音效或静音时清空待播队列并停掉正在播的声音。 */
    fun stopAll() {
        lock.lock()
        voiceQueue.clear()
        val voice = currentVoice
        currentVoice = null
        val effects = activeEffects.toList()
        activeEffects.clear()
        lock.unlock()
        runCatching { voice?.stop() }
        effects.forEach { runCatching { it.stop() } }
    }

    private fun createPlayer(url: NSURL): AVAudioPlayer? =
        runCatching { AVAudioPlayer(contentsOfURL = url, error = null) }.getOrNull()

    private fun urlFor(clip: String): NSURL? {
        lock.lock()
        val cached = urlCache[clip]
        lock.unlock()
        if (cached != null) return cached

        val path = NSBundle.mainBundle.pathForResource(
            clip,
            ofType = "wav",
            inDirectory = "raw",
        ) ?: return null
        val url = NSURL.fileURLWithPath(path)
        lock.lock()
        urlCache[clip] = url
        lock.unlock()
        return url
    }
}

actual fun platformInitAudio() {
    IosAudio.init()
}

actual fun platformPlaySound(clip: String, volume: Float) {
    IosAudio.playEffect(clip, volume)
}

actual fun platformPlayVoice(clip: String, volume: Float, delayMs: Long, urgent: Boolean) {
    IosAudio.playVoice(clip, volume, delayMs, urgent)
}

actual fun platformStopAllSounds() {
    IosAudio.stopAll()
}

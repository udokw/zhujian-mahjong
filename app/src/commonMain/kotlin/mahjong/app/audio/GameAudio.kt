package mahjong.app.audio

import mahjong.app.util.Clock

/**
 * 牌局音效与报牌语音的统一入口。
 *
 * 资源全部是本地 WAV：敲桌/动作音效由 tools/sfx/generate_audio.py 程序化合成，
 * 牌名语音在生成阶段用中文神经网络 TTS 合成后打包，运行期完全离线。
 *
 * 音量策略：敲桌声约 0.6 倍主音量，报牌语音 1.0 倍主音量，语音晚 60ms 起播，
 * 保证“先磕桌、后念牌”且报牌不被敲桌声盖住。
 *
 * 报牌人声按座位绑定：0/1/2/3 号座位固定对应 voice1..voice4（两女两男），
 * 这样四家出牌能靠声音分辨是谁，而不是同一个人重复念。
 */
enum class SoundEffect(
    internal val clip: String,
    internal val gain: Float,
    internal val throttleMs: Long,
) {
    /** 洗牌：进入对局只播一次，不循环。 */
    SHUFFLE("shuffle", 0.80f, 1_200L),
    /** 发牌：单张，连发由调用方按 40–60ms 间距触发。 */
    DEAL("deal", 0.50f, 30L),
    /** 选牌 / 抬起手牌。 */
    PICK("tile_pick", 0.55f, 25L),
    DRAW("tile_draw", 0.60f, 40L),
    DISCARD("tile_discard", 0.70f, 30L),
    CHI("chi", 0.82f, 120L),
    PENG("peng", 0.92f, 120L),
    GANG("gang", 0.95f, 120L),
    HU("hu", 0.92f, 250L),
    /** 一炮多响 / 连续胡，替代 [HU]。 */
    HU_MULTI("hu_multi", 0.95f, 400L),
    DRAW_GAME("sfx_liuju", 0.82f, 250L),
    /** 结算页短乐句。 */
    WIN_STING("win_sting", 0.90f, 1_500L),
    /** 分数滚动颗粒音，连续触发，音量已压得很低。 */
    SCORE_TICK("score_tick", 0.40f, 26L),
    BUTTON("button_tap", 0.50f, 40L),
    INVALID("invalid", 0.72f, 250L),
    COUNTDOWN("countdown", 0.65f, 800L),
}

enum class VoiceLine(
    internal val clip: String,
    internal val gain: Float,
    internal val urgent: Boolean = false,
) {
    CHI("chi", 0.95f),
    PENG("peng", 0.95f),
    GANG("gang", 0.95f),
    HU("hu", 1.0f),
    ZIMO("zimo", 1.0f, urgent = true),
    LIUJU("liuju", 0.95f),
}

expect fun platformInitAudio()

expect fun platformPlaySound(clip: String, volume: Float)

/** 报牌/动作语音；[delayMs] 用于让敲桌声先落，[urgent] 会清空待播队列优先播报。 */
expect fun platformPlayVoice(
    clip: String,
    volume: Float,
    delayMs: Long = 0L,
    urgent: Boolean = false,
)

object GameAudio {
    /** 报牌语音比敲桌声晚 60ms，形成“敲桌 → 念牌”的两层声音。 */
    private const val TILE_VOICE_DELAY_MS = 60L

    /** 人声套数，与 tools/sfx/generate_audio.py 的 VOICE_SETS 保持一致。 */
    private const val VOICE_SET_COUNT = 4

    private val lastPlayed = HashMap<String, Long>()
    private val lastVoiced = HashMap<String, Long>()

    /** 全局音效开关与主音量，由设置页同步进来，供 UI 层无参调用。 */
    private var soundEnabled = true
    private var masterVolume = 0.7f

    /** 座位 0..3 固定映射到 voice1..voice4，负座位（如流局）回落到 1 号音色。 */
    internal fun voiceSetForSeat(seat: Int): Int =
        ((seat % VOICE_SET_COUNT) + VOICE_SET_COUNT) % VOICE_SET_COUNT + 1

    fun configure(sound: Boolean, volume: Float) {
        soundEnabled = sound
        masterVolume = volume.coerceIn(0f, 1f)
    }

    /** UI 层无参播放：按钮、选牌、倒计时、分数滚动都在这里统一过开关。 */
    fun playUi(effect: SoundEffect) {
        if (!soundEnabled) return
        playEffect(effect, masterVolume)
    }

    fun playButton() = playUi(SoundEffect.BUTTON)
    fun playPick() = playUi(SoundEffect.PICK)
    fun playInvalid() = playUi(SoundEffect.INVALID)
    fun playCountdown() = playUi(SoundEffect.COUNTDOWN)
    fun playScoreTick() = playUi(SoundEffect.SCORE_TICK)

    fun playEffect(effect: SoundEffect, volume: Float) {
        if (volume <= 0f) return
        val now = Clock.now()
        val key = effect.name
        val last = lastPlayed[key]
        if (last != null && now - last < effect.throttleMs) return
        lastPlayed[key] = now
        platformPlaySound(effect.clip, volume * effect.gain)
    }

    /** 出牌：敲桌声立即响，牌名用该座位的音色紧随其后念出。 */
    fun playDiscard(tile: Int, volume: Float, speakTile: Boolean, seat: Int = 0) {
        playEffect(SoundEffect.DISCARD, volume)
        if (speakTile) announceTile(tile, volume, seat)
    }

    /** 报出 0..33 的牌名（万/筒/条/东南西北/中发白），花牌不报。 */
    fun announceTile(tile: Int, volume: Float, seat: Int) {
        if (volume <= 0f || tile !in 0..33) return
        val index = tile.toString().padStart(2, '0')
        platformPlayVoice("voice${voiceSetForSeat(seat)}_tile_$index", volume, TILE_VOICE_DELAY_MS)
    }

    fun announce(line: VoiceLine, volume: Float, seat: Int = 0) {
        if (volume <= 0f) return
        // 一炮多响等场景会连续收到多次胡牌事件，同一句只播一次；
        // 吃碰杠按座位去重，避免误吞别家刚喊过的同一句。
        val dedupeKey = when {
            line.urgent || line == VoiceLine.HU || line == VoiceLine.LIUJU -> line.clip
            else -> "${line.clip}_$seat"
        }
        val now = Clock.now()
        val last = lastVoiced[dedupeKey]
        if (last != null && now - last < 320L) return
        lastVoiced[dedupeKey] = now
        val clip = "voice${voiceSetForSeat(seat)}_${line.clip}"
        platformPlayVoice(clip, volume * line.gain, urgent = line.urgent)
    }
}

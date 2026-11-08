package mahjong.app.audio

import mahjong.app.util.Clock
import kotlin.random.Random

/**
 * 牌局音效与报牌语音的统一入口。
 *
 * 敲桌/洗牌/摸牌/理牌等音效由 assets/audio/tools/generate_real_sfx.py
 * 从可免费使用的真实麻将录音中切片与整形生成；牌名语音在生成阶段用中文
 * 神经网络 TTS 合成后打包，运行期完全离线。
 *
 * 音量策略：敲桌声约 0.7 倍主音量，报牌语音 1.0 倍主音量，语音晚 60ms 起播，
 * 保证“先磕桌、后念牌”且报牌不被敲桌声盖住。
 *
 * 报牌人声按玩家绑定：每局开始时给四家随机分配互不相同的一整套音色
 * （见 [beginGame]），整局固定不变，AI 与真人同一规则；重开一局重新随机。
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
    /** 出牌落桌：tile_discard_1..5 随机，见 [GameAudio.playEffect]。 */
    DISCARD("tile_discard", 0.70f, 30L),
    /** 理牌：手里挪牌、牌与牌轻碰的细碎声，开局一次。 */
    SORT("tile_sort", 0.55f, 600L),
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

/** 立即静音：清空待播队列并停掉正在播的声音，用于关闭音效或把音量拉到 0。 */
expect fun platformStopAllSounds()

object GameAudio {
    /** 报牌语音比敲桌声晚 60ms，形成“敲桌 → 念牌”的两层声音。 */
    private const val TILE_VOICE_DELAY_MS = 60L

    /** 人声套数，与 tools/sfx/generate_voice_voxcpm.py 的 DIALECTS 保持一致（9 种方言）。 */
    internal const val VOICE_SET_COUNT = 9

    private val lastPlayed = HashMap<String, Long>()
    private val lastVoiced = HashMap<String, Long>()

    /** 每局开始时为四家分到的整套音色，下标为座位，值为 1..VOICE_SET_COUNT。 */
    private val seatVoiceSets = IntArray(4) { it + 1 }

    /** 5 套真实落牌声，随机轮换且不与上一张重复。 */
    private val discardClips = (1..5).map { "tile_discard_$it" }
    private var lastDiscardIndex = -1

    /** 全局音效开关与主音量，由设置页同步进来，供 UI 层无参调用。 */
    private var soundEnabled = true
    private var masterVolume = 0.7f

    /**
     * 一局开始时给四家分配音色：把全部套数洗牌后按座位依次取，
     * 保证同一局内四家互不撞音色（套数 9 > 座位 4），AI 与真人同一规则。
     * 重开一局会重新洗牌，因此每局听感都可能换人。
     */
    fun beginGame() {
        val order = (1..VOICE_SET_COUNT).shuffled()
        for (seat in seatVoiceSets.indices) {
            seatVoiceSets[seat] = order[seat]
        }
    }

    /** 取某座位本局分到的音色编号；越界座位回落到 0 号座位。 */
    internal fun voiceSetForSeat(seat: Int): Int {
        val index = if (seat in seatVoiceSets.indices) seat else 0
        return seatVoiceSets[index]
    }

    fun configure(sound: Boolean, volume: Float) {
        soundEnabled = sound
        masterVolume = volume.coerceIn(0f, 1f)
        // 关掉音效或静音时立即掐断，避免已排队的报牌继续念出来。
        if (!sound || masterVolume <= 0f) platformStopAllSounds()
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
        val clip = if (effect == SoundEffect.DISCARD) nextDiscardClip() else effect.clip
        platformPlaySound(clip, volume * effect.gain)
    }

    private fun nextDiscardClip(): String {
        var index = Random.nextInt(discardClips.size)
        if (index == lastDiscardIndex) index = (index + 1) % discardClips.size
        lastDiscardIndex = index
        return discardClips[index]
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

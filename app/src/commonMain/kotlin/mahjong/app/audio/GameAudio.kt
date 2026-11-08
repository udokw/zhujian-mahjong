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
 * 报牌人声按座位绑定：每局开始时给四家分配互不相同的一整套音色（见 [beginGame]），
 * 整局固定不变。己方优先用设置里选定的音色；房主（或单机）可为下家/对家/上家
 * 分别指定音色，未指定则从剩余音色里随机。重开一局重新随机。
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

    private val lastPlayed = HashMap<String, Long>()
    private val lastVoiced = HashMap<String, Long>()

    /** 每局开始时为四家分到的整套音色，下标为座位，值为 1..VOICE_SET_COUNT。 */
    private val seatVoiceSets = IntArray(4) { it + 1 }

    /** 用户在设置里选定的己方音色；0 表示自动随机。 */
    private var preferredVoiceSet = 0

    /** 房主为三家座位指定的音色，按“相对房主”的位置存：下标 1..3 = 下家/对家/上家，0 = 随机。 */
    private var seatVoicePrefs = List(4) { 0 }
    private var seatPrefsEnabled = true

    /** 本局己方座位号：单机固定 0，联机由服务端下发，可能不是 0。 */
    private var mySeatIndex = 0

    /** 5 套真实落牌声，随机轮换且不与上一张重复。 */
    private val discardClips = (1..5).map { "tile_discard_$it" }
    private var lastDiscardIndex = -1

    /** 全局音效开关与主音量，由设置页同步进来，供 UI 层无参调用。 */
    private var soundEnabled = true
    private var masterVolume = 0.7f

    /**
     * 一局开始时给四家分配音色：
     * - 己方（[mySeat]，单机固定 0）优先使用设置里选定的音色；未选定时随机；
     * - 其余三家：房主/单机模式下按 [seatVoicePrefs] 指定，未指定则随机；
     * - 四家尽量不撞音色（9 套里取 4 套），重开一局重新洗牌。
     *
     * @param mySeat 己方座位号（0..3），联机时以服务端下发为准。
     * @param hostSeat 房主座位号；相对座位以它为中心计算，单机默认与 [mySeat] 相同。
     */
    fun beginGame(mySeat: Int = 0, hostSeat: Int = mySeat) {
        mySeatIndex = mySeat.coerceIn(0, 3)
        val host = hostSeat.coerceIn(0, 3)
        val unused = (1..VOICE_SET_COUNT).toMutableList()
        val used = mutableSetOf<Int>()

        fun take(preferred: Int): Int {
            val pick = if (preferred in 1..VOICE_SET_COUNT && preferred !in used) {
                preferred
            } else {
                unused.firstOrNull { it !in used } ?: (1..VOICE_SET_COUNT).random()
            }
            used += pick
            unused.remove(pick)
            return pick
        }

        seatVoiceSets[mySeatIndex] = take(preferredVoiceSet)
        for (seat in 0..3) {
            if (seat == mySeatIndex) continue
            val relative = (seat - host + 4) % 4
            val preferred = if (seatPrefsEnabled && relative in 1..3) seatVoicePrefs[relative] else 0
            seatVoiceSets[seat] = take(preferred)
        }
        val summary = (0..3).joinToString("  ") { "seat$it=voice${seatVoiceSets[it]}" }
        println("[VoiceAssign] 本局音色 $summary（mySeat=$mySeatIndex）")
    }

    /** 取某座位本局分到的音色编号；越界座位回落到 0 号座位。 */
    internal fun voiceSetForSeat(seat: Int): Int {
        val index = if (seat in seatVoiceSets.indices) seat else 0
        return seatVoiceSets[index]
    }

    /**
     * 同步音效开关、主音量、己方音色与三家座位音色。
     * [seatPrefs] 按相对房主的位置给：下标 1..3 = 下家/对家/上家，0 表示随机。
     * 联机非房主时由调用方把 [seatPrefsEnabled] 设为 false，避免沿用自己当房主时的旧配置。
     */
    fun configure(
        sound: Boolean,
        volume: Float,
        preferredSet: Int = 0,
        seatPrefs: List<Int> = emptyList(),
        seatPrefsEnabled: Boolean = true,
    ) {
        soundEnabled = sound
        masterVolume = volume.coerceIn(0f, 1f)
        preferredVoiceSet = if (preferredSet in 1..VOICE_SET_COUNT) preferredSet else 0
        seatVoicePrefs = List(4) { index ->
            seatPrefs.getOrElse(index) { 0 }.takeIf { it in 1..VOICE_SET_COUNT } ?: 0
        }
        this.seatPrefsEnabled = seatPrefsEnabled
        // 选定音色后不必等下一局，己方座位立刻用新音色报牌。
        if (preferredVoiceSet != 0) seatVoiceSets[mySeatIndex] = preferredVoiceSet
        // 关掉音效或静音时立即掐断，避免已排队的报牌继续念出来。
        if (!sound || masterVolume <= 0f) platformStopAllSounds()
    }

    /** 设置页试听：立即播一条「五万」，并清掉待播队列避免和上一段试听叠在一起。 */
    fun previewVoiceSet(setId: Int) {
        if (setId !in 1..VOICE_SET_COUNT) return
        val volume = if (masterVolume > 0f) masterVolume else 0.7f
        platformPlayVoice("voice${setId}_tile_04", volume, urgent = true)
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

package mahjong.app.audio

/**
 * 报牌人声套数，与 tools/sfx/voice_bank_common.py 的 VOICES 保持一致：
 * voice1..voice9 为 9 套普通话发音人，音色覆盖男女与不同年龄，套内一致。
 */
const val VOICE_SET_COUNT = 9

/** 一套报牌人声的展示信息：语种、音色特征。 */
data class VoiceSetInfo(
    val id: Int,
    val dialect: String,
    val timbre: String,
)

val VoiceSets: List<VoiceSetInfo> = listOf(
    VoiceSetInfo(1, "普通话", "青年男性，清亮，语速中等"),
    VoiceSetInfo(2, "普通话", "青年女性，清脆，语速轻快"),
    VoiceSetInfo(3, "普通话", "中年男性，低沉浑厚，语速偏慢"),
    VoiceSetInfo(4, "普通话", "中年女性，温和圆润，语速平稳"),
    VoiceSetInfo(5, "普通话", "老年男性，沙哑低沉，语速偏慢"),
    VoiceSetInfo(6, "普通话", "老年女性，温和略沙哑，语速偏慢"),
    VoiceSetInfo(7, "普通话", "十岁男孩，童声清亮，语速偏快"),
    VoiceSetInfo(8, "普通话", "十岁女孩，童声甜美，语速轻快"),
    VoiceSetInfo(9, "普通话", "成年男性，播音腔，浑厚洪亮"),
)

fun voiceSetInfo(id: Int): VoiceSetInfo? = VoiceSets.firstOrNull { it.id == id }

/** 设置页与弹窗共用的显示名，例如「普通话 · 青年男性，清亮，语速中等」。 */
fun voiceSetLabel(id: Int): String {
    val info = voiceSetInfo(id) ?: return "随机"
    return "${info.dialect} · ${info.timbre}"
}

/** 三家座位的相对称呼：下标 1..3 对应下家、对家、上家。 */
val VoiceSeatNames = listOf("己方", "下家", "对家", "上家")

/** 座位音色显示的短名，例如「3 号 · 中年男性」。 */
fun voiceSeatLabel(id: Int): String {
    if (id == 0) return "随机"
    val info = voiceSetInfo(id) ?: return "随机"
    return "$id 号 · ${info.timbre.substringBefore('，')}"
}

package mahjong.app.lan

import kotlinx.serialization.Serializable

/**
 * 牌谱里的单条动作。seed + 该序列即可完整复现一局。
 */
@Serializable
data class RecordedAction(
    val turn: Int,
    val seat: Int,
    val action: String,
    val tile: Int? = null,
    val tiles: List<Int> = emptyList(),
    val claimed: Int? = null,
    val kind: String? = null,
)

package mahjong.core.model

enum class Phase {
    AWAIT_DISCARD,
    AWAIT_CLAIM,
    AWAIT_ROB_KONG,
    GAME_OVER,
}

data class DiscardInfo(
    val player: Int,
    val tile: Int,
    val turn: Int,
    val lastTileOfWall: Boolean,
)

/** 玩家可执行的动作。所有动作都带 player，便于 UI/AI 直接回传。 */
sealed interface Action {
    val player: Int

    data class Discard(override val player: Int, val tile: Int) : Action

    /** tiles 为手牌中要拿出的两张，claimed 为被吃的牌。 */
    data class Chi(override val player: Int, val tiles: List<Int>, val claimed: Int) : Action

    data class Peng(override val player: Int, val tile: Int) : Action

    /** 明杠别人刚打出的牌。 */
    data class ExposedKong(override val player: Int, val tile: Int) : Action

    /** 暗杠：手里 4 张。 */
    data class ConcealedKong(override val player: Int, val tile: Int) : Action

    /** 补杠/加杠：已有刻子，手里再摸到第 4 张。 */
    data class AddedKong(override val player: Int, val tile: Int) : Action

    data class Hu(override val player: Int) : Action

    data class Pass(override val player: Int) : Action
}

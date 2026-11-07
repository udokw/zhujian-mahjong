package mahjong.core.model

import mahjong.core.rules.RuleConfig
import mahjong.core.score.WinResult

data class PlayerState(
    val seat: Int,
    val hand: List<Int>,
    val melds: List<Meld>,
    val discards: List<Int>,
    val flowers: List<Int>,
    val score: Int,
    val seatWind: Int,
)

data class OpponentView(
    val seat: Int,
    val handCount: Int,
    val melds: List<Meld>,
    val discards: List<Int>,
    val flowerCount: Int,
    val score: Int,
    val seatWind: Int,
)

/**
 * 只包含某个座位可见信息的视图，AI 只能消费这个。
 */
data class PlayerView(
    val seat: Int,
    val hand: List<Int>,
    val melds: List<Meld>,
    val flowers: List<Int>,
    val opponents: List<OpponentView>,
    val phase: Phase,
    val currentPlayer: Int,
    val wallRemaining: Int,
    val lastDiscard: DiscardInfo?,
    val seatWind: Int,
    val roundWind: Int,
    val dealer: Int,
    val lastDrawnTile: Int?,
    val lastDrawWasKongReplacement: Boolean,
    val canSelfDrawWin: Boolean,
    val config: RuleConfig,
    /** 自己已打出的牌；公开信息，用于听口剩余张数与「和绝张」推算。 */
    val ownDiscards: List<Int> = emptyList(),
) {
    /** 自己已经确定的副露数。 */
    val meldCount: Int get() = melds.size

    /** 自己看到的全部牌（含手牌、副露、弃牌、花牌），用于牌效统计。 */
    fun visibleTiles(includeOwnHand: Boolean = true): List<Int> {
        val result = ArrayList<Int>(64)
        if (includeOwnHand) result.addAll(hand)
        result.addAll(melds.flatMap { it.tiles })
        result.addAll(flowers)
        for (o in opponents) {
            result.addAll(o.melds.flatMap { it.tiles })
            result.addAll(o.discards)
        }
        return result
    }
}

data class GameState(
    val config: RuleConfig,
    val phase: Phase,
    val currentPlayer: Int,
    val dealer: Int,
    val roundWind: Int,
    val players: List<PlayerState>,
    val wallRemaining: Int,
    val lastDiscard: DiscardInfo?,
    val turn: Int,
    val result: GameResult?,
    val dealerStreak: Int = 0,
    val lastDrawnTile: Int? = null,
    val lastDrawWasKongReplacement: Boolean = false,
    /** 当前可以自摸和牌的座位（由引擎计算，避免 UI/AI 重复判定）。 */
    val canSelfDrawWinSeats: Set<Int> = emptySet(),
    /** 等待响应的吃碰杠胡座位。 */
    val pendingClaimSeats: List<Int> = emptyList(),
    /** 等待响应的抢杠胡座位。 */
    val pendingRobKongSeats: List<Int> = emptyList(),
    val robbedKongPlayer: Int? = null,
    val robbedKongTile: Int? = null,
) {
    fun viewFor(seat: Int): PlayerView {
        val me = players[seat]
        return PlayerView(
            seat = seat,
            hand = me.hand,
            melds = me.melds,
            flowers = me.flowers,
            opponents = players.filter { it.seat != seat }.map {
                OpponentView(
                    seat = it.seat,
                    handCount = it.hand.size,
                    melds = it.melds,
                    discards = it.discards,
                    flowerCount = it.flowers.size,
                    score = it.score,
                    seatWind = it.seatWind,
                )
            },
            phase = phase,
            currentPlayer = currentPlayer,
            wallRemaining = wallRemaining,
            lastDiscard = lastDiscard,
            seatWind = me.seatWind,
            roundWind = roundWind,
            dealer = dealer,
            lastDrawnTile = lastDrawnTile,
            lastDrawWasKongReplacement = lastDrawWasKongReplacement,
            canSelfDrawWin = seat in canSelfDrawWinSeats,
            config = config,
            ownDiscards = me.discards,
        )
    }
}

data class GameResult(
    val draw: Boolean,
    val winners: List<WinResult>,
    val deltaScores: List<Int>,
    val dealer: Int,
    val roundWind: Int,
    val dealerContinues: Boolean,
    val reason: String,
    /** 买马结果，仅在广东买马开启时非空。 */
    val horses: List<HorseResult> = emptyList(),
)

data class HorseResult(
    val tile: Int,
    val hitsWinner: Boolean,
    val hitSeats: List<Int>,
    val description: String,
)

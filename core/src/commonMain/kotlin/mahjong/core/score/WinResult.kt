package mahjong.core.score

import mahjong.core.model.Meld
import mahjong.core.rules.RuleConfig

/**
 * 和牌时的上下文，必须由牌局状态机完整提供，番种判定才能正确。
 */
data class WinContext(
    val seat: Int,
    val selfDraw: Boolean,
    val winningTile: Int,
    val fromPlayer: Int?,
    val robKong: Boolean = false,
    val kongReplacement: Boolean = false,
    val lastTile: Boolean = false,
    val seatWind: Int,
    val roundWind: Int,
    val flowers: List<Int>,
    val dealer: Int,
    /** 和牌前场上可见的牌（不含赢家手牌），用于和绝张。 */
    val visibleTiles: List<Int> = emptyList(),
)

data class FanItem(
    val id: String,
    val name: String,
    val fan: Int,
    val count: Int = 1,
) {
    val totalFan: Int get() = fan * count
    override fun toString(): String = if (count > 1) "$name×$count(${totalFan}番)" else "$name(${totalFan}番)"
}

data class WinResult(
    val winner: Int,
    val fromPlayer: Int?,
    val winningTile: Int,
    val selfDraw: Boolean,
    val robKong: Boolean,
    val fanItems: List<FanItem>,
    val totalFan: Int,
    /** 不含花牌的番数，用于国标 8 分起和判定。 */
    val thresholdFan: Int,
    /** 基本分（国标=总番，广东=2^番）。底分/包牌/买马在结算层处理。 */
    val points: Int,
    val handTiles: List<Int>,
    val melds: List<Meld>,
    val horseResults: List<HorseHit> = emptyList(),
) {
    val fanSummary: String get() = fanItems.joinToString(" + ") { it.toString() }
}

data class HorseHit(
    val tile: Int,
    val hitSeat: Int,
)

/**
 * 统一的计分入口。番数不足门槛返回 null。
 */
object ScoreEngine {
    fun score(
        config: RuleConfig,
        tiles: List<Int>,
        melds: List<Meld>,
        ctx: WinContext,
    ): WinResult? = when (config.mode) {
        mahjong.core.rules.GameMode.MCR -> McrScorer.score(tiles, melds, ctx, config)
        mahjong.core.rules.GameMode.GUANGDONG -> GuangdongScorer.score(tiles, melds, ctx, config)
    }

    fun pointsFor(config: RuleConfig, fan: Int): Int = when (config.mode) {
        mahjong.core.rules.GameMode.MCR -> fan.coerceAtLeast(0) * config.baseScore
        mahjong.core.rules.GameMode.GUANGDONG -> {
            // 传入的 fan 已经是封顶后的有效番；大牌型突破封顶时这里不能再压回封顶值。
            val effective = fan.coerceAtLeast(0)
            config.baseScore * (1 shl effective)
        }
    }

    /**
     * 广东封顶规则：普通番种累计不超过封顶；大牌型（如清一色、十三幺）可突破封顶，
     * 此时附加番不再叠加。
     */
    fun guangdongEffectiveFan(config: RuleConfig, totalFan: Int, maxPatternFan: Int): Int {
        val cap = config.maxFanCap
        return if (maxPatternFan > cap) maxPatternFan else minOf(totalFan, cap)
    }
}

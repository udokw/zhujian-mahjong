package mahjong.core.engine

import mahjong.core.model.GameResult
import mahjong.core.model.HorseResult
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.score.WinResult
import mahjong.core.tile.Tiles

/**
 * 结算层：只负责分数分配，不修改牌局状态。
 *
 * 国标：底分 8；自摸三家各付 (8+番)，点和放炮者付 (8+番)、另两家各付 8。
 * 广东：分值 = 2^番；自摸三家各付，点炮放炮者付；包牌由责任人付三家份额。
 * 杠分：放杠 3，明杠（加杠）每家 1，暗杠每家 2。
 */
object Settlement {

    fun settle(
        config: RuleConfig,
        winners: List<WinResult>,
        dealer: Int,
        roundWind: Int,
        dealerStreak: Int,
        gangDeltas: List<Int>,
        horses: List<Int>,
        baoPayers: Map<Int, Int>,
        draw: Boolean,
        reason: String,
    ): GameResult {
        val delta = gangDeltas.toMutableList()

        for (win in winners) {
            if (config.mode == GameMode.MCR) {
                settleMcr(config, win, delta)
            } else {
                settleGuangdong(config, win, baoPayers[win.winner], delta)
            }
        }

        val horseResults = if (!draw && config.buyHorseEnabled && horses.isNotEmpty()) {
            settleHorses(config, winners, dealer, horses, delta)
        } else {
            emptyList()
        }

        val winnerIsDealer = winners.any { it.winner == dealer }
        val dealerContinues = if (draw) {
            config.dealerContinuesOnDraw
        } else {
            winnerIsDealer && config.dealerContinuesOnWin
        }

        return GameResult(
            draw = draw,
            winners = winners,
            deltaScores = delta,
            dealer = dealer,
            roundWind = roundWind,
            dealerContinues = dealerContinues,
            reason = reason,
            horses = horseResults,
        )
    }

    private fun settleMcr(config: RuleConfig, win: WinResult, delta: MutableList<Int>) {
        val base = config.mcrBaseScore
        val fanPoints = win.points
        if (win.selfDraw) {
            for (s in 0..3) {
                if (s == win.winner) continue
                delta[s] -= base + fanPoints
                delta[win.winner] += base + fanPoints
            }
        } else {
            val from = win.fromPlayer
                ?: error("discard win without source seat: winner=${win.winner}")
            delta[from] -= base + fanPoints
            delta[win.winner] += base + fanPoints
            for (s in 0..3) {
                if (s == win.winner || s == from) continue
                delta[s] -= base
                delta[win.winner] += base
            }
        }
    }

    private fun settleGuangdong(
        config: RuleConfig,
        win: WinResult,
        baoPayer: Int?,
        delta: MutableList<Int>,
    ) {
        val points = win.points
        if (baoPayer != null) {
            // 包牌：责任人代付三家份额。
            delta[baoPayer] -= points * 3
            delta[win.winner] += points * 3
            return
        }
        if (win.selfDraw) {
            for (s in 0..3) {
                if (s == win.winner) continue
                delta[s] -= points
                delta[win.winner] += points
            }
        } else {
            val from = win.fromPlayer
                ?: error("discard win without source seat: winner=${win.winner}")
            delta[from] -= points
            delta[win.winner] += points
        }
    }

    /**
     * 买马：以庄家为起点，牌面数字 1..9 循环对应庄/下家/对家/上家；
     * 字牌按 东、南、西、北、中、发、白 从庄家起逆时针对应。
     * 庄家与该马对应的玩家按后者本局总输赢同额结算。
     */
    private fun settleHorses(
        config: RuleConfig,
        winners: List<WinResult>,
        dealer: Int,
        horses: List<Int>,
        delta: MutableList<Int>,
    ): List<HorseResult> {
        val winnerSeats = winners.map { it.winner }.toSet()
        val results = ArrayList<HorseResult>(horses.size)
        for (tile in horses) {
            val index = horseIndex(tile)
            check(index >= 0) { "illegal horse tile: $tile" }
            val hitSeat = (dealer + index) % 4
            val total = delta[hitSeat]
            delta[dealer] += total
            delta[hitSeat] -= total
            results.add(
                HorseResult(
                    tile = tile,
                    hitsWinner = hitSeat in winnerSeats,
                    hitSeats = listOf(hitSeat),
                    description = "马 ${Tiles.name(tile)} → ${seatName(hitSeat)}家(${total})",
                )
            )
        }
        return results
    }

    private fun horseIndex(tile: Int): Int = when (tile) {
        in 0..26 -> Tiles.rank(tile) - 1
        in 27..33 -> tile - 27
        else -> -1
    }

    private fun seatName(seat: Int): String = when (seat) {
        0 -> "东"
        1 -> "南"
        2 -> "西"
        else -> "北"
    }
}

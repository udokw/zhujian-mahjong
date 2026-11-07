package mahjong.core.engine

import mahjong.core.model.Action
import mahjong.core.model.GameResult
import mahjong.core.model.PlayerView
import mahjong.core.rules.RuleConfig

data class RoundRecord(
    val index: Int,
    val dealer: Int,
    val roundWind: Int,
    val dealerStreak: Int,
    val result: GameResult,
    val scoresAfter: List<Int>,
)

/**
 * 多局比赛：负责庄家轮转、圈风推进、连庄与累计分数。
 * 每局独立创建 [MahjongGame]，因此单局可复现，整场也可复现。
 */
class Match(
    val config: RuleConfig,
    private val seed: Long,
    val rounds: Int = 4,
    startDealer: Int = 0,
    startRoundWind: Int = 0,
    startingScores: List<Int>? = null,
) {
    val scores: MutableList<Int> =
        (startingScores ?: List(4) { config.startingScore }).toMutableList()

    var dealer: Int = startDealer
        private set
    var roundWind: Int = startRoundWind
        private set
    var dealerStreak: Int = 0
        private set

    private var rotationCount = 0
    private var roundIndex = 0

    fun playRound(decide: (PlayerView, List<Action>) -> Action): RoundRecord {
        val game = MahjongGame(
            config = config,
            seed = seed + roundIndex * 1000003L,
            dealer = dealer,
            roundWind = roundWind,
            dealerStreak = dealerStreak,
            initialScores = scores.toList(),
        )
        var steps = 0
        while (!game.isOver) {
            val seat = game.actor ?: break
            val legal = game.legalActions(seat)
            check(legal.isNotEmpty()) { "no legal actions for seat $seat in phase ${game.state.phase}" }
            val view = game.viewFor(seat)
            val action = decide(view, legal)
            game.apply(action)
            steps++
            check(steps < 20000) { "round did not terminate" }
        }
        val result = game.gameResult ?: error("game ended without result")
        for (s in 0..3) scores[s] += result.deltaScores[s]

        val record = RoundRecord(roundIndex, dealer, roundWind, dealerStreak, result, scores.toList())
        roundIndex++

        if (result.dealerContinues) {
            dealerStreak++
        } else {
            dealerStreak = 0
            dealer = (dealer + 1) % 4
            rotationCount++
            if (rotationCount % 4 == 0) roundWind = (roundWind + 1) % 4
        }
        return record
    }
}

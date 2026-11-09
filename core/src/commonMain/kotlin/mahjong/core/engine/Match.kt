package mahjong.core.engine

import mahjong.core.model.Action
import mahjong.core.model.GameResult
import mahjong.core.model.PlayerView
import mahjong.core.rules.RuleConfig

/**
 * 一局结束后的轮转状态：庄家、圈风、连庄次数与已轮转的庄家数。
 * 单机（App.playAgain）与联机（LanRoom）共用这一份，避免两套轮庄规则。
 */
data class RoundRotation(
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val dealerStreak: Int = 0,
    val rotations: Int = 0,
) {
    /** [continues] 为 true 表示本局连庄（庄家不变），否则庄家交给下家、轮满四家进一圈。 */
    fun next(continues: Boolean): RoundRotation = if (continues) {
        copy(dealerStreak = dealerStreak + 1)
    } else {
        val nextRotations = rotations + 1
        copy(
            dealer = (dealer + 1) % 4,
            roundWind = if (nextRotations % 4 == 0) (roundWind + 1) % 4 else roundWind,
            dealerStreak = 0,
            rotations = nextRotations,
        )
    }
}

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

        val rotated = RoundRotation(dealer, roundWind, dealerStreak, rotationCount)
            .next(result.dealerContinues)
        dealer = rotated.dealer
        roundWind = rotated.roundWind
        dealerStreak = rotated.dealerStreak
        rotationCount = rotated.rotations
        return record
    }
}

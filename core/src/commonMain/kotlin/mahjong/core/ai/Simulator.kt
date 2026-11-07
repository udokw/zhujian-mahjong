package mahjong.core.ai

import mahjong.core.engine.Match
import mahjong.core.rules.RuleConfig

data class LevelStats(
    val level: AiLevel,
    val games: Int,
    val wins: Int,
    val selfDraws: Int,
    val dealInGames: Int,
    val drawGames: Int,
    val fanSum: Int,
    val scoreDeltaSum: Int,
) {
    val winRate: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
    val dealInRate: Double get() = if (games == 0) 0.0 else dealInGames.toDouble() / games
    val drawRate: Double get() = if (games == 0) 0.0 else drawGames.toDouble() / games
    val avgFan: Double get() = if (wins == 0) 0.0 else fanSum.toDouble() / wins
    val avgScoreDelta: Double get() = if (games == 0) 0.0 else scoreDeltaSum.toDouble() / games
}

/**
 * 批量自动对局：把待测难度放在一个座位，其余三家统一使用“普通”AI，
 * 并在多局中轮换座位与庄家，得到可比较的胜率/放铳率/平均番数。
 */
object Simulator {

    fun runBatch(
        config: RuleConfig,
        games: Int,
        seed: Long,
        levels: List<AiLevel> = AiLevel.entries,
    ): List<LevelStats> {
        require(games > 0)
        return levels.map { level -> runLevel(config, level, games, seed) }
    }

    fun runLevel(
        config: RuleConfig,
        level: AiLevel,
        games: Int,
        seed: Long,
    ): LevelStats {
        var wins = 0
        var selfDraws = 0
        var dealInGames = 0
        var drawGames = 0
        var fanSum = 0
        var scoreDeltaSum = 0

        for (game in 0 until games) {
            val testSeat = game % 4
            val dealer = (game / 4) % 4
            val gameSeed = seed + game * 104729L
            val bots = (0..3).map { seat ->
                val botLevel = if (seat == testSeat) level else AiLevel.NORMAL
                AiBot(botLevel, gameSeed + seat * 31L)
            }
            val match = Match(
                config = config,
                seed = gameSeed,
                rounds = 1,
                startDealer = dealer,
            )
            val record = match.playRound { view, legal ->
                bots[view.seat].choose(view, legal)
            }
            val result = record.result
            // 每局分数必须守恒（含买马、杠分、包牌），否则视为引擎缺陷。
            check(result.deltaScores.sum() == 0) {
                "score not zero-sum: ${result.deltaScores} reason=${result.reason}"
            }
            scoreDeltaSum += result.deltaScores[testSeat]

            if (result.draw) {
                drawGames++
            } else {
                val myWins = result.winners.filter { it.winner == testSeat }
                if (myWins.isNotEmpty()) {
                    wins++
                    if (myWins.any { it.selfDraw }) selfDraws++
                    fanSum += myWins.maxOf { it.totalFan }
                }
                if (result.winners.any { it.fromPlayer == testSeat && !it.selfDraw }) {
                    dealInGames++
                }
            }
        }

        return LevelStats(
            level = level,
            games = games,
            wins = wins,
            selfDraws = selfDraws,
            dealInGames = dealInGames,
            drawGames = drawGames,
            fanSum = fanSum,
            scoreDeltaSum = scoreDeltaSum,
        )
    }
}

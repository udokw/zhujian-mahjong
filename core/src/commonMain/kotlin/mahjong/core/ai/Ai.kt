package mahjong.core.ai

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.engine.Rng
import mahjong.core.model.Action
import mahjong.core.model.PlayerView
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.tile.Tiles

/** 出牌评估深度：越深越强，但计算量也越大。 */
enum class AiEval {
    /** 向听数 + 局部近似的进张估算。 */
    APPROX,
    /** 近似评估全部候选，再对前 3 张做精确进张复核。 */
    REFINE,
    /** 向听数 + 精确进张（对每张候选都做一次完整搜索）。 */
    EXACT,
}

/**
 * AI 难度。四级共用状态机与规则，差异体现在：
 * - 出牌评估深度（普通用近似进张，高手复核前 3 张，大师全量精确）
 * - 失误率（随机性）
 * - 防守权重（危险牌判断）
 * - 番型规划（高手/大师，大师权重更高）
 */
enum class AiLevel(
    val id: String,
    val displayName: String,
    val discardMistake: Double,
    val claimMistake: Double,
    val missWinRate: Double,
    val defenseWeight: Double,
    val ukeireWeight: Double,
    val fanWeight: Double,
    val eval: AiEval,
) {
    NOVICE("novice", "新手", 0.40, 0.30, 0.15, 0.0, 3.0, 0.0, AiEval.APPROX),
    NORMAL("normal", "普通", 0.18, 0.10, 0.03, 0.15, 4.0, 0.0, AiEval.APPROX),
    EXPERT("expert", "高手", 0.0, 0.0, 0.0, 0.6, 6.0, 2.5, AiEval.REFINE),
    MASTER("master", "大师", 0.0, 0.0, 0.0, 1.2, 3.0, 5.0, AiEval.EXACT),
    ;

    companion object {
        fun fromId(id: String): AiLevel? = entries.firstOrNull { it.id == id }
    }
}

interface MahjongBot {
    val level: AiLevel
    fun choose(view: PlayerView, legal: List<Action>): Action
}

class AiBot(
    override val level: AiLevel,
    seed: Long,
) : MahjongBot {
    private val rng = Rng(seed)

    override fun choose(view: PlayerView, legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "no legal actions" }

        val hu = legal.firstOrNull { it is Action.Hu }
        if (hu != null && !(level == AiLevel.NOVICE && rng.nextDouble() < level.missWinRate)) {
            return hu
        }

        val discards = legal.filterIsInstance<Action.Discard>()
        if (discards.isNotEmpty()) {
            val kong = legal.firstOrNull { it is Action.ConcealedKong }
                ?: legal.filterIsInstance<Action.AddedKong>().firstOrNull()
            if (kong != null) return kong
            return pickDiscard(view, discards)
        }

        val claims = legal.filter { it !is Action.Pass && it !is Action.Hu }
        if (claims.isEmpty()) return legal.first { it is Action.Pass }
        return pickClaim(view, claims, legal)
    }

    // ------------------------------------------------------------ 出牌

    private fun pickDiscard(view: PlayerView, discards: List<Action.Discard>): Action {
        val exact = level.eval == AiEval.EXACT
        val scored = discards.map { it to discardScore(view, it.tile, exact) }
        val best = scored.maxByOrNull { it.second }!!

        if (level.discardMistake > 0 && rng.nextDouble() < level.discardMistake) {
            // 新手直接乱打；普通只在接近最优的几张里失误。
            if (level == AiLevel.NOVICE) {
                return discards[rng.nextInt(discards.size)]
            }
            val candidates = scored.filter { it.second >= best.second - 500.0 }
            return candidates[rng.nextInt(candidates.size)].first
        }

        if (level.eval == AiEval.REFINE && scored.size > 1) {
            val top = scored.sortedByDescending { it.second }.take(3)
            return top.maxByOrNull { (action, _) -> discardScore(view, action.tile, true) }!!.first
        }
        return best.first
    }

    private fun discardScore(view: PlayerView, tile: Int, exact: Boolean): Double {
        val rest = view.hand.toMutableList()
        rest.remove(tile)
        val meldCount = view.melds.size
        val visibleMap = counts(view.visibleTiles(includeOwnHand = true))
        val shanten = HandAnalyzer.shanten(rest, meldCount)
        val ukeire = if (exact) {
            HandAnalyzer.ukeireCount(rest, meldCount, visibleMap)
        } else {
            approximateUkeire(rest, visibleMap)
        }

        var score = -shanten * 1000.0 + ukeire * level.ukeireWeight
        if (level.fanWeight > 0.0) {
            // 广东 2 倍制下番型收益更高，规划权重上浮。
            val modeFactor = if (view.config.mode == GameMode.GUANGDONG) 1.4 else 1.0
            score += fanPotential(rest, meldCount, view.config.mode) * level.fanWeight * modeFactor
        }
        score += safety(view, tile) * defenseFactor(view, shanten)
        return score
    }

    /**
     * 防守权重：只有对手能点炮和牌时才需要防守；自己越接近听牌越应该推牌，
     * 对手副露越多、牌墙越短，威胁越大。
     */
    private fun defenseFactor(view: PlayerView, shanten: Int): Double {
        if (!view.config.allowDiscardWin || level.defenseWeight <= 0.0) return 0.0
        val push = when {
            shanten <= 0 -> 0.25
            shanten == 1 -> 0.6
            else -> 1.0
        }
        val maxMelds = view.opponents.maxOfOrNull { it.melds.size } ?: 0
        // 早期几乎不防，避免为了安全把危险牌留到听牌后再打；副露多或牌墙见底才收紧。
        val threat = when {
            maxMelds >= 2 -> 0.9
            view.wallRemaining < 40 -> 0.7
            maxMelds == 1 -> 0.45
            else -> 0.15
        }
        return level.defenseWeight * push * threat * 0.6
    }

    // ------------------------------------------------------------ 吃碰杠

    private fun pickClaim(view: PlayerView, claims: List<Action>, legal: List<Action>): Action {
        val pass = legal.first { it is Action.Pass }
        if (level.claimMistake > 0 && rng.nextDouble() < level.claimMistake) {
            return claims[rng.nextInt(claims.size)]
        }

        val best = claims
            .map { it to claimValue(view, it) }
            .maxByOrNull { it.second }!!

        // 国标副露会丢门前清、拉低番数，高难度更克制；广东副露是正常提速手段。
        val guangdong = view.config.mode == GameMode.GUANGDONG
        val threshold = when (level) {
            AiLevel.NOVICE -> 30.0
            AiLevel.NORMAL -> 45.0
            AiLevel.EXPERT -> if (guangdong) 55.0 else 60.0
            AiLevel.MASTER -> if (guangdong) 45.0 else 70.0
        }
        return if (best.second >= threshold) best.first else pass
    }

    private fun claimValue(view: PlayerView, claim: Action): Double {
        val config: RuleConfig = view.config
        val tile = when (claim) {
            is Action.Peng -> claim.tile
            is Action.ExposedKong -> claim.tile
            is Action.Chi -> claim.claimed
            else -> return -1000.0
        }
        val removed = when (claim) {
            is Action.Peng -> listOf(tile, tile)
            is Action.ExposedKong -> listOf(tile, tile, tile)
            is Action.Chi -> claim.tiles
            else -> return -1000.0
        }
        val hand = view.hand.toMutableList()
        for (t in removed) hand.remove(t)

        val currentShanten = HandAnalyzer.shanten(view.hand, view.melds.size)
        val newMeldCount = view.melds.size + 1
        // 副露后还要打一张，按最好的那张牌评估。
        var bestAfter = Int.MAX_VALUE
        for (t in hand.distinct()) {
            val h = hand.toMutableList()
            h.remove(t)
            val sh = HandAnalyzer.shanten(h, newMeldCount)
            if (sh < bestAfter) bestAfter = sh
        }
        if (bestAfter == Int.MAX_VALUE) return -1000.0
        if (bestAfter > currentShanten) return -1000.0
        if (claim is Action.Chi && bestAfter >= currentShanten) return -1000.0

        var value = (currentShanten - bestAfter) * 120.0 + 40.0
        if (Tiles.isDragon(tile)) value += 35.0
        if (Tiles.isWind(tile) &&
            (tile == 27 + view.roundWind || tile == 27 + view.seatWind)
        ) {
            value += 30.0
        }
        if (claim is Action.ExposedKong) value += 25.0
        if (level.fanWeight > 0.0) {
            value += fanPotential(hand, newMeldCount, config.mode) * level.fanWeight * 0.32
        }
        return value
    }

    // ------------------------------------------------------------ 评估函数

    /**
     * 番型潜力估算，只用于高难度的出牌与副露取舍，不参与最终计分。
     * 国标偏好多花色复合番种；广东牌型不累积、只看最大牌型，因此方向不同。
     */
    private fun fanPotential(tiles: List<Int>, meldCount: Int, mode: GameMode): Double {
        val c = counts(tiles)
        val suitTotals = (0..2).map { s ->
            (1..9).sumOf { c[Tiles.tileOf(s, it)] ?: 0 }
        }
        val maxSuit = suitTotals.maxOrNull() ?: 0
        val honors = (27..33).sumOf { c[it] ?: 0 }
        val triplets = (0..33).count { (c[it] ?: 0) >= 3 } + meldCount
        val pairs = (0..33).count { (c[it] ?: 0) == 2 }

        if (mode == GameMode.GUANGDONG) {
            // 广东：清一色/混一色、碰碰胡、箭刻与风刻，顺子为主且将牌非字牌是平胡方向。
            var p = when {
                maxSuit >= 11 -> 30.0
                maxSuit >= 8 -> 12.0
                maxSuit >= 6 -> 5.0
                else -> 0.0
            }
            if (maxSuit >= 8 && honors in 1..3) p += 8.0
            if (triplets >= 3) p += 12.0 else p += triplets * 1.5
            for (d in 31..33) p += (c[d] ?: 0) * 3.0
            for (w in 27..30) p += (c[w] ?: 0) * 1.5
            p += chowPotential(c) * 1.5
            if (pairs > 0 && (27..33).none { c[it] == 2 }) p += 3.0
            return p
        }

        var p = when {
            maxSuit >= 11 -> 24.0
            maxSuit >= 8 -> 10.0
            maxSuit >= 6 -> 4.0
            else -> 0.0
        }
        if (maxSuit >= 8 && honors in 1..3) p += 6.0 // 混一色方向

        p += triplets * 3.0 + pairs * 1.2
        if (triplets >= 3) p += 6.0 // 碰碰胡方向

        for (d in 31..33) p += (c[d] ?: 0) * 2.5
        for (w in 27..30) p += (c[w] ?: 0) * 1.5
        for (t in 0..26) if (Tiles.isTerminal(t)) p += (c[t] ?: 0) * 0.8
        return p
    }

    /** 顺子潜力：相邻两张都有的搭子数量。 */
    private fun chowPotential(c: Map<Int, Int>): Int {
        var n = 0
        for (t in 0..26) {
            if (t % 9 == 8) continue
            if ((c[t] ?: 0) > 0 && (c[t + 1] ?: 0) > 0) n++
        }
        return n
    }

    /** 危险牌判断：分数越高越安全。 */
    private fun safety(view: PlayerView, tile: Int): Double {
        var s = 50.0
        val visible = view.visibleTiles(includeOwnHand = true)
        val seen = visible.count { it == tile }
        for (o in view.opponents) {
            if (o.discards.contains(tile)) s += 35.0
            val open = o.melds.size
            if (tile < 27) {
                val suit = Tiles.suit(tile)
                val sameSuit = o.melds.count { it.suit == suit }
                s -= sameSuit * 12.0
                val rank = Tiles.rank(tile)
                if (rank in 3..7) s -= open * 4.0 else s += 6.0
            } else {
                s += 8.0
            }
            if (open >= 3) s -= 8.0
            if (o.discards.size >= 8) s -= 5.0
        }
        if (seen >= 3) s += 25.0
        return s.coerceIn(0.0, 100.0)
    }

    private fun counts(tiles: Iterable<Int>): Map<Int, Int> {
        val c = IntArray(Tiles.TYPE_COUNT)
        for (t in tiles) if (t in 0..33) c[t]++
        return (0..33).associateWith { c[it] }
    }

    /**
     * 快速进张估算：只做局部相邻牌计算，避免对每张候选牌都跑一次向听搜索。
     * 用于批量自动对局，保证 AI 决策速度。
     */
    private fun approximateUkeire(tiles: List<Int>, visible: Map<Int, Int>): Int {
        val c = IntArray(34)
        for (t in tiles) if (t in 0..33) c[t]++
        var total = 0
        for (t in 0..33) {
            val remaining = 4 - (visible[t] ?: 0)
            if (remaining <= 0) continue
            if (c[t] >= 2) {
                total += remaining
                continue
            }
            if (t >= 27) continue
            val rank = t % 9
            var adjacent = 0
            if (rank >= 1) adjacent += c[t - 1]
            if (rank >= 2) adjacent += c[t - 2]
            if (rank <= 7) adjacent += c[t + 1]
            if (rank <= 6) adjacent += c[t + 2]
            if (adjacent > 0) {
                total += (remaining * (0.4 + 0.2 * adjacent)).toInt()
            }
        }
        return total
    }
}

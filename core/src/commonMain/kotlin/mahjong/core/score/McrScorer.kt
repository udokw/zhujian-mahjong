package mahjong.core.score

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.analysis.SpecialHand
import mahjong.core.model.Group
import mahjong.core.model.HandShape
import mahjong.core.model.Meld
import mahjong.core.rules.RuleConfig
import mahjong.core.tile.Tiles

/**
 * 国标麻将（MCR）番种判定与结算。
 *
 * 设计要点：
 * 1. 枚举所有可能的“4 面子 + 1 将”分解，分别计番，取最高方案；
 * 2. 特殊牌型（七对、连七对、十三幺、九莲宝灯、全不靠、七星不靠）单独判定；
 * 3. 通过 FanTable 的排除表实现“不重复计”原则；
 * 4. 结算分 = 总番 × 底分；不足起和番门槛返回 null。
 */
object McrScorer {
    fun score(
        tiles: List<Int>,
        melds: List<Meld>,
        ctx: WinContext,
        config: RuleConfig,
    ): WinResult? {
        if (!HandAnalyzer.isWinningHand(tiles, melds)) return null
        val candidates = ArrayList<List<FanItem>>()

        for (shape in HandAnalyzer.winShapes(tiles, melds)) {
            candidates.add(evaluateShape(shape, ctx))
        }
        HandAnalyzer.specialHand(tiles, melds.size)?.let {
            candidates.add(evaluateSpecial(it, tiles, ctx, config))
        }
        if (candidates.isEmpty()) return null

        var best: List<FanItem>? = null
        var bestFan = -1
        for (raw in candidates) {
            val items = finalize(raw, ctx, config, melds)
            val fan = items.sumOf { it.totalFan }
            if (fan > bestFan) {
                bestFan = fan
                best = items
            }
        }
        val fanItems = best ?: return null
        val flowerFanTotal = fanItems.filter { it.id == Fan.FLOWER.id }.sumOf { it.totalFan }
        val thresholdFan = if (config.flowerCountsTowardMin) bestFan else bestFan - flowerFanTotal
        if (thresholdFan < config.minFan) return null

        return WinResult(
            winner = ctx.seat,
            fromPlayer = ctx.fromPlayer,
            winningTile = ctx.winningTile,
            selfDraw = ctx.selfDraw,
            robKong = ctx.robKong,
            fanItems = fanItems,
            totalFan = bestFan,
            thresholdFan = thresholdFan,
            points = ScoreEngine.pointsFor(config, bestFan),
            handTiles = tiles.sorted(),
            melds = melds,
        )
    }

    // ---------------------------------------------------------------- 结构番

    private fun evaluateSpecial(
        special: SpecialHand,
        tiles: List<Int>,
        ctx: WinContext,
        config: RuleConfig,
    ): List<FanItem> {
        val items = mutableListOf<FanItem>()
        fun add(fan: Fan, count: Int = 1) {
            if (count > 0) items.add(FanItem(fan.id, fan.displayName, fan.value, count))
        }
        when (special) {
            SpecialHand.SEVEN_PAIRS -> {
                add(Fan.SEVEN_PAIRS)
                addSuitAndNumberFans(tiles, items)
            }

            SpecialHand.SEVEN_SHIFTED_PAIRS -> {
                add(Fan.SEVEN_SHIFTED_PAIRS)
                add(Fan.FULL_FLUSH)
            }

            SpecialHand.THIRTEEN_ORPHANS -> add(Fan.THIRTEEN_ORPHANS)

            SpecialHand.NINE_GATES -> {
                add(Fan.NINE_GATES)
                add(Fan.FULL_FLUSH)
            }

            SpecialHand.LESSER_HONORS_AND_KNITTED -> add(Fan.LESSER_HONORS_AND_KNITTED)
            SpecialHand.GREATER_HONORS_AND_KNITTED -> add(Fan.GREATER_HONORS_AND_KNITTED)
        }
        return items
    }

    /** 七对等无面子牌型也能成立的花色/数字番。 */
    private fun addSuitAndNumberFans(tiles: List<Int>, items: MutableList<FanItem>) {
        val honors = tiles.count { Tiles.isHonor(it) }
        val suits = tiles.filter { !Tiles.isHonor(it) }.map { Tiles.suit(it) }.distinct()
        fun add(fan: Fan) {
            items.add(FanItem(fan.id, fan.displayName, fan.value))
        }
        if (honors == tiles.size) add(Fan.ALL_HONORS)
        if (suits.size == 1 && honors == 0) add(Fan.FULL_FLUSH)
        if (suits.size == 1 && honors > 0) add(Fan.HALF_FLUSH)
        if (suits.size == 2) add(Fan.MISSING_ONE_SUIT)
        if (honors == 0) add(Fan.NO_HONORS)
        if (honors == 0 && tiles.all { Tiles.rank(it) in 2..8 }) add(Fan.ALL_SIMPLES)
        if (tiles.all { Tiles.isTerminalOrHonor(it) }) {
            if (honors == 0) add(Fan.ALL_TERMINALS) else add(Fan.ALL_TERMINALS_AND_HONORS)
        }
        if (honors == 0 && tiles.all { Tiles.rank(it) in 7..9 }) add(Fan.UPPER_TILES)
        if (honors == 0 && tiles.all { Tiles.rank(it) in 4..6 }) add(Fan.MIDDLE_TILES)
        if (honors == 0 && tiles.all { Tiles.rank(it) in 1..3 }) add(Fan.LOWER_TILES)
        if (honors == 0 && tiles.all { Tiles.rank(it) in 6..9 }) add(Fan.UPPER_FOUR)
        if (honors == 0 && tiles.all { Tiles.rank(it) in 1..4 }) add(Fan.LOWER_FOUR)
        if (tiles.filter { !Tiles.isHonor(it) }.all { isGreen(it) }) add(Fan.ALL_GREEN)
        if (tiles.any { Tiles.isWind(it) } && tiles.any { Tiles.isDragon(it) } && suits.size == 3) add(Fan.ALL_TYPES)
        if (tiles.all { it % 2 == 0 || Tiles.isHonor(it) }) { /* 七对不判定全双刻 */ }
    }

    private fun evaluateShape(shape: HandShape, ctx: WinContext): List<FanItem> {
        val items = mutableListOf<FanItem>()
        fun add(fan: Fan, count: Int = 1) {
            if (count > 0) items.add(FanItem(fan.id, fan.displayName, fan.value, count))
        }

        val groups = shape.groups
        val chows = groups.filter { it.isChow }
        val pungs = groups.filter { it.isPung }
        val kongs = groups.filter { it.isKong }
        val pungLike = pungs + kongs
        val concealedPungs = groups.filter { it.isConcealedPungLike }
        val pair = shape.pair
        val all = shape.allTiles

        val windPungs = pungLike.filter { Tiles.isWind(it.tile) }
        val dragonPungs = pungLike.filter { Tiles.isDragon(it.tile) }
        val pairIsWind = Tiles.isWind(pair)
        val pairIsDragon = Tiles.isDragon(pair)

        // 88 / 64：字牌大牌
        if (windPungs.size == 4) {
            add(Fan.BIG_FOUR_WINDS)
        } else if (windPungs.size == 3 && pairIsWind) {
            add(Fan.LITTLE_FOUR_WINDS)
        } else if (windPungs.size == 3) {
            add(Fan.BIG_THREE_WINDS)
        }

        if (dragonPungs.size == 3) {
            add(Fan.BIG_THREE_DRAGONS)
        } else if (dragonPungs.size == 2 && pairIsDragon) {
            add(Fan.LITTLE_THREE_DRAGONS)
        } else if (dragonPungs.size == 2) {
            add(Fan.TWO_DRAGON_PUNGS)
        }

        if (all.all { isGreen(it) }) add(Fan.ALL_GREEN)
        if (all.all { isReversible(it) }) add(Fan.REVERSIBLE_TILES)

        if (kongs.size == 4) add(Fan.FOUR_KONGS) else if (kongs.size == 3) add(Fan.THREE_KONGS)

        if (concealedPungs.size >= 4) {
            add(Fan.FOUR_CONCEALED_PUNGS)
        } else if (concealedPungs.size == 3) {
            add(Fan.THREE_CONCEALED_PUNGS)
        } else if (concealedPungs.size == 2) {
            add(Fan.TWO_CONCEALED_PUNGS)
        }

        val honors = all.count { Tiles.isHonor(it) }
        val suited = all.filter { !Tiles.isHonor(it) }
        val suits = suited.map { Tiles.suit(it) }.distinct()

        if (honors == all.size) add(Fan.ALL_HONORS)
        if (all.all { Tiles.isTerminalOrHonor(it) }) {
            if (honors == 0) add(Fan.ALL_TERMINALS) else add(Fan.ALL_TERMINALS_AND_HONORS)
        }

        if (pungLike.size == 4) add(Fan.ALL_PUNGS)

        if (honors == 0 && suits.size == 1) add(Fan.FULL_FLUSH)
        if (honors > 0 && suits.size == 1) add(Fan.HALF_FLUSH)
        if (suits.size == 2) add(Fan.MISSING_ONE_SUIT)
        if (honors == 0) add(Fan.NO_HONORS)
        if (suits.size == 3 && all.any { Tiles.isWind(it) } && all.any { Tiles.isDragon(it) }) {
            add(Fan.ALL_TYPES)
        }
        if (honors == 0 && all.all { Tiles.rank(it) in 2..8 }) add(Fan.ALL_SIMPLES)

        if (honors == 0 && all.all { Tiles.rank(it) in 7..9 }) add(Fan.UPPER_TILES)
        if (honors == 0 && all.all { Tiles.rank(it) in 6..9 }) add(Fan.UPPER_FOUR)
        if (honors == 0 && all.all { Tiles.rank(it) in 4..6 }) add(Fan.MIDDLE_TILES)
        if (honors == 0 && all.all { Tiles.rank(it) in 1..3 }) add(Fan.LOWER_TILES)
        if (honors == 0 && all.all { Tiles.rank(it) in 1..4 }) add(Fan.LOWER_FOUR)

        if (pungLike.size == 4 && honors == 0 && all.all { Tiles.rank(it) % 2 == 0 }) {
            add(Fan.ALL_EVEN_PUNGS)
        }

        if (honors == 0 && groups.all { g -> g.tiles.any { Tiles.rank(it) == 5 } } && Tiles.rank(pair) == 5) {
            add(Fan.ALL_FIVES)
        }

        if (groups.all { g -> g.tiles.any { Tiles.isTerminalOrHonor(it) } } && Tiles.isTerminalOrHonor(pair)) {
            add(Fan.ALL_WITH_TERMINALS)
        }

        if (chows.size == 4 && !Tiles.isHonor(pair)) add(Fan.PING_HU)
        if (!ctx.selfDraw && pair == ctx.winningTile && groups.all { !it.concealed }) {
            add(Fan.MELDED_HAND)
        }

        // 一色双龙会 / 三色双龙会
        if (chows.size == 4) {
            for (s in 0..2) {
                if (chowCount(chows, s, 1) >= 2 && chowCount(chows, s, 7) >= 2 && pair == Tiles.tileOf(s, 5)) {
                    add(Fan.PURE_TERMINAL_CHOWS)
                    break
                }
            }
            if (items.none { it.id == Fan.PURE_TERMINAL_CHOWS.id }) {
                outer@ for (a in 0..2) {
                    if (chowCount(chows, a, 1) < 1 || chowCount(chows, a, 7) < 1) continue
                    for (b in 0..2) {
                        if (b == a || chowCount(chows, b, 1) < 1) continue
                        for (c in 0..2) {
                            if (c == a || c == b || chowCount(chows, c, 7) < 1) continue
                            if (Tiles.rank(pair) == 5) {
                                add(Fan.THREE_SUITED_TERMINAL_CHOWS)
                                break@outer
                            }
                        }
                    }
                }
            }
        }

        // 一色四同顺 / 一色三同顺 / 一般高
        val sameChowCount = chows.groupingBy { it.tile }.eachCount().values.maxOrNull() ?: 0
        if (sameChowCount >= 4) add(Fan.QUADRUPLE_CHOW)
        else if (sameChowCount == 3) add(Fan.PURE_TRIPLE_CHOW)
        val doubleChows = chows.groupBy { it.tile }.count { it.value.size >= 2 }
        if (sameChowCount < 3) add(Fan.PURE_DOUBLE_CHOW, doubleChows)

        // 三色三同顺 / 三色三步高 / 花龙
        for (rank in 1..7) {
            if ((0..2).count { s -> chows.any { it.tile == Tiles.tileOf(s, rank) } } >= 3) {
                add(Fan.MIXED_TRIPLE_CHOW)
                break
            }
        }
        val chowStartsBySuit = (0..2).map { s ->
            chows.filter { Tiles.suit(it.tile) == s }.map { Tiles.rank(it.tile) }.toSet()
        }
        for (rank in 1..7) {
            val perm = knittedPerms.any { p ->
                (0..2).all { i -> (rank + i) in chowStartsBySuit[p[i]] }
            }
            if (perm) {
                add(Fan.MIXED_SHIFTED_CHOWS)
                break
            }
        }
        if (knittedPerms.any { p -> (0..2).all { i -> (1 + i * 3) in chowStartsBySuit[p[i]] } }) {
            add(Fan.HUA_DRAGON)
        }

        // 一色四节高 / 三节高
        val pungRanksBySuit = (0..2).map { s ->
            pungLike.filter { Tiles.suit(it.tile) == s }.map { Tiles.rank(it.tile) }.sorted()
        }
        if (pungRanksBySuit.any { ranks -> hasConsecutive(ranks, 4) }) {
            add(Fan.FOUR_PURE_SHIFTED_PUNGS)
        } else if (pungRanksBySuit.any { ranks -> hasConsecutive(ranks, 3) }) {
            add(Fan.PURE_SHIFTED_PUNGS)
        }

        // 三色三节高：三花色刻子序号依次递增一位
        val pungSetsBySuit = (0..2).map { s ->
            pungLike.filter { Tiles.suit(it.tile) == s }.map { Tiles.rank(it.tile) }.toSet()
        }
        for (rank in 1..7) {
            if (knittedPerms.any { p -> (0..2).all { i -> (rank + i) in pungSetsBySuit[p[i]] } }) {
                add(Fan.MIXED_SHIFTED_PUNGS)
                break
            }
        }

        // 一色四步高 / 三步高 / 清龙
        if (chowStartsBySuit.any { starts -> hasArithmeticRun(starts.sorted(), 4) }) {
            add(Fan.FOUR_PURE_SHIFTED_CHOWS)
        } else if (chowStartsBySuit.any { starts -> hasArithmeticRun(starts.sorted(), 3) }) {
            add(Fan.PURE_SHIFTED_CHOWS)
        }
        for (s in 0..2) {
            if (chowCount(chows, s, 1) >= 1 && chowCount(chows, s, 4) >= 1 && chowCount(chows, s, 7) >= 1) {
                add(Fan.PURE_STRAIGHT)
                break
            }
        }

        // 组合龙
        if (kongs.isEmpty() && isKnittedStraightHand(all)) add(Fan.KNITTED_STRAIGHT)

        // 三同刻 / 双同刻
        val suitedPungs = pungLike.filter { !Tiles.isHonor(it.tile) }
        val ranksWithSuits = suitedPungs.groupBy { Tiles.rank(it.tile) }.filterValues { list ->
            list.map { Tiles.suit(it.tile) }.distinct().size >= 2
        }
        if (ranksWithSuits.values.any { it.map { g -> Tiles.suit(g.tile) }.distinct().size >= 3 }) {
            add(Fan.TRIPLE_PUNG)
        }
        for ((_, list) in ranksWithSuits) {
            val suitCount = list.map { Tiles.suit(it.tile) }.distinct().size
            if (suitCount == 2) add(Fan.DOUBLE_PUNG)
        }

        // 杠番
        val concealedKongs = kongs.count { it.concealed }
        val exposedKongs = kongs.size - concealedKongs
        if (concealedKongs >= 2) add(Fan.TWO_CONCEALED_KONGS)
        if (exposedKongs >= 2) add(Fan.TWO_MELDED_KONGS)
        add(Fan.CONCEALED_KONG, concealedKongs)
        add(Fan.MELDED_KONG, exposedKongs)

        // 箭刻 / 圈风刻 / 门风刻 / 幺九刻
        dragonPungs.forEach { add(Fan.DRAGON_PUNG) }
        add(Fan.ROUND_WIND_PUNG, pungLike.count { it.tile == 27 + ctx.roundWind })
        add(Fan.SEAT_WIND_PUNG, pungLike.count { it.tile == 27 + ctx.seatWind })

        val valueHonors = setOf(27 + ctx.roundWind, 27 + ctx.seatWind, 31, 32, 33)
        val terminalHonorPungs = pungLike.count {
            Tiles.isTerminal(it.tile) || (Tiles.isHonor(it.tile) && it.tile !in valueHonors)
        }
        add(Fan.TERMINAL_HONOR_PUNG, terminalHonorPungs)

        // 四归一
        val kongTiles = kongs.map { it.tile }.toSet()
        val fourCount = (0..33).count { t ->
            t !in kongTiles && all.count { it == t } == 4
        }
        add(Fan.FOUR_OF_A_KIND, fourCount)

        // 一般高 / 喜相逢 / 连六 / 老少副
        var mixedDouble = 0
        var shortStraight = 0
        var terminalChows = 0
        for (i in chows.indices) {
            for (j in i + 1 until chows.size) {
                val a = chows[i]
                val b = chows[j]
                if (Tiles.rank(a.tile) == Tiles.rank(b.tile) && Tiles.suit(a.tile) != Tiles.suit(b.tile)) {
                    mixedDouble++
                }
                if (Tiles.suit(a.tile) == Tiles.suit(b.tile)) {
                    val diff = kotlin.math.abs(Tiles.rank(a.tile) - Tiles.rank(b.tile))
                    if (diff == 3) shortStraight++
                    val starts = setOf(Tiles.rank(a.tile), Tiles.rank(b.tile))
                    if (starts == setOf(1, 7)) terminalChows++
                }
            }
        }
        add(Fan.MIXED_DOUBLE_CHOW, mixedDouble)
        add(Fan.SHORT_STRAIGHT, shortStraight)
        add(Fan.TWO_TERMINAL_CHOWS, terminalChows)

        // 待张
        waitFan(shape, ctx)?.let { add(it) }

        return items
    }

    // ---------------------------------------------------------------- 收尾

    private fun finalize(
        raw: List<FanItem>,
        ctx: WinContext,
        config: RuleConfig,
        melds: List<Meld>,
    ): List<FanItem> {
        val items = raw.toMutableList()
        fun add(fan: Fan, count: Int = 1) {
            if (count > 0) items.add(FanItem(fan.id, fan.displayName, fan.value, count))
        }
        val concealed = melds.all { it.isConcealed }

        if (ctx.selfDraw) add(Fan.SELF_DRAW)
        if (ctx.selfDraw && ctx.lastTile) add(Fan.LAST_TILE_DRAW)
        if (!ctx.selfDraw && ctx.lastTile) add(Fan.LAST_TILE_CLAIM)
        if (ctx.selfDraw && ctx.kongReplacement) add(Fan.OUT_WITH_REPLACEMENT)
        if (ctx.robKong) add(Fan.ROBBING_THE_KONG)
        val seenWinningTile = ctx.visibleTiles.count { it == ctx.winningTile }
        if ((ctx.selfDraw && seenWinningTile >= 3) || (!ctx.selfDraw && seenWinningTile >= 4)) {
            add(Fan.LAST_TILE)
        }
        if (concealed && !ctx.selfDraw) add(Fan.FRONT_CONCEALED)
        if (concealed && ctx.selfDraw) add(Fan.FULLY_CONCEALED)
        if (config.flowerFan > 0 && ctx.flowers.isNotEmpty()) {
            add(Fan.FLOWER, ctx.flowers.size)
        }
        if (raw.isEmpty()) add(Fan.NO_FAN_HAND)

        return FanTable.apply(items)
    }

    // ---------------------------------------------------------------- 工具

    private fun waitFan(shape: HandShape, ctx: WinContext): Fan? {
        val win = ctx.winningTile
        if (shape.pair == win) return Fan.SINGLE_WAIT
        for (g in shape.groups) {
            if (!g.isChow) continue
            if (Tiles.suit(g.tile) != Tiles.suit(win)) continue
            val pos = g.tiles.indexOf(win)
            if (pos < 0) continue
            if (pos == 1) return Fan.CLOSED_WAIT
            val startRank = Tiles.rank(g.tile)
            val winRank = Tiles.rank(win)
            if ((startRank == 1 && winRank == 3) || (startRank == 7 && winRank == 7)) return Fan.EDGE_WAIT
        }
        return null
    }

    private fun chowCount(chows: List<Group>, suit: Int, rank: Int): Int =
        chows.count { it.tile == Tiles.tileOf(suit, rank) }

    private fun hasConsecutive(sortedRanks: List<Int>, length: Int): Boolean {
        if (sortedRanks.size < length) return false
        for (i in 0..sortedRanks.size - length) {
            var ok = true
            for (j in 1 until length) {
                if (sortedRanks[i + j] != sortedRanks[i] + j) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun hasArithmeticRun(sortedStarts: List<Int>, length: Int): Boolean {
        if (sortedStarts.size < length) return false
        for (i in 0..sortedStarts.size - length) {
            for (step in 1..2) {
                var ok = true
                for (j in 1 until length) {
                    if (sortedStarts[i + j] != sortedStarts[i] + step * j) {
                        ok = false
                        break
                    }
                }
                if (ok) return true
            }
        }
        return false
    }

    private val knittedSets = listOf(setOf(1, 4, 7), setOf(2, 5, 8), setOf(3, 6, 9))

    private val knittedPerms = listOf(
        intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
    )

    private fun isKnittedStraightHand(all: List<Int>): Boolean {
        if (all.size != 14) return false
        val counts = HandAnalyzer.counts(all)
        for (perm in knittedPerms) {
            val c = counts.copyOf()
            var ok = true
            for (s in 0..2) {
                for (r in knittedSets[perm[s]]) {
                    val t = Tiles.tileOf(s, r)
                    if (c[t] <= 0) {
                        ok = false
                        break
                    }
                    c[t]--
                }
                if (!ok) break
            }
            if (ok && isSetPlusPair(c)) return true
        }
        return false
    }

    private fun isSetPlusPair(c: IntArray): Boolean {
        if (c.sum() != 5) return false
        for (t in 0..33) {
            if (c[t] < 2) continue
            c[t] -= 2
            val rests = c.indices.filter { c[it] > 0 }
            if (rests.size == 1 && c[rests[0]] == 3) {
                c[t] += 2
                return true
            }
            if (rests.size >= 2) {
                val a = rests[0]
                if (a < 27 && a % 9 <= 6 && c[a + 1] > 0 && c[a + 2] > 0) {
                    c[t] += 2
                    return true
                }
            }
            c[t] += 2
        }
        return false
    }

    private fun isGreen(tile: Int): Boolean = when (tile) {
        19, 20, 21, 23, 25, 32 -> true // 2/3/4/6/8 条 + 发
        else -> false
    }

    /** 推不倒：筒 1234589、条 245689、白板。 */
    private fun isReversible(tile: Int): Boolean = when (tile) {
        9, 10, 11, 12, 13, 16, 17, 19, 21, 22, 23, 25, 26, 33 -> true
        else -> false
    }
}

package mahjong.core.analysis

import mahjong.core.model.Group
import mahjong.core.model.GroupType
import mahjong.core.model.HandShape
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.tile.Tiles

enum class SpecialHand {
    SEVEN_PAIRS,
    SEVEN_SHIFTED_PAIRS,
    THIRTEEN_ORPHANS,
    NINE_GATES,
    LESSER_HONORS_AND_KNITTED,
    GREATER_HONORS_AND_KNITTED,
}

/**
 * 纯牌型分析：不涉及番种、不做平台相关操作。
 */
object HandAnalyzer {

    fun counts(tiles: Iterable<Int>): IntArray {
        val c = IntArray(Tiles.TYPE_COUNT)
        for (t in tiles) if (t in 0 until Tiles.TYPE_COUNT) c[t]++
        return c
    }

    fun meldGroup(meld: Meld): Group = when (meld.type) {
        MeldType.CHOW -> Group(GroupType.CHOW, meld.baseTile, concealed = false, meldType = meld.type)
        MeldType.PUNG -> Group(GroupType.PUNG, meld.baseTile, concealed = false, meldType = meld.type)
        MeldType.KONG_EXPOSED -> Group(GroupType.KONG, meld.baseTile, concealed = false, meldType = meld.type)
        MeldType.KONG_ADDED -> Group(GroupType.KONG, meld.baseTile, concealed = false, meldType = meld.type)
        MeldType.KONG_CONCEALED -> Group(GroupType.KONG, meld.baseTile, concealed = true, meldType = meld.type)
    }

    /**
     * 枚举所有“4 面子 + 1 将”的标准分解（不含七对/十三幺等特殊牌型）。
     * 副露中的面子会直接并入结果。
     */
    fun winShapes(tiles: List<Int>, melds: List<Meld>): List<HandShape> {
        val setsNeeded = 4 - melds.size
        if (setsNeeded < 0) return emptyList()
        val concealed = counts(tiles)
        if (concealed.sum() != setsNeeded * 3 + 2) return emptyList()

        val meldGroups = melds.map { meldGroup(it) }
        val result = LinkedHashSet<HandShape>()
        for (pair in 0 until 34) {
            if (concealed[pair] < 2) continue
            concealed[pair] -= 2
            val groups = ArrayList<Group>(4)
            decompose(concealed, 0, setsNeeded, groups) { shapeGroups ->
                result.add(HandShape(pair, meldGroups + shapeGroups))
            }
            concealed[pair] += 2
        }
        return result.toList()
    }

    private fun decompose(
        counts: IntArray,
        start: Int,
        setsLeft: Int,
        current: MutableList<Group>,
        onFound: (List<Group>) -> Unit,
    ) {
        if (setsLeft == 0) {
            if (counts.all { it == 0 }) onFound(current.toList())
            return
        }
        var i = start
        while (i < 34 && counts[i] == 0) i++
        if (i >= 34) return

        if (counts[i] >= 3) {
            counts[i] -= 3
            current.add(Group(GroupType.PUNG, i))
            decompose(counts, i, setsLeft - 1, current, onFound)
            current.removeAt(current.lastIndex)
            counts[i] += 3
        }
        if (i < 27 && i % 9 <= 6 && counts[i + 1] > 0 && counts[i + 2] > 0) {
            counts[i]--
            counts[i + 1]--
            counts[i + 2]--
            current.add(Group(GroupType.CHOW, i))
            decompose(counts, i, setsLeft - 1, current, onFound)
            current.removeAt(current.lastIndex)
            counts[i]++
            counts[i + 1]++
            counts[i + 2]++
        }
    }

    fun isWinningHand(tiles: List<Int>, melds: List<Meld>): Boolean {
        if (specialHand(tiles, melds.size) != null) return true
        return winShapes(tiles, melds).isNotEmpty()
    }

    /** 手牌是否已经能和（不含番数门槛）。 */
    fun isWinningTiles(tiles: List<Int>): Boolean {
        if (tiles.size == 14 && sevenPairsType(tiles) != null) return true
        if (tiles.size == 14 && isThirteenOrphans(tiles)) return true
        if (tiles.size == 14 && isNineGates(tiles)) return true
        return winShapes(tiles, emptyList()).isNotEmpty()
    }

    fun specialHand(tiles: List<Int>, meldCount: Int = 0): SpecialHand? {
        if (meldCount != 0 || tiles.size != 14) return null
        sevenPairsType(tiles)?.let { return it }
        if (isThirteenOrphans(tiles)) return SpecialHand.THIRTEEN_ORPHANS
        if (isNineGates(tiles)) return SpecialHand.NINE_GATES
        knittedHandType(tiles)?.let { return it }
        return null
    }

    fun sevenPairsType(tiles: List<Int>): SpecialHand? {
        if (tiles.size != 14) return null
        val c = counts(tiles)
        if (c.any { it != 0 && it != 2 }) return null
        if (c.count { it == 2 } != 7) return null
        // 连七对：同一花色 7 个连续对子
        val pairTiles = c.indices.filter { c[it] == 2 }
        if (pairTiles.all { it < 27 } && pairTiles.map { Tiles.suit(it) }.distinct().size == 1) {
            val ranks = pairTiles.map { Tiles.rank(it) }.sorted()
            if (ranks.last() - ranks.first() == 6 && ranks.toSet().size == 7) {
                return SpecialHand.SEVEN_SHIFTED_PAIRS
            }
        }
        return SpecialHand.SEVEN_PAIRS
    }

    fun isThirteenOrphans(tiles: List<Int>): Boolean {
        if (tiles.size != 14) return false
        val c = counts(tiles)
        val required = buildList {
            for (s in 0..2) {
                add(Tiles.tileOf(s, 1))
                add(Tiles.tileOf(s, 9))
            }
            for (h in 27..33) add(h)
        }
        if (required.any { c[it] == 0 }) return false
        if (c.indices.any { it !in required && c[it] > 0 }) return false
        return required.sumOf { c[it] } == 14 && required.any { c[it] >= 2 }
    }

    fun isNineGates(tiles: List<Int>): Boolean {
        if (tiles.size != 14) return false
        val c = counts(tiles)
        val suit = (0..2).firstOrNull { s ->
            (0 until 34).all { t -> Tiles.suit(t) != s || c[t] >= 0 } &&
                (0 until 34).filter { t -> Tiles.suit(t) == s }.sumOf { c[it] } == 14
        } ?: return false
        for (r in 1..9) if (c[Tiles.tileOf(suit, r)] < 1) return false
        return c[Tiles.tileOf(suit, 1)] >= 3 && c[Tiles.tileOf(suit, 9)] >= 3
    }

    private val knittedPatterns = listOf(
        setOf(1, 4, 7),
        setOf(2, 5, 8),
        setOf(3, 6, 9),
    )

    private val permutations = listOf(
        intArrayOf(0, 1, 2),
        intArrayOf(0, 2, 1),
        intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0),
        intArrayOf(2, 0, 1),
        intArrayOf(2, 1, 0),
    )

    fun knittedHandType(tiles: List<Int>): SpecialHand? {
        if (tiles.size != 14) return null
        val c = counts(tiles)
        if (c.any { it > 1 }) return null
        if (tiles.any { it > 33 }) return null
        val honors = tiles.filter { it in 27..33 }
        val suited = tiles.filter { it in 0..26 }
        val suitRanks = (0..2).map { s -> suited.filter { Tiles.suit(it) == s }.map { Tiles.rank(it) }.toSet() }
        if (suitRanks.any { it.isEmpty() }) return null

        val knitted = permutations.any { perm ->
            (0..2).all { s -> suitRanks[s].all { r -> r in knittedPatterns[perm[s]] } }
        }
        if (!knitted) return null
        return when (honors.size) {
            7 -> SpecialHand.GREATER_HONORS_AND_KNITTED
            0 -> null
            else -> SpecialHand.LESSER_HONORS_AND_KNITTED
        }
    }

    /** 判断多个花色是否能凑成组合龙（9 张：1-4-7 / 2-5-8 / 3-6-9 各一色）。 */
    fun knittedStraightTiles(tiles: List<Int>): List<Int>? {
        val c = counts(tiles)
        for (perm in permutations) {
            val picked = ArrayList<Int>(9)
            var ok = true
            for (s in 0..2) {
                for (r in knittedPatterns[perm[s]]) {
                    val t = Tiles.tileOf(s, r)
                    if (c[t] <= 0) {
                        ok = false
                        break
                    }
                    picked.add(t)
                }
                if (!ok) break
            }
            if (ok) return picked.sorted()
        }
        return null
    }

    /**
     * 标准向听数。meldCount 为已副露面子数（含杠）。
     */
    fun shanten(tiles: List<Int>, meldCount: Int = 0): Int {
        val c = counts(tiles)
        var best = standardShanten(c, meldCount)
        if (meldCount == 0) {
            best = minOf(best, sevenPairsShanten(c), thirteenOrphansShanten(c))
        }
        return best
    }

    private fun standardShanten(original: IntArray, meldCount: Int): Int {
        val c = original.copyOf()
        var min = 8
        fun search(idx: Int, sets: Int, partials: Int, pair: Int) {
            var i = idx
            while (i < 34 && c[i] == 0) i++
            if (i == 34) {
                val sh = 8 - 2 * (sets + meldCount) - partials - pair
                if (sh < min) min = sh
                return
            }
            if (sets + meldCount >= 4 && pair == 1 && partials == 0) {
                // 继续扫描只能降低不到更优，直接返回
                val sh = 8 - 2 * (sets + meldCount) - pair
                if (sh < min) min = sh
                return
            }

            if (c[i] >= 3) {
                c[i] -= 3
                search(i, sets + 1, partials, pair)
                c[i] += 3
            }
            if (i < 27 && i % 9 <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
                c[i]--
                c[i + 1]--
                c[i + 2]--
                search(i, sets + 1, partials, pair)
                c[i]++
                c[i + 1]++
                c[i + 2]++
            }
            if (c[i] >= 2 && pair == 0) {
                c[i] -= 2
                search(i, sets, partials, 1)
                c[i] += 2
            }
            if (c[i] >= 2 && sets + meldCount + partials < 4) {
                c[i] -= 2
                search(i, sets, partials + 1, pair)
                c[i] += 2
            }
            if (i < 27 && i % 9 <= 7 && c[i + 1] > 0 && sets + meldCount + partials < 4) {
                c[i]--
                c[i + 1]--
                search(i, sets, partials + 1, pair)
                c[i]++
                c[i + 1]++
            }
            if (i < 27 && i % 9 <= 6 && c[i + 2] > 0 && sets + meldCount + partials < 4) {
                c[i]--
                c[i + 2]--
                search(i, sets, partials + 1, pair)
                c[i]++
                c[i + 2]++
            }
            c[i]--
            search(i, sets, partials, pair)
            c[i]++
        }
        search(0, 0, 0, 0)
        return min
    }

    private fun sevenPairsShanten(c: IntArray): Int {
        val pairs = (0 until 34).count { c[it] >= 2 }
        val kinds = (0 until 34).count { c[it] > 0 }
        return 6 - pairs + maxOf(0, 7 - kinds)
    }

    private fun thirteenOrphansShanten(c: IntArray): Int {
        val orphans = buildList {
            for (s in 0..2) {
                add(Tiles.tileOf(s, 1))
                add(Tiles.tileOf(s, 9))
            }
            for (h in 27..33) add(h)
        }
        val kinds = orphans.count { c[it] > 0 }
        val hasPair = orphans.any { c[it] >= 2 }
        return 13 - kinds - if (hasPair) 1 else 0
    }

    /** 13 张手牌的听牌列表（返回牌值）。 */
    fun waits(tiles: List<Int>, meldCount: Int = 0): List<Int> {
        if (tiles.size != 13 - meldCount * 3) return emptyList()
        val result = ArrayList<Int>()
        for (t in 0 until 34) {
            if (tiles.count { it == t } >= 4) continue
            val test = tiles + t
            if (isWinningTilesWithMelds(test, meldCount)) result.add(t)
        }
        return result
    }

    fun isWinningTilesWithMelds(tiles: List<Int>, meldCount: Int): Boolean {
        val fakeMelds = List(meldCount) { Meld.pung(0, -1) }
        if (specialHand(tiles, meldCount) != null) return true
        return winShapes(tiles, fakeMelds).isNotEmpty()
    }

    /**
     * 有效进张：摸到哪些牌可以降低向听数，返回 牌 -> 剩余张数。
     * @param visible 已可见的牌计数（含自己手牌），用于扣减剩余枚数。
     */
    fun ukeire(tiles: List<Int>, meldCount: Int, visible: Map<Int, Int>): Map<Int, Int> {
        val base = shanten(tiles, meldCount)
        if (base <= -1) return emptyMap()
        val result = LinkedHashMap<Int, Int>()
        for (t in 0 until 34) {
            if (remaining(t, visible) <= 0) continue
            val test = tiles + t
            if (shanten(test, meldCount) < base) {
                result[t] = remaining(t, visible)
            }
        }
        return result
    }

    fun remaining(tile: Int, visible: Map<Int, Int>): Int = 4 - (visible[tile] ?: 0)

    /** 牌效率：进张总枚数。 */
    fun ukeireCount(tiles: List<Int>, meldCount: Int, visible: Map<Int, Int>): Int =
        ukeire(tiles, meldCount, visible).values.sum()
}

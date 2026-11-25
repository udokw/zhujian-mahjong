package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mahjong.core.analysis.HandAnalyzer
import mahjong.core.analysis.SpecialHand
import mahjong.core.rules.RuleConfig
import mahjong.core.score.Fan
import mahjong.core.score.McrScorer
import mahjong.core.score.WinContext
import mahjong.core.score.WinResult
import mahjong.core.tile.Tiles

class McrScoringTest {

    private val config = RuleConfig.mcr()

    @Test
    fun bigFourWindsExcludesContainedFans() {
        val hand = List(3) { Tiles.EAST } + List(3) { Tiles.SOUTH } + List(3) { Tiles.WEST } +
            List(3) { Tiles.NORTH } + List(2) { Tiles.RED }
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.RED), config))

        assertTrue(result.fanItems.any { it.id == Fan.BIG_FOUR_WINDS.id })
        assertTrue(result.fanItems.none { it.id == Fan.LITTLE_FOUR_WINDS.id })
        assertTrue(result.fanItems.none { it.id == Fan.ALL_PUNGS.id })
        assertTrue(result.fanItems.none { it.id == Fan.ROUND_WIND_PUNG.id })
        assertTrue(result.totalFan >= 88)
    }

    @Test
    fun thirteenOrphansIsSingleFan() {
        val hand = m(1, 9) + p(1, 9) + s(1, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED, Tiles.GREEN, Tiles.WHITE) +
            m(1)
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(0, 1)), config))
        assertTrue(result.fanItems.any { it.id == Fan.THIRTEEN_ORPHANS.id })
        assertEquals(88, result.totalFan)
    }

    @Test
    fun sevenPairsScores() {
        val hand = m(1, 1, 3, 3, 5, 5) + p(2, 2, 4, 4) + s(6, 6, 8, 8)
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(2, 8)), config))
        assertTrue(result.fanItems.any { it.id == Fan.SEVEN_PAIRS.id })
        assertEquals(25, result.totalFan)
    }

    @Test
    fun fullFlushAndAllSimplesAreCounted() {
        val hand = m(2, 3, 4, 2, 3, 4, 4, 5, 6, 6, 7, 8, 8, 8)
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(0, 8)), config))
        assertTrue(result.fanItems.any { it.id == Fan.FULL_FLUSH.id })
        assertTrue(result.fanItems.any { it.id == Fan.ALL_SIMPLES.id })
        assertTrue(result.fanItems.none { it.id == Fan.NO_HONORS.id })
        assertTrue(result.fanItems.none { it.id == Fan.HALF_FLUSH.id })
    }

    @Test
    fun nineGatesScores() {
        val hand = m(1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9) + m(5)
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(0, 5)), config))
        assertTrue(result.fanItems.any { it.id == Fan.NINE_GATES.id })
        assertEquals(88, result.totalFan)
    }

    @Test
    fun noFanHandDoesNotStackWithWinningMethod() {
        // 全顺子 + 字牌将，无其他番：自摸/门前清都只能与无番和取其一。
        val hand = m(1, 2, 3, 2, 3, 4) + p(3, 4, 5) + s(6, 7, 8) + listOf(Tiles.EAST, Tiles.EAST)
        val winTile = Tiles.tileOf(0, 4)

        val selfDraw = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = winTile, selfDraw = true), config)
        )
        assertTrue(selfDraw.fanItems.any { it.id == Fan.NO_FAN_HAND.id }, "selfDraw fans=${selfDraw.fanItems}")
        assertTrue(selfDraw.fanItems.none { it.id == Fan.SELF_DRAW.id }, "selfDraw fans=${selfDraw.fanItems}")
        assertTrue(selfDraw.fanItems.none { it.id == Fan.FULLY_CONCEALED.id })
        assertEquals(8, selfDraw.totalFan)

        val discardWin = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = winTile, selfDraw = false), config)
        )
        assertTrue(discardWin.fanItems.any { it.id == Fan.NO_FAN_HAND.id })
        assertTrue(discardWin.fanItems.none { it.id == Fan.FRONT_CONCEALED.id })
        assertEquals(8, discardWin.totalFan)
    }

    @Test
    fun flowersDoNotCountTowardMinimum() {
        // 平和 + 单钓 + 无字 + 连六 + 门前清 = 7 番，加上 4 张花仍不足 8 分起和。
        val hand = m(1, 2, 3) + p(4, 5, 6) + p(7, 8, 9) + s(2, 3, 4) + s(5, 5)
        val flowers = listOf(34, 35, 36, 37)
        val strict = McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(2, 5), flowers = flowers), config)
        assertNull(strict)

        val relaxedConfig = config.copy(flowerCountsTowardMin = true)
        val relaxed = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.tileOf(2, 5), flowers = flowers), relaxedConfig)
        )
        assertEquals(11, relaxed.totalFan)
        assertEquals(11, relaxed.thresholdFan)
    }

    // ------------------------------------------------ 全不靠 / 七星不靠 / 组合龙

    private fun lesserKnittedHand(): List<Int> =
        m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED)

    private fun greaterKnittedHand(): List<Int> =
        m(1, 4, 7) + p(2, 5) + s(3, 6) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED, Tiles.GREEN, Tiles.WHITE)

    @Test
    fun lesserHonorsAndKnittedIs12PlusFrontConcealed() {
        val hand = lesserKnittedHand()
        assertEquals(SpecialHand.LESSER_HONORS_AND_KNITTED, HandAnalyzer.specialHand(hand))
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.RED), config))
        assertTrue(result.fanItems.any { it.id == Fan.LESSER_HONORS_AND_KNITTED.id })
        assertTrue(result.fanItems.any { it.id == Fan.FRONT_CONCEALED.id })
        assertTrue(result.fanItems.none { it.id == Fan.GREATER_HONORS_AND_KNITTED.id })
        assertEquals(14, result.totalFan)
    }

    @Test
    fun lesserHonorsAndKnittedSelfDrawExcludesFullyConcealed() {
        val hand = lesserKnittedHand()
        val result = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.RED, selfDraw = true), config)
        )
        assertTrue(result.fanItems.any { it.id == Fan.LESSER_HONORS_AND_KNITTED.id })
        assertTrue(result.fanItems.none { it.id == Fan.FULLY_CONCEALED.id })
        assertTrue(result.fanItems.any { it.id == Fan.SELF_DRAW.id })
        assertEquals(13, result.totalFan)
    }

    @Test
    fun greaterHonorsAndKnittedScores24PlusFrontConcealed() {
        val hand = greaterKnittedHand()
        // 七星与全不靠天然互斥：七个字牌全齐时只应返回七星不靠。
        assertEquals(SpecialHand.GREATER_HONORS_AND_KNITTED, HandAnalyzer.specialHand(hand))
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.WHITE), config))
        assertTrue(result.fanItems.any { it.id == Fan.GREATER_HONORS_AND_KNITTED.id })
        assertTrue(result.fanItems.any { it.id == Fan.FRONT_CONCEALED.id })
        assertTrue(result.fanItems.none { it.id == Fan.LESSER_HONORS_AND_KNITTED.id })
        assertEquals(26, result.totalFan)
    }

    @Test
    fun greaterHonorsAndKnittedSelfDrawIs25() {
        val hand = greaterKnittedHand()
        val result = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.WHITE, selfDraw = true), config)
        )
        assertTrue(result.fanItems.none { it.id == Fan.FULLY_CONCEALED.id })
        assertTrue(result.fanItems.any { it.id == Fan.SELF_DRAW.id })
        assertEquals(25, result.totalFan)
    }

    @Test
    fun knittedRejectsPairsAndConnectedTiles() {
        val withPair = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) + List(2) { Tiles.EAST } +
            listOf(Tiles.SOUTH, Tiles.WEST, Tiles.NORTH)
        assertEquals(14, withPair.size)
        assertNull(HandAnalyzer.specialHand(withPair))

        // 万子 1/2 相连，不再满足不靠。
        val connected = m(1, 2, 4, 7) + p(5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED)
        assertEquals(14, connected.size)
        assertNull(HandAnalyzer.specialHand(connected))
    }

    @Test
    fun knittedStraightIsRecognizedAndScores() {
        val hand = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            List(3) { Tiles.EAST } + List(2) { Tiles.RED }
        assertEquals(SpecialHand.KNITTED_STRAIGHT, HandAnalyzer.specialHand(hand))
        val result = assertNotNull(McrScorer.score(hand, emptyList(), ctx(winningTile = Tiles.RED), config))
        assertTrue(result.fanItems.any { it.id == Fan.KNITTED_STRAIGHT.id })
        assertTrue(result.fanItems.any { it.id == Fan.ALL_TYPES.id })
        assertEquals(24, result.totalFan)
    }

    // ---------------------------------------------------------------- 和绝张

    @Test
    fun lastTileOnDiscardNeedsThreeVisible() {
        val hand = lesserKnittedHand()
        val w = Tiles.RED
        val twoVisible = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = w, visibleTiles = List(3) { w }), config)
        )
        assertTrue(twoVisible.fanItems.none { it.id == Fan.LAST_TILE.id })

        val fourVisible = assertNotNull(
            McrScorer.score(hand, emptyList(), ctx(winningTile = w, visibleTiles = List(4) { w }), config)
        )
        assertTrue(fourVisible.fanItems.any { it.id == Fan.LAST_TILE.id })
    }

    @Test
    fun lastTileOnSelfDrawWithThreeVisible() {
        val hand = lesserKnittedHand()
        val w = Tiles.RED
        val result = assertNotNull(
            McrScorer.score(
                hand, emptyList(),
                ctx(winningTile = w, selfDraw = true, visibleTiles = List(3) { w }),
                config,
            )
        )
        assertTrue(result.fanItems.any { it.id == Fan.LAST_TILE.id })
    }

    @Test
    fun robKongDoesNotCountLastTile() {
        val hand = lesserKnittedHand()
        val w = Tiles.RED
        val result = assertNotNull(
            McrScorer.score(
                hand, emptyList(),
                ctx(winningTile = w, robKong = true, visibleTiles = List(4) { w }),
                config,
            )
        )
        assertTrue(result.fanItems.any { it.id == Fan.ROBBING_THE_KONG.id })
        assertTrue(result.fanItems.none { it.id == Fan.LAST_TILE.id })
    }

    // ---------------------------------------------------------------- 工具

    private fun ctx(
        winningTile: Int,
        selfDraw: Boolean = false,
        flowers: List<Int> = emptyList(),
        seatWind: Int = 0,
        roundWind: Int = 0,
        visibleTiles: List<Int> = emptyList(),
        robKong: Boolean = false,
    ): WinContext = WinContext(
        seat = 0,
        selfDraw = selfDraw,
        winningTile = winningTile,
        fromPlayer = if (selfDraw) null else 1,
        seatWind = seatWind,
        roundWind = roundWind,
        flowers = flowers,
        dealer = 0,
        robKong = robKong,
        visibleTiles = visibleTiles,
    )

    private fun m(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(0, it) }
    private fun p(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(1, it) }
    private fun s(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(2, it) }
}

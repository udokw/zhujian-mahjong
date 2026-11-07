package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mahjong.core.rules.RuleConfig
import mahjong.core.score.GuangdongScorer
import mahjong.core.score.WinContext
import mahjong.core.score.WinResult
import mahjong.core.tile.Tiles

class GuangdongScoringTest {

    private val discardConfig = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)

    @Test
    fun chickenHandIsZeroFan() {
        val hand = m(1, 2, 3) + p(4, 5, 6) + s(7, 8, 9) + m(1, 1, 1) + p(2, 2)
        val result = assertNotNull(
            GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(1, 2)), discardConfig)
        )
        assertEquals(0, result.totalFan)
        assertEquals(1, result.points)
    }

    @Test
    fun pingHuIsOneFan() {
        val hand = m(1, 2, 3) + p(4, 5, 6) + s(7, 8, 9) + p(1, 2, 3) + m(5, 5)
        val result = assertNotNull(
            GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(0, 5)), discardConfig)
        )
        assertEquals(1, result.totalFan)
        assertEquals(2, result.points)
        assertTrue(result.fanItems.any { it.name == "平胡" })
    }

    @Test
    fun allPungsIsTwoFan() {
        val hand = m(1, 1, 1, 4, 4, 4) + p(2, 2, 2) + s(3, 3, 3) + p(5, 5)
        val result = assertNotNull(
            GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(1, 5)), discardConfig)
        )
        assertEquals(2, result.totalFan)
        assertEquals(4, result.points)
        assertTrue(result.fanItems.any { it.name == "碰碰胡" })
    }

    @Test
    fun fullFlushBreaksFanCap() {
        val hand = m(1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 1, 1, 2, 2)
        val result = assertNotNull(
            GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(0, 2), selfDraw = true), discardConfig)
        )
        // 清一色 4 番 > 封顶 3 番，自摸附加番不再叠加。
        assertEquals(4, result.totalFan)
        assertEquals(16, result.points)
    }

    @Test
    fun addOnsAreCappedForSmallPatterns() {
        val hand = m(1, 2, 3) + p(4, 5, 6) + s(7, 8, 9) + p(1, 2, 3) + m(5, 5)
        val result = assertNotNull(
            GuangdongScorer.score(
                hand,
                emptyList(),
                ctx(Tiles.tileOf(0, 5), selfDraw = true, flowers = listOf(34, 35)),
                discardConfig,
            )
        )
        // 平胡 1 + 自摸 1 + 花牌 2 = 4，封顶 3。
        assertEquals(3, result.totalFan)
        assertEquals(8, result.points)
    }

    @Test
    fun tuidaohuRejectsDiscardWin() {
        val hand = m(1, 2, 3) + p(4, 5, 6) + s(7, 8, 9) + p(1, 2, 3) + m(5, 5)
        val config = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        assertNull(GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(0, 5), selfDraw = false), config))
        assertNotNull(GuangdongScorer.score(hand, emptyList(), ctx(Tiles.tileOf(0, 5), selfDraw = true), config))
    }

    @Test
    fun robKongIsAllowedEvenWhenDiscardWinIsOff() {
        val hand = m(1, 2, 3) + p(4, 5, 6) + s(7, 8, 9) + p(1, 2, 3) + m(5, 5)
        val config = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        val result = GuangdongScorer.score(
            hand,
            emptyList(),
            WinContext(
                seat = 0,
                selfDraw = false,
                winningTile = Tiles.tileOf(0, 5),
                fromPlayer = 1,
                robKong = true,
                seatWind = 0,
                roundWind = 0,
                flowers = emptyList(),
                dealer = 0,
            ),
            config,
        )
        assertNotNull(result)
        assertTrue(result.fanItems.any { it.name == "抢杠" })
    }

    // ---------------------------------------------------------------- 工具

    private fun ctx(
        winningTile: Int,
        selfDraw: Boolean = false,
        flowers: List<Int> = emptyList(),
    ): WinContext = WinContext(
        seat = 0,
        selfDraw = selfDraw,
        winningTile = winningTile,
        fromPlayer = if (selfDraw) null else 1,
        seatWind = 0,
        roundWind = 0,
        flowers = flowers,
        dealer = 0,
    )

    private fun m(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(0, it) }
    private fun p(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(1, it) }
    private fun s(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(2, it) }
}

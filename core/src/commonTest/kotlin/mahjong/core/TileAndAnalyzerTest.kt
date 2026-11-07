package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mahjong.core.analysis.HandAnalyzer
import mahjong.core.analysis.SpecialHand
import mahjong.core.tile.Tiles

class TileAndAnalyzerTest {

    // ---------------------------------------------------------------- 牌张编码

    @Test
    fun tileEncoding() {
        assertEquals(0, Tiles.tileOf(0, 1))
        assertEquals(8, Tiles.tileOf(0, 9))
        assertEquals(9, Tiles.tileOf(1, 1))
        assertEquals(17, Tiles.tileOf(1, 9))
        assertEquals(18, Tiles.tileOf(2, 1))
        assertEquals(26, Tiles.tileOf(2, 9))

        assertEquals(0, Tiles.suit(0))
        assertEquals(1, Tiles.suit(9))
        assertEquals(2, Tiles.suit(26))
        assertEquals(3, Tiles.suit(27))

        for (r in 1..9) {
            assertEquals(r, Tiles.rank(Tiles.tileOf(0, r)))
            assertEquals(r, Tiles.rank(Tiles.tileOf(1, r)))
            assertEquals(r, Tiles.rank(Tiles.tileOf(2, r)))
        }
        assertEquals(1, Tiles.rank(Tiles.EAST))
        assertEquals(5, Tiles.rank(Tiles.RED))

        assertEquals("一万", Tiles.name(0))
        assertEquals("九条", Tiles.name(26))
        assertEquals("中", Tiles.name(Tiles.RED))
        assertEquals("春", Tiles.name(34))
    }

    @Test
    fun tilePredicates() {
        assertTrue(Tiles.isTerminal(0))
        assertTrue(Tiles.isTerminal(26))
        assertFalse(Tiles.isTerminal(4))
        assertTrue(Tiles.isHonor(Tiles.EAST))
        assertTrue(Tiles.isWind(Tiles.NORTH))
        assertFalse(Tiles.isDragon(Tiles.NORTH))
        assertTrue(Tiles.isDragon(Tiles.WHITE))
        assertTrue(Tiles.isFlower(41))
        assertFalse(Tiles.isFlower(33))
    }

    @Test
    fun parseTileText() {
        assertEquals(0, Tiles.parse("一万"))
        assertEquals(0, Tiles.parse("1万"))
        assertEquals(17, Tiles.parse("九筒"))
        assertEquals(Tiles.EAST, Tiles.parse("东"))
        assertEquals(listOf(0, 1, 2), Tiles.parseList("1万 2万 三万"))
    }

    // ---------------------------------------------------------------- 特殊牌型

    @Test
    fun sevenPairs() {
        val hand = m(1, 1, 3, 3, 5, 5) + p(2, 2, 4, 4) + s(6, 6, 8, 8)
        assertEquals(SpecialHand.SEVEN_PAIRS, HandAnalyzer.sevenPairsType(hand))
        assertTrue(HandAnalyzer.isWinningHand(hand, emptyList()))
    }

    @Test
    fun sevenShiftedPairs() {
        val hand = m(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7)
        assertEquals(SpecialHand.SEVEN_SHIFTED_PAIRS, HandAnalyzer.sevenPairsType(hand))
    }

    @Test
    fun thirteenOrphans() {
        val hand = m(1, 9) + p(1, 9) + s(1, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED, Tiles.GREEN, Tiles.WHITE) +
            m(1)
        assertEquals(14, hand.size)
        assertTrue(HandAnalyzer.isThirteenOrphans(hand))
        assertEquals(SpecialHand.THIRTEEN_ORPHANS, HandAnalyzer.specialHand(hand))
    }

    @Test
    fun nineGates() {
        // 1112345678999 + 5 万
        val hand = m(1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9) + m(5)
        assertEquals(14, hand.size)
        assertTrue(HandAnalyzer.isNineGates(hand))
        assertEquals(SpecialHand.NINE_GATES, HandAnalyzer.specialHand(hand))
    }

    @Test
    fun ordinaryHandIsNotSpecial() {
        val hand = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 2, 3) + s(1, 1)
        assertNull(HandAnalyzer.specialHand(hand))
        assertTrue(HandAnalyzer.isWinningHand(hand, emptyList()))
    }

    // ---------------------------------------------------------------- 向听 / 听牌

    @Test
    fun tenpaiShantenAndWaits() {
        val hand = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 2, 3) + s(5)
        assertEquals(13, hand.size)
        assertEquals(0, HandAnalyzer.shanten(hand))
        assertEquals(listOf(Tiles.tileOf(2, 5)), HandAnalyzer.waits(hand))
    }

    @Test
    fun oneShanten() {
        val hand = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 4) + p(5, 5)
        assertEquals(13, hand.size)
        assertEquals(1, HandAnalyzer.shanten(hand))
    }

    @Test
    fun thirteenOrphansTenpai() {
        val hand = m(1, 9) + p(1, 9) + s(1, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH, Tiles.RED, Tiles.GREEN, Tiles.WHITE)
        assertEquals(13, hand.size)
        assertEquals(0, HandAnalyzer.shanten(hand))
        // 十三种幺九牌各一张时，摸到任意幺九都能成和。
        assertEquals(13, HandAnalyzer.waits(hand).size)
    }

    // ---------------------------------------------------------------- 工具

    private fun m(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(0, it) }
    private fun p(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(1, it) }
    private fun s(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(2, it) }
}

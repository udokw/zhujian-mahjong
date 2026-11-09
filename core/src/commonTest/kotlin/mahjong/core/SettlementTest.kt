package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mahjong.core.engine.RoundRotation
import mahjong.core.engine.Settlement
import mahjong.core.rules.RuleConfig
import mahjong.core.score.WinResult
import mahjong.core.tile.Tiles

class SettlementTest {

    @Test
    fun mcrSelfDraw() {
        val cfg = RuleConfig.mcr()
        val result = Settlement.settle(
            config = cfg,
            winners = listOf(win(winner = 0, selfDraw = true, points = 10)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "自摸",
        )
        // 三家各付 底分 8 + 10 番。
        assertEquals(listOf(54, -18, -18, -18), result.deltaScores)
        assertFalse(result.dealerContinues)
    }

    @Test
    fun mcrDiscardWin() {
        val cfg = RuleConfig.mcr()
        val result = Settlement.settle(
            config = cfg,
            winners = listOf(win(winner = 0, selfDraw = false, from = 1, points = 10)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "点炮",
        )
        // 放炮者付 8+10，另两家各付底分 8。
        assertEquals(listOf(34, -18, -8, -8), result.deltaScores)
    }

    @Test
    fun guangdongSelfDrawAndDiscard() {
        val cfg = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        val selfDraw = Settlement.settle(
            config = cfg,
            winners = listOf(win(winner = 2, selfDraw = true, points = 8)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "自摸",
        )
        assertEquals(listOf(-8, -8, 24, -8), selfDraw.deltaScores)

        val discard = Settlement.settle(
            config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false),
            winners = listOf(win(winner = 2, selfDraw = false, from = 3, points = 4)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "点炮",
        )
        assertEquals(listOf(0, 0, 4, -4), discard.deltaScores)
    }

    @Test
    fun guangdongBaoPaiPaysAllThreeShares() {
        val cfg = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val result = Settlement.settle(
            config = cfg,
            winners = listOf(win(winner = 0, selfDraw = true, points = 6)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = mapOf(0 to 2),
            draw = false,
            reason = "包牌",
        )
        assertEquals(listOf(18, 0, -18, 0), result.deltaScores)
    }

    @Test
    fun gangDeltasAreCarriedIntoResult() {
        val cfg = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        val result = Settlement.settle(
            config = cfg,
            winners = emptyList(),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = listOf(3, -1, -1, -1),
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = true,
            reason = "荒庄",
        )
        assertEquals(listOf(3, -1, -1, -1), result.deltaScores)
        assertTrue(result.draw)
        assertTrue(result.dealerContinues)
    }

    @Test
    fun buyHorseSettlementIsZeroSum() {
        val cfg = RuleConfig.guangdongTuidaohu()
        val result = Settlement.settle(
            config = cfg,
            winners = listOf(win(winner = 1, selfDraw = true, points = 4)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            // 2 万 -> 序号 1 -> 庄家下家（座位 1）
            horses = listOf(Tiles.tileOf(0, 2)),
            baoPayers = emptyMap(),
            draw = false,
            reason = "自摸",
        )
        // 自摸后 [ -4, 12, -4, -4 ]，中马把座位 1 的输赢转给庄家。
        assertEquals(listOf(8, 0, -4, -4), result.deltaScores)
        assertEquals(0, result.deltaScores.sum())
        assertEquals(1, result.horses.size)
        assertTrue(result.horses[0].hitsWinner)
        assertEquals(listOf(1), result.horses[0].hitSeats)
    }

    @Test
    fun horseCountGrowsAndCapsAtFour() {
        val cfg = RuleConfig.guangdongTuidaohu()
        assertEquals(1, cfg.horseCountFor(0))
        assertEquals(2, cfg.horseCountFor(1))
        assertEquals(3, cfg.horseCountFor(2))
        assertEquals(4, cfg.horseCountFor(3))
        assertEquals(4, cfg.horseCountFor(10))
        assertEquals(0, RuleConfig.mcr().horseCountFor(3))

        // 鸡平胡同样默认买马，+1 递增、上限 4 匹。
        val jipinghu = RuleConfig.guangdongJipinghu()
        assertEquals(1, jipinghu.horseCountFor(0))
        assertEquals(4, jipinghu.horseCountFor(3))
        assertEquals(4, jipinghu.horseCountFor(10))
        assertEquals(0, jipinghu.copy(buyHorseEnabled = false).horseCountFor(3))
    }

    @Test
    fun dealerContinuesOnlyWhenDealerWins() {
        val gd = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        fun settleWith(winner: Int) = Settlement.settle(
            config = gd,
            winners = listOf(win(winner = winner, selfDraw = true, points = 4)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "自摸",
        )

        assertTrue(settleWith(winner = 0).dealerContinues)
        assertFalse(settleWith(winner = 1).dealerContinues)
        assertFalse(settleWith(winner = 2).dealerContinues)
        assertFalse(settleWith(winner = 3).dealerContinues)

        // 国标庄家胡牌也不连庄。
        val mcr = Settlement.settle(
            config = RuleConfig.mcr(),
            winners = listOf(win(winner = 0, selfDraw = true, points = 10)),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = false,
            reason = "自摸",
        )
        assertFalse(mcr.dealerContinues)
    }

    @Test
    fun dealerContinuesOnDrawFollowsPreset() {
        fun drawWith(config: RuleConfig) = Settlement.settle(
            config = config,
            winners = emptyList(),
            dealer = 0,
            roundWind = 0,
            dealerStreak = 0,
            gangDeltas = List(4) { 0 },
            horses = emptyList(),
            baoPayers = emptyMap(),
            draw = true,
            reason = "荒庄",
        )

        assertFalse(drawWith(RuleConfig.mcr()).dealerContinues)
        assertTrue(drawWith(RuleConfig.guangdongTuidaohu()).dealerContinues)
        assertTrue(drawWith(RuleConfig.guangdongJipinghu()).dealerContinues)
    }

    @Test
    fun presetsMatchProductRules() {
        val mcr = RuleConfig.mcr()
        assertEquals(144, mcr.buildTileSet().size)
        assertEquals(8, mcr.minFan)
        assertTrue(mcr.allowChi)
        assertFalse(mcr.multipleWinnersOnDiscard)
        assertFalse(mcr.buyHorseEnabled)
        assertFalse(mcr.dealerContinuesOnDraw)

        val tuidaohu = RuleConfig.guangdongTuidaohu()
        assertEquals(136, tuidaohu.buildTileSet().size)
        assertFalse(tuidaohu.allowChi)
        assertFalse(tuidaohu.allowDiscardWin)
        assertTrue(tuidaohu.buyHorseEnabled)
        assertTrue(tuidaohu.dealerContinuesOnDraw)

        val jipinghu = RuleConfig.guangdongJipinghu()
        assertEquals(144, jipinghu.buildTileSet().size)
        assertTrue(jipinghu.allowChi)
        assertTrue(jipinghu.allowDiscardWin)
        assertTrue(jipinghu.multipleWinnersOnDiscard)
        assertTrue(jipinghu.baoPai)
        assertTrue(jipinghu.buyHorseEnabled)
        // 开关保留可关。
        assertFalse(jipinghu.copy(buyHorseEnabled = false).buyHorseEnabled)
    }

    @Test
    fun mcrPresetRejectsConflictingFlags() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RuleConfig.mcr().copy(multipleWinnersOnDiscard = true)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RuleConfig.mcr().copy(dealerContinuesOnWin = true)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RuleConfig.mcr().copy(buyHorseEnabled = true)
        }
    }

    // ---------------------------------------------------------------- 轮庄/圈风

    @Test
    fun dealerKeepsSeatWhileContinuing() {
        val state = RoundRotation(dealer = 2, roundWind = 0)
        val next = state.next(continues = true)
        assertEquals(2, next.dealer)
        assertEquals(0, next.roundWind)
        assertEquals(1, next.dealerStreak)
        assertEquals(0, next.rotations)
    }

    @Test
    fun windsAdvanceAfterEveryFourRotations() {
        var state = RoundRotation()
        // 庄家轮着走一圈（东→南→西→北）后进南风圈。
        repeat(4) { state = state.next(continues = false) }
        assertEquals(0, state.dealer)
        assertEquals(1, state.roundWind)

        // 再轮一圈进西风圈。
        repeat(4) { state = state.next(continues = false) }
        assertEquals(2, state.roundWind)

        // 连庄不推进圈风。
        val stayed = state.next(continues = true)
        assertEquals(state.roundWind, stayed.roundWind)
        assertEquals(state.dealer, stayed.dealer)
    }

    // ---------------------------------------------------------------- 工具

    private fun win(winner: Int, selfDraw: Boolean, from: Int? = null, points: Int): WinResult = WinResult(
        winner = winner,
        fromPlayer = from,
        winningTile = Tiles.tileOf(0, 1),
        selfDraw = selfDraw,
        robKong = false,
        fanItems = emptyList(),
        totalFan = 0,
        thresholdFan = 0,
        points = points,
        handTiles = emptyList(),
        melds = emptyList(),
    )
}

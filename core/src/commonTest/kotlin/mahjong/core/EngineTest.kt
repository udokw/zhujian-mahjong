package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.model.Phase
import mahjong.core.rules.RuleConfig
import mahjong.core.tile.Tiles

class EngineTest {

    // ---------------------------------------------------------------- 可复现

    @Test
    fun sameSeedProducesSameGame() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val first = playOut(config, seed = 2024L)
        val second = playOut(config, seed = 2024L)
        assertEquals(first, second)
        assertTrue(first.size > 10)
    }

    // ---------------------------------------------------------------- 抢杠

    @Test
    fun robAddedKong() {
        val config = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        // P0 听 3 万；P1 先用 3 万碰，再摸到第 4 张加杠，被 P0 抢杠。
        val p0 = m(1, 2, 4, 5, 6, 7, 8, 9) + p(1, 1, 1) + listOf(Tiles.WHITE, Tiles.WHITE, Tiles.GREEN)
        val p1 = m(3, 3) + p(4, 5, 6) + s(7, 8, 9) + s(1, 4, 7) + listOf(Tiles.EAST, Tiles.EAST)
        val p2 = m(1, 2, 5, 7) + p(2, 4, 6, 8) + s(1, 3, 5, 7, 9)
        val p3 = m(2, 4, 6, 8) + p(1, 3, 5, 7, 9) + s(2, 4, 6, 8)
        val wall = buildWall(
            p0, p1, p2, p3,
            after = listOf(
                Tiles.tileOf(2, 9), // 53 P1 摸 9 条
                Tiles.tileOf(0, 3), // 54 P2 摸 3 万后打出
                Tiles.NORTH,        // 55 P2
                Tiles.NORTH,        // 56 P3
                Tiles.NORTH,        // 57 P0
                Tiles.tileOf(0, 3), // 58 P1 摸到第 4 张 3 万，加杠
            ),
        )
        val game = MahjongGame(config, seed = 1L, dealer = 0, wallTiles = wall)

        assertEquals(0, game.actor)
        game.apply(Action.Discard(0, Tiles.GREEN))

        assertEquals(1, game.actor)
        game.apply(Action.Discard(1, Tiles.tileOf(2, 9)))

        assertEquals(2, game.actor)
        game.apply(Action.Discard(2, Tiles.tileOf(0, 3)))

        // P1 的碰窗口
        assertEquals(Phase.AWAIT_CLAIM, game.state.phase)
        assertEquals(1, game.actor)
        assertTrue(game.legalActions(1).any { it == Action.Peng(1, Tiles.tileOf(0, 3)) })
        game.apply(Action.Peng(1, Tiles.tileOf(0, 3)))

        assertEquals(1, game.actor)
        game.apply(Action.Discard(1, Tiles.tileOf(2, 1)))

        assertEquals(2, game.actor)
        game.apply(Action.Discard(2, Tiles.NORTH))
        assertEquals(3, game.actor)
        game.apply(Action.Discard(3, Tiles.NORTH))
        assertEquals(0, game.actor)
        game.apply(Action.Discard(0, Tiles.NORTH))

        assertEquals(1, game.actor)
        assertTrue(game.legalActions(1).any { it == Action.AddedKong(1, Tiles.tileOf(0, 3)) })
        game.apply(Action.AddedKong(1, Tiles.tileOf(0, 3)))

        assertEquals(Phase.AWAIT_ROB_KONG, game.state.phase)
        assertEquals(0, game.actor)
        assertTrue(game.legalActions(0).any { it == Action.Hu(0) })
        game.apply(Action.Hu(0))

        val result = assertNotNull(game.gameResult)
        assertEquals(1, result.winners.size)
        assertEquals(0, result.winners[0].winner)
        assertTrue(result.winners[0].robKong)
        assertEquals("抢杠", result.reason)
    }

    // ---------------------------------------------------------------- 一炮多响

    @Test
    fun multipleWinnersOnOneDiscard() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val p0 = m(3, 1, 5, 9) + p(1, 5, 9) + s(1, 5, 9) +
            listOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH)
        val p1 = m(1, 2) + p(1, 2, 3, 4, 5, 6, 7, 8, 9) + listOf(Tiles.WHITE, Tiles.WHITE)
        val p2 = m(2, 4) + s(1, 2, 3, 4, 5, 6, 7, 8, 9) + listOf(Tiles.GREEN, Tiles.GREEN)
        val p3 = m(1, 5, 9) + p(1, 5, 9) + s(1, 5, 9) +
            listOf(Tiles.RED, Tiles.GREEN, Tiles.WHITE, Tiles.EAST)
        val game = MahjongGame(
            config, seed = 2L, dealer = 0,
            wallTiles = buildWall(p0, p1, p2, p3),
        )

        game.apply(Action.Discard(0, Tiles.tileOf(0, 3)))
        assertEquals(Phase.AWAIT_CLAIM, game.state.phase)
        assertEquals(setOf(1, 2), game.state.pendingClaimSeats.toSet())

        game.apply(Action.Hu(1))
        game.apply(Action.Hu(2))

        val result = assertNotNull(game.gameResult)
        assertEquals(2, result.winners.size)
        assertEquals(setOf(1, 2), result.winners.map { it.winner }.toSet())
        assertTrue(result.winners.all { it.fromPlayer == 0 })
        assertEquals("一炮多响", result.reason)
    }

    // ---------------------------------------------------------------- 荒庄

    @Test
    fun exhaustedWallIsDraw() {
        val config = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        val p0 = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 2, 3, 5, 9)
        val p1 = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH)
        val p2 = m(2, 4, 6) + p(1, 3, 7) + s(1, 4, 7) +
            listOf(Tiles.WEST, Tiles.WEST, Tiles.NORTH, Tiles.NORTH)
        val p3 = m(1, 2, 3) + p(1, 2, 3) + s(1, 2, 3) +
            listOf(Tiles.RED, Tiles.WHITE, Tiles.EAST, Tiles.SOUTH)
        val game = MahjongGame(
            config, seed = 3L, dealer = 0,
            wallTiles = buildWall(p0, p1, p2, p3, size = 53),
        )

        game.apply(Action.Discard(0, Tiles.tileOf(1, 9)))

        val result = assertNotNull(game.gameResult)
        assertTrue(result.draw)
        assertTrue(result.reason.contains("荒庄"))
        assertTrue(result.dealerContinues)
    }

    // ---------------------------------------------------------------- 补花

    @Test
    fun drawnFlowerIsReplacedFromWallTail() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val p0 = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 2, 3, 5, 9)
        val p1 = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH)
        val p2 = m(2, 4, 6) + p(1, 3, 7) + s(1, 4, 7) +
            listOf(Tiles.WEST, Tiles.WEST, Tiles.NORTH, Tiles.NORTH)
        val p3 = m(1, 2, 3) + p(1, 2, 3) + s(1, 2, 3) +
            listOf(Tiles.RED, Tiles.WHITE, Tiles.EAST, Tiles.SOUTH)
        val wall = buildWall(p0, p1, p2, p3, after = listOf(34), size = 60)
        val game = MahjongGame(config, seed = 4L, dealer = 0, wallTiles = wall)

        game.apply(Action.Discard(0, Tiles.tileOf(1, 9)))

        val state = game.state
        assertEquals(1, state.currentPlayer)
        assertEquals(listOf(34), state.players[1].flowers)
        assertEquals(14, state.players[1].hand.size)
        assertEquals(Phase.AWAIT_DISCARD, state.phase)
    }

    // ---------------------------------------------------------------- 买马

    @Test
    fun jipinghuBuysHorseAndSkipsFlowerTiles() {
        // 鸡平胡默认买马；庄家起手即自摸，马牌从牌尾取，遇到花牌要跳过。
        val config = RuleConfig.guangdongJipinghu()
        val p0 = m(1, 2, 3, 4, 5, 6, 7, 8, 9) + p(1, 2, 3) + s(5, 5)
        val p1 = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH)
        val p2 = m(2, 4, 6) + p(1, 3, 7) + s(1, 4, 7) +
            listOf(Tiles.WEST, Tiles.WEST, Tiles.NORTH, Tiles.NORTH)
        val p3 = m(1, 2, 3) + p(1, 2, 3) + s(1, 2, 3) +
            listOf(Tiles.RED, Tiles.WHITE, Tiles.EAST, Tiles.SOUTH)
        val wall = buildWall(p0, p1, p2, p3, size = 60).toMutableList()
        wall[59] = 34 // 花
        wall[58] = 41 // 花
        wall[57] = Tiles.tileOf(0, 5)
        val game = MahjongGame(config, seed = 5L, dealer = 0, wallTiles = wall)

        assertEquals(0, game.actor)
        assertTrue(game.legalActions(0).any { it == Action.Hu(0) })
        game.apply(Action.Hu(0))

        val result = assertNotNull(game.gameResult)
        assertEquals(1, result.horses.size)
        assertEquals(Tiles.tileOf(0, 5), result.horses[0].tile)
        assertTrue(!Tiles.isFlower(result.horses[0].tile))
    }

    // ---------------------------------------------------------------- 非法动作

    @Test
    fun illegalActionsAreRejected() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val game = MahjongGame(config, seed = 6L)
        val actor = assertNotNull(game.actor)
        val otherSeat = (actor + 1) % 4
        val tile = game.viewFor(actor).hand.first()

        // 自己回合不能过牌。
        kotlin.test.assertFailsWith<IllegalArgumentException> { game.apply(Action.Pass(actor)) }
        // 非当前座位不能打牌/胡牌。
        kotlin.test.assertFailsWith<IllegalArgumentException> { game.apply(Action.Discard(otherSeat, tile)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { game.apply(Action.Hu(otherSeat)) }

        // 被拒绝的动作不能推进牌局。
        assertEquals(actor, game.actor)
        assertEquals(0, game.state.turn)
    }

    @Test
    fun concealedKongRespectsRuleSwitch() {
        val p0 = m(1, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9) + listOf(Tiles.EAST, Tiles.EAST)
        val p1 = m(1, 4, 7) + p(2, 5, 8) + s(3, 6, 9) +
            listOf(Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH)
        val p2 = m(2, 4, 6) + p(1, 3, 7) + s(1, 4, 7) +
            listOf(Tiles.WEST, Tiles.WEST, Tiles.NORTH, Tiles.NORTH)
        val p3 = m(1, 2, 3) + p(1, 2, 3) + s(1, 2, 3) +
            listOf(Tiles.RED, Tiles.WHITE, Tiles.NORTH, Tiles.SOUTH)
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false, allowKong = false)
        val game = MahjongGame(config, seed = 7L, dealer = 0, wallTiles = buildWall(p0, p1, p2, p3))

        assertEquals(0, game.actor)
        assertTrue(game.legalActions(0).none { it is Action.ConcealedKong })
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            game.apply(Action.ConcealedKong(0, Tiles.tileOf(0, 1)))
        }
    }

    // ---------------------------------------------------------------- 鸡平胡包牌

    /** 十二张落地：四副副露全部落地后自摸，由打出第四副的玩家包三家。 */
    @Test
    fun twelveTileExposedSelfDrawIsPaidByLastFeeder() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val p0 = m(1, 2, 3, 4) + listOf(
            Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH, Tiles.WEST, Tiles.WEST,
            Tiles.NORTH, Tiles.NORTH, Tiles.RED, Tiles.GREEN,
        )
        val p1 = m(1, 1, 2, 2, 3, 3, 4, 4) + p(5, 6, 7, 8) + s(9)
        val p2 = p(1, 1, 3, 3, 5, 5, 7, 7) + s(1, 1, 3, 3, 5)
        val p3 = p(2, 2, 4, 4, 6, 6, 8, 8) + s(2, 2, 4, 4, 6)
        val wall = buildWall(
            p0, p1, p2, p3,
            after = List(12) { Tiles.NORTH } + Tiles.tileOf(2, 9),
        )
        val game = MahjongGame(config, seed = 10L, dealer = 0, wallTiles = wall)

        // P1 依次碰 1/2/3/4 万，第四副由 P0 打出。
        for (rank in 1..4) {
            game.apply(Action.Discard(0, Tiles.tileOf(0, rank)))
            respondClaims(game, 1, Action.Peng(1, Tiles.tileOf(0, rank)))
            game.apply(Action.Discard(1, Tiles.tileOf(1, rank + 4)))
            passClaims(game)
            if (rank < 4) {
                game.apply(Action.Discard(2, Tiles.NORTH))
                passClaims(game)
                game.apply(Action.Discard(3, Tiles.NORTH))
                passClaims(game)
            }
        }
        game.apply(Action.Discard(2, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(3, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(0, Tiles.EAST))
        passClaims(game)

        assertTrue(game.legalActions(1).contains(Action.Hu(1)))
        game.apply(Action.Hu(1))
        val result = assertNotNull(game.gameResult)
        val points = result.winners.single().points

        assertTrue(!result.draw)
        assertEquals(1, result.winners.single().winner)
        assertEquals(-3 * points, result.deltaScores[0])
        assertEquals(3 * points, result.deltaScores[1])
        assertEquals(0, result.deltaScores[2])
        assertEquals(0, result.deltaScores[3])
        assertEquals(0, result.deltaScores.sum())
    }

    /** 包先不包后：先由 P3 触发的包牌优先，后触发的 P0 不再承担。 */
    @Test
    fun earlierBaoTakesPrecedenceOverLaterBao() {
        val config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false)
        val p0 = listOf(Tiles.RED, Tiles.tileOf(0, 1)) + listOf(
            Tiles.EAST, Tiles.EAST, Tiles.SOUTH, Tiles.SOUTH, Tiles.WEST, Tiles.WEST,
            Tiles.NORTH, Tiles.NORTH,
        ) + p(1, 1, 9, 9)
        val p1 = listOf(
            Tiles.RED, Tiles.RED, Tiles.GREEN, Tiles.GREEN, Tiles.WHITE, Tiles.WHITE,
            Tiles.tileOf(0, 1), Tiles.tileOf(0, 1),
        ) + p(5, 6, 7, 8) + s(9)
        val p2 = listOf(Tiles.GREEN) + p(1, 1, 3, 3, 5, 5, 7, 7) + s(1, 1, 3, 3)
        val p3 = listOf(Tiles.WHITE) + p(2, 2, 4, 4, 6, 6, 8, 8) + s(2, 2, 4, 4)
        val wall = buildWall(
            p0, p1, p2, p3,
            after = List(9) { Tiles.NORTH } + Tiles.tileOf(2, 9),
        )
        val game = MahjongGame(config, seed = 11L, dealer = 0, wallTiles = wall)

        // 第 1-3 副：红中(P0)、发财(P2)、白板(P3) → 大三元包牌责任归 P3。
        game.apply(Action.Discard(0, Tiles.RED))
        respondClaims(game, 1, Action.Peng(1, Tiles.RED))
        game.apply(Action.Discard(1, Tiles.tileOf(1, 5)))
        passClaims(game)

        game.apply(Action.Discard(2, Tiles.GREEN))
        respondClaims(game, 1, Action.Peng(1, Tiles.GREEN))
        game.apply(Action.Discard(1, Tiles.tileOf(1, 6)))
        passClaims(game)

        game.apply(Action.Discard(2, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(3, Tiles.WHITE))
        respondClaims(game, 1, Action.Peng(1, Tiles.WHITE))
        game.apply(Action.Discard(1, Tiles.tileOf(1, 7)))
        passClaims(game)

        game.apply(Action.Discard(2, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(3, Tiles.NORTH))
        passClaims(game)

        // 第 4 副由 P0 打出：十二张落地更晚发生，不应覆盖先手包牌。
        game.apply(Action.Discard(0, Tiles.tileOf(0, 1)))
        respondClaims(game, 1, Action.Peng(1, Tiles.tileOf(0, 1)))
        game.apply(Action.Discard(1, Tiles.tileOf(1, 8)))
        passClaims(game)

        game.apply(Action.Discard(2, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(3, Tiles.NORTH))
        passClaims(game)
        game.apply(Action.Discard(0, Tiles.EAST))
        passClaims(game)

        game.apply(Action.Hu(1))
        val result = assertNotNull(game.gameResult)
        val points = result.winners.single().points

        assertTrue(!result.draw)
        assertEquals(1, result.winners.single().winner)
        assertEquals(0, result.deltaScores[0])
        assertEquals(3 * points, result.deltaScores[1])
        assertEquals(0, result.deltaScores[2])
        assertEquals(-3 * points, result.deltaScores[3])
        assertEquals(0, result.deltaScores.sum())
    }

    // ---------------------------------------------------------------- 工具

    private fun respondClaims(game: MahjongGame, target: Int, action: Action) {
        var guard = 0
        while (game.state.phase == Phase.AWAIT_CLAIM && guard++ < 10) {
            val seat = game.actor ?: break
            game.apply(if (seat == target) action else Action.Pass(seat))
        }
    }

    private fun passClaims(game: MahjongGame) {
        var guard = 0
        while (game.state.phase == Phase.AWAIT_CLAIM && guard++ < 10) {
            game.apply(Action.Pass(game.actor!!))
        }
    }

    private fun playOut(config: RuleConfig, seed: Long): List<String> {
        val game = MahjongGame(config, seed)
        val log = ArrayList<String>()
        var guard = 0
        while (!game.isOver && guard++ < 3000) {
            val seat = game.actor ?: break
            val actions = game.legalActions(seat)
            if (actions.isEmpty()) break
            log.addAll(game.apply(actions.last()).map { it.toString() })
        }
        log.add(game.gameResult?.toString() ?: "no-result")
        return log
    }

    /**
     * 按发牌顺序构造牌墙：前 13 轮每人一张，然后庄家第 14 张，
     * 之后的位置按 [after] 顺序（索引 53 起）。
     */
    private fun buildWall(
        p0: List<Int>,
        p1: List<Int>,
        p2: List<Int>,
        p3: List<Int>,
        after: List<Int> = emptyList(),
        size: Int = maxOf(120, 53 + after.size),
    ): List<Int> {
        val wall = MutableList(size) { Tiles.NORTH }
        val hands = listOf(p0, p1, p2, p3)
        for (round in 0 until 13) {
            for (seat in 0..3) wall[round * 4 + seat] = hands[seat][round]
        }
        wall[52] = p0[13]
        after.forEachIndexed { index, tile -> wall[53 + index] = tile }
        return wall
    }

    private fun m(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(0, it) }
    private fun p(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(1, it) }
    private fun s(vararg ranks: Int): List<Int> = ranks.map { Tiles.tileOf(2, it) }
}

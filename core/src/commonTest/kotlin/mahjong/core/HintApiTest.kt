package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue
import mahjong.core.api.DiscardHint
import mahjong.core.api.GameSession
import mahjong.core.api.Hints
import mahjong.core.api.WinOpportunity
import mahjong.core.api.WinType
import mahjong.core.model.DiscardInfo
import mahjong.core.model.Meld
import mahjong.core.model.OpponentView
import mahjong.core.model.Phase
import mahjong.core.model.PlayerView
import mahjong.core.rules.RuleConfig
import mahjong.core.tile.Tiles

class HintApiTest {

    // ------------------------------------------------------------ 听牌判断

    @Test
    fun detectsTenpaiAndWaits() {
        val hand = parse("1万 2万 3万 4万 5万 6万 7筒 8筒 9筒 1条 2条 3条 东")
        val view = view(hand, phase = Phase.AWAIT_CLAIM)

        val hint = Hints.tenpaiHint(view)

        assertTrue(hint.tenpai)
        assertEquals(0, hint.shanten)
        assertEquals(listOf(Tiles.EAST), hint.waits.map { it.tile })
        assertTrue(hint.totalRemaining > 0)
    }

    @Test
    fun detectsNotTenpai() {
        val hand = parse("1万 4万 7万 2筒 5筒 8筒 3条 6条 9条 东 南 西 北")
        val hint = Hints.tenpaiHint(view(hand, phase = Phase.AWAIT_CLAIM))

        assertFalse(hint.tenpai)
        assertTrue(hint.waits.isEmpty())
    }

    // ------------------------------------------------------------ 剩余张数

    @Test
    fun remainingCountsIncludeOwnAndPublicDiscards() {
        val tile3m = Tiles.parse("3万")!!
        val hand = parse("1万 2万 4万 5万 6万 7筒 8筒 9筒 1条 2条 3条 东 东")
        val opponent = OpponentView(
            seat = 1,
            handCount = 13,
            melds = emptyList(),
            discards = listOf(tile3m),
            flowerCount = 0,
            score = 100,
            seatWind = 1,
        )

        // 自己打过一张 + 对家打过一张 → 还剩 2 张。
        val hint = Hints.tenpaiHint(
            view(hand, opponents = listOf(opponent), ownDiscards = listOf(tile3m), phase = Phase.AWAIT_CLAIM),
        )
        val wait = hint.waits.first { it.tile == tile3m }
        assertEquals(2, wait.remaining)
    }

    @Test
    fun remainingCountsIncludeExposedMelds() {
        val tile3m = Tiles.parse("3万")!!
        val hand = parse("1万 2万 4万 5万 6万 7筒 8筒 9筒 1条 2条 3条 东 东")
        val opponent = OpponentView(
            seat = 1,
            handCount = 10,
            melds = listOf(Meld.pung(tile3m, 2)),
            discards = emptyList(),
            flowerCount = 0,
            score = 100,
            seatWind = 1,
        )

        // 对家碰了 3 万（3 张已现）+ 自己打过 1 张 → 剩余 0 张。
        val hint = Hints.tenpaiHint(
            view(hand, opponents = listOf(opponent), ownDiscards = listOf(tile3m), phase = Phase.AWAIT_CLAIM),
        )
        val wait = hint.waits.first { it.tile == tile3m }
        assertEquals(0, wait.remaining)
    }

    // ------------------------------------------------------------ 国标起和门槛

    @Test
    fun mcrTenpaiBelowThresholdIsDistinguished() {
        // 箭刻 2 + 单钓将 1 + 不求人 4 = 7 番，低于国标 8 分起和。
        val hand = parse("1万 2万 3万 5万 6万 7万 7筒 8筒 9筒 中 中 中 5条")
        val view = view(hand, config = RuleConfig.mcr(), phase = Phase.AWAIT_CLAIM)

        val hint = Hints.tenpaiHint(view)

        assertTrue(hint.tenpai)
        val wait = hint.waits.first { it.tile == Tiles.parse("5条") }
        val selfDraw = assertNotNull(wait.selfDraw)
        assertTrue(selfDraw.fan in 1..7, "fan=${selfDraw.fan}")
        assertFalse(selfDraw.meetsMinimum)
        assertFalse(hint.meetsMinimum)
    }

    @Test
    fun mcrBelowThresholdDoesNotOfferHuButton() {
        val tile5s = Tiles.parse("5条")!!
        val hand = parse("1万 2万 3万 5万 6万 7万 7筒 8筒 9筒 中 中 中 5条")
        val view = view(
            hand,
            config = RuleConfig.mcr(),
            phase = Phase.AWAIT_CLAIM,
            lastDiscard = DiscardInfo(player = 1, tile = tile5s, turn = 3, lastTileOfWall = false),
        )

        assertTrue(Hints.winHints(view).isEmpty())
    }

    // ------------------------------------------------------------ 广东封顶

    @Test
    fun guangdongReportsCappedFan() {
        // 碰碰胡 2 + 箭刻 1 + 自摸 1 = 4，推倒胡封顶 3 番。
        val hand = parse("1万 1万 1万 2万 2万 2万 3筒 3筒 3筒 中 中 中 5条")
        val view = view(hand, config = RuleConfig.guangdongTuidaohu(), phase = Phase.AWAIT_CLAIM)

        val hint = Hints.tenpaiHint(view)
        assertTrue(hint.tenpai)
        val wait = hint.waits.first { it.tile == Tiles.parse("5条") }
        val selfDraw = assertNotNull(wait.selfDraw)

        assertEquals(3, selfDraw.fan)
        assertEquals(8, selfDraw.points) // 2^3
        assertTrue(selfDraw.fanItems.any { it.id == "gd_self_draw" })
        // 推倒胡只允许自摸，不提供点炮预览。
        assertNull(wait.discard)
    }

    // ------------------------------------------------------------ 封顶配置

    @Test
    fun presetFanCapsAreExposedWithoutHardcoding() {
        val mcr = RuleConfig.mcr()
        val tuidaohu = RuleConfig.guangdongTuidaohu()
        val jipinghu = RuleConfig.guangdongJipinghu()

        // maxFanCap 字段：三套预设都是 3；但国标不封顶，实际生效值为 null。
        assertEquals(3, mcr.maxFanCap)
        assertNull(mcr.effectiveFanCap)
        assertEquals(3, tuidaohu.effectiveFanCap)
        assertEquals(3, jipinghu.effectiveFanCap)

        // FanPreview 同样能拿到：国标 null，广东 3。
        val mcrView = view(
            parse("1万 2万 3万 5万 6万 7万 7筒 8筒 9筒 中 中 中 5条"),
            config = mcr,
            phase = Phase.AWAIT_CLAIM,
        )
        assertNull(Hints.tenpaiHint(mcrView).bestPreview?.fanCap)

        val gdView = view(
            parse("1万 1万 1万 2万 2万 2万 3筒 3筒 3筒 中 中 中 5条"),
            config = tuidaohu,
            phase = Phase.AWAIT_CLAIM,
        )
        assertEquals(3, Hints.tenpaiHint(gdView).bestPreview?.fanCap)
    }

    // ------------------------------------------------------------ 出牌建议

    @Test
    fun discardHintsListTenpaiDiscards() {
        val hand = parse("1万 2万 3万 4万 5万 6万 7万 8万 9万 1筒 2筒 3筒 5条 5条")
        val view = view(
            hand,
            config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false),
            currentPlayer = 0,
        )

        val hints = Hints.discardHints(view)
        assertEquals(hand.distinct().size, hints.size)
        assertEquals(hints.map { it.tile }.sorted(), hints.map { it.tile })

        val best = hints.filter { it.tenpai }.maxByOrNull { it.totalRemaining }
        val chosen: DiscardHint = assertNotNull(best)
        assertTrue(chosen.waits.isNotEmpty())
        assertTrue(chosen.totalRemaining >= chosen.waits.size)

        // 打掉 5 条后听 5 条，剩余张数 = 4 - 手牌中已见的 1 张 = 3。
        val fiveS = Tiles.parse("5条")!!
        val wait = chosen.waits.firstOrNull { it.tile == fiveS }
        if (chosen.tile == fiveS) {
            assertEquals(3, assertNotNull(wait).remaining)
        }
    }

    // ------------------------------------------------------------ 可胡提示

    @Test
    fun selfDrawWinIsReported() {
        val hand = parse("1万 1万 1万 2万 2万 2万 3筒 3筒 3筒 中 中 中 5条 5条")
        val view = view(
            hand,
            config = RuleConfig.guangdongTuidaohu(),
            lastDrawnTile = Tiles.parse("5条"),
            canSelfDrawWin = true,
            currentPlayer = 0,
        )

        val wins = Hints.winHints(view)
        assertEquals(1, wins.size)
        val win = wins.first()
        assertEquals(WinType.SELF_DRAW, win.type)
        assertNull(win.fromPlayer)
        assertEquals(3, win.preview.fan)
        assertTrue(win.preview.fanItems.any { it.id == "gd_self_draw" })
    }

    @Test
    fun robKongOpportunityIsReported() {
        val tile5s = Tiles.parse("5条")!!
        val hand = parse("1万 1万 1万 2万 2万 2万 3筒 3筒 3筒 中 中 中 5条")
        val view = view(
            hand,
            config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false),
            phase = Phase.AWAIT_ROB_KONG,
        )

        val wins = Hints.winHints(
            view,
            listOf(WinOpportunity(WinType.ROB_KONG, tile5s, fromPlayer = 1)),
        )

        assertEquals(1, wins.size)
        val win = wins.first()
        assertEquals(WinType.ROB_KONG, win.type)
        assertEquals(1, win.fromPlayer)
        assertEquals(3, win.preview.fan)
        assertTrue(win.preview.fanItems.any { it.id == "gd_rob_kong" })
    }

    // ------------------------------------------------------------ 纯查询

    @Test
    fun queriesDoNotMutateGameState() {
        val session = GameSession(
            config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false),
            seed = 7L,
        )
        val beforeSnapshot = session.snapshot
        val beforeView = session.viewFor(0)
        val beforeLegal = session.legalActions(0)
        val beforeActor = session.actor

        repeat(2) {
            Hints.analyze(session.viewFor(0))
            Hints.discardHints(session.viewFor(0))
            Hints.tenpaiHint(session.viewFor(0))
            Hints.winHints(session.viewFor(0))
        }

        assertEquals(beforeSnapshot, session.snapshot)
        assertEquals(beforeView, session.viewFor(0))
        assertEquals(beforeLegal, session.legalActions(0))
        assertEquals(beforeActor, session.actor)
        assertTrue(session.actionLog().isEmpty())
    }

    // ------------------------------------------------------------ 性能

    @Test
    fun analyzeIsFastEnoughForUi() {
        val cases = listOf(
            "整齐手牌" to parse("1万 2万 3万 4万 5万 6万 7万 8万 9万 1筒 2筒 3筒 5条 5条"),
            "散牌手牌" to parse("1万 2万 4万 5万 7万 8万 2筒 3筒 5筒 6筒 3条 5条 7条 东"),
        )
        val runs = 50
        for ((name, hand) in cases) {
            val view = view(hand, config = RuleConfig.mcr(), currentPlayer = 0)
            repeat(20) { Hints.analyze(view) } // 预热 JIT
            val timed = measureTimedValue { repeat(runs) { Hints.analyze(view) } }
            val avgMs = timed.duration.inWholeMicroseconds / 1000.0 / runs
            println("Hints.analyze benchmark[$name]: avg=${avgMs}ms (runs=$runs)")
            assertTrue(avgMs < 50.0, "analyze too slow: ${name} ${avgMs}ms")
        }
    }

    // ------------------------------------------------------------ 工具

    private fun parse(text: String): List<Int> = Tiles.parseList(text)

    private fun view(
        hand: List<Int>,
        melds: List<Meld> = emptyList(),
        flowers: List<Int> = emptyList(),
        opponents: List<OpponentView> = emptyList(),
        ownDiscards: List<Int> = emptyList(),
        config: RuleConfig = RuleConfig.mcr(),
        phase: Phase = Phase.AWAIT_DISCARD,
        currentPlayer: Int = 0,
        wallRemaining: Int = 50,
        lastDiscard: DiscardInfo? = null,
        lastDrawnTile: Int? = null,
        canSelfDrawWin: Boolean = false,
        seat: Int = 0,
        seatWind: Int = 0,
        roundWind: Int = 0,
        dealer: Int = 0,
    ): PlayerView = PlayerView(
        seat = seat,
        hand = hand,
        melds = melds,
        flowers = flowers,
        opponents = opponents,
        phase = phase,
        currentPlayer = currentPlayer,
        wallRemaining = wallRemaining,
        lastDiscard = lastDiscard,
        seatWind = seatWind,
        roundWind = roundWind,
        dealer = dealer,
        lastDrawnTile = lastDrawnTile,
        lastDrawWasKongReplacement = false,
        canSelfDrawWin = canSelfDrawWin,
        config = config,
        ownDiscards = ownDiscards,
    )
}

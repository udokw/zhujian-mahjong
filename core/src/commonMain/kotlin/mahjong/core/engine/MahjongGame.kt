package mahjong.core.engine

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.model.Action
import mahjong.core.model.DiscardInfo
import mahjong.core.model.GameEvent
import mahjong.core.model.GameResult
import mahjong.core.model.GameState
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.model.Phase
import mahjong.core.model.PlayerState
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.score.ScoreEngine
import mahjong.core.score.WinContext
import mahjong.core.score.WinResult
import mahjong.core.tile.Tiles

/**
 * 国标 / 广东共用的牌局状态机。
 *
 * 对外只暴露：
 * - [state] 当前状态快照（UI 可消费）
 * - [legalActions] 某个座位当前可执行的动作
 * - [apply] 执行动作并返回产生的事件
 * - [actor] 当前需要做决定的座位
 *
 * 所有随机性都来自 [seed]，同一 seed + 同一动作序列必然产生同一局。
 */
class MahjongGame(
    val config: RuleConfig,
    seed: Long,
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val dealerStreak: Int = 0,
    initialScores: List<Int>? = null,
    /** 测试/回放用：传入已排好的牌墙则不再洗牌。 */
    wallTiles: List<Int>? = null,
) {
    init {
        require(dealer in 0..3)
        require(roundWind in 0..3)
        require(dealerStreak >= 0)
    }

    private val rng = Rng(seed)
    private val wall: Wall
    private val hands = Array(4) { mutableListOf<Int>() }
    private val melds = Array(4) { mutableListOf<Meld>() }
    private val discards = Array(4) { mutableListOf<Int>() }
    private val flowers = Array(4) { mutableListOf<Int>() }
    private val passedTiles = Array(4) { mutableSetOf<Int>() }
    private val initialScores: List<Int> = initialScores ?: List(4) { config.startingScore }
    private val gangDeltas = MutableList(4) { 0 }
    private val horses = mutableListOf<Int>()

    private var phase: Phase = Phase.AWAIT_DISCARD
    private var currentPlayer: Int = dealer
    private var turnCounter: Int = 0
    private var lastDiscard: DiscardInfo? = null
    private var lastDrawnTile: Int? = null
    private var lastDrawWasKongReplacement = false
    private var lastDrawWallEnd = false

    private var pendingClaims: List<Int> = emptyList()
    private val claimResponses = HashMap<Int, Action>()

    private var pendingRobKong: List<Int> = emptyList()
    private val robResponses = HashMap<Int, Action>()
    private var robbedKongPlayer: Int? = null
    private var robbedKongTile: Int? = null
    private var pendingAddedKongMeldIndex: Int = -1
    private var exposedKongSource: Int? = null

    private var result: GameResult? = null
    private val events = mutableListOf<GameEvent>()

    /** 副露触发的包牌责任，只保留每个赢家最早的一次（包先不包后）。 */
    private data class BaoRecord(val winner: Int, val payer: Int, val turn: Int)
    private val baoRecords = mutableListOf<BaoRecord>()

    init {
        val tiles = (wallTiles ?: config.buildTileSet()).toMutableList()
        if (wallTiles == null) rng.shuffle(tiles)
        wall = Wall(tiles)

        repeat(13) {
            for (s in 0..3) {
                hands[s].add(wall.drawHead()!!)
            }
        }
        hands[dealer].add(wall.drawHead()!!)

        val horseCount = config.horseCountFor(dealerStreak)
        repeat(horseCount) {
            // 花牌不能当马，从牌尾跳过。
            var tile = wall.removeTail()
            while (tile != null && Tiles.isFlower(tile)) tile = wall.removeTail()
            tile?.let { horses.add(it) }
        }

        addEvent(GameEvent.HandStarted(0, dealer, roundWind, wall.remaining))
        for (s in 0..3) addEvent(GameEvent.Dealt(0, s, hands[s].size))

        for (i in 0..3) {
            if (phase == Phase.GAME_OVER) break
            replaceInitialFlowers((dealer + i) % 4)
        }
        if (phase != Phase.GAME_OVER) {
            currentPlayer = dealer
            phase = Phase.AWAIT_DISCARD
            lastDrawnTile = hands[dealer].lastOrNull()
        }
    }

    // ------------------------------------------------------------ 对外接口

    val state: GameState
        get() {
            val selfWin = if (phase == Phase.AWAIT_DISCARD && result == null) {
                if (canHuSelf(currentPlayer) != null) setOf(currentPlayer) else emptySet()
            } else {
                emptySet()
            }
            return GameState(
                config = config,
                phase = phase,
                currentPlayer = currentPlayer,
                dealer = dealer,
                roundWind = roundWind,
                players = (0..3).map { s ->
                    PlayerState(
                        seat = s,
                        hand = hands[s].sorted(),
                        melds = melds[s].toList(),
                        discards = discards[s].toList(),
                        flowers = flowers[s].toList(),
                        score = initialScores[s] + gangDeltas[s],
                        seatWind = (s - dealer + 4) % 4,
                    )
                },
                wallRemaining = wall.remaining,
                lastDiscard = lastDiscard,
                turn = turnCounter,
                result = result,
                dealerStreak = dealerStreak,
                lastDrawnTile = lastDrawnTile,
                lastDrawWasKongReplacement = lastDrawWasKongReplacement,
                canSelfDrawWinSeats = selfWin,
                pendingClaimSeats = if (phase == Phase.AWAIT_CLAIM) pendingClaims else emptyList(),
                pendingRobKongSeats = if (phase == Phase.AWAIT_ROB_KONG) pendingRobKong else emptyList(),
                robbedKongPlayer = robbedKongPlayer,
                robbedKongTile = robbedKongTile,
            )
        }

    val isOver: Boolean get() = result != null || phase == Phase.GAME_OVER

    val gameResult: GameResult? get() = result

    /** 当前需要做决定的座位；无人需要决定时返回 null。 */
    val actor: Int?
        get() = when (phase) {
            Phase.AWAIT_DISCARD -> currentPlayer
            Phase.AWAIT_CLAIM -> pendingClaims.firstOrNull { it !in claimResponses }
            Phase.AWAIT_ROB_KONG -> pendingRobKong.firstOrNull { it !in robResponses }
            Phase.GAME_OVER -> null
        }

    fun viewFor(seat: Int) = state.viewFor(seat)

    fun drainEvents(): List<GameEvent> {
        val copy = events.toList()
        events.clear()
        return copy
    }

    fun legalActions(seat: Int): List<Action> {
        if (result != null) return emptyList()
        return when (phase) {
            Phase.AWAIT_DISCARD -> if (seat == currentPlayer) ownTurnActions(seat) else emptyList()
            Phase.AWAIT_CLAIM -> if (seat in pendingClaims && seat !in claimResponses) {
                claimActions(seat, lastDiscard!!)
            } else {
                emptyList()
            }
            Phase.AWAIT_ROB_KONG -> if (seat in pendingRobKong && seat !in robResponses) {
                buildList {
                    if (canHuOn(seat, robbedKongTile!!, robbedKongPlayer!!, robKong = true) != null) {
                        add(Action.Hu(seat))
                    }
                    add(Action.Pass(seat))
                }
            } else {
                emptyList()
            }
            Phase.GAME_OVER -> emptyList()
        }
    }

    fun apply(action: Action): List<GameEvent> {
        val before = events.size
        require(result == null) { "game is already over" }
        // 统一入口校验：任何不在该座位合法动作里的输入都直接拒绝，不静默忽略。
        require(legalActions(action.player).any { sameAction(it, action) }) {
            "illegal action: $action (phase=$phase, actor=${actor})"
        }
        turnCounter++
        when (phase) {
            Phase.AWAIT_DISCARD -> applyOwnTurn(action)
            Phase.AWAIT_CLAIM -> applyClaim(action)
            Phase.AWAIT_ROB_KONG -> applyRobKong(action)
            Phase.GAME_OVER -> error("game is over")
        }
        return events.subList(before, events.size).toList()
    }

    // ------------------------------------------------------------ 自己回合

    private fun ownTurnActions(seat: Int): List<Action> {
        val acts = ArrayList<Action>()
        if (canHuSelf(seat) != null) acts.add(Action.Hu(seat))
        if (config.allowKong) {
            val counts = HandAnalyzer.counts(hands[seat])
            for (t in 0..33) {
                if (counts[t] == 4) acts.add(Action.ConcealedKong(seat, t))
            }
            for (m in melds[seat]) {
                if (m.type == MeldType.PUNG && hands[seat].contains(m.baseTile)) {
                    acts.add(Action.AddedKong(seat, m.baseTile))
                }
            }
        }
        for (t in hands[seat].distinct().sorted()) acts.add(Action.Discard(seat, t))
        return acts
    }

    private fun applyOwnTurn(action: Action) {
        require(action.player == currentPlayer) { "not this player's turn" }
        when (action) {
            is Action.Hu -> {
                val win = canHuSelf(currentPlayer) ?: error("illegal self-draw win")
                settleWins(listOf(win), "自摸")
            }
            is Action.Discard -> {
                require(hands[currentPlayer].contains(action.tile)) { "tile not in hand" }
                performDiscard(currentPlayer, action.tile)
            }
            is Action.ConcealedKong -> {
                require(hands[currentPlayer].count { it == action.tile } == 4)
                hands[currentPlayer].removeAll { it == action.tile }
                melds[currentPlayer].add(Meld.concealedKong(action.tile))
                addEvent(GameEvent.KongDeclared(turnCounter, currentPlayer, action.tile, MeldType.KONG_CONCEALED, false))
                payGang(currentPlayer, MeldType.KONG_CONCEALED, -1)
                exposedKongSource = null
                drawKongReplacement(currentPlayer)
            }
            is Action.AddedKong -> declareAddedKong(currentPlayer, action.tile)
            else -> error("illegal action in own turn: $action")
        }
    }

    private fun performDiscard(seat: Int, tile: Int) {
        hands[seat].remove(tile)
        discards[seat].add(tile)
        val info = DiscardInfo(seat, tile, turnCounter, wall.isEmpty)
        lastDiscard = info
        lastDrawnTile = null
        lastDrawWasKongReplacement = false
        lastDrawWallEnd = false
        addEvent(GameEvent.TileDiscarded(turnCounter, seat, tile, info.lastTileOfWall))
        openClaimWindow(info)
    }

    // ------------------------------------------------------------ 吃碰杠胡响应

    private fun openClaimWindow(discard: DiscardInfo) {
        val claimants = (1..3)
            .map { (discard.player + it) % 4 }
            .filter { claimActionsWithoutPass(it, discard).isNotEmpty() }
        if (claimants.isEmpty()) {
            advanceAfterDiscard()
            return
        }
        pendingClaims = claimants
        claimResponses.clear()
        phase = Phase.AWAIT_CLAIM
        addEvent(GameEvent.ClaimWindowOpened(turnCounter, discard.player, discard.tile, claimants))
    }

    private fun claimActionsWithoutPass(seat: Int, discard: DiscardInfo): List<Action> {
        val tile = discard.tile
        val hand = hands[seat]
        val acts = ArrayList<Action>(4)
        if (canHuOn(seat, tile, discard.player, robKong = false) != null) acts.add(Action.Hu(seat))
        if (hand.count { it == tile } >= 2) acts.add(Action.Peng(seat, tile))
        if (config.allowKong && hand.count { it == tile } >= 3) acts.add(Action.ExposedKong(seat, tile))
        if (config.allowChi && seat == (discard.player + 1) % 4) {
            chiOptions(hand, tile).forEach { pair -> acts.add(Action.Chi(seat, pair, tile)) }
        }
        return acts
    }

    private fun claimActions(seat: Int, discard: DiscardInfo): List<Action> =
        claimActionsWithoutPass(seat, discard) + Action.Pass(seat)

    private fun applyClaim(action: Action) {
        val seat = action.player
        require(seat in pendingClaims && seat !in claimResponses) { "player is not a pending claimant" }
        val legal = claimActions(seat, lastDiscard!!)
        require(legal.any { sameAction(it, action) }) { "illegal claim action: $action" }
        claimResponses[seat] = action
        if (pendingClaims.all { it in claimResponses }) {
            resolveClaims()
        }
    }

    private fun resolveClaims() {
        val discard = lastDiscard!!
        val tile = discard.tile
        val from = discard.player

        val huWinners = pendingClaims
            .filter { claimResponses[it] is Action.Hu }
            .sortedBy { (it - from + 4) % 4 }

        if (huWinners.isNotEmpty()) {
            val selected = if (config.multipleWinnersOnDiscard) huWinners else huWinners.take(1)
            val wins = selected.mapNotNull { canHuOn(it, tile, from, robKong = false) }
            if (wins.isNotEmpty()) {
                settleWins(wins, if (wins.size > 1) "一炮多响" else "点炮")
                return
            }
        }

        // 漏胡：本可以胡却选择过，记录该牌直到自己摸牌/吃碰杠。
        if (config.missedWinRule) {
            for (s in pendingClaims) {
                if (claimResponses[s] is Action.Pass &&
                    canHuOn(s, tile, from, robKong = false) != null
                ) {
                    passedTiles[s].add(tile)
                }
            }
        }

        val chosen = pendingClaims
            .mapNotNull { s ->
                when (val a = claimResponses[s]) {
                    is Action.Peng, is Action.ExposedKong, is Action.Chi -> s to a
                    else -> null
                }
            }
            .minWithOrNull(compareBy({ claimTier(it.second) }, { (it.first - from + 4) % 4 }))

        if (chosen == null) {
            pendingClaims = emptyList()
            claimResponses.clear()
            advanceAfterDiscard()
            return
        }

        val (claimer, claim) = chosen
        pendingClaims = emptyList()
        claimResponses.clear()
        discards[from].removeAt(discards[from].lastIndex)

        when (claim) {
            is Action.Peng -> {
                repeat(2) { hands[claimer].remove(tile) }
                melds[claimer].add(Meld.pung(tile, from))
                addEvent(GameEvent.MeldFormed(turnCounter, claimer, melds[claimer].last()))
                recordMeldBao(claimer, from)
                afterMeldClaim(claimer)
            }
            is Action.ExposedKong -> {
                repeat(3) { hands[claimer].remove(tile) }
                melds[claimer].add(Meld.exposedKong(tile, from))
                addEvent(GameEvent.KongDeclared(turnCounter, claimer, tile, MeldType.KONG_EXPOSED, false))
                recordMeldBao(claimer, from)
                payGang(claimer, MeldType.KONG_EXPOSED, from)
                exposedKongSource = from
                drawKongReplacement(claimer)
            }
            is Action.Chi -> {
                for (t in claim.tiles) hands[claimer].remove(t)
                melds[claimer].add(Meld.chow(claim.tiles + tile, from, tile))
                addEvent(GameEvent.MeldFormed(turnCounter, claimer, melds[claimer].last()))
                recordMeldBao(claimer, from)
                afterMeldClaim(claimer)
            }
            else -> error("unexpected claim")
        }
    }

    private fun afterMeldClaim(claimer: Int) {
        currentPlayer = claimer
        phase = Phase.AWAIT_DISCARD
        lastDrawnTile = null
        lastDrawWasKongReplacement = false
        lastDrawWallEnd = false
        exposedKongSource = null
        passedTiles[claimer].clear()
    }

    // ------------------------------------------------------------ 抢杠

    private fun declareAddedKong(seat: Int, tile: Int) {
        val meldIndex = melds[seat].indexOfFirst { it.type == MeldType.PUNG && it.baseTile == tile }
        require(meldIndex >= 0 && hands[seat].contains(tile)) { "no pung to add kong" }

        val robbers = if (config.allowRobKong) {
            (1..3).map { (seat + it) % 4 }
                .filter { canHuOn(it, tile, seat, robKong = true) != null }
        } else {
            emptyList()
        }

        if (robbers.isNotEmpty()) {
            pendingRobKong = robbers
            robResponses.clear()
            robbedKongPlayer = seat
            robbedKongTile = tile
            pendingAddedKongMeldIndex = meldIndex
            phase = Phase.AWAIT_ROB_KONG
            addEvent(GameEvent.RobKongWindowOpened(turnCounter, seat, tile, robbers))
        } else {
            completeAddedKong(seat, tile, meldIndex)
        }
    }

    private fun applyRobKong(action: Action) {
        val seat = action.player
        require(seat in pendingRobKong && seat !in robResponses)
        robResponses[seat] = action
        if (pendingRobKong.all { it in robResponses }) {
            val winners = pendingRobKong
                .filter { robResponses[it] is Action.Hu }
                .sortedBy { (it - robbedKongPlayer!! + 4) % 4 }
            val selected = if (config.multipleWinnersOnDiscard) winners else winners.take(1)
            val wins = selected.mapNotNull {
                canHuOn(it, robbedKongTile!!, robbedKongPlayer!!, robKong = true)
            }
            pendingRobKong = emptyList()
            robResponses.clear()
            if (wins.isNotEmpty()) {
                settleWins(wins, "抢杠")
            } else {
                val konger = robbedKongPlayer!!
                val tile = robbedKongTile!!
                robbedKongPlayer = null
                robbedKongTile = null
                completeAddedKong(konger, tile, pendingAddedKongMeldIndex)
            }
        }
    }

    private fun completeAddedKong(seat: Int, tile: Int, meldIndex: Int) {
        hands[seat].remove(tile)
        val old = melds[seat][meldIndex]
        melds[seat][meldIndex] = Meld(MeldType.KONG_ADDED, List(4) { tile }, old.claimedFrom, tile)
        addEvent(GameEvent.KongDeclared(turnCounter, seat, tile, MeldType.KONG_ADDED, false))
        payGang(seat, MeldType.KONG_ADDED, -1)
        exposedKongSource = null
        pendingAddedKongMeldIndex = -1
        drawKongReplacement(seat)
    }

    // ------------------------------------------------------------ 摸牌与补花

    private fun advanceAfterDiscard() {
        if (wall.isEmpty) {
            endDraw("荒庄：牌墙摸完")
            return
        }
        val next = ((lastDiscard?.player ?: currentPlayer) + 1) % 4
        drawFor(next)
    }

    private fun drawFor(seat: Int) {
        if (wall.isEmpty) {
            endDraw("荒庄：牌墙摸完")
            return
        }
        currentPlayer = seat
        phase = Phase.AWAIT_DISCARD
        lastDiscard = null
        exposedKongSource = null
        val tile = drawWithReplacement(seat, fromTail = false, kongReplacement = false)
        if (tile == null) return
        hands[seat].add(tile)
        lastDrawnTile = tile
        passedTiles[seat].clear()
    }

    private fun drawKongReplacement(seat: Int) {
        if (wall.isEmpty) {
            endDraw("荒庄：杠后无牌可补")
            return
        }
        currentPlayer = seat
        phase = Phase.AWAIT_DISCARD
        lastDiscard = null
        val tile = drawWithReplacement(seat, fromTail = true, kongReplacement = true)
        if (tile == null) return
        hands[seat].add(tile)
        lastDrawnTile = tile
        passedTiles[seat].clear()
    }

    /**
     * 从牌墙取一张；若为花牌则自动补花（从牌尾），直到取到非花牌。
     * 返回 null 表示牌墙摸完、牌局结束。
     */
    private fun drawWithReplacement(seat: Int, fromTail: Boolean, kongReplacement: Boolean): Int? {
        var useTail = fromTail
        var isKongReplacement = kongReplacement
        while (true) {
            val tile = if (useTail) wall.drawTail() else wall.drawHead()
            if (tile == null) {
                endDraw("荒庄：牌墙摸完")
                return null
            }
            val wallEnd = wall.isEmpty
            if (Tiles.isFlower(tile)) {
                flowers[seat].add(tile)
                addEvent(GameEvent.FlowerRevealed(turnCounter, seat, tile))
                // 杠后补到花，补花成和只算自摸。
                isKongReplacement = false
                useTail = true
                continue
            }
            lastDrawWasKongReplacement = isKongReplacement
            lastDrawWallEnd = wallEnd
            addEvent(
                GameEvent.TileDrawn(
                    turn = turnCounter,
                    seat = seat,
                    tile = tile,
                    fromTail = useTail,
                    kongReplacement = isKongReplacement,
                    flowerReplacement = useTail && !fromTail,
                )
            )
            return tile
        }
    }

    private fun replaceInitialFlowers(seat: Int) {
        var i = 0
        while (i < hands[seat].size) {
            val tile = hands[seat][i]
            if (!Tiles.isFlower(tile)) {
                i++
                continue
            }
            hands[seat].removeAt(i)
            flowers[seat].add(tile)
            addEvent(GameEvent.FlowerRevealed(0, seat, tile))
            val replacement = wall.drawTail() ?: run {
                endDraw("荒庄：补花时牌墙摸完")
                return
            }
            hands[seat].add(replacement)
        }
    }

    // ------------------------------------------------------------ 和牌判定

    private fun canHuSelf(seat: Int): WinResult? {
        if (!config.allowSelfDrawWin) return null
        val winTile = lastDrawnTile ?: return null
        val hand = hands[seat]
        if (!HandAnalyzer.isWinningHand(hand, melds[seat])) return null
        val ctx = winContext(
            seat = seat,
            selfDraw = true,
            winningTile = winTile,
            fromPlayer = null,
            robKong = false,
            kongReplacement = lastDrawWasKongReplacement,
            lastTile = lastDrawWallEnd,
        )
        return ScoreEngine.score(config, hand, melds[seat], ctx)
    }

    private fun canHuOn(seat: Int, tile: Int, from: Int, robKong: Boolean): WinResult? {
        if (!robKong && !config.allowDiscardWin) return null
        if (config.missedWinRule && tile in passedTiles[seat]) return null
        val hand = hands[seat] + tile
        if (!HandAnalyzer.isWinningHand(hand, melds[seat])) return null
        val ctx = winContext(
            seat = seat,
            selfDraw = false,
            winningTile = tile,
            fromPlayer = from,
            robKong = robKong,
            kongReplacement = false,
            lastTile = if (robKong) false else (lastDiscard?.lastTileOfWall ?: false),
        )
        return ScoreEngine.score(config, hand, melds[seat], ctx)
    }

    private fun winContext(
        seat: Int,
        selfDraw: Boolean,
        winningTile: Int,
        fromPlayer: Int?,
        robKong: Boolean,
        kongReplacement: Boolean,
        lastTile: Boolean,
    ): WinContext = WinContext(
        seat = seat,
        selfDraw = selfDraw,
        winningTile = winningTile,
        fromPlayer = fromPlayer,
        robKong = robKong,
        kongReplacement = kongReplacement,
        lastTile = lastTile,
        seatWind = (seat - dealer + 4) % 4,
        roundWind = roundWind,
        flowers = flowers[seat].toList(),
        dealer = dealer,
        visibleTiles = visibleTilesFor(seat),
    )

    /** 场上已公开的牌（不含任何人的暗手牌）。 */
    private fun visibleTilesFor(seat: Int): List<Int> {
        val visible = ArrayList<Int>(80)
        for (s in 0..3) {
            visible.addAll(melds[s].flatMap { it.tiles })
            visible.addAll(discards[s])
            visible.addAll(flowers[s])
        }
        return visible
    }

    // ------------------------------------------------------------ 杠分

    private fun payGang(seat: Int, type: MeldType, source: Int) {
        val unit = config.gangScore
        if (unit <= 0) return
        val total: Int
        when (type) {
            MeldType.KONG_EXPOSED -> {
                total = unit * 3
                if (source >= 0) {
                    gangDeltas[source] -= total
                    gangDeltas[seat] += total
                }
            }
            MeldType.KONG_ADDED -> {
                total = unit * 3
                for (s in 0..3) {
                    if (s == seat) continue
                    gangDeltas[s] -= unit
                    gangDeltas[seat] += unit
                }
            }
            MeldType.KONG_CONCEALED -> {
                total = unit * 6
                for (s in 0..3) {
                    if (s == seat) continue
                    gangDeltas[s] -= unit * 2
                    gangDeltas[seat] += unit * 2
                }
            }
            else -> return
        }
        addEvent(
            GameEvent.GangScored(
                turnCounter, seat, total,
                when (type) {
                    MeldType.KONG_EXPOSED -> "放杠"
                    MeldType.KONG_ADDED -> "明杠"
                    else -> "暗杠"
                },
            )
        )
    }

    // ------------------------------------------------------------ 结算

    private fun settleWins(wins: List<WinResult>, reason: String) {
        val bao = if (config.baoPai) {
            wins.mapNotNull { w -> baoPayerFor(w)?.let { w.winner to it } }.toMap()
        } else {
            emptyMap()
        }
        val gameResult = Settlement.settle(
            config = config,
            winners = wins,
            dealer = dealer,
            roundWind = roundWind,
            dealerStreak = dealerStreak,
            gangDeltas = gangDeltas.toList(),
            horses = horses.toList(),
            baoPayers = bao,
            draw = false,
            reason = reason,
        )
        result = gameResult
        phase = Phase.GAME_OVER
        pendingClaims = emptyList()
        pendingRobKong = emptyList()
        wins.forEach { addEvent(GameEvent.WinDeclared(turnCounter, it)) }
        addEvent(GameEvent.GameOver(turnCounter, gameResult))
    }

    private fun endDraw(reason: String) {
        val gameResult = Settlement.settle(
            config = config,
            winners = emptyList(),
            dealer = dealer,
            roundWind = roundWind,
            dealerStreak = dealerStreak,
            gangDeltas = gangDeltas.toList(),
            horses = horses.toList(),
            baoPayers = emptyMap(),
            draw = true,
            reason = reason,
        )
        result = gameResult
        phase = Phase.GAME_OVER
        pendingClaims = emptyList()
        pendingRobKong = emptyList()
        addEvent(GameEvent.DrawGame(turnCounter, reason))
        addEvent(GameEvent.GameOver(turnCounter, gameResult))
    }

    /**
     * 副露形成时登记包牌责任，每个赢家只保留最早的一次（包先不包后）。
     *
     * - 十二张落地：第 4 副副露落地时，打出这张牌的玩家负责该家自摸；
     * - 大三元：第 3 副箭刻落地时，打出这张牌的玩家负责该家自摸；
     * - 大四喜：第 4 副风刻落地时，打出这张牌的玩家负责该家自摸。
     *
     * 条件一旦成立不会因后续摸打而消失，因此保留最早记录即等价于“先包优先”。
     */
    private fun recordMeldBao(seat: Int, from: Int) {
        if (from < 0 || from == seat) return
        if (baoRecords.any { it.winner == seat }) return
        val melds = melds[seat]
        val last = melds.lastOrNull() ?: return
        val allExposed = melds.size == 4 && melds.none { it.isConcealed }
        val dragons = melds.count { !it.isChow && Tiles.isDragon(it.baseTile) }
        val winds = melds.count { !it.isChow && Tiles.isWind(it.baseTile) }
        val thirdDragon = dragons == 3 && !last.isChow && Tiles.isDragon(last.baseTile)
        val fourthWind = winds == 4 && !last.isChow && Tiles.isWind(last.baseTile)
        if (!allExposed && !thirdDragon && !fourthWind) return
        baoRecords.add(BaoRecord(seat, from, turnCounter))
    }

    /**
     * 包牌责任（仅鸡平胡，只影响分数转移，不影响和牌合法性）：
     * - 抢杠：被抢杠者包三家；
     * - 放杠后杠上开花：放杠者包三家；
     * - 十二张落地/大三元/大四喜自摸：取最早触发的责任家（包先不包后）。
     */
    private fun baoPayerFor(win: WinResult): Int? {
        if (win.robKong) return win.fromPlayer
        if (!win.selfDraw) return null
        baoRecords.firstOrNull { it.winner == win.winner }?.let { return it.payer }
        if (lastDrawWasKongReplacement) return exposedKongSource
        return null
    }

    // ------------------------------------------------------------ 工具

    private fun addEvent(event: GameEvent) {
        events.add(event)
    }

    private fun chiOptions(hand: List<Int>, tile: Int): List<List<Int>> {
        if (tile >= 27) return emptyList()
        val rank = Tiles.rank(tile)
        val suit = Tiles.suit(tile)
        fun t(r: Int) = Tiles.tileOf(suit, r)
        val options = ArrayList<List<Int>>(3)
        if (rank >= 3) {
            val a = t(rank - 2)
            val b = t(rank - 1)
            if (hand.count { it == a } > 0 && hand.count { it == b } > 0) options.add(listOf(a, b))
        }
        if (rank in 2..8) {
            val a = t(rank - 1)
            val b = t(rank + 1)
            if (hand.count { it == a } > 0 && hand.count { it == b } > 0) options.add(listOf(a, b))
        }
        if (rank <= 7) {
            val a = t(rank + 1)
            val b = t(rank + 2)
            if (hand.count { it == a } > 0 && hand.count { it == b } > 0) options.add(listOf(a, b))
        }
        return options
    }

    private fun claimTier(action: Action): Int = when (action) {
        is Action.ExposedKong, is Action.Peng -> 0
        is Action.Chi -> 1
        else -> 2
    }

    private fun sameAction(a: Action, b: Action): Boolean = a == b
}

package mahjong.app.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mahjong.app.audio.GameAudio
import mahjong.app.audio.SoundEffect
import mahjong.app.audio.VoiceLine
import mahjong.app.platformVibrate
import mahjong.app.settings.AppSettings
import mahjong.app.util.Clock
import mahjong.core.ai.AiBot
import mahjong.core.api.Hints
import mahjong.core.api.WinOpportunity
import mahjong.core.api.WinType
import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.model.GameEvent
import mahjong.core.model.GameResult
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.model.Phase
import mahjong.core.rules.GameMode
import mahjong.core.score.FanItem
import mahjong.core.rules.RuleConfig
import kotlin.random.Random

private const val CLAIM_WINDOW_MS = 8_000L

/** 听牌锁定后摸打的停顿，避免每巡瞬间闪过、看不清刚摸的牌。 */
private const val TING_LOCK_THINK_MS = 480L

/**
 * 本地人机局：包一层 core 的 MahjongGame + 三个 AiBot，把引擎状态投影成 UI 模型。
 */
class LocalGameSession(
    val config: RuleConfig,
    val level: mahjong.core.ai.AiLevel,
    val meSeat: Int = 0,
    val names: List<String>,
    val handNumber: Int = 1,
    initialScores: List<Int>? = null,
    dealer: Int = 0,
    roundWind: Int = 0,
    dealerStreak: Int = 0,
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val onFinished: (SettlementUi) -> Unit,
) {
    private val game = MahjongGame(
        config = config,
        seed = Random.nextLong(),
        dealer = dealer,
        roundWind = roundWind,
        dealerStreak = dealerStreak,
        initialScores = initialScores,
    )
    private val bots: List<AiBot?> = List(4) { seat ->
        if (seat == meSeat) null else AiBot(level, Random.nextLong())
    }

    private var loopJob: Job? = null
    private var bannerJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisKey: String? = null
    private var autoBot: AiBot? = null
    private var pending: CompletableDeferred<Action>? = null
    private var claimDeadline: Long? = null
    private var finished = false

    var table by mutableStateOf<TableUi?>(null)
        private set

    var banner by mutableStateOf<Banner?>(null)
        private set

    var settlement by mutableStateOf<SettlementUi?>(null)
        private set

    /** 托管：由 AI 代打自己的回合（复用同一套 AiBot）。 */
    var autoPlay by mutableStateOf(false)
        private set

    /** 听牌锁定：听牌后锁定手牌，摸什么打什么，只等胡。与托管互斥。 */
    var tingLocked by mutableStateOf(false)
        private set

    fun toggleAuto() {
        autoPlay = !autoPlay
        if (autoPlay) {
            tingLocked = false
            // 正在等玩家出牌时立刻交给 AI，避免托管后仍卡在等待。
            val legal = game.legalActions(meSeat)
            if (legal.isNotEmpty()) pending?.complete(autoAction(legal))
        }
    }

    /**
     * 切换听牌锁定。只有已经听牌（引擎给出有效听口）才允许进入；
     * 返回 false 表示条件不满足，由界面提示用户。
     */
    fun toggleTingLock(): Boolean {
        if (!tingLocked) {
            val ting = table?.tingInfo
            if (ting == null || ting.waits.isEmpty()) return false
            tingLocked = true
            autoPlay = false
        } else {
            tingLocked = false
        }
        publish(game.legalActions(meSeat))
        return true
    }

    val gameResult: GameResult? get() = game.gameResult
    val currentDealer: Int get() = game.dealer
    val currentRoundWind: Int get() = game.roundWind

    val modeLabel: String
        get() = if (config.mode == GameMode.MCR) "国标麻将" else "广东麻将"

    fun start() {
        if (loopJob != null) return
        tingLocked = false
        autoPlay = false
        // 一局开始给四家随机分配互不相同的人声套数，整局固定。
        GameAudio.beginGame()
        playSfx(SoundEffect.SHUFFLE)
        loopJob = scope.launch {
            // 洗牌音约 1.2s，自带起手/加速/收尾；收尾落牌后再发牌、
            // 理牌，声音走完才开打，避免第一张出牌压住理牌声。
            delay(1_050)
            repeat(13) {
                delay(55)
                playSfx(SoundEffect.DEAL)
            }
            delay(60)
            playSfx(SoundEffect.SORT)
            delay(880)
            runLoop()
        }
    }

    fun cancel() {
        loopJob?.cancel()
        loopJob = null
        analysisJob?.cancel()
        analysisJob = null
        pending?.cancel()
        pending = null
    }

    /**
     * 调试/截图专用：冻结主循环并注入一份静态桌面。
     * mode: claim（胡 · N番 / 过）、multi（一炮多响）、rob（抢杠胡）。
     */
    fun debugInject(mode: String) {
        loopJob?.cancel()
        loopJob = null
        bannerJob?.cancel()
        bannerJob = null
        pending?.cancel()
        pending = null
        val seats = names.mapIndexed { seat, name ->
            SeatUi(
                seat = seat,
                name = name,
                isAI = seat != meSeat,
                seatWind = seat,
                isDealer = seat == 0,
                handCount = 13,
                melds = emptyList(),
                discards = debugDiscards(seat),
                flowers = emptyList(),
                score = 1_000,
                online = false,
            )
        }
        // 真实听牌形：123万 456万 78万 123筒 55筒，单钓 5 筒。
        val hand = listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13, 13)
        val base = TableUi(
            meSeat = meSeat,
            hand = hand,
            drawnTile = null,
            melds = emptyList(),
            flowers = emptyList(),
            seats = seats,
            wallRemaining = 68,
            roundWind = currentRoundWind,
            dealer = currentDealer,
            currentPlayer = meSeat,
            lastDiscardTile = 13,
            lastDiscardSeat = 1,
            claim = null,
            turnOptions = TurnOptions(),
            canDiscard = false,
            hint = "",
            banner = null,
            modeLabel = modeLabel,
            handNumber = handNumber,
            minFan = if (config.mode == GameMode.MCR) 8 else 0,
            huFan = 5,
            huMeetsMin = true,
            hintsAvailable = true,
        )
        table = when (mode) {
            "claim" -> base.copy(
                claim = ClaimUi(
                    options = listOf(ClaimOption.Hu, ClaimOption.Pass),
                    deadline = Clock.now() + 6_000L,
                    totalMs = 8_000L,
                    tile = 13,
                    fromSeat = 1,
                ),
            )

            "multi" -> base.copy(
                winShow = WinShowUi(
                    winners = listOf(debugWinner(0), debugWinner(2)),
                    index = 1,
                    stage = WinStage.FAN,
                    multi = true,
                ),
            )

            "rob" -> base.copy(
                winShow = WinShowUi(
                    winners = listOf(debugWinner(0, robKong = true)),
                    stage = WinStage.FAN,
                ),
            )

            // 截图/量尺寸用：四家各有两组副露（吃/碰/明杠/暗杠/补杠），且四家牌河都是两行。
            "melds" -> {
                val crowded = listOf(
                    listOf(
                        Meld(MeldType.CHOW, listOf(0, 1, 2)),
                        Meld(MeldType.PUNG, listOf(13, 13, 13)),
                    ),
                    listOf(
                        Meld(MeldType.PUNG, listOf(19, 19, 19)),
                        Meld(MeldType.KONG_EXPOSED, listOf(20, 20, 20, 20)),
                    ),
                    listOf(
                        Meld(MeldType.CHOW, listOf(9, 10, 11)),
                        Meld(MeldType.KONG_CONCEALED, listOf(27, 27, 27, 27)),
                    ),
                    listOf(
                        Meld(MeldType.KONG_ADDED, listOf(31, 31, 31, 31)),
                        Meld(MeldType.PUNG, listOf(5, 5, 5)),
                    ),
                )
                val crowdedSeats = seats.mapIndexed { seat, ui ->
                    ui.copy(
                        melds = crowded[seat],
                        handCount = if (seat == meSeat) 7 else 13,
                        discards = (0 until 18).map { (it * 2 + seat) % 34 },
                    )
                }
                base.copy(
                    seats = crowdedSeats,
                    melds = crowded[meSeat],
                    hand = listOf(3, 4, 5, 6, 7, 8, 12),
                )
            }

            // 截图/量尺寸用：中心提示圈（碰/杠/吃/胡）与余牌圈同心验证。
            "banner" -> base.copy(banner = Banner("碰", kind = BannerKind.MELD))

            else -> base
        }
    }

    private fun debugDiscards(seat: Int): List<Int> = when (seat) {
        0 -> listOf(12, 14, 15)
        1 -> listOf(18, 19, 20, 13)
        2 -> listOf(27, 28, 29)
        else -> listOf(30, 31, 32)
    }

    private fun debugWinner(seat: Int, robKong: Boolean = false): WinnerUi {
        val fans = if (robKong) {
            listOf(FanItem("qiangganghu", "抢杠胡", 8))
        } else {
            listOf(
                FanItem("pinghu", "平胡", 1),
                FanItem("duanyaojiu", "断幺九", 1),
                FanItem("menqianqing", "门前清", 2),
                FanItem("zimo", "自摸", 1),
            )
        }
        return WinnerUi(
            seat = seat,
            name = names.getOrElse(seat) { "" },
            selfDraw = false,
            fromSeat = 1,
            winningTile = 13,
            handTiles = listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13, 13, 13),
            melds = emptyList(),
            fans = fans,
            totalFan = fans.sumOf { it.totalFan },
            thresholdFan = if (config.mode == GameMode.MCR) 8 else 0,
            points = 24,
            robKong = robKong,
        )
    }

    // ------------------------------------------------------------ 玩家操作

    fun discard(tile: Int) {
        val state = game.state
        val legal = game.legalActions(meSeat).filterIsInstance<Action.Discard>()
        val match = legal.firstOrNull { it.tile == tile }
        if (state.phase != Phase.AWAIT_DISCARD || state.currentPlayer != meSeat || match == null) {
            invalid()
            return
        }
        feedback(0)
        pending?.complete(match)
    }

    fun claim(option: ClaimOption, chiTiles: List<Int>? = null) {
        val legal = game.legalActions(meSeat)
        val action: Action = when (option) {
            ClaimOption.Hu -> legal.filterIsInstance<Action.Hu>().firstOrNull()
            ClaimOption.Peng -> legal.filterIsInstance<Action.Peng>().firstOrNull()
            ClaimOption.Gang -> legal.filterIsInstance<Action.ExposedKong>().firstOrNull()
            ClaimOption.Pass -> legal.filterIsInstance<Action.Pass>().firstOrNull()
            is ClaimOption.Chi -> {
                val tiles = chiTiles ?: option.choices.firstOrNull() ?: return
                legal.filterIsInstance<Action.Chi>().firstOrNull { it.tiles.sorted() == tiles.sorted() }
            }
        } ?: run {
            invalid()
            return
        }
        when (option) {
            ClaimOption.Hu -> feedback(2)
            ClaimOption.Pass -> feedback(0)
            else -> feedback(1)
        }
        pending?.complete(action)
    }

    fun ownKong(option: KongOption) {
        val legal = game.legalActions(meSeat)
        val action = legal.firstOrNull { sameAction(it, option.action) }
        if (action == null) {
            invalid()
            return
        }
        feedback(1)
        pending?.complete(action)
    }

    fun ownHu() {
        val action = game.legalActions(meSeat).filterIsInstance<Action.Hu>().firstOrNull()
        if (action == null) {
            invalid()
            return
        }
        feedback(2)
        pending?.complete(action)
    }

    // ------------------------------------------------------------ 主循环

    private suspend fun runLoop() {
        publish()
        while (!game.isOver) {
            val actor = game.actor ?: break
            val legal = game.legalActions(actor)
            if (legal.isEmpty()) {
                delay(40)
                continue
            }
            if (actor == meSeat) {
                if (autoPlay) {
                    delay(settings().aiSpeed.thinkMs)
                    applyAction(autoAction(legal))
                    continue
                }
                if (tingLocked) {
                    // 听牌锁定：能胡立刻胡；被叫牌窗口只过不碰杠；自己的回合摸什么打什么。
                    val hu = legal.filterIsInstance<Action.Hu>().firstOrNull()
                    if (hu != null) {
                        delay(TING_LOCK_THINK_MS)
                        applyAction(hu)
                        continue
                    }
                    if (game.state.phase != Phase.AWAIT_DISCARD) {
                        val pass = legal.filterIsInstance<Action.Pass>().firstOrNull()
                        if (pass != null) {
                            applyAction(pass)
                            continue
                        }
                    }
                    val drawn = game.state.lastDrawnTile
                    val discard = legal.filterIsInstance<Action.Discard>()
                        .firstOrNull { it.tile == drawn }
                        ?: legal.filterIsInstance<Action.Discard>().firstOrNull()
                    if (discard != null) {
                        delay(TING_LOCK_THINK_MS)
                        applyAction(discard)
                        continue
                    }
                }
                val isClaim = game.state.phase != Phase.AWAIT_DISCARD
                if (isClaim) {
                    claimDeadline = Clock.now() + CLAIM_WINDOW_MS
                    publish(legal)
                    val action = withTimeoutOrNull(CLAIM_WINDOW_MS) { awaitHuman() }
                        ?: legal.filterIsInstance<Action.Pass>().firstOrNull()
                    claimDeadline = null
                    if (action == null) break
                    applyAction(action)
                } else {
                    claimDeadline = null
                    publish(legal)
                    val action = awaitHuman()
                    applyAction(action)
                }
            } else {
                val speed = settings().aiSpeed
                delay(if (legal.any { it is Action.Discard }) speed.thinkMs else speed.claimMs)
                val bot = bots[actor]
                if (bot == null) {
                    delay(80)
                    continue
                }
                val action = runCatching { bot.choose(game.viewFor(actor), legal) }.getOrNull() ?: legal.first()
                applyAction(action)
            }
        }
        finishIfNeeded()
    }

    /** 托管时替玩家决策：能胡先胡，其余交给同难度的 AI。 */
    private fun autoAction(legal: List<Action>): Action {
        legal.filterIsInstance<Action.Hu>().firstOrNull()?.let { return it }
        val bot = autoBot ?: AiBot(level, Random.nextLong()).also { autoBot = it }
        return runCatching { bot.choose(game.viewFor(meSeat), legal) }.getOrDefault(legal.first())
    }

    private suspend fun awaitHuman(): Action {
        val deferred = CompletableDeferred<Action>()
        pending = deferred
        try {
            return deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    private fun applyAction(action: Action) {
        if (game.isOver) return
        val events = runCatching { game.apply(action) }.getOrElse { return }
        handleEvents(events)
        publish(game.legalActions(meSeat))
    }

    private fun handleEvents(events: List<GameEvent>) {
        // 同一批事件里出现多次胡牌即一炮多响，用更厚的 hu_multi 替代普通胡牌音。
        val multiWin = events.count { it is GameEvent.WinDeclared } > 1
        events.forEachIndexed { index, event ->
            when (event) {
                is GameEvent.TileDrawn -> {
                    // AI 摸打几乎同一帧完成，跳过摸牌音，避免和出牌敲桌声糊在一起；
                    // 真人摸牌后通常会停顿，仍能听到独立的摸牌声。
                    val next = events.getOrNull(index + 1)
                    val sameSeatAction = when (next) {
                        is GameEvent.TileDiscarded -> next.seat == event.seat
                        is GameEvent.KongDeclared -> next.seat == event.seat
                        is GameEvent.WinDeclared -> true
                        else -> false
                    }
                    if (!sameSeatAction) playSfx(SoundEffect.DRAW)
                }

                is GameEvent.TileDiscarded -> {
                    // 四家出牌一致：敲桌声 + 牌名报读，自己的牌也报。
                    playDiscard(event.tile, event.seat)
                }

                is GameEvent.MeldFormed -> {
                    val label = when (event.meld.type) {
                        MeldType.CHOW -> "吃"
                        MeldType.PUNG -> "碰"
                        else -> "杠"
                    }
                    when (event.meld.type) {
                        MeldType.CHOW -> {
                            playSfx(SoundEffect.CHI)
                            announceVoice(VoiceLine.CHI, event.seat)
                        }
                        MeldType.PUNG -> {
                            playSfx(SoundEffect.PENG)
                            announceVoice(VoiceLine.PENG, event.seat)
                        }
                        else -> {
                            playSfx(SoundEffect.GANG)
                            announceVoice(VoiceLine.GANG, event.seat)
                        }
                    }
                    if (event.seat == meSeat) feedback(1)
                    showBanner(Banner(label, names.getOrElse(event.seat) { "" }, event.seat, BannerKind.MELD))
                }

                is GameEvent.KongDeclared -> {
                    playSfx(SoundEffect.GANG)
                    announceVoice(VoiceLine.GANG, event.seat)
                    if (event.seat == meSeat) feedback(1)
                    showBanner(Banner("杠", names.getOrElse(event.seat) { "" }, event.seat, BannerKind.KONG))
                }

                is GameEvent.WinDeclared -> {
                    // 胡牌不再用横幅提示，统一走完整演出链（亮牌→金框→压暗→番型浮层）。
                    playSfx(if (multiWin) SoundEffect.HU_MULTI else SoundEffect.HU)
                    announceVoice(
                        if (event.result.selfDraw) VoiceLine.ZIMO else VoiceLine.HU,
                        event.result.winner,
                    )
                    if (event.result.winner == meSeat) feedback(2)
                }

                is GameEvent.DrawGame -> {
                    playSfx(SoundEffect.DRAW_GAME)
                    announceVoice(VoiceLine.LIUJU)
                }

                else -> Unit
            }
        }
    }

    private fun showBanner(banner: Banner) {
        this.banner = banner
        bannerJob?.cancel()
        bannerJob = scope.launch {
            // 胡牌节拍按规范 560ms；碰/杠等提示保留稍长的可读时间。
            delay(if (banner.kind == BannerKind.HU) 560 else 1_250)
            if (this@LocalGameSession.banner == banner) this@LocalGameSession.banner = null
        }
    }

    private suspend fun finishIfNeeded() {
        if (finished) return
        finished = true
        val result = game.gameResult ?: return
        val seats = buildSeats(game.state)
        val ui = result.toSettlement(
            seats = seats,
            meSeat = meSeat,
            baseScore = config.mcrBaseScore.takeIf { config.mode == GameMode.MCR } ?: config.baseScore,
            modeLabel = modeLabel,
            roundWind = game.roundWind,
            handNumber = handNumber,
            minFan = config.minFan,
            fanCap = config.effectiveFanCap,
            flowerCountsTowardMin = config.flowerCountsTowardMin,
            horseCount = config.horseCountFor(game.dealerStreak),
            horses = result.horses.map { it.description },
        )
        settlement = ui
        banner = null
        bannerJob?.cancel()
        if (!ui.draw && ui.winners.isNotEmpty()) {
            runWinSequence(ui)
        } else {
            delay(if (settings().reduceMotion) 0 else 260)
        }
        onFinished(ui)
    }

    /**
     * 胡牌演出：亮牌定格 → 金框沿手牌描边 → 桌面压暗 → 番型浮层。
     * 一炮多响按座位顺序逐个播；减少动效时直接出静态终态（金框 + 压暗 + 番型）。
     */
    private suspend fun runWinSequence(ui: SettlementUi) {
        val ordered = ui.winners.sortedBy { it.seat }
        if (ordered.isEmpty()) return
        val reduce = settings().reduceMotion
        for (i in ordered.indices) {
            setWinShow(ordered, i, if (reduce) WinStage.FAN else WinStage.REVEAL)
            if (!reduce) {
                delay(320)
                setWinShow(ordered, i, WinStage.FRAME)
                delay(320)
                setWinShow(ordered, i, WinStage.DIM)
                delay(240)
                setWinShow(ordered, i, WinStage.FAN)
            }
            delay(if (reduce) 1_000L else 1_300L)
        }
        delay(if (reduce) 0 else 240)
        table = table?.copy(winShow = null)
    }

    private fun setWinShow(winners: List<WinnerUi>, index: Int, stage: WinStage) {
        val current = table ?: return
        table = current.copy(
            winShow = WinShowUi(winners, index, stage, winners.size > 1),
        )
    }

    // ------------------------------------------------------------ 状态投影

    private fun publish(legal: List<Action> = emptyList()) {
        val state = game.state
        val seats = buildSeats(state)
        val me = state.players[meSeat]
        val drawn = if (state.currentPlayer == meSeat && state.phase == Phase.AWAIT_DISCARD) {
            state.lastDrawnTile
        } else {
            null
        }
        val hand = if (drawn != null) {
            removeOne(me.hand, drawn)
        } else {
            me.hand
        }
        val isClaimTurn = legal.isNotEmpty() && legal.none { it is Action.Discard }
        val claim = if (isClaimTurn) buildClaim(legal) else null
        val canDiscard = legal.any { it is Action.Discard }
        val kongs = legal.mapNotNull { action ->
            when (action) {
                is Action.ConcealedKong -> KongOption(action, "暗杠 ${mahjong.core.tile.Tiles.name(action.tile)}")
                is Action.AddedKong -> KongOption(action, "补杠 ${mahjong.core.tile.Tiles.name(action.tile)}")
                else -> null
            }
        }
        val canHu = legal.any { it is Action.Hu }
        val actorName = seats.getOrNull(state.currentPlayer)?.name ?: "对手"
        val hint = when {
            claim != null && claim.options.contains(ClaimOption.Hu) -> "可以胡牌 · 还有 ${claimSeconds(claim)} 秒"
            claim != null -> "可以吃碰杠 · 还有 ${claimSeconds(claim)} 秒"
            canHu -> "自摸！可以胡牌"
            canDiscard -> "点一下提牌，再点一次打出"
            state.phase == Phase.AWAIT_CLAIM || state.phase == Phase.AWAIT_ROB_KONG -> "等待其他玩家响应…"
            else -> "$actorName 正在思考…"
        }

        table = TableUi(
            meSeat = meSeat,
            hand = sortHand(hand),
            drawnTile = drawn,
            melds = me.melds,
            flowers = me.flowers,
            seats = seats,
            wallRemaining = state.wallRemaining,
            roundWind = state.roundWind,
            dealer = state.dealer,
            currentPlayer = state.currentPlayer,
            lastDiscardTile = state.lastDiscard?.tile,
            lastDiscardSeat = state.lastDiscard?.player,
            claim = claim,
            turnOptions = TurnOptions(canHu = canHu, kongs = kongs),
            canDiscard = canDiscard,
            hint = hint,
            modeLabel = modeLabel,
            handNumber = handNumber,
            banner = banner,
            minFan = config.minFan,
            fanCap = config.effectiveFanCap,
            hintsAvailable = true,
            winShow = table?.winShow,
            tingLocked = tingLocked,
        )
        scheduleAnalysis(me.hand, me.melds, state, legal)
    }

    /**
     * 听牌与出牌建议全部走引擎的 [Hints] 接口，界面不自行判定牌型或番数。
     * 放后台线程执行，主线程只接收结果。
     */
    private fun scheduleAnalysis(
        hand: List<Int>,
        melds: List<mahjong.core.model.Meld>,
        state: mahjong.core.model.GameState,
        legal: List<Action>,
    ) {
        val key = hand.joinToString(",") + "|" + state.discardsSignature()
        if (key == analysisKey) return
        analysisKey = key
        analysisJob?.cancel()

        val robbedTile = state.robbedKongTile
        val robbedPlayer = state.robbedKongPlayer
        val opportunities = if (robbedTile != null && robbedPlayer != null) {
            listOf(
                WinOpportunity(
                    type = WinType.ROB_KONG,
                    tile = robbedTile,
                    fromPlayer = robbedPlayer,
                ),
            )
        } else {
            emptyList()
        }

        analysisJob = scope.launch {
            val hints = withContext(Dispatchers.Default) {
                runCatching { Hints.analyze(game.viewFor(meSeat)) }.getOrNull()
            } ?: return@launch
            if (analysisKey != key) return@launch

            val plans = hints.discards
                .filter { it.tenpai && it.waits.isNotEmpty() }
                .map { hint ->
                    DiscardPlan(
                        discard = hint.tile,
                        waits = hint.waits.map { wait ->
                            WaitTile(
                                tile = wait.tile,
                                remaining = wait.remaining,
                                fan = wait.bestPreview?.fan ?: 0,
                                meetsMin = wait.meetsMinimum,
                            )
                        },
                        bestFan = hint.bestPreview?.fan ?: 0,
                        meetsMin = hint.meetsMinimum,
                        totalRemaining = hint.totalRemaining,
                    )
                }
                .sortedWith(
                    compareByDescending<DiscardPlan> { it.meetsMin }
                        .thenByDescending { it.totalRemaining }
                        .thenByDescending { it.bestFan },
                )

            val waits = hints.tenpai.waits.map { wait ->
                WaitTile(
                    tile = wait.tile,
                    remaining = wait.remaining,
                    fan = wait.bestPreview?.fan ?: 0,
                    meetsMin = wait.meetsMinimum,
                )
            }
            val ting = if (hand.size == 13 - melds.size * 3) {
                TingInfo(
                    waits = waits.sortedWith(compareByDescending<WaitTile> { it.meetsMin }.thenByDescending { it.remaining }),
                    bestFan = hints.tenpai.bestPreview?.fan ?: 0,
                    meetsMin = hints.tenpai.meetsMinimum,
                    shanten = hints.tenpai.shanten,
                )
            } else {
                null
            }
            val win = hints.wins.firstOrNull()
                ?: if (legal.any { it is Action.Hu }) {
                    Hints.winHints(game.viewFor(meSeat), opportunities).firstOrNull()
                } else {
                    null
                }

            table = table?.copy(
                plans = plans,
                tingInfo = ting,
                huFan = win?.preview?.fan,
                huMeetsMin = win?.preview?.meetsMinimum ?: true,
            )
        }
    }

    private fun mahjong.core.model.GameState.discardsSignature(): String {
        val sb = StringBuilder(64)
        players.forEach { player ->
            sb.append(player.discards.size).append('/')
            player.melds.forEach { sb.append(it.tiles.size) }
            sb.append('/').append(player.flowers.size).append(';')
        }
        sb.append(phase).append('/').append(currentPlayer)
        return sb.toString()
    }

    private fun claimSeconds(claim: ClaimUi): Int {
        val deadline = claim.deadline
        return ((deadline - Clock.now()).coerceAtLeast(0) / 1000).toInt() + 1
    }

    private fun buildClaim(legal: List<Action>): ClaimUi {
        val state = game.state
        val options = mutableListOf<ClaimOption>()
        if (legal.any { it is Action.Hu }) options += ClaimOption.Hu
        if (legal.any { it is Action.Peng }) options += ClaimOption.Peng
        if (legal.any { it is Action.ExposedKong }) options += ClaimOption.Gang
        val chiTiles = legal.filterIsInstance<Action.Chi>().map { it.tiles.sorted() }.distinct()
        if (chiTiles.isNotEmpty()) options += ClaimOption.Chi(chiTiles)
        options += ClaimOption.Pass
        return ClaimUi(
            options = options,
            deadline = claimDeadline ?: (Clock.now() + CLAIM_WINDOW_MS),
            totalMs = CLAIM_WINDOW_MS,
            tile = state.lastDiscard?.tile ?: state.robbedKongTile,
            fromSeat = state.lastDiscard?.player ?: state.robbedKongPlayer,
        )
    }

    private fun buildSeats(state: mahjong.core.model.GameState): List<SeatUi> {
        return state.players.map { player ->
            SeatUi(
                seat = player.seat,
                name = names.getOrElse(player.seat) { "玩家${player.seat + 1}" },
                isAI = player.seat != meSeat,
                seatWind = player.seatWind,
                isDealer = player.seat == state.dealer,
                handCount = player.hand.size,
                melds = player.melds,
                discards = player.discards,
                flowers = player.flowers,
                score = player.score,
                tingLocked = player.seat == meSeat && tingLocked,
            )
        }
    }

    private fun removeOne(tiles: List<Int>, tile: Int): List<Int> {
        val result = tiles.toMutableList()
        result.remove(tile)
        return result
    }

    private fun sameAction(a: Action, b: Action): Boolean = a == b

    private fun feedback(strength: Int) {
        val s = settings()
        if (s.haptics) platformVibrate(strength)
    }

    private fun playSfx(effect: SoundEffect) {
        val s = settings()
        if (s.sound) GameAudio.playEffect(effect, s.volume)
    }

    /** 非法操作：短促提示音 + 轻震动，不打断当前等待。 */
    private fun invalid() {
        playSfx(SoundEffect.INVALID)
        feedback(0)
    }

    private fun playDiscard(tile: Int, seat: Int) {
        val s = settings()
        if (s.sound) GameAudio.playDiscard(tile, s.volume, speakTile = s.voice, seat = seat)
    }

    private fun announceVoice(line: VoiceLine, seat: Int = 0) {
        val s = settings()
        if (s.sound && s.voice) GameAudio.announce(line, s.volume, seat = seat)
    }
}

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
import mahjong.core.model.MeldType
import mahjong.core.model.Phase
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import kotlin.random.Random

private const val CLAIM_WINDOW_MS = 8_000L
private const val AI_TURN_DELAY_MS = 520L
private const val AI_CLAIM_DELAY_MS = 420L

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

    fun toggleAuto() {
        autoPlay = !autoPlay
        if (autoPlay) {
            // 正在等玩家出牌时立刻交给 AI，避免托管后仍卡在等待。
            val legal = game.legalActions(meSeat)
            if (legal.isNotEmpty()) pending?.complete(autoAction(legal))
        }
    }

    val gameResult: GameResult? get() = game.gameResult
    val currentDealer: Int get() = game.dealer
    val currentRoundWind: Int get() = game.roundWind

    val modeLabel: String
        get() = if (config.mode == GameMode.MCR) "国标麻将" else "广东麻将"

    fun start() {
        if (loopJob != null) return
        playSfx(SoundEffect.SHUFFLE)
        loopJob = scope.launch {
            // 洗牌落定后连发几张发牌声，贴合“洗牌 → 发牌 → 开打”的节奏。
            repeat(4) {
                delay(45)
                playSfx(SoundEffect.DEAL)
            }
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
                    delay(220)
                    applyAction(autoAction(legal))
                    continue
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
                delay(if (legal.any { it is Action.Discard }) AI_TURN_DELAY_MS else AI_CLAIM_DELAY_MS)
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
                    playSfx(SoundEffect.HU)
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

    private fun playDiscard(tile: Int, seat: Int) {
        val s = settings()
        if (s.sound) GameAudio.playDiscard(tile, s.volume, speakTile = s.voice, seat = seat)
    }

    private fun announceVoice(line: VoiceLine, seat: Int = 0) {
        val s = settings()
        if (s.sound && s.voice) GameAudio.announce(line, s.volume, seat = seat)
    }
}

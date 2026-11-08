package mahjong.app.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import mahjong.core.api.Hints
import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.model.GameEvent
import mahjong.core.model.GameResult
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.model.Phase
import mahjong.core.rules.RuleConfig
import mahjong.core.score.WinResult
import mahjong.app.util.Clock
import kotlin.random.Random

enum class SeatKind { EMPTY, HUMAN, AI }

enum class RoomPhase { WAITING, PLAYING }

private class Seat(val index: Int) {
    var kind = SeatKind.EMPTY
    var playerId: String? = null
    var nickname: String = ""
    var ready = false
    var online = false
    var session: RoomSession? = null
    var resumeToken: String? = null
    var aiLevel = 0
    var offlineSince = 0L
    var aiControlled = false
    var resigned = false
    var lastScore = 0
    /** 该座位最近一次实际动作的时间，用于按客户端设置的思考时长放慢 AI 节奏。 */
    var lastActionAt = 0L
    /** 听牌锁定：听牌后锁定手牌，摸打等胡；由本人开启，服务端校验听牌。 */
    var tingLocked = false
}

/**
 * 一个房间。
 *
 * 所有状态变更都在 [mutex] 内串行执行，避免两家同时碰/杠的竞态。
 * 服务端持有唯一的 [MahjongGame]，客户端只能通过 [handleAction] 提交意图。
 */
class Room(
    val code: String,
    private val config: RuleConfig,
    val ruleSetName: String,
    private val passwordHash: String?,
    private val settings: LanConfig,
    /** 房主客户端设置的 AI 思考时长；0 表示沿用默认节奏。 */
    private val aiThinkMs: Long = 0L,
    private val scope: CoroutineScope,
    private val onClose: (Room) -> Unit,
    private val now: () -> Long = { Clock.now() },
) {
    private val mutex = Mutex()
    private val seats = Array(4) { Seat(it) }
    private val random = Random.Default
    private val brains = Array(4) { AiBrain(settings.autoplayAiLevel) }
    private val logger = LanLog

    private var hostSeat = -1
    private var roomPhase = RoomPhase.WAITING
    private var game: MahjongGame? = null
    private var gameId: String? = null
    private var seedHex = ""
    private var sseq = 0L
    private var pendingDeadline = 0L
    private val windowNotified = HashSet<Int>()
    private val actions = ArrayList<RecordedAction>()
    private val scores = IntArray(4) { config.startingScore }
    private var dealerSeat = 0
    private var roundWind = 0
    private var dealerStreak = 0
    private var closed = false

    /** 供局域网发现读取的公开摘要，所有字段都在 [mutex] 内刷新。 */
    private var summaryCache: RoomSummary? = null

    fun summary(): RoomSummary? = summaryCache

    private val ticker: Job = scope.launch {
        while (isActive) {
            delay(200)
            try {
                tick()
            } catch (e: Exception) {
                logger.warn("房间 $code tick 异常", e)
            }
        }
    }

    val isEmptyRoom: Boolean get() = seats.all { it.kind == SeatKind.EMPTY }

    // ------------------------------------------------------------ 房间操作

    suspend fun join(session: RoomSession, password: String?): Int = mutex.withLock {
        if (passwordHash != null && !Password.verify(password ?: "", passwordHash)) {
            throw RoomError(ErrorCode.BAD_PASSWORD, "房间密码错误")
        }
        if (roomPhase == RoomPhase.PLAYING) {
            throw RoomError(ErrorCode.ALREADY_PLAYING, "对局已开始，请使用重连令牌")
        }
        val seat = seats.indexOfFirst { it.kind == SeatKind.EMPTY }
        if (seat < 0) throw RoomError(ErrorCode.ROOM_FULL, "房间已满")
        val s = seats[seat]
        s.kind = SeatKind.HUMAN
        s.playerId = session.playerId
        s.nickname = session.nickname
        s.online = true
        s.session = session
        s.ready = false
        s.resumeToken = newToken()
        s.aiLevel = 0
        s.offlineSince = 0
        s.aiControlled = false
        s.resigned = false
        s.lastScore = scores[seat]
        if (hostSeat < 0) hostSeat = seat
        sendSeatGranted(seat)
        broadcastRoomState()
        seat
    }

    suspend fun setReady(session: RoomSession, ready: Boolean) = mutex.withLock {
        val seat = seatOf(session) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        if (roomPhase == RoomPhase.PLAYING) throw RoomError(ErrorCode.ALREADY_PLAYING, "对局进行中")
        seats[seat].ready = ready
        broadcastRoomState()
    }

    /**
     * 房主设置 AI 座位（等待阶段）。
     *
     * - [seatHint] 0..3 指定座位；-1 自动取第一个空位。
     * - [level] 1..4 对应 core `AiLevel`（新手/普通/高手/大师），0 用服务端默认。
     * - 指向真人座位时：先按踢人流程把该玩家移出（收到 `room_kicked`），再把座位设为 AI。
     */
    suspend fun addAi(session: RoomSession, seatHint: Int, level: Int = 0) = mutex.withLock {
        val hostIndex = seatOf(session) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        if (hostIndex != hostSeat) throw RoomError(ErrorCode.NOT_HOST, "只有房主可以设置 AI")
        if (roomPhase == RoomPhase.PLAYING) throw RoomError(ErrorCode.ALREADY_PLAYING, "对局进行中")

        val hinted = seatHint.takeIf { it in 0..3 }
        if (hinted != null && seats[hinted].kind == SeatKind.HUMAN) {
            if (hinted == hostIndex) {
                throw RoomError(ErrorCode.CANNOT_KICK_SELF, "不能把自己的座位换成 AI")
            }
            val name = ejectLocked(hinted, "房主把你的座位换成了 AI", hostIndex)
            logger.info("房间 $code 座位 $hinted（$name）被房主改为 AI")
        }
        val seat = hinted ?: seats.indexOfFirst { it.kind == SeatKind.EMPTY }
        if (seat < 0) throw RoomError(ErrorCode.ROOM_FULL, "房间已满")
        val s = seats[seat]
        val lv = level.takeIf { it in 1..4 } ?: settings.autoplayAiLevel
        s.kind = SeatKind.AI
        s.aiLevel = lv
        s.nickname = "AI·${aiLevelName(lv)}"
        s.ready = true
        s.online = true
        s.session = null
        s.playerId = null
        s.resumeToken = null
        s.offlineSince = 0
        s.aiControlled = true
        s.resigned = false
        s.lastScore = scores[seat]
        brains[seat] = AiBrain(lv)
        broadcastRoomState()
    }

    suspend fun removeAi(session: RoomSession, seat: Int) = mutex.withLock {
        requireHost(session)
        if (roomPhase == RoomPhase.PLAYING) throw RoomError(ErrorCode.ALREADY_PLAYING, "对局进行中")
        if (seat !in 0..3 || seats[seat].kind != SeatKind.AI) {
            throw RoomError(ErrorCode.ILLEGAL_ACTION, "该座位不是 AI")
        }
        vacate(seat)
        broadcastRoomState()
    }

    /**
     * 房主移出玩家。
     *
     * - 等待阶段：立即空出座位，被踢者收到 `room_kicked` 后被断开，可被新玩家加入。
     * - 对局中：座位转 AI（二级）打完整局，被踢者令牌作废，本局结束后释放座位。
     * - 幂等：重复踢同一个已空/已移出的座位直接返回成功。
     */
    suspend fun kick(host: RoomSession, targetSeat: Int, reason: String?) = mutex.withLock {
        val hostIndex = seatOf(host) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        if (hostIndex != hostSeat) throw RoomError(ErrorCode.NOT_HOST, "只有房主可以移出玩家")
        if (targetSeat !in 0..3) throw RoomError(ErrorCode.BAD_JSON, "座位号不合法")
        if (targetSeat == hostIndex) {
            throw RoomError(ErrorCode.CANNOT_KICK_SELF, "房主不能移出自己")
        }
        val target = seats[targetSeat]
        if (target.kind == SeatKind.EMPTY) return@withLock // 已被移出/空座：幂等成功
        if (target.kind == SeatKind.AI) {
            throw RoomError(ErrorCode.NOT_A_PLAYER, "AI 座位请用「移除 AI」")
        }

        val targetName = ejectLocked(
            targetSeat,
            reason?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "你已被房主移出房间",
            hostIndex,
        )

        if (roomPhase == RoomPhase.PLAYING) {
            // 对局中：认输托管到本局结束，不打断牌局。
            target.aiControlled = true
            target.resigned = true
            broadcastSeatOnline(targetSeat, false)
            broadcastRoomState()
            logger.info("房间 $code 对局中移出座位 $targetSeat（$targetName），由 AI 打到本局结束")
        } else {
            vacate(targetSeat)
            broadcastRoomState()
            maybeClose()
            logger.info("房间 $code 移出座位 $targetSeat（$targetName）")
        }
    }

    /**
     * 把真人座位「摘下来」：作废令牌、私发 `room_kicked` 并关闭连接。
     * 只负责通知与离线标记；等待阶段/对局中的后续处理由调用方决定。
     */
    private suspend fun ejectLocked(targetSeat: Int, message: String, bySeat: Int): String {
        val target = seats[targetSeat]
        val name = target.nickname.ifBlank { "玩家" }
        val targetSession = target.session
        target.session = null
        target.online = false
        target.resumeToken = null
        target.offlineSince = now()
        if (targetSession != null) {
            runCatching {
                targetSession.sendEnvelope(
                    MessageType.ROOM_KICKED,
                    json(RoomKickedView(message, bySeat)),
                    sid = "",
                    roomCode = code,
                    seat = targetSeat,
                    sseq = 0,
                )
            }
            runCatching { targetSession.close(message) }
        }
        return name
    }

    /** 1..4 与服务端 AI 等级（core 四档）对应。 */
    private fun aiLevelName(level: Int): String = when (level) {
        1 -> "新手"
        2 -> "普通"
        3 -> "高手"
        else -> "大师"
    }

    suspend fun start(session: RoomSession) = mutex.withLock {
        requireHost(session)
        if (roomPhase == RoomPhase.PLAYING) throw RoomError(ErrorCode.ALREADY_PLAYING, "对局进行中")
        if (seats.any { it.kind == SeatKind.EMPTY }) throw RoomError(ErrorCode.NOT_ENOUGH_SEATS, "还有空座位")
        if (seats.any { it.kind == SeatKind.HUMAN && !it.ready }) {
            throw RoomError(ErrorCode.NOT_ALL_READY, "还有玩家未准备")
        }

        val seed = random.nextLong()
        seedHex = seed.toULong().toString(16)
        val newGame = MahjongGame(config, seed, dealerSeat, roundWind, dealerStreak, scores.toList())
        game = newGame
        gameId = "g-${now()}-${(seed and 0xffff).toString(16).padStart(4, '0')}"
        actions.clear()
        pendingDeadline = 0
        windowNotified.clear()
        seats.forEach { it.tingLocked = false }
        roomPhase = RoomPhase.PLAYING

        val initialEvents = newGame.drainEvents()
        val st = newGame.state
        for (s in 0..3) {
            val sessionOfSeat = seats[s].session ?: continue
            val view = st.players[s]
            sendTo(
                sessionOfSeat,
                MessageType.GAME_START,
                json(
                    GameStartView(
                        gameId = gameId!!,
                        ruleSet = ruleSetName,
                        seed = seedHex,
                        dealer = dealerSeat,
                        roundWind = roundWind,
                        seatWind = view.seatWind,
                        initialHand = view.hand,
                        scores = scores.toList(),
                        wallRemaining = st.wallRemaining,
                        flowers = view.flowers,
                    ),
                ),
            )
        }
        broadcastEvents(initialEvents)
        afterStateChange()
    }

    suspend fun leave(session: RoomSession) = mutex.withLock {
        val seat = seatOf(session) ?: return@withLock
        val s = seats[seat]
        if (roomPhase == RoomPhase.PLAYING) {
            s.online = false
            s.session = null
            s.offlineSince = now()
            s.aiControlled = true
            s.resigned = true
            s.resumeToken = null
            // 房主中途退出：立刻把房主权限交给还在线的真人，避免房间无人可管理。
            transferHostFrom(seat)
            broadcastSeatOnline(seat, false)
            broadcastRoomState()
        } else {
            vacate(seat)
            session.close("已离开房间")
            broadcastRoomState()
            maybeClose()
        }
    }

    suspend fun reconnect(session: RoomSession, token: String, lastSseq: Long): Int = mutex.withLock {
        val seat = seats.indexOfFirst {
            it.kind == SeatKind.HUMAN && it.resumeToken != null && it.resumeToken == token
        }
        if (seat < 0) throw RoomError(ErrorCode.RESUME_EXPIRED, "重连令牌无效或已过期")
        val s = seats[seat]
        val old = s.session
        if (old != null && old !== session) {
            runCatching {
                old.sendEnvelope(
                    MessageType.ERROR,
                    json(ErrorView(ErrorCode.SEAT_TAKEOVER, "座位已在其他连接重连")),
                    "",
                    code,
                    seat,
                    sseq,
                )
            }
            runCatching { old.close("座位被其他连接接管") }
        }
        s.session = session
        s.online = true
        s.offlineSince = 0
        s.aiControlled = false
        sendSeatGranted(seat)
        sendSync(session, seat, lastSseq)
        broadcastSeatOnline(seat, true)
        broadcastRoomState()
        // 重连后补发一次当前牌局状态：如果正轮到该座位，客户端必须拿到 state 才能行动。
        // 响应窗口也重新下发（旧连接的 window 已丢失）。
        if (roomPhase == RoomPhase.PLAYING && game != null) {
            windowNotified.remove(seat)
            broadcastState()
            sendWindows()
        }
        seat
    }

    suspend fun sync(session: RoomSession, lastSseq: Long) = mutex.withLock {
        val seat = seatOf(session) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        sendSync(session, seat, lastSseq)
    }

    suspend fun handleAction(session: RoomSession, body: ActionBody, sid: String) = mutex.withLock {
        val seat = seatOf(session)
        if (seat == null) {
            sendAck(session, sid, false, ErrorCode.NOT_IN_ROOM, "不在房间内")
            return@withLock
        }
        val currentGame = game
        if (currentGame == null || roomPhase != RoomPhase.PLAYING) {
            sendAck(session, sid, false, ErrorCode.NOT_PLAYING, "当前不在对局中")
            return@withLock
        }
        val legal = currentGame.legalActions(seat)
        if (legal.isEmpty()) {
            sendAck(session, sid, false, ErrorCode.NOT_YOUR_TURN, "当前没有你的操作")
            return@withLock
        }
        val action = try {
            toAction(seat, body, legal)
        } catch (e: RoomError) {
            sendAck(session, sid, false, e.code, e.message)
            return@withLock
        }
        if (legal.none { it == action }) {
            sendAck(session, sid, false, ErrorCode.ILLEGAL_ACTION, "当前不能执行该操作")
            return@withLock
        }
        try {
            val events = currentGame.apply(action)
            actions.add(recordOf(action))
            sendAck(session, sid, true)
            broadcastEvents(events)
            afterStateChange()
        } catch (e: Exception) {
            logger.warn("执行动作失败 room=$code seat=$seat action=$action", e)
            sendAck(session, sid, false, ErrorCode.ILLEGAL_ACTION, e.message ?: "非法操作")
        }
    }

    /**
     * 听牌锁定开关。开启前必须确实听牌；服务端记录并广播，随后由 tick 按“摸打等胡”代打，
     * 让所有玩家看到的出牌与状态一致。
     */
    suspend fun setTingLock(session: RoomSession, enabled: Boolean) = mutex.withLock {
        val seat = seatOf(session) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        val currentGame = game ?: throw RoomError(ErrorCode.NOT_PLAYING, "当前不在对局中")
        if (roomPhase != RoomPhase.PLAYING) throw RoomError(ErrorCode.NOT_PLAYING, "当前不在对局中")
        if (enabled && !Hints.tenpaiHint(currentGame.viewFor(seat)).tenpai) {
            throw RoomError(ErrorCode.ILLEGAL_ACTION, "尚未听牌，暂不能开启听牌锁定")
        }
        val changed = seats[seat].tingLocked != enabled
        seats[seat].tingLocked = enabled
        if (changed) {
            seats[seat].lastActionAt = 0
            broadcastState()
        }
    }

    suspend fun onDisconnect(session: RoomSession) = mutex.withLock {
        val seat = seatOf(session) ?: return@withLock
        val s = seats[seat]
        s.session = null
        s.online = false
        s.offlineSince = now()
        broadcastSeatOnline(seat, false)
        broadcastRoomState()
    }

    fun isClosed(): Boolean = closed

    // ------------------------------------------------------------ 定时推进

    private suspend fun tick() {
        mutex.withLock {
            if (closed) return@withLock
            val timestamp = now()

            // 宽限期到：托管令牌作废；等待中的座位直接释放
            var stateChanged = false
            for (s in seats) {
                if (s.kind == SeatKind.HUMAN && !s.online && s.session == null &&
                    s.resumeToken != null && s.offlineSince > 0 &&
                    timestamp - s.offlineSince >= settings.resumeGraceMs
                ) {
                    s.resumeToken = null
                    s.aiControlled = true
                    if (roomPhase == RoomPhase.WAITING) vacate(s.index)
                    stateChanged = true
                }
            }
            if (stateChanged) {
                broadcastRoomState()
                maybeClose()
                if (closed) return@withLock
            }

            val currentGame = game ?: return@withLock
            if (currentGame.isOver) {
                finishGame()
                return@withLock
            }

            val st = currentGame.state
            when (st.phase) {
                Phase.AWAIT_CLAIM, Phase.AWAIT_ROB_KONG -> {
                    if (pendingDeadline > 0 && timestamp >= pendingDeadline) {
                        expireClaims()
                        return@withLock
                    }
                    for (seat in 0..3) {
                        if (currentGame.legalActions(seat).isEmpty()) continue
                        if (shouldAutoPlay(seat, timestamp)) {
                            val action = if (seats[seat].tingLocked) {
                                tingLockAction(currentGame, seat) ?: continue
                            } else {
                                brains[seat].decide(currentGame, seat) ?: Action.Pass(seat)
                            }
                            applyInternal(action)
                            return@withLock
                        }
                    }
                }

                Phase.AWAIT_DISCARD -> {
                    val actor = st.currentPlayer
                    if (shouldAutoPlay(actor, timestamp)) {
                        val action = if (seats[actor].tingLocked) {
                            tingLockAction(currentGame, actor) ?: return@withLock
                        } else {
                            brains[actor].decide(currentGame, actor)
                                ?: Action.Discard(actor, st.players[actor].hand.first())
                        }
                        applyInternal(action)
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * 听牌锁定座位的代打策略：能胡就胡；被叫牌只过不碰杠；自己的回合摸什么打什么。
     * 绝不调用 AI 决策，避免拆牌或改听口。
     */
    private fun tingLockAction(currentGame: MahjongGame, seat: Int): Action? {
        val legal = currentGame.legalActions(seat)
        if (legal.isEmpty()) return null
        legal.filterIsInstance<Action.Hu>().firstOrNull()?.let { return it }
        if (currentGame.state.phase != Phase.AWAIT_DISCARD) {
            return legal.filterIsInstance<Action.Pass>().firstOrNull()
        }
        val drawn = currentGame.state.lastDrawnTile
        return legal.filterIsInstance<Action.Discard>().firstOrNull { it.tile == drawn }
            ?: legal.filterIsInstance<Action.Discard>().firstOrNull()
    }

    private fun shouldAutoPlay(seat: Int, timestamp: Long): Boolean {
        val s = seats[seat]
        val controlled = when (s.kind) {
            SeatKind.AI -> true
            // 听牌锁定把真人座位也交给本机代打（服务端执行），但策略只有摸打/胡。
            SeatKind.HUMAN -> s.tingLocked || (!s.online &&
                (s.aiControlled || (s.offlineSince > 0 && timestamp - s.offlineSince >= settings.autoplayDelayMs)))
            SeatKind.EMPTY -> true
        }
        if (!controlled) return false
        // 思考时长 > 0 时，同一座位的两次动作至少间隔这么久；
        // 于是「摸牌 → 停顿 → 出牌」整条链自然放慢，而不是只在某一帧加延迟。
        val pacing = if (s.tingLocked) maxOf(aiThinkMs, TING_LOCK_THINK_MS) else aiThinkMs
        return pacing <= 0L || timestamp - s.lastActionAt >= pacing
    }

    private suspend fun applyInternal(action: Action) {
        val currentGame = game ?: return
        val seat = action.player
        val legal = currentGame.legalActions(seat)
        val safe = if (legal.any { it == action }) {
            action
        } else {
            when (currentGame.state.phase) {
                Phase.AWAIT_DISCARD -> {
                    val tile = currentGame.state.players[seat].hand.firstOrNull() ?: return
                    Action.Discard(seat, tile)
                }
                else -> Action.Pass(seat)
            }
        }
        if (legal.none { it == safe }) return
        try {
            val events = currentGame.apply(safe)
            seats[seat].lastActionAt = now()
            actions.add(recordOf(safe))
            broadcastEvents(events)
            afterStateChange()
        } catch (e: Exception) {
            logger.warn("自动出牌失败 room=$code seat=$seat action=$safe", e)
        }
    }

    private suspend fun expireClaims() {
        val currentGame = game ?: return
        val claimants = (0..3).filter { currentGame.legalActions(it).isNotEmpty() }
        val events = ArrayList<GameEvent>()
        for (seat in claimants) {
            if (currentGame.isOver) break
            if (currentGame.legalActions(seat).isEmpty()) continue
            try {
                events.addAll(currentGame.apply(Action.Pass(seat)))
                actions.add(recordOf(Action.Pass(seat)))
            } catch (e: Exception) {
                logger.warn("超时过牌失败 room=$code seat=$seat", e)
            }
        }
        broadcastEvents(events)
        afterStateChange()
    }

    private suspend fun afterStateChange() {
        val currentGame = game ?: return
        if (currentGame.isOver) {
            finishGame()
            return
        }
        val phase = currentGame.state.phase
        if (phase == Phase.AWAIT_CLAIM || phase == Phase.AWAIT_ROB_KONG) {
            if (pendingDeadline == 0L) {
                pendingDeadline = now() + settings.claimWindowMs
                windowNotified.clear()
            }
        } else {
            pendingDeadline = 0
            windowNotified.clear()
        }
        broadcastState()
        sendWindows()
    }

    // ------------------------------------------------------------ 结算

    private suspend fun finishGame() {
        val currentGame = game ?: return
        val result = currentGame.gameResult ?: run {
            game = null
            roomPhase = RoomPhase.WAITING
            return
        }
        val newScores = result.deltaScores.indices.map { scores[it] + result.deltaScores[it] }
        val gid = gameId ?: ""
        val over = GameOverView(
            gameId = gid,
            draw = result.draw,
            reason = result.reason,
            winners = result.winners.map { winnerView(it) },
            deltaScores = result.deltaScores,
            scores = newScores,
            dealer = result.dealer,
            roundWind = result.roundWind,
            dealerContinues = result.dealerContinues,
        )
        for (i in 0..3) {
            scores[i] = newScores[i]
            seats[i].lastScore = newScores[i]
        }
        broadcastJson(MessageType.GAME_OVER) { json(over) }

        if (result.dealerContinues) {
            dealerStreak++
        } else {
            dealerSeat = (dealerSeat + 1) % 4
            dealerStreak = 0
            if (dealerSeat == 0) roundWind = (roundWind + 1) % 4
        }

        game = null
        gameId = null
        roomPhase = RoomPhase.WAITING
        pendingDeadline = 0
        windowNotified.clear()
        for (s in seats) {
            s.ready = s.kind == SeatKind.AI
            // 主动退出，或宽限期已过仍未回来的玩家，本局结束后释放座位。
            val abandoned = s.kind == SeatKind.HUMAN && !s.online && s.session == null && s.resumeToken == null
            if (s.resigned || abandoned) vacate(s.index)
        }
        broadcastRoomState()
        maybeClose()
    }

    private fun winnerView(win: WinResult) = WinnerView(
        seat = win.winner,
        from = win.fromPlayer,
        selfDraw = win.selfDraw,
        winningTile = win.winningTile,
        fan = win.fanItems.map { FanView(it.id, it.name, it.fan, it.count) },
        totalFan = win.totalFan,
        points = win.points,
        hand = win.handTiles,
        melds = win.melds.map { meldView(it, win.winner, win.winner) },
    )

    // ------------------------------------------------------------ 视图

    private suspend fun broadcastState() {
        val currentGame = game ?: return
        val seq = ++sseq
        for (seat in 0..3) {
            val s = seats[seat]
            if (s.kind == SeatKind.EMPTY) continue
            val session = s.session ?: continue
            val view = buildState(seat, currentGame)
            session.sendEnvelope(MessageType.STATE, json(view), "", code, seat, seq)
        }
    }

    private fun buildState(seat: Int, currentGame: MahjongGame): StateView {
        val view = currentGame.viewFor(seat)
        val st = currentGame.state
        val legal = currentGame.legalActions(seat)
        val isClaim = (st.phase == Phase.AWAIT_CLAIM && seat in st.pendingClaimSeats) ||
            (st.phase == Phase.AWAIT_ROB_KONG && seat in st.pendingRobKongSeats)

        val actions = LinkedHashSet<String>()
        for (a in legal) {
            when (a) {
                is Action.Discard -> actions.add("discard")
                is Action.Chi -> actions.add("chi")
                is Action.Peng -> actions.add("peng")
                is Action.ExposedKong -> actions.add("gang")
                is Action.ConcealedKong -> actions.add("concealed_kong")
                is Action.AddedKong -> actions.add("added_kong")
                is Action.Hu -> actions.add("hu")
                is Action.Pass -> actions.add("pass")
            }
        }

        return StateView(
            gameId = gameId ?: "",
            turn = st.turn,
            phase = st.phase.name,
            seat = seat,
            hand = view.hand,
            melds = view.melds.map { meldView(it, seat, seat) },
            flowers = view.flowers,
            opponents = view.opponents.map { o ->
                OpponentView(
                    seat = o.seat,
                    handCount = o.handCount,
                    melds = o.melds.map { meldView(it, o.seat, seat) },
                    discards = o.discards,
                    flowerCount = o.flowerCount,
                    score = o.score,
                    seatWind = o.seatWind,
                    tingLocked = seats[o.seat]?.tingLocked ?: false,
                )
            },
            wallRemaining = view.wallRemaining,
            dealer = view.dealer,
            roundWind = view.roundWind,
            seatWind = view.seatWind,
            currentPlayer = view.currentPlayer,
            lastDiscard = view.lastDiscard?.let { LastDiscardView(it.player, it.tile, it.turn) },
            legal = LegalView(
                actions = actions.toList(),
                discardable = legal.filterIsInstance<Action.Discard>().map { it.tile }.distinct().sorted(),
            ),
            window = if (isClaim && legal.isNotEmpty()) buildWindow(seat, legal, st) else null,
            tingLocked = seats[seat]?.tingLocked ?: false,
        )
    }

    private fun buildWindow(seat: Int, legal: List<Action>, st: mahjong.core.model.GameState): WindowView? {
        val rob = st.phase == Phase.AWAIT_ROB_KONG
        val from = if (rob) st.robbedKongPlayer ?: -1 else st.lastDiscard?.player ?: -1
        val tile = if (rob) st.robbedKongTile ?: -1 else st.lastDiscard?.tile ?: -1
        if (from < 0 || tile < 0) return null

        val options = ArrayList<WindowOptionView>()
        if (legal.any { it is Action.Hu }) options.add(WindowOptionView("hu"))
        if (legal.any { it is Action.ExposedKong }) options.add(WindowOptionView("gang"))
        if (legal.any { it is Action.Peng }) options.add(WindowOptionView("peng"))
        val chi = legal.filterIsInstance<Action.Chi>().map { it.tiles }
        if (chi.isNotEmpty()) options.add(WindowOptionView("chi", chi))

        val deadline = if (pendingDeadline > 0) pendingDeadline else now() + settings.claimWindowMs
        return WindowView(
            kind = if (rob) "rob_kong" else "discard_claim",
            from = from,
            tile = tile,
            deadline = deadline,
            remainingMs = (deadline - now()).coerceAtLeast(0),
            options = options,
        )
    }

    private fun meldView(meld: Meld, owner: Int, viewer: Int): MeldView {
        val concealed = meld.type == MeldType.KONG_CONCEALED
        val hide = concealed && owner != viewer
        return MeldView(
            type = meld.type.name,
            tiles = if (hide) emptyList() else meld.tiles,
            claimedFrom = if (hide) -1 else meld.claimedFrom,
            claimedTile = if (hide) -1 else meld.claimedTile,
            concealed = concealed,
        )
    }

    private suspend fun sendWindows() {
        val currentGame = game ?: return
        val st = currentGame.state
        if (st.phase != Phase.AWAIT_CLAIM && st.phase != Phase.AWAIT_ROB_KONG) return
        for (seat in 0..3) {
            if (seat in windowNotified) continue
            val legal = currentGame.legalActions(seat)
            if (legal.isEmpty()) continue
            val window = buildWindow(seat, legal, st) ?: continue
            val session = seats[seat].session ?: continue
            session.sendEnvelope(MessageType.WINDOW, json(window), "", code, seat, sseq)
            windowNotified.add(seat)
        }
    }

    private suspend fun sendSync(session: RoomSession, seat: Int, lastSseq: Long) {
        val currentGame = game
        val state = currentGame?.let { buildState(seat, it) }
        val payload = SyncView(
            serverTime = now(),
            room = buildRoomState(),
            state = state,
            window = state?.window,
            lastSseq = if (sseq > 0) sseq else lastSseq,
            autoPlaying = seats[seat].aiControlled,
        )
        session.sendEnvelope(MessageType.SYNC, json(payload), "", code, seat, sseq)
    }

    private fun buildRoomState() = RoomStateView(
        room = code,
        ruleSet = ruleSetName,
        hostSeat = if (hostSeat >= 0) hostSeat else 0,
        phase = roomPhase.name.lowercase(),
        seats = seats.map {
            SeatInfoView(
                seat = it.index,
                kind = it.kind.name.lowercase(),
                playerId = it.playerId,
                nickname = it.nickname,
                ready = it.ready,
                online = it.online,
                aiLevel = it.aiLevel,
                score = currentScore(it.index),
            )
        },
        gameId = gameId,
    )

    private fun currentScore(seat: Int): Int = game?.state?.players?.get(seat)?.score ?: scores[seat]

    // ------------------------------------------------------------ 广播

    private suspend fun broadcastJson(type: String, payload: (Int) -> JsonObject) {
        val seq = ++sseq
        for (seat in 0..3) {
            val s = seats[seat]
            if (s.kind == SeatKind.EMPTY) continue
            val session = s.session ?: continue
            session.sendEnvelope(type, payload(seat), "", code, seat, seq)
        }
    }

    private suspend fun broadcastEvents(events: List<GameEvent>) {
        for (event in events) {
            when (event) {
                is GameEvent.HandStarted -> broadcastJson(MessageType.EVENT) {
                    json(EventView("hand_started", -1, tileCount = event.wallCount, turn = event.turn))
                }

                is GameEvent.Dealt -> broadcastJson(MessageType.EVENT) {
                    json(EventView("dealt", event.seat, tileCount = event.tileCount, turn = event.turn))
                }

                is GameEvent.TileDrawn -> broadcastJson(MessageType.EVENT) { viewer ->
                    json(
                        EventView(
                            type = "draw",
                            seat = event.seat,
                            tile = if (viewer == event.seat) event.tile else null,
                            kind = if (event.kongReplacement) "kong" else null,
                            turn = event.turn,
                        ),
                    )
                }

                is GameEvent.FlowerRevealed -> broadcastJson(MessageType.EVENT) {
                    json(EventView("flower", event.seat, tile = event.tile, turn = event.turn))
                }

                is GameEvent.TileDiscarded -> broadcastJson(MessageType.EVENT) {
                    json(
                        EventView(
                            "discard",
                            event.seat,
                            tile = event.tile,
                            turn = event.turn,
                            kind = if (event.lastTileOfWall) "last_tile" else null,
                        ),
                    )
                }

                is GameEvent.MeldFormed -> broadcastJson(MessageType.EVENT) {
                    json(
                        EventView(
                            type = when (event.meld.type) {
                                MeldType.CHOW -> "chi"
                                MeldType.PUNG -> "peng"
                                else -> "meld"
                            },
                            seat = event.seat,
                            tile = event.meld.claimedTile.takeIf { it >= 0 },
                            from = event.meld.claimedFrom.takeIf { it >= 0 },
                            turn = event.turn,
                        ),
                    )
                }

                is GameEvent.KongDeclared -> broadcastJson(MessageType.EVENT) { viewer ->
                    val concealed = event.meldType == MeldType.KONG_CONCEALED
                    json(
                        EventView(
                            type = "kong",
                            seat = event.seat,
                            tile = if (concealed && viewer != event.seat) null else event.tile,
                            kind = when (event.meldType) {
                                MeldType.KONG_EXPOSED -> "exposed"
                                MeldType.KONG_CONCEALED -> "concealed"
                                else -> "added"
                            },
                            turn = event.turn,
                        ),
                    )
                }

                is GameEvent.ClaimWindowOpened -> broadcastJson(MessageType.EVENT) {
                    json(EventView("claim_window", event.discarder, tile = event.tile, turn = event.turn))
                }

                is GameEvent.RobKongWindowOpened -> broadcastJson(MessageType.EVENT) {
                    json(EventView("rob_kong_window", event.kongPlayer, tile = event.tile, turn = event.turn))
                }

                is GameEvent.GangScored -> broadcastJson(MessageType.EVENT) {
                    json(
                        EventView(
                            "gang_score",
                            event.seat,
                            turn = event.turn,
                            points = event.points,
                            description = event.description,
                        ),
                    )
                }

                is GameEvent.WinDeclared -> broadcastJson(MessageType.EVENT) {
                    json(
                        EventView(
                            "hu",
                            event.result.winner,
                            tile = event.result.winningTile,
                            from = event.result.fromPlayer,
                            kind = when {
                                event.result.robKong -> "rob_kong"
                                event.result.selfDraw -> "self_draw"
                                else -> "discard"
                            },
                            turn = event.turn,
                        ),
                    )
                }

                is GameEvent.DrawGame -> broadcastJson(MessageType.EVENT) {
                    json(EventView("draw_game", -1, reason = event.reason, turn = event.turn))
                }

                is GameEvent.GameOver -> Unit // 由 finishGame 统一发送 game_over
            }
        }
    }

    private suspend fun broadcastSeatOnline(seat: Int, online: Boolean) {
        broadcastJson(MessageType.SEAT_ONLINE) { json(SeatOnlineView(seat, online)) }
    }

    private suspend fun broadcastRoomState() {
        summaryCache = RoomSummary(
            code = code,
            ruleSet = ruleSetName,
            phase = roomPhase.name.lowercase(),
            host = if (hostSeat in 0..3) seats[hostSeat].nickname else "",
            players = seats.count { it.kind != SeatKind.EMPTY },
            humans = seats.count { it.kind == SeatKind.HUMAN },
            hasPassword = passwordHash != null,
        )
        broadcastJson(MessageType.ROOM_STATE) { json(buildRoomState()) }
    }

    // ------------------------------------------------------------ 工具

    private suspend fun sendSeatGranted(seat: Int) {
        val token = seats[seat].resumeToken ?: return
        val session = seats[seat].session ?: return
        session.sendEnvelope(
            MessageType.SEAT_GRANTED,
            json(SeatGrantedView(seat, token)),
            "",
            code,
            seat,
            sseq,
        )
    }

    private suspend fun sendTo(session: RoomSession, type: String, payload: JsonObject) {
        session.sendEnvelope(type, payload, "", code, -1, sseq)
    }

    private suspend fun sendAck(
        session: RoomSession,
        sid: String,
        ok: Boolean,
        code: String? = null,
        message: String? = null,
    ) {
        session.sendEnvelope(
            MessageType.ACTION_ACK,
            json(ActionAckView(sid, ok, code, message)),
            sid = sid,
            roomCode = this.code,
            seat = -1,
            sseq = sseq,
        )
    }

    private fun seatOf(session: RoomSession): Int? =
        seats.indexOfFirst { it.session === session }.takeIf { it >= 0 }

    private fun requireHost(session: RoomSession) {
        val seat = seatOf(session) ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        if (seat != hostSeat) throw RoomError(ErrorCode.NOT_HOST, "只有房主可以操作")
    }

    private fun toAction(seat: Int, body: ActionBody, legal: List<Action>): Action {
        fun tile(): Int = body.tile ?: throw RoomError(ErrorCode.BAD_JSON, "缺少 tile")
        return when (body.action) {
            "discard" -> Action.Discard(seat, tile())
            "chi" -> Action.Chi(
                seat,
                body.tiles,
                body.claimed ?: throw RoomError(ErrorCode.BAD_JSON, "缺少 claimed"),
            )
            "peng" -> Action.Peng(seat, tile())
            "gang" -> when (body.kind) {
                "concealed" -> Action.ConcealedKong(seat, tile())
                "added" -> Action.AddedKong(seat, tile())
                "exposed" -> Action.ExposedKong(seat, tile())
                else -> legal.firstOrNull { it is Action.ConcealedKong && it.tile == body.tile }
                    ?: legal.firstOrNull { it is Action.AddedKong && it.tile == body.tile }
                    ?: legal.firstOrNull { it is Action.ExposedKong && it.tile == body.tile }
                    ?: throw RoomError(ErrorCode.ILLEGAL_ACTION, "当前不能杠")
            }
            "hu" -> Action.Hu(seat)
            "pass" -> Action.Pass(seat)
            else -> throw RoomError(ErrorCode.ILLEGAL_ACTION, "未知动作: ${body.action}")
        }
    }

    private fun recordOf(action: Action): RecordedAction {
        val turn = game?.state?.turn ?: 0
        return when (action) {
            is Action.Discard -> RecordedAction(turn, action.player, "discard", tile = action.tile)
            is Action.Chi -> RecordedAction(turn, action.player, "chi", tiles = action.tiles, claimed = action.claimed)
            is Action.Peng -> RecordedAction(turn, action.player, "peng", tile = action.tile)
            is Action.ExposedKong -> RecordedAction(turn, action.player, "gang", tile = action.tile, kind = "exposed")
            is Action.ConcealedKong -> RecordedAction(turn, action.player, "gang", tile = action.tile, kind = "concealed")
            is Action.AddedKong -> RecordedAction(turn, action.player, "gang", tile = action.tile, kind = "added")
            is Action.Hu -> RecordedAction(turn, action.player, "hu")
            is Action.Pass -> RecordedAction(turn, action.player, "pass")
        }
    }

    private fun vacate(seat: Int) {
        val s = seats[seat]
        s.kind = SeatKind.EMPTY
        s.playerId = null
        s.nickname = ""
        s.ready = false
        s.online = false
        s.session = null
        s.resumeToken = null
        s.aiLevel = 0
        s.offlineSince = 0
        s.aiControlled = false
        s.resigned = false
        if (hostSeat == seat) transferHostFrom(seat)
    }

    /** 优先交给还在线的真人，其次任意非空座位。 */
    private fun transferHostFrom(seat: Int) {
        if (hostSeat != seat) return
        hostSeat = seats.firstOrNull { it.index != seat && it.kind == SeatKind.HUMAN && it.online }?.index
            ?: seats.firstOrNull { it.index != seat && it.kind != SeatKind.EMPTY }?.index
            ?: -1
    }

    private suspend fun maybeClose() {
        if (closed) return
        if (seats.none { it.kind == SeatKind.HUMAN }) {
            closeLocked("房间已无玩家")
        }
    }

    /** 房主主动关房（局域网断开/离开时调用）。 */
    suspend fun shutdown(reason: String) = mutex.withLock { closeLocked(reason) }

    private suspend fun closeLocked(reason: String) {
        if (closed) return
        closed = true
        ticker.cancel()
        val payload = json(RoomClosedView(reason))
        for (s in seats) {
            val session = s.session ?: continue
            runCatching { session.sendEnvelope(MessageType.ROOM_CLOSED, payload, "", code, s.index, sseq) }
            runCatching { session.close(reason) }
            s.session = null
        }
        onClose(this)
    }

    private fun newToken(): String =
        random.nextLong().toULong().toString(16).padStart(16, '0') +
            random.nextLong().toULong().toString(16).padStart(16, '0')

    private inline fun <reified T> json(value: T): JsonObject =
        Wire.json.encodeToJsonElement(value).jsonObject
}

/** 听牌锁定后摸打的固定停顿，保证双端节奏一致。 */
private const val TING_LOCK_THINK_MS = 480L

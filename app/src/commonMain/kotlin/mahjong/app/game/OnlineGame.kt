package mahjong.app.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import mahjong.app.audio.GameAudio
import mahjong.app.audio.SoundEffect
import mahjong.app.audio.VoiceLine
import mahjong.app.keyValueStore
import mahjong.app.lan.LanConn
import mahjong.app.lan.LanDiag
import mahjong.app.lan.LanDirectory
import mahjong.app.lan.LanDiscoveryNode
import mahjong.app.lan.LanHost
import mahjong.app.lan.LanLog
import mahjong.app.lan.LanPeer
import mahjong.app.lan.createLanDiscovery
import mahjong.app.lan.lanConnect
import mahjong.app.platformDeviceId
import mahjong.app.platformVibrate
import mahjong.app.settings.AppSettings
import mahjong.app.util.Clock
import mahjong.core.api.Hints
import mahjong.core.model.Action
import mahjong.core.model.DiscardInfo
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.model.OpponentView
import mahjong.core.model.Phase
import mahjong.core.model.PlayerView
import mahjong.core.score.FanItem
import mahjong.core.rules.RuleConfig
import mahjong.core.tile.Tiles

// ---------------------------------------------------------------- 协议 DTO

@Serializable
internal data class EnvelopeDto(
    val v: Int = 1,
    val t: String,
    val sid: String = "",
    val ts: Long = 0,
    val cseq: Long = 0,
    val sseq: Long = 0,
    val room: String = "",
    val seat: Int = -1,
    val p: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class HelloAckDto(
    val playerId: String = "",
    val nickname: String = "",
    val serverTime: Long = 0,
)

@Serializable
internal data class ErrorDto(val code: String = "", val message: String = "")

@Serializable
internal data class SeatRefBody(val seat: Int = -1)

@Serializable
internal data class ReadyBody(val ready: Boolean = true)

@Serializable
internal data class RoomCreateBody(val ruleSet: String = "MCR", val password: String? = null)

@Serializable
internal data class RoomJoinBody(val room: String, val password: String? = null)

@Serializable
internal data class ReconnectBody(val resumeToken: String, val lastSseq: Long = 0)

@Serializable
internal data class SeatGrantedDto(val seat: Int = -1, val resumeToken: String = "")

@Serializable
data class SeatInfoDto(
    val seat: Int,
    val kind: String = "empty",
    val playerId: String? = null,
    val nickname: String = "",
    val ready: Boolean = false,
    val online: Boolean = false,
    val aiLevel: Int = 0,
    val score: Int = 0,
)

@Serializable
data class RoomStateDto(
    val room: String = "",
    val ruleSet: String = "MCR",
    val hostSeat: Int = 0,
    val phase: String = "waiting",
    val seats: List<SeatInfoDto> = emptyList(),
    val gameId: String? = null,
)

@Serializable
internal data class SeatOnlineDto(val seat: Int, val online: Boolean)

@Serializable
internal data class RoomKickedDto(val reason: String = "", val bySeat: Int = -1)

@Serializable
internal data class MeldDto(
    val type: String = "CHOW",
    val tiles: List<Int> = emptyList(),
    val claimedFrom: Int = -1,
    val claimedTile: Int = -1,
    val concealed: Boolean = false,
)

@Serializable
internal data class OpponentDto(
    val seat: Int,
    val handCount: Int = 0,
    val melds: List<MeldDto> = emptyList(),
    val discards: List<Int> = emptyList(),
    val flowerCount: Int = 0,
    val score: Int = 0,
    val seatWind: Int = 0,
    val tingLocked: Boolean = false,
)

@Serializable
internal data class LastDiscardDto(val seat: Int, val tile: Int, val turn: Int = 0)

@Serializable
internal data class LegalDto(
    val actions: List<String> = emptyList(),
    val discardable: List<Int> = emptyList(),
)

@Serializable
internal data class WindowOptionDto(val action: String, val choices: List<List<Int>> = emptyList())

@Serializable
internal data class WindowDto(
    val kind: String = "discard_claim",
    val from: Int = -1,
    val tile: Int = -1,
    val deadline: Long = 0,
    val remainingMs: Long = 0,
    val options: List<WindowOptionDto> = emptyList(),
)

@Serializable
internal data class StateDto(
    val gameId: String = "",
    val turn: Int = 0,
    val phase: String = "AWAIT_DISCARD",
    val seat: Int = 0,
    val hand: List<Int> = emptyList(),
    val melds: List<MeldDto> = emptyList(),
    val flowers: List<Int> = emptyList(),
    val opponents: List<OpponentDto> = emptyList(),
    val wallRemaining: Int = 0,
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val seatWind: Int = 0,
    val currentPlayer: Int = 0,
    val lastDiscard: LastDiscardDto? = null,
    val legal: LegalDto = LegalDto(),
    val window: WindowDto? = null,
    val tingLocked: Boolean = false,
)

@Serializable
internal data class EventDto(
    val type: String = "",
    val seat: Int = -1,
    val tile: Int? = null,
    val from: Int? = null,
    val tileCount: Int? = null,
    val kind: String? = null,
    val turn: Int? = null,
    val reason: String? = null,
    val handAfterCount: Int? = null,
    val points: Int? = null,
    val description: String? = null,
)

@Serializable
internal data class ActionAckDto(
    val sid: String = "",
    val ok: Boolean = true,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
internal data class FanDto(val id: String = "", val name: String = "", val fan: Int = 0, val count: Int = 1)

@Serializable
internal data class WinnerDto(
    val seat: Int = 0,
    val from: Int? = null,
    val selfDraw: Boolean = false,
    val winningTile: Int = 0,
    val fan: List<FanDto> = emptyList(),
    val totalFan: Int = 0,
    val points: Int = 0,
    val hand: List<Int> = emptyList(),
    val melds: List<MeldDto> = emptyList(),
)

@Serializable
internal data class GameOverDto(
    val gameId: String = "",
    val draw: Boolean = false,
    val reason: String = "",
    val winners: List<WinnerDto> = emptyList(),
    val deltaScores: List<Int> = emptyList(),
    val scores: List<Int> = emptyList(),
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val dealerContinues: Boolean = false,
)

@Serializable
internal data class SyncDto(
    val serverTime: Long = 0,
    val room: RoomStateDto = RoomStateDto(),
    val state: StateDto? = null,
    val window: WindowDto? = null,
    val lastSseq: Long = 0,
    val autoPlaying: Boolean = false,
)

// ---------------------------------------------------------------- 会话

enum class OnlineStatus(val label: String) {
    OFFLINE("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    ERROR("连接失败"),
}

private const val KEY_TOKEN = "online_resume_token"
private const val KEY_ROOM = "online_resume_room"
private const val KEY_SEAT = "online_resume_seat"
private const val KEY_RULE = "online_resume_rule"
private const val KEY_SERVER = "online_server_url"

class OnlineGameSession(
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val onSettlement: (SettlementUi) -> Unit,
    private val onClosure: (String) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val store = keyValueStore()

    private var job: Job? = null
    private var bannerJob: Job? = null
    private var analysisJob: Job? = null
    private var winJob: Job? = null
    private var analysisKey: String? = null
    private var cseq = 0L
    private var lastSseq = 0L
    private var roomCode = ""
    private var ruleSet = "MCR"
    private var pending: PendingAction? = null
    private var latestWindow: WindowDto? = null
    private var lastDrawn: Int? = null
    private var myDiscards = mutableListOf<Int>()
    private var finished = false
    private var closing = false
    /** 上一次胡牌事件时间，用来识别一炮多响并切到 hu_multi。 */
    private var lastHuAt = 0L

    /** 连接代号：只允许最新一次连接的回调改动状态，避免「离开后立刻建房」时旧连接的收尾把新房间冲掉。 */
    private var connectionId = 0
    private var socketRef: LanConn? = null

    /** 本机作为房主时的实例；非房主为 null。 */
    private var lanHost: LanHost? = null

    /** 客机通过发现拿到的房主地址；房主本人固定 127.0.0.1。 */
    private var lanEndpoint: Pair<String, Int>? = null
    private var lobbyNode: LanDiscoveryNode? = null
    private var lobbySweep: Job? = null
    private val _lobbyPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    val lobbyPeers: StateFlow<List<LanPeer>> = _lobbyPeers.asStateFlow()

    /** 手动兜底地址（设置页填写），仅在自动发现失败时使用。 */
    private var targetServerUrl: String? = store.get(KEY_SERVER)?.takeIf { it.isNotBlank() }

    var status by mutableStateOf(OnlineStatus.OFFLINE)
        private set
    var room by mutableStateOf<RoomStateDto?>(null)
        private set
    var mySeat by mutableStateOf(-1)
        private set
    var table by mutableStateOf<TableUi?>(null)
        private set
    var autoPlaying by mutableStateOf(false)
        private set
    var tingLocked by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var banner by mutableStateOf<Banner?>(null)

    val savedRoom: String? get() = store.get(KEY_ROOM)?.takeIf { it.isNotBlank() }
    val savedRule: String get() = store.get(KEY_RULE) ?: "MCR"
    val hasSavedSeat: Boolean get() = !store.get(KEY_TOKEN).isNullOrBlank() && savedRoom != null

    val isInRoom: Boolean get() = room != null
    val inGame: Boolean get() = table != null
    val hostSeat: Int get() = room?.hostSeat ?: 0
    val isHost: Boolean get() = mySeat >= 0 && mySeat == hostSeat

    fun cancel() {
        connectionId++
        closing = true
        job?.cancel()
        job = null
        socketRef?.close()
        socketRef = null
        stopLanHost()
        pending = null
        bannerJob?.cancel()
        analysisJob?.cancel()
        analysisJob = null
        winJob?.cancel()
        winJob = null
        status = OnlineStatus.OFFLINE
    }

    // ------------------------------------------------------------ 局域网

    /**
     * 大厅自动发现：房主应答、客机查询。只在未进房时运行。
     * 结果通过 [lobbyPeers] 暴露（已经过 TTL 过滤）。
     */
    fun startLobbyScan() {
        stopLobbyScan()
        LanDirectory.clear()
        // 换了角色（房主 → 客机）后计数应从零开始，否则诊断页会混着上一局房主的数据。
        LanLog.reset()
        LanDiag.reset()
        val node = createLanDiscovery()
        lobbyNode = node
        node.startAsGuest {
            _lobbyPeers.value = LanDirectory.alive()
        }
        lobbySweep = scope.launch {
            while (isActive) {
                delay(2_000)
                _lobbyPeers.value = LanDirectory.alive()
            }
        }
    }

    fun stopLobbyScan() {
        lobbySweep?.cancel()
        lobbySweep = null
        lobbyNode?.stop()
        lobbyNode = null
        _lobbyPeers.value = emptyList()
    }

    private fun stopLanHost() {
        lanHost?.stop()
        lanHost = null
        lanEndpoint = null
    }

    /** 解析 "host:port" / "1.2.3.4:45232" / 老格式 ws://host:port/ws。 */
    private fun parseEndpoint(raw: String): Pair<String, Int>? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        value = value.removePrefix("ws://").removePrefix("wss://").removePrefix("tcp://")
        value = value.substringBefore('/')
        val host = value.substringBeforeLast(':', "")
        val portText = value.substringAfterLast(':', "")
        if (host.isBlank()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    // ------------------------------------------------------------ 连接入口

    /** 建房：本机直接成为房主，不再需要任何服务器地址。 */
    fun createRoom(presetId: String, password: String? = null) {
        stopLobbyScan()
        stopLanHost()
        ruleSet = wireRule(presetId)
        val host = LanHost.start(ruleSet, password, settings().aiSpeed.thinkMs)
        if (host == null) {
            errorMessage = "无法开启局域网房间：端口被占用"
            status = OnlineStatus.ERROR
            return
        }
        lanHost = host
        lanEndpoint = "127.0.0.1" to host.port
        roomCode = host.code
        onMessage("房间已创建：${host.code}")
        connect(PendingAction.Join(host.code, password))
    }

    /**
     * 加入房间：只认 4 位数字房号。
     * [serverUrl] 来自发现列表（host:port），通常不需要传。
     */
    fun joinRoom(code: String, serverUrl: String? = null, password: String? = null) {
        val normalized = code.trim().filter { it.isDigit() }
        if (normalized.length != 4) {
            onMessage("请输入 4 位数字房号")
            return
        }
        stopLobbyScan()
        stopLanHost()
        if (!serverUrl.isNullOrBlank()) {
            parseEndpoint(serverUrl)?.let { lanEndpoint = it }
        }
        roomCode = normalized
        connect(PendingAction.Join(normalized, password))
    }

    fun resumeSaved() {
        val token = store.get(KEY_TOKEN) ?: return
        val saved = store.get(KEY_ROOM) ?: return
        roomCode = saved
        ruleSet = store.get(KEY_RULE) ?: "MCR"
        connect(PendingAction.Resume(saved, token))
    }

    private fun connect(action: PendingAction) {
        job?.cancel()
        val connId = ++connectionId
        closing = false
        pending = action
        errorMessage = null
        notice = null
        status = OnlineStatus.CONNECTING
        job = scope.launch {
            var conn: LanConn? = null
            val closed = CompletableDeferred<Unit>()
            try {
                val endpoint = resolveEndpoint()
                    ?: throw IllegalStateException("未找到房间，请确认两台设备在同一 Wi-Fi")
                conn = lanConnect(
                    host = endpoint.first,
                    port = endpoint.second,
                    timeoutMs = 4_000,
                    onText = { text ->
                        scope.launch {
                            if (connId == connectionId) handle(text)
                        }
                    },
                    onClosed = { closed.complete(Unit) },
                ) ?: throw IllegalStateException("连不上房主 ${endpoint.first}:${endpoint.second}")
                if (connId != connectionId) {
                    conn.close()
                    return@launch
                }
                socketRef = conn
                conn.sendEnvelope(
                    type = "hello",
                    payload = buildJsonObject {
                        put("deviceId", platformDeviceId())
                        put("nickname", settings().nickname.ifBlank { "玩家" })
                    },
                )
                val pingConn = conn
                launch {
                    while (isActive) {
                        delay(5_000)
                        runCatching {
                            pingConn.sendEnvelope(
                                type = "ping",
                                payload = buildJsonObject { put("time", Clock.now()) },
                            )
                        }
                    }
                }
                closed.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connId == connectionId) {
                    errorMessage = friendlyError(e)
                    status = OnlineStatus.ERROR
                }
            } finally {
                conn?.close()
                // 已被更新的连接取代（离开后立刻重建房间等），旧连接的收尾不能再改状态。
                if (connId == connectionId) {
                    socketRef = null
                    if (!closing && hasSavedSeat) {
                        status = OnlineStatus.CONNECTING
                        scope.launch {
                            delay(1_400)
                            if (connId == connectionId && !closing && hasSavedSeat) resumeSaved()
                        }
                    } else if (status != OnlineStatus.ERROR) {
                        status = OnlineStatus.OFFLINE
                    }
                }
            }
        }
    }

    /** 房主 → 搜索缓存 → 现场扫描 → 手动地址（兜底）。 */
    private suspend fun resolveEndpoint(): Pair<String, Int>? {
        lanEndpoint?.let { return it }
        LanDirectory.find(roomCode)?.let { return it.host to it.port }
        scanForRoom(roomCode)?.let { return it }
        return targetServerUrl?.let { parseEndpoint(it) }
    }

    private suspend fun scanForRoom(code: String): Pair<String, Int>? {
        if (code.isBlank()) return null
        val node = createLanDiscovery()
        val found = CompletableDeferred<LanPeer?>()
        try {
            node.startAsGuest { peer ->
                if (peer.code == code && !found.isCompleted) found.complete(peer)
            }
            val peer = withTimeoutOrNull(3_000) { found.await() } ?: return null
            return peer.host to peer.port
        } finally {
            node.stop()
        }
    }

    private suspend fun LanConn.sendEnvelope(
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
        sid: String = "",
    ) {
        val envelope = buildJsonObject {
            put("v", 1)
            put("t", type)
            put("sid", sid)
            put("ts", Clock.now())
            put("cseq", ++cseq)
            put("room", roomCode)
            put("p", payload)
        }
        send(json.encodeToString(JsonObject.serializer(), envelope))
    }

    private fun LanConn.sendAction(
        action: String,
        tile: Int? = null,
        tiles: List<Int> = emptyList(),
        claimed: Int? = null,
        kind: String? = null,
    ) {
        scope.launch {
            runCatching {
                sendEnvelope(
                    type = "action",
                    payload = buildJsonObject {
                        put("action", action)
                        if (tile != null) put("tile", tile)
                        if (tiles.isNotEmpty()) put(
                            "tiles",
                            kotlinx.serialization.json.JsonArray(tiles.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                        )
                        if (claimed != null) put("claimed", claimed)
                        if (kind != null) put("kind", kind)
                    },
                )
            }.onFailure { errorMessage = friendlyError(it) }
        }
    }

    // ------------------------------------------------------------ 房间操作

    fun setReady(ready: Boolean) = send("room_ready", buildJsonObject { put("ready", ready) })

    /** 房主设置 AI 座位；level 1..4 = 新手/普通/高手/大师，0 = 服务端默认。 */
    fun addAi(seat: Int = -1, level: Int = 0) = send(
        "seat_add_ai",
        buildJsonObject {
            put("seat", seat)
            if (level > 0) put("level", level)
        },
    )

    fun removeAi(seat: Int) = send("seat_remove_ai", buildJsonObject { put("seat", seat) })

    /** 房主把真人玩家移出房间。 */
    fun kick(seat: Int) = send("seat_kick", buildJsonObject { put("seat", seat) })

    fun fillWithAi() {
        val current = room ?: return
        current.seats.filter { it.kind == "empty" }.forEach { addAi(it.seat) }
    }

    fun startGame() = send("game_start")

    /** 听牌锁定开关：服务端校验是否已听牌并广播状态，客户端不自行锁定。 */
    fun toggleTingLock() = send("ting_lock", buildJsonObject { put("enabled", !tingLocked) })

    fun leave() {
        // 先置 closing：离开是主动行为，断线后不该再自动重连回这个座位。
        closing = true
        sendAsync("room_leave")
        clearSaved()
        room = null
        table = null
        mySeat = -1
        status = OnlineStatus.OFFLINE
        // 给 room_leave 一点时间写出去，再真正断开；否则服务端只看到断线，座位要等宽限期才释放。
        scope.launch {
            delay(300)
            cancel()
        }
    }

    fun backToRoom() {
        table = null
        finished = false
        send("room_ready", buildJsonObject { put("ready", true) })
    }

    private fun send(type: String) {
        send(type, JsonObject(emptyMap()))
    }

    private fun send(type: String, payload: JsonObject) {
        sendAsync(type, payload)
    }

    private fun sendAsync(type: String, payload: JsonObject = JsonObject(emptyMap()), sid: String = "") {
        scope.launch {
            runCatching {
                wsSend(type, payload, sid)
            }.onFailure { errorMessage = friendlyError(it) }
        }
    }

    private suspend fun wsSend(type: String, payload: JsonObject, sid: String) {
        val session = socketRef
        if (session == null) {
            errorMessage = "连接已断开，请重试"
            return
        }
        session.sendEnvelope(type, payload, sid)
    }

    // ------------------------------------------------------------ 玩家操作

    fun discard(tile: Int) {
        feedback(0)
        sendAsync(
            "action",
            buildJsonObject {
                put("action", "discard")
                put("tile", tile)
            },
        )
    }

    fun claim(option: ClaimOption, chiTiles: List<Int>?) {
        val window = latestWindow
        when (option) {
            ClaimOption.Hu -> {
                feedback(2)
                sendActionAsync("hu")
            }
            ClaimOption.Pass -> {
                feedback(0)
                sendActionAsync("pass")
            }
            ClaimOption.Peng -> {
                feedback(1)
                sendActionAsync("peng", tile = window?.tile)
            }
            ClaimOption.Gang -> {
                feedback(1)
                sendActionAsync("gang", tile = window?.tile, kind = "exposed")
            }
            is ClaimOption.Chi -> {
                val tiles = chiTiles ?: option.choices.firstOrNull() ?: return
                feedback(1)
                sendActionAsync("chi", tiles = tiles, claimed = window?.tile)
            }
        }
    }

    fun ownKong(option: KongOption) {
        feedback(1)
        when (val action = option.action) {
            is Action.ConcealedKong -> sendActionAsync("gang", tile = action.tile, kind = "concealed")
            is Action.AddedKong -> sendActionAsync("gang", tile = action.tile, kind = "added")
            else -> Unit
        }
    }

    fun ownHu() {
        feedback(2)
        sendActionAsync("hu")
    }

    private fun sendActionAsync(
        action: String,
        tile: Int? = null,
        tiles: List<Int> = emptyList(),
        claimed: Int? = null,
        kind: String? = null,
    ) {
        scope.launch {
            runCatching { socketRef?.sendAction(action, tile, tiles, claimed, kind) }
                .onFailure { errorMessage = friendlyError(it) }
        }
    }

    // ------------------------------------------------------------ 收包

    private suspend fun handle(text: String) {
        val envelope = runCatching { json.decodeFromString(EnvelopeDto.serializer(), text) }.getOrNull() ?: return
        if (envelope.v != 1) return
        if (envelope.sseq > 0 && envelope.t in publicTypes) {
            if (envelope.sseq <= lastSseq) return
            if (lastSseq > 0 && envelope.sseq > lastSseq + 1) {
                socketRef?.let { socket ->
                    runCatching {
                        socket.sendEnvelope(
                            "sync_request",
                            buildJsonObject { put("lastSseq", lastSseq) },
                        )
                    }
                }
            }
            lastSseq = envelope.sseq
        }
        when (envelope.t) {
            "hello_ack" -> {
                status = OnlineStatus.CONNECTED
                performPending()
            }

            "room_state" -> {
                // 已经主动离开（closing）时不再回填房间，等连接收尾。
                if (closing) return
                val dto = decode(RoomStateDto.serializer(), envelope.p) ?: return
                roomCode = dto.room
                room = dto
                // 客机必须以房主下发的规则为准，否则标题/起和线/封顶全按本机默认走。
                if (dto.ruleSet.isNotBlank()) ruleSet = dto.ruleSet
                if (mySeat < 0) {
                    mySeat = envelope.seat
                }
                if (table != null) {
                    table = table?.let { t ->
                        t.copy(seats = t.seats.map { seat ->
                            val info = dto.seats.firstOrNull { it.seat == seat.seat }
                            seat.copy(
                                name = info?.nickname?.takeIf { it.isNotBlank() } ?: seat.name,
                                isAI = info?.kind == "ai",
                                online = info?.online ?: seat.online,
                                score = info?.score ?: seat.score,
                            )
                        })
                    }
                }
            }

            "seat_granted" -> {
                val dto = decode(SeatGrantedDto.serializer(), envelope.p) ?: return
                mySeat = dto.seat
                if (envelope.room.isNotBlank()) roomCode = envelope.room
                if (roomCode.isNotBlank()) {
                    store.put(KEY_ROOM, roomCode)
                    store.put(KEY_TOKEN, dto.resumeToken)
                    store.put(KEY_SEAT, dto.seat.toString())
                    store.put(KEY_RULE, ruleSet)
                    targetServerUrl?.takeIf { it.isNotBlank() }?.let { store.put(KEY_SERVER, it) }
                }
            }

            "state" -> {
                val dto = decode(StateDto.serializer(), envelope.p) ?: return
                publish(dto, dto.window)
            }

            "window" -> {
                val dto = decode(WindowDto.serializer(), envelope.p) ?: return
                latestWindow = dto
                val current = table ?: return
                table = current.copy(claim = buildClaim(dto))
            }

            "event" -> decode(EventDto.serializer(), envelope.p)?.let { handleEvent(it) }

            "action_ack" -> {
                val dto = decode(ActionAckDto.serializer(), envelope.p) ?: return
                if (!dto.ok) {
                    playSfx(SoundEffect.INVALID)
                    onMessage(dto.message ?: "操作未被接受")
                }
            }

            "sync" -> {
                val dto = decode(SyncDto.serializer(), envelope.p) ?: return
                roomCode = dto.room.room
                room = dto.room
                autoPlaying = dto.autoPlaying
                lastSseq = dto.lastSseq
                dto.state?.let { publish(it, dto.window ?: it.window) }
                if (dto.autoPlaying) notice = "已在 AI 托管中"
                status = OnlineStatus.CONNECTED
            }

            "game_over" -> {
                val dto = decode(GameOverDto.serializer(), envelope.p) ?: return
                finish(dto)
            }

            "seat_online" -> {
                val dto = decode(SeatOnlineDto.serializer(), envelope.p) ?: return
                room = room?.let { r ->
                    r.copy(seats = r.seats.map { if (it.seat == dto.seat) it.copy(online = dto.online) else it })
                }
                if (!dto.online && dto.seat != mySeat) notice = "座位 ${dto.seat + 1} 掉线，3 秒后由 AI 托管"
            }

            "room_closed" -> {
                val reason = (envelope.p["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "房间已关闭"
                clearSaved()
                room = null
                table = null
                mySeat = -1
                onClosure(reason)
            }

            "room_kicked" -> {
                val dto = decode(RoomKickedDto.serializer(), envelope.p)
                val reason = dto?.reason?.takeIf { it.isNotBlank() } ?: "你已被房主移出房间"
                clearSaved()
                room = null
                table = null
                mySeat = -1
                autoPlaying = false
                onClosure(reason)
            }

            "error" -> {
                val dto = decode(ErrorDto.serializer(), envelope.p)
                val message = dto?.message ?: "服务端返回错误"
                if (dto?.code == "E_RESUME_EXPIRED") {
                    clearSaved()
                    onMessage("重连凭证已过期，请重新创建房间")
                } else {
                    onMessage(message)
                }
                if (dto?.code == "E_ROOM_NOT_FOUND") errorMessage = message
            }

            "game_start" -> {
                myDiscards.clear()
                lastDrawn = null
                finished = false
                table = null
                notice = "牌局开始"
            }

            "pong" -> Unit
        }
    }

    private suspend fun performPending() {
        when (val action = pending) {
            is PendingAction.Create -> {
                ruleSet = action.ruleSet
                socketRef?.sendEnvelope(
                    "room_create",
                    buildJsonObject {
                        put("ruleSet", action.ruleSet)
                        put("aiThinkMs", settings().aiSpeed.thinkMs)
                        action.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                    },
                )
            }
            is PendingAction.Join -> {
                roomCode = action.room
                socketRef?.sendEnvelope(
                    "room_join",
                    buildJsonObject {
                        put("room", action.room)
                        action.password?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                    },
                )
            }
            is PendingAction.Resume -> {
                roomCode = action.room
                socketRef?.sendEnvelope(
                    "reconnect",
                    buildJsonObject {
                        put("resumeToken", action.token)
                        put("lastSseq", lastSseq)
                    },
                )
            }
            null -> Unit
        }
        pending = null
    }

    private fun <T> decode(serializer: kotlinx.serialization.DeserializationStrategy<T>, obj: JsonObject): T? =
        runCatching { json.decodeFromJsonElement(serializer, obj) }.getOrNull()

    // ------------------------------------------------------------ 状态投影

    private fun publish(dto: StateDto, window: WindowDto?) {
        mySeat = dto.seat
        latestWindow = window
        val inTurn = dto.currentPlayer == dto.seat && dto.phase == "AWAIT_DISCARD"
        val drawn = if (inTurn && dto.hand.size % 3 == 2) {
            lastDrawn?.takeIf { dto.hand.contains(it) } ?: dto.hand.last()
        } else {
            null
        }
        val hand = if (drawn != null) removeOne(dto.hand, drawn) else dto.hand
        val actions = dto.legal.actions
        val canDiscard = actions.contains("discard")
        val canHu = actions.contains("hu") && window == null
        val kongs = buildKongs(dto, actions)
        val claim = window?.let { buildClaim(it) }
        val seats = buildSeats(dto)
        tingLocked = dto.tingLocked

        val hint = when {
            claim != null && claim.options.contains(ClaimOption.Hu) -> "可以胡牌 · 还有 ${secondsLeft(claim)} 秒"
            claim != null -> "可以吃碰杠 · 还有 ${secondsLeft(claim)} 秒"
            canHu -> "自摸！可以胡牌"
            canDiscard -> "点一下提牌，再点一次打出"
            dto.phase == "AWAIT_CLAIM" || dto.phase == "AWAIT_ROB_KONG" -> "等待其他玩家响应…"
            else -> "${seats.getOrNull(dto.currentPlayer)?.name ?: "对手"} 正在思考…"
        }

        table = TableUi(
            meSeat = dto.seat,
            hand = sortHand(hand),
            drawnTile = drawn,
            melds = dto.melds.map { it.toMeld() },
            flowers = dto.flowers,
            seats = seats,
            wallRemaining = dto.wallRemaining,
            roundWind = dto.roundWind,
            dealer = dto.dealer,
            currentPlayer = dto.currentPlayer,
            lastDiscardTile = dto.lastDiscard?.tile,
            lastDiscardSeat = dto.lastDiscard?.seat,
            claim = claim,
            turnOptions = TurnOptions(canHu = canHu, kongs = kongs),
            canDiscard = canDiscard,
            hint = hint,
            modeLabel = modeLabel(ruleSet),
            handNumber = table?.handNumber ?: 1,
            banner = banner,
            autoPlaying = autoPlaying,
            minFan = ruleConfig(ruleSet).minFan,
            fanCap = ruleConfig(ruleSet).effectiveFanCap,
            hintsAvailable = true,
            winShow = table?.winShow,
            tingLocked = dto.tingLocked,
        )
        scheduleAnalysis(dto, drawn)
    }

    /**
     * 联机端也用引擎 [Hints] 本地算提示：只吃 PlayerView（自己手牌 + 公开信息），
     * 拿不到别家手牌，不构成泄密。胡牌合法性仍以服务端 legalActions / 回执为准。
     */
    private fun scheduleAnalysis(dto: StateDto, drawn: Int?) {
        val key = buildString {
            append(dto.gameId).append('|').append(dto.turn).append('|')
            append(dto.phase).append('|').append(dto.currentPlayer).append('|')
            append(dto.hand.joinToString(",")).append('|')
            dto.opponents.sortedBy { it.seat }.forEach { o ->
                append(o.seat).append(':').append(o.handCount).append(',')
                append(o.discards.joinToString(",")).append(';')
            }
            append('|').append(dto.window?.kind).append(dto.window?.tile)
        }
        if (key == analysisKey) return
        analysisKey = key
        analysisJob?.cancel()
        val view = runCatching { dto.toPlayerView(drawn) }.getOrNull() ?: return
        analysisJob = scope.launch {
            val hints = withContext(Dispatchers.Default) {
                runCatching { Hints.analyze(view) }.getOrNull()
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
            val meldCount = view.melds.size
            val ting = if (view.hand.size == 13 - meldCount * 3) {
                TingInfo(
                    waits = hints.tenpai.waits.map { wait ->
                        WaitTile(
                            tile = wait.tile,
                            remaining = wait.remaining,
                            fan = wait.bestPreview?.fan ?: 0,
                            meetsMin = wait.meetsMinimum,
                        )
                    }.sortedWith(compareByDescending<WaitTile> { it.meetsMin }.thenByDescending { it.remaining }),
                    bestFan = hints.tenpai.bestPreview?.fan ?: 0,
                    meetsMin = hints.tenpai.meetsMinimum,
                    shanten = hints.tenpai.shanten,
                )
            } else {
                null
            }
            table = table?.copy(
                plans = plans,
                tingInfo = ting,
                // 服务端能胡就亮按钮；本地只补番数预览，不越权隐藏。
                huFan = hints.wins.firstOrNull()?.preview?.fan ?: table?.huFan,
                huMeetsMin = true,
            )
        }
    }

    private fun StateDto.toPlayerView(drawn: Int?): PlayerView = PlayerView(
        seat = seat,
        hand = hand,
        melds = melds.map { it.toMeld() },
        flowers = flowers,
        opponents = opponents.map { o ->
            OpponentView(
                seat = o.seat,
                handCount = o.handCount,
                melds = o.melds.map { it.toMeld() },
                discards = o.discards,
                flowerCount = o.flowerCount,
                score = o.score,
                seatWind = o.seatWind,
            )
        },
        phase = runCatching { Phase.valueOf(phase) }.getOrDefault(Phase.AWAIT_CLAIM),
        currentPlayer = currentPlayer,
        wallRemaining = wallRemaining,
        lastDiscard = lastDiscard?.let {
            DiscardInfo(it.seat, it.tile, it.turn, wallRemaining == 0)
        },
        seatWind = seatWind,
        roundWind = roundWind,
        dealer = dealer,
        lastDrawnTile = drawn,
        lastDrawWasKongReplacement = false,
        canSelfDrawWin = legal.actions.contains("hu"),
        config = ruleConfig(ruleSet),
        ownDiscards = myDiscards.toList(),
    )

    private fun buildSeats(dto: StateDto): List<SeatUi> {
        val roomSeats = room?.seats.orEmpty()
        return (0..3).map { seat ->
            val info = roomSeats.firstOrNull { it.seat == seat }
            if (seat == dto.seat) {
                SeatUi(
                    seat = seat,
                    name = settings().nickname.ifBlank { "我" },
                    isAI = false,
                    seatWind = dto.seatWind,
                    isDealer = seat == dto.dealer,
                    handCount = dto.hand.size,
                    melds = dto.melds.map { it.toMeld() },
                    discards = myDiscards.toList(),
                    flowers = dto.flowers,
                    score = info?.score ?: 0,
                    online = true,
                    thinking = false,
                    tingLocked = dto.tingLocked,
                )
            } else {
                val opponent = dto.opponents.firstOrNull { it.seat == seat }
                SeatUi(
                    seat = seat,
                    name = info?.nickname?.takeIf { it.isNotBlank() }
                        ?: opponent?.let { "玩家${it.seat + 1}" }
                        ?: "空位",
                    isAI = info?.kind == "ai",
                    seatWind = opponent?.seatWind ?: ((seat - dto.dealer + 4) % 4),
                    isDealer = seat == dto.dealer,
                    handCount = opponent?.handCount ?: 0,
                    melds = opponent?.melds?.map { it.toMeld() } ?: emptyList(),
                    discards = opponent?.discards ?: emptyList(),
                    flowers = emptyList(),
                    score = opponent?.score ?: info?.score ?: 0,
                    online = info?.online ?: false,
                    thinking = dto.currentPlayer == seat,
                    tingLocked = opponent?.tingLocked ?: false,
                )
            }
        }
    }

    private fun buildClaim(window: WindowDto): ClaimUi {
        val options = mutableListOf<ClaimOption>()
        if (window.options.any { it.action == "hu" }) options += ClaimOption.Hu
        if (window.options.any { it.action == "gang" }) options += ClaimOption.Gang
        if (window.options.any { it.action == "peng" }) options += ClaimOption.Peng
        val chi = window.options.firstOrNull { it.action == "chi" }?.choices.orEmpty()
        if (chi.isNotEmpty()) options += ClaimOption.Chi(chi)
        options += ClaimOption.Pass
        val total = window.remainingMs.coerceAtLeast(1)
        return ClaimUi(
            options = options,
            deadline = Clock.now() + window.remainingMs.coerceAtLeast(0),
            totalMs = total,
            tile = window.tile.takeIf { it >= 0 },
            fromSeat = window.from.takeIf { it >= 0 },
        )
    }

    private fun buildKongs(dto: StateDto, actions: List<String>): List<KongOption> {
        val result = mutableListOf<KongOption>()
        if (actions.contains("concealed_kong")) {
            dto.hand.groupingBy { it }.eachCount()
                .filterValues { it == 4 }
                .keys
                .sorted()
                .forEach { tile ->
                    result += KongOption(
                        Action.ConcealedKong(dto.seat, tile),
                        "暗杠 ${Tiles.name(tile)}",
                    )
                }
        }
        if (actions.contains("added_kong")) {
            val counts = dto.hand.groupingBy { it }.eachCount()
            dto.melds.filter { it.type == MeldType.PUNG.name }
                .map { it.claimedTile }
                .distinct()
                .filter { counts[it] ?: 0 > 0 }
                .forEach { tile ->
                    result += KongOption(
                        Action.AddedKong(dto.seat, tile),
                        "补杠 ${Tiles.name(tile)}",
                    )
                }
        }
        return result
    }

    private fun handleEvent(event: EventDto) {
        when (event.type) {
            "hand_started" -> {
                // 每局开始给四家分配音色：己方座位优先用设置里选的，房主可指定其余三家。
                GameAudio.beginGame(mySeat = mySeat.coerceIn(0, 3))
                playSfx(SoundEffect.SHUFFLE)
                scope.launch {
                    repeat(4) {
                        delay(45)
                        playSfx(SoundEffect.DEAL)
                    }
                }
            }

            "draw" -> {
                if (event.seat == mySeat && event.tile != null) lastDrawn = event.tile
                playSfx(SoundEffect.DRAW)
            }

            "discard" -> {
                if (event.tile != null && event.seat == mySeat) {
                    myDiscards += event.tile
                }
                if (event.seat == mySeat) lastDrawn = null
                event.tile?.let { playDiscard(it, event.seat) }
            }

            "chi" -> {
                playSfx(SoundEffect.CHI)
                announceVoice(VoiceLine.CHI, event.seat)
            }

            "peng" -> {
                playSfx(SoundEffect.PENG)
                announceVoice(VoiceLine.PENG, event.seat)
                showBanner(Banner("碰", nameOf(event.seat), event.seat, BannerKind.MELD))
            }

            "kong" -> {
                playSfx(SoundEffect.GANG)
                announceVoice(VoiceLine.GANG, event.seat)
                showBanner(Banner("杠", nameOf(event.seat), event.seat, BannerKind.KONG))
            }

            "hu" -> {
                // 900ms 内再次收到胡牌事件视为一炮多响 / 连续胡，换成更厚的 hu_multi。
                val now = Clock.now()
                val multi = lastHuAt != 0L && now - lastHuAt < 900L
                lastHuAt = now
                playSfx(if (multi) SoundEffect.HU_MULTI else SoundEffect.HU)
                announceVoice(if (event.kind == "self_draw") VoiceLine.ZIMO else VoiceLine.HU, event.seat)
                showBanner(Banner(if (event.from == null) "自摸" else "胡牌", nameOf(event.seat), event.seat, BannerKind.HU))
            }

            "draw_game" -> {
                playSfx(SoundEffect.DRAW_GAME)
                announceVoice(VoiceLine.LIUJU)
                showBanner(Banner("荒庄", "牌墙摸尽", null, BannerKind.WIN))
            }

            "game_start" -> {
                myDiscards.clear()
                lastDrawn = null
                finished = false
                table = null
            }
        }
    }

    private fun nameOf(seat: Int): String =
        room?.seats?.firstOrNull { it.seat == seat }?.nickname ?: "玩家${seat + 1}"

    private fun showBanner(next: Banner) {
        banner = next
        table = table?.copy(banner = next)
        bannerJob?.cancel()
        bannerJob = scope.launch {
            // 胡牌节拍按规范 560ms；碰/杠等提示保留稍长的可读时间。
            delay(if (next.kind == BannerKind.HU) 560 else 1_150)
            if (banner == next) {
                banner = null
                table = table?.copy(banner = null)
            }
        }
    }

    private fun finish(dto: GameOverDto) {
        if (finished) return
        finished = true
        flagFeedback(dto)
        val current = table
        val seats = (0..3).map { seat ->
            val base = current?.seats?.firstOrNull { it.seat == seat }
            (base ?: SeatUi(
                seat = seat,
                name = nameOf(seat),
                isAI = room?.seats?.firstOrNull { it.seat == seat }?.kind == "ai",
                seatWind = (seat - dto.dealer + 4) % 4,
                isDealer = seat == dto.dealer,
                handCount = 0,
                melds = emptyList(),
                discards = emptyList(),
                flowers = emptyList(),
                score = 0,
            )).copy(score = dto.scores.getOrElse(seat) { base?.score ?: 0 })
        }
        val winners = dto.winners.map { w ->
            WinnerUi(
                seat = w.seat,
                name = seats.getOrNull(w.seat)?.name ?: "玩家",
                selfDraw = w.selfDraw,
                fromSeat = w.from,
                winningTile = w.winningTile,
                handTiles = sortHand(w.hand),
                melds = w.melds.map { it.toMeld() },
                fans = w.fan.map { FanItem(it.id, it.name, it.fan, it.count) },
                totalFan = w.totalFan,
                points = w.points,
                robKong = false,
            )
        }
        val ruleConfig = ruleConfig(ruleSet)
        val settlement = SettlementUi(
            draw = dto.draw,
            title = if (dto.draw) "荒庄流局" else "本局结算",
            subtitle = "${WindFullNames.getOrElse(dto.roundWind) { "东风" }} · 联机对局",
            reason = when (dto.reason) {
                "self_draw" -> "自摸"
                "discard" -> "点炮"
                "wall_empty" -> "牌墙摸尽"
                else -> dto.reason
            },
            winners = winners,
            seats = seats,
            deltas = dto.deltaScores,
            meSeat = mySeat,
            totalFan = winners.maxOfOrNull { it.totalFan } ?: 0,
            baseScore = if (ruleSet == "MCR") 8 else 1,
            modeLabel = modeLabel(ruleSet),
            minFan = ruleConfig.minFan,
            fanCap = ruleConfig.effectiveFanCap,
            flowerCountsTowardMin = ruleConfig.flowerCountsTowardMin,
        )
        table = current?.copy(banner = null, winShow = null)
        banner = null
        bannerJob?.cancel()
        val ordered = winners.sortedBy { it.seat }
        if (dto.draw || ordered.isEmpty()) {
            onSettlement(settlement)
            return
        }
        // 胡牌完整演出：亮牌定格 → 金框描边 → 压暗 → 番型浮层 → 结算；一炮多响逐个播。
        setWinShow(ordered, 0, if (settings().reduceMotion) WinStage.FAN else WinStage.REVEAL)
        winJob?.cancel()
        winJob = scope.launch { runWinSequence(ordered, settlement) }
    }

    private suspend fun runWinSequence(winners: List<WinnerUi>, ui: SettlementUi) {
        val reduce = settings().reduceMotion
        for (i in winners.indices) {
            setWinShow(winners, i, if (reduce) WinStage.FAN else WinStage.REVEAL)
            if (!reduce) {
                delay(320)
                setWinShow(winners, i, WinStage.FRAME)
                delay(320)
                setWinShow(winners, i, WinStage.DIM)
                delay(240)
                setWinShow(winners, i, WinStage.FAN)
            }
            delay(if (reduce) 1_000L else 1_300L)
        }
        delay(if (reduce) 0 else 240)
        table = table?.copy(winShow = null)
        if (!closing) onSettlement(ui)
    }

    private fun setWinShow(winners: List<WinnerUi>, index: Int, stage: WinStage) {
        val current = table ?: return
        table = current.copy(winShow = WinShowUi(winners, index, stage, winners.size > 1))
    }

    private fun flagFeedback(dto: GameOverDto) {
        val meWin = dto.winners.any { it.seat == mySeat }
        feedback(if (meWin) 2 else 0)
    }

    private fun clearSaved() {
        store.put(KEY_TOKEN, "")
        store.put(KEY_ROOM, "")
        store.put(KEY_SEAT, "")
    }

    private val publicTypes = setOf("state", "event", "room_state", "game_over", "seat_online")

    private fun removeOne(tiles: List<Int>, tile: Int): List<Int> {
        val result = tiles.toMutableList()
        result.remove(tile)
        return result
    }

    private fun secondsLeft(claim: ClaimUi): Int =
        ((claim.deadline - Clock.now()).coerceAtLeast(0) / 1000).toInt() + 1

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

    private fun friendlyError(e: Throwable): String {
        val text = e.message ?: e::class.simpleName ?: "未知错误"
        return if (text.length > 90) text.take(90) + "…" else text
    }

    private fun wireRule(presetId: String): String = when (presetId) {
        "gd_tuidaohu" -> "GD_TUIDAOHU"
        "gd_jipinghu" -> "GD_JIPINGHU"
        else -> "MCR"
    }

    private fun modeLabel(rule: String): String = when (rule) {
        "GD_TUIDAOHU" -> "广东推倒胡"
        "GD_JIPINGHU" -> "广东鸡平胡"
        else -> "国标麻将"
    }

    /** 结算页的起和线/封顶口径统一从规则配置读，避免写死。 */
    private fun ruleConfig(rule: String): RuleConfig = when (rule) {
        "GD_TUIDAOHU" -> RuleConfig.guangdongTuidaohu()
        "GD_JIPINGHU" -> RuleConfig.guangdongJipinghu()
        else -> RuleConfig.mcr()
    }

    private fun MeldDto.toMeld(): Meld {
        val typeValue = runCatching { MeldType.valueOf(type) }.getOrDefault(MeldType.CHOW)
        val need = if (typeValue == MeldType.KONG_CONCEALED ||
            typeValue == MeldType.KONG_EXPOSED ||
            typeValue == MeldType.KONG_ADDED
        ) 4 else 3
        val values = if (tiles.size == need) tiles else List(need) { -1 }
        return Meld(typeValue, values, claimedFrom, claimedTile)
    }

    private sealed interface PendingAction {
        data class Create(val ruleSet: String, val password: String? = null) : PendingAction
        data class Join(val room: String, val password: String? = null) : PendingAction
        data class Resume(val room: String, val token: String) : PendingAction
    }
}

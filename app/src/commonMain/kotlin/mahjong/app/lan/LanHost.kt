package mahjong.app.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * 局域网房主：把「房间引擎 + 消息路由 + TCP 监听 + 发现应答」组装成一个对象。
 *
 * 房主自己也是一台客户端，通过 127.0.0.1 连进来，走完全相同的协议，
 * 因此不存在「房主特权代码路径」。
 */
class LanHost private constructor(
    val code: String,
    val port: Int,
    val iid: String,
    private val scope: CoroutineScope,
    private val room: Room,
    private val router: MessageRouter,
    private val server: LanServer,
    private val discovery: LanDiscoveryNode,
) {
    private var stopped = false
    private var sessionSeq = 0

    /** 对外展示：当前房间的公开摘要。 */
    fun summary(): RoomSummary? = room.summary()

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { discovery.stop() }
        runCatching { server.stop() }
        scope.launch {
            runCatching { room.shutdown("房主已关闭房间") }
            scope.cancel()
        }
        LanLog.info("房主已停止：房号 $code")
    }

    internal fun attach(conn: LanConn) {
        val session = TcpSession(conn, "c-${++sessionSeq}")
        conn.onText = { text ->
            scope.launch {
                val envelope = runCatching { Wire.decode(text) }.getOrElse {
                    conn.send(
                        Wire.encodeEnvelope(
                            MessageType.ERROR,
                            Wire.obj("code" to kotlinx.serialization.json.JsonPrimitive(ErrorCode.BAD_JSON)),
                        ),
                    )
                    return@launch
                }
                router.handle(session, envelope)
            }
        }
        conn.onClosed = {
            scope.launch {
                session.room?.let { current ->
                    // 传输断开不等于主动离开：这里必须保留重连令牌，
                    // 否则断线瞬间座位就被判为认输，客机永远无法再占回座位。
                    runCatching { current.onDisconnect(session) }
                }
            }
        }
    }

    private fun info(): LanRoomInfo {
        val s = room.summary()
        return LanRoomInfo(
            code = room.code,
            ruleSet = s?.ruleSet ?: room.ruleSetName,
            phase = s?.phase ?: "waiting",
            port = port,
            players = s?.players ?: 0,
            humans = s?.humans ?: 0,
            hasPassword = s?.hasPassword ?: false,
            iid = iid,
        )
    }

    companion object {
        /**
         * 起一个局域网房主。[ruleSetId] 与客户端设置里的规则 ID 一致；
         * 返回 null 表示端口全忙或规则未知。
         */
        fun start(
            ruleSetId: String,
            password: String?,
            aiThinkMs: Long = 0L,
            continuous: Boolean = true,
        ): LanHost? {
            LanLog.reset()
            LanDiag.reset()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rooms = RoomManager(LanConfig(), scope)
            val router = MessageRouter(rooms)
            val rule = runCatching { router.ruleOf(ruleSetId) }.getOrNull()
            if (rule == null) {
                LanLog.warn("未知规则集 $ruleSetId，无法开房")
                scope.cancel()
                return null
            }
            val room: Room = rooms.create(rule.config, rule.name, password, aiThinkMs, continuous)
            val iid = "h-" + (0 until 3).joinToString("") {
                Random.nextInt(0x10000).toString(16).padStart(4, '0')
            }

            var holder: LanHost? = null
            val server = lanListen(LAN_TCP_PORTS) { conn -> holder?.attach(conn) }
            if (server == null) {
                scope.launch { runCatching { room.shutdown("端口被占用") } }
                scope.cancel()
                return null
            }

            val discovery = createLanDiscovery()
            val host = LanHost(room.code, server.port, iid, scope, room, router, server, discovery)
            holder = host
            discovery.startAsHost { host.info() }
            LanLog.info(
                "局域网房主已就绪：房号 ${room.code}，TCP ${server.port}，规则 ${rule.name}",
            )
            return host
        }
    }
}

/** 房主侧的一条 TCP 会话。 */
private class TcpSession(
    private val conn: LanConn,
    override val sessionId: String,
) : LanSession {
    override var playerId: String? = null
    override var nickname: String = ""
    override var room: Room? = null

    private var lastCseq = 0L

    override fun acceptCseq(cseq: Long): Boolean {
        if (cseq <= 0) return true
        if (cseq <= lastCseq) return false
        lastCseq = cseq
        return true
    }

    override suspend fun sendEnvelope(
        type: String,
        payload: JsonObject,
        sid: String,
        roomCode: String,
        seat: Int,
        sseq: Long,
    ) {
        conn.send(Wire.encodeEnvelope(type, payload, sid, roomCode, seat, sseq))
    }

    override suspend fun close(reason: String) {
        conn.close()
    }
}

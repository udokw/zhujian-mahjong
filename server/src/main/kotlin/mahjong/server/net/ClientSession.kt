package mahjong.server.net

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import mahjong.server.protocol.Wire
import mahjong.server.room.Room
import mahjong.server.room.RoomSession
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条 WebSocket 连接的服务端封装。
 *
 * 只做四件事：发消息、去重 cseq、限流、记录最后活跃时间。
 * 所有发送串行化，保证同一连接上的 sseq 顺序稳定。
 */
class ClientSession(
    val sessionId: String,
    private val socket: DefaultWebSocketServerSession,
    private val now: () -> Long = System::currentTimeMillis,
) : RoomSession {

    @Volatile
    override var playerId: String? = null

    @Volatile
    override var nickname: String = "玩家"

    /** 当前所在房间；由 MessageRouter 维护。 */
    @Volatile
    var room: Room? = null

    @Volatile
    var lastSeen: Long = now()

    @Volatile
    var closed: Boolean = false
        private set

    private val sendLock = Mutex()
    private val lastCseq = AtomicLong(0)
    private val windowStart = AtomicLong(0)
    private val windowCount = AtomicLong(0)

    companion object {
        /** 单连接每秒最多接受的客户端消息数。 */
        const val RATE_LIMIT_PER_SECOND = 40
    }

    fun touch() {
        lastSeen = now()
    }

    /**
     * 连接内去重：cseq 为 0 表示客户端未使用序号，直接放行；
     * 其余必须严格大于已处理的最大值。
     */
    fun acceptCseq(cseq: Long): Boolean {
        if (cseq <= 0) return true
        while (true) {
            val previous = lastCseq.get()
            if (cseq <= previous) return false
            if (lastCseq.compareAndSet(previous, cseq)) return true
        }
    }

    fun allowMessage(): Boolean {
        val current = now()
        val start = windowStart.get()
        if (current - start >= 1000) {
            if (windowStart.compareAndSet(start, current)) windowCount.set(1)
            return true
        }
        return windowCount.incrementAndGet() <= RATE_LIMIT_PER_SECOND
    }

    override suspend fun sendEnvelope(
        type: String,
        payload: JsonObject,
        sid: String,
        roomCode: String,
        seat: Int,
        sseq: Long,
    ) {
        if (closed) return
        val text = Wire.encodeEnvelope(type, payload, sid, roomCode, seat, sseq)
        sendRaw(text)
    }

    suspend fun sendRaw(text: String) {
        if (closed) return
        sendLock.withLock {
            if (closed) return@withLock
            runCatching { socket.send(text) }
                .onFailure { closed = true }
        }
    }

    override suspend fun close(reason: String) {
        if (closed) return
        closed = true
        runCatching { socket.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(100))) }
    }
}

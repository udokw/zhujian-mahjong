package mahjong.server.room

import mahjong.server.ServerConfig
import mahjong.server.persist.ReplayStore
import mahjong.server.protocol.ErrorCode
import kotlinx.coroutines.CoroutineScope
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class RoomError(val code: String, override val message: String) : Exception(message)

data class MatchTicket(
    val ticketId: String,
    val playerId: String,
    val ruleSet: String,
    val createdAt: Long,
)

sealed interface MatchStatus {
    data object Waiting : MatchStatus
    data class Matched(val roomCode: String) : MatchStatus
}

/** 匹配队列接口：v1 只有好友房，先把契约留出来。 */
interface Matchmaker {
    suspend fun enqueue(ticket: MatchTicket): MatchStatus
    suspend fun cancel(ticketId: String)
    suspend fun poll(ticketId: String): MatchStatus
}

class NoopMatchmaker : Matchmaker {
    override suspend fun enqueue(ticket: MatchTicket): MatchStatus = MatchStatus.Waiting
    override suspend fun cancel(ticketId: String) {}
    override suspend fun poll(ticketId: String): MatchStatus = MatchStatus.Waiting
}

/**
 * 房间注册表：生成房号、创建房间、按房号查找。
 */
class RoomManager(
    private val settings: ServerConfig,
    private val store: ReplayStore,
    private val scope: CoroutineScope,
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val random = SecureRandom()

    val matchmaker: Matchmaker = NoopMatchmaker()

    fun create(
        config: mahjong.core.rules.RuleConfig,
        ruleSetName: String,
        password: String?,
        aiThinkMs: Long = 0L,
    ): Room {
        repeat(20) {
            val code = generateCode()
            val room = Room(
                code = code,
                config = config,
                ruleSetName = ruleSetName,
                passwordHash = password?.takeIf { it.isNotBlank() }?.let { Password.hash(it) },
                settings = settings,
                aiThinkMs = aiThinkMs.coerceIn(0L, 5_000L),
                store = store,
                scope = scope,
                onClose = { rooms.remove(code, it) },
            )
            if (rooms.putIfAbsent(code, room) == null) return room
        }
        throw RoomError(ErrorCode.INTERNAL, "无法生成唯一房号")
    }

    fun find(code: String): Room? = rooms[code.uppercase()]

    fun remove(room: Room) {
        rooms.remove(room.code, room)
    }

    fun count(): Int = rooms.size

    /** 局域网发现用：所有房间的公开摘要。 */
    fun summaries(): List<RoomSummary> = rooms.values.mapNotNull { it.summary() }.sortedBy { it.code }

    private fun generateCode(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return buildString(6) {
            repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}

object Password {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(8).also(random::nextBytes)
        val digest = digest(salt, password)
        return salt.joinToString("") { "%02x".format(it) } + "$" + digest
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 2) return false
        val salt = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return digest(salt, password) == parts[1]
    }

    private fun digest(salt: ByteArray, password: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(password.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 生成游客 ID：同一 deviceId 稳定。 */
fun guestPlayerId(deviceId: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(deviceId.toByteArray(Charsets.UTF_8))
    return "p-" + digest.take(4).joinToString("") { "%02x".format(it) }
}

/** 会话持有者接口，避免 net 与 room 包硬绑 Ktor 类型。 */
interface RoomSession {
    val playerId: String?
    val nickname: String
    suspend fun sendEnvelope(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
        sid: String = "",
        roomCode: String = "",
        seat: Int = -1,
        sseq: Long = 0,
    )

    /** 服务端主动关闭连接（例如座位被接管、房间关闭）。 */
    suspend fun close(reason: String)
}

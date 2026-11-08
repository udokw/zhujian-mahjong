package mahjong.app.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import mahjong.core.rules.RuleConfig
import kotlin.random.Random

/** 房间操作失败：错误码与可直接展示给玩家的中文消息。 */
class RoomError(val code: String, override val message: String) : Exception(message)

/** 局域网房主的运行参数；默认值直接沿用原服务端节奏。 */
data class LanConfig(
    val claimWindowMs: Long = 8_000,
    val autoplayDelayMs: Long = 3_000,
    val resumeGraceMs: Long = 180_000,
    val autoplayAiLevel: Int = 2,
)

/** 会话持有者接口：房间只依赖这个，不关心底层是 TCP 还是别的。 */
interface RoomSession {
    var playerId: String?
    var nickname: String
    suspend fun sendEnvelope(
        type: String,
        payload: JsonObject,
        sid: String = "",
        roomCode: String = "",
        seat: Int = -1,
        sseq: Long = 0,
    )

    /** 房主主动关闭连接（座位被接管、被移出、房间关闭）。 */
    suspend fun close(reason: String)
}

/**
 * 房间注册表。局域网房里同一时刻只有一个房间，因此内部只保存一个引用；
 * 房号是 4 位纯数字，由房主生成，随发现应答一起广播。
 */
class RoomManager(
    private val config: LanConfig,
    private val scope: CoroutineScope,
) {
    private var current: Room? = null

    fun create(
        ruleConfig: RuleConfig,
        ruleSetName: String,
        password: String?,
        aiThinkMs: Long = 0L,
    ): Room {
        if (current != null) {
            throw RoomError(ErrorCode.ILLEGAL_ACTION, "本机已经有房间在运行")
        }
        val room = Room(
            code = generateCode(),
            config = ruleConfig,
            ruleSetName = ruleSetName,
            passwordHash = password?.takeIf { it.isNotBlank() }?.let { Password.hash(it) },
            settings = config,
            aiThinkMs = aiThinkMs.coerceIn(0L, 5_000L),
            scope = scope,
            onClose = { closed -> if (current === closed) current = null },
        )
        current = room
        return room
    }

    fun find(code: String): Room? {
        val room = current ?: return null
        return room.takeIf { it.code == code.trim() }
    }

    fun remove(room: Room) {
        if (current === room) current = null
    }

    fun count(): Int = if (current != null) 1 else 0

    /** 局域网发现用：当前房间的公开摘要。 */
    fun summaries(): List<RoomSummary> = listOfNotNull(current?.summary())

    private fun generateCode(): String = (1000 + Random.nextInt(9000)).toString()

    fun currentRoom(): Room? = current
}

/**
 * 房间密码。局域网场景不做持久化凭据，使用带随机盐的 FNV-1a 64 位摘要，
 * 只用于「防止同网段随手加入」，不是安全边界。
 */
object Password {
    fun hash(password: String): String {
        val salt = Random.nextInt(0x10000, 0xFFFFF).toString(16)
        return "$salt$" + digest(salt, password)
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 2) return false
        return digest(parts[0], password) == parts[1]
    }

    private fun digest(salt: String, password: String): String {
        var h = 0xcbf29ce484222325uL
        for (b in (salt + "\u0000" + password).encodeToByteArray()) {
            h = h xor b.toUByte().toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16)
    }
}

/** 生成游客 ID：同一 deviceId 稳定（FNV-1a）。 */
fun guestPlayerId(deviceId: String): String {
    var h = 0xcbf29ce484222325uL
    for (b in deviceId.encodeToByteArray()) {
        h = h xor b.toUByte().toULong()
        h *= 0x100000001b3uL
    }
    return "p-" + h.toString(16)
}

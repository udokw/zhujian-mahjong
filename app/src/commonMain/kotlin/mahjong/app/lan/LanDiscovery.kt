package mahjong.app.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mahjong.app.util.Clock

/** UDP 发现端口（TCP 用 45232 起，避开这个 UDP 端口）。 */
const val LAN_DISCOVERY_PORT = 45231

/** TCP 房主端口：默认 45232，被占用时顺延到 45239。 */
const val LAN_DEFAULT_TCP_PORT = 45232
val LAN_TCP_PORTS: IntRange = 45232..45239

/** Bonjour 服务类型与游戏标识。 */
const val LAN_SERVICE_TYPE = "_mahjong._tcp"
const val LAN_GAME_ID = "mahjong"
const val LAN_PROTOCOL = 2

/** 发现报文。只有 [buildLanPacket] 一个构造入口，避免发出错误类型。 */
@Serializable
data class LanPacket(
    val v: Int = LAN_PROTOCOL,
    val t: String,
    val iid: String = "",
    val game: String = LAN_GAME_ID,
    val room: String = "",
    val rule: String = "",
    val phase: String = "",
    val port: Int = 0,
    val size: Int = 4,
    val players: Int = 0,
    val humans: Int = 0,
    val lock: Int = 0,
    val ts: Long = 0,
)

enum class LanPacketType(val wire: String) {
    QUERY("room_query"),
    REPLY("room_reply"),
    ANNOUNCE("room_announce");

    companion object {
        fun from(raw: String): LanPacketType? = when (raw.trim().lowercase().replace('-', '_')) {
            "room_query", "roomquery", "query", "mj_query", "mj_query_v2" -> QUERY
            "room_reply", "roomreply", "reply", "mj_rooms", "mj_rooms_v2", "mj_reply_v2" -> REPLY
            "room_announce", "roomannounce", "announce", "mj_announce", "mj_announce_v2" -> ANNOUNCE
            else -> null
        }
    }
}

private val lanJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

data class LanRoomInfo(
    val code: String,
    val ruleSet: String,
    val phase: String,
    val port: Int,
    val players: Int,
    val humans: Int,
    val hasPassword: Boolean,
    val iid: String,
)

data class LanPeer(
    val code: String,
    val ruleSet: String,
    val phase: String,
    val host: String,
    val port: Int,
    val players: Int,
    val humans: Int,
    val capacity: Int,
    val hasPassword: Boolean,
    val iid: String,
    val lastSeen: Long,
) {
    val endpoint: String get() = "$host:$port"
}

/** 唯一的报文构造入口。 */
fun buildLanPacket(type: LanPacketType, info: LanRoomInfo): String =
    lanJson.encodeToString(
        LanPacket.serializer(),
        LanPacket(
            t = type.wire,
            iid = info.iid,
            room = info.code,
            rule = info.ruleSet,
            phase = info.phase,
            port = info.port,
            players = info.players,
            humans = info.humans,
            lock = if (info.hasPassword) 1 else 0,
            ts = Clock.now(),
        ),
    )

/** 客机的查询包；同样走 buildLanPacket，避免发出错类型。 */
fun buildLanQuery(iid: String): String = buildLanPacket(
    LanPacketType.QUERY,
    LanRoomInfo(
        code = "",
        ruleSet = "",
        phase = "",
        port = 0,
        players = 0,
        humans = 0,
        hasPassword = false,
        iid = iid,
    ),
)

/** 解析并校验；不合法返回 null（永远不会抛异常）。 */
fun parseLanPacket(text: String, senderHost: String): Pair<LanPacketType, LanPacket>? {
    val packet = runCatching { lanJson.decodeFromString(LanPacket.serializer(), text) }.getOrNull() ?: return null
    if (packet.v != LAN_PROTOCOL) return null
    if (packet.game != LAN_GAME_ID) return null
    if (packet.iid.isBlank()) return null
    val type = LanPacketType.from(packet.t) ?: return null
    if (type == LanPacketType.QUERY) return type to packet
    if (packet.room.isBlank()) return null
    if (packet.port !in 1..65535) return null
    return type to packet
}

fun LanPacket.toPeer(senderHost: String, lastSeen: Long = Clock.now()): LanPeer = LanPeer(
    code = room,
    ruleSet = rule,
    phase = phase,
    host = senderHost,
    port = port,
    players = players,
    humans = humans,
    capacity = if (size in 2..4) size else 4,
    hasPassword = lock == 1,
    iid = iid,
    lastSeen = lastSeen,
)

/** 本机可能的广播地址：255.255.255.255 + 同网段 x.y.z.255。 */
fun lanBroadcastTargets(): List<String> {
    val local = lanLocalIpv4()
    val subnet = local.split('.').let { parts ->
        if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else null
    }
    return listOfNotNull("255.255.255.255", subnet).distinct()
}

/**
 * 发现节点。角色互斥：房主只应答/广播，客机只查询/收集。
 * 两者共用同一个 UDP 端口，但生命周期完全不同，绝不共用循环。
 */
interface LanDiscoveryNode {
    /** 房主：绑定发现端口并开始应答；[info] 每次应答时实时读取。 */
    fun startAsHost(info: () -> LanRoomInfo)
    /** 客机：开始周期查询，发现新房间或刷新 TTL 时回调。 */
    fun startAsGuest(onPeer: (LanPeer) -> Unit)
    fun stop()
}

expect fun createLanDiscovery(): LanDiscoveryNode

/** 发现的房间缓存：TTL 15 秒，避免僵尸房间一直挂在列表里。 */
object LanDirectory {
    private const val TTL_MS = 15_000L
    private val peers = LinkedHashMap<String, LanPeer>()

    fun upsert(peer: LanPeer): Boolean {
        val old = peers[peer.iid]
        val isNew = old == null ||
            old.endpoint != peer.endpoint ||
            old.players != peer.players ||
            old.phase != peer.phase ||
            old.hasPassword != peer.hasPassword
        peers[peer.iid] = peer
        return isNew
    }

    fun alive(): List<LanPeer> {
        val now = Clock.now()
        val expired = peers.filterValues { now - it.lastSeen > TTL_MS }.keys
        expired.forEach { peers.remove(it) }
        return peers.values.sortedBy { it.code }
    }

    fun find(code: String): LanPeer? {
        val now = Clock.now()
        return peers.values.firstOrNull { it.code == code.trim() && now - it.lastSeen <= TTL_MS }
    }

    fun clear() = peers.clear()
}

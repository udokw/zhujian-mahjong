package mahjong.app.online

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 与服务端 DiscoveryServer 约定的 UDP 端口。 */
const val DISCOVERY_PORT: Int = 45231
internal const val DISCOVERY_QUERY: String = "MJ?1"
internal const val DISCOVERY_PREFIX: String = "MJ_ROOMS/1"

/** 局域网内发现的房间（全部信息来自服务端公开摘要）。 */
data class DiscoveredRoom(
    val code: String,
    val ruleSet: String = "MCR",
    val phase: String = "waiting",
    val host: String = "",
    val players: Int = 0,
    val humans: Int = 0,
    val capacity: Int = 4,
    val hasPassword: Boolean = false,
    val serverHost: String = "",
    val wsPort: Int = 8080,
) {
    val wsUrl: String get() = "ws://${serverHost.ifBlank { "127.0.0.1" }}:$wsPort/ws"

    val ruleLabel: String get() = when (ruleSet) {
        "GD_TUIDAOHU" -> "广东推倒胡"
        "GD_JIPINGHU" -> "广东鸡平胡"
        else -> "国标麻将"
    }
}

/**
 * 广播 [DISCOVERY_QUERY] 到局域网 [port]，收集 [timeoutMs] 内服务端的应答。
 *
 * [unicastHosts] 是额外的单播探测地址（例如设置里已填过的服务器 host），
 * 用于广播出不去的环境：Android 模拟器 NAT、跨网段、部分企业 Wi-Fi 禁广播。
 *
 * 返回结果按 `服务端地址:端口/房号` 去重；网络不可用时返回空列表，不抛异常。
 */
expect suspend fun discoverNearbyRooms(
    port: Int = DISCOVERY_PORT,
    timeoutMs: Int = 1200,
    unicastHosts: List<String> = emptyList(),
): List<DiscoveredRoom>

/** 从 `ws://host:port/ws` 里取出 host，取不到返回空串。 */
internal fun hostOfServerUrl(url: String): String {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isBlank()) return ""
    val authority = afterScheme.substringBefore('/')
    return when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        else -> authority.substringBefore(':')
    }.trim()
}

@Serializable
internal data class DiscoveryPayloadDto(
    val v: Int = 1,
    val host: String = "",
    val wsPort: Int = 8080,
    val discoveryPort: Int = DISCOVERY_PORT,
    val rooms: List<DiscoveryRoomDto> = emptyList(),
)

@Serializable
internal data class DiscoveryRoomDto(
    val code: String = "",
    val ruleSet: String = "MCR",
    val phase: String = "waiting",
    val host: String = "",
    val players: Int = 0,
    val humans: Int = 0,
    val capacity: Int = 4,
    val hasPassword: Boolean = false,
)

private val discoveryJson = Json { ignoreUnknownKeys = true }

/**
 * 解析一条发现应答。`senderHost` 是 UDP 包源地址，优先于 payload 里服务端自报的网卡地址，
 * 这样多网卡机器也能拿到客户端真正可达的 IP。
 */
internal fun parseDiscoveryRooms(text: String, senderHost: String): List<DiscoveredRoom> {
    if (!text.startsWith(DISCOVERY_PREFIX)) return emptyList()
    val body = text.substringAfter('\n', "")
    if (body.isBlank()) return emptyList()
    val dto = runCatching {
        discoveryJson.decodeFromString(DiscoveryPayloadDto.serializer(), body)
    }.getOrNull() ?: return emptyList()
    val host = senderHost.ifBlank { dto.host }
    return dto.rooms.filter { it.code.isNotBlank() }.map { room ->
        DiscoveredRoom(
            code = room.code,
            ruleSet = room.ruleSet,
            phase = room.phase,
            host = room.host,
            players = room.players,
            humans = room.humans,
            capacity = room.capacity,
            hasPassword = room.hasPassword,
            serverHost = host,
            wsPort = dto.wsPort,
        )
    }
}

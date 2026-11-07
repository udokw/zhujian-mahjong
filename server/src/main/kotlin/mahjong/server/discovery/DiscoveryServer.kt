package mahjong.server.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mahjong.server.ServerConfig
import mahjong.server.room.RoomManager
import mahjong.server.room.RoomSummary
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** 单条房间摘要，客户端大厅直接展示。 */
@Serializable
data class DiscoveryRoom(
    val code: String,
    val ruleSet: String,
    val phase: String,
    val host: String,
    val players: Int,
    val humans: Int,
    val capacity: Int,
    val hasPassword: Boolean,
)

/** UDP 发现响应体。 */
@Serializable
data class DiscoveryResponse(
    val v: Int = 1,
    val host: String,
    val wsPort: Int,
    val discoveryPort: Int,
    val rooms: List<DiscoveryRoom>,
)

/**
 * 局域网自动发现（UDP 广播）。
 *
 * 协议（纯文本，UTF-8）：
 * - 查询：`MJ?1`（客户端广播到 [ServerConfig.discoveryPort]）
 * - 应答：`MJ_ROOMS/1` + `\n` + [DiscoveryResponse] JSON（单播回查询方）
 * - 主动通告：每 2s 向 255.255.255.255 与各网卡广播地址发送同一应答
 *
 * 端口可被占用时只降级（打日志），不影响 WebSocket 主服务。
 */
class DiscoveryServer(
    private val config: ServerConfig,
    private val rooms: RoomManager,
    private val wsPort: Int,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(config.discoveryPort))
            }
        } catch (e: Exception) {
            running.set(false)
            log.warn("局域网发现启动失败（UDP {}）：{}", config.discoveryPort, e.message)
            return
        }
        socket = sock
        job = scope.launch(Dispatchers.IO) {
            launch { receiveLoop(sock) }
            launch { announceLoop(sock) }
        }
        log.info(
            "局域网发现已启动：UDP {} 广播，房间 {} 个，WebSocket 端口 {}",
            config.discoveryPort,
            rooms.count(),
            wsPort,
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    /** 立即构造一条应答，供自测/调试直接调用。 */
    fun snapshot(): String = encode()

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(512)
        while (running.get() && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (text.equals(QUERY, ignoreCase = true)) {
                    val bytes = encode().toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            } catch (e: SocketException) {
                if (running.get()) log.debug("发现 socket 已关闭：{}", e.message)
                return
            } catch (e: Exception) {
                if (running.get()) log.warn("处理发现请求失败：{}", e.message)
            }
        }
    }

    private suspend fun announceLoop(sock: DatagramSocket) {
        while (running.get() && !sock.isClosed) {
            try {
                val bytes = encode().toByteArray(Charsets.UTF_8)
                for (address in broadcastAddresses()) {
                    runCatching {
                        sock.send(DatagramPacket(bytes, bytes.size, address, config.discoveryPort))
                    }
                }
            } catch (e: Exception) {
                log.debug("广播房间失败：{}", e.message)
            }
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private fun encode(): String {
        val payload = DiscoveryResponse(
            host = localAddresses().firstOrNull() ?: "127.0.0.1",
            wsPort = wsPort,
            discoveryPort = config.discoveryPort,
            rooms = rooms.summaries().map { it.toDiscovery() },
        )
        return PREFIX + "\n" + json.encodeToString(payload)
    }

    private fun RoomSummary.toDiscovery() = DiscoveryRoom(
        code = code,
        ruleSet = ruleSet,
        phase = phase,
        host = host,
        players = players,
        humans = humans,
        capacity = capacity,
        hasPassword = hasPassword,
    )

    private fun broadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        runCatching { result += InetAddress.getByName("255.255.255.255") }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { result += it }
        }
        return result.toList()
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    companion object {
        const val QUERY = "MJ?1"
        const val PREFIX = "MJ_ROOMS/1"
        const val ANNOUNCE_INTERVAL_MS = 2_000L
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        private val log = org.slf4j.LoggerFactory.getLogger(DiscoveryServer::class.java)
    }
}

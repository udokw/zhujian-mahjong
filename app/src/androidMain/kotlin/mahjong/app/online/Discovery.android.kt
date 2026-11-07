package mahjong.app.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

actual suspend fun discoverNearbyRooms(
    port: Int,
    timeoutMs: Int,
    unicastHosts: List<String>,
): List<DiscoveredRoom> =
    withContext(Dispatchers.IO) {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return@withContext emptyList()
        try {
            socket.broadcast = true
            socket.soTimeout = 200
            val query = DISCOVERY_QUERY.toByteArray(Charsets.UTF_8)
            runCatching {
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), port))
            }
            // 广播在模拟器 NAT / 禁广播的 Wi-Fi 里出不去，再对已知服务器地址单播问一次。
            unicastHosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { host ->
                runCatching {
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
                }
            }
            val found = LinkedHashMap<String, DiscoveredRoom>()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8_192)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseDiscoveryRooms(text, packet.address?.hostAddress ?: "").forEach { room ->
                    found["${room.serverHost}:${room.wsPort}/${room.code}"] = room
                }
            }
            found.values.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { socket.close() }
        }
    }

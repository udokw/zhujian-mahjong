package mahjong.app.online

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SO_BROADCAST
import platform.posix.SO_RCVTIMEO
import platform.posix.SOL_SOCKET
import platform.posix.close
import platform.posix.htons
import platform.posix.inet_addr
import platform.posix.memset
import platform.posix.ntohl
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
actual suspend fun discoverNearbyRooms(
    port: Int,
    timeoutMs: Int,
    unicastHosts: List<String>,
): List<DiscoveredRoom> =
    withContext(Dispatchers.Default) {
        memScoped {
            val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) return@memScoped emptyList()
            try {
                val enable = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_BROADCAST, enable.ptr, sizeOf<IntVar>().convert())
                val wait = alloc<timeval>().apply {
                    tv_sec = 0
                    tv_usec = 200_000
                }
                setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())

                val query = DISCOVERY_QUERY.encodeToByteArray()
                // 广播之外再单播问一次已知服务器，兼容禁广播的 Wi-Fi。
                val targets = (
                    listOf("255.255.255.255") +
                        unicastHosts.map { it.trim() }.filter { it.isNotEmpty() }
                    ).distinct()
                for (address in targets) {
                    val target = alloc<sockaddr_in>()
                    memset(target.ptr, 0, sizeOf<sockaddr_in>().convert())
                    target.sin_family = AF_INET.convert()
                    target.sin_port = htons(port.toUShort())
                    target.sin_addr.s_addr = inet_addr(address)
                    query.usePinned { pinned ->
                        sendto(
                            fd,
                            pinned.addressOf(0),
                            query.size.convert(),
                            0,
                            target.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().convert(),
                        )
                    }
                }

                val found = LinkedHashMap<String, DiscoveredRoom>()
                val buffer = ByteArray(8_192)
                val started = TimeSource.Monotonic.markNow()
                while (started.elapsedNow().inWholeMilliseconds < timeoutMs) {
                    buffer.usePinned { pinned ->
                        val from = alloc<sockaddr_in>()
                        val fromLen = alloc<UIntVar>().apply {
                            value = sizeOf<sockaddr_in>().convert()
                        }
                        val read = recvfrom(
                            fd,
                            pinned.addressOf(0),
                            buffer.size.convert(),
                            0,
                            from.ptr.reinterpret(),
                            fromLen.ptr,
                        )
                        if (read > 0) {
                            val text = buffer.decodeToString(0, read.toInt())
                            val ip = ntohl(from.sin_addr.s_addr)
                            val host = "${(ip shr 24) and 0xFFu}.${(ip shr 16) and 0xFFu}." +
                                "${(ip shr 8) and 0xFFu}.${ip and 0xFFu}"
                            parseDiscoveryRooms(text, host).forEach { room ->
                                found["${room.serverHost}:${room.wsPort}/${room.code}"] = room
                            }
                        }
                    }
                }
                found.values.toList()
            } catch (e: Exception) {
                emptyList()
            } finally {
                close(fd)
            }
        }
    }

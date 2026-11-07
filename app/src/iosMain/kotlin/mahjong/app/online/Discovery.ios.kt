package mahjong.app.online

import kotlinx.cinterop.*
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
import platform.posix.memset
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
                    target.sin_port = htons(port)
                    target.sin_addr.s_addr = inetAddr(address)
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
                            val host = ipv4Text(from.sin_addr.s_addr)
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

// Darwin 的 htons/inet_addr/ntohl 在头文件里是宏或内联函数，Kotlin/Native 没有导出，
// 这里按小端设备（所有 iPhone）手工换算网络字节序，避免依赖平台宏。
private fun htons(value: Int): UShort =
    (((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)).toUShort()

private fun inetAddr(address: String): UInt {
    val parts = address.split('.').map { it.trim().toUIntOrNull() ?: 0u }
    if (parts.size != 4) return 0u
    return ((parts[3] and 0xFFu) shl 24) or
        ((parts[2] and 0xFFu) shl 16) or
        ((parts[1] and 0xFFu) shl 8) or
        (parts[0] and 0xFFu)
}

/** sin_addr.s_addr 的内存布局本身就是网络序的四个字节，小端下最低位就是第一个八位组。 */
private fun ipv4Text(raw: UInt): String =
    "${raw and 0xFFu}.${(raw shr 8) and 0xFFu}.${(raw shr 16) and 0xFFu}.${(raw shr 24) and 0xFFu}"

package mahjong.app.lan

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SO_BROADCAST
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SOL_SOCKET
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.gettimeofday
import platform.posix.memset
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

// 走 POSIX gettimeofday：Kotlin/Native 的 NSDate 未导出 timeIntervalSince1970，
// 而 platform.posix 的时间接口在所有 Apple 目标上都能解析。
@OptIn(ExperimentalForeignApi::class)
actual fun nowWallMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}

actual fun createLanDiscovery(): LanDiscoveryNode = IosLanDiscovery()

@OptIn(ExperimentalForeignApi::class)
private class IosLanDiscovery : LanDiscoveryNode {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var fd: Int = -1
    private val selfIid = "g-ios-" + (nowWallMillis() % 100000).toString(16)

    override fun startAsHost(info: () -> LanRoomInfo) {
        stop()
        val sock = openSocket() ?: return
        fd = sock
        val roomInfo = info()
        LanDiag.mdnsState = "未启用（UDP 广播）"
        LanLog.info("房主发现通道已启动：UDP $LAN_DISCOVERY_PORT，房号 ${roomInfo.code}，TCP ${roomInfo.port}")
        job = runLoop { hostLoop(sock, info) }
    }

    override fun startAsGuest(onPeer: (LanPeer) -> Unit) {
        stop()
        val sock = openSocket() ?: return
        fd = sock
        LanLog.info("客机发现通道已启动：UDP $LAN_DISCOVERY_PORT")
        job = runLoop { guestLoop(sock, onPeer) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        if (fd >= 0) {
            runCatching { close(fd) }
            fd = -1
        }
    }

    private fun runLoop(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            LanLog.warn("发现通道异常", e)
        }
    }

    private fun openSocket(): Int? {
        memScoped {
            val sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (sock < 0) {
                LanDiag.udpBindFailed = "socket 创建失败"
                LanLog.warn("UDP socket 创建失败")
                return null
            }
            val enable = alloc<IntVar>().apply { value = 1 }
            setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, enable.ptr, sizeOf<IntVar>().convert())
            setsockopt(sock, SOL_SOCKET, SO_BROADCAST, enable.ptr, sizeOf<IntVar>().convert())
            val wait = alloc<timeval>().apply {
                tv_sec = 0
                tv_usec = 250_000
            }
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
            addr.sin_family = AF_INET.convert()
            addr.sin_port = htons(LAN_DISCOVERY_PORT)
            addr.sin_addr.s_addr = 0u
            if (bind(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(sock)
                LanDiag.udpBindFailed = "端口 $LAN_DISCOVERY_PORT 绑定失败"
                LanLog.warn("UDP 绑定 $LAN_DISCOVERY_PORT 失败")
                return null
            }
            LanDiag.udpBoundPort = LAN_DISCOVERY_PORT
            return sock
        }
    }

    private suspend fun hostLoop(sock: Int, info: () -> LanRoomInfo) {
        val buffer = ByteArray(2_048)
        var lastAnnounce = 0L
        val targets = lanBroadcastTargets()
        // 注意：必须看协程自身状态，不能看 scope（scope 永不取消，stop 后这里会空转）。
        while (currentCoroutineContext().isActive) {
            val now = nowWallMillis()
            if (now - lastAnnounce >= 2_000 || lastAnnounce == 0L) {
                lastAnnounce = now
                if (sendAll(sock, buildLanPacket(LanPacketType.ANNOUNCE, info()), targets)) {
                    LanDiag.announcesSent++
                }
            }
            val received = receive(sock, buffer) ?: continue
            val (text, sender, port) = received
            val parsed = parseLanPacket(text, sender) ?: continue
            val (type, _) = parsed
            if (type == LanPacketType.QUERY) {
                LanDiag.queriesReceived++
                val reply = buildLanPacket(LanPacketType.REPLY, info())
                val ok = sendTo(sock, reply, sender, port)
                if (ok) LanDiag.repliesSent++
                LanLog.info("收到查询 $sender:$port，已应答=$ok")
            }
        }
    }

    private suspend fun guestLoop(sock: Int, onPeer: (LanPeer) -> Unit) {
        val buffer = ByteArray(2_048)
        var lastQuery = 0L
        val targets = lanBroadcastTargets()
        while (currentCoroutineContext().isActive) {
            val now = nowWallMillis()
            if (now - lastQuery >= 1_500 || lastQuery == 0L) {
                lastQuery = now
                if (sendAll(sock, buildLanQuery(selfIid), targets)) LanDiag.queriesSent++
            }
            val received = receive(sock, buffer) ?: continue
            val (text, sender, _) = received
            val parsed = parseLanPacket(text, sender) ?: continue
            val (type, packet) = parsed
            when (type) {
                LanPacketType.REPLY -> LanDiag.repliesReceived++
                LanPacketType.ANNOUNCE -> LanDiag.announcesReceived++
                LanPacketType.QUERY -> continue
            }
            if (packet.iid == selfIid) continue
            val peer = packet.toPeer(sender)
            LanDirectory.upsert(peer)
            onPeer(peer)
        }
    }

    private data class Received(val text: String, val host: String, val port: Int)

    private fun receive(sock: Int, buffer: ByteArray): Received? = memScoped {
        val from = alloc<sockaddr_in>()
        val fromLen = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        val read = buffer.usePinned { pinned ->
            recvfrom(
                sock,
                pinned.addressOf(0),
                buffer.size.convert(),
                0,
                from.ptr.reinterpret(),
                fromLen.ptr,
            )
        }
        if (read <= 0) return null
        Received(
            text = buffer.decodeToString(0, read.toInt()),
            host = ipv4Text(from.sin_addr.s_addr),
            port = ntohs(from.sin_port),
        )
    }

    private fun sendAll(sock: Int, text: String, targets: List<String>): Boolean {
        var any = false
        for (target in targets) {
            if (sendTo(sock, text, target, LAN_DISCOVERY_PORT)) any = true
        }
        return any
    }

    private fun sendTo(sock: Int, text: String, host: String, port: Int): Boolean {
        if (port <= 0) return false
        return memScoped {
            val target = alloc<sockaddr_in>()
            memset(target.ptr, 0, sizeOf<sockaddr_in>().convert())
            target.sin_family = AF_INET.convert()
            target.sin_port = htons(port)
            target.sin_addr.s_addr = inetAddr(host)
            val bytes = text.encodeToByteArray()
            val sent = bytes.usePinned { pinned ->
                sendto(
                    sock,
                    pinned.addressOf(0),
                    bytes.size.convert(),
                    0,
                    target.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().convert(),
                )
            }
            sent > 0
        }
    }
}

internal fun ntohs(value: UShort): Int = ((value.toInt() and 0xFF) shl 8) or ((value.toInt() shr 8) and 0xFF)

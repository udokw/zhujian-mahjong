package mahjong.app.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mahjong.app.PlatformContext
import mahjong.app.platformDeviceId
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

actual fun createLanDiscovery(): LanDiscoveryNode = AndroidLanDiscovery()

private class AndroidLanDiscovery : LanDiscoveryNode {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var nsd: AndroidNsd? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val selfIid: String by lazy { "g-" + guestPlayerId(platformDeviceId()).takeLast(8) }

    override fun startAsHost(info: () -> LanRoomInfo) {
        stop()
        val sock = openSocket()
        socket = sock
        if (sock == null) {
            LanDiag.udpBindFailed = "端口 $LAN_DISCOVERY_PORT 不可用"
            LanLog.warn("房主发现通道启动失败：UDP $LAN_DISCOVERY_PORT 绑定失败")
            return
        }
        LanDiag.udpBoundPort = sock.localPort
        LanDiag.mdnsState = "注册中"
        acquireMulticastLock()
        val roomInfo = info()
        nsd = AndroidNsd().also { it.advertise(roomInfo) }
        LanLog.info("房主发现通道已启动：UDP ${sock.localPort}，房号 ${roomInfo.code}，TCP ${roomInfo.port}")
        job = scope.launch { hostLoop(sock, info) }
    }

    override fun startAsGuest(onPeer: (LanPeer) -> Unit) {
        stop()
        val sock = openSocket()
        socket = sock
        if (sock == null) {
            LanDiag.udpBindFailed = "端口 $LAN_DISCOVERY_PORT 不可用"
            LanLog.warn("客机发现通道启动失败：UDP $LAN_DISCOVERY_PORT 绑定失败")
            return
        }
        LanDiag.udpBoundPort = sock.localPort
        LanDiag.mdnsState = "浏览中"
        acquireMulticastLock()
        nsd = AndroidNsd().also { it.browse() }
        LanLog.info("客机发现通道已启动：UDP ${sock.localPort}")
        job = scope.launch { guestLoop(sock, onPeer) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        nsd?.stop()
        nsd = null
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        runCatching {
            val context = PlatformContext.app
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("mahjong-lan")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            multicastLock = lock
        }
    }

    /** 绑定 45231；若刚释放还没回收，短暂重试并使用 SO_REUSEADDR。 */
    private fun openSocket(): DatagramSocket? {
        repeat(6) { attempt ->
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.broadcast = true
                sock.soTimeout = 250
                sock.bind(InetSocketAddress(LAN_DISCOVERY_PORT))
                return sock
            } catch (e: Exception) {
                if (attempt == 5) {
                    LanLog.warn("UDP 绑定 ${LAN_DISCOVERY_PORT} 失败", e)
                    return null
                }
                Thread.sleep(120)
            }
        }
        return null
    }

    private suspend fun hostLoop(sock: DatagramSocket, info: () -> LanRoomInfo) {
        val buffer = ByteArray(2_048)
        var lastAnnounce = 0L
        val targets = lanBroadcastTargets()
        while (scope.isActive && !sock.isClosed) {
            val now = nowWallMillis()
            if (now - lastAnnounce >= 2_000 || lastAnnounce == 0L) {
                lastAnnounce = now
                val packet = buildLanPacket(LanPacketType.ANNOUNCE, info())
                if (sendAll(sock, packet, targets)) LanDiag.announcesSent++
            }
            try {
                val incoming = DatagramPacket(buffer, buffer.size)
                sock.receive(incoming)
                val text = buffer.decodeToString(0, incoming.length)
                val sender = incoming.address?.hostAddress ?: continue
                val parsed = parseLanPacket(text, sender)
                if (parsed == null) {
                    LanDiag.lastPeer = "invalid from $sender"
                    continue
                }
                val (type, packet) = parsed
                when (type) {
                    LanPacketType.QUERY -> {
                        LanDiag.queriesReceived++
                        val reply = buildLanPacket(LanPacketType.REPLY, info())
                        val ok = sendTo(sock, reply, incoming.address, incoming.port)
                        if (ok) LanDiag.repliesSent++
                        LanLog.info("收到查询 $sender:${incoming.port}，已应答=${ok}")
                    }
                    else -> Unit
                }
            } catch (e: SocketTimeoutException) {
                // 正常：周期性醒来广播。
            } catch (e: SocketException) {
                if (!sock.isClosed) LanLog.warn("房主发现 socket 异常", e)
                break
            }
        }
    }

    private suspend fun guestLoop(sock: DatagramSocket, onPeer: (LanPeer) -> Unit) {
        val buffer = ByteArray(2_048)
        var lastQuery = 0L
        val targets = lanBroadcastTargets()
        while (scope.isActive && !sock.isClosed) {
            val now = nowWallMillis()
            if (now - lastQuery >= 1_500 || lastQuery == 0L) {
                lastQuery = now
                val query = buildLanQuery(selfIid)
                if (sendAll(sock, query, targets)) LanDiag.queriesSent++
            }
            try {
                val incoming = DatagramPacket(buffer, buffer.size)
                sock.receive(incoming)
                val text = buffer.decodeToString(0, incoming.length)
                val sender = incoming.address?.hostAddress ?: continue
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
            } catch (e: SocketTimeoutException) {
                // 正常。
            } catch (e: SocketException) {
                if (!sock.isClosed) LanLog.warn("客机发现 socket 异常", e)
                break
            }
        }
    }

    private fun sendAll(sock: DatagramSocket, text: String, targets: List<String>): Boolean {
        var any = false
        for (target in targets) {
            runCatching { InetAddress.getByName(target) }.getOrNull()?.let { address ->
                if (sendTo(sock, text, address, LAN_DISCOVERY_PORT)) any = true
            }
        }
        return any
    }

    private fun sendTo(sock: DatagramSocket, text: String, address: InetAddress?, port: Int): Boolean {
        if (address == null) return false
        return try {
            val bytes = text.encodeToByteArray()
            sock.send(DatagramPacket(bytes, bytes.size, address, port))
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Android 的 mDNS（NsdManager）补充通道；失败不影响 UDP 广播。 */
private class AndroidNsd {
    private val manager: NsdManager? by lazy {
        runCatching {
            PlatformContext.app.getSystemService(Context.NSD_SERVICE) as NsdManager
        }.getOrNull()
    }
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = HashSet<String>()

    fun advertise(info: LanRoomInfo) {
        val nsd = manager ?: return
        val service = NsdServiceInfo().apply {
            serviceName = "mj-${info.code}-${info.iid.takeLast(4)}"
            serviceType = LAN_SERVICE_TYPE
            setPort(info.port)
            setAttribute("game", LAN_GAME_ID)
            setAttribute("room", info.code)
            setAttribute("port", info.port.toString())
            setAttribute("iid", info.iid)
            setAttribute("rule", info.ruleSet)
            setAttribute("players", info.players.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                LanDiag.mdnsState = "已广播 ${info.serviceName}"
                LanLog.info("mDNS 已广播 ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                LanDiag.mdnsState = "广播失败 $errorCode"
                LanLog.warn("mDNS 广播失败 code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                LanDiag.mdnsState = "已停止"
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                LanDiag.mdnsState = "注册异常"
                LanLog.warn("mDNS 注册异常", it)
            }
    }

    fun browse() {
        val nsd = manager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                LanDiag.mdnsState = "浏览中"
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("mahjong")) return
                val key = service.serviceName ?: return
                if (!resolving.add(key)) return
                runCatching {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolving.remove(key)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolving.remove(key)
                            val host = info.host?.hostAddress ?: return
                            val attrs = info.attributes ?: return
                            val room = attrs["room"]?.decodeToString() ?: return
                            val iid = attrs["iid"]?.decodeToString() ?: return
                            val peer = LanPeer(
                                code = room,
                                ruleSet = attrs["rule"]?.decodeToString() ?: "",
                                phase = "waiting",
                                host = host,
                                port = info.port,
                                players = attrs["players"]?.decodeToString()?.toIntOrNull() ?: 0,
                                humans = 0,
                                capacity = 4,
                                hasPassword = false,
                                iid = iid,
                                lastSeen = mahjong.app.util.Clock.now(),
                            )
                            LanDirectory.upsert(peer)
                            LanDiag.mdnsState = "已解析 ${peer.code}@$host:${info.port}"
                            LanLog.info("mDNS 发现 ${peer.code}@$host:${info.port}")
                        }
                    })
                }.onFailure { resolving.remove(key) }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LanDiag.mdnsState = "浏览失败 $errorCode"
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                LanDiag.mdnsState = "启动浏览异常"
                LanLog.warn("mDNS 浏览异常", it)
            }
    }

    fun stop() {
        val nsd = manager ?: return
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }
}

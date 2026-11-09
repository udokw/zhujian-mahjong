package mahjong.app.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

actual fun nowWallMillis(): Long = System.currentTimeMillis()

actual fun lanListen(ports: IntRange, onAccept: (LanConn) -> Unit): LanServer? {
    for (port in ports) {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 500
            }
        } catch (e: Exception) {
            continue
        }
        val thread = Thread({ acceptLoop(server, onAccept) }, "lan-accept-$port").apply { isDaemon = true }
        thread.start()
        LanDiag.tcpBoundPort = port
        LanLog.info("TCP 监听成功 :$port（本机 ${lanLocalIpv4()}）")
        return object : LanServer {
            override val port: Int = port
            override fun stop() {
                runCatching { server.close() }
            }
        }
    }
    LanLog.warn("TCP 监听失败：$ports 全被占用")
    return null
}

private fun acceptLoop(server: ServerSocket, onAccept: (LanConn) -> Unit) {
    while (!server.isClosed) {
        try {
            val socket = server.accept()
            LanDiag.tcpAccepted++
            LanDiag.lastPeer = socket.inetAddress?.hostAddress ?: "?"
            LanLog.info("TCP 接入 ${socket.inetAddress?.hostAddress}:${socket.port}")
            onAccept(AndroidLanConn(socket))
        } catch (e: SocketTimeoutException) {
            // 周期性醒来检查 server 是否已关闭。
        } catch (e: Exception) {
            if (!server.isClosed) LanLog.warn("accept 失败", e)
        }
    }
}

actual suspend fun lanConnect(
    host: String,
    port: Int,
    timeoutMs: Int,
    onText: (String) -> Unit,
    onClosed: () -> Unit,
): LanConn? = withContext(Dispatchers.IO) {
    try {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        LanDiag.tcpConnected++
        LanDiag.lastPeer = "$host:$port"
        LanLog.info("TCP 连接成功 $host:$port")
        val conn = AndroidLanConn(socket)
        conn.onClosed = onClosed
        conn.onText = onText
        conn
    } catch (e: Exception) {
        LanLog.warn("TCP 连接失败 $host:$port", e)
        null
    }
}

actual fun lanLocalIpv4(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in nif.inetAddresses) {
                val ip = address.hostAddress ?: continue
                if (address.isLoopbackAddress || ip.contains(':')) continue
                if (ip.startsWith("169.254.")) continue
                return ip
            }
        }
        "127.0.0.1"
    } catch (e: Exception) {
        "127.0.0.1"
    }
}

private class AndroidLanConn(private val socket: Socket) : LanConn {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    @Volatile
    private var closed = false

    private val closeGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private var readerStarted = false

    // 发送统一走独立写线程：调用方常在主线程（Compose 事件），
    // 直接 output.write 会抛 NetworkOnMainThreadException；队列同时保证帧序。
    private val writeQueue = LinkedBlockingQueue<ByteArray>(256)

    override val remote: String = socket.inetAddress?.hostAddress ?: "?"

    private val writeWorker = Thread({ writeLoop() }, "lan-write").apply {
        isDaemon = true
        start()
    }

    override var onText: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) startReader()
        }

    override var onClosed: (() -> Unit)? = null

    init {
        runCatching { socket.soTimeout = 250 }
        // 房主 accept 出来的连接默认开着 Nagle，状态帧很小，会被合并到几十毫秒后才发出；
        // 客机端连出时已设置，这里补齐房主侧，保证双向都即时。
        runCatching { socket.tcpNoDelay = true }
    }

    private fun startReader() {
        if (readerStarted) return
        readerStarted = true
        Thread({ readLoop() }, "lan-conn-$remote").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val header = ByteArray(4)
        var headerRead = 0
        var body: ByteArray? = null
        var bodyRead = 0
        try {
            while (!closed) {
                try {
                    val current = body
                    if (current == null) {
                        val n = input.read(header, headerRead, 4 - headerRead)
                        if (n < 0) break
                        headerRead += n
                        if (headerRead < 4) continue
                        val len = LanFrames.bodyLength(header)
                        headerRead = 0
                        if (len < 0) {
                            LanLog.warn("收到非法帧长度，断开 $remote")
                            break
                        }
                        if (len == 0) {
                            onText?.invoke("")
                            continue
                        }
                        body = ByteArray(len)
                        bodyRead = 0
                    } else {
                        val n = input.read(current, bodyRead, current.size - bodyRead)
                        if (n < 0) break
                        bodyRead += n
                        if (bodyRead >= current.size) {
                            body = null
                            onText?.invoke(current.decodeToString())
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // 超时只是让线程周期性醒来，继续下一轮。
                }
            }
        } catch (e: Exception) {
            if (!closed) LanLog.warn("TCP 读取中断 $remote", e)
        } finally {
            if (!closed) close()
        }
    }

    override fun send(text: String) {
        if (closed) return
        if (!writeQueue.offer(LanFrames.encode(text))) {
            LanLog.warn("发送队列已满，丢弃一帧 $remote")
        }
    }

    private fun writeLoop() {
        try {
            // 收到空帧（关闭哨兵）才退出，保证 close() 之前入队的帧能写完。
            while (true) {
                val frame = writeQueue.take()
                if (frame.isEmpty()) break
                output.write(frame)
                output.flush()
            }
        } catch (e: Exception) {
            if (!closed) {
                LanLog.warn("TCP 发送失败 $remote", e)
                close()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!closeGuard.compareAndSet(false, true)) return
        closed = true
        // 先投递哨兵让写线程把已入队的帧（尤其是被踢时的 room_kicked）刷出去，再关 socket；
        // 直接 socket.close() 会把队列里最后一帧丢掉。
        writeQueue.offer(ByteArray(0))
        if (Thread.currentThread() !== writeWorker) {
            runCatching { writeWorker.join(400) }
        }
        runCatching { socket.close() }
        onClosed?.invoke()
        onClosed = null
    }
}

package mahjong.app.lan

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.IPPROTO_UDP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SOL_SOCKET
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
private val lanIoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@OptIn(ExperimentalForeignApi::class)
actual fun lanListen(ports: IntRange, onAccept: (LanConn) -> Unit): LanServer? {
    for (port in ports) {
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
            if (fd < 0) return null
            val enable = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, enable.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
            addr.sin_family = AF_INET.convert()
            addr.sin_port = htons(port)
            addr.sin_addr.s_addr = 0u
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(fd)
                return@memScoped
            }
            if (listen(fd, 8) != 0) {
                close(fd)
                return@memScoped
            }
            // 非阻塞 accept：靠短 sleep 轮询，stop() 时立即释放。
            fcntl(fd, F_SETFL, O_NONBLOCK)
            val handle = IosLanServer(port, fd)
            LanDiag.tcpBoundPort = port
            LanLog.info("TCP 监听成功 :$port（本机 ${lanLocalIpv4()}）")
            lanIoScope.launch { acceptLoop(fd, handle, onAccept) }
            return handle
        }
    }
    LanLog.warn("TCP 监听失败：$ports 全被占用")
    return null
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun acceptLoop(fd: Int, handle: IosLanServer, onAccept: (LanConn) -> Unit) {
    while (!handle.stopped) {
        val client = accept(fd, null, null)
        if (client < 0) {
            delay(120)
            continue
        }
        LanDiag.tcpAccepted++
        LanLog.info("TCP 接入 #$client")
        onAccept(IosLanConn(client))
    }
    close(fd)
}

@OptIn(ExperimentalForeignApi::class)
private class IosLanServer(override val port: Int, private val fd: Int) : LanServer {
    var stopped = false
        private set

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { close(fd) }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun lanConnect(
    host: String,
    port: Int,
    timeoutMs: Int,
    onText: (String) -> Unit,
    onClosed: () -> Unit,
): LanConn? = withContext(Dispatchers.Default) {
    memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (fd < 0) return@memScoped null
        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port)
        addr.sin_addr.s_addr = inetAddr(host)
        if (connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            close(fd)
            LanLog.warn("TCP 连接失败 $host:$port")
            return@memScoped null
        }
        LanDiag.tcpConnected++
        LanDiag.lastPeer = "$host:$port"
        LanLog.info("TCP 连接成功 $host:$port")
        val conn = IosLanConn(fd)
        conn.onClosed = onClosed
        conn.onText = onText
        conn
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun lanLocalIpv4(): String {
    // platform.posix 没有导出 getifaddrs/ifaddrs，改用"UDP connect 探路由 + getsockname"：
    // 不实际发包，只让内核按默认出口网卡填上源地址。
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) return "127.0.0.1"
    try {
        return memScoped {
            val target = alloc<sockaddr_in>()
            memset(target.ptr, 0, sizeOf<sockaddr_in>().convert())
            target.sin_family = AF_INET.convert()
            target.sin_port = htons(53)
            target.sin_addr.s_addr = inetAddr("8.8.8.8")
            if (connect(fd, target.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                return@memScoped "127.0.0.1"
            }
            val local = alloc<sockaddr_in>()
            val len = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            if (getsockname(fd, local.ptr.reinterpret(), len.ptr) != 0) {
                return@memScoped "127.0.0.1"
            }
            ipv4Text(local.sin_addr.s_addr)
        }
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosLanConn(private val fd: Int) : LanConn {
    private var closed = false

    private var readerStarted = false

    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var writerStarted = false

    override val remote: String = "tcp"

    override var onText: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) startReader()
        }

    override var onClosed: (() -> Unit)? = null

    private fun startReader() {
        if (readerStarted) return
        readerStarted = true
        // 250ms 接收超时：既不会永久占用线程，也能周期性检查 closed。
        memScoped {
            val wait = alloc<timeval>().apply {
                tv_sec = 0
                tv_usec = 250_000
            }
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
        }
        lanIoScope.launch { readLoop() }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readLoop() {
        val header = ByteArray(4)
        var headerRead = 0
        var body: ByteArray? = null
        var bodyRead = 0
        val buffer = ByteArray(LanFrames.MAX_FRAME)
        while (!closed) {
            val current = body
            if (current == null) {
                val want = 4 - headerRead
                val n = buffer.usePinned { pinned ->
                    recv(fd, pinned.addressOf(0), want.convert(), 0)
                }
                if (n == 0L) break
                if (n < 0) continue
                buffer.copyInto(header, headerRead, 0, n.toInt())
                headerRead += n.toInt()
                if (headerRead < 4) continue
                val len = LanFrames.bodyLength(header)
                headerRead = 0
                if (len < 0) {
                    LanLog.warn("收到非法帧长度，断开")
                    break
                }
                if (len == 0) {
                    onText?.invoke("")
                    continue
                }
                body = ByteArray(len)
                bodyRead = 0
            } else {
                val want = current.size - bodyRead
                val n = buffer.usePinned { pinned ->
                    recv(fd, pinned.addressOf(0), want.convert(), 0)
                }
                if (n == 0L) break
                if (n < 0) continue
                buffer.copyInto(current, bodyRead, 0, n.toInt())
                bodyRead += n.toInt()
                if (bodyRead >= current.size) {
                    body = null
                    onText?.invoke(current.decodeToString())
                }
            }
        }
        if (!closed) close()
    }

    override fun send(text: String) {
        if (closed) return
        if (writeQueue.trySend(LanFrames.encode(text)).isFailure) {
            LanLog.warn("发送队列不可用，丢弃一帧")
        }
        startWriter()
    }

    /** 发送统一走 lanIoScope：调用方可能在主线程，阻塞 send 会卡 UI。 */
    private fun startWriter() {
        if (writerStarted) return
        writerStarted = true
        lanIoScope.launch {
            try {
                // 队列被 close() 关闭后仍会把已入队的帧写完：最后一帧常常是
                // room_kicked / room_closed，提前 break 会让对端永远收不到通知。
                for (frame in writeQueue) {
                    var offset = 0
                    var failed = false
                    while (offset < frame.size) {
                        // 显式写成 platform.posix.send：本类有同名成员 send(String)，
                        // 不加限定符容易踩到重载解析的坑。
                        val written = frame.usePinned { pinned ->
                            platform.posix.send(fd, pinned.addressOf(offset), (frame.size - offset).convert(), 0)
                        }
                        if (written <= 0) {
                            failed = true
                            break
                        }
                        offset += written.toInt()
                    }
                    if (failed) {
                        if (!closed) {
                            LanLog.warn("TCP 发送失败")
                            close()
                        }
                        break
                    }
                }
            } finally {
                // fd 由写协程统一关闭：既保证最后一帧已刷出，也避免重复 close。
                if (closed) runCatching { platform.posix.close(fd) }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 只关队列入口，不再直接关 fd；写完队列后由写协程收尾。
        writeQueue.close()
        startWriter()
        onClosed?.invoke()
        onClosed = null
    }
}

// Darwin 的 htons / inet_addr 是宏，Kotlin/Native 未导出；按小端设备手工换算。
internal fun htons(value: Int): UShort =
    (((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)).toUShort()

internal fun inetAddr(address: String): UInt {
    val parts = address.trim().split('.').map { it.toUIntOrNull() ?: 0u }
    if (parts.size != 4) return 0u
    return ((parts[3] and 0xFFu) shl 24) or
        ((parts[2] and 0xFFu) shl 16) or
        ((parts[1] and 0xFFu) shl 8) or
        (parts[0] and 0xFFu)
}

internal fun ipv4Text(raw: UInt): String =
    "${raw and 0xFFu}.${(raw shr 8) and 0xFFu}.${(raw shr 16) and 0xFFu}.${(raw shr 24) and 0xFFu}"

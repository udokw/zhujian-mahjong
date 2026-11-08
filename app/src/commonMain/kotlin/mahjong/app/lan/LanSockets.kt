package mahjong.app.lan

/** 一条已建立的 TCP 连接。send / close 可跨线程调用。 */
interface LanConn {
    val remote: String

    /** 收到一条完整帧。赋值后读取线程才开始工作，避免漏掉首包。 */
    var onText: ((String) -> Unit)?
    var onClosed: (() -> Unit)?
    fun send(text: String)
    fun close()
}

/** 已监听的服务端句柄。 */
interface LanServer {
    val port: Int
    fun stop()
}

/**
 * 在 [ports] 中顺序尝试绑定，返回第一个成功的监听；全部失败返回 null。
 * [onAccept] 在 IO 线程被回调，实现方不得在回调里做重活。
 */
expect fun lanListen(ports: IntRange, onAccept: (LanConn) -> Unit): LanServer?

/**
 * 连接 host:port。成功后持续把收到的完整帧回调 [onText]；断线回调 [onClosed]。
 * 连接失败返回 null。
 */
expect suspend fun lanConnect(
    host: String,
    port: Int,
    timeoutMs: Int = 4000,
    onText: (String) -> Unit,
    onClosed: () -> Unit,
): LanConn?

/** 本机当前 Wi-Fi / 热点网段的 IPv4，拿不到时返回 "127.0.0.1"。 */
expect fun lanLocalIpv4(): String

/** 4 字节大端长度前缀 + UTF-8 JSON 正文。 */
object LanFrames {
    const val MAX_FRAME = 256 * 1024

    fun encode(text: String): ByteArray {
        val body = text.encodeToByteArray()
        require(body.size <= MAX_FRAME) { "帧过大: ${body.size}" }
        val out = ByteArray(4 + body.size)
        val len = body.size
        out[0] = ((len ushr 24) and 0xFF).toByte()
        out[1] = ((len ushr 16) and 0xFF).toByte()
        out[2] = ((len ushr 8) and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        body.copyInto(out, 4)
        return out
    }

    fun bodyLength(header: ByteArray): Int {
        if (header.size < 4) return -1
        val len = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        return if (len in 0..MAX_FRAME) len else -1
    }
}

package mahjong.app.lan

import kotlinx.coroutines.sync.Mutex

/**
 * 联机日志：只保留最近 N 条，供「联机诊断」页复制给对面排查。
 * 使用 tryLock，避免非挂起调用方被阻塞；拿不到锁时丢一条，不影响正确性。
 */
object LanLog {
    private const val MAX_LINES = 160
    private val mutex = Mutex()
    private val lines = ArrayList<String>(MAX_LINES)

    fun info(message: String) = append("I", message)

    fun warn(message: String, error: Throwable? = null) =
        append("W", if (error == null) message else "$message | ${error::class.simpleName}: ${error.message}")

    fun reset() {
        if (!mutex.tryLock()) return
        try {
            lines.clear()
        } finally {
            mutex.unlock()
        }
    }

    fun dump(): String {
        if (!mutex.tryLock()) return "（日志繁忙，请稍后重试）"
        try {
            return if (lines.isEmpty()) "（暂无联机日志）" else lines.joinToString("\n")
        } finally {
            mutex.unlock()
        }
    }

    private fun append(level: String, message: String) {
        if (!mutex.tryLock()) return
        try {
            lines.add("$level ${DiagClock.stamp()} $message")
            if (lines.size > MAX_LINES) {
                lines.subList(0, lines.size - MAX_LINES).clear()
            }
        } finally {
            mutex.unlock()
        }
    }
}

/** 联机计数：两端对不上时，先对比这些数字。 */
object LanDiag {
    var udpBoundPort: Int = 0
    var udpBindFailed: String = ""
    var queriesReceived: Long = 0
    var repliesSent: Long = 0
    var announcesSent: Long = 0
    var queriesSent: Long = 0
    var repliesReceived: Long = 0
    var announcesReceived: Long = 0
    var mdnsState: String = "未启动"
    var tcpBoundPort: Int = 0
    var tcpAccepted: Long = 0
    var tcpConnected: Long = 0
    var lastPeer: String = "-"

    fun reset() {
        udpBoundPort = 0
        udpBindFailed = ""
        queriesReceived = 0
        repliesSent = 0
        announcesSent = 0
        queriesSent = 0
        repliesReceived = 0
        announcesReceived = 0
        mdnsState = "未启动"
        tcpBoundPort = 0
        tcpAccepted = 0
        tcpConnected = 0
        lastPeer = "-"
    }

    fun snapshot(): String = buildString {
        appendLine("UDP 绑定端口: ${if (udpBoundPort > 0) udpBoundPort else "失败 $udpBindFailed"}")
        appendLine("mDNS: $mdnsState")
        appendLine("TCP 监听端口: $tcpBoundPort（已接入 $tcpAccepted）")
        appendLine("本机作为房主: 收到查询 $queriesReceived / 发出应答 $repliesSent / 广播 $announcesSent")
        appendLine("本机作为客机: 发出查询 $queriesSent / 收应答 $repliesReceived / 收广播 $announcesReceived")
        appendLine("最近连接: $lastPeer")
    }
}

/** 诊断时间戳：只用于日志可读性，不参与协议。 */
object DiagClock {
    fun stamp(): String {
        val ms = nowWallMillis()
        val totalSec = ms / 1000
        val h = (totalSec / 3600 + 8) % 24
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        val milli = ms % 1000
        return buildString {
            if (h < 10) append('0'); append(h); append(':')
            if (m < 10) append('0'); append(m); append(':')
            if (s < 10) append('0'); append(s); append('.')
            append(milli.toString().padStart(3, '0'))
        }
    }
}

/** 墙上时钟毫秒（仅诊断与发现 TTL 使用）。 */
expect fun nowWallMillis(): Long

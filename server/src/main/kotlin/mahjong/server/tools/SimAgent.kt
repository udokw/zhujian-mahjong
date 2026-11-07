package mahjong.server.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import mahjong.server.protocol.Envelope
import mahjong.server.protocol.Wire
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 端到端脚本用的最小 WebSocket 客户端：只用 JDK 自带 API，不引入额外依赖。
 */
class SimAgent(val name: String, private val uri: URI) {
    private val http = HttpClient.newHttpClient()
    private val inbox = LinkedBlockingQueue<Envelope>()
    private val cseq = AtomicLong()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var closed: Boolean = false

    var playerId: String? = null
    var seat: Int = -1
    var resumeToken: String? = null
    var room: String = ""
    var lastSseq: Long = 0
    var lastActedTurn: Int = -1
    var lastWindowKey: String = ""
    val errors = mutableListOf<String>()

    fun connect(timeoutMs: Long = 10_000) {
        val opened = CountDownLatch(1)
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                opened.countDown()
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching { Wire.decode(text) }
                        .onSuccess { envelope ->
                            observe(envelope)
                            inbox.put(envelope)
                        }
                        .onFailure { errors.add("无法解析服务端消息: ${it.message}") }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed = true
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                closed = true
                errors.add("连接错误: ${error.message}")
            }
        }
        socket = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .buildAsync(uri, listener)
            .get(timeoutMs, TimeUnit.MILLISECONDS)
        check(opened.await(timeoutMs, TimeUnit.MILLISECONDS)) { "$name 连接超时" }
    }

    fun send(type: String, payload: JsonObject = JsonObject(emptyMap()), sid: String = "") {
        val ws = socket ?: error("$name 尚未连接")
        val envelope = Envelope(
            t = type,
            sid = sid,
            cseq = cseq.incrementAndGet(),
            room = room,
            seat = seat,
            p = payload,
        )
        ws.sendText(Wire.encode(envelope), true)
    }

    /** 主动断开（模拟掉线），不发送 close 帧。 */
    fun abort() {
        socket?.abort()
        closed = true
    }

    /** 正常关闭连接，避免测试结束时服务端出现断连噪音。 */
    fun close() {
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "sim-done") }
    }

    suspend fun next(timeoutMs: Long = 30): Envelope? =
        withContext(Dispatchers.IO) { inbox.poll(timeoutMs, TimeUnit.MILLISECONDS) }

    /** 在超时内等待满足条件的消息，其余消息会被丢弃（仅用于握手阶段）。 */
    fun await(timeoutMs: Long = 5_000, predicate: (Envelope) -> Boolean): Envelope? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val envelope = inbox.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (predicate(envelope)) return envelope
        }
    }

    fun awaitType(type: String, timeoutMs: Long = 5_000): Envelope? = await(timeoutMs) { it.t == type }

    /** 等待所有消息都处理完，用于测试结束前的排空。 */
    fun drain(): List<Envelope> = buildList {
        while (true) add(inbox.poll() ?: break)
    }

    private fun observe(envelope: Envelope) {
        if (envelope.t == "seat_granted") {
            seat = envelope.seat
            runCatching {
                (envelope.p["resumeToken"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.getOrNull()?.let { resumeToken = it }
        }
        if (envelope.room.isNotEmpty()) room = envelope.room
        if (envelope.sseq > 0 && envelope.sseq >= lastSseq) lastSseq = envelope.sseq
        if (envelope.t == "error") {
            val view = runCatching { Wire.payload<mahjong.server.protocol.ErrorView>(envelope) }.getOrNull()
            errors.add("${view?.code ?: "E_UNKNOWN"}: ${view?.message ?: ""}")
        }
    }
}

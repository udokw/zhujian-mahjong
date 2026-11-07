package mahjong.server.net

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import mahjong.server.ServerConfig
import mahjong.server.protocol.ErrorCode
import mahjong.server.protocol.ErrorView
import mahjong.server.protocol.MessageType
import mahjong.server.protocol.ProtocolException
import mahjong.server.protocol.Wire
import java.util.UUID

private val log = org.slf4j.LoggerFactory.getLogger("mahjong.server.net.WsHandler")

/**
 * `/ws` 的读循环：解析信封 → 交由 [MessageRouter]。
 *
 * 心跳超时由 watchdog 负责；断线后统一调用 `room.onDisconnect`，
 * 房间据此进入托管计时。
 */
suspend fun handleWebSocket(
    socket: DefaultWebSocketServerSession,
    router: MessageRouter,
    config: ServerConfig,
) {
    val session = ClientSession(UUID.randomUUID().toString().take(8), socket)
    log.info("连接建立 session=${session.sessionId}")

    coroutineScope {
        val watchdog = launch {
            while (isActive) {
                delay(1_000)
                if (session.closed) break
                if (System.currentTimeMillis() - session.lastSeen > config.heartbeatTimeoutMs) {
                    log.info("心跳超时 session=${session.sessionId}")
                    session.close("心跳超时")
                    break
                }
            }
        }

        try {
            for (frame in socket.incoming) {
                if (frame !is Frame.Text) continue
                session.touch()

                if (!session.allowMessage()) {
                    sendError(session, ErrorCode.RATE_LIMITED, "消息过于频繁")
                    continue
                }

                val envelope = try {
                    Wire.decode(frame.readText())
                } catch (e: ProtocolException) {
                    sendError(session, e.code, e.message ?: "无法解析消息")
                    continue
                }
                router.handle(session, envelope)
            }
        } catch (e: Exception) {
            if (!session.closed) log.debug("连接异常 session=${session.sessionId}: ${e.message}")
        } finally {
            watchdog.cancel()
            session.room?.onDisconnect(session)
            session.room = null
            log.info("连接关闭 session=${session.sessionId}")
        }
    }
}

private suspend fun sendError(session: ClientSession, code: String, message: String) {
    session.sendEnvelope(
        MessageType.ERROR,
        Wire.json.encodeToJsonElement(ErrorView(code, message)).jsonObject,
    )
}

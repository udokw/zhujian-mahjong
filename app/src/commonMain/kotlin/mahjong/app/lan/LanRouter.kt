package mahjong.app.lan

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import mahjong.app.util.Clock
import mahjong.core.rules.RuleConfig
import mahjong.core.rules.RulePreset

/** 房主侧的一条客户端连接。 */
interface LanSession : RoomSession {
    var room: Room?
    val sessionId: String
    fun acceptCseq(cseq: Long): Boolean
}

/**
 * 消息路由：把客户端意图翻译成房间操作。
 *
 * 这里没有任何牌局规则，规则全部由 Room + core 引擎负责。
 */
class MessageRouter(
    private val rooms: RoomManager,
) {
    suspend fun handle(session: LanSession, envelope: Envelope) {
        if (envelope.v != PROTOCOL_VERSION) {
            fail(session, envelope, ErrorCode.BAD_VERSION, "不支持的协议版本 ${envelope.v}")
            return
        }
        if (!session.acceptCseq(envelope.cseq)) {
            // 重复消息静默丢弃，避免客户端重发导致重复操作。
            return
        }

        try {
            when (envelope.t) {
                MessageType.PING -> handlePing(session, envelope)
                MessageType.HELLO -> handleHello(session, envelope)
                MessageType.ROOM_CREATE -> handleRoomCreate(session, envelope)
                MessageType.ROOM_JOIN -> handleRoomJoin(session, envelope)
                MessageType.ROOM_LEAVE -> handleRoomLeave(session)
                MessageType.ROOM_READY -> handleReady(session, envelope)
                MessageType.SEAT_ADD_AI -> handleSeatAddAi(session, envelope)
                MessageType.SEAT_REMOVE_AI -> handleSeatRemoveAi(session, envelope)
                MessageType.SEAT_KICK -> handleSeatKick(session, envelope)
                MessageType.GAME_START -> handleGameStart(session)
                MessageType.ACTION -> handleAction(session, envelope)
                MessageType.TING_LOCK -> handleTingLock(session, envelope)
                MessageType.RECONNECT -> handleReconnect(session, envelope)
                MessageType.SYNC_REQUEST -> handleSyncRequest(session, envelope)
                else -> fail(session, envelope, ErrorCode.BAD_JSON, "未知消息类型 ${envelope.t}")
            }
        } catch (e: RoomError) {
            fail(session, envelope, e.code, e.message)
        } catch (e: ProtocolException) {
            fail(session, envelope, e.code, e.message ?: "协议错误")
        } catch (e: Exception) {
            log.warn("处理消息失败 type=${envelope.t} session=${session.sessionId}", e)
            fail(session, envelope, ErrorCode.INTERNAL, e.message ?: "服务端内部错误")
        }
    }

    // ------------------------------------------------------------ 连接

    private suspend fun handlePing(session: LanSession, envelope: Envelope) {
        val body = Wire.payload<PingBody>(envelope)
        reply(session, MessageType.PONG, json(PongView(body.time, Clock.now())), envelope.sid)
    }

    private suspend fun handleHello(session: LanSession, envelope: Envelope) {
        if (session.playerId != null) {
            throw ProtocolException(ErrorCode.ALREADY_AUTHED, "已经完成握手", envelope.sid)
        }
        val body = Wire.payload<HelloBody>(envelope)
        if (body.deviceId.isBlank()) {
            throw ProtocolException(ErrorCode.BAD_JSON, "deviceId 不能为空", envelope.sid)
        }
        session.playerId = guestPlayerId(body.deviceId)
        session.nickname = body.nickname.ifBlank { "玩家" }.take(16)
        reply(
            session,
            MessageType.HELLO_ACK,
            json(HelloAckView(session.playerId!!, session.nickname, Clock.now())),
            envelope.sid,
        )
    }

    // ------------------------------------------------------------ 房间

    private suspend fun handleRoomCreate(session: LanSession, envelope: Envelope) {
        requireAuth(session, envelope)
        val body = Wire.payload<RoomCreateBody>(envelope)
        val rule = ruleOf(body.ruleSet)
        leaveCurrentRoom(session)
        val room = rooms.create(rule.config, rule.name, body.password, body.aiThinkMs)
        session.room = room
        room.join(session, body.password)
        log.info("房间创建 code=${room.code} rule=${rule.name} by=${session.nickname}")
    }

    private suspend fun handleRoomJoin(session: LanSession, envelope: Envelope) {
        requireAuth(session, envelope)
        val body = Wire.payload<RoomJoinBody>(envelope)
        val code = body.room.trim().uppercase()
        val room = rooms.find(code)
            ?: throw RoomError(ErrorCode.ROOM_NOT_FOUND, "房间不存在")
        leaveCurrentRoom(session)
        session.room = room
        room.join(session, body.password)
    }

    private suspend fun handleRoomLeave(session: LanSession) {
        val room = session.room ?: return
        room.leave(session)
        session.room = null
    }

    private suspend fun handleReady(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<RoomReadyBody>(envelope)
        room.setReady(session, body.ready)
    }

    private suspend fun handleSeatAddAi(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<AiSeatBody>(envelope)
        room.addAi(session, body.seat, body.level)
        reply(session, MessageType.ACTION_ACK, json(ActionAckView(envelope.sid, true)), envelope.sid)
    }

    private suspend fun handleSeatRemoveAi(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<SeatRefBody>(envelope)
        room.removeAi(session, body.seat)
    }

    private suspend fun handleSeatKick(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<KickBody>(envelope)
        room.kick(session, body.seat, body.reason)
        reply(
            session,
            MessageType.ACTION_ACK,
            json(ActionAckView(envelope.sid, true)),
            envelope.sid,
        )
        log.info("房主移出玩家 room=${room.code} seat=${body.seat}")
    }

    private suspend fun handleGameStart(session: LanSession) {
        val room = session.room ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
        room.start(session)
    }

    // ------------------------------------------------------------ 对局

    private suspend fun handleAction(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<ActionBody>(envelope)
        room.handleAction(session, body, envelope.sid)
    }

    private suspend fun handleTingLock(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<TingLockBody>(envelope)
        room.setTingLock(session, body.enabled)
        reply(session, MessageType.ACTION_ACK, json(ActionAckView(envelope.sid, true)), envelope.sid)
    }

    private suspend fun handleReconnect(session: LanSession, envelope: Envelope) {
        requireAuth(session, envelope)
        val body = Wire.payload<ReconnectBody>(envelope)
        val room = rooms.find(envelope.room.trim().uppercase())
            ?: throw RoomError(ErrorCode.ROOM_NOT_FOUND, "房间不存在或已关闭")
        leaveCurrentRoom(session)
        session.room = room
        room.reconnect(session, body.resumeToken, body.lastSseq, body.seat)
        log.info("重连成功 room=${room.code} seatToken=${body.resumeToken.take(6)}…")
    }

    private suspend fun handleSyncRequest(session: LanSession, envelope: Envelope) {
        val room = requireRoom(session, envelope)
        val body = Wire.payload<SyncRequestBody>(envelope)
        room.sync(session, body.lastSseq)
    }

    // ------------------------------------------------------------ 工具

    private suspend fun leaveCurrentRoom(session: LanSession) {
        val current = session.room ?: return
        current.leave(session)
        session.room = null
    }

    private fun requireAuth(session: LanSession, envelope: Envelope) {
        if (session.playerId == null) {
            throw ProtocolException(ErrorCode.UNAUTHENTICATED, "请先发送 hello", envelope.sid)
        }
    }

    private fun requireRoom(session: LanSession, envelope: Envelope): Room {
        requireAuth(session, envelope)
        return session.room ?: throw RoomError(ErrorCode.NOT_IN_ROOM, "不在房间内")
    }

    data class ResolvedRule(val name: String, val config: RuleConfig)

    fun ruleOf(raw: String): ResolvedRule = when (val id = raw.trim().uppercase()) {
        "MCR", "MCR_1998", "国标" -> ResolvedRule("MCR", RuleConfig.of(RulePreset.MCR_1998))
        "GD_TUIDAOHU", "GUANGDONG", "GD", "推倒胡" ->
            ResolvedRule("GD_TUIDAOHU", RuleConfig.of(RulePreset.GD_TUIDAOHU))
        "GD_JIPINGHU", "JIPINGHU", "鸡平胡" ->
            ResolvedRule("GD_JIPINGHU", RuleConfig.of(RulePreset.GD_JIPINGHU))
        else -> throw ProtocolException(ErrorCode.BAD_JSON, "未知规则集: $id")
    }

    private suspend fun fail(session: LanSession, envelope: Envelope, code: String, message: String) {
        reply(session, MessageType.ERROR, json(ErrorView(code, message)), envelope.sid)
    }

    private suspend fun reply(session: LanSession, type: String, payload: JsonObject, sid: String) {
        session.sendEnvelope(type, payload, sid, session.room?.code ?: "", -1, 0)
    }

    private inline fun <reified T> json(value: T): JsonObject =
        Wire.json.encodeToJsonElement(value).jsonObject

    private companion object {
        val log = LanLog
    }
}

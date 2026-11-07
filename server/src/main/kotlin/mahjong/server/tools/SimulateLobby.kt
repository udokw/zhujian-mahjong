package mahjong.server.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mahjong.server.MahjongServer
import mahjong.server.ServerConfig
import mahjong.server.discovery.DiscoveryResponse
import mahjong.server.persist.InMemoryReplayStore
import mahjong.server.protocol.Envelope
import mahjong.server.protocol.MessageType
import mahjong.server.room.RoomSession
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 端到端验证 3：局域网自动发现 + 房主踢人（等待阶段/对局中）与边界。
 *
 * 运行：`cd server && gradlew.bat simulateLobby`
 */
fun main() = runBlocking {
    val discoveryPort = System.getProperty("sim.discoveryPort")?.toIntOrNull() ?: 45299
    val discoveryJson = Json { ignoreUnknownKeys = true }
    val violations = mutableListOf<String>()
    val agents = mutableListOf<SimAgent>()
    val store = InMemoryReplayStore()
    val config = ServerConfig(
        host = "127.0.0.1",
        port = 0,
        dbUrl = "jdbc:sqlite::memory:",
        claimWindowMs = 400,
        heartbeatTimeoutMs = 60_000,
        autoplayDelayMs = 250,
        resumeGraceMs = 30_000,
        autoplayAiLevel = 2,
        discoveryPort = discoveryPort,
    )
    val server = MahjongServer(config, store)
    val port = server.startAndGetPort()
    println("[lobby] 服务端 127.0.0.1:$port，局域网发现 UDP $discoveryPort")

    fun check(ok: Boolean, message: String) {
        if (ok) println("[lobby]  ✓ $message") else {
            violations.add(message)
            println("[lobby]  ✗ $message")
        }
    }

    fun newAgent(name: String): SimAgent {
        val agent = SimAgent(name, URI("ws://127.0.0.1:$port/ws"))
        agent.connect()
        agent.send("hello", obj("deviceId" to "lobby-$name-${System.nanoTime()}", "nickname" to name))
        checkNotNull(agent.awaitType("hello_ack", 5_000)) { "$name 握手失败" }
        agents += agent
        return agent
    }

    fun discoverVia(address: String): DiscoveryResponse? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1_200
            val bytes = "MJ?1".toByteArray(Charsets.UTF_8)
            val target = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
            socket.send(DatagramPacket(bytes, bytes.size, target, discoveryPort))
            val buffer = ByteArray(8_192)
            val packet = DatagramPacket(buffer, buffer.size)
            return try {
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("MJ_ROOMS/1\n")) {
                    violations.add("发现响应前缀异常: ${text.take(32)}")
                    null
                } else {
                    discoveryJson.decodeFromString<DiscoveryResponse>(text.substringAfter('\n'))
                }
            } catch (e: SocketTimeoutException) {
                null
            } catch (e: Exception) {
                violations.add("发现响应解析失败: ${e.message}")
                null
            }
        }
    }

    fun discover(): DiscoveryResponse? = discoverVia("127.0.0.1")

    suspend fun discoverUntil(timeoutMs: Long, predicate: (DiscoveryResponse) -> Boolean): DiscoveryResponse? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val response = discover()
            if (response != null && predicate(response)) return response
            delay(200)
        }
        return null
    }

    val jobs = mutableListOf<Job>()
    try {
        // ---------------------------------------------------- 1. 创建即被发现
        val host = newAgent("房主")
        host.send("room_create", obj("ruleSet" to "GD_JIPINGHU"))
        val grant = checkNotNull(host.awaitType("seat_granted", 5_000)) { "建房失败" }
        val room = grant.room
        val hostSeat = host.seat
        check(room.isNotEmpty(), "房间创建成功 code=$room")

        val firstDiscovery = discoverUntil(5_000) { res -> res.rooms.any { it.code == room } }
        check(firstDiscovery != null, "创建后立即被 UDP 自动发现（无需手输地址）")
        if (firstDiscovery != null) {
            check(firstDiscovery.wsPort == port, "发现响应携带正确 WebSocket 端口 ${firstDiscovery.wsPort}")
            val summary = firstDiscovery.rooms.first { it.code == room }
            check(summary.players == 1 && summary.humans == 1, "发现摘要人数正确 players=${summary.players}")
            check(summary.host == "房主", "发现摘要携带房主昵称 ${summary.host}")
            check(!summary.hasPassword, "无密码房正确标记 hasPassword=false")
        }

        val broadcastProbe = discoverVia("255.255.255.255")
        check(
            broadcastProbe?.rooms?.any { it.code == room } == true,
            "经真实网卡广播 255.255.255.255 同样能发现房间（非回环路径）",
        )

        // ---------------------------------------------------- 2. 密码房 + 从发现结果直接加入
        val pwHost = newAgent("密码房主")
        pwHost.send("room_create", obj("ruleSet" to "MCR", "password" to "8888"))
        val pwGrant = checkNotNull(pwHost.awaitType("seat_granted", 5_000)) { "密码房创建失败" }
        val pwRoom = pwGrant.room
        val pwDiscovery = discoverUntil(5_000) { res -> res.rooms.any { it.code == pwRoom } }
        check(pwDiscovery?.rooms?.firstOrNull { it.code == pwRoom }?.hasPassword == true, "密码房在发现结果中标记 hasPassword=true")
        val joiner = newAgent("密码玩家")
        joiner.send("room_join", obj("room" to pwRoom, "password" to "8888"))
        val joinerGrant = joiner.awaitType("seat_granted", 5_000)
        check(joinerGrant != null, "用发现到的房号 + 密码直接加入成功（座位 ${joinerGrant?.seat}）")
        joiner.send("room_leave")
        pwHost.send("room_leave")
        delay(300)
        check(discover()?.rooms?.none { it.code == pwRoom } == true, "房间关闭后从发现列表移除")

        // ---------------------------------------------------- 3. 等待阶段踢人
        val a = newAgent("玩家A")
        a.send("room_join", obj("room" to room))
        checkNotNull(a.awaitType("seat_granted", 5_000))
        val b = newAgent("玩家B")
        b.send("room_join", obj("room" to room))
        checkNotNull(b.awaitType("seat_granted", 5_000))
        checkNotNull(host.await(5_000) { it.t == "room_state" && humans(it) == 3 }) { "房间未坐满 3 人" }

        a.send("seat_kick", obj("seat" to b.seat), sid = "kick-non-host")
        val notHost = a.awaitType("error", 3_000)
        check(notHost?.p?.str("code") == "E_NOT_HOST", "非房主踢人返回 E_NOT_HOST")

        host.send("seat_kick", obj("seat" to hostSeat), sid = "kick-self")
        val kickSelf = host.awaitType("error", 3_000)
        check(kickSelf?.p?.str("code") == "E_CANNOT_KICK_SELF", "房主踢自己返回 E_CANNOT_KICK_SELF")

        host.send("seat_add_ai", obj("seat" to 3))
        checkNotNull(host.await(3_000) { it.t == "room_state" && seatKind(it, 3) == "ai" }) { "添加 AI 失败" }
        host.send("seat_kick", obj("seat" to 3), sid = "kick-ai")
        val kickAi = host.awaitType("error", 3_000)
        check(kickAi?.p?.str("code") == "E_NOT_A_PLAYER", "踢 AI 座位返回 E_NOT_A_PLAYER（应走移除 AI）")
        host.send("seat_remove_ai", obj("seat" to 3))
        checkNotNull(host.await(3_000) { it.t == "room_state" && seatKind(it, 3) == "empty" }) { "移除 AI 失败" }

        val bSeat = b.seat
        host.send("seat_kick", obj("seat" to bSeat, "reason" to "测试移出"), sid = "kick-b")
        checkNotNull(host.await(3_000) { it.t == "room_state" && seatKind(it, bSeat) == "empty" }) {
            "被踢座位 $bSeat 已释放为空座"
        }
        val ack = host.awaitType("action_ack", 3_000)
        check(ack?.p?.bool("ok") == true, "房主踢人成功，收到 action_ack(ok)")
        val kicked = b.awaitType("room_kicked", 3_000)
        check(kicked != null, "被踢方收到 room_kicked 明确提示（非静默断线）")
        check(kicked?.p?.str("reason") == "测试移出", "被踢提示携带房主给的原因")
        check(waitUntil(3_000) { b.closed }, "被踢连接随后被服务端关闭")

        // 重复踢：座位已空，幂等成功
        host.send("seat_kick", obj("seat" to bSeat), sid = "kick-b-again")
        val ackAgain = host.awaitType("action_ack", 3_000)
        check(ackAgain?.p?.bool("ok") == true, "重复踢同一空座位幂等成功，不报错")

        val c = newAgent("玩家C")
        c.send("room_join", obj("room" to room))
        val cGrant = c.awaitType("seat_granted", 3_000)
        check(cGrant?.seat == bSeat, "新玩家可无缝加入被释放的座位 $bSeat")

        // 踢掉已断线玩家
        val aSeat = a.seat
        a.abort()
        check(waitUntil(3_000) { a.closed }, "玩家A 已断线")
        host.send("seat_kick", obj("seat" to aSeat), sid = "kick-offline")
        checkNotNull(host.await(3_000) { it.t == "room_state" && seatKind(it, aSeat) == "empty" }) {
            "断线玩家被踢后座位 $aSeat 已释放"
        }
        val ackOffline = host.awaitType("action_ack", 3_000)
        check(ackOffline?.p?.bool("ok") == true, "踢掉已断线玩家成功（无需对方在线）")

        // 房主自己退出：房主权限转移（等待阶段离开会关闭房主连接，改由剩余玩家观察）
        host.send("room_leave")
        val transferred = c.await(3_000) {
            it.t == "room_state" && it.p.int("hostSeat") != hostSeat && seatKind(it, hostSeat) == "empty"
        }
        check(transferred != null, "房主退出后房主权限转移给剩余玩家（新 hostSeat=${transferred?.p?.int("hostSeat")}）")
        val last = c
        last.send("room_leave")
        delay(400)
        check(server.rooms.find(room) == null, "最后一人退出后房间自动关闭并从发现列表消失")
        check(discover()?.rooms?.none { it.code == room } == true, "已关闭房间不再出现在发现响应中")

        // ---------------------------------------------------- 4. 对局中踢人（直接驱动 Room，避免真局时长影响断言）
        val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val closedRoom = CompletableDeferred<mahjong.server.room.Room>()
        val gameRoom = mahjong.server.room.Room(
            code = "GAME01",
            config = mahjong.core.rules.RuleConfig.of(mahjong.core.rules.RulePreset.GD_JIPINGHU),
            ruleSetName = "GD_JIPINGHU",
            passwordHash = null,
            settings = config.copy(claimWindowMs = 30, autoplayDelayMs = 20),
            store = store,
            scope = roomScope,
            onClose = { closedRoom.complete(it) },
        )
        val gh = FakeSession("对局房主")
        val gp1 = FakeSession("对局P1")
        val gp2 = FakeSession("对局P2")
        val ghSeat = gameRoom.join(gh, null)
        val gp1Seat = gameRoom.join(gp1, null)
        val gp2Seat = gameRoom.join(gp2, null)
        gameRoom.addAi(gh, 3)
        gameRoom.setReady(gh, true)
        gameRoom.setReady(gp1, true)
        gameRoom.setReady(gp2, true)
        gameRoom.start(gh)
        check(ghSeat == 0 && gp1Seat == 1 && gp2Seat == 2, "对局房座位分配正确 0/1/2 + AI")
        val p2Token = gp2.last(MessageType.SEAT_GRANTED)?.p?.str("resumeToken")
        check(p2Token != null, "对局前已取得 P2 的 resumeToken")

        gameRoom.kick(gh, gp2Seat, "对局中移出")
        check(gp2.closed, "对局中踢人：被踢方连接已关闭")
        check(
            gp2.last(MessageType.ROOM_KICKED)?.p?.str("reason") == "对局中移出",
            "对局中踢人：被踢方收到 room_kicked 与原因",
        )
        val stateAfterKick = gh.last(MessageType.ROOM_STATE)
        // 注意：协议使用 encodeDefaults=false，online=false 会省略，客户端按缺省 false 处理。
        check(
            stateAfterKick?.p?.str("phase") == "playing" &&
                seatKind(stateAfterKick, gp2Seat) == "human" &&
                seatOnline(stateAfterKick, gp2Seat) != true,
            "对局中踢人：座位保留但标记离线，牌局不中断",
        )
        var repeatOk = true
        try {
            gameRoom.kick(gh, gp2Seat, null)
        } catch (e: Exception) {
            repeatOk = false
        }
        check(repeatOk, "对局中重复踢同一座位幂等成功")
        val staleResume = runCatching { gameRoom.reconnect(FakeSession("冒名"), p2Token!!, 0) }
        check(staleResume.exceptionOrNull() is mahjong.server.room.RoomError, "被踢者的旧 resumeToken 立即失效")

        // 对局中房主退出：权限转移，连接不静默断开（座位交给 AI 打到本局结束）
        gameRoom.leave(gh)
        check(!gh.closed, "对局中房主退出不会静默断线")
        check(
            gp1.last(MessageType.ROOM_STATE)?.p?.int("hostSeat") == gp1Seat,
            "对局中房主退出，房主权限转移给在线玩家座位 $gp1Seat",
        )
        gameRoom.leave(gp1)

        val ended = withTimeoutOrNull(90_000) { closedRoom.await() }
        check(ended != null, "全房无真人后由 AI 托管打到局末并收尾")
        check(
            ended?.summary()?.humans == 0 && ended?.summary()?.players == 1,
            "局末释放被踢/退出座位，仅剩 1 个 AI 座位",
        )
        roomScope.cancel()
        agents.forEach { it.close() }
        delay(200)
    } finally {
        jobs.forEach { it.cancel() }
        agents.forEach { it.close() }
        delay(150)
        server.stop()
    }

    if (violations.isEmpty()) {
        println("[PASS] 局域网自动发现 + 房主踢人（等待/对局中/重复踢/离线踢/房主转移）全部验证通过")
    } else {
        violations.distinct().take(30).forEach { println("[FAIL] $it") }
        kotlin.system.exitProcess(1)
    }
}

private fun humans(envelope: Envelope): Int =
    envelope.p.arr("seats")?.count { it.jsonObject.str("kind") == "human" } ?: 0

private fun seatKind(envelope: Envelope, seat: Int): String? =
    envelope.p.arr("seats")?.getOrNull(seat)?.jsonObject?.str("kind")

private fun seatOnline(envelope: Envelope, seat: Int): Boolean? =
    envelope.p.arr("seats")?.getOrNull(seat)?.jsonObject?.bool("online")

private suspend fun waitUntil(timeoutMs: Long, intervalMs: Long = 50, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(intervalMs)
    }
    return condition()
}

/** 直接驱动 Room 时使用的内存会话，记录服务端下发的所有消息。 */
private class FakeSession(label: String) : RoomSession {
    override val playerId: String? = "p-$label"
    override val nickname: String = label
    private val messages = java.util.Collections.synchronizedList(mutableListOf<Envelope>())

    @Volatile
    var closed: Boolean = false
        private set

    override suspend fun sendEnvelope(
        type: String,
        payload: JsonObject,
        sid: String,
        roomCode: String,
        seat: Int,
        sseq: Long,
    ) {
        messages += Envelope(t = type, sid = sid, room = roomCode, seat = seat, sseq = sseq, p = payload)
    }

    override suspend fun close(reason: String) {
        closed = true
    }

    fun last(type: String): Envelope? = synchronized(messages) { messages.lastOrNull { it.t == type } }
}

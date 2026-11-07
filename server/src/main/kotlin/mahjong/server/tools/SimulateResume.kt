package mahjong.server.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import mahjong.server.MahjongServer
import mahjong.server.ServerConfig
import mahjong.server.persist.InMemoryReplayStore
import mahjong.server.protocol.Envelope
import mahjong.server.protocol.GameOverView
import mahjong.server.protocol.SyncView
import mahjong.server.protocol.Wire
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 端到端验证 2：断线 → AI 托管 → 宽限期内 resumeToken 重连 → 继续打完。
 *
 * 运行：`cd server && gradlew.bat simulateResume`
 */
fun main() = runBlocking {
    val rule = System.getProperty("sim.rule") ?: "MCR"
    val store = InMemoryReplayStore()
    val config = ServerConfig(
        host = "127.0.0.1",
        port = 0,
        dbUrl = "jdbc:sqlite::memory:",
        claimWindowMs = 400,
        heartbeatTimeoutMs = 60_000,
        autoplayDelayMs = 300,
        // 宽限期故意给足，确保能在过期前重连。
        resumeGraceMs = 30_000,
        autoplayAiLevel = 2,
    )
    val server = MahjongServer(config, store)
    val port = server.startAndGetPort()
    println("[resume] 服务端已启动 127.0.0.1:$port")

    val violations = mutableListOf<String>()
    try {
        val agents = (0..3).map { SimAgent("P$it", URI("ws://127.0.0.1:$port/ws")) }
        agents.forEach { agent ->
            agent.connect()
            agent.send("hello", obj("deviceId" to "resume-${agent.name}", "nickname" to agent.name))
            checkNotNull(agent.awaitType("hello_ack"))
        }

        val host = agents[0]
        host.send("room_create", obj("ruleSet" to rule))
        val granted = checkNotNull(host.awaitType("seat_granted"))
        val roomCode = granted.room
        check(roomCode.isNotEmpty())
        for (i in 1..3) {
            agents[i].send("room_join", obj("room" to roomCode))
            checkNotNull(agents[i].awaitType("seat_granted"))
        }
        checkNotNull(
            host.await(5_000) { it.t == "room_state" && it.p.arr("seats")?.count { s ->
                s.jsonObject.str("kind") == "human"
            } == 4 },
        ) { "房间未坐满" }

        agents.forEach { it.send("room_ready", obj("ready" to true)) }
        checkNotNull(
            host.await(5_000) { it.t == "room_state" && it.p.arr("seats")?.all {
                it.jsonObject.bool("ready") == true
            } == true },
        ) { "未全部准备" }
        host.send("game_start")
        checkNotNull(host.awaitType("game_start", 5_000)) { "开局失败" }
        println("[resume] 开局成功，房号 $roomCode")

        val done = CompletableDeferred<Envelope>()
        val progress = AtomicInteger(0)
        val target = agents[1]
        val targetSeat = target.seat
        val targetToken = checkNotNull(target.resumeToken) { "未拿到 resumeToken" }

        val offlineSeen = AtomicBoolean(false)
        val disconnectedAt = AtomicLong(0)
        val aiActedSeat = AtomicBoolean(false)
        val aiDiscardSeen = AtomicLong(0)

        val jobs = arrayOfNulls<kotlinx.coroutines.Job>(4)
        jobs[0] = launch(Dispatchers.IO) {
            agents[0].playLoop(done, violations, onTurn = { progress.set(it) }, onEnvelope = { envelope ->
                if (envelope.t == "seat_online" &&
                    envelope.p.int("seat") == targetSeat &&
                    envelope.p.bool("online") == false
                ) {
                    offlineSeen.set(true)
                }
                if (envelope.t == "event" &&
                    envelope.p.str("type") == "discard" &&
                    envelope.p.int("seat") == targetSeat &&
                    disconnectedAt.get() > 0
                ) {
                    aiActedSeat.set(true)
                    if (aiDiscardSeen.get() == 0L) aiDiscardSeen.set(System.currentTimeMillis())
                }
            })
        }
        for (i in 1..3) {
            val agent = agents[i]
            jobs[i] = launch(Dispatchers.IO) { agent.playLoop(done, violations, onTurn = { progress.set(it) }) }
        }

        check(waitUntil(20_000) { progress.get() >= 8 || done.isCompleted }) { "对局未推进" }
        check(!done.isCompleted) { "对局在断线测试前就结束了，请重跑" }
        println("[resume] 对局进行到 turn=${progress.get()}，现在断开 P1（座位 $targetSeat）")

        target.abort()
        jobs[1]?.cancel()
        disconnectedAt.set(System.currentTimeMillis())

        check(waitUntil(5_000) { offlineSeen.get() }) { "其他客户端没有收到 seat_online(offline)" }
        println("[resume] 其他客户端已收到 P1 离线通知")

        check(waitUntil(15_000) { aiActedSeat.get() || done.isCompleted }) {
            "断线后 AI 未接管出牌"
        }
        check(!done.isCompleted || aiActedSeat.get()) { "AI 托管未产生动作" }
        val aiDelay = aiDiscardSeen.get() - disconnectedAt.get()
        println("[resume] AI 托管已接管：座位 $targetSeat 在断线 ${aiDelay}ms 后由 AI 出牌")

        val turnsBeforeResume = progress.get()
        val reconnected = SimAgent("P1r", URI("ws://127.0.0.1:$port/ws"))
        reconnected.connect()
        reconnected.room = roomCode
        reconnected.send("hello", obj("deviceId" to "resume-P1", "nickname" to "P1"))
        checkNotNull(reconnected.awaitType("hello_ack"))
        reconnected.send(
            "reconnect",
            obj("resumeToken" to targetToken, "lastSseq" to target.lastSseq),
            sid = "reconnect-1",
        )
        val regrant = checkNotNull(reconnected.awaitType("seat_granted", 5_000)) { "重连被拒绝" }
        check(regrant.seat == targetSeat) { "重连回到错误座位 ${regrant.seat} != $targetSeat" }
        val syncEnvelope = checkNotNull(reconnected.awaitType("sync", 5_000)) { "未收到 sync 快照" }
        val sync = Wire.payload<SyncView>(syncEnvelope)
        check(!sync.autoPlaying) { "重连后座位仍处于 AI 托管" }
        check(sync.state != null) { "sync 未携带牌局状态" }
        check(sync.state!!.hand.isNotEmpty()) { "sync 未下发自己的手牌" }
        println(
            "[resume] 重连成功：座位 ${regrant.seat}，sync.lastSseq=${sync.lastSseq}，" +
                "autoPlaying=${sync.autoPlaying}，手牌 ${sync.state!!.hand.size} 张",
        )

        jobs[1] = launch(Dispatchers.IO) {
            reconnected.playLoop(done, violations, onTurn = { progress.set(it) })
        }

        val over = withTimeoutOrNull(180_000) { done.await() }
        jobs.forEach { it?.cancel() }
        checkNotNull(over) { "重连后对局未打完" }

        val result = Wire.payload<GameOverView>(over)
        println("[resume] 重连后继续打完：${if (result.draw) "流局" else "有人和牌"}")
        println("[resume] 分数变化 ${result.deltaScores}  总分 ${result.scores}")
        println("[resume] 重连时 turn=${turnsBeforeResume}，最终 turn=${progress.get()}")

        check(store.count() > 0 || withTimeoutOrNull(5_000) {
            while (store.count() == 0) delay(50)
            true
        } == true) { "牌谱未落库" }
        val record = store.load(result.gameId)
        if (record == null) {
            violations.add("牌谱读取失败")
        } else {
            println("[resume] 牌谱已落库：seed=${record.seed}，动作 ${record.actions.size} 条")
            val mismatch = replayMismatch(record)
            if (mismatch != null) violations.add("牌谱复现失败：$mismatch")
            else println("[resume] 含 AI 托管动作的牌谱复现一致")
        }

        val unexpected = agents.filterIndexed { index, _ -> index != 1 }.flatMap { it.errors } +
            reconnected.errors
        if (unexpected.isNotEmpty()) {
            violations.addAll(unexpected.map { "客户端错误: $it" })
        }

        agents.forEach { it.close() }
        reconnected.close()
        delay(150)
    } finally {
        server.stop()
    }

    if (violations.isEmpty()) {
        println("[PASS] 断线托管与重连验证通过：离线广播、AI 接管、宽限期重连、sync 快照、打完整局")
    } else {
        violations.distinct().take(20).forEach { println("[FAIL] $it") }
        kotlin.system.exitProcess(1)
    }
}

private suspend fun waitUntil(timeoutMs: Long, intervalMs: Long = 50, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(intervalMs)
    }
    return condition()
}

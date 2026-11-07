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
import mahjong.server.protocol.GameOverView
import mahjong.server.protocol.GameStartView
import mahjong.server.protocol.Wire
import java.net.URI

/**
 * 端到端验证 1：4 个真实 WebSocket 客户端建好友房、打完整一局、校验隐私与牌谱复现。
 *
 * 运行：`cd server && gradlew.bat simulateFour`
 */
fun main() = runBlocking {
    val rule = System.getProperty("sim.rule") ?: "MCR"
    val store = InMemoryReplayStore()
    val config = ServerConfig(
        host = "127.0.0.1",
        port = 0,
        dbUrl = "jdbc:sqlite::memory:",
        claimWindowMs = 300,
        heartbeatTimeoutMs = 60_000,
        autoplayDelayMs = 300,
        resumeGraceMs = 30_000,
    )
    val server = MahjongServer(config, store)
    val port = server.startAndGetPort()
    println("[sim] 服务端已启动 127.0.0.1:$port")

    val violations = mutableListOf<String>()
    try {
        val agents = (0..3).map { SimAgent("P$it", URI("ws://127.0.0.1:$port/ws")) }
        agents.forEach { agent ->
            agent.connect()
            agent.send("hello", obj("deviceId" to "sim-${agent.name}", "nickname" to agent.name))
            checkNotNull(agent.awaitType("hello_ack")) { "${agent.name} 握手失败" }
        }
        println("[sim] 4 个客户端握手完成")

        val host = agents[0]
        host.send("room_create", obj("ruleSet" to rule))
        val granted = checkNotNull(host.awaitType("seat_granted")) { "建房失败" }
        val roomCode = granted.room
        check(roomCode.isNotEmpty()) { "未拿到房号" }
        println("[sim] 好友房已创建：$roomCode（规则 $rule，房主 P0 座位 ${host.seat}）")

        for (i in 1..3) {
            agents[i].send("room_join", obj("room" to roomCode))
            checkNotNull(agents[i].awaitType("seat_granted")) { "P$i 加入失败" }
        }
        val full = host.await(5_000) { envelope ->
            envelope.t == "room_state" && envelope.p.arr("seats")?.count {
                it.jsonObject.str("kind") == "human"
            } == 4
        }
        checkNotNull(full) { "房间未坐满 4 人" }
        println("[sim] 4 人就座：${agents.joinToString { "${it.name}@${it.seat}" }}")

        agents.forEach { it.send("room_ready", obj("ready" to true)) }
        val allReady = host.await(5_000) { envelope ->
            envelope.t == "room_state" && envelope.p.arr("seats")?.all {
                it.jsonObject.bool("ready") == true
            } == true
        }
        checkNotNull(allReady) { "有玩家未准备" }

        host.send("game_start")
        val start = checkNotNull(host.awaitType("game_start", 5_000)) { "开局失败" }
        val startView = Wire.payload<GameStartView>(start)
        println("[sim] 开局 seed=${startView.seed} 庄家=${startView.dealer} 手牌=${startView.initialHand.size} 张")

        val done = CompletableDeferred<mahjong.server.protocol.Envelope>()
        val progress = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = agents.map { agent ->
            launch(Dispatchers.IO) {
                agent.playLoop(done, violations, onTurn = { progress.set(it) })
            }
        }
        val over = withTimeoutOrNull(180_000) { done.await() }
        jobs.forEach { it.cancel() }
        checkNotNull(over) { "对局 180 秒内未结束（最后 turn=${progress.get()}）" }

        val result = Wire.payload<GameOverView>(over)
        println("[sim] 对局结束：${if (result.draw) "流局(${result.reason})" else "有人和牌"}，共 ${result.gameId}")
        println("[sim] 分数变化 ${result.deltaScores}  总分 ${result.scores}")
        for (winner in result.winners) {
            println(
                "[sim] 赢家 座位${winner.seat} 自摸=${winner.selfDraw} " +
                    "番数=${winner.totalFan} 番种=${winner.fan.joinToString { "${it.name}(${it.fan})" }}",
            )
        }

        val persisted = withTimeoutOrNull(5_000) {
            while (store.count() == 0) delay(50)
            store.count()
        }
        checkNotNull(persisted) { "牌谱未落库" }
        val record = checkNotNull(store.load(result.gameId)) { "牌谱读取失败" }
        println(
            "[sim] 牌谱已落库：seed=${record.seed} dealer=${record.dealer} " +
                "动作 ${record.actions.size} 条",
        )
        val mismatch = replayMismatch(record)
        if (mismatch != null) violations.add("牌谱复现失败：$mismatch")
        else println("[sim] 牌谱复现一致（seed + 动作序列 → 相同结算）")

        println("[sim] 产生的服务端错误/作弊线索：${agents.sumOf { it.errors.size }}")
        agents.flatMap { it.errors }.distinct().take(10).forEach { println("  - $it") }

        agents.forEach { it.close() }
        delay(150)
    } finally {
        server.stop()
    }

    if (violations.isEmpty()) {
        println("[PASS] 4 人完整对局通过：未泄露别家手牌/暗杠，sseq 单调，牌谱可复现")
    } else {
        violations.distinct().take(20).forEach { println("[FAIL] $it") }
        kotlin.system.exitProcess(1)
    }
}

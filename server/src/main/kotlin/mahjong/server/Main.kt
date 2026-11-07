package mahjong.server

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mahjong.server.discovery.DiscoveryServer
import mahjong.server.net.MessageRouter
import mahjong.server.net.handleWebSocket
import mahjong.server.persist.InMemoryReplayStore
import mahjong.server.persist.JdbcReplayStore
import mahjong.server.persist.ReplayStore
import mahjong.server.room.RoomManager
import java.util.concurrent.CountDownLatch

/**
 * 可嵌入的服务器实例，方便 `main` 与端到端脚本共用。
 */
class MahjongServer(
    val config: ServerConfig = ServerConfig.fromEnv(),
    val store: ReplayStore = createStore(config),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val rooms = RoomManager(config, store, scope)
    private val router = MessageRouter(rooms)
    private val stopLatch = CountDownLatch(1)
    private var discovery: DiscoveryServer? = null
    @Volatile
    private var launchedPort: Int = config.port

    private val engine: ApplicationEngine = embeddedServer(
        Netty,
        host = config.host,
        port = config.port,
    ) {
        install(WebSockets) {
            pingPeriodMillis = 30_000
            timeoutMillis = 30_000
            maxFrameSize = 64L * 1024
            masking = false
        }
        routing {
            get("/health") {
                call.respondText(
                    """{"status":"ok","rooms":${rooms.count()},"protocol":1}""",
                    ContentType.Application.Json,
                )
            }
            webSocket("/ws") {
                handleWebSocket(this, router, config)
            }
        }
    }

    fun start(wait: Boolean = true) {
        engine.start(wait = false)
        val port = runBlocking { engine.resolvedConnectors().first().port }
        launchedPort = port
        startDiscovery(port)
        if (wait) stopLatch.await()
    }

    /** 启动后返回实际端口（配置 0 时由系统分配）。 */
    suspend fun startAndGetPort(): Int {
        engine.start(wait = false)
        val port = engine.resolvedConnectors().first().port
        launchedPort = port
        startDiscovery(port)
        return port
    }

    private fun startDiscovery(webSocketPort: Int) {
        if (!config.discoveryEnabled) {
            log.info("局域网发现已关闭（MAHJONG_DISCOVERY=off）")
            return
        }
        discovery = runCatching {
            DiscoveryServer(config, rooms, webSocketPort).also { it.start(scope) }
        }.onFailure {
            log.warn("局域网发现初始化失败：{}", it.message)
        }.getOrNull()
    }

    /** 实际 WebSocket 端口（启动后有效）。 */
    val webSocketPort: Int get() = launchedPort

    fun stop() {
        discovery?.stop()
        discovery = null
        runCatching { engine.stop(1_000, 5_000) }
        scope.cancel()
        runCatching { store.close() }
        stopLatch.countDown()
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(MahjongServer::class.java)
    }
}

/** SQLite 默认；连接失败时退回内存库，保证开发环境一定能起。 */
fun createStore(config: ServerConfig): ReplayStore = try {
    JdbcReplayStore(config)
} catch (e: Exception) {
    org.slf4j.LoggerFactory.getLogger("mahjong.server.Main")
        .warn("数据库连接失败，使用内存牌谱库: ${e.message}")
    InMemoryReplayStore()
}

fun main() {
    val config = ServerConfig.fromEnv()
    val server = MahjongServer(config)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    println("麻将服务端启动：http://${config.host}:${config.port}  (WebSocket /ws, 健康检查 /health)")
    println("牌谱库: ${config.dbUrl}")
    if (config.discoveryEnabled) {
        println("局域网发现: UDP ${config.discoveryPort}（客户端广播 ${mahjong.server.discovery.DiscoveryServer.QUERY} 即可自动发现房间）")
    }
    server.start(wait = true)
}

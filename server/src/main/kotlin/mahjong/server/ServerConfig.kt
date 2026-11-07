package mahjong.server

import java.io.File

data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val dbUrl: String = "jdbc:sqlite:./data/mahjong.db",
    val dbUser: String? = null,
    val dbPassword: String? = null,
    val claimWindowMs: Long = 8_000,
    val heartbeatTimeoutMs: Long = 15_000,
    val autoplayDelayMs: Long = 3_000,
    val resumeGraceMs: Long = 180_000,
    /** 联机托管固定使用第二级 AI。 */
    val autoplayAiLevel: Int = 2,
    /** 局域网自动发现：UDP 广播端口。 */
    val discoveryPort: Int = 45231,
    val discoveryEnabled: Boolean = true,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val dbUrl = env["MAHJONG_DB_URL"]?.takeIf { it.isNotBlank() } ?: run {
                val path = env["MAHJONG_DB"]?.takeIf { it.isNotBlank() } ?: "./data/mahjong.db"
                if (path == ":memory:") {
                    "jdbc:sqlite::memory:"
                } else {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    "jdbc:sqlite:${file.absolutePath}"
                }
            }
            return ServerConfig(
                host = env["MAHJONG_HOST"] ?: "0.0.0.0",
                port = env["MAHJONG_PORT"]?.toIntOrNull() ?: 8080,
                dbUrl = dbUrl,
                dbUser = env["MAHJONG_DB_USER"],
                dbPassword = env["MAHJONG_DB_PASSWORD"],
                claimWindowMs = env["MAHJONG_CLAIM_WINDOW_MS"]?.toLongOrNull() ?: 8_000,
                heartbeatTimeoutMs = env["MAHJONG_HEARTBEAT_TIMEOUT_MS"]?.toLongOrNull() ?: 15_000,
                autoplayDelayMs = env["MAHJONG_AUTOPLAY_DELAY_MS"]?.toLongOrNull() ?: 3_000,
                resumeGraceMs = env["MAHJONG_RESUME_GRACE_MS"]?.toLongOrNull() ?: 180_000,
                discoveryPort = env["MAHJONG_DISCOVERY_PORT"]?.toIntOrNull() ?: 45231,
                discoveryEnabled = env["MAHJONG_DISCOVERY"]?.lowercase() != "off",
            )
        }
    }
}

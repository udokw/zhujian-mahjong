package mahjong.server.persist

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import mahjong.server.ServerConfig
import mahjong.server.game.RecordedAction
import mahjong.server.protocol.Wire
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ReplaySeat(
    val seat: Int,
    val kind: String,
    val playerId: String? = null,
    val nickname: String = "",
    val aiLevel: Int = 0,
)

@Serializable
data class ReplayRecord(
    val gameId: String,
    val roomCode: String,
    val ruleSet: String,
    val seed: String,
    val dealer: Int,
    val roundWind: Int = 0,
    val dealerStreak: Int = 0,
    /** 本局开始前的分数，复现多局房间必需。 */
    val initialScores: List<Int> = emptyList(),
    val seats: List<ReplaySeat>,
    val actions: List<RecordedAction>,
    val resultJson: String,
    val createdAt: Long,
)

interface ReplayStore : AutoCloseable {
    fun save(record: ReplayRecord)
    fun load(gameId: String): ReplayRecord?
    fun count(): Int
}

class InMemoryReplayStore : ReplayStore {
    private val records = ConcurrentHashMap<String, ReplayRecord>()
    override fun save(record: ReplayRecord) {
        records[record.gameId] = record
    }

    override fun load(gameId: String): ReplayRecord? = records[gameId]
    override fun count(): Int = records.size
    override fun close() {}
}

/**
 * JDBC 牌谱库。默认 SQLite；设置 `MAHJONG_DB_URL=jdbc:postgresql://...` 即可切到 Postgres。
 * 牌局写入频率很低，单连接 + 锁足够。
 */
class JdbcReplayStore(private val config: ServerConfig) : ReplayStore {
    private val lock = Any()
    private val connection: Connection = DriverManager.getConnection(
        config.dbUrl,
        config.dbUser,
        config.dbPassword,
    ).also { createTable(it) }

    private fun createTable(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS replays (
                  game_id      VARCHAR(64) PRIMARY KEY,
                  room_code    VARCHAR(16) NOT NULL,
                  rule_set     VARCHAR(16) NOT NULL,
                  seed         VARCHAR(32) NOT NULL,
                  dealer       INT NOT NULL,
                  round_wind   INT NOT NULL DEFAULT 0,
                  dealer_streak INT NOT NULL DEFAULT 0,
                  scores_json  TEXT NOT NULL DEFAULT '[]',
                  seats_json   TEXT NOT NULL,
                  actions_json TEXT NOT NULL,
                  result_json  TEXT NOT NULL,
                  created_at   BIGINT NOT NULL
                )
                """.trimIndent(),
            )
        }
        // 兼容早期开发库：缺列时补齐，已存在则忽略。
        listOf(
            "round_wind INT NOT NULL DEFAULT 0",
            "dealer_streak INT NOT NULL DEFAULT 0",
            "scores_json TEXT NOT NULL DEFAULT '[]'",
        ).forEach { column ->
            runCatching { conn.createStatement().use { it.executeUpdate("ALTER TABLE replays ADD COLUMN $column") } }
        }
    }

    override fun save(record: ReplayRecord) {
        val seatsJson = Wire.json.encodeToString(ListSerializer(ReplaySeat.serializer()), record.seats)
        val actionsJson = Wire.json.encodeToString(ListSerializer(RecordedAction.serializer()), record.actions)
        val scoresJson = Wire.json.encodeToString(ListSerializer(Int.serializer()), record.initialScores)
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM replays WHERE game_id = ?").use { st ->
                st.setString(1, record.gameId)
                st.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO replays
                  (game_id, room_code, rule_set, seed, dealer, round_wind, dealer_streak,
                   scores_json, seats_json, actions_json, result_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, record.gameId)
                st.setString(2, record.roomCode)
                st.setString(3, record.ruleSet)
                st.setString(4, record.seed)
                st.setInt(5, record.dealer)
                st.setInt(6, record.roundWind)
                st.setInt(7, record.dealerStreak)
                st.setString(8, scoresJson)
                st.setString(9, seatsJson)
                st.setString(10, actionsJson)
                st.setString(11, record.resultJson)
                st.setLong(12, record.createdAt)
                st.executeUpdate()
            }
        }
    }

    override fun load(gameId: String): ReplayRecord? = synchronized(lock) {
        connection.prepareStatement("SELECT * FROM replays WHERE game_id = ?").use { st ->
            st.setString(1, gameId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                ReplayRecord(
                    gameId = rs.getString("game_id"),
                    roomCode = rs.getString("room_code"),
                    ruleSet = rs.getString("rule_set"),
                    seed = rs.getString("seed"),
                    dealer = rs.getInt("dealer"),
                    roundWind = rs.getInt("round_wind"),
                    dealerStreak = rs.getInt("dealer_streak"),
                    initialScores = Wire.json.decodeFromString(
                        ListSerializer(Int.serializer()),
                        rs.getString("scores_json") ?: "[]",
                    ),
                    seats = Wire.json.decodeFromString(
                        ListSerializer(ReplaySeat.serializer()),
                        rs.getString("seats_json"),
                    ),
                    actions = Wire.json.decodeFromString(
                        ListSerializer(RecordedAction.serializer()),
                        rs.getString("actions_json"),
                    ),
                    resultJson = rs.getString("result_json"),
                    createdAt = rs.getLong("created_at"),
                )
            }
        }
    }

    override fun count(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM replays").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    override fun close() {
        synchronized(lock) { connection.close() }
    }
}

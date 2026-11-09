package mahjong.app.records

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mahjong.app.keyValueStore

/** 一局结束后的本机战绩。只记结果，不存牌谱。 */
@Serializable
data class GameRecord(
    val at: Long = 0L,
    /** 写入时格式化好的本地时间，避免读取端再做平台时间转换。 */
    val time: String = "",
    val mode: String = "",
    val rule: String = "",
    val won: Boolean = false,
    val draw: Boolean = false,
    val fan: Int = 0,
    val delta: Int = 0,
    val result: String = "",
)

object GameRecordsRepository {
    private const val KEY = "game_records_v1"
    private const val MAX_RECORDS = 300
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<GameRecord> = runCatching {
        val raw = keyValueStore().get(KEY) ?: return emptyList()
        json.decodeFromString(ListSerializer(GameRecord.serializer()), raw)
    }.getOrDefault(emptyList())

    /** 新记录放最前；只保留最近 [MAX_RECORDS] 局。 */
    fun add(record: GameRecord) {
        runCatching {
            val next = (listOf(record) + load()).take(MAX_RECORDS)
            keyValueStore().put(KEY, json.encodeToString(ListSerializer(GameRecord.serializer()), next))
        }
    }
}

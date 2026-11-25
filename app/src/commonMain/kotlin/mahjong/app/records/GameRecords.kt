package mahjong.app.records

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mahjong.app.keyValueStore

/**
 * 一局结束后的本机战绩。
 *
 * [detail] 是结算那一刻的详情快照：之后规则再改，历史记录也不会变。
 * 老记录没有这个字段，读取时为 null，详情页据此给出降级提示。
 */
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
    val detail: RecordDetail? = null,
)

/** 结算瞬间的完整快照，字段只在写入时计算，读取端不做规则重算。 */
@Serializable
data class RecordDetail(
    val draw: Boolean = false,
    /** 自摸 / 点炮 / 抢杠 / 杠上开花 / 荒庄。 */
    val winType: String = "",
    val winnerSeat: Int = -1,
    val winnerName: String = "",
    val fromSeat: Int? = null,
    /** 最终手牌（含胡张），已按界面顺序（万→条→筒→字）排好。 */
    val handTiles: List<Int> = emptyList(),
    /** 胡张；-1 表示荒庄。 */
    val winningTile: Int = -1,
    val melds: List<RecordMeld> = emptyList(),
    val flowers: List<Int> = emptyList(),
    val fans: List<RecordFan> = emptyList(),
    val totalFan: Int = 0,
    val thresholdFan: Int = 0,
    /** 基本分（国标=总番，广东=2^番）。 */
    val points: Int = 0,
    val minFan: Int = 0,
    /** 封顶番数；null 表示不封顶（国标）。 */
    val fanCap: Int? = null,
    val flowerCountsTowardMin: Boolean = false,
    val horseCount: Int = 0,
    val horses: List<String> = emptyList(),
    val seats: List<RecordSeat> = emptyList(),
    val handNumber: Int = 1,
    val roundWind: Int = 0,
    val dealer: Int = 0,
    val baseScore: Int = 0,
    /** 规则预设（与记录列表展示的 rule 一致）。 */
    val rule: String = "",
    val mode: String = "",
    val time: String = "",
)

@Serializable
data class RecordMeld(
    /** core MeldType 名称：CHOW / PUNG / KONG_EXPOSED / KONG_CONCEALED / KONG_ADDED。 */
    val type: String = "PUNG",
    val tiles: List<Int> = emptyList(),
)

@Serializable
data class RecordFan(
    val id: String = "",
    val name: String = "",
    val fan: Int = 0,
    val count: Int = 1,
    /** 是否计入起和门槛；无门槛的玩法统一为 false。 */
    val countsTowardMin: Boolean = false,
)

@Serializable
data class RecordSeat(
    val seat: Int = 0,
    val name: String = "",
    val seatWind: Int = 0,
    val isDealer: Boolean = false,
    val delta: Int = 0,
)

object GameRecordsRepository {
    private const val KEY = "game_records_v1"
    /** 列表最多保留的条数（仅摘要，体积可控）。 */
    private const val MAX_RECORDS = 300
    /** 最近多少条保留完整快照，更老的只留摘要，避免本地 JSON 无限膨胀。 */
    const val DETAIL_RECORDS = 60

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<GameRecord> = runCatching {
        val raw = keyValueStore().get(KEY) ?: return emptyList()
        json.decodeFromString(ListSerializer(GameRecord.serializer()), raw)
    }.getOrDefault(emptyList())

    /** 新记录放最前；只保留最近 [MAX_RECORDS] 局，前 [DETAIL_RECORDS] 局带详情。 */
    fun add(record: GameRecord) {
        runCatching {
            val next = (listOf(record) + load())
                .take(MAX_RECORDS)
                .mapIndexed { index, item ->
                    if (index < DETAIL_RECORDS || item.detail == null) {
                        item
                    } else {
                        item.copy(detail = null)
                    }
                }
            keyValueStore().put(KEY, json.encodeToString(ListSerializer(GameRecord.serializer()), next))
        }
    }
}

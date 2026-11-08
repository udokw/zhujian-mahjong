package mahjong.app.game

import mahjong.core.model.Action
import mahjong.core.model.GameResult
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.score.FanItem
import mahjong.core.score.WinResult

data class SeatUi(
    val seat: Int,
    val name: String,
    val isAI: Boolean,
    val seatWind: Int,
    val isDealer: Boolean,
    val handCount: Int,
    val melds: List<Meld>,
    val discards: List<Int>,
    val flowers: List<Int>,
    val score: Int,
    val online: Boolean = true,
    val thinking: Boolean = false,
    /** 该玩家已进入听牌锁定（听牌后摸打等胡）。 */
    val tingLocked: Boolean = false,
)

data class ClaimUi(
    val options: List<ClaimOption>,
    val deadline: Long,
    val totalMs: Long,
    val tile: Int?,
    val fromSeat: Int?,
)

sealed interface ClaimOption {
    data object Hu : ClaimOption
    data object Peng : ClaimOption
    data object Gang : ClaimOption
    data class Chi(val choices: List<List<Int>>) : ClaimOption
    data object Pass : ClaimOption
}

data class KongOption(val action: Action, val label: String)

data class TurnOptions(
    val canHu: Boolean = false,
    val kongs: List<KongOption> = emptyList(),
)

data class Banner(val text: String, val sub: String = "", val seat: Int? = null, val kind: BannerKind = BannerKind.MELD)

enum class BannerKind { MELD, KONG, WIN, HU }

/** 某张听口牌：剩余枚数与和牌番数（meetsMin=false 表示牌型成立但番数不够门槛）。 */
data class WaitTile(
    val tile: Int,
    val remaining: Int,
    val fan: Int,
    val meetsMin: Boolean,
)

/** 当前 13 张的听牌信息。 */
data class TingInfo(
    val waits: List<WaitTile>,
    val bestFan: Int,
    val meetsMin: Boolean,
    val shanten: Int,
)

/** 出牌建议：打某张后能听哪些牌。 */
data class DiscardPlan(
    val discard: Int,
    val waits: List<WaitTile>,
    val bestFan: Int,
    val meetsMin: Boolean,
    val totalRemaining: Int,
)

/** 胡牌演出阶段：亮牌定格 → 金框描边 → 桌面压暗 → 番型浮层。 */
enum class WinStage { REVEAL, FRAME, DIM, FAN }

/** 胡牌演出：一炮多响时按座位顺序逐个播。 */
data class WinShowUi(
    val winners: List<WinnerUi>,
    val index: Int = 0,
    val stage: WinStage = WinStage.REVEAL,
    val multi: Boolean = false,
) {
    val winner: WinnerUi get() = winners[index.coerceIn(0, winners.lastIndex.coerceAtLeast(0))]
}

data class TableUi(
    val meSeat: Int,
    val hand: List<Int>,
    val drawnTile: Int?,
    val melds: List<Meld>,
    val flowers: List<Int>,
    val seats: List<SeatUi>,
    val wallRemaining: Int,
    val roundWind: Int,
    val dealer: Int,
    val currentPlayer: Int,
    val lastDiscardTile: Int?,
    val lastDiscardSeat: Int?,
    val claim: ClaimUi?,
    val turnOptions: TurnOptions,
    val canDiscard: Boolean,
    val hint: String,
    val modeLabel: String,
    val handNumber: Int,
    val banner: Banner?,
    val autoPlaying: Boolean = false,
    val tingInfo: TingInfo? = null,
    val plans: List<DiscardPlan> = emptyList(),
    val huFan: Int? = null,
    val huMeetsMin: Boolean = true,
    /** 起和门槛（国标 8）；来自规则配置，界面不写死。 */
    val minFan: Int = 0,
    /** 本场封顶；null 为不封顶。 */
    val fanCap: Int? = null,
    /** 本地算力可用时才展示听牌/出牌建议（联机目前由服务端为准，不本地推算）。 */
    val hintsAvailable: Boolean = false,
    /** 胡牌演出；非 null 时桌面进入逐段演出并屏蔽操作。 */
    val winShow: WinShowUi? = null,
    /** 已进入听牌锁定：摸什么打什么，只等胡牌。 */
    val tingLocked: Boolean = false,
)

data class WinnerUi(
    val seat: Int,
    val name: String,
    val selfDraw: Boolean,
    val fromSeat: Int?,
    val winningTile: Int,
    val handTiles: List<Int>,
    val melds: List<Meld>,
    val fans: List<FanItem>,
    val totalFan: Int,
    val thresholdFan: Int = 0,
    val points: Int,
    val robKong: Boolean,
    val horseHits: Int = 0,
)

data class SettlementUi(
    val draw: Boolean,
    val title: String,
    val subtitle: String,
    val reason: String,
    val winners: List<WinnerUi>,
    val seats: List<SeatUi>,
    val deltas: List<Int>,
    val meSeat: Int,
    val totalFan: Int,
    val baseScore: Int,
    val modeLabel: String,
    val minFan: Int = 0,
    /** 本场封顶番数；null 表示不封顶（国标）。取自 RuleConfig.effectiveFanCap。 */
    val fanCap: Int? = null,
    val flowerCountsTowardMin: Boolean = false,
    val horseCount: Int = 0,
    /** 买马结算明细，用于解释实得分为何与基本分不同。 */
    val horses: List<String> = emptyList(),
)

val WindNames = listOf("东", "南", "西", "北")
val WindFullNames = listOf("东风", "南风", "西风", "北风")

fun seatLabel(seat: Int): String = when (seat) {
    0 -> "自己"
    1 -> "下家"
    2 -> "对家"
    else -> "上家"
}

fun meldLabel(type: MeldType): String = when (type) {
    MeldType.CHOW -> "吃"
    MeldType.PUNG -> "碰"
    MeldType.KONG_CONCEALED -> "暗杠"
    MeldType.KONG_EXPOSED -> "明杠"
    MeldType.KONG_ADDED -> "补杠"
}

/**
 * 界面固定排序：万 → 条 → 筒 → 字牌（东南西北中发白）。
 * 引擎内部按 万→筒→条 编号，所以这里单独给一套显示权重。
 */
fun tileDisplayRank(tile: Int): Int = when {
    tile in 0..8 -> tile               // 万
    tile in 18..26 -> 100 + (tile - 18) // 条
    tile in 9..17 -> 200 + (tile - 9)   // 筒
    else -> 300 + (tile - 27)           // 字牌
}

fun sortHand(tiles: List<Int>): List<Int> = tiles.sortedBy { tileDisplayRank(it) }

fun GameResult.toSettlement(
    seats: List<SeatUi>,
    meSeat: Int,
    baseScore: Int,
    modeLabel: String,
    roundWind: Int,
    handNumber: Int,
    minFan: Int = 0,
    fanCap: Int? = null,
    flowerCountsTowardMin: Boolean = false,
    horseCount: Int = 0,
    horses: List<String> = emptyList(),
): SettlementUi {
    val winnersUi = winners.map { win ->
        WinnerUi(
            seat = win.winner,
            name = seats.firstOrNull { it.seat == win.winner }?.name ?: "玩家",
            selfDraw = win.selfDraw,
            fromSeat = win.fromPlayer,
            winningTile = win.winningTile,
            handTiles = sortHand(win.handTiles),
            melds = win.melds,
            fans = win.fanItems,
            totalFan = win.totalFan,
            thresholdFan = win.thresholdFan,
            points = win.points,
            robKong = win.robKong,
            horseHits = win.horseResults.size,
        )
    }
    return SettlementUi(
        draw = draw,
        title = if (draw) "荒庄流局" else "本局结算",
        subtitle = "${WindFullNames[roundWind]} · 第 $handNumber 局",
        reason = reason,
        winners = winnersUi,
        seats = seats,
        deltas = deltaScores,
        meSeat = meSeat,
        totalFan = winnersUi.maxOfOrNull { it.totalFan } ?: 0,
        baseScore = baseScore,
        modeLabel = modeLabel,
        minFan = minFan,
        fanCap = fanCap,
        flowerCountsTowardMin = flowerCountsTowardMin,
        horseCount = horseCount,
        horses = horses,
    )
}

fun WinResult.isSelfDraw(): Boolean = selfDraw

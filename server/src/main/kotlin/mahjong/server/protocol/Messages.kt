package mahjong.server.protocol

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- 连接

@Serializable
data class HelloBody(
    val deviceId: String,
    val nickname: String = "玩家",
)

@Serializable
data class HelloAckView(
    val playerId: String,
    val nickname: String,
    val serverTime: Long,
)

@Serializable
data class PingBody(val time: Long = 0)

@Serializable
data class PongView(val time: Long, val serverTime: Long)

@Serializable
data class ErrorView(val code: String, val message: String)

// ---------------------------------------------------------------- 房间

@Serializable
data class RoomCreateBody(
    val ruleSet: String = "MCR",
    val password: String? = null,
    /** 房主设置的 AI 思考时长（毫秒），0 表示沿用服务端节奏。 */
    val aiThinkMs: Long = 0L,
)

@Serializable
data class RoomJoinBody(
    val room: String,
    val password: String? = null,
)

@Serializable
data class RoomReadyBody(val ready: Boolean = true)

/** seat = -1 表示由服务端选择第一个空位。 */
@Serializable
data class SeatRefBody(val seat: Int = -1)

/**
 * 房主设置 AI 座位。
 *
 * - `seat`：0..3 指定座位；-1 表示自动取第一个空位。
 * - `level`：1..4，对应 core `AiLevel`（1 新手 / 2 普通 / 3 高手 / 4 大师）；0 表示用服务端默认（普通）。
 * - 指向真人座位时等价于「先移出该玩家再把座位设为 AI」。
 */
@Serializable
data class AiSeatBody(val seat: Int = -1, val level: Int = 0)

/** 房主移出玩家；reason 会原样展示给被踢者。 */
@Serializable
data class KickBody(val seat: Int, val reason: String? = null)

/** 被踢通知，先于连接关闭下发。 */
@Serializable
data class RoomKickedView(val reason: String = "", val bySeat: Int = -1)

@Serializable
data class SeatGrantedView(val seat: Int, val resumeToken: String)

@Serializable
data class SeatInfoView(
    val seat: Int,
    val kind: String,
    val playerId: String? = null,
    val nickname: String = "",
    val ready: Boolean = false,
    val online: Boolean = false,
    val aiLevel: Int = 0,
    val score: Int = 0,
)

@Serializable
data class RoomStateView(
    val room: String,
    val ruleSet: String,
    val hostSeat: Int,
    val phase: String,
    val seats: List<SeatInfoView>,
    val gameId: String? = null,
)

@Serializable
data class SeatOnlineView(val seat: Int, val online: Boolean)

@Serializable
data class RoomClosedView(val reason: String)

// ---------------------------------------------------------------- 重连

@Serializable
data class ReconnectBody(
    val resumeToken: String,
    val lastSseq: Long = 0,
)

@Serializable
data class SyncRequestBody(val lastSseq: Long = 0)

// ---------------------------------------------------------------- 对局

@Serializable
data class GameStartView(
    val gameId: String,
    val ruleSet: String,
    val seed: String,
    val dealer: Int,
    val roundWind: Int,
    val seatWind: Int,
    val initialHand: List<Int>,
    val scores: List<Int>,
    val wallRemaining: Int,
    val flowers: List<Int>,
)

@Serializable
data class MeldView(
    val type: String,
    val tiles: List<Int>,
    val claimedFrom: Int = -1,
    val claimedTile: Int = -1,
    val concealed: Boolean = false,
)

@Serializable
data class OpponentView(
    val seat: Int,
    val handCount: Int,
    val melds: List<MeldView>,
    val discards: List<Int>,
    val flowerCount: Int,
    val score: Int,
    val seatWind: Int,
)

@Serializable
data class LastDiscardView(
    val seat: Int,
    val tile: Int,
    val turn: Int,
)

@Serializable
data class LegalView(
    val actions: List<String>,
    val discardable: List<Int> = emptyList(),
)

@Serializable
data class WindowOptionView(
    val action: String,
    val choices: List<List<Int>> = emptyList(),
)

@Serializable
data class WindowView(
    val kind: String,
    val from: Int,
    val tile: Int,
    val deadline: Long,
    val remainingMs: Long,
    val options: List<WindowOptionView>,
)

@Serializable
data class StateView(
    val gameId: String,
    val turn: Int,
    val phase: String,
    val seat: Int,
    val hand: List<Int>,
    val melds: List<MeldView>,
    val flowers: List<Int>,
    val opponents: List<OpponentView>,
    val wallRemaining: Int,
    val dealer: Int,
    val roundWind: Int,
    val seatWind: Int,
    val currentPlayer: Int,
    val lastDiscard: LastDiscardView? = null,
    val legal: LegalView,
    val window: WindowView? = null,
)

@Serializable
data class EventView(
    val type: String,
    val seat: Int,
    val tile: Int? = null,
    val from: Int? = null,
    val tileCount: Int? = null,
    val kind: String? = null,
    val turn: Int? = null,
    val reason: String? = null,
    val handAfterCount: Int? = null,
    val points: Int? = null,
    val description: String? = null,
)

@Serializable
data class ActionBody(
    val action: String,
    val tile: Int? = null,
    val tiles: List<Int> = emptyList(),
    val claimed: Int? = null,
    val kind: String? = null,
)

@Serializable
data class ActionAckView(
    val sid: String,
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class FanView(
    val id: String,
    val name: String,
    val fan: Int,
    val count: Int,
)

@Serializable
data class WinnerView(
    val seat: Int,
    val from: Int? = null,
    val selfDraw: Boolean,
    val winningTile: Int,
    val fan: List<FanView>,
    val totalFan: Int,
    val points: Int,
    val hand: List<Int>,
    val melds: List<MeldView> = emptyList(),
)

@Serializable
data class GameOverView(
    val gameId: String,
    val draw: Boolean,
    val reason: String,
    val winners: List<WinnerView>,
    val deltaScores: List<Int>,
    val scores: List<Int>,
    val dealer: Int,
    val roundWind: Int,
    val dealerContinues: Boolean,
)

@Serializable
data class SyncView(
    val serverTime: Long,
    val room: RoomStateView,
    val state: StateView? = null,
    val window: WindowView? = null,
    val lastSseq: Long,
    val autoPlaying: Boolean,
)

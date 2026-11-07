package mahjong.core.api

import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.model.DiscardInfo
import mahjong.core.model.GameEvent
import mahjong.core.model.GameResult
import mahjong.core.model.GameState
import mahjong.core.model.Meld
import mahjong.core.model.Phase
import mahjong.core.model.PlayerView
import mahjong.core.rules.RuleConfig

/**
 * 面向界面层的稳定快照层。
 *
 * 约定：此文件中的公开类型与函数签名一经发布保持向后兼容；
 * 界面只消费 [GameSnapshot]、[PlayerView]、[GameEvent] 与 [ActionStep]。
 */
data class PlayerSnapshot(
    val seat: Int,
    val handCount: Int,
    val melds: List<Meld>,
    val discards: List<Int>,
    val flowers: List<Int>,
    val score: Int,
    val seatWind: Int,
)

/** 不含任何玩家手牌的公开快照，可安全地发给任意客户端。 */
data class GameSnapshot(
    val turn: Int,
    val phase: Phase,
    val currentPlayer: Int,
    val dealer: Int,
    val roundWind: Int,
    val dealerStreak: Int,
    val wallRemaining: Int,
    val players: List<PlayerSnapshot>,
    val lastDiscard: DiscardInfo?,
    val lastDrawnTile: Int?,
    val canSelfDrawWinSeats: Set<Int>,
    val pendingClaimSeats: List<Int>,
    val pendingRobKongSeats: List<Int>,
    val result: GameResult?,
) {
    companion object {
        fun of(state: GameState): GameSnapshot = GameSnapshot(
            turn = state.turn,
            phase = state.phase,
            currentPlayer = state.currentPlayer,
            dealer = state.dealer,
            roundWind = state.roundWind,
            dealerStreak = state.dealerStreak,
            wallRemaining = state.wallRemaining,
            players = state.players.map {
                PlayerSnapshot(
                    seat = it.seat,
                    handCount = it.hand.size,
                    melds = it.melds,
                    discards = it.discards,
                    flowers = it.flowers,
                    score = it.score,
                    seatWind = it.seatWind,
                )
            },
            lastDiscard = state.lastDiscard,
            lastDrawnTile = state.lastDrawnTile,
            canSelfDrawWinSeats = state.canSelfDrawWinSeats,
            pendingClaimSeats = state.pendingClaimSeats,
            pendingRobKongSeats = state.pendingRobKongSeats,
            result = state.result,
        )
    }
}

/** 一步动作及其产生的事件，界面按 [events] 顺序播放动画即可。 */
data class ActionStep(
    val turn: Int,
    val action: Action,
    val events: List<GameEvent>,
)

/**
 * 牌局会话：界面唯一需要持有的入口。
 *
 * 典型用法：
 * 1. 读 [snapshot] 渲染桌面，读 [viewFor] 渲染自己的手牌；
 * 2. 用 [legalActions] 决定可点的按钮；
 * 3. 玩家操作后调用 [submit]，把返回的事件交给动画层；
 * 4. 结束后从 [result] 或 [snapshot] 取结算。
 */
class GameSession(
    val config: RuleConfig,
    val seed: Long,
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val dealerStreak: Int = 0,
    initialScores: List<Int>? = null,
    /** 测试/回放用：预设牌墙，不参与洗牌。 */
    val wallTiles: List<Int>? = null,
) {
    private val game = MahjongGame(
        config = config,
        seed = seed,
        dealer = dealer,
        roundWind = roundWind,
        dealerStreak = dealerStreak,
        initialScores = initialScores,
        wallTiles = wallTiles,
    )
    private val initialScores = initialScores?.toList()
    private val steps = mutableListOf<ActionStep>()

    val snapshot: GameSnapshot get() = GameSnapshot.of(game.state)
    val actor: Int? get() = game.actor
    val isOver: Boolean get() = game.isOver
    val result: GameResult? get() = game.gameResult

    fun viewFor(seat: Int): PlayerView = game.viewFor(seat)

    fun legalActions(seat: Int): List<Action> = game.legalActions(seat)

    /** 取出开局以来尚未消费的事件（发牌等）。 */
    fun drainEvents(): List<GameEvent> = game.drainEvents()

    /** 执行动作，记录到动作流并返回本步产生的事件。 */
    fun submit(action: Action): List<GameEvent> {
        val events = game.apply(action)
        steps += ActionStep(turn = game.state.turn, action = action, events = events)
        return events
    }

    /** 已执行的动作流（可直接序列化后用于观战/回放）。 */
    fun actionLog(): List<ActionStep> = steps.toList()

    /** 导出可重放的对局记录。 */
    fun toReplay(): GameReplay = GameReplay(
        config = config,
        seed = seed,
        dealer = dealer,
        roundWind = roundWind,
        dealerStreak = dealerStreak,
        initialScores = initialScores,
        wallTiles = wallTiles,
        actions = steps.map { it.action },
    )
}

/** 可序列化的对局记录；同一记录重放必然得到同一结果。 */
data class GameReplay(
    val config: RuleConfig,
    val seed: Long,
    val dealer: Int,
    val roundWind: Int,
    val dealerStreak: Int,
    val initialScores: List<Int>?,
    val wallTiles: List<Int>?,
    val actions: List<Action>,
) {
    fun start(): GameSession = GameSession(
        config = config,
        seed = seed,
        dealer = dealer,
        roundWind = roundWind,
        dealerStreak = dealerStreak,
        initialScores = initialScores,
        wallTiles = wallTiles,
    )

    /**
     * 按记录重放。[onStep] 每步回调一次，可用于驱动界面动画；
     * 返回重放完成后的会话。
     */
    fun replay(onStep: (ActionStep) -> Unit = {}): GameSession {
        val session = start()
        for (action in actions) {
            val events = session.submit(action)
            onStep(ActionStep(turn = session.snapshot.turn, action = action, events = events))
        }
        return session
    }
}

package mahjong.core.model

import mahjong.core.score.WinResult

sealed interface GameEvent {
    val turn: Int

    data class HandStarted(
        override val turn: Int,
        val dealer: Int,
        val roundWind: Int,
        val wallCount: Int,
    ) : GameEvent

    data class Dealt(
        override val turn: Int,
        val seat: Int,
        val tileCount: Int,
    ) : GameEvent

    data class TileDrawn(
        override val turn: Int,
        val seat: Int,
        val tile: Int,
        val fromTail: Boolean,
        val kongReplacement: Boolean,
        val flowerReplacement: Boolean,
    ) : GameEvent

    data class FlowerRevealed(
        override val turn: Int,
        val seat: Int,
        val tile: Int,
    ) : GameEvent

    data class TileDiscarded(
        override val turn: Int,
        val seat: Int,
        val tile: Int,
        val lastTileOfWall: Boolean,
    ) : GameEvent

    data class MeldFormed(
        override val turn: Int,
        val seat: Int,
        val meld: Meld,
    ) : GameEvent

    data class KongDeclared(
        override val turn: Int,
        val seat: Int,
        val tile: Int,
        val meldType: MeldType,
        val robbed: Boolean,
    ) : GameEvent

    data class ClaimWindowOpened(
        override val turn: Int,
        val discarder: Int,
        val tile: Int,
        val claimants: List<Int>,
    ) : GameEvent

    data class RobKongWindowOpened(
        override val turn: Int,
        val kongPlayer: Int,
        val tile: Int,
        val claimants: List<Int>,
    ) : GameEvent

    data class GangScored(
        override val turn: Int,
        val seat: Int,
        val points: Int,
        val description: String,
    ) : GameEvent

    data class WinDeclared(
        override val turn: Int,
        val result: WinResult,
    ) : GameEvent

    data class DrawGame(
        override val turn: Int,
        val reason: String,
    ) : GameEvent

    data class GameOver(
        override val turn: Int,
        val result: GameResult,
    ) : GameEvent
}
